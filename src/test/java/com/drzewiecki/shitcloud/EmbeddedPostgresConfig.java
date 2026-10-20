package com.drzewiecki.shitcloud;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import javax.sql.DataSource;
import java.io.IOException;

/**
 * Starts a real PostgreSQL in this process and hands it to the application as its {@link DataSource}; Spring
 * Boot's own data source then backs off, and Flyway migrates this one. Lives in test sources, so it is picked
 * up by component scanning only on the test classpath: by {@link DevApplication} and by the integration tests.
 * The database is created fresh on every start.
 */
@Configuration
@PropertySource("classpath:embedded-postgres.properties")
class EmbeddedPostgresConfig {

    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres() throws IOException {
        return EmbeddedPostgres.builder().start();
    }

    @Bean
    DataSource dataSource(EmbeddedPostgres postgres) {
        return postgres.getPostgresDatabase();
    }
}
