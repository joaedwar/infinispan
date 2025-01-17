package org.infinispan.server.security.authentication;

import static org.infinispan.server.security.Common.HTTP_MECHS;
import static org.infinispan.server.security.Common.HTTP_PROTOCOLS;
import static org.infinispan.server.security.Common.sync;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.infinispan.client.rest.RestClient;
import org.infinispan.client.rest.RestResponse;
import org.infinispan.client.rest.configuration.Protocol;
import org.infinispan.client.rest.configuration.RestClientConfigurationBuilder;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.server.security.Common;
import org.infinispan.server.test.InfinispanServerRule;
import org.infinispan.server.test.InfinispanServerTestMethodRule;
import org.infinispan.server.test.category.Security;
import org.infinispan.test.Exceptions;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * @author Tristan Tarrant &lt;tristan@infinispan.org&gt;
 * @since 10.0
 **/

@RunWith(Parameterized.class)
@Category(Security.class)
public class RestAuthentication {

   @ClassRule
   public static InfinispanServerRule SERVERS = AuthenticationIT.SERVERS;

   @Rule
   public InfinispanServerTestMethodRule SERVER_TEST = new InfinispanServerTestMethodRule(SERVERS);

   private final Protocol protocol;
   private final String mechanism;

   @Parameterized.Parameters(name = "{1}({0})")
   public static Collection<Object[]> data() {
      List<Object[]> params = new ArrayList<>(Common.HTTP_MECHS.size() * Common.HTTP_PROTOCOLS.size());
      for (Protocol protocol : HTTP_PROTOCOLS) {
         for (Object[] mech : HTTP_MECHS) {
            params.add(new Object[]{protocol, mech[0]});
         }
      }
      return params;
   }

   public RestAuthentication(Protocol protocol, String mechanism) {
      this.protocol = protocol;
      this.mechanism = mechanism;
   }

   @Test
   public void testRestReadWrite() {
      RestClientConfigurationBuilder builder = new RestClientConfigurationBuilder();
      if (!mechanism.isEmpty()) {
         builder
               .protocol(protocol)
               .security().authentication()
               .mechanism(mechanism)
               .realm("default")
               .username("all_user")
               .password("all");
      }
      if (mechanism.isEmpty()) {
         Exceptions.expectException(RuntimeException.class, () -> SERVER_TEST.getRestClient(builder, CacheMode.DIST_SYNC));
      } else {
         RestClient client = SERVER_TEST.getRestClient(builder, CacheMode.DIST_SYNC);
         RestResponse response = sync(client.cache(SERVER_TEST.getMethodName()).post("k1", "v1"));
         assertEquals(200, response.getStatus());
         assertEquals(protocol, response.getProtocol());
         response = sync(client.cache(SERVER_TEST.getMethodName()).get("k1"));
         assertEquals(200, response.getStatus());
         assertEquals(protocol, response.getProtocol());
         assertEquals("v1", response.getBody());
      }
   }
}
