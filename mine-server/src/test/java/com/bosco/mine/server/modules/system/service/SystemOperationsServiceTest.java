package com.bosco.mine.server.modules.system.service;

import com.bosco.mine.server.modules.system.infrastructure.ServiceHealthProbe;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验系统运行监控会隔离单项探测失败并输出脱敏结果。
 */
class SystemOperationsServiceTest {

    @Test
    void 单个依赖探测失败不应导致整个运行监控失败() {
        ServiceHealthProbe healthy = () -> new ServiceHealthProbe.ProbeResult(
                "mine-server", "SERVICE", "UP", 3, "serviceUp"
        );
        ServiceHealthProbe failed = () -> {
            throw new IllegalStateException("jdbc:postgresql://secret-host/mine?password=secret");
        };
        SystemOperationsService service = new SystemOperationsService(
                List.of(healthy, failed),
                Duration.ofMillis(100),
                Clock.fixed(Instant.parse("2026-07-16T03:00:00Z"), ZoneOffset.UTC)
        );

        SystemOperationsService.SystemOperationsOverview result = service.overview();

        assertThat(result.checkedAt()).isEqualTo(Instant.parse("2026-07-16T03:00:00Z"));
        assertThat(result.status()).isEqualTo("DEGRADED");
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).status()).isEqualTo("UP");
        assertThat(result.items().get(1).status()).isEqualTo("UNKNOWN");
        assertThat(result.toString()).doesNotContain("secret-host", "password", "secret");
    }

    @Test
    void 单项探测超过统一时限时应及时降级且不阻塞其他结果() {
        ServiceHealthProbe slow = () -> {
            try {
                Thread.sleep(Duration.ofSeconds(5));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new ServiceHealthProbe.ProbeResult("slow", "STORAGE", "UP", 5000, "serviceUp");
        };
        ServiceHealthProbe healthy = () -> new ServiceHealthProbe.ProbeResult(
                "mine-server", "SERVICE", "UP", 1, "serviceUp"
        );
        SystemOperationsService service = new SystemOperationsService(
                List.of(slow, healthy), Duration.ofMillis(50), Clock.systemUTC()
        );

        long startedAt = System.nanoTime();
        SystemOperationsService.SystemOperationsOverview result = service.overview();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(elapsedMillis).isLessThan(1000);
        assertThat(result.items()).extracting(SystemOperationsService.OperationItem::status)
                .containsExactly("UNKNOWN", "UP");
        assertThat(result.items().getFirst().summary()).isEqualTo("checkTimeout");
    }
}
