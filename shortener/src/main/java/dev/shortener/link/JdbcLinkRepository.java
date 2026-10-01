package dev.shortener.link;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** PostgreSQL is the authority on which codes exist; its unique constraint settles concurrent claims. */
@Repository
class JdbcLinkRepository implements LinkRepository {
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
    private static final String CODE = "code";
    private static final RowMapper<Link> LINK = (rs, row) -> new Link(
            rs.getLong("id"),
            rs.getString(CODE),
            rs.getString("target_url"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    JdbcLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Link create(String code, String targetUrl) {
        return jdbc.sql(CREATE)
                .param(CODE, code)
                .param("targetUrl", targetUrl)
                .query(LINK)
                .single();
    }

    @Override
    public Optional<Link> findByCode(String code) {
        return jdbc.sql(FIND_BY_CODE).param(CODE, code).query(LINK).optional();
    }
}
