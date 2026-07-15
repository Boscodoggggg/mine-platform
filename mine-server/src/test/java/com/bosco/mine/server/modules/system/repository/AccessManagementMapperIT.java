package com.bosco.mine.server.modules.system.repository;

import com.bosco.mine.server.MineServerApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用真实 PostgreSQL 校验访问管理查询、关联替换基础操作和乐观锁。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class AccessManagementMapperIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private AccessManagementMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void 清理访问管理测试数据() {
        jdbcTemplate.update("DELETE FROM sys_role_menu");
        jdbcTemplate.update("DELETE FROM sys_user_role");
        jdbcTemplate.update("DELETE FROM sys_role");
        jdbcTemplate.update("DELETE FROM sys_user");
    }

    @Test
    void 应分页查询用户并通过版本号避免覆盖并发修改() {
        UUID userId = UUID.randomUUID();
        assertThat(mapper.insertUser(
                userId,
                "dispatch-admin",
                "bcrypt-hash",
                "调度管理员",
                "ENABLED",
                null
        )).isOne();

        assertThat(mapper.countUsers("调度", "ENABLED")).isOne();
        assertThat(mapper.findUsers("调度", "ENABLED", 0, 20))
                .singleElement()
                .satisfies(user -> {
                    assertThat(user.id()).isEqualTo(userId);
                    assertThat(user.username()).isEqualTo("dispatch-admin");
                    assertThat(user.version()).isZero();
                });

        assertThat(mapper.updateUser(userId, "调度主管", "DISABLED", 0, null)).isOne();
        assertThat(mapper.updateUser(userId, "并发覆盖", "ENABLED", 0, null)).isZero();
        assertThat(mapper.findManagedUserById(userId)).get().satisfies(user -> {
            assertThat(user.displayName()).isEqualTo("调度主管");
            assertThat(user.status()).isEqualTo("DISABLED");
            assertThat(user.version()).isOne();
        });
    }

    @Test
    void 应维护用户角色关系并识别仍被使用的角色() {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        mapper.insertUser(userId, "role-user", "bcrypt-hash", "角色用户", "ENABLED", null);
        mapper.insertRole(roleId, "DISPATCHER", "调度员", "ENABLED", "负责车辆调度", null);

        assertThat(mapper.countRoles(null, "ENABLED")).isOne();
        assertThat(mapper.findRoles("DISPATCH", "ENABLED", 0, 20))
                .extracting(AccessManagementMapper.ManagedRoleRecord::code)
                .containsExactly("DISPATCHER");
        assertThat(mapper.insertUserRole(UUID.randomUUID(), userId, roleId, null)).isOne();
        assertThat(mapper.findRoleIdsByUserId(userId)).containsExactly(roleId);
        assertThat(mapper.countUsersByRoleId(roleId)).isOne();

        assertThat(mapper.updateRole(roleId, "车辆调度员", "DISABLED", "暂停使用", 0, null)).isOne();
        assertThat(mapper.updateRole(roleId, "并发覆盖", "ENABLED", null, 0, null)).isZero();
        assertThat(mapper.findManagedRoleById(roleId)).get().satisfies(role -> {
            assertThat(role.name()).isEqualTo("车辆调度员");
            assertThat(role.status()).isEqualTo("DISABLED");
            assertThat(role.version()).isOne();
        });

        assertThat(mapper.deleteUserRoles(userId)).isOne();
        assertThat(mapper.findRoleIdsByUserId(userId)).isEmpty();
    }

    @Test
    void 应查询完整只读菜单并维护角色授权关系() {
        UUID roleId = UUID.randomUUID();
        mapper.insertRole(roleId, "AUDITOR", "审计员", "ENABLED", null, null);
        List<AccessManagementMapper.ManagedMenuRecord> menus = mapper.findAllMenus();
        UUID userViewMenuId = menus.stream()
                .filter(menu -> "system:user:view".equals(menu.permission()))
                .findFirst()
                .orElseThrow()
                .id();

        assertThat(mapper.countExistingMenuIds(List.of(userViewMenuId))).isOne();
        assertThat(mapper.insertRoleMenu(UUID.randomUUID(), roleId, userViewMenuId, null)).isOne();
        assertThat(mapper.findMenuIdsByRoleId(roleId)).containsExactly(userViewMenuId);
        assertThat(mapper.deleteRoleMenus(roleId)).isOne();
        assertThat(mapper.findMenuIdsByRoleId(roleId)).isEmpty();
    }
}
