package com.bosco.mine.server.modules.alarm.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.bootstrap.web.TraceIdFilter;
import com.bosco.mine.server.modules.alarm.domain.AlarmStatus;
import com.bosco.mine.server.modules.alarm.dto.AlarmEventResponse;
import com.bosco.mine.server.modules.alarm.service.AlarmEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验矿区场景的车载设备告警查询始终带矿区边界。
 */
class MineAlarmQueryControllerTest {
    private static final UUID SITE_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000440");
    private AlarmEventService service;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建矿区告警接口测试环境() {
        service = mock(AlarmEventService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MineAlarmQueryController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void 矿区路径应强制透传作用域() throws Exception {
        when(service.findForSite(
                SITE_ID, null, false, null, "CRITICAL", null, null,
                null, null, null, 1, 20))
                .thenReturn(new PageResponse<>(List.of(), 1, 20, 0));

        mockMvc.perform(get("/api/v1/mine/sites/{siteId}/alarms", SITE_ID)
                        .param("closed", "false")
                        .param("severity", "CRITICAL"))
                .andExpect(status().isOk());

        verify(service).findForSite(
                SITE_ID, null, false, null, "CRITICAL", null, null,
                null, null, null, 1, 20);
    }

    @Test
    void 矿区告警应接受场景最小权限或全局告警查看权限() throws NoSuchMethodException {
        SaCheckPermission annotation = MineAlarmQueryController.class.getMethod(
                        "findAlarms", UUID.class, Boolean.class, Boolean.class, AlarmStatus.class,
                        String.class, UUID.class, String.class, String.class, Instant.class,
                        Instant.class, int.class, int.class)
                .getAnnotation(SaCheckPermission.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).containsExactly("mine:alarm:view", "alarm:event:view");
        assertThat(annotation.mode()).isEqualTo(SaMode.OR);
    }
}
