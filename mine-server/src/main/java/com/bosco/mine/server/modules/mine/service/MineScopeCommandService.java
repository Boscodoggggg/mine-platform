package com.bosco.mine.server.modules.mine.service;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.mine.dto.MineOrganizationResponse;
import com.bosco.mine.server.modules.mine.dto.MineSiteResponse;
import com.bosco.mine.server.modules.mine.model.MineOrganization;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.location.LocationSnapshotService;
import com.bosco.mine.server.modules.mine.repository.MineScopeMapper;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 处理组织、矿区和数据授权写操作并追加脱敏审计。
 */
@Service
public class MineScopeCommandService {

    private static final ErrorCode ORGANIZATION_NOT_FOUND =
            new ErrorCode("MINE-SCOPE-404-001", "组织不存在或不可用");
    private static final ErrorCode SITE_NOT_FOUND =
            new ErrorCode("MINE-SCOPE-404-002", "矿区不存在或不可用");
    private static final ErrorCode ORGANIZATION_CODE_CONFLICT =
            new ErrorCode("MINE-SCOPE-409-001", "组织编码已存在");
    private static final ErrorCode SITE_CODE_CONFLICT =
            new ErrorCode("MINE-SCOPE-409-002", "矿区编码已存在或数据版本冲突");
    private static final ErrorCode ORGANIZATION_VERSION_CONFLICT =
            new ErrorCode("MINE-SCOPE-409-003", "组织数据已被其他管理员修改");
    private static final ErrorCode ORGANIZATION_IN_USE =
            new ErrorCode("MINE-SCOPE-409-004", "组织下仍有活动矿区");
    private static final ErrorCode TARGET_USER_DISABLED =
            new ErrorCode("MINE-SCOPE-422-001", "目标用户不存在或未启用");
    private static final ErrorCode SITE_ORGANIZATION_MISMATCH =
            new ErrorCode("MINE-SCOPE-422-002", "矿区不属于目标组织");

    private final MineScopeMapper mapper;
    private final MineScopeAuthorizer authorizer;
    private final OperationAuditService auditService;
    private final LocationSnapshotService locationSnapshotService;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建矿区作用域写服务。
     *
     * @param mapper 作用域数据访问接口
     * @param authorizer 数据作用域授权器
     * @param auditService 操作审计服务
     * @param locationSnapshotService 定位运行快照服务
     */
    public MineScopeCommandService(
            MineScopeMapper mapper,
            MineScopeAuthorizer authorizer,
            OperationAuditService auditService,
            LocationSnapshotService locationSnapshotService
    ) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.auditService = auditService;
        this.locationSnapshotService = locationSnapshotService;
    }

    /** 创建组织并给创建者组织级数据授权。 */
    @Transactional
    public MineOrganizationResponse createOrganization(String code, String name) {
        UUID operatorId = authorizer.currentUserId();
        UUID organizationId = idGenerator.next();
        String normalizedCode = normalizeCode(code);
        try {
            mapper.insertOrganization(organizationId, normalizedCode, requiredText(name), operatorId);
            mapper.insertScopeGrant(
                    idGenerator.next(), operatorId, organizationId, null, operatorId);
        } catch (DuplicateKeyException exception) {
            throw new MineException(
                    ORGANIZATION_CODE_CONFLICT, Map.of("organizationCode", normalizedCode));
        }
        MineOrganizationResponse created = organizationById(organizationId);
        audit("CREATE_ORGANIZATION", "mine:organization:edit", "MINE_ORGANIZATION",
                organizationId, Map.of(), organizationSummary(created));
        return created;
    }

    /** 使用乐观锁更新组织名称。 */
    @Transactional
    public MineOrganizationResponse updateOrganization(UUID organizationId, String name, long version) {
        MineOrganization before = authorizer.requireOrganizationWide(organizationId);
        UUID operatorId = authorizer.currentUserId();
        if (mapper.updateOrganization(organizationId, requiredText(name), version, operatorId) == 0) {
            throw new MineException(ORGANIZATION_VERSION_CONFLICT, Map.of("organizationId", organizationId));
        }
        MineOrganizationResponse updated = organizationById(organizationId);
        audit("UPDATE_ORGANIZATION", "mine:organization:edit", "MINE_ORGANIZATION",
                organizationId, organizationSummary(MineScopeQueryService.toResponse(before)),
                organizationSummary(updated));
        return updated;
    }

    /** 归档没有活动矿区的组织。 */
    @Transactional
    public void archiveOrganization(UUID organizationId, long version) {
        MineOrganization before = authorizer.requireOrganizationWide(organizationId);
        if (mapper.countActiveSites(organizationId) > 0) {
            throw new MineException(ORGANIZATION_IN_USE, Map.of("organizationId", organizationId));
        }
        UUID operatorId = authorizer.currentUserId();
        if (mapper.archiveOrganization(organizationId, version, operatorId) == 0) {
            throw new MineException(ORGANIZATION_VERSION_CONFLICT, Map.of("organizationId", organizationId));
        }
        MineOrganizationResponse archived = organizationById(organizationId);
        audit("ARCHIVE_ORGANIZATION", "mine:organization:edit", "MINE_ORGANIZATION",
                organizationId, organizationSummary(MineScopeQueryService.toResponse(before)),
                organizationSummary(archived));
    }

    /** 在拥有组织级授权的组织下创建矿区。 */
    @Transactional
    public MineSiteResponse createSite(
            UUID organizationId,
            String siteCode,
            String name,
            String timezone,
            String description
    ) {
        authorizer.requireOrganizationWide(organizationId);
        UUID operatorId = authorizer.currentUserId();
        UUID siteId = idGenerator.next();
        String normalizedCode = normalizeCode(siteCode);
        try {
            mapper.insertSite(
                    siteId, organizationId, normalizedCode, requiredText(name),
                    requiredText(timezone), optionalText(description), operatorId);
        } catch (DuplicateKeyException exception) {
            throw new MineException(SITE_CODE_CONFLICT, Map.of("siteCode", normalizedCode));
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("矿区时区或字段不合法", exception);
        }
        MineSiteResponse created = siteById(siteId);
        audit("CREATE_SITE", "mine:site:edit", "MINE_SITE", siteId,
                Map.of(), siteSummary(created));
        locationSnapshotService.captureSite(
                created.organizationId(), created.id(),
                LocationSnapshotService.ChangeReason.SITE_CHANGE);
        return created;
    }

    /** 使用乐观锁更新活动矿区。 */
    @Transactional
    public MineSiteResponse updateSite(
            UUID siteId,
            String name,
            String timezone,
            String description,
            long version
    ) {
        MineSite before = authorizer.requireSite(siteId);
        UUID operatorId = authorizer.currentUserId();
        try {
            if (mapper.updateSite(
                    siteId, requiredText(name), requiredText(timezone), optionalText(description),
                    version, operatorId) == 0) {
                throw new MineException(SITE_CODE_CONFLICT, Map.of("siteId", siteId));
            }
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("矿区时区或字段不合法", exception);
        }
        MineSiteResponse updated = siteById(siteId);
        audit("UPDATE_SITE", "mine:site:edit", "MINE_SITE", siteId,
                siteSummary(MineScopeQueryService.toResponse(before)), siteSummary(updated));
        locationSnapshotService.captureSite(
                updated.organizationId(), updated.id(),
                LocationSnapshotService.ChangeReason.SITE_CHANGE);
        return updated;
    }

    /** 使用乐观锁归档矿区。 */
    @Transactional
    public void archiveSite(UUID siteId, long version) {
        MineSite before = authorizer.requireSite(siteId);
        UUID operatorId = authorizer.currentUserId();
        if (mapper.archiveSite(siteId, version, operatorId) == 0) {
            throw new MineException(SITE_CODE_CONFLICT, Map.of("siteId", siteId));
        }
        MineSiteResponse archived = siteById(siteId);
        audit("ARCHIVE_SITE", "mine:site:edit", "MINE_SITE", siteId,
                siteSummary(MineScopeQueryService.toResponse(before)), siteSummary(archived));
        locationSnapshotService.captureSite(
                archived.organizationId(), archived.id(),
                LocationSnapshotService.ChangeReason.SITE_CHANGE);
    }

    /** 幂等授予组织级或矿区级数据范围。 */
    @Transactional
    public void grantScope(UUID userId, UUID organizationId, UUID siteId) {
        authorizer.requireOrganizationWide(organizationId);
        if (!mapper.isEnabledUser(userId)) {
            throw new MineException(TARGET_USER_DISABLED, Map.of("userId", userId));
        }
        if (siteId != null) {
            MineSite site = mapper.findSiteById(siteId)
                    .filter(item -> "ACTIVE".equals(item.status()))
                    .orElseThrow(() -> new MineException(SITE_NOT_FOUND, Map.of()));
            if (!organizationId.equals(site.organizationId())) {
                throw new MineException(SITE_ORGANIZATION_MISMATCH, Map.of());
            }
        }
        UUID operatorId = authorizer.currentUserId();
        UUID grantId = idGenerator.next();
        try {
            mapper.insertScopeGrant(grantId, userId, organizationId, siteId, operatorId);
        } catch (DuplicateKeyException exception) {
            return;
        }
        audit("GRANT_SCOPE", "mine:scope:assign", "MINE_SCOPE_GRANT", grantId,
                Map.of(), scopeSummary(userId, organizationId, siteId));
    }

    /** 幂等撤销一条精确数据范围授权。 */
    @Transactional
    public void revokeScope(UUID userId, UUID organizationId, UUID siteId) {
        authorizer.requireOrganizationWide(organizationId);
        if (mapper.deleteScopeGrant(userId, organizationId, siteId) == 0) {
            return;
        }
        audit("REVOKE_SCOPE", "mine:scope:assign", "MINE_ORGANIZATION", organizationId,
                scopeSummary(userId, organizationId, siteId), Map.of());
    }

    private MineOrganizationResponse organizationById(UUID organizationId) {
        return mapper.findOrganizationById(organizationId)
                .map(MineScopeQueryService::toResponse)
                .orElseThrow(() -> new MineException(ORGANIZATION_NOT_FOUND, Map.of()));
    }

    private MineSiteResponse siteById(UUID siteId) {
        return mapper.findSiteById(siteId)
                .map(MineScopeQueryService::toResponse)
                .orElseThrow(() -> new MineException(SITE_NOT_FOUND, Map.of()));
    }

    private String normalizeCode(String value) {
        return requiredText(value).toLowerCase(Locale.ROOT);
    }

    private String requiredText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("字段不能为空");
        }
        return value.strip();
    }

    private String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void audit(
            String action,
            String permission,
            String resourceType,
            UUID resourceId,
            Map<String, ?> before,
            Map<String, ?> after
    ) {
        auditService.recordSuccess(
                "MINE_MANAGEMENT", action, permission, resourceType, resourceId, before, after);
    }

    private Map<String, ?> organizationSummary(MineOrganizationResponse organization) {
        return Map.of(
                "organizationCode", organization.organizationCode(),
                "name", organization.name(),
                "status", organization.status(),
                "version", organization.version());
    }

    private Map<String, ?> siteSummary(MineSiteResponse site) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("organizationId", site.organizationId());
        summary.put("siteCode", site.siteCode());
        summary.put("name", site.name());
        summary.put("timezone", site.timezone());
        summary.put("status", site.status());
        summary.put("version", site.version());
        return summary;
    }

    private Map<String, ?> scopeSummary(UUID userId, UUID organizationId, UUID siteId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("userId", userId);
        summary.put("organizationId", organizationId);
        summary.put("siteId", siteId);
        return summary;
    }
}
