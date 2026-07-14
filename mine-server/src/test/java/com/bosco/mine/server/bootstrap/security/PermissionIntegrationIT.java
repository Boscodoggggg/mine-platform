package com.bosco.mine.server.bootstrap.security;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;
import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.server.MineServerApplication;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用真实 PostgreSQL 和 Redis 校验五表 RBAC、共享会话与踢下线行为。
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(PermissionIntegrationIT.PermissionProbeConfiguration.class)
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
class PermissionIntegrationIT {

    private static final UUID SUPER_ADMIN_ID = UUID.fromString("0198aef6-2e80-7000-8000-000000000001");
    private static final String SUPER_ADMIN_PASSWORD = "mine-admin-test-password";
    private static final Path SUPER_ADMIN_SECRET = createSecretFile();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine")
            .withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void 注册动态配置(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("mine.security.bootstrap-admin.enabled", () -> true);
        registry.add("mine.security.bootstrap-admin.id", SUPER_ADMIN_ID::toString);
        registry.add("mine.security.bootstrap-admin.username", () -> "mine-admin");
        registry.add("mine.security.bootstrap-admin.display-name", () -> "矿区超级管理员");
        registry.add("mine.security.bootstrap-admin.password-file", SUPER_ADMIN_SECRET::toString);
        registry.add("mine.security.password.bcrypt-strength", () -> 10);
        registry.add("mine.security.login-protection.max-failures-per-account", () -> 3);
        registry.add("mine.security.login-protection.max-failures-per-source", () -> 50);
        registry.add("mine.security.login-protection.attempt-window", () -> "PT10M");
        registry.add("mine.security.login-protection.lock-duration", () -> "PT15M");
    }

    @BeforeEach
    void 清理普通测试权限数据和Redis会话() {
        jdbcTemplate.update("DELETE FROM sys_login_log");
        jdbcTemplate.update("DELETE FROM sys_role_menu");
        jdbcTemplate.update("DELETE FROM sys_user_role");
        jdbcTemplate.update("DELETE FROM sys_menu");
        jdbcTemplate.update("DELETE FROM sys_role");
        jdbcTemplate.update("DELETE FROM sys_user WHERE id <> ?", SUPER_ADMIN_ID);
        jdbcTemplate.update("""
                UPDATE sys_user
                SET password_hash = ?, password_change_required = TRUE
                WHERE id = ?
                """, passwordEncoder.encode(SUPER_ADMIN_PASSWORD), SUPER_ADMIN_ID);
        Set<String> keys = redisTemplate.keys("*");
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void 超级管理员应完成首次改密后才能访问业务且所有旧令牌失效() throws Exception {
        LoginSession session = login("mine-admin", SUPER_ADMIN_PASSWORD);

        assertThat(session.userId()).isEqualTo(SUPER_ADMIN_ID.toString());
        assertThat(session.passwordChangeRequired()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_login_log
                WHERE user_id = ? AND event_type = 'LOGIN' AND result = 'SUCCESS'
                """, Long.class, SUPER_ADMIN_ID)).isOne();
        mockMvc.perform(get("/api/v1/test/permission")
                        .header(session.tokenName(), session.tokenValue()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-403-003"));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(session.tokenName(), session.tokenValue()))
                .andExpect(status().isOk());

        String secondToken = login("mine-admin", SUPER_ADMIN_PASSWORD).tokenValue();
        String changedPassword = "mine-admin-changed-password";
        mockMvc.perform(post("/api/v1/auth/password")
                        .header(session.tokenName(), session.tokenValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword":"%s",
                                  "newPassword":"%s"
                                }
                                """.formatted(SUPER_ADMIN_PASSWORD, changedPassword)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(session.tokenName(), session.tokenValue()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(session.tokenName(), secondToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"mine-admin","password":"%s"}
                                """.formatted(SUPER_ADMIN_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-401-001"));

        LoginSession changedSession = login("mine-admin", changedPassword);
        assertThat(changedSession.passwordChangeRequired()).isFalse();
        mockMvc.perform(get("/api/v1/test/permission")
                        .header(changedSession.tokenName(), changedSession.tokenValue()))
                .andExpect(status().isOk());
    }

    @Test
    void 错误密码应返回统一未授权响应() throws Exception {
        createUser("test-wrong-password", "correct-password");

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Request-Id", "request-login-failure")
                        .header("User-Agent", "mine-integration-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"test-wrong-password","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-401-001"));

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_login_log
                WHERE username_snapshot = 'test-wrong-password'
                  AND event_type = 'LOGIN_FAILURE'
                  AND result = 'FAILURE'
                  AND error_code = 'AUTH-401-001'
                  AND request_id = 'request-login-failure'
                  AND trace_id <> ''
                  AND client_ip = '127.0.0.1'::inet
                  AND user_agent = 'mine-integration-test'
                """, Long.class)).isOne();
    }

    @Test
    void 连续登录失败后应暂时限制账号且保持统一错误响应() throws Exception {
        createUser("test-rate-limit", "correct-password");

        for (int attempt = 0; attempt < 3; attempt++) {
            loginExpectingInvalidCredentials("test-rate-limit", "wrong-password");
        }
        loginExpectingInvalidCredentials("test-rate-limit", "correct-password");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_login_log
                WHERE username_snapshot = 'test-rate-limit'
                  AND event_type = 'LOGIN_FAILURE'
                  AND error_code = 'AUTH-401-001'
                """, Long.class)).isEqualTo(3L);
    }

    @Test
    void 并发爆破时进入密码校验的请求不得超过账号阈值() throws Exception {
        createUser("test-concurrent-limit", "correct-password");
        int requestCount = 20;
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(requestCount)) {
            var futures = IntStream.range(0, requestCount)
                    .mapToObj(ignored -> executor.<Void>submit(() -> {
                        start.await();
                        loginExpectingInvalidCredentials("test-concurrent-limit", "wrong-password");
                        return null;
                    }))
                    .toList();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }
        }

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_login_log
                WHERE username_snapshot = 'test-concurrent-limit'
                  AND event_type = 'LOGIN_FAILURE'
                  AND error_code = 'AUTH-401-001'
                """, Long.class)).isEqualTo(3L);
    }

    @Test
    void 七十二字节密码附加后缀后必须登录失败() throws Exception {
        String password = "a".repeat(72);
        createUser("test-bcrypt-boundary", password);

        login("test-bcrypt-boundary", password);
        loginExpectingInvalidCredentials("test-bcrypt-boundary", password + "suffix");
    }

    @Test
    void 登录后禁用账号应立即使普通登录态接口失效() throws Exception {
        UUID userId = createUser("test-disabled-session", "disabled-password");
        LoginSession session = login("test-disabled-session", "disabled-password");
        jdbcTemplate.update("UPDATE sys_user SET status = 'DISABLED' WHERE id = ?", userId);

        mockMvc.perform(get("/api/v1/test/login-only")
                        .header(session.tokenName(), session.tokenValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-401-002"));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(session.tokenName(), session.tokenValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-401-002"));
    }

    @Test
    void 退出应记录请求上下文且立即注销当前令牌() throws Exception {
        createUser("test-logout", "logout-password");
        LoginSession session = login("test-logout", "logout-password");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(session.tokenName(), session.tokenValue())
                        .header("X-Request-Id", "request-logout")
                        .header("User-Agent", "mine-logout-test"))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_login_log
                WHERE username_snapshot = 'test-logout'
                  AND event_type = 'LOGOUT'
                  AND result = 'SUCCESS'
                  AND request_id = 'request-logout'
                  AND client_ip = '127.0.0.1'::inet
                  AND user_agent = 'mine-logout-test'
                """, Long.class)).isOne();
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(session.tokenName(), session.tokenValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 未携带令牌访问当前用户接口应返回未授权() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-401-002"));
    }

    @Test
    void 应实时读取五表权限并区分缺权限和有权限() throws Exception {
        UUID userId = createUser("test-permission", "permission-password");
        LoginSession session = login("test-permission", "permission-password");

        mockMvc.perform(get("/api/v1/test/permission")
                        .header(session.tokenName(), session.tokenValue()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-403-001"));

        grantPermission(userId, "audit:view");

        mockMvc.perform(get("/api/v1/test/permission")
                        .header(session.tokenName(), session.tokenValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permission").value("audit:view"));
    }

    @Test
    void 当前用户菜单应补齐祖先目录并保留按钮权限() throws Exception {
        UUID userId = createUser("test-navigation", "navigation-password");
        UUID directoryId = UUID.randomUUID();
        UUID menuId = UUID.randomUUID();
        UUID buttonId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, name, type, path, icon, sort_order)
                VALUES (?, '设备管理', 'DIRECTORY', '/device', 'mdi:factory', 10)
                """, directoryId);
        jdbcTemplate.update("""
                INSERT INTO sys_menu (
                    id, parent_id, name, type, path, component_key, icon, sort_order
                ) VALUES (?, ?, '设备台账', 'MENU', '/devices', 'device-list', 'mdi:truck', 20)
                """, menuId, directoryId);
        jdbcTemplate.update("""
                INSERT INTO sys_menu (
                    id, parent_id, name, type, permission, sort_order
                ) VALUES (?, ?, '查看设备', 'BUTTON', 'device:device:list', 30)
                """, buttonId, menuId);
        grantMenu(userId, buttonId, "DEVICE_OPERATOR");
        LoginSession session = login("test-navigation", "navigation-password");

        mockMvc.perform(get("/api/v1/auth/menus")
                        .header(session.tokenName(), session.tokenValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("DEVICE_OPERATOR"))
                .andExpect(jsonPath("$.permissions[0]").value("device:device:list"))
                .andExpect(jsonPath("$.menus[0].id").value(directoryId.toString()))
                .andExpect(jsonPath("$.menus[0].children[0].id").value(menuId.toString()))
                .andExpect(jsonPath("$.menus[0].children[0].componentKey").value("device-list"))
                .andExpect(jsonPath("$.menus[0].children[0].children[0].id").value(buttonId.toString()));
    }

    @Test
    void 登录态应写入Redis且踢下线后令牌立即失效() throws Exception {
        createUser("test-kickout", "kickout-password");
        LoginSession session = login("test-kickout", "kickout-password");

        assertThat(SaManager.getSaTokenDao()).isInstanceOf(SaTokenDaoForRedisTemplate.class);
        assertThat(SaManager.getConfig().getActiveTimeout()).isEqualTo(1800L);
        assertThat(redisTemplate.keys("*")).isNotEmpty();
        StpUtil.kickout(session.userId());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(session.tokenName(), session.tokenValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-401-002"));
    }

    private LoginSession login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new LoginSession(
                JsonPath.read(body, "$.tokenName"),
                JsonPath.read(body, "$.tokenValue"),
                JsonPath.read(body, "$.user.id"),
                JsonPath.read(body, "$.user.passwordChangeRequired")
        );
    }

    private void loginExpectingInvalidCredentials(String username, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-401-001"))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    private UUID createUser(String username, String password) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_user (
                    id, username, password_hash, display_name, password_change_required
                ) VALUES (?, ?, ?, ?, FALSE)
                """, userId, username, passwordEncoder.encode(password), username);
        return userId;
    }

    private void grantPermission(UUID userId, String permission) {
        UUID roleId = UUID.randomUUID();
        UUID menuId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_role (id, code, name)
                VALUES (?, ?, '测试角色')
                """, roleId, "TEST_" + roleId);
        jdbcTemplate.update("""
                INSERT INTO sys_menu (id, name, type, permission)
                VALUES (?, '审计查看', 'BUTTON', ?)
                """, menuId, permission);
        jdbcTemplate.update("""
                INSERT INTO sys_user_role (id, user_id, role_id)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), userId, roleId);
        jdbcTemplate.update("""
                INSERT INTO sys_role_menu (id, role_id, menu_id)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), roleId, menuId);
    }

    private void grantMenu(UUID userId, UUID menuId, String roleCode) {
        UUID roleId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_role (id, code, name)
                VALUES (?, ?, '导航测试角色')
                """, roleId, roleCode);
        jdbcTemplate.update("""
                INSERT INTO sys_user_role (id, user_id, role_id)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), userId, roleId);
        jdbcTemplate.update("""
                INSERT INTO sys_role_menu (id, role_id, menu_id)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), roleId, menuId);
    }

    private static Path createSecretFile() {
        try {
            Path secret = Files.createTempFile("mine-super-admin-", ".secret");
            Files.writeString(secret, SUPER_ADMIN_PASSWORD);
            secret.toFile().deleteOnExit();
            return secret;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("无法创建超级管理员测试密钥", exception);
        }
    }

    /**
     * 权限测试端点配置，只在该集成测试上下文中生效。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class PermissionProbeConfiguration {

        /**
         * 注册用于验证注解鉴权的测试控制器。
         *
         * @return 权限测试控制器
         */
        @Bean
        PermissionProbeController permissionProbeController() {
            return new PermissionProbeController();
        }
    }

    /**
     * 仅用于集成测试 Sa-Token 权限注解的端点。
     */
    @RestController
    @RequestMapping("/api/v1/test")
    static class PermissionProbeController {

        /**
         * 返回通过鉴权的权限名称。
         *
         * @return 权限名称
         */
        @GetMapping("/permission")
        @SaCheckPermission("audit:view")
        Map<String, String> permission() {
            return Map.of("permission", "audit:view");
        }

        /**
         * 返回仅要求登录、不声明业务权限的探针结果。
         *
         * @return 登录态探针结果
         */
        @GetMapping("/login-only")
        Map<String, String> loginOnly() {
            return Map.of("status", "authenticated");
        }
    }

    /**
     * 登录测试产生的会话信息。
     *
     * @param tokenName              令牌请求头名称
     * @param tokenValue             令牌值
     * @param userId                 用户标识
     * @param passwordChangeRequired 是否必须修改初始密码
     */
    record LoginSession(
            String tokenName,
            String tokenValue,
            String userId,
            Boolean passwordChangeRequired
    ) {
    }
}
