package com.bosco.mine.server.modules.mine.location;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验定位运行服务拉取快照的结构化内部接口。
 */
class LocationSnapshotControllerTest {

    @Test
    void 应返回最新快照元数据() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-07-18T08:00:00Z");
        LocationSnapshotService service = mock(LocationSnapshotService.class);
        when(service.findLatest(siteId)).thenReturn(new LocationSnapshotService.SnapshotMetadata(
                snapshotId, organizationId, siteId, 7,
                LocationSnapshotService.ChangeReason.ASSET_CHANGE,
                12, "sha256:" + "a".repeat(64), createdAt));
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(get("/api/v1/internal/location-snapshots/latest")
                        .queryParam("siteId", siteId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").value(snapshotId.toString()))
                .andExpect(jsonPath("$.siteId").value(siteId.toString()))
                .andExpect(jsonPath("$.revision").value(7))
                .andExpect(jsonPath("$.changeReason").value("ASSET_CHANGE"))
                .andExpect(jsonPath("$.itemCount").value(12))
                .andExpect(jsonPath("$.contentHash").value("sha256:" + "a".repeat(64)));

        verify(service).findLatest(siteId);
    }

    @Test
    void 应按稳定条目键游标返回结构化快照页() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        UUID nextId = UUID.randomUUID();
        String cursor = "VEHICLE:" + vehicleId;
        String nextCursor = "VEHICLE:" + nextId;
        LocationSnapshotService service = mock(LocationSnapshotService.class);
        when(service.findSnapshotPage(snapshotId, cursor, 1)).thenReturn(
                new LocationSnapshotService.SnapshotPage(
                        snapshotId, organizationId, siteId, 8,
                        LocationSnapshotService.ChangeReason.BINDING_SWITCH,
                        2, "sha256:" + "b".repeat(64), Instant.parse("2026-07-18T08:01:00Z"),
                        List.of(new LocationSnapshotService.SnapshotItemView(
                                nextCursor, "VEHICLE",
                                "{\"id\":\"%s\",\"status\":\"ACTIVE\"}".formatted(nextId),
                                "sha256:" + "c".repeat(64))),
                        nextCursor));
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(get("/api/v1/internal/location-snapshots/{id}", snapshotId)
                        .queryParam("afterItemKey", cursor)
                        .queryParam("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(8))
                .andExpect(jsonPath("$.items[0].itemKey").value(nextCursor))
                .andExpect(jsonPath("$.items[0].itemType").value("VEHICLE"))
                .andExpect(jsonPath("$.items[0].content.id").value(nextId.toString()))
                .andExpect(jsonPath("$.items[0].content.status").value("ACTIVE"))
                .andExpect(jsonPath("$.nextAfterItemKey").value(nextCursor));

        verify(service).findSnapshotPage(snapshotId, cursor, 1);
    }

    private MockMvc mockMvc(LocationSnapshotService service) {
        return MockMvcBuilders.standaloneSetup(new LocationSnapshotController(
                        service, JsonMapper.builder().findAndAddModules().build()))
                .build();
    }
}
