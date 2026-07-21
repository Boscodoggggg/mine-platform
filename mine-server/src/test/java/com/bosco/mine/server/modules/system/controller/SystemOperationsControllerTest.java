package com.bosco.mine.server.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.modules.system.service.OperationsObservabilityService;
import com.bosco.mine.server.modules.system.service.SystemOperationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验系统运行监控接口契约和独立权限声明。
 */
class SystemOperationsControllerTest {

    private SystemOperationsService service;
    private OperationsObservabilityService observabilityService;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建系统运行监控接口测试环境() {
        service = mock(SystemOperationsService.class);
        observabilityService = mock(OperationsObservabilityService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new SystemOperationsController(service, observabilityService)
        ).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void 应返回历史运行总览和单项趋势() throws Exception {
        when(observabilityService.overview(OperationsObservabilityService.TimeRange.DAY))
                .thenReturn(new OperationsObservabilityService.ObservabilityOverview(
                        "AVAILABLE", "24h", Instant.parse("2026-07-16T03:00:00Z"),
                        "DEGRADED", 5, 0, 1, 1,
                        List.of(new OperationsObservabilityService.ObservabilityItem(
                                "rabbitmq", "MESSAGE", "DOWN", 98.5,
                                null, null, Instant.parse("2026-07-16T02:58:00Z"), 288
                        ))
                ));
        when(observabilityService.series("rabbitmq", OperationsObservabilityService.TimeRange.DAY))
                .thenReturn(new OperationsObservabilityService.ObservabilitySeries(
                        "rabbitmq", "MESSAGE", "AVAILABLE", "24h",
                        Instant.parse("2026-07-16T03:00:00Z"),
                        List.of(new OperationsObservabilityService.SeriesPoint(
                                Instant.parse("2026-07-16T02:59:00Z"), "DOWN", null
                        ))
                ));

        mockMvc.perform(get("/api/v1/system/operations/overview").queryParam("range", "24h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.down").value(1))
                .andExpect(jsonPath("$.items[0].availabilityPercent").value(98.5));

        mockMvc.perform(get("/api/v1/system/operations/items/rabbitmq/series").queryParam("range", "24h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("rabbitmq"))
                .andExpect(jsonPath("$.points[0].status").value("DOWN"));
    }

    @Test
    void 应返回探测结果和检查时间() throws Exception {
        when(service.overview()).thenReturn(new SystemOperationsService.SystemOperationsOverview(
                "UP",
                Instant.parse("2026-07-16T03:00:00Z"),
                List.of(new SystemOperationsService.OperationItem(
                        "mine-server", "SERVICE", "UP", 3, "serviceUp"
                ))
        ));

        mockMvc.perform(get("/api/v1/system/operations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.checkedAt").value("2026-07-16T03:00:00Z"))
                .andExpect(jsonPath("$.items[0].key").value("mine-server"))
                .andExpect(jsonPath("$.items[0].latencyMillis").value(3));
    }

    @Test
    void 应按检查项标识返回单项即时结果() throws Exception {
        when(service.check("redis")).thenReturn(new SystemOperationsService.OperationItem(
                "redis", "STORAGE", "UP", 4, "connectionUp"
        ));

        mockMvc.perform(get("/api/v1/system/operations/items/redis/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("redis"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.latencyMillis").value(4));
    }

    @Test
    void 未注册的即时检查项应返回资源不存在() throws Exception {
        when(service.check("missing")).thenThrow(new MineException(
                new ErrorCode("SYSTEM-404-006", "运行检查项不存在"), Map.of("key", "missing")
        ));

        mockMvc.perform(get("/api/v1/system/operations/items/missing/check"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SYSTEM-404-006"));
    }

    @Test
    void 接口应声明独立的运行监控权限() throws Exception {
        SaCheckPermission permission = SystemOperationsController.class
                .getMethod("operations")
                .getAnnotation(SaCheckPermission.class);

        assertThat(permission).isNotNull();
        assertThat(permission.value()).containsExactly("system:operations:view");

        SaCheckPermission overviewPermission = SystemOperationsController.class
                .getMethod("overview", String.class)
                .getAnnotation(SaCheckPermission.class);
        SaCheckPermission seriesPermission = SystemOperationsController.class
                .getMethod("series", String.class, String.class)
                .getAnnotation(SaCheckPermission.class);
        SaCheckPermission checkPermission = SystemOperationsController.class
                .getMethod("check", String.class)
                .getAnnotation(SaCheckPermission.class);
        assertThat(overviewPermission.value()).containsExactly("system:operations:view");
        assertThat(seriesPermission.value()).containsExactly("system:operations:view");
        assertThat(checkPermission.value()).containsExactly("system:operations:view");
    }
}
