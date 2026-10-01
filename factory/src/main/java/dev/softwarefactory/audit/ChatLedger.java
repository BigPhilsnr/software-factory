package dev.softwarefactory.audit;

import dev.softwarefactory.platform.WorkflowConflictException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** The persistent daily budget for conversational provider requests, and the audit of chat tool activity. */
public final class ChatLedger {
    private static final int MAX_AUDIT_DETAIL = 2000;
    private static final int MAX_BUDGET = 10_000;

    private final ControlDatabase database;

    public ChatLedger(ControlDatabase database) {
        this.database = database;
    }

    /**
     * Increments the daily budget and audits the reservation in one transaction.
     *
     * @throws WorkflowConflictException when today's budget is exhausted; nothing is recorded
     */
    public void reserveRequest(int dailyLimit) throws IOException {
        if (dailyLimit < 1 || dailyLimit > MAX_BUDGET)
            throw new IllegalArgumentException("Chat request budget must be 1.." + MAX_BUDGET);
        database.transaction(connection -> {
            try (var query = connection.prepareStatement(
                    "INSERT INTO chat_budget(day, requests) VALUES (?, 1) ON CONFLICT(day) DO UPDATE SET requests = chat_budget.requests + 1 WHERE chat_budget.requests < ? RETURNING requests")) {
                query.setObject(1, LocalDate.now(ZoneOffset.UTC));
                query.setInt(2, dailyLimit);
                try (var row = query.executeQuery()) {
                    if (!row.next())
                        throw new WorkflowConflictException("Daily chat provider-request budget exhausted");
                }
            }
            insert(connection, EventTypes.MODEL_CALL_RESERVED, "dailyLimit=" + dailyLimit);
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
