package com.bosco.mine.server.modules.configuration.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.modules.configuration.service.ProtocolConfigurationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验协议样本、测试、发布和回滚接口的契约与权限边界。
 */
class ProtocolConfigurationControllerTest {

    private final UUID mappingVersionId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private ProtocolConfigurationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建协议配置接口测试环境() {
        service = mock(ProtocolConfigurationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ProtocolConfigurationController(
                                service, JsonMapper.builder().findAndAddModules().build()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 应查询并原子替换黄金样本() throws Exception {
        ProtocolConfigurationService.SampleView sample = new ProtocolConfigurationService.SampleView(
                UUID.randomUUID(), "正常上报", "{\"rpm\":1200}", true,
                "{\"engineRpm\":1200}", null, 0, 0
        );
        when(service.findSamples(mappingVersionId)).thenReturn(List.of(sample));
        when(service.replaceSamples(eq(mappingVersionId), eq(3L), any())).thenReturn(List.of(sample));

        mockMvc.perform(get("/api/v1/protocol-mapping-versions/{id}/samples", mappingVersionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rawPayload.rpm").value(1200));

        mockMvc.perform(put("/api/v1/protocol-mapping-versions/{id}/samples", mappingVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version":3,
                                  "samples":[{
                                    "name":"正常上报",
                                    "rawPayload":{"rpm":1200},
                                    "expectedSuccess":true,
                                    "expectedProperties":{"engineRpm":1200},
                                    "sortOrder":0
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].expectedProperties.engineRpm").value(1200));

        verify(service).replaceSamples(eq(mappingVersionId), eq(3L), any());
    }

    @Test
    void 发布应强制幂等键并返回发布资源位置() throws Exception {
        UUID releaseId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        when(service.publish(any())).thenReturn(new ProtocolConfigurationService.ReleaseView(
                releaseId, productId, packageId, 7, "PUBLISH", null,
                "sha256:" + "a".repeat(64), "{}"
        ));

        mockMvc.perform(post("/api/v1/config-releases")
                        .header("Idempotency-Key", "publish-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productId":"%s",
                                  "mappingVersionId":"%s",
                                  "reason":"接入运输车协议"
                                }
                                """.formatted(productId, mappingVersionId)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/config-releases/" + releaseId))
                .andExpect(jsonPath("$.releaseSequence").value(7))
                .andExpect(jsonPath("$.snapshotJson").doesNotExist());
    }

    @Test
    void 缺少幂等键或非法样本应返回参数错误() throws Exception {
        mockMvc.perform(post("/api/v1/config-releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productId":"%s",
                                  "mappingVersionId":"%s",
                                  "reason":"接入运输车协议"
                                }
                                """.formatted(productId, mappingVersionId)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/protocol-mapping-versions/{id}/samples", mappingVersionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"samples\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 回放和回滚应返回结构化结果() throws Exception {
        UUID runId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        when(service.test(mappingVersionId)).thenReturn(new ProtocolConfigurationService.TestRunView(
                runId, true, 1, 1, 0, List.of()
        ));
        when(service.rollback(eq(releaseId), eq("恢复稳定版本"), eq("rollback-001")))
                .thenReturn(new ProtocolConfigurationService.ReleaseView(
                        UUID.randomUUID(), productId, UUID.randomUUID(), 8,
                        "ROLLBACK", releaseId, "sha256:" + "b".repeat(64), "{}"
                ));

        mockMvc.perform(post("/api/v1/protocol-mapping-versions/{id}/test", mappingVersionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true));
        mockMvc.perform(post("/api/v1/config-releases/{id}/rollback", releaseId)
                        .header("Idempotency-Key", "rollback-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"恢复稳定版本\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.action").value("ROLLBACK"));
    }

    @Test
    void 所有管理入口都应声明独立权限() throws Exception {
        assertPermission("findSamples", "device:protocol:sample:view", UUID.class);
        assertPermission("replaceSamples", "device:protocol:sample:edit",
                UUID.class, ProtocolConfigurationController.ReplaceSamplesRequest.class);
        assertPermission("test", "device:protocol:test", UUID.class);
        assertPermission("publish", "device:configuration:publish",
                String.class, ProtocolConfigurationController.PublishRequest.class);
        assertPermission("rollback", "device:configuration:rollback",
                UUID.class, String.class, ProtocolConfigurationController.RollbackRequest.class);
    }

    private void assertPermission(String methodName, String permission, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        SaCheckPermission annotation = ProtocolConfigurationController.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(SaCheckPermission.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).containsExactly(permission);
    }
}
