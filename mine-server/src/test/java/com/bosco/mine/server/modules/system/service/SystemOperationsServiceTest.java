package com.bosco.mine.server.modules.system.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.system.infrastructure.ServiceHealthProbe;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void 应按稳定标识独立执行单个探测() {
        ServiceHealthProbe redis = new ServiceHealthProbe() {
            @Override
            public ProbeResult probe() {
                return new ProbeResult("redis", "STORAGE", "UP", 4, "connectionUp");
            }

            @Override
            public String key() {
                return "redis";
            }

            @Override
            public String category() {
                return "STORAGE";
            }
        };
        SystemOperationsService service = new SystemOperationsService(
                List.of(redis), Duration.ofMillis(100), Clock.systemUTC()
        );

        SystemOperationsService.OperationItem result = service.check("redis");

        assertThat(result).isEqualTo(new SystemOperationsService.OperationItem(
                "redis", "STORAGE", "UP", 4, "connectionUp"
        ));
    }

    @Test
    void 单个探测超过时限时应返回超时结果() {
        ServiceHealthProbe slow = new ServiceHealthProbe() {
            @Override
            public ProbeResult probe() {
                try {
                    Thread.sleep(Duration.ofSeconds(5));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return new ProbeResult("redis", "STORAGE", "UP", 5000, "connectionUp");
            }

            @Override
            public String key() {
                return "redis";
            }

            @Override
            public String category() {
                return "STORAGE";
            }
        };
        SystemOperationsService service = new SystemOperationsService(
                List.of(slow), Duration.ofMillis(50), Clock.systemUTC()
        );

        SystemOperationsService.OperationItem result = service.check("redis");

        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.summary()).isEqualTo("checkTimeout");
    }

    @Test
    void 未注册的检查项应返回稳定的资源不存在错误() {
        SystemOperationsService service = new SystemOperationsService(
                List.of(), Duration.ofMillis(100), Clock.systemUTC()
        );

        assertThatThrownBy(() -> service.check("missing"))
                .isInstanceOfSatisfying(MineException.class, exception -> {
                    assertThat(exception.errorCode().code()).isEqualTo("SYSTEM-404-006");
                    assertThat(exception.details()).containsEntry("key", "missing");
                });
    }
}
