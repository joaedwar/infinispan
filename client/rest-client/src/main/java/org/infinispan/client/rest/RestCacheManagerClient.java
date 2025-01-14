package org.infinispan.client.rest;

import java.util.concurrent.CompletionStage;

/**
 * @author Tristan Tarrant &lt;tristan@infinispan.org&gt;
 * @since 10.0
 **/
public interface RestCacheManagerClient {
   String name();

   CompletionStage<RestResponse> globalConfiguration();

   CompletionStage<RestResponse> cacheConfigurations();

   CompletionStage<RestResponse> info();

   CompletionStage<RestResponse> health();

   CompletionStage<RestResponse> healthStatus();

   CompletionStage<RestResponse> stats();
}
