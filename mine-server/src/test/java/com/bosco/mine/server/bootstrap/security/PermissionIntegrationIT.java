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
    }

    @BeforeEach
    void 清理普通测试权限数据和Redis会话() {
        jdbcTemplate.update("DELETE FROM sys_login_log");
        jdbcTemplate.update("DELETE FROM sys_role_menu");
        jdbcTemplate.update("DELETE FROM sys_user_role");
        jdbcTemplate.update("DELETE FROM sys_menu");
        jdbcTemplate.update("DELETE FROM sys_role");
        jdbcTemplate.update("DELETE FROM sys_user WHERE id <> ?", SUPER_ADMIN_ID);
        Set<String> keys = redisTemplate.keys("*");
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void 超级管理员应由外部密钥初始化并强制首次改密() throws Exception {
        LoginSession session = login("mine-admin", SUPER_ADMIN_PASSWORD);

        assertThat(session.userId()).isEqualTo(SUPER_ADMIN_ID.toString());
        assertThat(session.passwordChangeRequired()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_login_log
                WHERE user_id = ? AND event_type = 'LOGIN' AND result = 'SUCCESS'
                """, Long.class, SUPER_ADMIN_ID)).isOne();
        mockMvc.perform(get("/api/v1/test/permission")
                        .header(session.tokenName(), session.tokenValue()))
                .andExpect(status().isOk());
    }

    @Test
    void 错误密码应返回统一未授权响应() throws Exception {
        createUser("test-wrong-password", "correct-password");

        mockMvc.perform(post("/api/v1/auth/login")
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
                  AND trace_id <> ''
                """, Long.class)).isOne();
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
    void 登录态应写入Redis且踢下线后令牌立即失效() throws Exception {
        createUser("test-kickout", "kickout-password");
        LoginSession session = login("test-kickout", "kickout-password");

        assertThat(SaManager.getSaTokenDao()).isInstanceOf(SaTokenDaoForRedisTemplate.class);
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
