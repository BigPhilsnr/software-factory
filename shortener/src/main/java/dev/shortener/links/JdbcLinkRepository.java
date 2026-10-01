package dev.shortener.links;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLinkRepository implements LinkRepository {
    private static final String CREATE = """
        WITH created AS (
            INSERT INTO links(code, target_url) VALUES (:code, :targetUrl)
            RETURNING id, code, target_url, created_at
        ), stats AS (
            INSERT INTO link_stats(link_id) SELECT id FROM created
        )
        SELECT id, code, target_url, created_at FROM created
        """;
    private static final String FIND_BY_CODE = "SELECT id, code, target_url, created_at FROM links WHERE code = :code";
    private static final String STATISTICS = """
        SELECT COALESCE(s.redirect_count, 0) AS redirect_count, s.last_redirect_at
        FROM links l LEFT JOIN link_stats s ON s.link_id = l.id
        WHERE l.id = :id
        """;
    private static final RowMapper<Link> LINK = (rs, row) -> new Link(rs.getLong("id"), rs.getString("code"),
        rs.getString("target_url"), rs.getObject("created_at", OffsetDateTime.class).toInstant());
    private static final RowMapper<RedirectStats> STATS = (rs, row) -> {
        OffsetDateTime last = rs.getObject("last_redirect_at", OffsetDateTime.class);
        return new RedirectStats(rs.getLong("redirect_count"), last == null ? null : last.toInstant());
    };

    private final JdbcClient jdbc;

    public JdbcLinkRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public Link create(String code, String targetUrl) {
        return jdbc.sql(CREATE).param("code", code).param("targetUrl", targetUrl).query(LINK).single();
    }

    @Override
    public Optional<Link> findByCode(String code) {
        return jdbc.sql(FIND_BY_CODE).param("code", code).query(LINK).optional();
    }

    @Override
    public Optional<RedirectStats> statistics(long linkId) {
        return jdbc.sql(STATISTICS).param("id", linkId).query(STATS).optional();
    }
}
