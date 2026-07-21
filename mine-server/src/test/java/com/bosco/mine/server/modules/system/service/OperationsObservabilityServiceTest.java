package com.bosco.mine.server.modules.system.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验可观测性历史会转换为稳定、可降级的运行结论。
 */
class OperationsObservabilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T06:00:00Z");

    @Test
    void 应计算可用率并将异常检查项排在正常项之前() {
        OperationsObservabilityQuery query = (range, keys) -> new OperationsObservabilityQuery.QueryResult(
                true,
                NOW,
                List.of(
                        history("mine-server", "SERVICE", point("05:58:00", 1), point("05:59:00", 1)),
                        history("rabbitmq", "MESSAGE", point("05:57:00", 1), point("05:58:00", 0), point("05:59:00", 0))
                )
        );
        OperationsObservabilityService service = new OperationsObservabilityService(
                query, Clock.fixed(NOW, ZoneOffset.UTC)
        );

        OperationsObservabilityService.ObservabilityOverview result = service.overview(
                OperationsObservabilityService.TimeRange.DAY
        );

        assertThat(result.dataStatus()).isEqualTo("AVAILABLE");
        assertThat(result.status()).isEqualTo("DEGRADED");
        assertThat(result.healthy()).isEqualTo(1);
        assertThat(result.down()).isEqualTo(1);
        assertThat(result.unknown()).isEqualTo(5);
        assertThat(result.items()).extracting(OperationsObservabilityService.ObservabilityItem::key)
                .containsExactly("rabbitmq", "mine-server", "mine-gateway", "mine-telemetry", "postgres", "redis", "timescale");
        assertThat(result.items().getFirst().availabilityPercent()).isEqualTo(33.3);
        assertThat(result.items().getFirst().lastChangedAt()).isEqualTo(Instant.parse("2026-07-16T05:58:00Z"));
    }

    @Test
    void 查询源不可用时所有检查项都应为未知() {
        OperationsObservabilityQuery query = (range, keys) -> new OperationsObservabilityQuery.QueryResult(
                false, NOW, List.of()
        );
        OperationsObservabilityService service = new OperationsObservabilityService(
                query, Clock.fixed(NOW, ZoneOffset.UTC)
        );

        OperationsObservabilityService.ObservabilityOverview result = service.overview(
                OperationsObservabilityService.TimeRange.HOUR
        );

        assertThat(result.dataStatus()).isEqualTo("UNAVAILABLE");
        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.items()).hasSize(7).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo("UNKNOWN");
            assertThat(item.availabilityPercent()).isNull();
        });
    }

    @Test
    void 单项趋势只允许查询已注册检查项() {
        OperationsObservabilityQuery query = (range, keys) -> {
            assertThat(keys).isEqualTo(Set.of("redis"));
            return new OperationsObservabilityQuery.QueryResult(
                    true, NOW, List.of(history("redis", "STORAGE", point("05:59:00", 1)))
            );
        };
        OperationsObservabilityService service = new OperationsObservabilityService(
                query, Clock.fixed(NOW, ZoneOffset.UTC)
        );

        OperationsObservabilityService.ObservabilitySeries result = service.series(
                "redis", OperationsObservabilityService.TimeRange.SIX_HOURS
        );

        assertThat(result.key()).isEqualTo("redis");
        assertThat(result.points()).hasSize(1);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> service.series("arbitrary-promql", OperationsObservabilityService.TimeRange.SIX_HOURS)
        );
    }

    private static OperationsObservabilityQuery.ItemHistory history(
            String key,
            String category,
            OperationsObservabilityQuery.StatusPoint... points
    ) {
        return new OperationsObservabilityQuery.ItemHistory(key, category, List.of(points), List.of());
    }

    private static OperationsObservabilityQuery.StatusPoint point(String time, int value) {
        return new OperationsObservabilityQuery.StatusPoint(
                Instant.parse("2026-07-16T" + time + "Z"), value
        );
    }
}
