package com.agenthub.orchestrator.shared.multitenant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Hibernate {@link MultiTenantConnectionProvider} that routes connections to the correct
 * per-tenant PostgreSQL schema by executing {@code SET search_path TO <schema>, public}
 * on each acquired connection.
 *
 * <p>Registered as a Spring bean so that Spring Boot's {@code HibernateJpaConfiguration}
 * auto-wires it into the {@code EntityManagerFactory}.
 *
 * @since 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaMultiTenantConnectionProvider implements MultiTenantConnectionProvider<String> {

    private final DataSource dataSource;

    @Override
    public Connection getAnyConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void releaseAnyConnection(Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public Connection getConnection(String schema) throws SQLException {
        Connection connection = dataSource.getConnection();
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("SET search_path TO " + schema + ", public");
        } catch (SQLException ex) {
            log.error("Failed to set search_path to schema: {}", schema, ex);
            connection.close();
            throw ex;
        }
        return connection;
    }

    @Override
    public void releaseConnection(String schema, Connection connection) throws SQLException {
        connection.close();
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return false;
    }

    @Override
    public boolean isUnwrappableAs(Class<?> unwrapType) {
        return MultiTenantConnectionProvider.class.isAssignableFrom(unwrapType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> unwrapType) {
        if (isUnwrappableAs(unwrapType)) {
            return (T) this;
        }
        throw new IllegalArgumentException("Cannot unwrap as " + unwrapType.getName());
    }
}
