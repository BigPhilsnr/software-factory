package com.example.shortener.persistence;

import com.example.shortener.domain.AnalyticsRecorder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Isolates counter writes from the lookup pool and bounds request wait and in-flight work. */
@Component
public final class BoundedAnalyticsRecorder implements AnalyticsRecorder, DisposableBean {
    private final HikariDataSource source;
    private final JdbcTemplate jdbc;
    private final ThreadPoolExecutor workers;
    private final Counter failures;

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
        config.setMinimumIdle(0);
        config.setConnectionTimeout(250);
        config.setPoolName("analytics-pool");
        this.source = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(source);
        this.workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), runnable -> {
                Thread thread = new Thread(runnable, "analytics-writer");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        this.failures = meters.counter("shortener.analytics.failures");
    }

    @Override
    public void record(long linkId) {
        Future<?> attempt;
        try {
            attempt = workers.submit(() -> jdbc.update(
                "UPDATE link_stats SET redirect_count = redirect_count + 1, last_redirect_at = now() WHERE link_id = ?", linkId));
        } catch (RuntimeException saturated) {
            failures.increment();
            return;
        }
        try {
            attempt.get(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            attempt.cancel(true);
            failures.increment();
        } catch (TimeoutException | ExecutionException failed) {
            attempt.cancel(true);
            failures.increment();
        }
    }

    @Override
    public void destroy() {
        workers.shutdownNow();
        source.close();
    }
}
