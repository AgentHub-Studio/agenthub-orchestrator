package com.agenthub.orchestrator.shared.multitenant;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Applies {@code db/migrations/schemas} migrations to every tenant schema ({@code ah_%})
 * at application startup.
 *
 * <p>Spring Boot's Flyway auto-configuration only runs {@code db/migrations/public} (global
 * baseline). Per-tenant schema tables must be migrated separately because each tenant lives
 * in its own PostgreSQL schema ({@code ah_{tenantId}}). This component iterates all existing
 * tenant schemas and runs Flyway against each one.
 *
 * <p>A dedicated Flyway history table ({@code flyway_orchestrator_history}) is used so that
 * this service's migration state is tracked independently from other services that may also
 * run migrations against the same tenant schemas.
 *
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlywaySchemasMigrationApplier {

    private static final String SCHEMAS_LOCATION = "db/migrations/schemas";
    private static final String HISTORY_TABLE = "flyway_orchestrator_history";
    private static final String SELECT_TENANT_SCHEMAS =
            "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'ah\\_%'";

    private final DataSource dataSource;

    /**
     * Runs tenant schema migrations for all existing {@code ah_%} schemas.
     * Executed once after the Spring bean is fully initialized.
     */
    @PostConstruct
    public void migrate() {
        List<String> schemas = getTenantSchemas();
        log.info("Running orchestrator schemas migrations for {} tenant schema(s)", schemas.size());
        for (String schema : schemas) {
            migrateSchema(schema);
        }
    }

    /**
     * Applies pending schema migrations to the given tenant schema.
     *
     * @param schema the PostgreSQL schema name (e.g. {@code ah_550e8400-...})
     */
    public void migrateSchema(String schema) {
        log.debug("Migrating orchestrator schema: {}", schema);
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .locations(SCHEMAS_LOCATION)
                .table(HISTORY_TABLE)
                .baselineOnMigrate(true)
                .outOfOrder(true)
                .load()
                .migrate();
    }

    private List<String> getTenantSchemas() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(SELECT_TENANT_SCHEMAS)) {

            List<String> schemas = new ArrayList<>();
            while (rs.next()) {
                schemas.add(rs.getString(1));
            }
            return schemas;
        } catch (SQLException ex) {
            log.error("Failed to retrieve tenant schemas", ex);
            return Collections.emptyList();
        }
    }
}
