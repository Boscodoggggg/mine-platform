package com.bosco.mine.server.modules.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.bootstrap.web.GlobalExceptionHandler;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.bootstrap.web.TraceIdFilter;
import com.bosco.mine.server.modules.system.service.AccessManagementService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
 * 校验用户管理接口的请求约束、成功响应和权限声明。
 */
class UserManagementControllerTest {

    private AccessManagementService service;
    private MockMvc mockMvc;

    @BeforeEach
    void 创建用户管理接口测试环境() {
        service = mock(AccessManagementService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UserManagementController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void 应分页返回不含密码信息的用户列表() throws Exception {
        UUID userId = UUID.randomUUID();
        when(service.findUsers("调度", "ENABLED", 2, 20)).thenReturn(new PageResponse<>(
                List.of(user(userId, "dispatcher")),
                2,
                20,
                21
        ));

        mockMvc.perform(get("/api/v1/system/users")
                        .param("keyword", "调度")
                        .param("status", "ENABLED")
                        .param("page", "2")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.total").value(21))
                .andExpect(jsonPath("$.items[0].id").value(userId.toString()))
                .andExpect(jsonPath("$.items[0].username").value("dispatcher"))
                .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());
    }

    @Test
    void 创建用户应返回二零一和资源地址() throws Exception {
        UUID userId = UUID.randomUUID();
        when(service.createUser(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(user(userId, "dispatcher"));

        mockMvc.perform(post("/api/v1/system/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"dispatcher",
                                  "displayName":"调度员",
                                  "temporaryPassword":"temporary-password",
                                  "status":"ENABLED"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/system/users/" + userId))
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));
    }

    @Test
    void 修改用户应携带版本号并返回新快照() throws Exception {
        UUID userId = UUID.randomUUID();
        AccessManagementService.UserView updated = new AccessManagementService.UserView(
                userId,
                "dispatcher",
                "调度主管",
                "DISABLED",
                true,
                null,
                Instant.parse("2026-07-15T00:00:00Z"),
                Instant.parse("2026-07-15T01:00:00Z"),
                4,
                List.of()
        );
        when(service.updateUser(userId, "调度主管", "DISABLED", 3)).thenReturn(updated);

        mockMvc.perform(put("/api/v1/system/users/{id}", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"调度主管","status":"DISABLED","version":3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("调度主管"))
                .andExpect(jsonPath("$.version").value(4));
    }

    @Test
    void 应支持原子替换角色和重置临时密码() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/system/users/{id}/roles", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleIds":["%s"]}
                                """.formatted(roleId)))
                .andExpect(status().isNoContent());
        verify(service).replaceUserRoles(userId, List.of(roleId));

        mockMvc.perform(post("/api/v1/system/users/{id}/password-reset", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"temporaryPassword":"another-temporary-password"}
                                """))
                .andExpect(status().isNoContent());
        verify(service).resetUserPassword(userId, "another-temporary-password");
    }

    @Test
    void 应在控制器层拒绝非法分页和过短密码() throws Exception {
        mockMvc.perform(get("/api/v1/system/users")
                        .param("page", "0")
                        .param("size", "101"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/system/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"dispatcher",
                                  "displayName":"调度员",
                                  "temporaryPassword":"short",
                                  "status":"ENABLED"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 每个用户管理入口都应声明对应权限() throws Exception {
        assertPermission("findUsers", "system:user:view", String.class, String.class, int.class, int.class);
        assertPermission(
                "createUser",
                "system:user:create",
                UserManagementController.UserCreateRequest.class
        );
        assertPermission(
                "updateUser",
                "system:user:update",
                UUID.class,
                UserManagementController.UserUpdateRequest.class
        );
        assertPermission(
                "replaceUserRoles",
                "system:user:assign-role",
                UUID.class,
                UserManagementController.UserRoleReplaceRequest.class
        );
        assertPermission(
                "resetUserPassword",
                "system:user:reset-password",
                UUID.class,
                UserManagementController.PasswordResetRequest.class
        );
    }

    private AccessManagementService.UserView user(UUID userId, String username) {
        return new AccessManagementService.UserView(
                userId,
                username,
                "调度员",
                "ENABLED",
                true,
                null,
                Instant.parse("2026-07-15T00:00:00Z"),
                Instant.parse("2026-07-15T00:00:00Z"),
                0,
                List.of()
        );
    }

    private void assertPermission(String methodName, String permission, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        SaCheckPermission annotation = UserManagementController.class
                .getMethod(methodName, parameterTypes)
                .getAnnotation(SaCheckPermission.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).containsExactly(permission);
    }
}
