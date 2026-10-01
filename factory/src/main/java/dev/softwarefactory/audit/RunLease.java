package dev.softwarefactory.audit;

import dev.softwarefactory.platform.InfrastructureException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Held for one operator transition; closing releases the advisory lock and returns the session. */
public final class RunLease implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(RunLease.class);

    private final Connection session;
    private final int key;

    RunLease(Connection session, int key) {
        this.session = session;
        this.key = key;
    }

    boolean isOpen() throws SQLException {
        return !session.isClosed();
    }

    @Override
    public void close() throws IOException {
        if (!unlock()) {
            LOG.error(
                    "Run lease {}/{} was not held by its session at release; leases may not be exclusive",
                    RunLeases.NAMESPACE,
                    key);
        }
        try {
            session.close();
        } catch (SQLException failure) {
            throw new InfrastructureException("Could not return lease connection", failure);
        }
    }

    private boolean unlock() throws InfrastructureException {
        try (var unlock = session.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
            unlock.setInt(1, RunLeases.NAMESPACE);
            unlock.setInt(2, key);
            try (var row = unlock.executeQuery()) {
                return row.next() && row.getBoolean(1);
            }
        } catch (SQLException failure) {
            // A session with an uncertain lock must never return to the pool.
            RunLeases.discard(session, failure);
            throw new InfrastructureException("Could not release run lease", failure);
        }
    }
}
