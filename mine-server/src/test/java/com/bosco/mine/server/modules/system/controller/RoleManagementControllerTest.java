package com.bosco.mine.server.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.bootstrap.web.TraceIdFilter;
import com.bosco.mine.server.modules.system.service.AccessManagementService;
import com.bosco.mine.server.modules.system.service.MenuManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验角色管理、菜单授权和只读菜单树接口契约。
 */
class RoleManagementControllerTest {

    private AccessManagementService service;
    private MenuManagementService menuService;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建角色管理接口测试环境() {
        service = mock(AccessManagementService.class);
        menuService = mock(MenuManagementService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new RoleManagementController(service),
                        new MenuManagementController(menuService)
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void 应分页查询并创建角色() throws Exception {
        UUID roleId = UUID.randomUUID();
        when(service.findRoles("调度", "ENABLED", 1, 20)).thenReturn(new PageResponse<>(
                List.of(role(roleId, "DISPATCHER")),
                1,
                20,
                1
        ));
        when(service.createRole(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(role(roleId, "DISPATCHER"));

        mockMvc.perform(get("/api/v1/system/roles")
                        .param("keyword", "调度")
                        .param("status", "ENABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].code").value("DISPATCHER"));

        mockMvc.perform(post("/api/v1/system/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code":"DISPATCHER",
                                  "name":"调度员",
                                  "status":"ENABLED",
                                  "description":"负责车辆调度"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/system/roles/" + roleId))
                .andExpect(jsonPath("$.id").value(roleId.toString()));
    }

    @Test
    void 应修改删除角色并原子替换菜单() throws Exception {
        UUID roleId = UUID.randomUUID();
        UUID menuId = UUID.randomUUID();
        AccessManagementService.RoleView updated = new AccessManagementService.RoleView(
                roleId,
                "DISPATCHER",
                "车辆调度员",
                "DISABLED",
                "暂停使用",
                Instant.parse("2026-07-15T00:00:00Z"),
                Instant.parse("2026-07-15T01:00:00Z"),
                2,
                List.of(menuId)
        );
        when(service.updateRole(roleId, "车辆调度员", "DISABLED", "暂停使用", 1))
                .thenReturn(updated);

        mockMvc.perform(put("/api/v1/system/roles/{id}", roleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"车辆调度员",
                                  "status":"DISABLED",
                                  "description":"暂停使用",
                                  "version":1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(put("/api/v1/system/roles/{id}/menus", roleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"menuIds":["%s"]}
                                """.formatted(menuId)))
                .andExpect(status().isNoContent());
        verify(service).replaceRoleMenus(roleId, List.of(menuId));

        mockMvc.perform(delete("/api/v1/system/roles/{id}", roleId))
                .andExpect(status().isNoContent());
        verify(service).deleteRole(roleId);
    }

    @Test
    void 应返回包含按钮权限码的只读菜单树() throws Exception {
        UUID directoryId = UUID.randomUUID();
        UUID buttonId = UUID.randomUUID();
        when(menuService.findMenuTree()).thenReturn(List.of(new MenuManagementService.MenuNode(
                directoryId,
                null,
                "系统管理",
                "DIRECTORY",
                null,
                null,
                null,
                "setting",
                100,
                true,
                "ENABLED",
                "mine.menu.system",
                true,
                0,
                List.of(new MenuManagementService.MenuNode(
                        buttonId,
                        directoryId,
                        "查看用户",
                        "BUTTON",
                        null,
                        null,
                        "system:user:view",
                        null,
                        10,
                        false,
                        "ENABLED",
                        null,
                        true,
                        0,
                        List.of()
                ))
        )));

        mockMvc.perform(get("/api/v1/system/menus/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("系统管理"))
                .andExpect(jsonPath("$[0].children[0].permission").value("system:user:view"));
    }

    @Test
    void 角色和菜单入口应声明后端权限() throws Exception {
        assertRolePermission("findRoles", "system:role:view",
                String.class, String.class, int.class, int.class);
        assertRolePermission("createRole", "system:role:create",
                RoleManagementController.RoleCreateRequest.class);
        assertRolePermission("updateRole", "system:role:update",
                UUID.class, RoleManagementController.RoleUpdateRequest.class);
        assertRolePermission("deleteRole", "system:role:delete", UUID.class);
        assertRolePermission("replaceRoleMenus", "system:role:assign-menu",
                UUID.class, RoleManagementController.RoleMenuReplaceRequest.class);

        SaCheckPermission menuPermission = MenuManagementController.class
                .getMethod("findMenuTree")
                .getAnnotation(SaCheckPermission.class);
        assertThat(menuPermission).isNotNull();
        assertThat(menuPermission.value()).containsExactly("system:role:view", "system:menu:view");
    }

    @Test
    void 应拒绝非法角色编码和缺少菜单集合的请求() throws Exception {
        mockMvc.perform(post("/api/v1/system/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"不合法","name":"角色","status":"ENABLED"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/v1/system/roles/{id}/menus", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    private AccessManagementService.RoleView role(UUID roleId, String code) {
        Instant now = Instant.parse("2026-07-15T00:00:00Z");
        return new AccessManagementService.RoleView(
                roleId,
                code,
                "调度员",
                "ENABLED",
                "负责车辆调度",
                now,
                now,
                0,
                List.of()
        );
    }

    private void assertRolePermission(String method, String permission, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        SaCheckPermission annotation = RoleManagementController.class
                .getMethod(method, parameterTypes)
                .getAnnotation(SaCheckPermission.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).containsExactly(permission);
    }
}
