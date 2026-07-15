package com.bosco.mine.server.modules.system.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.security.MineSecurityProperties;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.system.repository.AccessManagementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验用户管理用例的边界、乐观锁和超级管理员保护。
 */
class AccessManagementServiceTest {

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString("0198aef6-2e80-7000-8000-000000000001");

    private AccessManagementMapper mapper;
    private PasswordEncoder passwordEncoder;
    private OperationAuditService auditService;
    private AccessManagementService service;

    @BeforeEach
    void 创建用户管理服务测试环境() {
        mapper = mock(AccessManagementMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        auditService = mock(OperationAuditService.class);
        MineSecurityProperties properties = new MineSecurityProperties(
                new MineSecurityProperties.BootstrapAdmin(
                        false,
                        SUPER_ADMIN_ID,
                        "mine-admin",
                        "矿区超级管理员",
                        Path.of("/tmp/mine-test-secret")
                ),
                new MineSecurityProperties.Password(12),
                new MineSecurityProperties.LoginProtection(
                        5,
                        25,
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(15)
                )
        );
        service = new AccessManagementService(mapper, passwordEncoder, properties, auditService);
    }

    @Test
    void 分页查询应计算偏移并附带用户角色() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        when(mapper.findUsers("调度", "ENABLED", 20, 20))
                .thenReturn(List.of(user(userId, 0)));
        when(mapper.findRoleIdsByUserId(userId)).thenReturn(List.of(roleId));
        when(mapper.countUsers("调度", "ENABLED")).thenReturn(21L);

        PageResponse<AccessManagementService.UserView> response =
                service.findUsers(" 调度 ", " ENABLED ", 2, 20);

        assertThat(response.total()).isEqualTo(21);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(userId);
            assertThat(item.roleIds()).containsExactly(roleId);
        });
    }

    @Test
    void 创建用户应散列临时密码且响应不包含散列() {
        when(passwordEncoder.encode("temporary-password")).thenReturn("bcrypt-hash");
        when(mapper.findManagedUserById(any(UUID.class)))
                .thenAnswer(invocation -> Optional.of(user(invocation.getArgument(0), 0)));

        AccessManagementService.UserView created = service.createUser(
                " dispatcher ",
                " 调度员 ",
                "temporary-password",
                "enabled"
        );

        ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
        verify(mapper).insertUser(
                id.capture(),
                org.mockito.ArgumentMatchers.eq("dispatcher"),
                org.mockito.ArgumentMatchers.eq("bcrypt-hash"),
                org.mockito.ArgumentMatchers.eq("调度员"),
                org.mockito.ArgumentMatchers.eq("ENABLED"),
                any()
        );
        assertThat(created.id()).isEqualTo(id.getValue());
        assertThat(created.toString()).doesNotContain("bcrypt-hash", "temporary-password");
        verify(auditService).recordSuccess(
                org.mockito.ArgumentMatchers.eq("CREATE_USER"),
                org.mockito.ArgumentMatchers.eq("system:user:create"),
                org.mockito.ArgumentMatchers.eq("USER"),
                org.mockito.ArgumentMatchers.eq(created.id()),
                any(),
                any()
        );
    }

    @Test
    void 重复用户名应返回稳定冲突错误且不散列密码() {
        when(mapper.usernameExists("dispatcher")).thenReturn(true);

        assertThatThrownBy(() -> service.createUser(
                "dispatcher",
                "调度员",
                "temporary-password",
                "ENABLED"
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("SYSTEM-409-001"));

        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void 乐观锁失败应返回冲突而不是覆盖新数据() {
        UUID userId = UUID.randomUUID();
        when(mapper.findManagedUserById(userId)).thenReturn(Optional.of(user(userId, 3)));
        when(mapper.updateUser(userId, "调度主管", "ENABLED", 3, null)).thenReturn(0);

        assertThatThrownBy(() -> service.updateUser(userId, "调度主管", "ENABLED", 3))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("SYSTEM-409-003"));
    }

    @Test
    void 分配角色应去重并拒绝不存在或停用的角色() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        when(mapper.findManagedUserById(userId)).thenReturn(Optional.of(user(userId, 0)));
        when(mapper.countEnabledRoleIds(List.of(roleId))).thenReturn(1L);

        service.replaceUserRoles(userId, List.of(roleId, roleId));

        verify(mapper).deleteUserRoles(userId);
        verify(mapper).insertUserRole(any(UUID.class),
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(roleId),
                any());

        UUID disabledRoleId = UUID.randomUUID();
        when(mapper.countEnabledRoleIds(List.of(disabledRoleId))).thenReturn(0L);
        assertThatThrownBy(() -> service.replaceUserRoles(userId, List.of(disabledRoleId)))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("SYSTEM-404-002"));
    }

    @Test
    void 超级管理员不得被修改分配角色或重置密码() {
        assertThatThrownBy(() -> service.updateUser(SUPER_ADMIN_ID, "新名称", "DISABLED", 0))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("SYSTEM-403-001"));
        assertThatThrownBy(() -> service.replaceUserRoles(SUPER_ADMIN_ID, List.of()))
                .isInstanceOf(MineException.class);
        assertThatThrownBy(() -> service.resetUserPassword(SUPER_ADMIN_ID, "temporary-password"))
                .isInstanceOf(MineException.class);

        verify(mapper, never()).updateUser(any(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyLong(), any());
        verify(mapper, never()).deleteUserRoles(any());
        verify(mapper, never()).resetUserPassword(any(), anyString(), any());
    }

    @Test
    void 角色分页应附带授权节点并正确计算偏移() {
        UUID roleId = UUID.randomUUID();
        UUID menuId = UUID.randomUUID();
        when(mapper.findRoles("调度", "ENABLED", 10, 10))
                .thenReturn(List.of(role(roleId, 0)));
        when(mapper.findMenuIdsByRoleId(roleId)).thenReturn(List.of(menuId));
        when(mapper.countRoles("调度", "ENABLED")).thenReturn(11L);

        PageResponse<AccessManagementService.RoleView> response =
                service.findRoles(" 调度 ", " ENABLED ", 2, 10);

        assertThat(response.total()).isEqualTo(11);
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(roleId);
            assertThat(item.menuIds()).containsExactly(menuId);
        });
    }

    @Test
    void 创建角色应规范化编码并记录脱敏审计() {
        when(mapper.findManagedRoleById(any(UUID.class)))
                .thenAnswer(invocation -> Optional.of(role(invocation.getArgument(0), 0)));

        AccessManagementService.RoleView created = service.createRole(
                " dispatcher ",
                " 调度员 ",
                "enabled",
                " 负责车辆调度 "
        );

        verify(mapper).insertRole(
                org.mockito.ArgumentMatchers.eq(created.id()),
                org.mockito.ArgumentMatchers.eq("DISPATCHER"),
                org.mockito.ArgumentMatchers.eq("调度员"),
                org.mockito.ArgumentMatchers.eq("ENABLED"),
                org.mockito.ArgumentMatchers.eq("负责车辆调度"),
                any()
        );
        verify(auditService).recordSuccess(
                org.mockito.ArgumentMatchers.eq("CREATE_ROLE"),
                org.mockito.ArgumentMatchers.eq("system:role:create"),
                org.mockito.ArgumentMatchers.eq("ROLE"),
                org.mockito.ArgumentMatchers.eq(created.id()),
                any(),
                any()
        );
    }

    @Test
    void 角色删除应拒绝仍被用户使用的角色() {
        UUID roleId = UUID.randomUUID();
        when(mapper.findManagedRoleById(roleId)).thenReturn(Optional.of(role(roleId, 0)));
        when(mapper.deleteUnusedRole(roleId)).thenReturn(0);

        assertThatThrownBy(() -> service.deleteRole(roleId))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("SYSTEM-409-004"));
    }

    @Test
    void 菜单授权应去重并拒绝停用或未知节点() {
        UUID roleId = UUID.randomUUID();
        UUID menuId = UUID.randomUUID();
        when(mapper.findManagedRoleById(roleId)).thenReturn(Optional.of(role(roleId, 0)));
        when(mapper.countEnabledMenuIds(List.of(menuId))).thenReturn(1L);

        service.replaceRoleMenus(roleId, List.of(menuId, menuId));

        verify(mapper).deleteRoleMenus(roleId);
        verify(mapper).insertRoleMenu(any(UUID.class),
                org.mockito.ArgumentMatchers.eq(roleId),
                org.mockito.ArgumentMatchers.eq(menuId),
                any());

        UUID unknownMenuId = UUID.randomUUID();
        when(mapper.countEnabledMenuIds(List.of(unknownMenuId))).thenReturn(0L);
        assertThatThrownBy(() -> service.replaceRoleMenus(roleId, List.of(unknownMenuId)))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("SYSTEM-404-003"));
    }

    @Test
    void 只读菜单应按父子关系构建树并保留按钮权限码() {
        UUID rootId = UUID.randomUUID();
        UUID buttonId = UUID.randomUUID();
        when(mapper.findAllMenus()).thenReturn(List.of(
                new AccessManagementMapper.ManagedMenuRecord(
                        rootId,
                        null,
                        "系统管理",
                        "DIRECTORY",
                        null,
                        null,
                        null,
                        "setting",
                        100,
                        true,
                        "ENABLED"
                ),
                new AccessManagementMapper.ManagedMenuRecord(
                        buttonId,
                        rootId,
                        "查看用户",
                        "BUTTON",
                        null,
                        null,
                        "system:user:view",
                        null,
                        10,
                        false,
                        "ENABLED"
                )
        ));

        assertThat(service.findMenuTree()).singleElement().satisfies(root -> {
            assertThat(root.id()).isEqualTo(rootId);
            assertThat(root.children()).singleElement().satisfies(button ->
                    assertThat(button.permission()).isEqualTo("system:user:view"));
        });
    }

    private AccessManagementMapper.ManagedUserRecord user(UUID userId, long version) {
        Instant now = Instant.parse("2026-07-15T00:00:00Z");
        return new AccessManagementMapper.ManagedUserRecord(
                userId,
                "dispatcher",
                "调度员",
                "ENABLED",
                true,
                null,
                now,
                now,
                version
        );
    }

    private AccessManagementMapper.ManagedRoleRecord role(UUID roleId, long version) {
        Instant now = Instant.parse("2026-07-15T00:00:00Z");
        return new AccessManagementMapper.ManagedRoleRecord(
                roleId,
                "DISPATCHER",
                "调度员",
                "ENABLED",
                "负责车辆调度",
                now,
                now,
                version
        );
    }
}
