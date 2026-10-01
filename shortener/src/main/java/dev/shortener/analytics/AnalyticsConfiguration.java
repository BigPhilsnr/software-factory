package dev.shortener.analytics;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Spring destroys the recorder before its dedicated pool, draining bounded writes first. */
@Configuration(proxyBeanMethods = false)
public class AnalyticsConfiguration {
    // A wrapper keeps this bulkhead out of Boot's primary DataSource candidate selection.
    public record AnalyticsPool(HikariDataSource source) implements AutoCloseable {
        @Override public void close() { source.close(); }
    }

    @Bean(destroyMethod = "close")
    AnalyticsPool analyticsPool(@Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password) {
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(250);
        config.setPoolName("analytics-pool");
        return new AnalyticsPool(new HikariDataSource(config));
    }

    @Bean
    BoundedAnalyticsRecorder analyticsRecorder(AnalyticsPool pool, MeterRegistry meters) {
        var jdbc = new JdbcTemplate(pool.source());
        jdbc.setQueryTimeout(5);
        var workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128), runnable -> {
                var thread = new Thread(runnable, "analytics-writer");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        return new BoundedAnalyticsRecorder(jdbc, workers, meters.counter("shortener.analytics.failures"));
    }
}
