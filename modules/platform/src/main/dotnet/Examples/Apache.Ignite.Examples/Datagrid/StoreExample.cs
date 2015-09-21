/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

using System;
using System.Collections.Generic;
using Apache.Ignite.Core;
using Apache.Ignite.ExamplesDll.Datagrid;
using Apache.Ignite.ExamplesDll.Portable;

namespace Apache.Ignite.Examples.Datagrid
{
    /// <summary>
    /// Example demonstrating cache store.
    /// <para />
    /// 1) Build the project Apache.Ignite.ExamplesDll (select it -> right-click -> Build).
    ///    Apache.Ignite.ExamplesDll.dll must appear in %IGNITE_HOME%/platforms/dotnet/Examples/Apache.Ignite.ExamplesDll/bin/${Platform]/${Configuration} folder.
    /// 2) Set this class as startup object (Apache.Ignite.Examples project -> right-click -> Properties ->
    ///     Application -> Startup object);
    /// 3) Start example (F5 or Ctrl+F5).
    /// <para />
    /// This example can be run with standalone Apache Ignite .Net node:
    /// 1) Run %IGNITE_HOME%/platforms/dotnet/Apache.Ignite/bin/${Platform]/${Configuration}/Apache.Ignite.exe:
    /// Apache.Ignite.exe -IgniteHome="%IGNITE_HOME%" -springConfigUrl=platforms\dotnet\examples\config\example-cache-store.xml -assembly=[path_to_Apache.Ignite.ExamplesDll.dll]
    /// 2) Start example.
    /// </summary>
    class StoreExample
    {
        /// <summary>
        /// Runs the example.
        /// </summary>
        [STAThread]
        public static void Main()
        {
            var cfg = new IgniteConfiguration
            {
                SpringConfigUrl = @"platforms\dotnet\examples\config\example-cache-store.xml",
                JvmOptions = new List<string> { "-Xms512m", "-Xmx1024m" }
            };

            using (var ignite = Ignition.Start(cfg))
            {
                Console.WriteLine();
                Console.WriteLine(">>> Cache store example started.");

                var cache = ignite.GetCache<int, Employee>(null);

                // Clean up caches on all nodes before run.
                cache.Clear();

                Console.WriteLine();
                Console.WriteLine(">>> Cleared values from cache.");
                Console.WriteLine(">>> Current cache size: " + cache.GetSize());

                // Load entries from store which pass provided filter.
                cache.LoadCache(new EmployeeStorePredicate());

                Console.WriteLine();
                Console.WriteLine(">>> Loaded entry from store through ICache.LoadCache().");
                Console.WriteLine(">>> Current cache size: " + cache.GetSize());
                
                // Load entry from store calling ICache.Get() method.
                Employee emp = cache.Get(2);

                Console.WriteLine();
                Console.WriteLine(">>> Loaded entry from store through ICache.Get(): " + emp);
                Console.WriteLine(">>> Current cache size: " + cache.GetSize());

                // Put an entry to the cache
                cache.Put(3, new Employee(
                    "James Wilson",
                    12500,
                    new Address("1096 Eddy Street, San Francisco, CA", 94109),
                    new List<string> { "Human Resources", "Customer Service" }
                    ));

                Console.WriteLine();
                Console.WriteLine(">>> Put entry to cache. ");
                Console.WriteLine(">>> Current cache size: " + cache.GetSize());

                // Clear values again.
                cache.Clear();
                
                Console.WriteLine();
                Console.WriteLine(">>> Cleared values from cache again.");
                Console.WriteLine(">>> Current cache size: " + cache.GetSize());

                // Read values from cache after clear.
                Console.WriteLine();
                Console.WriteLine(">>> Read values after clear:");

                for (int i = 1; i <= 3; i++)
                    Console.WriteLine(">>>     Key=" + i + ", value=" + cache.Get(i));
            }

            Console.WriteLine();
            Console.WriteLine(">>> Example finished, press any key to exit ...");
            Console.ReadKey();
        }
    }
}