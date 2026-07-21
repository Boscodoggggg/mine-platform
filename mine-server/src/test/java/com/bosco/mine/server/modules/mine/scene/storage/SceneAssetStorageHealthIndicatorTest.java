package com.bosco.mine.server.modules.mine.scene.storage;

import com.bosco.mine.server.modules.mine.scene.SceneMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 验证场景存储健康探针有界执行、脱敏失败并同步更新可用性指标。
 */
class SceneAssetStorageHealthIndicatorTest {

    @Test
    void 并发健康请求应复用同轮探针且不得制造假Down() throws Exception {
        SceneAssetStorage storage = mock(SceneAssetStorage.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await();
            return null;
        }).when(storage).probe();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SceneMetrics metrics = new SceneMetrics(registry, () -> 0D);

        try (SceneAssetStorageHealthIndicator indicator =
                     new SceneAssetStorageHealthIndicator(
                             storage, metrics, Duration.ofMillis(500));
             ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Health> first = callers.submit(() -> indicator.health());
            assertThat(entered.await(200, TimeUnit.MILLISECONDS)).isTrue();
            Future<Health> second = callers.submit(() -> indicator.health());
            release.countDown();

            assertThat(first.get().getStatus().getCode()).isEqualTo("UP");
            assertThat(second.get().getStatus().getCode()).isEqualTo("UP");
            verify(storage, times(1)).probe();
            assertThat(registry.get("mine.server.scene.storage.available")
                    .gauge().value()).isOne();
        }
    }

    @Test
    void 应用启动后应主动刷新存储Gauge而不依赖健康端点访问() throws Exception {
        SceneAssetStorage storage = mock(SceneAssetStorage.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SceneMetrics metrics = new SceneMetrics(registry, () -> 0D);

        try (SceneAssetStorageHealthIndicator indicator =
                     new SceneAssetStorageHealthIndicator(
                             storage, metrics, Duration.ofMillis(200))) {
            indicator.startMonitoring();

            verify(storage, timeout(500)).probe();
            assertThat(awaitGauge(registry)).isOne();
        }
    }

    @Test
    void 专用存储探针成功时应返回健康并更新Gauge() throws IOException {
        SceneAssetStorage storage = mock(SceneAssetStorage.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SceneMetrics metrics = new SceneMetrics(registry, () -> 0D);

        try (SceneAssetStorageHealthIndicator indicator =
                     new SceneAssetStorageHealthIndicator(
                             storage, metrics, Duration.ofMillis(200))) {
            Health health = indicator.health();

            assertThat(health.getStatus().getCode()).isEqualTo("UP");
            assertThat(health.getDetails())
                    .containsEntry("component", "scene-asset-storage")
                    .containsEntry("fallback", "2d-ready");
            assertThat(registry.get("mine.server.scene.storage.available")
                    .gauge().value()).isOne();
            verify(storage).probe();
        }
    }

    @Test
    void 存储失败不得泄漏路径或异常且只降级三维能力() throws IOException {
        SceneAssetStorage storage = mock(SceneAssetStorage.class);
        doThrow(new IOException("s3://secret-bucket/customer/path token=secret"))
                .when(storage).probe();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SceneMetrics metrics = new SceneMetrics(registry, () -> 0D);

        try (SceneAssetStorageHealthIndicator indicator =
                     new SceneAssetStorageHealthIndicator(
                             storage, metrics, Duration.ofMillis(200))) {
            Health health = indicator.health();

            assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
            assertThat(health.getDetails())
                    .containsEntry("reason", "probe-failed")
                    .containsEntry("fallback", "2d-ready");
            assertThat(health.toString())
                    .doesNotContain("secret-bucket", "customer/path", "IOException");
            assertThat(registry.get("mine.server.scene.storage.available")
                    .gauge().value()).isZero();
        }
    }

    @Test
    void 阻塞探针必须在固定超时内返回脱敏降级() throws IOException {
        SceneAssetStorage storage = mock(SceneAssetStorage.class);
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            new CountDownLatch(1).await();
            return null;
        }).when(storage).probe();
        SceneMetrics metrics = new SceneMetrics(new SimpleMeterRegistry(), () -> 0D);

        long started = System.nanoTime();
        try (SceneAssetStorageHealthIndicator indicator =
                     new SceneAssetStorageHealthIndicator(
                             storage, metrics, Duration.ofMillis(30))) {
            Health health = indicator.health();

            assertThat(entered.await(100, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
            assertThat(health.getDetails()).containsEntry("reason", "probe-timeout");
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofMillis(500));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private double awaitGauge(SimpleMeterRegistry registry) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMillis(500).toNanos();
        double value;
        do {
            value = registry.get("mine.server.scene.storage.available").gauge().value();
            if (!Double.isNaN(value)) {
                return value;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        return value;
    }
}
