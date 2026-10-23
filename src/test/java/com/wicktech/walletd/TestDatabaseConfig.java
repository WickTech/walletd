package com.wicktech.walletd;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real PostgreSQL for every test, so the Flyway migrations and triggers run
 * exactly as in production.
 *
 * <ul>
 *   <li>default: PostgreSQL binaries started in-process (no Docker needed);</li>
 *   <li>{@code -Pcontainer} (what CI also runs): the official {@code postgres:17}
 *       image through Testcontainers.</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabaseConfig {

    static final boolean USE_CONTAINER = "container".equals(System.getProperty("walletd.test.db"));

    /** One container for the whole test run, shared by every Spring context. */
    private static final class Container {
        static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

        static {
            POSTGRES.start(); // Testcontainers' Ryuk reaper removes it when the JVM exits
        }
    }

    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres() throws IOException {
        return USE_CONTAINER ? null : EmbeddedPostgres.start();
    }

    @Bean
    DataSource dataSource(EmbeddedPostgres embedded) {
        if (!USE_CONTAINER) {
            return embedded.getPostgresDatabase();
        }
        var ds = new PGSimpleDataSource();
        ds.setUrl(Container.POSTGRES.getJdbcUrl());
        ds.setUser(Container.POSTGRES.getUsername());
        ds.setPassword(Container.POSTGRES.getPassword());
        return ds;
    }
}
