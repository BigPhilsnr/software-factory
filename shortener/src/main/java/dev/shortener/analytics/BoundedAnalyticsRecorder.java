package dev.shortener.analytics;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Isolates writes from link lookup; redirect threads only enqueue into a bounded queue. */
@Component
public final class BoundedAnalyticsRecorder implements AnalyticsRecorder, DisposableBean {
    private final HikariDataSource source;
    private final JdbcTemplate jdbc;
    private final ThreadPoolExecutor workers;
    private final Counter failures;

    @org.springframework.beans.factory.annotation.Autowired
    public BoundedAnalyticsRecorder(
        @Value("${spring.datasource.url}") String url,
        @Value("${spring.datasource.username}") String username,
        @Value("${spring.datasource.password}") String password,
        MeterRegistry meters
    ) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(250);
        config.setPoolName("analytics-pool");
        this.source = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(source);
        this.jdbc.setQueryTimeout(5);
        this.workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128), runnable -> {
                Thread thread = new Thread(runnable, "analytics-writer");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        this.failures = meters.counter("shortener.analytics.failures");
    }

    BoundedAnalyticsRecorder(JdbcTemplate jdbc, ThreadPoolExecutor workers, Counter failures) {
        this.source = null;
        this.jdbc = jdbc;
        this.workers = workers;
        this.failures = failures;
    }

    @Override
    public void record(long linkId) {
        try {
            workers.execute(() -> {
                try {
                    jdbc.update("UPDATE link_stats SET redirect_count = redirect_count + 1, last_redirect_at = now() WHERE link_id = ?", linkId);
                } catch (RuntimeException failure) { failures.increment(); }
            });
        } catch (java.util.concurrent.RejectedExecutionException saturated) {
            failures.increment();
        }
    }

    @Override
    public void destroy() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (source != null) source.close();
    }
}
