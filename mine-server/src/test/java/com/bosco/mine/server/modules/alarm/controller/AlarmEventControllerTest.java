package com.bosco.mine.server.modules.alarm.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.bootstrap.web.TraceIdFilter;
import com.bosco.mine.server.modules.alarm.domain.AlarmStatus;
import com.bosco.mine.server.modules.alarm.dto.AlarmAcknowledgeRequest;
import com.bosco.mine.server.modules.alarm.dto.AlarmCloseRequest;
import com.bosco.mine.server.modules.alarm.dto.AlarmEventResponse;
import com.bosco.mine.server.modules.alarm.service.AlarmEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验告警中心分页、幂等处置请求和权限声明。
 */
class AlarmEventControllerTest {
    private static final UUID ALARM_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000441");
    private AlarmEventService service;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建告警接口测试环境() {
        service = mock(AlarmEventService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AlarmEventController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void 告警列表应透传组合过滤并返回推导状态() throws Exception {
        AlarmEventResponse alarm = alarm(AlarmStatus.ACTIVE_UNACKNOWLEDGED, 2);
        when(service.findGlobal(
                null, false, null, "CRITICAL", alarm.deviceId(), "运输车",
                "engine-hot", null, null, 1, 20))
                .thenReturn(new PageResponse<>(List.of(alarm), 1, 20, 1));

        mockMvc.perform(get("/api/v1/alarms")
                        .param("closed", "false")
                        .param("severity", "CRITICAL")
                        .param("deviceId", alarm.deviceId().toString())
                        .param("deviceKeyword", "运输车")
                        .param("ruleKey", "engine-hot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(ALARM_ID.toString()))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE_UNACKNOWLEDGED"))
                .andExpect(jsonPath("$.items[0].deviceName").value("一号运输车"));
    }

    @Test
    void 确认和关闭应要求幂等键并返回最新事实() throws Exception {
        when(service.acknowledgeCurrentUser(ALARM_ID, "已通知调度", "ack-001", 2))
                .thenReturn(alarm(AlarmStatus.ACTIVE_ACKNOWLEDGED, 3));
        when(service.closeCurrentUser(ALARM_ID, "现场复核完成", "close-001", 3))
                .thenReturn(alarm(AlarmStatus.CLOSED, 4));

        mockMvc.perform(post("/api/v1/alarms/{id}/acknowledgements", ALARM_ID)
                        .header("Idempotency-Key", "ack-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"已通知调度","version":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE_ACKNOWLEDGED"));
        mockMvc.perform(post("/api/v1/alarms/{id}/close", ALARM_ID)
                        .header("Idempotency-Key", "close-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"现场复核完成","version":3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        verify(service).acknowledgeCurrentUser(ALARM_ID, "已通知调度", "ack-001", 2);
        verify(service).closeCurrentUser(ALARM_ID, "现场复核完成", "close-001", 3);
    }

    @Test
    void 缺少幂等键或空白原因应返回参数错误() throws Exception {
        mockMvc.perform(post("/api/v1/alarms/{id}/acknowledgements", ALARM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"已通知调度","version":2}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/alarms/{id}/close", ALARM_ID)
                        .header("Idempotency-Key", "close-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note":"   ","version":3}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 所有告警入口都应声明最小权限() throws Exception {
        assertPermission("findAlarms", "alarm:event:view",
                Boolean.class, Boolean.class, AlarmStatus.class, String.class, UUID.class, String.class, String.class,
                Instant.class, Instant.class, int.class, int.class);
        assertPermission("findAlarm", "alarm:event:view", UUID.class);
        assertPermission("acknowledge", "alarm:event:ack",
                UUID.class, String.class, AlarmAcknowledgeRequest.class);
        assertPermission("close", "alarm:event:close",
                UUID.class, String.class, AlarmCloseRequest.class);
    }

    private AlarmEventResponse alarm(AlarmStatus status, long version) {
        Instant raisedAt = Instant.parse("2026-07-17T01:00:00Z");
        Instant recoveredAt = status == AlarmStatus.CLOSED ? raisedAt.plusSeconds(300) : null;
        Instant acknowledgedAt = switch (status) {
            case ACTIVE_ACKNOWLEDGED, RECOVERED_ACKNOWLEDGED, CLOSED -> raisedAt.plusSeconds(60);
            default -> null;
        };
        return new AlarmEventResponse(
                ALARM_ID, UUID.fromString("019f64e5-a000-7000-8000-000000000442"),
                "truck-001", "一号运输车", "haul-truck", "engine-hot", "发动机过热",
                "NUMERIC", "CRITICAL", "engineTemp", status, 7,
                UUID.fromString("019f64e5-a000-7000-8000-000000000443"),
                "sha256:" + "a".repeat(64), raisedAt, Map.of("observedValue", 90),
                recoveredAt, recoveredAt == null ? Map.of() : Map.of("observedValue", 70),
                recoveredAt == null ? null : "CONDITION_CLEARED", acknowledgedAt,
                acknowledgedAt == null ? null : UUID.randomUUID(),
                acknowledgedAt == null ? null : "已通知调度",
                status == AlarmStatus.CLOSED ? raisedAt.plusSeconds(600) : null,
                status == AlarmStatus.CLOSED ? UUID.randomUUID() : null,
                status == AlarmStatus.CLOSED ? "现场复核完成" : null,
                version
        );
    }

    private void assertPermission(String method, String permission, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        SaCheckPermission annotation = AlarmEventController.class
                .getMethod(method, parameterTypes)
                .getAnnotation(SaCheckPermission.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).containsExactly(permission);
    }
}
