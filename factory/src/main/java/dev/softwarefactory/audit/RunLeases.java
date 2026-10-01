package dev.softwarefactory.audit;

import dev.softwarefactory.platform.InfrastructureException;
import dev.softwarefactory.platform.WorkflowConflictException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Exclusive PostgreSQL advisory locks: at most one process transitions a run at a time. */
public final class RunLeases {
    /** First key of the two-int advisory lock form; isolates run leases from other advisory lock users. */
    static final int NAMESPACE = 0x46414354; // "FACT"

    private final ControlDatabase database;

    public RunLeases(ControlDatabase database) {
        this.database = database;
    }

    /**
     * @throws WorkflowConflictException when another holder exists
     */
    public RunLease acquire(String id) throws IOException {
        int key = UUID.fromString(id).hashCode();
        Connection session = database.session();
        if (tryLock(session, key)) return new RunLease(session, key);
        var conflict = new WorkflowConflictException("Run is already being advanced: " + id);
        try {
            session.close();
        } catch (SQLException cleanup) {
            conflict.addSuppressed(cleanup);
        }
        throw conflict;
    }

    private static boolean tryLock(Connection session, int key) throws InfrastructureException {
        try (var query = session.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
            query.setInt(1, NAMESPACE);
            query.setInt(2, key);
            try (var row = query.executeQuery()) {
                return row.next() && row.getBoolean(1);
            }
        } catch (SQLException failure) {
            // The lock may have been granted before the transport failed: never pool that session.
            discard(session, failure);
            throw new InfrastructureException("Could not acquire run lease", failure);
        }
    }

    static void discard(Connection session, Exception failure) {
        try {
            session.abort(Runnable::run);
        } catch (SQLException abort) {
            failure.addSuppressed(abort);
        }
        try {
            session.close();
        } catch (SQLException close) {
            failure.addSuppressed(close);
        }
    }
}
