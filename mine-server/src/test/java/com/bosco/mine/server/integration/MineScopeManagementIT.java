package com.bosco.mine.server.integration;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.mine.dto.MineOrganizationResponse;
import com.bosco.mine.server.modules.mine.dto.MineSiteResponse;
import com.bosco.mine.server.modules.mine.service.MineRequestIdentity;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import com.bosco.mine.server.modules.mine.service.MineScopeCommandService;
import com.bosco.mine.server.modules.mine.service.MineScopeQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.TestConfiguration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 校验矿区主数据、数据授权和审计闭环。
 */
@Testcontainers
@ActiveProfiles("test")
@Import(MineScopeManagementIT.IdentityTestConfiguration.class)
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class MineScopeManagementIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private MineScopeCommandService commandService;

    @Autowired
    private MineScopeQueryService queryService;

    @Autowired
    private MineScopeAuthorizer authorizer;

    @Autowired
    private MutableMineRequestIdentity identity;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID firstUserId;
    private UUID secondUserId;
    private UUID thirdUserId;

    @BeforeEach
    void 准备用户并清理矿区数据() {
        jdbcTemplate.update("DELETE FROM mine_user_scope_grant");
        jdbcTemplate.update("DELETE FROM mine_site");
        jdbcTemplate.update("DELETE FROM mine_organization");
        jdbcTemplate.update("DELETE FROM sys_operation_log");
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'mine-scope-it-%'");
        firstUserId = createUser("first");
        secondUserId = createUser("second");
        thirdUserId = createUser("third");
        identity.use(firstUserId);
    }

    @Test
    void 应按组织和矿区授权隔离上下文并阻止站点授权扩权() {
        MineOrganizationResponse firstOrganization = commandService.createOrganization(
                "north-group", "北方矿业集团");
        MineSiteResponse firstSite = commandService.createSite(
                firstOrganization.id(), "north-main", "北方一号矿", "Asia/Shanghai", null);
        MineSiteResponse secondSite = commandService.createSite(
                firstOrganization.id(), "north-west", "北方西矿", "Asia/Shanghai", "露天矿");

        identity.use(secondUserId);
        MineOrganizationResponse secondOrganization = commandService.createOrganization(
                "south-group", "南方矿业集团");
        MineSiteResponse thirdSite = commandService.createSite(
                secondOrganization.id(), "south-main", "南方一号矿", "Asia/Shanghai", null);
        jdbcTemplate.update("DELETE FROM mine_user_scope_grant WHERE user_id = ?", secondUserId);
        jdbcTemplate.update("""
                INSERT INTO mine_user_scope_grant (id, user_id, organization_id, site_id)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), secondUserId, secondOrganization.id(), thirdSite.id());

        identity.use(firstUserId);
        assertThat(queryService.context().organizations())
                .extracting(item -> item.organization().id())
                .containsExactly(firstOrganization.id());
        assertThat(queryService.context().organizations().getFirst().sites())
                .extracting(MineSiteResponse::id)
                .containsExactlyInAnyOrder(firstSite.id(), secondSite.id());
        assertThatThrownBy(() -> authorizer.requireSite(thirdSite.id()))
                .isInstanceOf(MineException.class);

        identity.use(secondUserId);
        assertThat(queryService.context().organizations())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.organization().id()).isEqualTo(secondOrganization.id());
                    assertThat(item.sites()).extracting(MineSiteResponse::id)
                            .containsExactly(thirdSite.id());
                });
        assertThatThrownBy(() -> commandService.grantScope(
                thirdUserId, secondOrganization.id(), null))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCOPE-403-002"));

        identity.use(thirdUserId);
        assertThat(queryService.context().organizations()).isEmpty();
    }

    @Test
    void 应完成授权归档乐观锁和脱敏审计闭环() {
        MineOrganizationResponse organization = commandService.createOrganization(
                "central-group", "中央矿业集团");
        MineSiteResponse site = commandService.createSite(
                organization.id(), "central-main", "中央一号矿", "Asia/Shanghai", "井工矿");
        commandService.grantScope(thirdUserId, organization.id(), site.id());

        identity.use(thirdUserId);
        assertThat(authorizer.requireSite(site.id()).id()).isEqualTo(site.id());
        assertThatThrownBy(() -> authorizer.requireOrganizationWide(organization.id()))
                .isInstanceOf(MineException.class);

        identity.use(firstUserId);
        MineSiteResponse updated = commandService.updateSite(
                site.id(), "中央主矿", "Asia/Shanghai", "更新说明", site.version());
        assertThatThrownBy(() -> commandService.updateSite(
                site.id(), "并发覆盖", "Asia/Shanghai", null, site.version()))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCOPE-409-002"));
        commandService.archiveSite(site.id(), updated.version());

        identity.use(thirdUserId);
        assertThatThrownBy(() -> authorizer.requireSite(site.id()))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCOPE-404-002"));
        assertThat(jdbcTemplate.queryForList("""
                SELECT module || ':' || action
                FROM sys_operation_log
                WHERE module = 'MINE_MANAGEMENT'
                ORDER BY occurred_at, id
                """, String.class)).containsExactly(
                "MINE_MANAGEMENT:CREATE_ORGANIZATION",
                "MINE_MANAGEMENT:CREATE_SITE",
                "MINE_MANAGEMENT:GRANT_SCOPE",
                "MINE_MANAGEMENT:UPDATE_SITE",
                "MINE_MANAGEMENT:ARCHIVE_SITE"
        );
    }

    private UUID createUser(String suffix) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password_hash, display_name)
                VALUES (?, ?, 'bcrypt:test', ?)
                """, id, "mine-scope-it-" + suffix, "矿区测试用户" + suffix);
        return id;
    }

    /**
     * 为集成测试提供不依赖 Redis 会话的稳定请求身份。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class IdentityTestConfiguration {

        /**
         * 覆盖生产 Sa-Token 请求身份。
         *
         * @return 可切换的测试身份
         */
        @Bean
        @Primary
        MutableMineRequestIdentity mutableMineRequestIdentity() {
            return new MutableMineRequestIdentity();
        }
    }

    /**
     * 单线程集成测试使用的可切换身份。
     */
    static final class MutableMineRequestIdentity implements MineRequestIdentity {
        private UUID userId;

        /** 切换当前测试用户。 */
        void use(UUID value) {
            userId = value;
        }

        /** 返回当前测试用户。 */
        @Override
        public UUID currentUserId() {
            return userId;
        }
    }
}
