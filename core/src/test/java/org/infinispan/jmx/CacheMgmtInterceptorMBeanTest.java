package org.infinispan.jmx;

import static org.infinispan.test.TestingUtil.checkMBeanOperationParameterNaming;
import static org.infinispan.test.TestingUtil.getCacheObjectName;
import static org.infinispan.test.TestingUtil.k;
import static org.infinispan.test.TestingUtil.v;
import static org.testng.AssertJUnit.assertEquals;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.infinispan.AdvancedCache;
import org.infinispan.commons.jmx.PerThreadMBeanServerLookup;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.persistence.dummy.DummyInMemoryStoreConfigurationBuilder;
import org.infinispan.persistence.spi.AdvancedLoadWriteStore;
import org.infinispan.test.SingleCacheManagerTest;
import org.infinispan.test.TestingUtil;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import io.reactivex.exceptions.Exceptions;

/**
 * Test functionality in {@link org.infinispan.interceptors.CacheMgmtInterceptor}.
 *
 * @author Mircea.Markus@jboss.com
 * @author Galder Zamarreño
 */
@Test(groups = "functional", testName = "jmx.CacheMgmtInterceptorMBeanTest")
public class CacheMgmtInterceptorMBeanTest extends SingleCacheManagerTest {
   private ObjectName mgmtInterceptor;
   private MBeanServer server;
   AdvancedCache<?, ?> advanced;
   AdvancedLoadWriteStore loader;
   private static final String JMX_DOMAIN = CacheMgmtInterceptorMBeanTest.class.getSimpleName();

   @Override
   protected EmbeddedCacheManager createCacheManager() throws Exception {
      cacheManager = TestCacheManagerFactory.createCacheManagerEnforceJmxDomain(JMX_DOMAIN);

      ConfigurationBuilder configuration = getDefaultStandaloneCacheConfig(false);
      configuration.memory().size(1)
              .persistence()
              .passivation(true)
              .addStore(DummyInMemoryStoreConfigurationBuilder.class);

      configuration.jmxStatistics().enable();
      cacheManager.defineConfiguration("test", configuration.build());
      cache = cacheManager.getCache("test");
      advanced = cache.getAdvancedCache();
      mgmtInterceptor = getCacheObjectName(JMX_DOMAIN, "test(local)", "Statistics");
      loader = (AdvancedLoadWriteStore) TestingUtil.getFirstLoader(cache);

      server = PerThreadMBeanServerLookup.getThreadMBeanServer();
      return cacheManager;
   }

   @AfterMethod
   public void resetStats() throws Exception {
      server.invoke(mgmtInterceptor, "resetStatistics", new Object[0], new String[0]);
   }

   public void testJmxOperationMetadata() throws Exception {
      checkMBeanOperationParameterNaming(mgmtInterceptor);
   }

   public void testEviction(Method m) throws Exception {
      assertEvictions(0);
      assert cache.get(k(m, "1")) == null;
      cache.put(k(m, "1"), v(m, 1));
      //test explicit evict command
      cache.evict(k(m, "1"));
      assert loader.contains(k(m, "1")) : "the entry should have been evicted";
      assertEvictions(1);
      assert cache.get(k(m, "1")).equals(v(m, 1));
      //test implicit eviction
      cache.put(k(m, "2"), v(m, 2));
      // Evictions of unrelated keys are non blocking now so it may not be updated immediately
      eventuallyAssertEvictions(2);
      assert loader.contains(k(m, "1")) : "the entry should have been evicted";
   }

   public void testGetKeyValue() throws Exception {
      assertMisses(0);
      assertHits(0);
      assert 0 == advanced.getStats().getHits();
      assertAttributeValue("HitRatio", 0);

      cache.put("key", "value");

      assertMisses(0);
      assertHits(0);
      assertAttributeValue("HitRatio", 0);

      assert cache.get("key").equals("value");
      assertMisses(0);
      assertHits(1);
      assertAttributeValue("HitRatio", 1);

      assert cache.get("key_ne") == null;
      assert cache.get("key_ne") == null;
      assert cache.get("key_ne") == null;
      assertMisses(3);
      assertHits(1);
      assertAttributeValue("HitRatio", 0.25f);
   }

   public void testStores() throws Exception {
      assertEvictions(0);
      assertStores(0);
      cache.put("key", "value");
      assertStores(1);
      cache.put("key", "value");
      assertStores(2);

      assertCurrentNumberOfEntries(1);
      cache.evict("key");
      assertCurrentNumberOfEntriesInMemory(0);
      assertCurrentNumberOfEntries(1);

      Map<String, String> toAdd = new HashMap<>();
      toAdd.put("key", "value");
      toAdd.put("key2", "value2");
      cache.putAll(toAdd);
      assertStores(4);
      TestingUtil.cleanUpDataContainerForCache(cache);
      assertCurrentNumberOfEntriesInMemory(1);
      assertCurrentNumberOfEntries(2);

      resetStats();

      toAdd = new HashMap<>();
      toAdd.put("key3", "value3");
      toAdd.put("key4", "value4");
      cache.putAll(toAdd);
      assertStores(2);
      TestingUtil.cleanUpDataContainerForCache(cache);
      assertCurrentNumberOfEntriesInMemory(1);
      eventuallyAssertEvictions(2);
      assertCurrentNumberOfEntries(4);
   }

   public void testStoresPutForExternalRead() throws Exception {
      assertStores(0);
      cache.putForExternalRead("key", "value");
      assertStores(1);
      cache.putForExternalRead("key", "value");
      assertStores(1);
   }

   public void testStoresPutIfAbsent() throws Exception {
      assertStores(0);
      cache.putIfAbsent("voooo", "doooo");
      assertStores(1);
      cache.putIfAbsent("voooo", "no-doooo");
      assertStores(1);
   }

   public void testRemoves() throws Exception {
      assertStores(0);
      assertRemoveHits(0);
      assertRemoveMisses(0);
      cache.put("key", "value");
      cache.put("key2", "value2");
      cache.put("key3", "value3");
      assertStores(3);
      assertRemoveHits(0);
      assertRemoveMisses(0);

      cache.remove("key");
      cache.remove("key3");
      cache.remove("key4");
      assertRemoveHits(2);
      assertRemoveMisses(1);

      cache.remove("key2");
      assertRemoveHits(3);
      assertRemoveMisses(1);
   }

   public void testGetAll() throws Exception {
      assertEquals(0, advanced.getStats().getMisses());
      assertEquals(0, advanced.getStats().getHits());
      String hitRatioString = server.getAttribute(mgmtInterceptor, "HitRatio").toString();
      Float hitRatio = Float.parseFloat(hitRatioString);
      assertEquals(0f, hitRatio);

      cache.put("key", "value");

      assertEquals(0, advanced.getStats().getMisses());
      assertEquals(0, advanced.getStats().getHits());
      hitRatioString = server.getAttribute(mgmtInterceptor, "HitRatio").toString();
      hitRatio = Float.parseFloat(hitRatioString);
      assertEquals(0f, hitRatio);

      Set<String> keySet = new HashSet<>();
      keySet.add("key");
      keySet.add("key1");
      advanced.getAll(keySet);
      assertEquals(1, advanced.getStats().getMisses());
      assertEquals(1, advanced.getStats().getHits());
      hitRatioString = server.getAttribute(mgmtInterceptor, "HitRatio").toString();
      hitRatio = Float.parseFloat(hitRatioString);
      assertEquals(0.5f, hitRatio);
   }

   private void eventuallyAssertAttributeValue(String attrName, float expectedValue) {
      eventuallyEquals(expectedValue, () -> {
         try {
            String receivedVal = server.getAttribute(mgmtInterceptor, attrName).toString();
            return Float.parseFloat(receivedVal);
         } catch (Exception e) {
            throw Exceptions.propagate(e);
         }
      });
   }

   private void assertAttributeValue(String attrName, float expectedValue) throws Exception {
      String receivedVal = server.getAttribute(mgmtInterceptor, attrName).toString();
      assert Float.parseFloat(receivedVal) == expectedValue : "expecting " + expectedValue + " for " + attrName + ", but received " + receivedVal;
   }

   private void eventuallyAssertEvictions(long expectedValue) {
      eventuallyAssertAttributeValue("Evictions", expectedValue);
      assertEquals(expectedValue, advanced.getStats().getEvictions());
   }

   private void assertEvictions(long expectedValue) throws Exception {
      assertAttributeValue("Evictions", expectedValue);
      assertEquals(expectedValue, advanced.getStats().getEvictions());
   }

   private void assertMisses(float expectedValue) throws Exception {
      assertAttributeValue("Misses", expectedValue);
      assert expectedValue == advanced.getStats().getMisses();
   }

   private void assertHits(float expectedValue) throws Exception {
      assertAttributeValue("Hits", expectedValue);
      assert expectedValue == advanced.getStats().getHits();
   }

   private void assertStores(float expectedValue) throws Exception {
      assertAttributeValue("Stores", expectedValue);
      assert expectedValue == advanced.getStats().getStores();
   }

   private void assertRemoveHits(float expectedValue) throws Exception {
      assertAttributeValue("RemoveHits", expectedValue);
      assert expectedValue == advanced.getStats().getRemoveHits();
   }

   private void assertRemoveMisses(float expectedValue) throws Exception {
      assertAttributeValue("RemoveMisses", expectedValue);
      assert expectedValue == advanced.getStats().getRemoveMisses();
   }

   private void assertCurrentNumberOfEntries(float expectedValue) throws Exception {
      assertAttributeValue("NumberOfEntries", expectedValue);
      assert expectedValue == advanced.getStats().getCurrentNumberOfEntries();
   }

   private void assertCurrentNumberOfEntriesInMemory(float expectedValue) throws Exception {
      assertAttributeValue("NumberOfEntriesInMemory", expectedValue);
      assert expectedValue == advanced.getStats().getCurrentNumberOfEntriesInMemory();
   }
}
