package com.bosco.mine.server.modules.device.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.bootstrap.web.TraceIdFilter;
import com.bosco.mine.server.modules.device.dto.DeviceLatestResponse;
import com.bosco.mine.server.modules.device.dto.TelemetryHistoryPointResponse;
import com.bosco.mine.server.modules.device.dto.TelemetryHistoryResponse;
import com.bosco.mine.server.modules.device.dto.TelemetryLatestItemResponse;
import com.bosco.mine.server.modules.device.service.DeviceTelemetryQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验设备最新遥测和历史遥测查询接口契约。
 */
class DeviceTelemetryControllerTest {

    private DeviceTelemetryQueryService queryService;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建设备遥测接口测试环境() {
        queryService = mock(DeviceTelemetryQueryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DeviceTelemetryController(queryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void 最新值应返回质量来源和缺失属性() throws Exception {
        UUID deviceId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-07-15T01:02:03Z");
        Instant receivedAt = Instant.parse("2026-07-15T01:02:04Z");
        when(queryService.findLatest(deviceId, List.of("speed", "rpm")))
                .thenReturn(new DeviceLatestResponse(
                        deviceId,
                        List.of(new TelemetryLatestItemResponse(
                                "speed", 12.5D, occurredAt, receivedAt, Quality.GOOD,
                                eventId, "boot-a", 7L, "REDIS"
                        )),
                        List.of("rpm")
                ));

        mockMvc.perform(get("/api/v1/devices/{id}/latest", deviceId)
                        .param("propertyIds", "speed", "rpm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(deviceId.toString()))
                .andExpect(jsonPath("$.items[0].propertyId").value("speed"))
                .andExpect(jsonPath("$.items[0].quality").value("GOOD"))
                .andExpect(jsonPath("$.items[0].source").value("REDIS"))
                .andExpect(jsonPath("$.missingPropertyIds[0]").value("rpm"));
    }

    @Test
    void 历史查询应返回分页聚合结果和最差质量() throws Exception {
        UUID deviceId = UUID.randomUUID();
        Instant from = Instant.parse("2026-07-15T00:00:00Z");
        Instant to = Instant.parse("2026-07-15T01:00:00Z");
        Duration bucket = Duration.ofMinutes(5);
        when(queryService.findHistory(
                deviceId, "speed", from, to, "AVG", bucket, null, 1, 100
        )).thenReturn(new TelemetryHistoryResponse(
                deviceId,
                "speed",
                "AVG",
                bucket,
                42L,
                List.of(new TelemetryHistoryPointResponse(
                        from, to, 10.25D, Quality.UNCERTAIN, null, 12L
                )),
                1,
                100,
                1
        ));

        mockMvc.perform(get("/api/v1/devices/{id}/telemetry", deviceId)
                        .param("propertyId", "speed")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("aggregation", "AVG")
                        .param("bucket", "PT5M"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggregation").value("AVG"))
                .andExpect(jsonPath("$.bucket").value("PT5M"))
                .andExpect(jsonPath("$.watermark").value(42))
                .andExpect(jsonPath("$.items[0].sampleCount").value(12))
                .andExpect(jsonPath("$.items[0].quality").value("UNCERTAIN"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void 缺少最新值属性列表应返回参数错误() throws Exception {
        mockMvc.perform(get("/api/v1/devices/{id}/latest", UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST-400-001"));
    }

    @Test
    void 遥测入口应声明独立只读权限() throws Exception {
        assertPermission("findLatest", "device:telemetry:view",
                UUID.class, List.class);
        assertPermission("findHistory", "device:telemetry:view",
                UUID.class, String.class, Instant.class, Instant.class,
                String.class, Duration.class, Long.class, int.class, int.class);
    }

    private void assertPermission(String methodName, String permission, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        SaCheckPermission annotation = DeviceTelemetryController.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(SaCheckPermission.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).containsExactly(permission);
    }
}
