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

package org.apache.ignite.internal.processors.cache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.cache.CacheException;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.cache.CacheAtomicityMode;
import org.apache.ignite.cache.CacheExistsException;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.NearCacheConfiguration;
import org.apache.ignite.events.CacheEvent;
import org.apache.ignite.events.EventType;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.IgniteKernal;
import org.apache.ignite.internal.managers.discovery.GridDiscoveryManager;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.G;
import org.apache.ignite.internal.util.typedef.X;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgnitePredicate;
import org.apache.ignite.lang.IgniteUuid;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Test;

import static org.apache.ignite.events.EventType.EVT_CACHE_STARTED;
import static org.apache.ignite.events.EventType.EVT_CACHE_STOPPED;

/**
 * Test for dynamic cache start.
 */
@SuppressWarnings("unchecked")
public class IgniteDynamicCacheStartSelfTest extends GridCommonAbstractTest {
    /** */
    private static final String DYNAMIC_CACHE_NAME = "TestDynamicCache";

    /** */
    private static final String STATIC_CACHE_NAME = "TestStaticCache";

    /** */
    private static final String TEST_ATTRIBUTE_NAME = "TEST_ATTRIBUTE_NAME";

    /** */
    public static final IgnitePredicate<ClusterNode> NODE_FILTER = new IgnitePredicate<ClusterNode>() {
        /** {@inheritDoc} */
        @Override public boolean apply(ClusterNode n) {
            Boolean val = n.attribute(TEST_ATTRIBUTE_NAME);

            return val != null && val;
        }
    };

    /** */
    private boolean testAttribute = true;

    /**
     * @return Number of nodes for this test.
     */
    public int nodeCount() {
        return 3;
    }

    /**
     * {@inheritDoc}
     */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        cfg.setUserAttributes(F.asMap(TEST_ATTRIBUTE_NAME, testAttribute));

        CacheConfiguration cacheCfg = new CacheConfiguration(DEFAULT_CACHE_NAME);

        cacheCfg.setCacheMode(CacheMode.REPLICATED);

        cacheCfg.setName(STATIC_CACHE_NAME);

        cfg.setCacheConfiguration(cacheCfg);

        cfg.setIncludeEventTypes(EVT_CACHE_STARTED, EVT_CACHE_STOPPED, EventType.EVT_CACHE_NODES_LEFT);

        return cfg;
    }

    /**
     * {@inheritDoc}
     */
    @Override protected void beforeTestsStarted() throws Exception {
        startGridsMultiThreaded(nodeCount());
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testStartStopCacheMultithreadedSameNode() throws Exception {
        final IgniteEx kernal = grid(0);

        final Collection<IgniteInternalFuture<?>> futs = new ConcurrentLinkedDeque<>();

        int threadNum = 20;

        GridTestUtils.runMultiThreaded(new Callable<Object>() {
            @Override public Object call() throws Exception {
                CacheConfiguration ccfg = new CacheConfiguration(DEFAULT_CACHE_NAME);

                ccfg.setName(DYNAMIC_CACHE_NAME);

                futs.add(kernal.context().cache().dynamicStartCache(ccfg,
                    ccfg.getName(),
                    null,
                    true,
                    true,
                    true));

                return null;
            }
        }, threadNum, "cache-starter");

        assertEquals(threadNum, futs.size());

        int succeeded = 0;
        int failed = 0;

        for (IgniteInternalFuture<?> fut : futs) {
            try {
                fut.get();

                info("Succeeded: " + System.identityHashCode(fut));

                succeeded++;
            }
            catch (IgniteCheckedException e) {
                info(e.getMessage());

                failed++;
            }
        }

        assertEquals(1, succeeded);
        assertEquals(threadNum - 1, failed);

        futs.clear();

        GridTestUtils.runMultiThreaded(new Callable<Object>() {
            @Override public Object call() throws Exception {
                futs.add(kernal.context().cache().dynamicDestroyCache(DYNAMIC_CACHE_NAME, false, true, false, null));

                return null;
            }
        }, threadNum, "cache-stopper");

        assertEquals(threadNum, futs.size());

        for (IgniteInternalFuture<?> fut : futs)
            fut.get();
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testStartCacheMultithreadedDifferentNodes() throws Exception {
        final Collection<IgniteInternalFuture<?>> futs = new ConcurrentLinkedDeque<>();

        int threadNum = 20;

        GridTestUtils.runMultiThreaded(new Callable<Object>() {
            @Override public Object call() throws Exception {
                CacheConfiguration ccfg = new CacheConfiguration(DEFAULT_CACHE_NAME);

                ccfg.setName(DYNAMIC_CACHE_NAME);

                IgniteEx kernal = grid(ThreadLocalRandom.current().nextInt(nodeCount()));

                futs.add(kernal.context().cache().dynamicStartCache(ccfg,
                    ccfg.getName(),
                    null,
                    true,
                    true,
                    true));

                return null;
            }
        }, threadNum, "cache-starter");

        assertEquals(threadNum, futs.size());

        int succeeded = 0;
        int failed = 0;

        for (IgniteInternalFuture<?> fut : futs) {
            try {
                fut.get();

                info("Succeeded: " + System.identityHashCode(fut));

                succeeded++;
            }
            catch (IgniteCheckedException e) {
                info(e.getMessage());

                failed++;
            }
        }

        assertEquals(1, succeeded);
        assertEquals(threadNum - 1, failed);

        futs.clear();

        GridTestUtils.runMultiThreaded(new Callable<Object>() {
            @Override public Object call() throws Exception {
                IgniteEx kernal = grid(ThreadLocalRandom.current().nextInt(nodeCount()));

                futs.add(kernal.context().cache().dynamicDestroyCache(DYNAMIC_CACHE_NAME, false, true, false, null));

                return null;
            }
        }, threadNum, "cache-stopper");

        assertEquals(threadNum, futs.size());

        for (IgniteInternalFuture<?> fut : futs)
            fut.get();
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testStartStopCacheSimpleTransactional() throws Exception {
        checkStartStopCacheSimple(CacheAtomicityMode.TRANSACTIONAL);
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testStartStopCacheSimpleAtomic() throws Exception {
        checkStartStopCacheSimple(CacheAtomicityMode.ATOMIC);
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testStartStopCachesSimpleTransactional() throws Exception {
        checkStartStopCachesSimple(CacheAtomicityMode.TRANSACTIONAL);
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testStartStopCachesSimpleAtomic() throws Exception {
        checkStartStopCachesSimple(CacheAtomicityMode.ATOMIC);
    }

    /**
     * @param mode Cache atomicity mode.
     * @throws Exception If failed.
     */
    private void checkStartStopCacheSimple(CacheAtomicityMode mode) throws Exception {
        final IgniteEx kernal = grid(0);

        CacheConfiguration ccfg = new CacheConfiguration(DEFAULT_CACHE_NAME);
        ccfg.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);
        ccfg.setAtomicityMode(mode);

        ccfg.setName(DYNAMIC_CACHE_NAME);

        kernal.createCache(ccfg);

        for (int g = 0; g < nodeCount(); g++) {
            IgniteEx kernal0 = grid(g);

            info("Getting cache for node: " + g);

            assertNotNull(grid(g).cache(DYNAMIC_CACHE_NAME));
        }

        grid(0).cache(DYNAMIC_CACHE_NAME).put("1", "1");

        for (int g = 0; g < nodeCount(); g++)
            assertEquals("1", grid(g).cache(DYNAMIC_CACHE_NAME).get("1"));

        // Grab caches before stop.
        final IgniteCache[] caches = new IgniteCache[nodeCount()];

        for (int g = 0; g < nodeCount(); g++)
            caches[g] = grid(g).cache(DYNAMIC_CACHE_NAME);

        kernal.destroyCache(DYNAMIC_CACHE_NAME);

        awaitPartitionMapExchange();

        for (int g = 0; g < nodeCount(); g++) {
            final IgniteKernal kernal0 = (IgniteKernal)grid(g);

            final int idx = g;

            assertNull(kernal0.cache(DYNAMIC_CACHE_NAME));

            GridTestUtils.assertThrows(log, new Callable<Object>() {
                @Override public Object call() throws Exception {
                    return caches[idx].get("1");
                }
            }, IllegalStateException.class, null);
        }
    }

    /**
     * @param mode Cache atomicity mode.
     * @throws Exception If failed.
     */
    private void checkStartStopCachesSimple(CacheAtomicityMode mode) throws Exception {
        final IgniteEx kernal = grid(0);
        final int cacheCnt = 3;

        List<CacheConfiguration> ccfgList = new ArrayList<>();

        for (int i = 0; i < cacheCnt; i++) {
            CacheConfiguration ccfg = new CacheConfiguration(DEFAULT_CACHE_NAME);
            ccfg.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);
            ccfg.setAtomicityMode(mode);
            ccfg.setName(DYNAMIC_CACHE_NAME + Integer.toString(i));

            ccfgList.add(ccfg);
        }

        kernal.createCaches(ccfgList);

        for (int g = 0; g < nodeCount(); g++) {
            IgniteEx kernal0 = grid(g);

            info("Getting cache for node: " + g);

            for (int i = 0; i < cacheCnt; i++)
                assertNotNull(grid(g).cache(DYNAMIC_CACHE_NAME + Integer.toString(i)));
        }

        for (int i = 0; i < cacheCnt; i++)
            grid(0).cache(DYNAMIC_CACHE_NAME + Integer.toString(i)).put(Integer.toString(i), Integer.toString(i));

        for (int g = 0; g < nodeCount(); g++) {
            for (int i = 0; i < cacheCnt; i++) {
                assertEquals(
                    Integer.toString(i),
                    grid(g).cache(DYNAMIC_CACHE_NAME + Integer.toString(i)).get(Integer.toString(i))
                );
            }
        }

        // Grab caches before stop.
        final IgniteCache[] caches = new IgniteCache[nodeCount() * cacheCnt];

        for (int g = 0; g < nodeCount(); g++) {
            for (int i = 0; i < cacheCnt; i++)
                caches[g * nodeCount() + i] = grid(g).cache(DYNAMIC_CACHE_NAME + Integer.toString(i));
        }

        List<String> namesToDestroy = new ArrayList<>();

        for (int i = 0; i < cacheCnt; i++)
            namesToDestroy.add(DYNAMIC_CACHE_NAME + Integer.toString(i));

        kernal.destroyCaches(namesToDestroy);

        awaitPartitionMapExchange();

        for (int g = 0; g < nodeCount(); g++) {
            final IgniteKernal kernal0 = (IgniteKernal)grid(g);

            for (int i = 0; i < cacheCnt; i++) {
                final int idx = g * nodeCount() + i;
                final int expVal = i;

                assertNull(kernal0.cache(DYNAMIC_CACHE_NAME));

                GridTestUtils.assertThrows(log, new Callable<Object>() {
                    @Override public Object call() throws Exception {
                        return caches[idx].get(Integer.toString(expVal));
                    }
                }, IllegalStateException.class, null);
            }
        }
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testStartStopCacheAddNode() throws Exception {
        final IgniteEx kernal = grid(0);

        CacheConfiguration ccfg = new CacheConfiguration(DEFAULT_CACHE_NAME);
        ccfg.setCacheMode(CacheMode.REPLICATED);
        ccfg.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);

        ccfg.setName(DYNAMIC_CACHE_NAME);

        kernal.createCache(ccfg);

        info(">>>>>>> Deployed dynamic cache");

        startGrid(nodeCount());

        try {
            // Check that cache got deployed on new node.
            IgniteCache<Object, Object> cache = ignite(nodeCount()).cache(DYNAMIC_CACHE_NAME);

            cache.put("1", "1");

            for (int g = 0; g < nodeCount() + 1; g++) {
                assertEquals("1", grid(g).cache(DYNAMIC_CACHE_NAME).get("1"));

                Collection<ClusterNode> nodes = grid(g).affinity(DYNAMIC_CACHE_NAME).mapKeyToPrimaryAndBackups(0);

                assertEquals(nodeCount() + 1, nodes.size());
            }

            // Undeploy cache.
            kernal.destroyCache(DYNAMIC_CACHE_NAME);

            startGrid(nodeCount() + 1);

            awaitPartitionMapExchange();

            // Check that cache is not deployed on new node after undeploy.
            for (int g = 0; g < nodeCount() + 2; g++) {
                final IgniteKernal kernal0 = (IgniteKernal)grid(g);

                assertNull(kernal0.cache(DYNAMIC_CACHE_NAME));
            }
        }
        finally {
            stopGrid(nodeCount() + 1);
            stopGrid(nodeCount());
        }
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testDeployFilter() throws Exception {
        try {
            testAttribute = false;

            startGrid(nodeCount());

            final IgniteEx kernal = grid(0);

            CacheConfiguration ccfg = new CacheConfiguration(DEFAULT_CACHE_NAME);
            ccfg.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);

            ccfg.setName(DYNAMIC_CACHE_NAME);

            ccfg.setNodeFilter(NODE_FILTER);

            kernal.createCache(ccfg);

            startGrid(nodeCount() + 1);

            for (int i = 0; i < 100; i++)
                grid(0).cache(DYNAMIC_CACHE_NAME).put(i, i);

            for (int i = 0; i < 100; i++)
                assertEquals(i, grid(1).cache(DYNAMIC_CACHE_NAME).get(i));

            info("Affinity nodes: " + grid(0).affinity(DYNAMIC_CACHE_NAME).mapKeyToPrimaryAndBackups(0));

            for (int g = 0; g < nodeCount(); g++) {
                for (int i = 0; i < 100; i++) {
                    assertFalse(grid(g).affinity(DYNAMIC_CACHE_NAME).mapKeyToPrimaryAndBackups(i)
                        .contains(grid(nodeCount()).cluster().localNode()));

                    assertFalse(grid(g).affinity(DYNAMIC_CACHE_NAME).mapKeyToPrimaryAndBackups(i)
                        .contains(grid(nodeCount() + 1).cluster().localNode()));
                }
            }

            awaitPartitionMapExchange();

            // Check that cache is not deployed on new node after undeploy.
            for (int g = 0; g < nodeCount() + 2; g++) {
                final IgniteKernal kernal0 = (IgniteKernal)grid(g);

                if (g < nodeCount())
                    assertNotNull(grid(g).cache(DYNAMIC_CACHE_NAME));
                else
                    GridTestUtils.assertThrows(log, new Callable<Object>() {
                        @Override public Object call() throws Exception {
                            return kernal0.getCache(DYNAMIC_CACHE_NAME);
                        }
                    }, IllegalArgumentException.class, null);
            }

            kernal.destroyCache(DYNAMIC_CACHE_NAME);

            stopGrid(nodeCount() + 1);
            stopGrid(nodeCount());
        }
        finally {
            testAttribute = true;
        }
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testFailWhenConfiguredCacheExists() throws Exception {
        GridTestUtils.assertThrowsInherited(log, new Callable<Object>() {
            @Override public Object call() throws Exception {
                final Ignite kernal = grid(0);

                CacheConfiguration ccfg = new CacheConfiguration(DEFAULT_CACHE_NAME);
                ccfg.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);

                // Cache is already configured, should fail.
                ccfg.setName(STATIC_CACHE_NAME);

                ccfg.setNodeFilter(NODE_FILTER);

                return kernal.createCache(ccfg);
            }
        }, CacheExistsException.class, null);
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testFailWhenOneOfConfiguredCacheExists() throws Exception {
        GridTestUtils.assertThrowsInherited(log, new Callable<Object>() {
            @Override public Object call() throws Exception {
                final Ignite kernal = grid(0);

                CacheConfiguration ccfgDynamic = new CacheConfiguration(DEFAULT_CACHE_NAME);
                ccfgDynamic.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);

                ccfgDynamic.setName(DYNAMIC_CACHE_NAME);

                ccfgDynamic.setNodeFilter(NODE_FILTER);

                CacheConfiguration ccfgStatic = new CacheConfiguration(DEFAULT_CACHE_NAME);
                ccfgStatic.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);

                // Cache is already configured, should fail.
                ccfgStatic.setName(STATIC_CACHE_NAME);

                ccfgStatic.setNodeFilter(NODE_FILTER);

                return kernal.createCaches(F.asList(ccfgDynamic, ccfgStatic));
            }
        }, CacheExistsException.class, null);

        assertNull(grid(0).cache(DYNAMIC_CACHE_NAME));
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testClientCache() throws Exception {
        try {
            testAttribute = false;

            startGrid(nodeCount());

            final IgniteEx kernal = grid(0);

            CacheConfiguration ccfg = new CacheConfiguration(DEFAULT_CACHE_NAME);
            ccfg.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);

            ccfg.setName(DYNAMIC_CACHE_NAME);

            ccfg.setNodeFilter(NODE_FILTER);

            kernal.createCache(ccfg);

            GridTestUtils.assertThrows(log, new Callable<Object>() {
                @Override public Object call() throws Exception {
                    IgniteKernal ignite = (IgniteKernal)grid(nodeCount());

                    return ignite.getCache(DYNAMIC_CACHE_NAME);
                }
            }, IllegalArgumentException.class, null);

            // Should obtain client cache on new node.
            IgniteCache<Object, Object> clientCache = ignite(nodeCount()).cache(DYNAMIC_CACHE_NAME);

            clientCache.put("1", "1");

            for (int g = 0; g < nodeCount() + 1; g++)
                assertEquals("1", ignite(g).cache(DYNAMIC_CACHE_NAME).get("1"));

            kernal.destroyCache(DYNAMIC_CACHE_NAME);
        }
        finally {
            stopGrid(nodeCount());
        }
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testStartFromClientNode() throws Exception {
        try {
            testAttribute = false;

            startGrid(nodeCount());

            final IgniteEx kernal = grid(0);

            CacheConfiguration ccfg = new CacheConfiguration(DEFAULT_CACHE_NAME);
            ccfg.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);

            ccfg.setName(DYNAMIC_CACHE_NAME);

            ccfg.setNodeFilter(NODE_FILTER);

            final IgniteKernal started = (IgniteKernal)grid(nodeCount());

            started.createCache(ccfg);

            GridCacheAdapter<Object, Object> cache = started.internalCache(DYNAMIC_CACHE_NAME);

            assertNotNull(cache);
            assertFalse(cache.context().affinityNode());

            // Should obtain client cache on new node.
            IgniteCache<Object, Object> clientCache = ignite(nodeCount()).cache(DYNAMIC_CACHE_NAME);

            clientCache.put("1", "1");

            for (int g = 0; g < nodeCount() + 1; g++)
                assertEquals("1", ignite(g).cache(DYNAMIC_CACHE_NAME).get("1"));

            kernal.destroyCache(DYNAMIC_CACHE_NAME);
        }
        finally {
            stopGrid(nodeCount());
        }
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testStartNearCacheFromClientNode() throws Exception {
        try {
            testAttribute = false;

            startGrid(nodeCount());

            final IgniteEx kernal = grid(0);

            CacheConfiguration ccfg = new CacheConfiguration(DEFAULT_CACHE_NAME);
            ccfg.setWriteSynchronizationMode(CacheWriteSynchronizationMode.FULL_SYNC);

            ccfg.setName(DYNAMIC_CACHE_NAME);

            ccfg.setNodeFilter(NODE_FILTER);

            final IgniteKernal started = (IgniteKernal)grid(nodeCount());

            NearCacheConfiguration nearCfg = new NearCacheConfiguration();

            started.createCache(ccfg, nearCfg);

            GridCacheAdapter<Object, Object> cache = started.internalCache(DYNAMIC_CACHE_NAME);

            assertNotNull(cache);
            assertFalse(cache.context().affinityNode());
            assertTrue(cache.context().isNear());

            // Should obtain client cache on new node.
            IgniteCache<Object, Object> clientCache = ignite(nodeCount()).cache(DYNAMIC_CACHE_NAME);

            clientCache.put("1", "1");

            for (int g = 0; g < nodeCount() + 1; g++)
                assertEquals("1", ignite(g).cache(DYNAMIC_CACHE_NAME).get("1"));

            kernal.destroyCache(DYNAMIC_CACHE_NAME);
        }
        finally {
            stopGrid(nodeCount());
        }
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testEvents() throws Exception {
        CacheConfiguration cfg = new CacheConfiguration(DEFAULT_CACHE_NAME);

        cfg.setName(DYNAMIC_CACHE_NAME);
        cfg.setCacheMode(CacheMode.REPLICATED);

        // This cache will not fire any cache events.
        CacheConfiguration cfgEvtsDisabled = new CacheConfiguration(cfg);

        cfgEvtsDisabled.setName("DynamicCacheEvtsDisabled");
        cfgEvtsDisabled.setEventsDisabled(true);

        final CountDownLatch[] starts = new CountDownLatch[nodeCount()];
        final CountDownLatch[] stops = new CountDownLatch[nodeCount()];

        IgnitePredicate[] lsnrs = new IgnitePredicate[nodeCount()];

        for (int i = 0; i < nodeCount(); i++) {
            final int idx = i;

            starts[i] = new CountDownLatch(1);
            stops[i] = new CountDownLatch(1);

            lsnrs[i] = new IgnitePredicate<CacheEvent>() {
                @Override public boolean apply(CacheEvent e) {
                    switch (e.type()) {
                        case EVT_CACHE_STARTED:
                            starts[idx].countDown();

                            break;

                        case EVT_CACHE_STOPPED:
                            stops[idx].countDown();

                            break;

                        default:
                            assert false;
                    }

                    assertEquals(DYNAMIC_CACHE_NAME, e.cacheName());

                    return true;
                }
            };

            ignite(i).events().localListen(lsnrs[i], EventType.EVTS_CACHE_LIFECYCLE);
        }

        IgniteCache<Object, Object> cacheEvtsDisabled = ignite(0).createCache(cfgEvtsDisabled);
        IgniteCache<Object, Object> cache = ignite(0).createCache(cfg);

        try {
            for (CountDownLatch start : starts)
                start.await();
        }
        finally {
            cacheEvtsDisabled.destroy();
            cache.destroy();
        }

        for (CountDownLatch stop : stops)
            stop.await();

        for (int i = 0; i < nodeCount(); i++)
            ignite(i).events().stopLocalListen(lsnrs[i]);
    }

    /** */
    @Test
    public void testRemoteListenShouldGetCacheEventFromEveryNode() throws Exception {
        CountDownLatch[] evts = new CountDownLatch[] {new CountDownLatch(nodeCount())};
        int[] evtType = new int[] {EVT_CACHE_STARTED};

        UUID lsnrId = ignite(0).events().remoteListen((uuid, evt) -> {
            assertEquals(DYNAMIC_CACHE_NAME, ((CacheEvent)evt).cacheName());
            assertEquals(evt.type(), evtType[0]);

            evts[0].countDown();

            return true;
        }, evt -> true, EventType.EVTS_CACHE_LIFECYCLE);

        try {
            IgniteCache<Object, Object> cache = ignite(0).createCache(DYNAMIC_CACHE_NAME);

            evts[0].await(10, TimeUnit.SECONDS);

            evts[0] = new CountDownLatch(nodeCount());
            evtType[0] = EVT_CACHE_STOPPED;

            cache.destroy();

            evts[0].await(10, TimeUnit.SECONDS);
        }
        finally {
            ignite(0).events().stopRemoteListen(lsnrId);

            if (ignite(0).cache(DYNAMIC_CACHE_NAME) != null)
                ignite(0).cache(DYNAMIC_CACHE_NAME).destroy();
        }
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testNearNodesCache() throws Exception {
        try {
            testAttribute = false;

            Ignite ig = startGrid(nodeCount());

            CacheConfiguration ccfg = new CacheConfiguration(DEFAULT_CACHE_NAME);

            ccfg.setName(DYNAMIC_CACHE_NAME);
            ccfg.setCacheMode(CacheMode.PARTITIONED);
            ccfg.setNodeFilter(NODE_FILTER);

            IgniteCache cache = ig.createCache(ccfg, new NearCacheConfiguration());
            assertNotNull(cache);

            GridCacheAdapter<Object, Object> cacheAdapter =
                ((IgniteKernal)ig).internalCache(DYNAMIC_CACHE_NAME);

            assertNotNull(cacheAdapter);
            assertFalse(cacheAdapter.context().affinityNode());
            assertTrue(cacheAdapter.context().isNear());

            try {
                IgniteEx grid = startGrid(nodeCount() + 1);

                // Check that new node sees near node.
                GridDiscoveryManager disco = grid.context().discovery();

                assertTrue(disco.cacheNearNode(disco.node(ig.cluster().localNode().id()),
                    DYNAMIC_CACHE_NAME));
            }
            finally {
                cache.destroy();

                stopGrid(nodeCount() + 1);
            }
        }
        finally {
            stopGrid(nodeCount());
        }
    }

    /** */
    @Test
    public void testGetOrCreate() throws Exception {
        try {
            final CacheConfiguration cfg = new CacheConfiguration(DEFAULT_CACHE_NAME);

            cfg.setName(DYNAMIC_CACHE_NAME);
            cfg.setNodeFilter(NODE_FILTER);

            grid(0).getOrCreateCache(cfg);
            grid(0).getOrCreateCache(cfg);

            GridTestUtils.assertThrows(log, new Callable<Object>() {
                @Override public Object call() throws Exception {
                    return grid(0).getOrCreateCache(cfg, new NearCacheConfiguration());
                }
            }, CacheException.class, null);

            GridTestUtils.assertThrows(log, new Callable<Object>() {
                @Override public Object call() throws Exception {
                    return grid(0).getOrCreateNearCache(DYNAMIC_CACHE_NAME, new NearCacheConfiguration());
                }
            }, CacheException.class, null);

            testAttribute = false;

            startGrid(nodeCount());
            startGrid(nodeCount() + 1);

            try {
                IgniteEx nearGrid = grid(nodeCount());

                nearGrid.getOrCreateCache(cfg, new NearCacheConfiguration());
                nearGrid.getOrCreateNearCache(DYNAMIC_CACHE_NAME, new NearCacheConfiguration());

                GridCacheContext<Object, Object> nCtx = ((IgniteKernal)nearGrid)
                    .internalCache(DYNAMIC_CACHE_NAME).context();

                assertTrue(nCtx.isNear());
                assertFalse(nCtx.affinityNode());

                IgniteEx clientGrid = grid(nodeCount() + 1);

                clientGrid.getOrCreateCache(cfg);
                clientGrid.getOrCreateCache(cfg);

                GridCacheContext<Object, Object> cCtx = ((IgniteKernal)clientGrid)
                    .internalCache(DYNAMIC_CACHE_NAME).context();

                assertFalse(cCtx.isNear());
                assertFalse(cCtx.affinityNode());
            }
            finally {
                stopGrid(nodeCount() + 1);
                stopGrid(nodeCount());
            }
        }
        finally {
            grid(0).destroyCache(DYNAMIC_CACHE_NAME);
        }
    }

    /** */
    @Test
    public void testGetOrCreateCollection() throws Exception {
        final int cacheCnt = 3;

        try {
            final Collection<CacheConfiguration> ccfgs = new ArrayList<>();

            for (int i = 0; i < cacheCnt; i++) {
                final CacheConfiguration cfg = new CacheConfiguration(DEFAULT_CACHE_NAME);

                cfg.setName(DYNAMIC_CACHE_NAME + Integer.toString(i));
                cfg.setNodeFilter(NODE_FILTER);

                ccfgs.add(cfg);

                grid(0).getOrCreateCaches(ccfgs);
            }

            for (int i = 0; i < cacheCnt; i++) {
                assertNotNull(grid(0).cache(DYNAMIC_CACHE_NAME + Integer.toString(i)));

                IgniteCache<Object, Object> jcache = grid(0).cache(DYNAMIC_CACHE_NAME + Integer.toString(i));

                jcache.put(Integer.toString(i), Integer.toString(i));

                assertEquals(jcache.get(Integer.toString(i)), Integer.toString(i));
            }
        }
        finally {
            for (int i = 0; i < cacheCnt; i++)
                grid(0).destroyCache(DYNAMIC_CACHE_NAME + Integer.toString(i));
        }
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testGetOrCreateMultiNode() throws Exception {
        try {
            final AtomicInteger cnt = new AtomicInteger();
            final AtomicReference<Throwable> err = new AtomicReference<>();

            GridTestUtils.runMultiThreaded(new Callable<Object>() {
                @Override public Object call() throws Exception {
                    int idx = cnt.getAndIncrement();

                    try {
                        CacheConfiguration cfg = new CacheConfiguration(DYNAMIC_CACHE_NAME);

                        ignite(idx).getOrCreateCache(cfg);
                    }
                    catch (Exception e) {
                        err.compareAndSet(null, e);
                    }

                    return null;
                }
            }, nodeCount(), "starter");

            assertNull(err.get());

            for (int i = 0; i < nodeCount(); i++) {
                GridCacheContext<Object, Object> ctx = ((IgniteKernal)ignite(i)).internalCache(DYNAMIC_CACHE_NAME)
                    .context();

                assertTrue(ctx.affinityNode());
                assertFalse(ctx.isNear());
            }

            lightCheckDynamicCache();
        }
        finally {
            ignite(0).destroyCache(DYNAMIC_CACHE_NAME);
        }
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testGetOrCreateMultiNodeTemplate() throws Exception {
        final AtomicInteger idx = new AtomicInteger();

        GridTestUtils.runMultiThreaded(new Callable<Object>() {
            @Override public Object call() throws Exception {
                int idx0 = idx.getAndIncrement();

                ignite(idx0 % nodeCount()).getOrCreateCache(DYNAMIC_CACHE_NAME);

                return null;
            }
        }, nodeCount() * 4, "runner");

        ignite(0).destroyCache(DYNAMIC_CACHE_NAME);
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testGetOrCreateNearOnlyMultiNode() throws Exception {
        checkGetOrCreateNear(true);
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testGetOrCreateNearMultiNode() throws Exception {
        checkGetOrCreateNear(false);
    }

    /**
     * @param nearOnly Near only flag.
     * @throws Exception If failed.
     */
    private void checkGetOrCreateNear(final boolean nearOnly) throws Exception {
        try {
            final AtomicInteger cnt = new AtomicInteger(nodeCount());
            final AtomicReference<Throwable> err = new AtomicReference<>();

            final int clientCnt = 2;

            try {
                testAttribute = false;

                for (int i = 0; i < clientCnt; i++)
                    startGrid(nodeCount() + i);

                cnt.set(nodeCount());

                final CacheConfiguration<Object, Object> cacheCfg = new CacheConfiguration<>(DYNAMIC_CACHE_NAME);
                cacheCfg.setNodeFilter(NODE_FILTER);

                if (nearOnly)
                    ignite(0).createCache(cacheCfg);

                GridTestUtils.runMultiThreaded(new Callable<Object>() {
                    @Override public Object call() throws Exception {
                        int idx = cnt.getAndIncrement();

                        try {
                            if (nearOnly)
                                ignite(idx).getOrCreateNearCache(DYNAMIC_CACHE_NAME, new NearCacheConfiguration<>());
                            else
                                ignite(idx).getOrCreateCache(cacheCfg, new NearCacheConfiguration<>());
                        }
                        catch (Exception ex) {
                            err.compareAndSet(null, ex);
                        }

                        return null;
                    }
                }, clientCnt, "starter");

                assertNull(err.get());

                for (int i = 0; i < nodeCount(); i++) {
                    GridCacheContext<Object, Object> ctx = ((IgniteKernal)ignite(i)).internalCache(DYNAMIC_CACHE_NAME)
                        .context();

                    assertTrue(ctx.affinityNode());
                    assertFalse(ctx.isNear());
                }

                for (int i = 0; i < clientCnt; i++) {
                    GridCacheContext<Object, Object> ctx = ((IgniteKernal)ignite(nodeCount() + i))
                        .internalCache(DYNAMIC_CACHE_NAME).context();

                    assertFalse(ctx.affinityNode());
                    assertTrue("Cache is not near for index: " + (nodeCount() + i), ctx.isNear());
                }

                lightCheckDynamicCache();
            }
            finally {
                for (int i = 0; i < clientCnt; i++)
                    stopGrid(nodeCount() + i);
            }
        }
        finally {
            ignite(0).destroyCache(DYNAMIC_CACHE_NAME);
        }
    }

    /**
     * @throws Exception If failed.
     */
    private void lightCheckDynamicCache() throws Exception {
        int nodes = F.size(G.allGrids());

        for (int i = 0; i < nodes; i++) {
            IgniteCache<Object, Object> jcache = ignite(i).cache(DYNAMIC_CACHE_NAME);

            for (int k = 0; k < 20; k++) {
                int key = i + k * nodes;

                jcache.put(key, key);
            }
        }

        for (int i = 0; i < nodes; i++) {
            IgniteCache<Object, Object> jcache = ignite(i).cache(DYNAMIC_CACHE_NAME);

            for (int k = 0; k < 20 * nodes; k++)
                assertEquals(k, jcache.get(k));
        }

        for (int i = 0; i < nodes; i++) {
            IgniteCache<Object, Object> jcache = ignite(i).cache(DYNAMIC_CACHE_NAME);

            for (int k = 0; k < 20; k++) {
                int key = i + k * nodes;

                assertEquals(key, jcache.getAndRemove(key));
            }
        }

        for (int i = 0; i < nodes; i++) {
            IgniteCache<Object, Object> jcache = ignite(i).cache(DYNAMIC_CACHE_NAME);

            for (int k = 0; k < 20 * nodes; k++)
                assertNull(jcache.get(k));
        }
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testServerNodesLeftEvent() throws Exception {
        testAttribute = false;

        startGrid(nodeCount());

        CacheConfiguration cfg = new CacheConfiguration(DYNAMIC_CACHE_NAME);

        cfg.setNodeFilter(F.not(NODE_FILTER));

        IgniteCache<Object, Object> cache = ignite(0).createCache(cfg);

        final CountDownLatch[] latches = new CountDownLatch[nodeCount()];

        IgnitePredicate[] lsnrs = new IgnitePredicate[nodeCount()];

        for (int i = 0; i < nodeCount(); i++) {
            final int idx = i;

            latches[i] = new CountDownLatch(1);
            lsnrs[i] = new IgnitePredicate<CacheEvent>() {
                @Override public boolean apply(CacheEvent e) {
                    switch (e.type()) {
                        case EventType.EVT_CACHE_NODES_LEFT:
                            latches[idx].countDown();

                            break;

                        default:
                            assert false;
                    }

                    assertEquals(DYNAMIC_CACHE_NAME, e.cacheName());

                    return true;
                }
            };

            ignite(i).events().localListen(lsnrs[i], EventType.EVTS_CACHE_LIFECYCLE);
        }

        stopGrid(nodeCount());

        for (CountDownLatch latch : latches)
            latch.await();

        for (int i = 0; i < nodeCount(); i++)
            ignite(i).events().stopLocalListen(lsnrs[i]);

        cache.destroy();
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testAwaitPartitionMapExchange() throws Exception {
        IgniteCache cache = grid(0).getOrCreateCache(new CacheConfiguration(DYNAMIC_CACHE_NAME));

        awaitPartitionMapExchange();

        startGrid(nodeCount());

        awaitPartitionMapExchange();

        startGrid(nodeCount() + 1);

        awaitPartitionMapExchange();

        stopGrid(nodeCount() + 1);

        awaitPartitionMapExchange();

        stopGrid(nodeCount());

        cache.destroy();
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testStartStopWithClientJoin() throws Exception {
        Ignite ignite1 = ignite(1);

        final AtomicBoolean stop = new AtomicBoolean();

        IgniteInternalFuture<?> fut = GridTestUtils.runMultiThreadedAsync(new Callable<Object>() {
            @Override public Object call() throws Exception {
                int iter = 0;

                while (!stop.get()) {
                    if (iter % 10 == 0)
                        log.info("Client start/stop iteration: " + iter);

                    iter++;

                    try (Ignite ignite = startClientGrid(nodeCount())) {
                        assertTrue(ignite.configuration().isClientMode());
                    }
                }

                return null;
            }
        }, 1, "client-start-stop");

        try {
            long stopTime = U.currentTimeMillis() + 30_000;

            int iter = 0;

            while (System.currentTimeMillis() < stopTime) {
                if (iter % 10 == 0)
                    log.info("Cache start/stop iteration: " + iter);

                IgniteCache<Object, Object> cache = ignite1.getOrCreateCache("cache-" + iter);

                assertNotNull(cache);

                cache.destroy();

                iter++;
            }
        }
        finally {
            stop.set(true);
        }

        fut.get();
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testStartStopSameCacheMultinode() throws Exception {
        final AtomicInteger idx = new AtomicInteger();

        IgniteInternalFuture<?> fut = GridTestUtils.runMultiThreadedAsync(new Callable<Object>() {
            @Override public Object call() throws Exception {
                int node = idx.getAndIncrement();

                Ignite ignite = ignite(node);

                Thread.currentThread().setName("start-stop-" + ignite.name());

                CacheConfiguration ccfg = new CacheConfiguration(DEFAULT_CACHE_NAME);

                ccfg.setName("testStartStop");

                for (int i = 0; i < 1000; i++) {
                    log.info("Start cache: " + i);

                    try (IgniteCache<Object, Object> cache = ignite.getOrCreateCache(ccfg)) {
                        // No-op.
                    }

                    log.info("Stopped cache: " + i);
                }

                return null;
            }
        }, nodeCount(), "start-stop-cache");

        fut.get();
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testCacheRestartIsAllowedOnlyToItsInititator() throws Exception {
        IgniteEx kernal = grid(ThreadLocalRandom.current().nextInt(nodeCount()));

        CacheConfiguration ccfg = new CacheConfiguration("testCacheRestartIsAllowedOnlyToItsInititator");

        kernal.createCache(ccfg);

        IgniteUuid restartId = IgniteUuid.randomUuid();

        kernal.context().cache().dynamicDestroyCache(ccfg.getName(), false, true, true, restartId)
                .get(getTestTimeout(), TimeUnit.MILLISECONDS);

        try {
            kernal.createCache(ccfg);

            fail();
        }
        catch (Exception e) {
            assertTrue(X.hasCause(e, CacheExistsException.class));

            System.out.println("User couldn't start new cache with the same name");
        }

        try {
            kernal.context().cache().dynamicStartCache(ccfg, ccfg.getName(), null, true, false, true).get();

            fail();
        }
        catch (Exception e) {
            assertTrue(X.hasCause(e, CacheExistsException.class));

            System.out.println("We couldn't start new cache with private API");
        }

        StoredCacheData storedCacheData = new StoredCacheData(ccfg);

        try {
            kernal.context().cache().dynamicStartCachesByStoredConf(
                Collections.singleton(storedCacheData),
                true,
                true,
                false,
                IgniteUuid.randomUuid()
            ).get();

            fail();
        }
        catch (Exception e) {
            assertTrue(X.hasCause(e, CacheExistsException.class));

            System.out.println("We couldn't start new cache with wrong restart id.");
        }

        kernal.context().cache().dynamicStartCachesByStoredConf(Collections.singleton(storedCacheData), true, true, false, restartId).get();

        System.out.println("We successfully restarted cache with initial restartId.");

        kernal.destroyCache(ccfg.getName());
    }
}
