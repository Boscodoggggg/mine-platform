package com.bosco.mine.telemetry.spatial;

import com.bosco.mine.telemetry.location.snapshot.LocationRuntimeRegistry;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验空间评估器不会一次持有超过当前可立即处理数量的数据库租约。
 */
class SpatialEvaluationWorkerTest {

    @Test
    void 串行评估器每次只应认领一条以避免后续租约等待中过期() {
        DataSource dataSource = mock(DataSource.class);
        LocationRuntimeRegistry registry = mock(LocationRuntimeRegistry.class);
        SpatialEvaluationQueue queue = mock(SpatialEvaluationQueue.class);
        Instant now = Instant.parse("2026-07-19T04:00:00Z");
        Duration lease = Duration.ofSeconds(30);
        when(queue.claimReady("worker-1", now, lease, 1)).thenReturn(List.of());
        SpatialEvaluationWorker worker = new SpatialEvaluationWorker(
                dataSource, registry, queue, "worker-1", lease, 3,
                Duration.ofSeconds(1), Duration.ofMinutes(1),
                Clock.fixed(now, ZoneOffset.UTC));

        worker.processReady(now, 500);

        verify(queue).claimReady("worker-1", now, lease, 1);
    }

    @Test
    void 处理入口时间落后时应使用当前可信时钟认领新租约() {
        DataSource dataSource = mock(DataSource.class);
        LocationRuntimeRegistry registry = mock(LocationRuntimeRegistry.class);
        SpatialEvaluationQueue queue = mock(SpatialEvaluationQueue.class);
        Instant staleBatchTime = Instant.parse("2026-07-19T04:00:00Z");
        Instant currentTime = staleBatchTime.plusSeconds(8);
        Duration lease = Duration.ofSeconds(30);
        when(queue.claimReady("worker-2", currentTime, lease, 1)).thenReturn(List.of());
        SpatialEvaluationWorker worker = new SpatialEvaluationWorker(
                dataSource, registry, queue, "worker-2", lease, 3,
                Duration.ofSeconds(1), Duration.ofMinutes(1),
                Clock.fixed(currentTime, ZoneOffset.UTC));

        worker.processReady(staleBatchTime, 500);

        verify(queue).claimReady("worker-2", currentTime, lease, 1);
    }
}
