package org.infinispan.rest.resources;

import static org.infinispan.rest.NettyRestRequest.EXTENDED_HEADER;
import static org.infinispan.rest.framework.Method.DELETE;
import static org.infinispan.rest.framework.Method.GET;
import static org.infinispan.rest.framework.Method.HEAD;
import static org.infinispan.rest.framework.Method.POST;
import static org.infinispan.rest.framework.Method.PUT;
import static org.infinispan.rest.resources.MediaTypeUtils.negotiateMediaType;

import java.util.Date;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.infinispan.AdvancedCache;
import org.infinispan.Cache;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.commons.hash.MurmurHash3;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.metadata.Metadata;
import org.infinispan.rest.CacheControl;
import org.infinispan.rest.DateUtils;
import org.infinispan.rest.InvocationHelper;
import org.infinispan.rest.NettyRestResponse;
import org.infinispan.rest.RestResponseException;
import org.infinispan.rest.cachemanager.RestCacheManager;
import org.infinispan.rest.configuration.RestServerConfiguration;
import org.infinispan.rest.framework.ContentSource;
import org.infinispan.rest.framework.ResourceHandler;
import org.infinispan.rest.framework.RestRequest;
import org.infinispan.rest.framework.RestResponse;
import org.infinispan.rest.framework.impl.Invocations;
import org.infinispan.rest.operations.CacheOperationsHelper;
import org.infinispan.rest.operations.exceptions.NoDataFoundException;
import org.infinispan.rest.operations.exceptions.NoKeyException;
import org.infinispan.rest.operations.mediatypes.Charset;
import org.infinispan.rest.operations.mediatypes.EntrySetFormatter;
import org.infinispan.rest.operations.mediatypes.OutputPrinter;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Handler for the cache resource.
 *
 * @since 10.0
 */
public class CacheResource implements ResourceHandler {

   private static final MurmurHash3 hashFunc = MurmurHash3.getInstance();

   final CacheResourceQueryAction queryAction;
   final InvocationHelper invocationHelper;

   public CacheResource(InvocationHelper invocationHelper) {
      this.invocationHelper = invocationHelper;
      this.queryAction = new CacheResourceQueryAction(invocationHelper);
   }

   @Override
   public Invocations getInvocations() {
      return new Invocations.Builder()
            .invocation().methods(PUT, POST).path("/{cacheName}/{cacheKey}").handleWith(this::putValueToCache)
            .invocation().methods(GET, HEAD).path("/{cacheName}/{cacheKey}").handleWith(this::getCacheValue)
            .invocation().method(DELETE).path("/{cacheName}/{cacheKey}").handleWith(this::deleteCacheValue)
            .invocation().method(DELETE).path("/{cacheName}").handleWith(this::clearEntireCache)
            .invocation().method(GET).path("/{cacheName}").handleWith(this::getCacheKeys)
            .invocation().methods(GET, POST).path("/{cacheName}").withAction("search").handleWith(queryAction::search)
            .create();
   }

   private CompletionStage<RestResponse> getCacheKeys(RestRequest request) throws RestResponseException {
      String cacheName = request.variables().get("cacheName");

      AdvancedCache<Object, Object> cache = invocationHelper.getRestCacheManager().getCache(cacheName, request);
      MediaType contentType = negotiateMediaType(cache, request);

      Charset mediaCharset = Charset.fromMediaType(contentType.toString());
      Charset charset = mediaCharset == null ? Charset.UTF8 : mediaCharset;
      return CompletableFuture.supplyAsync(() -> {
         NettyRestResponse.Builder responseBuilder = new NettyRestResponse.Builder();
         responseBuilder.contentType(contentType);
         responseBuilder.header(HttpHeaderNames.CACHE_CONTROL.toString(), CacheControl.noCache());
         OutputPrinter outputPrinter = EntrySetFormatter.forMediaType(contentType);
         responseBuilder.entity(outputPrinter.print(cacheName, cache.keySet(), charset));
         return responseBuilder.build();
      }, invocationHelper.getExecutor());
   }

   CompletionStage<RestResponse> deleteCacheValue(RestRequest request) throws RestResponseException {
      String cacheName = request.variables().get("cacheName");

      Object key = request.variables().get("cacheKey");
      if (key == null) throw new NoKeyException();

      MediaType keyContentType = request.keyContentType();
      RestCacheManager<Object> restCacheManager = invocationHelper.getRestCacheManager();
      AdvancedCache<Object, Object> cache = restCacheManager.getCache(cacheName, keyContentType, MediaType.MATCH_ALL, request);

      return restCacheManager.getPrivilegedInternalEntry(cache, key, true).thenCompose(entry -> {
         NettyRestResponse.Builder responseBuilder = new NettyRestResponse.Builder();
         responseBuilder.status(HttpResponseStatus.NOT_FOUND);

         if (entry instanceof InternalCacheEntry) {
            InternalCacheEntry<Object, Object> ice = (InternalCacheEntry<Object, Object>) entry;
            String etag = calcETAG(ice.getValue());
            String clientEtag = request.getEtagIfNoneMatchHeader();
            if (clientEtag == null || clientEtag.equals(etag)) {
               responseBuilder.status(HttpResponseStatus.OK.code());
               return restCacheManager.remove(cacheName, key, keyContentType, request).thenApply(v -> responseBuilder.build());
            } else {
               //ETags don't match, so preconditions failed
               responseBuilder.status(HttpResponseStatus.PRECONDITION_FAILED.code());
            }
         }
         return CompletableFuture.completedFuture(responseBuilder.build());
      });
   }

   CompletionStage<RestResponse> putValueToCache(RestRequest request) {

      String cacheName = request.variables().get("cacheName");

      MediaType contentType = request.contentType();
      MediaType keyContentType = request.keyContentType();
      RestCacheManager<Object> restCacheManager = invocationHelper.getRestCacheManager();
      AdvancedCache<Object, Object> cache = restCacheManager.getCache(cacheName, keyContentType, contentType, request);
      Object key = request.variables().get("cacheKey");
      if (key == null) throw new NoKeyException();
      NettyRestResponse.Builder responseBuilder = new NettyRestResponse.Builder();

      ContentSource contents = request.contents();
      if (contents == null) throw new NoDataFoundException();
      Long ttl = request.getTimeToLiveSecondsHeader();
      Long idle = request.getMaxIdleTimeSecondsHeader();

      byte[] data = request.contents().rawContent();

      return restCacheManager.getPrivilegedInternalEntry(cache, key, true).thenCompose(entry -> {
         if (request.method() == POST && entry != null) {
            return CompletableFuture.completedFuture(responseBuilder.status(HttpResponseStatus.CONFLICT.code()).entity("An entry already exists").build());
         }
         if (entry instanceof InternalCacheEntry) {
            InternalCacheEntry ice = (InternalCacheEntry) entry;
            String etagNoneMatch = request.getEtagIfNoneMatchHeader();
            if (etagNoneMatch != null) {
               String etag = calcETAG(ice.getValue());
               if (etagNoneMatch.equals(etag)) {
                  //client's and our ETAG match. Nothing to do, an entry is cached on the client side...
                  responseBuilder.status(HttpResponseStatus.NOT_MODIFIED.code());
                  return CompletableFuture.completedFuture(responseBuilder.build());
               }
            }
         }
         return putInCache(responseBuilder, cache, key, data, ttl, idle);
      });
   }

   CompletionStage<RestResponse> clearEntireCache(RestRequest request) throws RestResponseException {
      String cacheName = request.variables().get("cacheName");

      NettyRestResponse.Builder responseBuilder = new NettyRestResponse.Builder();
      responseBuilder.status(HttpResponseStatus.OK.code());

      Cache<Object, Object> cache = invocationHelper.getRestCacheManager().getCache(cacheName, request);

      return cache.clearAsync().thenApply(v -> responseBuilder.build());
   }


   CompletionStage<RestResponse> getCacheValue(RestRequest request) throws RestResponseException {
      String cacheName = request.variables().get("cacheName");

      MediaType keyContentType = request.keyContentType();
      AdvancedCache<?, ?> cache = invocationHelper.getRestCacheManager().getCache(cacheName, request);

      MediaType requestedMediaType = negotiateMediaType(cache, request);

      Object key = request.variables().get("cacheKey");
      if (key == null) throw new NoKeyException();

      String cacheControl = request.getCacheControlHeader();
      boolean returnBody = request.method() == GET;
      RestCacheManager<Object> restCacheManager = invocationHelper.getRestCacheManager();
      return restCacheManager.getInternalEntry(cacheName, key, keyContentType, requestedMediaType, request).thenApply(entry -> {
         NettyRestResponse.Builder responseBuilder = new NettyRestResponse.Builder();
         responseBuilder.status(HttpResponseStatus.NOT_FOUND.code());

         if (entry instanceof InternalCacheEntry) {
            InternalCacheEntry<Object, Object> ice = (InternalCacheEntry<Object, Object>) entry;
            long lastMod = CacheOperationsHelper.lastModified(ice);
            Date expires = ice.canExpire() ? new Date(ice.getExpiryTime()) : null;
            OptionalInt minFreshSeconds = CacheOperationsHelper.minFresh(cacheControl);
            if (CacheOperationsHelper.entryFreshEnough(expires, minFreshSeconds)) {
               Metadata meta = ice.getMetadata();
               String etag = calcETAG(ice.getValue());
               String ifNoneMatch = request.getEtagIfNoneMatchHeader();
               String ifMatch = request.getEtagIfMatchHeader();
               String ifUnmodifiedSince = request.getIfUnmodifiedSinceHeader();
               String ifModifiedSince = request.getIfModifiedSinceHeader();
               if (ifNoneMatch != null && ifNoneMatch.equals(etag)) {
                  return responseBuilder.status(HttpResponseStatus.NOT_MODIFIED).build();
               }
               if (ifMatch != null && !ifMatch.equals(etag)) {
                  return responseBuilder.status(HttpResponseStatus.PRECONDITION_FAILED).build();
               }
               if (DateUtils.ifUnmodifiedIsBeforeModificationDate(ifUnmodifiedSince, lastMod)) {
                  return responseBuilder.status(HttpResponseStatus.PRECONDITION_FAILED).build();
               }
               if (DateUtils.isNotModifiedSince(ifModifiedSince, lastMod)) {
                  return responseBuilder.status(HttpResponseStatus.NOT_MODIFIED).build();
               }
               Object value = ice.getValue();
               MediaType configuredMediaType = restCacheManager.getValueConfiguredFormat(cacheName, request);
               writeValue(value, requestedMediaType, configuredMediaType, responseBuilder, returnBody);

               responseBuilder.status(HttpResponseStatus.OK)
                     .lastModified(lastMod)
                     .eTag(etag)
                     .cacheControl(CacheOperationsHelper.calcCacheControl(expires))
                     .expires(expires)
                     .timeToLive(meta.lifespan())
                     .maxIdle(meta.maxIdle())
                     .created(ice.getCreated())
                     .lastUsed(ice.getLastUsed());

               List<String> extended = request.parameters().get(EXTENDED_HEADER);
               RestServerConfiguration restServerConfiguration = invocationHelper.getConfiguration();
               if (extended != null && extended.size() > 0 && CacheOperationsHelper.supportsExtendedHeaders(restServerConfiguration, extended.iterator().next())) {
                  responseBuilder.clusterPrimaryOwner(restCacheManager.getPrimaryOwner(cacheName, key, request))
                        .clusterNodeName(restCacheManager.getNodeName())
                        .clusterServerAddress(restCacheManager.getServerAddress());
               }
            }
         }
         return responseBuilder.build();
      });
   }

   private void writeValue(Object value, MediaType requested, MediaType configuredMediaType, NettyRestResponse.Builder
         responseBuilder, boolean returnBody) {
      MediaType responseContentType;

      if (!requested.matchesAll()) {
         responseContentType = requested;
      } else {
         if (configuredMediaType == null) {
            responseContentType = value instanceof byte[] ? MediaType.APPLICATION_OCTET_STREAM : MediaType.TEXT_PLAIN;
         } else {
            responseContentType = configuredMediaType;
         }
      }
      responseBuilder.contentType(responseContentType);

      if (returnBody) responseBuilder.entity(value);
   }

   private <V> String calcETAG(V value) {
      return String.valueOf(hashFunc.hash(value));
   }

   private CompletionStage<RestResponse> putInCache(NettyRestResponse.Builder responseBuilder,
                                                    AdvancedCache<Object, Object> cache, Object key, byte[] data, Long ttl,
                                                    Long idleTime) {
      final Metadata metadata = CacheOperationsHelper.createMetadata(SecurityActions.getCacheConfiguration(cache), ttl, idleTime);
      responseBuilder.header("etag", calcETAG(data));
      return cache.putAsync(key, data, metadata).thenApply(o -> responseBuilder.build());
   }

}
