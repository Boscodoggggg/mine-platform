package com.bosco.mine.server.modules.strategy.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.model.strategy.EffectiveStrategyResolver;
import com.bosco.mine.model.strategy.StrategyScopeType;
import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.bootstrap.web.TraceIdFilter;
import com.bosco.mine.server.modules.strategy.service.StrategyConfigurationService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验策略配置管理接口的请求契约和权限声明。
 */
class StrategyConfigurationControllerTest {

    private StrategyConfigurationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建策略配置接口测试环境() {
        service = mock(StrategyConfigurationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new StrategyConfigurationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void 创建策略草稿应返回二零一和资源地址() throws Exception {
        UUID versionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(service.createDraft(any())).thenReturn(new StrategyConfigurationService.StrategyVersionView(
                versionId, productId, "ONLINE_STATUS", 1, "DRAFT",
                Map.of("offlineThresholdSeconds", 300), null, "在线状态规则",
                null, Instant.parse("2026-07-16T00:00:00Z"),
                Instant.parse("2026-07-16T00:00:00Z"), 0
        ));

        mockMvc.perform(post("/api/v1/strategy-versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productId":"%s",
                                  "strategyType":"ONLINE_STATUS",
                                  "values":{"offlineThresholdSeconds":300},
                                  "description":"在线状态规则"
                                }
                                """.formatted(productId)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/strategy-versions/" + versionId))
                .andExpect(jsonPath("$.values.offlineThresholdSeconds").value(300));
    }

    @Test
    void 发布必须携带幂等键并返回发布资源地址() throws Exception {
        UUID versionId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(service.publish(any())).thenReturn(new StrategyConfigurationService.StrategyReleaseView(
                releaseId, productId, versionId, "ONLINE_STATUS", 7,
                StrategyScopeType.GROUP, groupId, 800, "PUBLISH", null,
                "sha256:" + "1".repeat(64)
        ));

        mockMvc.perform(post("/api/v1/strategy-releases")
                        .header("Idempotency-Key", "strategy-publish-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "strategyVersionId":"%s",
                                  "scopeType":"GROUP",
                                  "scopeId":"%s",
                                  "reason":"提高弱网容忍度"
                                }
                                """.formatted(versionId, groupId)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/strategy-releases/" + releaseId))
                .andExpect(jsonPath("$.releaseSequence").value(7));

        mockMvc.perform(post("/api/v1/strategy-releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "strategyVersionId":"%s",
                                  "scopeType":"GROUP",
                                  "scopeId":"%s",
                                  "reason":"提高弱网容忍度"
                                }
                                """.formatted(versionId, groupId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 有效策略接口应返回来源和冲突结构() throws Exception {
        UUID deviceId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        when(service.findEffective(deviceId, "ONLINE_STATUS")).thenReturn(
                new StrategyConfigurationService.EffectiveStrategyView(
                        deviceId,
                        productId,
                        "ONLINE_STATUS",
                        true,
                        Map.of("offlineThresholdSeconds", 300),
                        Map.of("offlineThresholdSeconds", new EffectiveStrategyResolver.ValueSource(
                                StrategyScopeType.GROUP, groupId, releaseId, 800
                        )),
                        List.of(),
                        "sha256:" + "1".repeat(64)
                )
        );

        mockMvc.perform(get("/api/v1/devices/{id}/effective-strategies/{type}",
                        deviceId, "ONLINE_STATUS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.offlineThresholdSeconds").value(300))
                .andExpect(jsonPath("$.sources.offlineThresholdSeconds.scopeType").value("GROUP"))
                .andExpect(jsonPath("$.sources.offlineThresholdSeconds.releaseId")
                        .value(releaseId.toString()));
    }

    @Test
    void 资源地址应支持查询单个策略版本和发布() throws Exception {
        UUID versionId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(service.findVersion(versionId)).thenReturn(new StrategyConfigurationService.StrategyVersionView(
                versionId, productId, "ONLINE_STATUS", 1, "PUBLISHED",
                Map.of("offlineThresholdSeconds", 300), "sha256:" + "1".repeat(64), null,
                Instant.parse("2026-07-16T00:00:00Z"), Instant.parse("2026-07-16T00:00:00Z"),
                Instant.parse("2026-07-16T00:00:00Z"), 1
        ));
        when(service.findRelease(releaseId)).thenReturn(new StrategyConfigurationService.StrategyReleaseView(
                releaseId, productId, versionId, "ONLINE_STATUS", 1,
                StrategyScopeType.PRODUCT, productId, null, "PUBLISH", null,
                "sha256:" + "1".repeat(64)
        ));

        mockMvc.perform(get("/api/v1/strategy-versions/{id}", versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(versionId.toString()));
        mockMvc.perform(get("/api/v1/strategy-releases/{id}", releaseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(releaseId.toString()));
    }

    @Test
    void 每个策略入口都应声明明确权限() throws Exception {
        assertPermission("findVersions", "device:strategy:view",
                UUID.class, String.class, String.class, int.class);
        assertPermission("findVersion", "device:strategy:view", UUID.class);
        assertPermission("createDraft", "device:strategy:edit",
                StrategyConfigurationController.CreateDraftRequest.class);
        assertPermission("updateDraft", "device:strategy:edit",
                UUID.class, StrategyConfigurationController.UpdateDraftRequest.class);
        assertPermission("findReleases", "device:strategy:view",
                UUID.class, String.class, int.class);
        assertPermission("findRelease", "device:strategy:view", UUID.class);
        assertPermission("publish", "device:strategy:publish",
                String.class, StrategyConfigurationController.PublishRequest.class);
        assertPermission("rollback", "device:strategy:publish",
                UUID.class, String.class, StrategyConfigurationController.RollbackRequest.class);
        assertPermission("findEffective", "device:strategy:view", UUID.class, String.class);
    }

    private void assertPermission(String methodName, String permission, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        SaCheckPermission annotation = StrategyConfigurationController.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(SaCheckPermission.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).containsExactly(permission);
    }
}
