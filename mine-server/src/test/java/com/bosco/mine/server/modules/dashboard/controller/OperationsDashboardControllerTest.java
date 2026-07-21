package com.bosco.mine.server.modules.dashboard.controller;

import com.bosco.mine.server.modules.dashboard.service.OperationsDashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验调度首页聚合接口只返回具备明确口径的生产运行数据。
 */
class OperationsDashboardControllerTest {

    private OperationsDashboardService service;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建调度首页接口测试环境() {
        service = mock(OperationsDashboardService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new OperationsDashboardController(service)).build();
    }

    @Test
    void 应返回设备遥测趋势和关注设备() throws Exception {
        Instant generatedAt = Instant.parse("2026-07-16T02:30:00Z");
        UUID deviceId = UUID.fromString("019b0000-0000-7000-8000-000000000501");
        when(service.overview()).thenReturn(new OperationsDashboardService.OperationsOverview(
                generatedAt,
                "AVAILABLE",
                300,
                new OperationsDashboardService.DeviceSummary(12, 8L, 3L, 1L),
                new OperationsDashboardService.TelemetrySummary(
                        12500L,
                        Instant.parse("2026-07-16T02:29:58Z"),
                        List.of(new OperationsDashboardService.HourBucket(
                                Instant.parse("2026-07-16T02:00:00Z"), 730
                        ))
                ),
                List.of(new OperationsDashboardService.AttentionDevice(
                        deviceId,
                        "truck-009",
                        "九号矿卡",
                        "STALE",
                        Instant.parse("2026-07-16T02:10:00Z")
                ))
        ));

        mockMvc.perform(get("/api/v1/dashboard/operations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").value("2026-07-16T02:30:00Z"))
                .andExpect(jsonPath("$.dataStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.freshnessSeconds").value(300))
                .andExpect(jsonPath("$.devices.enabled").value(12))
                .andExpect(jsonPath("$.devices.reporting").value(8))
                .andExpect(jsonPath("$.devices.stale").value(3))
                .andExpect(jsonPath("$.devices.neverReported").value(1))
                .andExpect(jsonPath("$.telemetry.todayMessages").value(12500))
                .andExpect(jsonPath("$.telemetry.hourly[0].count").value(730))
                .andExpect(jsonPath("$.attentionDevices[0].deviceCode").value("truck-009"))
                .andExpect(jsonPath("$.attentionDevices[0].state").value("STALE"));
    }
}
