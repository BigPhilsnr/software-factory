package dev.shortener.analytics;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads totals over the primary pool; the analytics pool is reserved for flushes. */
@Repository
class JdbcRedirectStatsReader implements RedirectStatsReader {
    private static final String STATISTICS = """
        SELECT COALESCE(s.redirect_count, 0) AS redirect_count, s.last_redirect_at
        FROM links l LEFT JOIN link_stats s ON s.link_id = l.id
        WHERE l.id = :id
        """;
    private static final RowMapper<RedirectStats> STATS = (rs, row) -> {
        OffsetDateTime last = rs.getObject("last_redirect_at", OffsetDateTime.class);
        return new RedirectStats(rs.getLong("redirect_count"), last == null ? null : last.toInstant());
    };

    private final JdbcClient jdbc;

    JdbcRedirectStatsReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RedirectStats> forLink(long linkId) {
        return jdbc.sql(STATISTICS).param("id", linkId).query(STATS).optional();
    }
}
