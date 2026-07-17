package com.bosco.mine.server.modules.aggregation.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.modules.aggregation.dto.AggregationExecutionResponse;
import com.bosco.mine.server.modules.aggregation.dto.AggregationRecomputeResponse;
import com.bosco.mine.server.modules.aggregation.service.AggregationGovernanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验聚合治理接口的权限、幂等头和有界响应合同。
 */
class AggregationGovernanceControllerTest {

    private AggregationGovernanceService service;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建控制器() {
        service = mock(AggregationGovernanceService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AggregationGovernanceController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 创建重算请求应要求幂等键并返回已接受事实() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-02T00:00:00Z");
        when(service.create(any(), any())).thenReturn(new AggregationRecomputeResponse(
                requestId, deviceId, "engine.rpm", Duration.ofMinutes(5),
                from, to, "补算历史", "request-001", "PENDING",
                UUID.randomUUID(), from, null, null, null
        ));

        mockMvc.perform(post("/api/v1/aggregation-recomputations")
                        .header("Idempotency-Key", "request-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "deviceId":"%s",
                                  "propertyId":"engine.rpm",
                                  "bucket":"PT5M",
                                  "from":"%s",
                                  "to":"%s",
                                  "reason":"补算历史"
                                }
                                """.formatted(deviceId, from, to)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        SaCheckPermission permission = AggregationGovernanceController.class
                .getMethod("createRecomputation", String.class,
                        com.bosco.mine.server.modules.aggregation.dto.AggregationRecomputeRequest.class)
                .getAnnotation(SaCheckPermission.class);
        assertThat(permission.value()).containsExactly("governance:aggregation:execute");
    }

    @Test
    void 运行历史应限制一百条并使用聚合查看权限() throws Exception {
        UUID executionId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-17T08:00:00Z");
        when(service.recentExecutions(20)).thenReturn(List.of(
                new AggregationExecutionResponse(
                        executionId, "telemetry-a", "SUCCEEDED", 2, 3,
                        99, 12, 15L, 1, now.minusSeconds(30), now, null)
        ));

        mockMvc.perform(get("/api/v1/aggregation-executions").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].executionId").value(executionId.toString()))
                .andExpect(jsonPath("$[0].bucketCount").value(12));

        SaCheckPermission permission = AggregationGovernanceController.class
                .getMethod("recentExecutions", int.class)
                .getAnnotation(SaCheckPermission.class);
        assertThat(permission.value()).containsExactly("governance:aggregation:view");
    }
}
