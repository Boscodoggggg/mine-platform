package com.bosco.mine.server.modules.mine.service;

import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.mine.dto.MineContextResponse;
import com.bosco.mine.server.modules.mine.dto.MineOrganizationResponse;
import com.bosco.mine.server.modules.mine.dto.MineScopeGrantResponse;
import com.bosco.mine.server.modules.mine.dto.MineSiteResponse;
import com.bosco.mine.server.modules.mine.model.MineOrganization;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.repository.MineScopeMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 查询当前用户可见的组织、矿区和数据授权。
 */
@Service
public class MineScopeQueryService {

    private static final int CONTEXT_LIMIT = 1_000;

    private final MineScopeMapper mapper;
    private final MineScopeAuthorizer authorizer;

    /**
     * 创建矿区作用域查询服务。
     *
     * @param mapper 作用域数据访问接口
     * @param authorizer 数据作用域授权器
     */
    public MineScopeQueryService(MineScopeMapper mapper, MineScopeAuthorizer authorizer) {
        this.mapper = mapper;
        this.authorizer = authorizer;
    }

    /**
     * 返回当前用户的完整活动矿区选择上下文。
     *
     * @return 组织和矿区上下文
     */
    @Transactional(readOnly = true)
    public MineContextResponse context() {
        UUID userId = authorizer.currentUserId();
        boolean allAccess = authorizer.isSuperAdmin();
        List<MineContextResponse.OrganizationContext> organizations = mapper
                .findVisibleOrganizations(userId, allAccess, null, "ACTIVE", 0, CONTEXT_LIMIT)
                .stream()
                .map(organization -> new MineContextResponse.OrganizationContext(
                        toResponse(organization),
                        allAccess || mapper.hasOrganizationWideGrant(userId, organization.id()),
                        mapper.findVisibleSites(
                                        userId, allAccess, organization.id(), null,
                                        "ACTIVE", 0, CONTEXT_LIMIT)
                                .stream()
                                .map(MineScopeQueryService::toResponse)
                                .toList()
                ))
                .toList();
        return new MineContextResponse(organizations);
    }

    /** 分页查询可见组织。 */
    @Transactional(readOnly = true)
    public PageResponse<MineOrganizationResponse> findOrganizations(
            String keyword,
            String status,
            int page,
            int size
    ) {
        UUID userId = authorizer.currentUserId();
        boolean allAccess = authorizer.isSuperAdmin();
        String normalizedKeyword = optional(keyword);
        String normalizedStatus = optionalUpper(status);
        int offset = Math.multiplyExact(page - 1, size);
        return new PageResponse<>(
                mapper.findVisibleOrganizations(
                                userId, allAccess, normalizedKeyword, normalizedStatus, offset, size)
                        .stream().map(MineScopeQueryService::toResponse).toList(),
                page,
                size,
                mapper.countVisibleOrganizations(
                        userId, allAccess, normalizedKeyword, normalizedStatus)
        );
    }

    /** 分页查询组织下的可见矿区。 */
    @Transactional(readOnly = true)
    public PageResponse<MineSiteResponse> findSites(
            UUID organizationId,
            String keyword,
            String status,
            int page,
            int size
    ) {
        authorizer.requireOrganization(organizationId);
        UUID userId = authorizer.currentUserId();
        boolean allAccess = authorizer.isSuperAdmin();
        String normalizedKeyword = optional(keyword);
        String normalizedStatus = optionalUpper(status);
        int offset = Math.multiplyExact(page - 1, size);
        return new PageResponse<>(
                mapper.findVisibleSites(
                                userId, allAccess, organizationId, normalizedKeyword,
                                normalizedStatus, offset, size)
                        .stream().map(MineScopeQueryService::toResponse).toList(),
                page,
                size,
                mapper.countVisibleSites(
                        userId, allAccess, organizationId, normalizedKeyword, normalizedStatus)
        );
    }

    /** 查询组织下全部数据授权。 */
    @Transactional(readOnly = true)
    public List<MineScopeGrantResponse> findScopeGrants(UUID organizationId) {
        authorizer.requireOrganizationWide(organizationId);
        return mapper.findScopeGrants(organizationId).stream()
                .map(item -> new MineScopeGrantResponse(
                        item.id(), item.userId(), item.organizationId(), item.siteId(),
                        item.grantedAt(), item.grantedBy()))
                .toList();
    }

    /** 将组织持久化模型转换为响应。 */
    public static MineOrganizationResponse toResponse(MineOrganization organization) {
        return new MineOrganizationResponse(
                organization.id(), organization.organizationCode(), organization.name(),
                organization.status(), organization.archivedAt(), organization.createdAt(),
                organization.updatedAt(), organization.version());
    }

    /** 将矿区持久化模型转换为响应。 */
    public static MineSiteResponse toResponse(MineSite site) {
        return new MineSiteResponse(
                site.id(), site.organizationId(), site.siteCode(), site.name(),
                site.timezone(), site.description(), site.status(), site.archivedAt(),
                site.createdAt(), site.updatedAt(), site.version());
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String optionalUpper(String value) {
        String normalized = optional(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }
}
