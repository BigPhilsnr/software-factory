package dev.shortener.analytics;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import dev.shortener.platform.ShortenerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Analytics writes use their own small pool so a slow flush can never starve link lookups. The recorder
 * stops (and flushes) during lifecycle shutdown, before Spring destroys this pool.
 */
@Configuration(proxyBeanMethods = false)
class AnalyticsConfiguration {
    private static final String POOL_NAME = "analytics-pool";

    /** A wrapper keeps this bulkhead out of Boot's primary DataSource candidate selection. */
    static final class AnalyticsPool implements AutoCloseable {
        private final HikariDataSource source;

        AnalyticsPool(HikariDataSource source) {
            this.source = source;
        }

        HikariDataSource source() {
            return source;
        }

        @Override
        public void close() {
            source.close();
        }
    }

    /** Same URL, credentials and driver properties (socket and statement timeouts) as the primary pool. */
    @Bean(destroyMethod = "close")
    AnalyticsPool analyticsPool(
            DataSourceProperties properties,
            HikariDataSource primary,
            ShortenerProperties shortener,
            MeterRegistry meters) {
        ShortenerProperties.Analytics settings = shortener.analytics();
        HikariDataSource pool = properties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        pool.setDataSourceProperties(primary.getDataSourceProperties());
        pool.setPoolName(POOL_NAME);
        pool.setMaximumPoolSize(settings.poolSize());
        pool.setMinimumIdle(1);
        pool.setConnectionTimeout(settings.connectionTimeout().toMillis());
        pool.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(meters));
        return new AnalyticsPool(pool);
    }

    @Bean
    CoalescingVisitRecorder analyticsRecorder(
            AnalyticsPool pool, Clock clock, ShortenerProperties shortener, MeterRegistry meters) {
        ShortenerProperties.Analytics settings = shortener.analytics();
        var jdbc = new JdbcTemplate(pool.source());
        jdbc.setQueryTimeout(Math.toIntExact(settings.queryTimeout().toSeconds()));
        return new CoalescingVisitRecorder(
                new JdbcRedirectStatsWriter(jdbc, settings.flushBatchSize()), clock, settings, meters);
    }
}
