package org.infinispan.rest.resources;

import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.infinispan.commons.dataconversion.MediaType.APPLICATION_JSON;
import static org.infinispan.commons.dataconversion.MediaType.APPLICATION_JSON_TYPE;
import static org.infinispan.commons.dataconversion.MediaType.TEXT_PLAIN;
import static org.infinispan.rest.framework.Method.DELETE;
import static org.infinispan.rest.framework.Method.GET;
import static org.infinispan.rest.framework.Method.POST;

import java.lang.management.ManagementFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import org.infinispan.commons.util.JVMMemoryInfoInfo;
import org.infinispan.commons.util.Util;
import org.infinispan.commons.util.Version;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.rest.InvocationHelper;
import org.infinispan.rest.NettyRestResponse;
import org.infinispan.rest.framework.ResourceHandler;
import org.infinispan.rest.framework.RestRequest;
import org.infinispan.rest.framework.RestResponse;
import org.infinispan.rest.framework.impl.Invocations;
import org.infinispan.server.core.ServerManagement;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * @since 10.0
 */
public class ServerResource implements ResourceHandler {
   private final InvocationHelper invocationHelper;
   private static final int SHUTDOWN_DELAY_SECONDS = 3;
   private static final ServerInfo SERVER_INFO = new ServerInfo();

   public ServerResource(InvocationHelper invocationHelper) {
      this.invocationHelper = invocationHelper;
   }

   @Override
   public Invocations getInvocations() {
      return new Invocations.Builder()
            .invocation().methods(GET).path("/v2/server/").handleWith(this::info)
            .invocation().methods(GET).path("/v2/server/config").handleWith(this::config)
            .invocation().methods(GET).path("/v2/server/env").handleWith(this::env)
            .invocation().methods(GET).path("/v2/server/memory").handleWith(this::memory)
            .invocation().methods(GET).path("/v2/server/stop").handleWith(this::stop)
            .invocation().methods(GET).path("/v2/server/threads").handleWith(this::threads)
            .invocation().methods(GET).path("/v2/server/cache-managers").handleWith(this::cacheManagers)
            .invocation().methods(GET).path("/v2/server/ignored-caches/{cache-manager}").handleWith(this::listIgnored)
            .invocation().methods(POST, DELETE).path("/v2/server/ignored-caches/{cache-manager}/{cache}").handleWith(this::doIgnoreOp)
            .create();
   }

   private CompletionStage<RestResponse> doIgnoreOp(RestRequest restRequest) {
      NettyRestResponse.Builder builder = new NettyRestResponse.Builder();
      boolean add = restRequest.method().equals(POST);

      String cacheManagerName = restRequest.variables().get("cache-manager");
      DefaultCacheManager cacheManager = invocationHelper.getServer().getCacheManager(cacheManagerName);

      if (cacheManager == null) return completedFuture(builder.status(NOT_FOUND).build());

      String cacheName = restRequest.variables().get("cache");

      if (!cacheManager.getCacheNames().contains(cacheName)) {
         return completedFuture(builder.status(NOT_FOUND).build());
      }
      ServerManagement server = invocationHelper.getServer();

      if (add) {
         return server.ignoreCache(cacheManagerName, cacheName).thenApply(r -> builder.build());
      } else {
         return server.unIgnoreCache(cacheManagerName, cacheName).thenApply(r -> builder.build());
      }
   }

   private CompletionStage<RestResponse> listIgnored(RestRequest restRequest) {
      String cacheManagerName = restRequest.variables().get("cache-manager");
      DefaultCacheManager cacheManager = invocationHelper.getServer().getCacheManager(cacheManagerName);
      NettyRestResponse.Builder builder = new NettyRestResponse.Builder();

      if (cacheManager == null) return completedFuture(builder.status(NOT_FOUND).build());

      return invocationHelper.getServer().ignoredCaches(cacheManagerName).thenApply(ignored -> {
         try {
            byte[] resultBytes = invocationHelper.getMapper().writeValueAsBytes(ignored);
            builder.contentType(APPLICATION_JSON_TYPE).entity(resultBytes);
         } catch (JsonProcessingException e) {
            builder.status(HttpResponseStatus.INTERNAL_SERVER_ERROR).entity(e.getMessage());
         }
         return builder.build();
      });
   }

   private CompletionStage<RestResponse> cacheManagers(RestRequest restRequest) {
      return serializeObject(invocationHelper.getServer().cacheManagerNames());
   }

   private CompletionStage<RestResponse> memory(RestRequest restRequest) {
      return serializeObject(new JVMMemoryInfoInfo());
   }

   private CompletionStage<RestResponse> env(RestRequest restRequest) {
      return serializeObject(ManagementFactory.getRuntimeMXBean().getSystemProperties());
   }

   private CompletionStage<RestResponse> info(RestRequest restRequest) {
      return serializeObject(SERVER_INFO);
   }

   private CompletionStage<RestResponse> threads(RestRequest restRequest) {
      return completedFuture(new NettyRestResponse.Builder()
            .contentType(TEXT_PLAIN).entity(Util.threadDump())
            .build());
   }

   private CompletionStage<RestResponse> serializeObject(Object object) {
      NettyRestResponse.Builder responseBuilder = new NettyRestResponse.Builder();
      try {
         byte[] bytes = invocationHelper.getMapper().writeValueAsBytes(object);
         responseBuilder.contentType(APPLICATION_JSON).entity(bytes).status(OK);
      } catch (JsonProcessingException e) {
         responseBuilder.status(HttpResponseStatus.INTERNAL_SERVER_ERROR);
      }
      return completedFuture(responseBuilder.build());
   }

   private CompletionStage<RestResponse> stop(RestRequest restRequest) {
      invocationHelper.getScheduledExecutor().schedule(() -> invocationHelper.getServer().stop(), SHUTDOWN_DELAY_SECONDS, TimeUnit.SECONDS);
      return CompletableFuture.completedFuture(new NettyRestResponse.Builder().build());
   }

   private CompletionStage<RestResponse> config(RestRequest restRequest) {
      NettyRestResponse.Builder responseBuilder = new NettyRestResponse.Builder();
      String json = invocationHelper.getJsonWriter().toJSON(invocationHelper.getServer().getConfiguration());
      responseBuilder.entity(json).contentType(APPLICATION_JSON);
      return CompletableFuture.completedFuture(responseBuilder.build());
   }

   static class ServerInfo {
      final String version = Version.printVersion();

      public String getVersion() {
         return version;
      }
   }
}
