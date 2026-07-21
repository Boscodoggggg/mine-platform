package com.bosco.mine.server.modules.system;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.system.repository.AccessManagementMapper;
import com.bosco.mine.server.modules.system.service.AccessManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 校验用户、角色关联和密码重置事务。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class AccessManagementIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void 注册Redis动态配置(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private AccessManagementService service;

    @Autowired
    private AccessManagementMapper mapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 清理访问管理集成测试数据() {
        jdbcTemplate.update("DELETE FROM sys_role_menu");
        jdbcTemplate.update("DELETE FROM sys_user_role");
        jdbcTemplate.update("DELETE FROM sys_operation_log");
        jdbcTemplate.update("DELETE FROM sys_role");
        jdbcTemplate.update("DELETE FROM sys_user");
    }

    @Test
    void 应完成用户创建修改角色替换与密码重置闭环() {
        AccessManagementService.UserView created = service.createUser(
                "dispatcher",
                "调度员",
                "temporary-password",
                "ENABLED"
        );
        UUID roleId = UUID.randomUUID();
        mapper.insertRole(roleId, "DISPATCHER", "调度员", "ENABLED", null, null);

        service.replaceUserRoles(created.id(), List.of(roleId));
        AccessManagementService.UserView updated = service.updateUser(
                created.id(),
                "调度主管",
                "ENABLED",
                created.version()
        );
        service.resetUserPassword(created.id(), "changed-temporary-password");

        assertThat(updated.displayName()).isEqualTo("调度主管");
        assertThat(mapper.findRoleIdsByUserId(created.id())).containsExactly(roleId);
        String passwordHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM sys_user WHERE id = ?",
                String.class,
                created.id()
        );
        assertThat(passwordHash).doesNotContain("changed-temporary-password");
        assertThat(passwordEncoder.matches("changed-temporary-password", passwordHash)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT password_change_required FROM sys_user WHERE id = ?",
                Boolean.class,
                created.id()
        )).isTrue();
        assertThat(jdbcTemplate.queryForList(
                "SELECT action FROM sys_operation_log ORDER BY occurred_at",
                String.class
        )).containsExactly(
                "CREATE_USER",
                "ASSIGN_USER_ROLES",
                "UPDATE_USER",
                "RESET_USER_PASSWORD"
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT after_summary::text FROM sys_operation_log WHERE action = 'RESET_USER_PASSWORD'",
                String.class
        )).doesNotContain("changed-temporary-password");
    }

    @Test
    void 应拒绝重复用户名和过期版本() {
        AccessManagementService.UserView created = service.createUser(
                "duplicate-user",
                "用户一",
                "temporary-password",
                "ENABLED"
        );

        assertThatThrownBy(() -> service.createUser(
                "duplicate-user",
                "用户二",
                "another-temporary-password",
                "ENABLED"
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("SYSTEM-409-001"));

        service.updateUser(created.id(), "第一次修改", "ENABLED", created.version());
        assertThatThrownBy(() -> service.updateUser(
                created.id(),
                "覆盖修改",
                "ENABLED",
                created.version()
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("SYSTEM-409-003"));
    }

    @Test
    void 应完成角色创建修改授权与删除闭环() {
        AccessManagementService.RoleView created = service.createRole(
                "auditor",
                "审计员",
                "ENABLED",
                "查看系统审计"
        );
        UUID menuId = mapper.findAllMenus().stream()
                .filter(menu -> "system:user:view".equals(menu.permission()))
                .findFirst()
                .orElseThrow()
                .id();

        service.replaceRoleMenus(created.id(), List.of(menuId));
        AccessManagementService.RoleView updated = service.updateRole(
                created.id(),
                "安全审计员",
                "DISABLED",
                "暂停授权",
                created.version()
        );

        assertThat(updated.code()).isEqualTo("AUDITOR");
        assertThat(updated.menuIds()).containsExactly(menuId);
        assertThat(service.findMenuTree()).isNotEmpty();
        service.deleteRole(created.id());
        assertThat(mapper.findManagedRoleById(created.id())).isEmpty();
        assertThat(jdbcTemplate.queryForList(
                "SELECT action FROM sys_operation_log ORDER BY occurred_at",
                String.class
        )).containsExactly(
                "CREATE_ROLE",
                "ASSIGN_ROLE_MENUS",
                "UPDATE_ROLE",
                "DELETE_ROLE"
        );
    }

    @Test
    void 应拒绝删除仍分配给用户的角色() {
        AccessManagementService.UserView user = service.createUser(
                "role-owner",
                "角色用户",
                "temporary-password",
                "ENABLED"
        );
        AccessManagementService.RoleView role = service.createRole(
                "ROLE_OWNER",
                "占用角色",
                "ENABLED",
                null
        );
        service.replaceUserRoles(user.id(), List.of(role.id()));

        assertThatThrownBy(() -> service.deleteRole(role.id()))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("SYSTEM-409-004"));
        assertThat(mapper.findManagedRoleById(role.id())).isPresent();
    }
}
