package org.infinispan.client.rest;

import java.util.concurrent.CompletionStage;

import org.infinispan.commons.api.CacheContainerAdmin;

/**
 * @author Tristan Tarrant &lt;tristan@infinispan.org&gt;
 * @since 10.0
 **/
public interface RestCacheClient {

   /**
    * Returns the name of the cache
    */
   String name();

   CompletionStage<RestResponse> keys();

   /**
    * Retrieves the cache configuration
    */
   CompletionStage<RestResponse> configuration();

   /**
    * Clears a cache
    */
   CompletionStage<RestResponse> clear();

   /**
    * Obtains the total number of elements in the cache
    */
   CompletionStage<RestResponse> size();

   /**
    * POSTs a key/value to the cache as text/plain
    *
    * @param key
    * @param value
    * @return
    */
   CompletionStage<RestResponse> post(String key, String value);

   /**
    * POSTs a key/value to the cache with the specified encoding
    *
    * @param key
    * @param value
    * @return
    */
   CompletionStage<RestResponse> post(String key, RestEntity value);

   /**
    * PUTs a key/value to the cache as text/plain
    *
    * @param key
    * @param value
    * @return
    */
   CompletionStage<RestResponse> put(String key, String value);

   /**
    * PUTs a key/value to the cache with the specified encoding
    *
    * @param key
    * @param value
    * @return
    */
   CompletionStage<RestResponse> put(String key, RestEntity value);

   /**
    * GETs a key from the cache
    *
    * @param key
    * @return
    */
   CompletionStage<RestResponse> get(String key);

   /**
    * DELETEs an entry from the cache
    *
    * @param key
    * @return
    */
   CompletionStage<RestResponse> remove(String key);

   /**
    * Creates the cache using the supplied template name
    *
    * @param template the name of a template
    * @param flags    any flags to apply to the create operation, e.g. {@link org.infinispan.commons.api.CacheContainerAdmin.AdminFlag#PERMANENT}
    * @return
    */
   CompletionStage<RestResponse> createWithTemplate(String template, CacheContainerAdmin.AdminFlag... flags);

   /**
    * Obtains statistics for the cache
    *
    * @return
    */
   CompletionStage<RestResponse> stats();

   /**
    * Creates the cache using the supplied configuration
    *
    * @param configuration the configuration, either in XML or JSON format
    * @param flags         any flags to apply to the create operation, e.g. {@link org.infinispan.commons.api.CacheContainerAdmin.AdminFlag#PERMANENT}
    * @return
    */
   CompletionStage<RestResponse> createWithConfiguration(RestEntity configuration, CacheContainerAdmin.AdminFlag... flags);

   /**
    * Removes the cache
    *
    * @return
    */
   CompletionStage<RestResponse> delete();

   CompletionStage<RestResponse> query(String query);
}
