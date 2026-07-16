package com.bosco.mine.server.modules.configuration.controller;

import com.bosco.mine.server.modules.configuration.service.ProtocolConfigurationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验运行节点拉取配置快照时获得结构化 JSON 而不是二次转义文本。
 */
class InternalConfigurationSnapshotControllerTest {

    @Test
    void 应返回指定发布的完整结构化快照() throws Exception {
        UUID releaseId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID packageId = UUID.randomUUID();
        ProtocolConfigurationService service = mock(ProtocolConfigurationService.class);
        when(service.findSnapshot(releaseId)).thenReturn(new ProtocolConfigurationService.ReleaseSnapshot(
                releaseId, productId, packageId, 9,
                "sha256:" + "a".repeat(64),
                "{\"schemaVersion\":1,\"productKey\":\"haul-truck\"}"
        ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new InternalConfigurationSnapshotController(
                        service, JsonMapper.builder().findAndAddModules().build()))
                .build();

        mockMvc.perform(get("/internal/v1/config-releases/{id}", releaseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseSequence").value(9))
                .andExpect(jsonPath("$.snapshot.schemaVersion").value(1))
                .andExpect(jsonPath("$.snapshot.productKey").value("haul-truck"));
    }

    @Test
    void 应接收Gateway配置加载状态() throws Exception {
        UUID releaseId = UUID.randomUUID();
        ProtocolConfigurationService service = mock(ProtocolConfigurationService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                        new InternalConfigurationSnapshotController(
                                service, JsonMapper.builder().findAndAddModules().build()))
                .build();

        mockMvc.perform(post("/internal/v1/config-releases/{id}/nodes/{nodeId}",
                        releaseId, "gateway-1")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "nodeType":"GATEWAY",
                                  "status":"LOADED",
                                  "actualHash":"sha256:%s",
                                  "message":"配置已原子切换"
                                }
                                """.formatted("a".repeat(64))))
                .andExpect(status().isNoContent());

        verify(service).reportNodeStatus(
                releaseId,
                new ProtocolConfigurationService.NodeStatusCommand(
                        "gateway-1", "GATEWAY", "LOADED",
                        "sha256:" + "a".repeat(64), "配置已原子切换")
        );
    }
}
