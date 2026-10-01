package dev.shortener.analytics;

import io.micrometer.core.instrument.Counter;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;

/** Isolates writes from link lookup; redirect threads only enqueue into a bounded queue. */
public final class BoundedAnalyticsRecorder implements AnalyticsRecorder, DisposableBean {
    private final JdbcTemplate jdbc;
    private final ThreadPoolExecutor workers;
    private final Counter failures;

    public BoundedAnalyticsRecorder(JdbcTemplate jdbc, ThreadPoolExecutor workers, Counter failures) {
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
    }
}
