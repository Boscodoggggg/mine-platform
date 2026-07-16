package com.bosco.mine.server.modules.strategy.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.bootstrap.web.TraceIdFilter;
import com.bosco.mine.server.modules.strategy.service.DeviceGroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * 校验设备组管理接口的请求契约和权限声明。
 */
class DeviceGroupControllerTest {

    private DeviceGroupService service;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建设备组接口测试环境() {
        service = mock(DeviceGroupService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DeviceGroupController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void 创建设备组应返回二零一和资源地址() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        when(service.createGroup(any())).thenReturn(group(groupId, productId));

        mockMvc.perform(post("/api/v1/device-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productId":"%s",
                                  "groupKey":"haul-team",
                                  "name":"运输一组",
                                  "priority":800,
                                  "description":"主采区运输车辆"
                                }
                                """.formatted(productId)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/device-groups/" + groupId))
                .andExpect(jsonPath("$.groupKey").value("haul-team"))
                .andExpect(jsonPath("$.priority").value(800));
    }

    @Test
    void 设备组列表应返回标准分页对象() throws Exception {
        UUID productId = UUID.randomUUID();
        when(service.findGroups(productId, "运输", "ACTIVE", 1, 20))
                .thenReturn(new PageResponse<>(List.of(group(UUID.randomUUID(), productId)), 1, 20, 1));

        mockMvc.perform(get("/api/v1/device-groups")
                        .param("productId", productId.toString())
                        .param("keyword", "运输")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].groupKey").value("haul-team"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void 设备组资源地址应支持详情查询() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        when(service.findGroup(groupId)).thenReturn(group(groupId, productId));

        mockMvc.perform(get("/api/v1/device-groups/{id}", groupId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(groupId.toString()))
                .andExpect(jsonPath("$.productId").value(productId.toString()));
    }

    @Test
    void 替换成员应返回差异并拒绝重复设备标识() throws Exception {
        UUID groupId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(service.replaceMembers(groupId, List.of(first, second), 3))
                .thenReturn(new DeviceGroupService.MembershipChange(
                        groupId, List.of(second), List.of(), 2, 4
                ));

        mockMvc.perform(put("/api/v1/device-groups/{id}/members", groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceIds":["%s","%s"],"version":3}
                                """.formatted(first, second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addedDeviceIds[0]").value(second.toString()))
                .andExpect(jsonPath("$.memberCount").value(2))
                .andExpect(jsonPath("$.version").value(4));

        mockMvc.perform(put("/api/v1/device-groups/{id}/members", groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceIds":["%s","%s"],"version":3}
                                """.formatted(first, first)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("STRATEGY-422-003"));
    }

    @Test
    void 归档设备组应返回无内容响应() throws Exception {
        UUID groupId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/device-groups/{id}/archive", groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":3}"))
                .andExpect(status().isNoContent());

        verify(service).archiveGroup(groupId, 3);
    }

    @Test
    void 每个设备组入口都应声明明确权限() throws Exception {
        assertPermission("findGroups", "device:group:view",
                UUID.class, String.class, String.class, int.class, int.class);
        assertPermission("findGroup", "device:group:view", UUID.class);
        assertPermission("findMembers", "device:group:view", UUID.class);
        assertPermission("createGroup", "device:group:edit", DeviceGroupController.CreateRequest.class);
        assertPermission("updateGroup", "device:group:edit",
                UUID.class, DeviceGroupController.UpdateRequest.class);
        assertPermission("replaceMembers", "device:group:edit",
                UUID.class, DeviceGroupController.ReplaceMembersRequest.class);
        assertPermission("archiveGroup", "device:group:edit",
                UUID.class, DeviceGroupController.ArchiveRequest.class);
    }

    private DeviceGroupService.DeviceGroupView group(UUID groupId, UUID productId) {
        Instant now = Instant.parse("2026-07-16T00:00:00Z");
        return new DeviceGroupService.DeviceGroupView(
                groupId, productId, "haul-team", "运输一组", 800, "ACTIVE",
                "主采区运输车辆", null, now, now, 0, 0
        );
    }

    private void assertPermission(String methodName, String permission, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        SaCheckPermission annotation = DeviceGroupController.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(SaCheckPermission.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).containsExactly(permission);
    }
}
