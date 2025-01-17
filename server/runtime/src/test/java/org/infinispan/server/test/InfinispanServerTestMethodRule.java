package org.infinispan.server.test;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServerConnection;

import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.RemoteCounterManagerFactory;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.rest.RestClient;
import org.infinispan.client.rest.RestResponse;
import org.infinispan.client.rest.configuration.RestClientConfigurationBuilder;
import org.infinispan.commons.configuration.BasicConfiguration;
import org.infinispan.commons.util.Util;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.counter.api.CounterManager;
import org.infinispan.test.Exceptions;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import net.spy.memcached.MemcachedClient;

/**
 * @author Tristan Tarrant &lt;tristan@infinispan.org&gt;
 * @since 10.0
 **/
public class InfinispanServerTestMethodRule implements TestRule {
   private final InfinispanServerRule infinispanServerRule;
   private String methodName;
   private List<Closeable> resources;

   public InfinispanServerTestMethodRule(InfinispanServerRule infinispanServerRule) {
      assert infinispanServerRule != null;
      this.infinispanServerRule = infinispanServerRule;
   }

   public <T extends Closeable> T registerResource(T resource) {
      resources.add(resource);
      return resource;
   }

   @Override
   public Statement apply(Statement base, Description description) {
      return new Statement() {
         @Override
         public void evaluate() throws Throwable {
            before();
            try {
               methodName = description.getTestClass().getSimpleName() + "." + description.getMethodName();
               base.evaluate();
            } finally {
               after();
            }
         }
      };
   }

   private void before() {
      resources = new ArrayList<>();
   }

   private void after() {
      resources.forEach(closeable -> Util.close(closeable));
      resources.clear();
   }

   public String getMethodName() {
      return methodName;
   }

   public RemoteCache<String, String> getHotRodCache(ConfigurationBuilder clientConfigurationBuilder) {
      RemoteCacheManager remoteCacheManager = registerResource(infinispanServerRule.newHotRodClient(clientConfigurationBuilder));
      return remoteCacheManager.getCache(methodName);
   }

   public <K, V> RemoteCache<K, V> getHotRodCache(CacheMode mode) {
      return getHotRodCache(new ConfigurationBuilder(), mode);
   }

   public <K, V> RemoteCache<K, V> getHotRodCache(ConfigurationBuilder clientConfigurationBuilder, CacheMode cacheMode) {
      RemoteCacheManager remoteCacheManager = registerResource(infinispanServerRule.newHotRodClient(clientConfigurationBuilder));
      return remoteCacheManager.administration().getOrCreateCache(methodName, "org.infinispan." + cacheMode.name());
   }

   public <K, V> RemoteCache<K, V> getHotRodCache(ConfigurationBuilder clientConfigurationBuilder, BasicConfiguration cacheConfiguration) {
      RemoteCacheManager remoteCacheManager = registerResource(infinispanServerRule.newHotRodClient(clientConfigurationBuilder));
      return remoteCacheManager.administration().getOrCreateCache(methodName, cacheConfiguration);
   }

   public RestClient getRestClient(CacheMode mode) {
      return getRestClient(new RestClientConfigurationBuilder(), mode);
   }

   public RestClient getRestClient(RestClientConfigurationBuilder clientConfigurationBuilder, CacheMode mode) {
      RestClient restClient = registerResource(infinispanServerRule.newRestClient(clientConfigurationBuilder));
      RestResponse response = Exceptions.unchecked(() -> restClient.cache(methodName).createWithTemplate("org.infinispan." + mode.name()).toCompletableFuture().get(5, TimeUnit.SECONDS));
      if (response.getStatus() != 200) {
         throw new RuntimeException("Could not create cache " + methodName + ", status = " + response.getStatus());
      }
      return restClient;
   }

   public CounterManager getCounterManager() {
      RemoteCacheManager remoteCacheManager = registerResource(infinispanServerRule.newHotRodClient());
      return RemoteCounterManagerFactory.asCounterManager(remoteCacheManager);
   }

   public MemcachedClient getMemcachedClient() {
      return registerResource(infinispanServerRule.newMemcachedClient()).getClient();
   }

   public MBeanServerConnection getJmxConnection(int server) {
      return infinispanServerRule.getServerDriver().getJmxConnection(server);
   }
}
