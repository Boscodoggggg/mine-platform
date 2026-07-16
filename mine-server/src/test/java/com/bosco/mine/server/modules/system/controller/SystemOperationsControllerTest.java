package com.bosco.mine.server.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.system.service.SystemOperationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

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
    private MockMvc mockMvc;

    @BeforeEach
    void 创建系统运行监控接口测试环境() {
        service = mock(SystemOperationsService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SystemOperationsController(service)).build();
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
    void 接口应声明独立的运行监控权限() throws Exception {
        SaCheckPermission permission = SystemOperationsController.class
                .getMethod("operations")
                .getAnnotation(SaCheckPermission.class);

        assertThat(permission).isNotNull();
        assertThat(permission.value()).containsExactly("system:operations:view");
    }
}
