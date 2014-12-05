/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.streamer;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.configuration.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.streamer.*;
import org.apache.ignite.streamer.window.*;
import org.gridgain.grid.spi.discovery.tcp.*;
import org.gridgain.grid.spi.discovery.tcp.ipfinder.*;
import org.gridgain.grid.spi.discovery.tcp.ipfinder.vm.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.testframework.junits.common.*;
import org.jdk8.backport.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 *
 */
public class GridStreamerFailoverSelfTest extends GridCommonAbstractTest {
    /** IP finder. */
    private static final TcpDiscoveryIpFinder IP_FINDER = new TcpDiscoveryVmIpFinder(true);

    /** Event router. */
    private TestRandomRouter router;

    /** Maximum number of concurrent sessions for test. */
    private int maxConcurrentSess;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        cfg.setStreamerConfiguration(streamerConfiguration());

        TcpDiscoverySpi discoSpi = new TcpDiscoverySpi();

        discoSpi.setIpFinder(IP_FINDER);

        cfg.setDiscoverySpi(discoSpi);

        cfg.setPeerClassLoadingEnabled(false);

        return cfg;
    }

    /**
     * @return Streamer configuration.
     */
    private StreamerConfiguration streamerConfiguration() {
        StreamerConfiguration cfg = new StreamerConfiguration();

        cfg.setAtLeastOnce(true);

        cfg.setRouter(router);

        StreamerBoundedSizeWindow window = new StreamerBoundedSizeWindow();

        window.setMaximumSize(100);

        cfg.setWindows(F.asList((StreamerWindow)window));

        cfg.setMaximumConcurrentSessions(maxConcurrentSess);

        SC pass = new SC() {
            @SuppressWarnings("unchecked")
            @Override public Map<String, Collection<?>> applyx(String stageName, StreamerContext ctx,
                Collection<Object> objects) {
                assert ctx.nextStageName() != null;

                // Pass to next stage.
                return (Map)F.asMap(ctx.nextStageName(), objects);
            }
        };

        SC put = new SC() {
            @Override public Map<String, Collection<?>> applyx(String stageName, StreamerContext ctx,
                Collection<Object> evts) {
                ConcurrentMap<Object, AtomicInteger> cntrs = ctx.localSpace();

                for (Object evt : evts) {
                    AtomicInteger cnt = cntrs.get(evt);

                    if (cnt == null)
                        cnt = F.addIfAbsent(cntrs, evt, new AtomicInteger());

                    cnt.incrementAndGet();
                }

                return null;
            }
        };

        cfg.setStages(F.asList((StreamerStage)new GridTestStage("pass", pass), new GridTestStage("put", put)));

        return cfg;
    }

    /**
     * @throws Exception If failed.
     */
    public void testEventFailover() throws Exception {
        checkEventFailover(500);
    }

    /**
     * @throws Exception If failed.
     */
    private void checkEventFailover(int max) throws Exception {
        router = new TestRandomRouter();
        maxConcurrentSess = max;

        startGrids(6);

        try {
            router.sourceNodeId(grid(0).localNode().id());
            router.destinationNodeId(grid(5).localNode().id());

            final AtomicBoolean done = new AtomicBoolean(false);

            IgniteFuture<?> fut = multithreadedAsync(new Callable<Object>() {
                @Override public Object call() throws Exception {
                    Random rnd = new Random();

                    while (!done.get()) {
                        // Pick a random grid to restart.
                        int idx = rnd.nextInt(4) + 1;

                        info(">>>>> Stopping grid " + grid(idx).localNode().id());

                        stopGrid(idx, true);

                        U.sleep(1000);

                        startGrid(idx);

                        info(">>>>>> Started grid " + grid(idx).localNode().id());

                        U.sleep(500);
                    }

                    return null;
                }
            }, 1);

            final Collection<Object> failed = new ConcurrentLinkedQueue<>();

            IgniteStreamer streamer = grid(0).streamer(null);

            streamer.addStreamerFailureListener(new StreamerFailureListener() {
                @Override public void onFailure(String stageName, Collection<Object> evts, Throwable err) {
                    info("Unable to failover events [stageName=" + stageName + ", err=" + err + ']');

                    failed.addAll(evts);
                }
            });

            final int evtsCnt = 300000;

            // Now we are ready to process events.
            for (int i = 0; i < evtsCnt; i++) {
                if (i > 0 && i % 10000 == 0)
                    info("Processed: " + i);

                streamer.addEvent(i);
            }

            done.set(true);

            fut.get();

            // Do not cancel and wait for all tasks to finish.
            G.stop(getTestGridName(0), false);

            ConcurrentMap<Integer, AtomicInteger> finSpace = grid(5).streamer(null).context().localSpace();

            for (int i = 0; i < evtsCnt; i++) {
                AtomicInteger cnt = finSpace.get(i);

                if (cnt == null) {
                    assertTrue("Missing counter for key both in result map and in failover failed map: " + i,
                        failed.contains(i));
                }
                else
                    assertTrue(cnt.get() > 0);
            }
        }
        finally {
            stopAllGrids();
        }
    }

    /**
     * Test random router.
     */
    private static class TestRandomRouter extends StreamerEventRouterAdapter {
        /** Source node ID. */
        private UUID srcNodeId;

        /** Destination node ID. */
        private UUID destNodeId;

        /** {@inheritDoc} */
        @Override public <T> ClusterNode route(StreamerContext ctx, String stageName, T evt) {
            if ("put".equals(stageName))
                return ctx.projection().node(destNodeId);

            // Route to random node different from srcNodeId.
            Collection<ClusterNode> nodes = ctx.projection().forPredicate(new P1<ClusterNode>() {
                @Override public boolean apply(ClusterNode n) {
                    return !srcNodeId.equals(n.id()) && !destNodeId.equals(n.id());
                }
            }).nodes();

            int idx = ThreadLocalRandom8.current().nextInt(nodes.size());

            int i = 0;

            Iterator<ClusterNode> iter = nodes.iterator();

            while (true) {
                if (!iter.hasNext())
                    iter = nodes.iterator();

                ClusterNode node = iter.next();

                if (idx == i++)
                    return node;
            }
        }

        /**
         * @param srcNodeId New source node ID.
         */
        public void sourceNodeId(UUID srcNodeId) {
            this.srcNodeId = srcNodeId;
        }

        /**
         * @param destNodeId New destination node ID.
         */
        public void destinationNodeId(UUID destNodeId) {
            this.destNodeId = destNodeId;
        }
    }
}
