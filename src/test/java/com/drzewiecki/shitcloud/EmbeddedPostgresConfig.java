package com.drzewiecki.shitcloud;

import com.zaxxer.hikari.HikariDataSource;
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
 *
 * <p>Connections go through a small HikariCP pool, as in production: a code path that never returns its connection
 * exhausts it within a few calls and fails fast, instead of passing here and failing on Railway.
 */
@Configuration
@PropertySource("classpath:embedded-postgres.properties")
class EmbeddedPostgresConfig {

    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres() throws IOException {
        return EmbeddedPostgres.builder().start();
    }

    @Bean(destroyMethod = "close")
    DataSource dataSource(EmbeddedPostgres postgres) {
        HikariDataSource pool = new HikariDataSource();
        pool.setDataSource(postgres.getPostgresDatabase());
        pool.setMaximumPoolSize(4);
        pool.setConnectionTimeout(2_000);
        return pool;
    }
}
