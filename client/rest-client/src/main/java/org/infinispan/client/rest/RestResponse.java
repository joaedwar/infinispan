package org.infinispan.client.rest;

import java.io.InputStream;

import org.infinispan.client.rest.configuration.Protocol;
import org.infinispan.commons.util.Experimental;

/**
 * @author Tristan Tarrant &lt;tristan@infinispan.org&gt;
 * @since 10.0
 **/
@Experimental
public interface RestResponse extends RestEntity {
   int getStatus();

   InputStream getBodyAsStream();

   Protocol getProtocol();
}
