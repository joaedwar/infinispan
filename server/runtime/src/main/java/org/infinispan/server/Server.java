package org.infinispan.server;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import javax.xml.stream.XMLStreamException;

import org.infinispan.commons.CacheConfigurationException;
import org.infinispan.commons.configuration.ConfigurationFor;
import org.infinispan.commons.configuration.ConfigurationInfo;
import org.infinispan.commons.time.DefaultTimeService;
import org.infinispan.commons.time.TimeService;
import org.infinispan.commons.util.Util;
import org.infinispan.commons.util.Version;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ParserRegistry;
import org.infinispan.lifecycle.ComponentStatus;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.rest.RestServer;
import org.infinispan.server.configuration.ServerConfiguration;
import org.infinispan.server.configuration.ServerConfigurationBuilder;
import org.infinispan.server.configuration.admin.ServerAdminOperationsHandler;
import org.infinispan.server.core.ProtocolServer;
import org.infinispan.server.core.ServerManagement;
import org.infinispan.server.core.configuration.ProtocolServerConfiguration;
import org.infinispan.server.hotrod.HotRodServer;
import org.infinispan.server.logging.Log;
import org.infinispan.server.router.RoutingTable;
import org.infinispan.server.router.configuration.SinglePortRouterConfiguration;
import org.infinispan.server.router.router.impl.singleport.SinglePortEndpointRouter;
import org.infinispan.server.router.routes.Route;
import org.infinispan.server.router.routes.RouteDestination;
import org.infinispan.server.router.routes.RouteSource;
import org.infinispan.server.router.routes.hotrod.HotRodServerRouteDestination;
import org.infinispan.server.router.routes.rest.RestServerRouteDestination;
import org.infinispan.server.router.routes.singleport.SinglePortRouteSource;
import org.infinispan.util.logging.LogFactory;
import org.wildfly.security.http.basic.WildFlyElytronHttpBasicProvider;
import org.wildfly.security.http.bearer.WildFlyElytronHttpBearerProvider;
import org.wildfly.security.http.cert.WildFlyElytronHttpClientCertProvider;
import org.wildfly.security.http.digest.WildFlyElytronHttpDigestProvider;
import org.wildfly.security.sasl.digest.WildFlyElytronSaslDigestProvider;
import org.wildfly.security.sasl.external.WildFlyElytronSaslExternalProvider;
import org.wildfly.security.sasl.localuser.WildFlyElytronSaslLocalUserProvider;
import org.wildfly.security.sasl.oauth2.WildFlyElytronSaslOAuth2Provider;
import org.wildfly.security.sasl.plain.WildFlyElytronSaslPlainProvider;
import org.wildfly.security.sasl.scram.WildFlyElytronSaslScramProvider;

/**
 * @author Tristan Tarrant &lt;tristan@infinispan.org&gt;
 * @since 10.0
 */
public class Server implements ServerManagement {
   public static final Log log = LogFactory.getLog("SERVER", Log.class);

   // Properties
   public static final String INFINISPAN_BIND_ADDRESS = "infinispan.bind.address";
   public static final String INFINISPAN_BIND_PORT = "infinispan.bind.port";
   public static final String INFINISPAN_CLUSTER_NAME = "infinispan.cluster.name";
   public static final String INFINISPAN_CLUSTER_STACK = "infinispan.cluster.stack";
   public static final String INFINISPAN_NODE_NAME = "infinispan.node.name";
   public static final String INFINISPAN_PORT_OFFSET = "infinispan.socket.binding.port-offset";
   /**
    * Property name indicating the path to the server installation. If unspecified, the current working directory will be used
    */
   public static final String INFINISPAN_SERVER_HOME_PATH = "infinispan.server.home.path";
   /**
    * Property name indicating the path to the root of a server instance. If unspecified, defaults to the <i>server</i> directory under the server home.
    */
   public static final String INFINISPAN_SERVER_ROOT_PATH = "infinispan.server.root.path";
   /**
    * Property name indicating the path to the configuration directory of a server instance. If unspecified, defaults to the <i>conf</i> directory under the server root.
    */
   public static final String INFINISPAN_SERVER_CONFIG_PATH = "infinispan.server.config.path";
   /**
    * Property name indicating the path to the data directory of a server instance. If unspecified, defaults to the <i>data</i> directory under the server root.
    */
   public static final String INFINISPAN_SERVER_DATA_PATH = "infinispan.server.data.path";
   /**
    * Property name indicating the path to the log directory of a server instance. If unspecified, defaults to the <i>log</i> directory under the server root.
    */
   public static final String INFINISPAN_SERVER_LOG_PATH = "infinispan.server.log.path";

   // Defaults
   private static final String SERVER_DEFAULTS = "infinispan-defaults.xml";

   public static final String DEFAULT_SERVER_CONFIG = "conf";
   public static final String DEFAULT_SERVER_DATA = "data";
   public static final String DEFAULT_SERVER_LIB = "lib";
   public static final String DEFAULT_SERVER_LOG = "log";
   public static final String DEFAULT_SERVER_ROOT_DIR = "server";
   public static final String DEFAULT_CONFIGURATION_FILE = "infinispan.xml";
   public static final String DEFAULT_CLUSTER_NAME = "cluster";
   public static final String DEFAULT_CLUSTER_STACK = "tcp";
   public static final int DEFAULT_BIND_PORT = 11222;

   private final TimeService timeService;
   private final File serverRoot;
   private final File serverConf;
   private final long startTime;
   private final Properties properties;
   private ExitHandler exitHandler = new DefaultExitHandler();
   private ConfigurationBuilderHolder defaultsHolder;
   private ConfigurationBuilderHolder configurationBuilderHolder;
   private Map<String, DefaultCacheManager> cacheManagers;
   private Map<String, ProtocolServer> protocolServers;
   private volatile ComponentStatus status;
   private ServerConfiguration serverConfiguration;
   private Extensions extensions;

   /**
    * Initializes a server with the default server root, the default configuration file and system properties
    */
   public Server() {
      this(
            new File(DEFAULT_SERVER_ROOT_DIR),
            new File(DEFAULT_CONFIGURATION_FILE),
            SecurityActions.getSystemProperties()
      );
   }

   /**
    * Initializes a server with the supplied server root, configuration file and properties
    *
    * @param serverRoot
    * @param configuration
    * @param properties
    */
   public Server(File serverRoot, File configuration, Properties properties) {
      this(serverRoot, properties);
      if (!configuration.isAbsolute()) {
         configuration = new File(serverConf, configuration.getPath());
      }
      try {
         parseConfiguration(configuration.toURI().toURL());
      } catch (IOException e) {
         throw new CacheConfigurationException(e);
      }
   }

   private Server(File serverRoot, Properties properties) {
      this.timeService = DefaultTimeService.INSTANCE;
      this.startTime = timeService.time();
      this.serverRoot = serverRoot;
      this.properties = properties;
      this.status = ComponentStatus.INSTANTIATED;

      // Populate system properties unless they have already been set externally
      properties.putIfAbsent(INFINISPAN_SERVER_ROOT_PATH, serverRoot);
      properties.putIfAbsent(INFINISPAN_SERVER_CONFIG_PATH, new File(serverRoot, DEFAULT_SERVER_CONFIG).getAbsolutePath());
      properties.putIfAbsent(INFINISPAN_SERVER_DATA_PATH, new File(serverRoot, DEFAULT_SERVER_DATA).getAbsolutePath());
      properties.putIfAbsent(INFINISPAN_SERVER_LOG_PATH, new File(serverRoot, DEFAULT_SERVER_LOG).getAbsolutePath());
      properties.putIfAbsent(INFINISPAN_BIND_PORT, DEFAULT_BIND_PORT);
      properties.putIfAbsent(INFINISPAN_CLUSTER_NAME, DEFAULT_CLUSTER_NAME);
      properties.putIfAbsent(INFINISPAN_CLUSTER_STACK, DEFAULT_CLUSTER_STACK);

      this.serverConf = new File(properties.getProperty(INFINISPAN_SERVER_CONFIG_PATH));

      //SecurityActions.addSecurityProvider(new WildFlyElytronProvider());


      // Register only the providers that matter to us
      SecurityActions.addSecurityProvider(WildFlyElytronHttpBasicProvider.getInstance());
      SecurityActions.addSecurityProvider(WildFlyElytronHttpBearerProvider.getInstance());
      SecurityActions.addSecurityProvider(WildFlyElytronHttpDigestProvider.getInstance());
      SecurityActions.addSecurityProvider(WildFlyElytronHttpClientCertProvider.getInstance());
      SecurityActions.addSecurityProvider(WildFlyElytronSaslPlainProvider.getInstance());
      SecurityActions.addSecurityProvider(WildFlyElytronSaslDigestProvider.getInstance());
      SecurityActions.addSecurityProvider(WildFlyElytronSaslScramProvider.getInstance());
      SecurityActions.addSecurityProvider(WildFlyElytronSaslExternalProvider.getInstance());
      SecurityActions.addSecurityProvider(WildFlyElytronSaslLocalUserProvider.getInstance());
      SecurityActions.addSecurityProvider(WildFlyElytronSaslOAuth2Provider.getInstance());
   }

   private void parseConfiguration(URL config) {
      ParserRegistry parser = new ParserRegistry(this.getClass().getClassLoader(), false, properties);
      try {
         // load the defaults first
         URL defaults = this.getClass().getClassLoader().getResource(SERVER_DEFAULTS);
         defaultsHolder = parser.parse(defaults);

         // base the global configuration to the default
         configurationBuilderHolder = new ConfigurationBuilderHolder();
         configurationBuilderHolder.getGlobalConfigurationBuilder().read(defaultsHolder.getGlobalConfigurationBuilder().build());

         // Copy all default templates
         for (Map.Entry<String, ConfigurationBuilder> entry : defaultsHolder.getNamedConfigurationBuilders().entrySet()) {
            configurationBuilderHolder.newConfigurationBuilder(entry.getKey()).read(entry.getValue().build());
         }

         // then load the user configuration
         configurationBuilderHolder = parser.parse(config, configurationBuilderHolder);

         // Set the operation handler on all endpoints
         ServerAdminOperationsHandler adminOperationsHandler = new ServerAdminOperationsHandler(defaultsHolder);
         ServerConfigurationBuilder serverConfigurationBuilder = configurationBuilderHolder.getGlobalConfigurationBuilder().module(ServerConfigurationBuilder.class);
         serverConfigurationBuilder.connectors().forEach(builder -> builder.adminOperationsHandler(adminOperationsHandler));

         // Amend the named caches configurations with the defaults
         for (Map.Entry<String, ConfigurationBuilder> entry : configurationBuilderHolder.getNamedConfigurationBuilders().entrySet()) {
            Configuration cfg = entry.getValue().build();
            ConfigurationBuilder defaultCfg = defaultsHolder.getNamedConfigurationBuilders().get("org.infinispan." + cfg.clustering().cacheMode().name());
            ConfigurationBuilder rebased = new ConfigurationBuilder().read(defaultCfg.build());
            rebased.read(cfg);
            entry.setValue(rebased);
         }

         configurationBuilderHolder.validate();
      } catch (IOException | XMLStreamException e) {
         throw new CacheConfigurationException(e);
      }
   }

   public ExitHandler getExitHandler() {
      return exitHandler;
   }

   public void setExitHandler(ExitHandler exitHandler) {
      if (status == ComponentStatus.INSTANTIATED) {
         this.exitHandler = exitHandler;
      } else {
         throw new IllegalStateException("Cannot change exit handler on a running server");
      }
   }

   public synchronized CompletableFuture<Integer> run() {
      CompletableFuture<Integer> r = exitHandler.getExitFuture();
      if (status == ComponentStatus.RUNNING) {
         return r;
      }
      cacheManagers = new LinkedHashMap<>(1);
      protocolServers = new LinkedHashMap<>(3);
      try {
         // Load any server extensions
         extensions = new Extensions();
         extensions.load(this.getClass().getClassLoader());

         // Start the cache manager(s)
         DefaultCacheManager cm = new DefaultCacheManager(configurationBuilderHolder, false);
         cacheManagers.put(cm.getName(), cm);
         SecurityActions.startCacheManager(cm);

         // Start the protocol servers
         serverConfiguration = SecurityActions.getCacheManagerConfiguration(cm).module(ServerConfiguration.class);
         SinglePortRouteSource routeSource = new SinglePortRouteSource();
         ConcurrentMap<Route<? extends RouteSource, ? extends RouteDestination>, Object> routes = new ConcurrentHashMap<>();
         serverConfiguration.endpoints().connectors().parallelStream().forEach(configuration -> {
            try {
               Class<? extends ProtocolServer> protocolServerClass = configuration.getClass().getAnnotation(ConfigurationFor.class).value().asSubclass(ProtocolServer.class);
               ProtocolServer protocolServer = Util.getInstance(protocolServerClass);
               if (protocolServer instanceof RestServer) ((RestServer) protocolServer).setServer(this);
               protocolServers.put(protocolServer.getName() + "-" + configuration.name(), protocolServer);
               SecurityActions.startProtocolServer(protocolServer, configuration, cm);
               ProtocolServerConfiguration protocolConfig = protocolServer.getConfiguration();
               if (protocolConfig.startTransport()) {
                  log.protocolStarted(protocolServer.getName(), protocolConfig.host(), protocolConfig.port());
               } else {
                  if (protocolServer instanceof HotRodServer) {
                     routes.put(new Route<>(routeSource, new HotRodServerRouteDestination(protocolServer.getName(), (HotRodServer) protocolServer)), 0);
                     extensions.apply((HotRodServer) protocolServer);
                  } else if (protocolServer instanceof RestServer) {
                     routes.put(new Route<>(routeSource, new RestServerRouteDestination(protocolServer.getName(), (RestServer) protocolServer)), 0);
                  }
                  log.protocolStarted(protocolServer.getName());
               }
            } catch (Throwable t) {
               System.err.println(t.getMessage());
               t.printStackTrace();
            }
         });
         // Next we start the single-port endpoint
         SinglePortRouterConfiguration singlePortRouter = serverConfiguration.endpoints().singlePortRouter();
         SinglePortEndpointRouter endpointServer = new SinglePortEndpointRouter(singlePortRouter);
         endpointServer.start(new RoutingTable(routes.keySet()));
         protocolServers.put("endpoint", endpointServer);
         log.protocolStarted(endpointServer.getName(), singlePortRouter.host(), singlePortRouter.port());
         // Change status
         this.status = ComponentStatus.RUNNING;
         log.serverStarted(Version.getBrandName(), Version.getVersion(), timeService.timeDuration(startTime, TimeUnit.MILLISECONDS));
      } catch (Exception e) {
         r.completeExceptionally(e);
      }
      r = r.whenComplete((status, t) -> shutdown());
      return r;
   }

   @Override
   public ConfigurationInfo getConfiguration() {
      return serverConfiguration;
   }

   private void shutdown() {
      status = ComponentStatus.STOPPING;
      // Shutdown the protocol servers in parallel
      protocolServers.values().parallelStream().forEach(ProtocolServer::stop);
      cacheManagers.values().forEach(cm -> SecurityActions.stopCacheManager(cm));
      status = ComponentStatus.TERMINATED;
   }

   public void stop() {
      getExitHandler().exit(0);
   }

   @Override
   public Set<String> cacheManagerNames() {
      return cacheManagers.keySet();
   }

   @Override
   public DefaultCacheManager getCacheManager(String name) {
      return cacheManagers.get(name);
   }

   @Override
   public CompletionStage<Void> ignoreCache(String cacheManager, String cache) {
      ProtocolServer protocolServer = protocolServers.values().iterator().next();
      return protocolServer.ignoreCache(cache);
   }

   @Override
   public CompletionStage<Boolean> unIgnoreCache(String cacheManager, String cache) {
      ProtocolServer protocolServer = protocolServers.values().iterator().next();
      return protocolServer.unignore(cache);
   }

   @Override
   public CompletionStage<Collection<String>> ignoredCaches(String cacheManager) {
      ProtocolServer protocolServer = protocolServers.values().iterator().next();
      return protocolServer.getIgnoredCaches(cacheManager);
   }

   public ConfigurationBuilderHolder getConfigurationBuilderHolder() {
      return configurationBuilderHolder;
   }

   public File getServerRoot() {
      return serverRoot;
   }

   public Map<String, DefaultCacheManager> getCacheManagers() {
      return cacheManagers;
   }

   public Map<String, ProtocolServer> getProtocolServers() {
      return protocolServers;
   }
}
