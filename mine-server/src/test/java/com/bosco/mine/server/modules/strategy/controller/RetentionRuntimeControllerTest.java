package com.bosco.mine.server.modules.strategy.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.modules.strategy.service.RetentionRuntimeService;
import jakarta.validation.constraints.Max;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * 校验保留预览与执行历史 HTTP 契约、参数门禁和权限声明。
 */
class RetentionRuntimeControllerTest {

    private static final UUID PRODUCT_ID =
            UUID.fromString("019f6e20-0000-7000-8000-000000000511");
    private static final UUID VERSION_ID =
            UUID.fromString("019f6e20-0000-7000-8000-000000000512");
    private static final UUID PREVIEW_TOKEN =
            UUID.fromString("019f6e20-0000-7000-8000-000000000513");
    private static final Instant OBSERVED_AT = Instant.parse("2026-07-17T09:00:00Z");

    private RetentionRuntimeService service;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建控制器() {
        service = mock(RetentionRuntimeService.class);
        mockMvc = standaloneSetup(new RetentionRuntimeController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 预览接口应返回统计时点上限标记和两个数据集() throws Exception {
        when(service.preview(any())).thenReturn(new RetentionRuntimeService.RetentionPreviewView(
                PREVIEW_TOKEN,
                OBSERVED_AT,
                OBSERVED_AT.plusSeconds(900),
                15,
                12,
                10,
                new RetentionRuntimeService.PolicyView(true, "STANDARD", 90, 180),
                new RetentionRuntimeService.DatasetImpactView(
                        1_000_000, Instant.parse("2025-01-01T00:00:00Z"), true),
                new RetentionRuntimeService.DatasetImpactView(
                        32, Instant.parse("2025-02-01T00:00:00Z"), false)
        ));

        mockMvc.perform(post("/api/v1/retention-previews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productId":"%s",
                                  "strategyVersionId":"%s",
                                  "scopeType":"PRODUCT",
                                  "scopeId":"%s"
                                }
                                """.formatted(PRODUCT_ID, VERSION_ID, PRODUCT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewToken").value(PREVIEW_TOKEN.toString()))
                .andExpect(jsonPath("$.observedAt").value(OBSERVED_AT.toString()))
                .andExpect(jsonPath("$.targetDeviceCount").value(15))
                .andExpect(jsonPath("$.affectedDeviceCount").value(12))
                .andExpect(jsonPath("$.enabledDeviceCount").value(10))
                .andExpect(jsonPath("$.rawTelemetry.eligibleRows").value(1_000_000))
                .andExpect(jsonPath("$.rawTelemetry.atLeast").value(true))
                .andExpect(jsonPath("$.qualityEvidence.eligibleRows").value(32));
    }

    @Test
    void 预览接口应拒绝缺失作用域和版本() throws Exception {
        mockMvc.perform(post("/api/v1/retention-previews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productId":"%s",
                                  "scopeType":"PRODUCT"
                                }
                                """.formatted(PRODUCT_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST-400-001"));
        verifyNoInteractions(service);
    }

    @Test
    void 设备组成员拟变更预览应返回是否需要不可逆激活凭证() throws Exception {
        UUID groupId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        RetentionRuntimeService.RetentionPreviewView preview =
                new RetentionRuntimeService.RetentionPreviewView(
                        PREVIEW_TOKEN, OBSERVED_AT, OBSERVED_AT.plusSeconds(900),
                        1, 1, 1,
                        new RetentionRuntimeService.PolicyView(true, "SHORT", 30, 60),
                        new RetentionRuntimeService.DatasetImpactView(500, OBSERVED_AT, false),
                        new RetentionRuntimeService.DatasetImpactView(20, OBSERVED_AT, false));
        when(service.previewGroupChange(any())).thenReturn(
                new RetentionRuntimeService.GroupChangePreviewView(true, preview));

        mockMvc.perform(post("/api/v1/retention-group-change-previews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "groupId":"%s",
                                  "changeType":"MEMBERSHIP",
                                  "groupVersion":2,
                                  "deviceIds":["%s"]
                                }
                                """.formatted(groupId, deviceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.required").value(true))
                .andExpect(jsonPath("$.preview.previewToken").value(PREVIEW_TOKEN.toString()))
                .andExpect(jsonPath("$.preview.affectedDeviceCount").value(1));

        SaCheckPermission permission = RetentionRuntimeController.class
                .getMethod("previewGroupChange",
                        RetentionRuntimeController.GroupChangePreviewRequest.class)
                .getAnnotation(SaCheckPermission.class);
        assertThat(permission.value())
                .containsExactly("device:group:edit", "device:strategy:publish");
    }

    @Test
    void 执行历史接口应限制最多一百条并返回失败摘要() throws Exception {
        UUID executionId = UUID.randomUUID();
        when(service.recentExecutions(20)).thenReturn(List.of(
                new RetentionRuntimeService.ExecutionView(
                        executionId, "telemetry-a", "FAILED", 2,
                        10, 3, 2, OBSERVED_AT.minusSeconds(30), OBSERVED_AT,
                        "query timeout"
                )
        ));

        mockMvc.perform(get("/api/v1/retention-executions").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].executionId").value(executionId.toString()))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].lastError").value("query timeout"));

        Max maximum = RetentionRuntimeController.class
                .getMethod("recentExecutions", int.class)
                .getParameters()[0]
                .getAnnotation(Max.class);
        assertThat(maximum).isNotNull();
        assertThat(maximum.value()).isEqualTo(100);
    }

    @Test
    void 遥测查询不可用时应返回脱敏五零三() throws Exception {
        when(service.preview(any())).thenThrow(new MineException(
                new ErrorCode("RETENTION-503-001", "遥测保留运行查询暂时不可用"),
                Map.of("password", "secret")
        ));

        mockMvc.perform(post("/api/v1/retention-previews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productId":"%s",
                                  "strategyVersionId":"%s",
                                  "scopeType":"PRODUCT",
                                  "scopeId":"%s"
                                }
                                """.formatted(PRODUCT_ID, VERSION_ID, PRODUCT_ID)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RETENTION-503-001"))
                .andExpect(jsonPath("$.message").value("服务暂时不可用"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    void 两个运行查询入口都应声明策略查看权限() throws Exception {
        assertPermission("preview", RetentionRuntimeController.RetentionPreviewRequest.class);
        assertPermission("recentExecutions", int.class);
    }

    private void assertPermission(String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        SaCheckPermission annotation = RetentionRuntimeController.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(SaCheckPermission.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).containsExactly("device:strategy:view");
    }
}
