package com.drzewiecki.shitcloud.apps;

import com.drzewiecki.shitcloud.service.AppsProperties;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;

/**
 * Opens the apps port next to the API port. Same application, same handlers: {@link PortFence} decides
 * what each port may answer, so an app document can never be served from the API's origin or the other way round.
 */
@Configuration
public class AppsConnectorConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private final AppsProperties properties;
    private final int serverPort;

    public AppsConnectorConfig(AppsProperties properties, @Value("${server.port}") int serverPort) {
        this.properties = properties;
        this.serverPort = serverPort;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        if (properties.port() == serverPort) {
            throw new IllegalStateException("apps.port must differ from server.port, both are " + serverPort);
        }
        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setPort(properties.port());
        factory.addAdditionalConnectors(connector);
    }
}
