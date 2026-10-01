package dev.shortener.analytics;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class BoundedAnalyticsRecorderTest {
    @Test void doesNotWaitOrCancelColdWritesAndCountsQueueRejections() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(call -> { entered.countDown(); release.await(); return 1; }).when(jdbc).update(anyString(), anyLong());
        var pool = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1));
        var counter = new SimpleMeterRegistry().counter("failures");
        var recorder = new BoundedAnalyticsRecorder(jdbc,pool,counter);
        try {
            recorder.record(1);
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertEquals(0,counter.count(),"A pending cold write is not a failed or cancelled write");
            recorder.record(2);
            recorder.record(3);
            assertEquals(1,counter.count(),"Only the full queue drops a write");
        } finally { release.countDown(); recorder.destroy(); }
        verify(jdbc,times(2)).update(anyString(),anyLong());
    }
}
