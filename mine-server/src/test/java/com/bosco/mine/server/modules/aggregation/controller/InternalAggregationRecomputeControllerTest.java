package com.bosco.mine.server.modules.aggregation.controller;

import com.bosco.mine.server.modules.aggregation.dto.InternalAggregationRecomputeClaim;
import com.bosco.mine.server.modules.aggregation.service.AggregationGovernanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验内部聚合重算接口只暴露可靠认领和令牌终结合同。
 */
class InternalAggregationRecomputeControllerTest {

    private AggregationGovernanceService service;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建控制器() {
        service = mock(AggregationGovernanceService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new InternalAggregationRecomputeController(service)).build();
    }

    @Test
    void 应认领一个稳定执行标识和租约令牌() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-02T00:00:00Z");
        when(service.claimRecomputation("telemetry-a", Duration.ofMinutes(5)))
                .thenReturn(Optional.of(new InternalAggregationRecomputeClaim(
                        requestId, executionId, claimToken, deviceId, "engine.rpm",
                        Duration.ofMinutes(5), from, to, from.plusSeconds(300), 1)));

        mockMvc.perform(post("/internal/v1/aggregation-recomputations/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instanceId":"telemetry-a","leaseDuration":"PT5M"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.executionId").value(executionId.toString()))
                .andExpect(jsonPath("$.claimToken").value(claimToken.toString()));
    }

    @Test
    void 无待执行请求时应返回无内容且完成必须携带当前令牌() throws Exception {
        when(service.claimRecomputation("telemetry-a", Duration.ofMinutes(5)))
                .thenReturn(Optional.empty());
        mockMvc.perform(post("/internal/v1/aggregation-recomputations/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instanceId":"telemetry-a","leaseDuration":"PT5M"}
                                """))
                .andExpect(status().isNoContent());

        UUID requestId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        when(service.completeRecomputation(
                eq(requestId), eq(executionId), eq(claimToken), eq("FAILED"), any()))
                .thenReturn(true);
        mockMvc.perform(post("/internal/v1/aggregation-recomputations/{requestId}/complete", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "executionId":"%s",
                                  "claimToken":"%s",
                                  "status":"FAILED",
                                  "error":"database unavailable"
                                }
                                """.formatted(executionId, claimToken)))
                .andExpect(status().isNoContent());
        verify(service).completeRecomputation(
                requestId, executionId, claimToken, "FAILED", "database unavailable");
    }
}
