package org.infinispan.server.functional;

import static org.infinispan.commons.util.Eventually.eventually;
import static org.infinispan.server.security.Common.sync;

import java.net.ConnectException;

import org.infinispan.client.rest.RestClient;
import org.infinispan.commons.util.Util;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.server.test.InfinispanServerRule;
import org.infinispan.server.test.InfinispanServerTestConfiguration;
import org.infinispan.server.test.InfinispanServerTestMethodRule;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

/**
 * @since 10.0
 */
public class ShutdownRestIT {

   @ClassRule
   public static final InfinispanServerRule SERVER = new InfinispanServerRule(
         new InfinispanServerTestConfiguration("configuration/ClusteredServerTest.xml").numServers(1));

   @Rule
   public InfinispanServerTestMethodRule SERVER_TEST = new InfinispanServerTestMethodRule(SERVER);

   @Test
   public void testShutDown() {
      RestClient client = SERVER_TEST.getRestClient(CacheMode.DIST_SYNC);
      sync(client.server().stop());
      eventually(() -> {
         try {
            sync(client.server().configuration());
         } catch (RuntimeException r) {
            return (Util.getRootCause(r) instanceof ConnectException);
         }
         return false;
      });
   }
}
