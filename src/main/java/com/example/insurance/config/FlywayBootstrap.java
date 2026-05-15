package com.example.insurance.config;

import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

import java.util.logging.Logger;

/**
 * Runs Flyway migrations against jdbc/insuranceDS at application startup.
 * Observing @Initialized(ApplicationScoped.class) gives us a hook that fires
 * after the DataSource is available but before any HTTP request can be served.
 *
 * Schema generation in persistence.xml is "none" so this is the only thing that
 * touches DDL.
 */
@ApplicationScoped
public class FlywayBootstrap {

    private static final Logger LOG = Logger.getLogger(FlywayBootstrap.class.getName());

    @Resource(lookup = "jdbc/insuranceDS")
    private DataSource dataSource;

    public void onStartup(@Observes @Initialized(ApplicationScoped.class) Object init) {
        LOG.info("Running Flyway migrations against jdbc/insuranceDS...");
        MigrateResult result = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate();
        LOG.info(() -> "Flyway: " + result.migrationsExecuted + " migration(s) applied.");
    }
}
