package dev.shortener.analytics;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** One batched UPDATE per flush; rows are locked in link order so concurrent instances cannot deadlock. */
public final class JdbcRedirectStatsWriter implements RedirectStatsWriter {
    private static final String APPLY = """
        UPDATE link_stats
        SET redirect_count = redirect_count + ?,
            last_redirect_at = GREATEST(COALESCE(last_redirect_at, ?), ?)
        WHERE link_id = ?
        """;
    private static final int COUNT = 1;
    private static final int FALLBACK_TIMESTAMP = 2;
    private static final int TIMESTAMP = 3;
    private static final int LINK_ID = 4;

    private final JdbcTemplate jdbc;
    private final int batchSize;

    public JdbcRedirectStatsWriter(JdbcTemplate jdbc, int batchSize) {
        this.jdbc = jdbc;
        this.batchSize = batchSize;
    }

    @Override
    public void write(List<RedirectDelta> deltas) {
        jdbc.batchUpdate(APPLY, deltas, batchSize, (statement, delta) -> {
            OffsetDateTime at = OffsetDateTime.ofInstant(delta.lastRedirectAt(), ZoneOffset.UTC);
            statement.setLong(COUNT, delta.count());
            statement.setObject(FALLBACK_TIMESTAMP, at);
            statement.setObject(TIMESTAMP, at);
            statement.setLong(LINK_ID, delta.linkId());
        });
    }
}
