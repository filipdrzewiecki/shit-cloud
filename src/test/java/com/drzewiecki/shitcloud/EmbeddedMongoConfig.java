package com.drzewiecki.shitcloud;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/**
 * Points the application at an embedded mongod. Lives in test sources, so it is picked up by component
 * scanning only on the test classpath: by {@link DevApplication} and by the integration tests.
 */
@Configuration
@PropertySource("classpath:embedded-mongo.properties")
class EmbeddedMongoConfig {
}
