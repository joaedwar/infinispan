package org.infinispan.rest.resources;

import static org.infinispan.commons.dataconversion.MediaType.TEXT_PLAIN_TYPE;
import static org.infinispan.configuration.cache.CacheMode.DIST_SYNC;
import static org.infinispan.configuration.cache.CacheMode.LOCAL;
import static org.infinispan.partitionhandling.PartitionHandling.DENY_READ_WRITES;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.infinispan.commons.configuration.JsonWriter;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ParserRegistry;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.rest.assertion.ResponseAssertion;
import org.testng.annotations.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

@Test(groups = "functional", testName = "rest.CacheManagerResourceTest")
public class CacheManagerResourceTest extends AbstractRestResourceTest {

   private Configuration cache1Config;
   private Configuration cache2Config;
   private ObjectMapper mapper = new ObjectMapper();
   private JsonWriter jsonWriter = new JsonWriter();

   @Override
   protected void defineCaches(EmbeddedCacheManager cm) {
      cache1Config = getCache1Config();
      cache2Config = getCache2Config();
      cm.defineConfiguration("cache1", cache1Config);
      cm.defineConfiguration("cache2", cache2Config);
   }

   private Configuration getCache1Config() {
      ConfigurationBuilder builder = new ConfigurationBuilder();
      builder.jmxStatistics().enable().clustering().cacheMode(DIST_SYNC).partitionHandling().whenSplit(DENY_READ_WRITES);
      return builder.build();
   }

   private Configuration getCache2Config() {
      ConfigurationBuilder builder = new ConfigurationBuilder();
      builder.jmxStatistics().enable().clustering().cacheMode(LOCAL).encoding().key().mediaType(TEXT_PLAIN_TYPE);
      return builder.build();
   }

   @Test
   public void testHealth() throws Exception {
      String url = String.format("http://localhost:%d/rest/v2/cache-managers/default/health", restServer().getPort());
      ContentResponse response = client.newRequest(url).send();
      ResponseAssertion.assertThat(response).isOk();

      JsonNode jsonNode = mapper.readTree(response.getContent());
      JsonNode clusterHealth = jsonNode.get("cluster_health");
      assertEquals(clusterHealth.get("health_status").asText(), "HEALTHY");
      assertEquals(clusterHealth.get("number_of_nodes").asInt(), 2);
      assertEquals(clusterHealth.get("node_names").size(), 2);

      ArrayNode cacheHealth = (ArrayNode) jsonNode.get("cache_health");
      List<String> cacheNames = extractCacheNames(cacheHealth);
      assertTrue(cacheNames.contains("cache1"));
      assertTrue(cacheNames.contains("cache2"));

      response = client.newRequest(url).method(HttpMethod.HEAD).send();
      ResponseAssertion.assertThat(response).isOk();
      ResponseAssertion.assertThat(response).hasNoContent();
   }

   @Test
   public void testCacheConfigs() throws Exception {
      String accept = "text/plain; q=0.9, application/json; q=0.6";
      String url = String.format("http://localhost:%d/rest/v2/cache-managers/default/cache-configs", restServer().getPort());
      ContentResponse response = client.newRequest(url).header("Accept", accept).send();
      ResponseAssertion.assertThat(response).isOk();

      String json = response.getContentAsString();
      JsonNode jsonNode = mapper.readTree(json);
      Map<String, String> cachesAndConfig = cacheAndConfig((ArrayNode) jsonNode);

      assertEquals(cachesAndConfig.get("cache1"), jsonWriter.toJSON(cache1Config));
      assertEquals(cachesAndConfig.get("cache2"), jsonWriter.toJSON(cache2Config));
   }

   @Test
   public void testGetGlobalConfig() throws Exception {
      String url = String.format("http://localhost:%d/rest/v2/cache-managers/default/config", restServer().getPort());
      ContentResponse response = client.newRequest(url).send();
      ResponseAssertion.assertThat(response).isOk();

      String json = response.getContentAsString();
      EmbeddedCacheManager embeddedCacheManager = cacheManagers.get(0);
      GlobalConfiguration globalConfiguration = embeddedCacheManager.getCacheManagerConfiguration();
      String globalConfigJSON = jsonWriter.toJSON(globalConfiguration);
      assertEquals(globalConfigJSON, json);
   }

   @Test
   public void testGetGlobalConfigXML() throws Exception {
      String url = String.format("http://localhost:%d/rest/v2/cache-managers/default/config", restServer().getPort());
      ContentResponse response = client.newRequest(url).header(HttpHeader.ACCEPT, MediaType.APPLICATION_XML_TYPE).send();
      ResponseAssertion.assertThat(response).isOk();


      String xml = response.getContentAsString();
      ParserRegistry parserRegistry = new ParserRegistry();
      ConfigurationBuilderHolder builderHolder = parserRegistry.parse(xml);

      GlobalConfigurationBuilder globalConfigurationBuilder = builderHolder.getGlobalConfigurationBuilder();
      assertNotNull(globalConfigurationBuilder.build());
   }

   @Test
   public void testInfo() throws Exception {
      String url = String.format("http://localhost:%d/rest/v2/cache-managers/default/", restServer().getPort());
      ContentResponse response = client.newRequest(url).send();
      ResponseAssertion.assertThat(response).isOk();

      String json = response.getContentAsString();
      JsonNode cmInfo = mapper.readTree(json);

      assertFalse(cmInfo.get("version").asText().isEmpty());
      assertEquals(2, cmInfo.get("cluster_members").size());
      assertEquals(2, cmInfo.get("cluster_members_physical_addresses").size());
   }

   @Test
   public void testStats() throws Exception {
      String url = String.format("http://localhost:%d/rest/v2/cache-managers/default/stats", restServer().getPort());
      ContentResponse response = client.newRequest(url).send();
      ResponseAssertion.assertThat(response).isOk();

      String json = response.getContentAsString();
      JsonNode cmStats = mapper.readTree(json);

      assertTrue(cmStats.get("statistics_enabled").asBoolean());
      assertEquals(0, cmStats.get("stores").asInt());
      assertEquals(0, cmStats.get("number_of_entries").asInt());

      cacheManagers.iterator().next().getCache("cache1").put("key", "value");
      cmStats = mapper.readTree(client.newRequest(url).send().getContentAsString());
      assertEquals(1, cmStats.get("stores").asInt());
      assertEquals(1, cmStats.get("number_of_entries").asInt());
   }

   private Map<String, String> cacheAndConfig(ArrayNode list) {
      Map<String, String> result = new HashMap<>();
      list.elements().forEachRemaining(node -> result.put(node.get("name").asText(), node.get("configuration").toString()));
      return result;
   }

   private List<String> extractCacheNames(ArrayNode cacheStatuses) {
      List<String> names = new ArrayList<>();
      cacheStatuses.elements().forEachRemaining(n -> names.add(n.get("cache_name").asText()));
      return names;
   }
}
