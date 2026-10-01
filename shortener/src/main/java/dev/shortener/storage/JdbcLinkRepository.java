package dev.shortener.storage;

import dev.shortener.analytics.RedirectStats;

import dev.shortener.links.Link;
import dev.shortener.links.LinkRepository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcLinkRepository implements LinkRepository {
    private static final RowMapper<Link> MAPPER = (rs, row) -> new Link(
        rs.getLong("id"), rs.getString("code"), rs.getString("target_url"), rs.getTimestamp("created_at").toInstant());
    private final JdbcTemplate jdbc;

    public JdbcLinkRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public Link create(String code, String targetUrl) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                "INSERT INTO links(code, target_url) VALUES (?, ?)", new String[]{"id"});
            statement.setString(1, code);
            statement.setString(2, targetUrl);
            return statement;
        }, key);
        long id = key.getKey().longValue();
        jdbc.update("INSERT INTO link_stats(link_id) VALUES (?)", id);
        return findByCode(code).orElseThrow();
    }

    @Override
    public Optional<Link> findByCode(String code) {
        return jdbc.query("SELECT id, code, target_url, created_at FROM links WHERE code = ?", MAPPER, code).stream().findFirst();
    }

    @Override
    public long redirectCount(long linkId) {
        return statistics(linkId).redirectCount();
    }

    @Override public RedirectStats statistics(long linkId) {
        return jdbc.queryForObject("SELECT redirect_count, last_redirect_at FROM link_stats WHERE link_id = ?",
            (row, number) -> new RedirectStats(row.getLong("redirect_count"),
                row.getTimestamp("last_redirect_at") == null ? null : row.getTimestamp("last_redirect_at").toInstant()), linkId);
    }

}
