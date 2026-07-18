package com.bosco.mine.server.modules.mine.service;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.security.MineSecurityProperties;
import com.bosco.mine.server.modules.mine.model.MineOrganization;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.repository.MineScopeMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 统一执行组织和矿区数据范围授权，业务服务不得信任客户端作用域字段。
 */
@Service
public class MineScopeAuthorizer {

    private static final ErrorCode USER_DISABLED =
            new ErrorCode("MINE-SCOPE-403-001", "当前用户不可访问矿区数据");
    private static final ErrorCode SCOPE_DENIED =
            new ErrorCode("MINE-SCOPE-403-002", "无权访问该组织或矿区");
    private static final ErrorCode ORGANIZATION_NOT_FOUND =
            new ErrorCode("MINE-SCOPE-404-001", "组织不存在或不可用");
    private static final ErrorCode SITE_NOT_FOUND =
            new ErrorCode("MINE-SCOPE-404-002", "矿区不存在或不可用");

    private final MineScopeMapper mapper;
    private final MineRequestIdentity identity;
    private final UUID bootstrapAdminId;

    /**
     * 创建矿区数据作用域授权器。
     *
     * @param mapper 作用域数据访问接口
     * @param identity 当前请求身份
     * @param properties 平台安全配置
     */
    public MineScopeAuthorizer(
            MineScopeMapper mapper,
            MineRequestIdentity identity,
            MineSecurityProperties properties
    ) {
        this.mapper = Objects.requireNonNull(mapper, "作用域数据访问接口不能为空");
        this.identity = Objects.requireNonNull(identity, "请求身份不能为空");
        this.bootstrapAdminId = Objects.requireNonNull(
                properties.bootstrapAdmin().id(), "超级管理员标识不能为空");
    }

    /**
     * 返回当前启用用户标识。
     *
     * @return 当前用户标识
     */
    public UUID currentUserId() {
        UUID userId = identity.currentUserId();
        if (!mapper.isEnabledUser(userId)) {
            throw new MineException(USER_DISABLED, Map.of());
        }
        return userId;
    }

    /** 判断当前用户是否为 Bootstrap 超级管理员。 */
    public boolean isSuperAdmin() {
        return bootstrapAdminId.equals(currentUserId());
    }

    /**
     * 要求当前用户可查看组织；矿区级授权也允许看到所属组织摘要。
     *
     * @param organizationId 组织标识
     * @return 活动组织
     */
    public MineOrganization requireOrganization(UUID organizationId) {
        MineOrganization organization = activeOrganization(organizationId);
        UUID userId = currentUserId();
        if (!bootstrapAdminId.equals(userId)
                && !mapper.hasOrganizationAccess(userId, organizationId)) {
            throw denied();
        }
        return organization;
    }

    /**
     * 要求当前用户拥有组织级授权，用于创建矿区和转授权。
     *
     * @param organizationId 组织标识
     * @return 活动组织
     */
    public MineOrganization requireOrganizationWide(UUID organizationId) {
        MineOrganization organization = activeOrganization(organizationId);
        UUID userId = currentUserId();
        if (!bootstrapAdminId.equals(userId)
                && !mapper.hasOrganizationWideGrant(userId, organizationId)) {
            throw denied();
        }
        return organization;
    }

    /**
     * 要求当前用户可访问活动矿区。
     *
     * @param siteId 矿区标识
     * @return 活动矿区
     */
    public MineSite requireSite(UUID siteId) {
        MineSite site = mapper.findSiteById(siteId)
                .filter(item -> "ACTIVE".equals(item.status()))
                .orElseThrow(() -> new MineException(SITE_NOT_FOUND, Map.of()));
        MineOrganization organization = mapper.findOrganizationById(site.organizationId())
                .filter(item -> "ACTIVE".equals(item.status()))
                .orElseThrow(() -> new MineException(SITE_NOT_FOUND, Map.of()));
        UUID userId = currentUserId();
        if (!bootstrapAdminId.equals(userId) && !mapper.hasSiteAccess(userId, siteId)) {
            throw denied();
        }
        return site;
    }

    private MineOrganization activeOrganization(UUID organizationId) {
        return mapper.findOrganizationById(organizationId)
                .filter(item -> "ACTIVE".equals(item.status()))
                .orElseThrow(() -> new MineException(ORGANIZATION_NOT_FOUND, Map.of()));
    }

    private MineException denied() {
        return new MineException(SCOPE_DENIED, Map.of());
    }
}
