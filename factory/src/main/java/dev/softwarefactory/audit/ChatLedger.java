package dev.softwarefactory.audit;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** The persistent daily usage counter for conversational provider requests, and the audit of chat tool activity. */
public final class ChatLedger {
    private static final int MAX_AUDIT_DETAIL = 2000;

    private final ControlDatabase database;

    public ChatLedger(ControlDatabase database) {
        this.database = database;
    }

    /** Counts daily usage and audits the request atomically, without a request ceiling. */
    public void reserveRequest() throws IOException {
        database.transaction(connection -> {
            try (var query = connection.prepareStatement(
                    "INSERT INTO chat_budget(day, requests) VALUES (?, 1) ON CONFLICT(day) DO UPDATE SET requests = chat_budget.requests + 1")) {
                query.setObject(1, LocalDate.now(ZoneOffset.UTC));
                query.executeUpdate();
            }
            insert(connection, EventTypes.MODEL_CALL_RESERVED, "Provider request recorded; no call limit");
            return null;
        });
    }

    public void record(String type, String detail) throws IOException {
        database.query(connection -> {
            insert(connection, type, detail);
            return null;
        });
    }

    private static void insert(Connection connection, String type, String detail) throws SQLException {
        try (var query =
                connection.prepareStatement("INSERT INTO chat_audit(id, at, type, detail) VALUES (?, ?, ?, ?)")) {
            query.setObject(1, UUID.randomUUID());
            query.setObject(2, OffsetDateTime.now());
            query.setString(3, type);
            query.setString(4, detail.substring(0, Math.min(detail.length(), MAX_AUDIT_DETAIL)));
            query.executeUpdate();
        }
    }
}
