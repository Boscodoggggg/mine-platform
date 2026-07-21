package com.bosco.mine.server.modules.dashboard.service;

import com.bosco.mine.server.modules.dashboard.repository.OperationsDashboardRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 校验调度首页在业务库和遥测库之间进行可解释的应用层聚合。
 */
class OperationsDashboardServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T02:30:00Z");
    private static final UUID REPORTING_ID = UUID.fromString("019b0000-0000-7000-8000-000000000501");
    private static final UUID STALE_ID = UUID.fromString("019b0000-0000-7000-8000-000000000502");
    private static final UUID NEVER_ID = UUID.fromString("019b0000-0000-7000-8000-000000000503");

    @Test
    void 应区分正常上报数据中断和从未上报设备() {
        OperationsDashboardRepository repository = mock(OperationsDashboardRepository.class);
        when(repository.findEnabledDevices()).thenReturn(List.of(
                device(REPORTING_ID, "truck-001", "一号矿卡"),
                device(STALE_ID, "truck-002", "二号矿卡"),
                device(NEVER_ID, "truck-003", "三号矿卡")
        ));
        when(repository.readTelemetry(any(), any())).thenReturn(
                new OperationsDashboardRepository.TelemetrySnapshot(
                        Map.of(
                                REPORTING_ID, NOW.minusSeconds(30),
                                STALE_ID, NOW.minusSeconds(900)
                        ),
                        12500,
                        NOW.minusSeconds(2),
                        List.of(new OperationsDashboardRepository.HourCount(
                                Instant.parse("2026-07-16T02:00:00Z"), 730
                        ))
                )
        );
        OperationsDashboardService service = service(repository);

        OperationsDashboardService.OperationsOverview result = service.overview();

        assertThat(result.dataStatus()).isEqualTo("AVAILABLE");
        assertThat(result.devices()).isEqualTo(new OperationsDashboardService.DeviceSummary(3, 1L, 1L, 1L));
        assertThat(result.telemetry().todayMessages()).isEqualTo(12500);
        assertThat(result.telemetry().hourly()).hasSize(24);
        assertThat(result.telemetry().hourly().getFirst().count()).isZero();
        assertThat(result.telemetry().hourly().getLast().count()).isEqualTo(730);
        assertThat(result.attentionDevices()).extracting(OperationsDashboardService.AttentionDevice::state)
                .containsExactly("NEVER_REPORTED", "STALE");
    }

    @Test
    void 遥测库不可用时不应把未知指标伪装成零() {
        OperationsDashboardRepository repository = mock(OperationsDashboardRepository.class);
        when(repository.findEnabledDevices()).thenReturn(List.of(device(REPORTING_ID, "truck-001", "一号矿卡")));
        when(repository.readTelemetry(any(), any())).thenThrow(new IllegalStateException("连接包含敏感信息"));
        OperationsDashboardService service = service(repository);

        OperationsDashboardService.OperationsOverview result = service.overview();

        assertThat(result.dataStatus()).isEqualTo("UNAVAILABLE");
        assertThat(result.devices().enabled()).isEqualTo(1);
        assertThat(result.devices().reporting()).isNull();
        assertThat(result.devices().stale()).isNull();
        assertThat(result.devices().neverReported()).isNull();
        assertThat(result.telemetry().todayMessages()).isNull();
        assertThat(result.attentionDevices()).isEmpty();
        assertThat(result.toString()).doesNotContain("连接包含敏感信息");
    }

    private OperationsDashboardService service(OperationsDashboardRepository repository) {
        return new OperationsDashboardService(
                repository,
                Duration.ofMinutes(5),
                ZoneId.of("Asia/Shanghai"),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private OperationsDashboardRepository.DashboardDevice device(UUID id, String code, String name) {
        return new OperationsDashboardRepository.DashboardDevice(id, code, name);
    }
}
