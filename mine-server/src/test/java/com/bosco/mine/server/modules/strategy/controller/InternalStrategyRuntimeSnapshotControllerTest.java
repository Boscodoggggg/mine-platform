package com.bosco.mine.server.modules.strategy.controller;

import com.bosco.mine.server.modules.strategy.service.StrategyRuntimeSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验运行服务按设备游标拉取结构化策略增量快照。
 */
class InternalStrategyRuntimeSnapshotControllerTest {

    @Test
    void 应返回结构化设备策略和下一页游标() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        StrategyRuntimeSnapshotService service = mock(StrategyRuntimeSnapshotService.class);
        when(service.findSnapshotPage(snapshotId, null, 500)).thenReturn(
                new StrategyRuntimeSnapshotService.SnapshotPage(
                        snapshotId, productId, "DATA_QUALITY", 7,
                        StrategyRuntimeSnapshotService.ChangeReason.PUBLISH,
                        2, "sha256:" + "a".repeat(64), Instant.now(),
                        List.of(new StrategyRuntimeSnapshotService.SnapshotItemView(
                                deviceId,
                                "{\"required.engine.rpm\":true,\"staleAfterSeconds\":60}",
                                "sha256:" + "b".repeat(64)
                        )),
                        deviceId
                )
        );
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new InternalStrategyRuntimeSnapshotController(
                        service, JsonMapper.builder().findAndAddModules().build()))
                .build();

        mockMvc.perform(get("/internal/v1/strategy-runtime-snapshots/{id}", snapshotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runtimeRevision").value(7))
                .andExpect(jsonPath("$.items[0].deviceId").value(deviceId.toString()))
                .andExpect(jsonPath("$.items[0].values.staleAfterSeconds").value(60))
                .andExpect(jsonPath("$.nextAfterDeviceId").value(deviceId.toString()));
    }
}
