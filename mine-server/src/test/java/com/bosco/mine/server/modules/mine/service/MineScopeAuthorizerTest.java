package com.bosco.mine.server.modules.mine.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.security.MineSecurityProperties;
import com.bosco.mine.server.modules.mine.model.MineOrganization;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.repository.MineScopeMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 校验组织级、矿区级和超级管理员数据作用域判定。
 */
class MineScopeAuthorizerTest {

    private static final UUID SUPER_ADMIN_ID =
            UUID.fromString("0198aef6-2e80-7000-8000-000000000001");

    @Test
    void 超级管理员只能在账号启用时访问全部活动矿区() {
        MineScopeMapper mapper = mock(MineScopeMapper.class);
        UUID organizationId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        when(mapper.isEnabledUser(SUPER_ADMIN_ID)).thenReturn(true);
        when(mapper.findOrganizationById(organizationId)).thenReturn(Optional.of(
                organization(organizationId, "ACTIVE")));
        when(mapper.findSiteById(siteId)).thenReturn(Optional.of(
                site(siteId, organizationId, "ACTIVE")));
        MineScopeAuthorizer authorizer = authorizer(mapper, () -> SUPER_ADMIN_ID);

        assertThat(authorizer.requireSite(siteId).id()).isEqualTo(siteId);

        when(mapper.isEnabledUser(SUPER_ADMIN_ID)).thenReturn(false);
        assertThatThrownBy(() -> authorizer.requireSite(siteId))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCOPE-403-001"));
    }

    @Test
    void 组织级授权覆盖所属矿区而矿区级授权不能扩权管理组织() {
        MineScopeMapper mapper = mock(MineScopeMapper.class);
        UUID userId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        when(mapper.isEnabledUser(userId)).thenReturn(true);
        when(mapper.findOrganizationById(organizationId)).thenReturn(Optional.of(
                organization(organizationId, "ACTIVE")));
        when(mapper.findSiteById(siteId)).thenReturn(Optional.of(
                site(siteId, organizationId, "ACTIVE")));
        when(mapper.hasOrganizationAccess(userId, organizationId)).thenReturn(true);
        when(mapper.hasOrganizationWideGrant(userId, organizationId)).thenReturn(false);
        when(mapper.hasSiteAccess(userId, siteId)).thenReturn(true);
        MineScopeAuthorizer authorizer = authorizer(mapper, () -> userId);

        assertThat(authorizer.requireOrganization(organizationId).id()).isEqualTo(organizationId);
        assertThat(authorizer.requireSite(siteId).id()).isEqualTo(siteId);
        assertThatThrownBy(() -> authorizer.requireOrganizationWide(organizationId))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCOPE-403-002"));
    }

    @Test
    void 应拒绝无授权和已归档资源且不暴露资源是否存在() {
        MineScopeMapper mapper = mock(MineScopeMapper.class);
        UUID userId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        when(mapper.isEnabledUser(userId)).thenReturn(true);
        when(mapper.findOrganizationById(organizationId)).thenReturn(Optional.of(
                organization(organizationId, "ARCHIVED")));
        when(mapper.findSiteById(siteId)).thenReturn(Optional.of(
                site(siteId, organizationId, "ARCHIVED")));
        MineScopeAuthorizer authorizer = authorizer(mapper, () -> userId);

        assertThatThrownBy(() -> authorizer.requireOrganization(organizationId))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCOPE-404-001"));
        assertThatThrownBy(() -> authorizer.requireSite(siteId))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCOPE-404-002"));
    }

    @Test
    void 实时通道应按显式用户重新校验矿区授权() {
        MineScopeMapper mapper = mock(MineScopeMapper.class);
        UUID userId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        when(mapper.isEnabledUser(userId)).thenReturn(true);
        when(mapper.findOrganizationById(organizationId)).thenReturn(Optional.of(
                organization(organizationId, "ACTIVE")));
        when(mapper.findSiteById(siteId)).thenReturn(Optional.of(
                site(siteId, organizationId, "ACTIVE")));
        when(mapper.hasSiteAccess(userId, siteId)).thenReturn(true);
        MineScopeAuthorizer authorizer = authorizer(mapper, UUID::randomUUID);

        assertThat(authorizer.requireSiteForUser(userId, siteId).id()).isEqualTo(siteId);

        when(mapper.hasSiteAccess(userId, siteId)).thenReturn(false);
        assertThatThrownBy(() -> authorizer.requireSiteForUser(userId, siteId))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCOPE-403-002"));
    }

    private MineScopeAuthorizer authorizer(
            MineScopeMapper mapper,
            MineRequestIdentity identity
    ) {
        MineSecurityProperties properties = new MineSecurityProperties(
                new MineSecurityProperties.BootstrapAdmin(
                        false, SUPER_ADMIN_ID, "mine-admin", "矿区超级管理员", null),
                null,
                null
        );
        return new MineScopeAuthorizer(mapper, identity, properties);
    }

    private MineOrganization organization(UUID id, String status) {
        return new MineOrganization(
                id, "group-a", "甲矿业集团", status,
                Instant.EPOCH, null, Instant.EPOCH, null,
                "ARCHIVED".equals(status) ? Instant.EPOCH : null, 0
        );
    }

    private MineSite site(UUID id, UUID organizationId, String status) {
        return new MineSite(
                id, organizationId, "site-a", "甲矿区", "Asia/Shanghai", null, status,
                Instant.EPOCH, null, Instant.EPOCH, null,
                "ARCHIVED".equals(status) ? Instant.EPOCH : null, 0
        );
    }
}
