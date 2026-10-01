package dev.softwarefactory.audit;

import dev.softwarefactory.platform.InfrastructureException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The separate PostgreSQL control database, inaccessible to workers. Every failure to reach it is an
 * {@link InfrastructureException}: it says nothing about a candidate.
 */
public final class ControlDatabase {
    private static final Logger LOG = LoggerFactory.getLogger(ControlDatabase.class);

    private final Sessions sessions;

    private ControlDatabase(Sessions sessions) {
        this.sessions = sessions;
    }

    @FunctionalInterface
    private interface Sessions {
        Connection open() throws SQLException;
    }

    @FunctionalInterface
    interface Work<T> {
        T run(Connection connection) throws SQLException, IOException;
    }

    /** Uses a pool whose schema the caller (Spring Boot) has already migrated. */
    public static ControlDatabase using(DataSource pool) {
        return new ControlDatabase(pool::getConnection);
    }

    /** Connects directly and migrates: the CLI and isolated fixtures use the same versioned schema as Boot. */
    public static ControlDatabase open(String url, String user, String password) {
        var source = new DriverManagerDataSource(url, user, password);
        var properties = new Properties();
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "30");
        properties.setProperty("options", "-c statement_timeout=15000 -c lock_timeout=5000");
        source.setConnectionProperties(properties);
        var configuration = Flyway.configure()
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0");
        configuration.dataSource(source);
        configuration.load().migrate();
        return new ControlDatabase(source::getConnection);
    }

    <T> T query(Work<T> work) throws IOException {
        try (Connection connection = sessions.open()) {
            return work.run(connection);
        } catch (SQLException failure) {
            throw new InfrastructureException("Control database operation failed: " + failure.getMessage(), failure);
        }
    }

    /** Commits when {@code work} returns; any failure rolls back before it propagates. */
    <T> T transaction(Work<T> work) throws IOException {
        return query(connection -> {
            connection.setAutoCommit(false);
            boolean committed = false;
            try {
                T result = work.run(connection);
                connection.commit();
                committed = true;
                return result;
            } finally {
                if (!committed) rollback(connection);
            }
        });
    }

    /** A session the caller owns and must close, for state that lives as long as the session (leases). */
    Connection session() throws InfrastructureException {
        try {
            return sessions.open();
        } catch (SQLException unavailable) {
            throw new InfrastructureException("Control database unavailable", unavailable);
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException failure) {
            LOG.warn("Control database rollback failed: {}", failure.getMessage());
        }
    }
}
