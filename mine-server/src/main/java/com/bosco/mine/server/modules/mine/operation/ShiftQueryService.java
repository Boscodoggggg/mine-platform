package com.bosco.mine.server.modules.mine.operation;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftDefinitionResponse;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftInstanceResponse;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 提供经过矿区作用域校验的班次模板和实例查询。
 */
@Service
public class ShiftQueryService {

    private static final TypeReference<Map<String, Object>> SNAPSHOT_TYPE = new TypeReference<>() { };
    private static final Set<String> DEFINITION_STATUSES = Set.of("ACTIVE", "ARCHIVED");
    private static final Set<String> INSTANCE_STATUSES = Set.of(
            "PLANNED", "OPEN", "CLOSED", "CANCELLED");
    private static final ErrorCode NOT_FOUND =
            new ErrorCode("MINE-SHIFT-404-002", "班次实例不存在");
    private static final ErrorCode DEFINITION_NOT_FOUND =
            new ErrorCode("MINE-SHIFT-404-001", "班次模板不存在");
    private static final ErrorCode INVALID =
            new ErrorCode("MINE-SHIFT-422-001", "班次查询参数不合法");

    private final OperationMapper mapper;
    private final MineScopeAuthorizer authorizer;
    private final ObjectMapper objectMapper;

    /**
     * 创建班次查询服务。
     *
     * @param mapper 运营控制面数据访问接口
     * @param authorizer 矿区作用域授权器
     * @param objectMapper JSON 映射器
     */
    public ShiftQueryService(
            OperationMapper mapper,
            MineScopeAuthorizer authorizer,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询一个矿区班次模板。
     *
     * @param siteId 矿区标识
     * @param definitionId 模板标识
     * @return 班次模板
     */
    @Transactional(readOnly = true)
    public ShiftDefinitionResponse findDefinition(UUID siteId, UUID definitionId) {
        MineSite site = authorizer.requireSite(siteId);
        return mapper.findShiftDefinition(site.organizationId(), site.id(), definitionId)
                .map(ShiftQueryService::toResponse)
                .orElseThrow(() -> new MineException(
                        DEFINITION_NOT_FOUND, Map.of("definitionId", definitionId)));
    }

    /**
     * 分页查询矿区班次模板。
     *
     * @param siteId 矿区标识
     * @param status 可选生命周期状态
     * @param offset 从零开始的偏移量
     * @param limit 每页数量，最大一百
     * @return 班次模板列表
     */
    @Transactional(readOnly = true)
    public List<ShiftDefinitionResponse> findDefinitions(
            UUID siteId, String status, long offset, int limit
    ) {
        MineSite site = authorizer.requireSite(siteId);
        validatePage(offset, limit);
        String normalizedStatus = normalizeStatus(status, DEFINITION_STATUSES);
        return mapper.findShiftDefinitions(
                        site.organizationId(), site.id(), normalizedStatus, offset, limit)
                .stream()
                .map(ShiftQueryService::toResponse)
                .toList();
    }

    /**
     * 查询一个矿区班次实例。
     *
     * @param siteId 矿区标识
     * @param shiftId 班次实例标识
     * @return 班次实例
     */
    @Transactional(readOnly = true)
    public ShiftInstanceResponse findInstance(UUID siteId, UUID shiftId) {
        MineSite site = authorizer.requireSite(siteId);
        return mapper.findShiftInstance(site.organizationId(), site.id(), shiftId)
                .map(this::toResponse)
                .orElseThrow(() -> new MineException(NOT_FOUND, Map.of("shiftId", shiftId)));
    }

    /**
     * 分页查询营业日期范围内的班次实例。
     *
     * @param siteId 矿区标识
     * @param fromDate 起始营业日期
     * @param toDate 结束营业日期
     * @param status 可选生命周期状态
     * @param offset 从零开始的偏移量
     * @param limit 每页数量，最大一百
     * @return 班次实例列表
     */
    @Transactional(readOnly = true)
    public List<ShiftInstanceResponse> findInstances(
            UUID siteId,
            LocalDate fromDate,
            LocalDate toDate,
            String status,
            long offset,
            int limit
    ) {
        MineSite site = authorizer.requireSite(siteId);
        validatePage(offset, limit);
        if (fromDate == null || toDate == null || toDate.isBefore(fromDate)
                || ChronoUnit.DAYS.between(fromDate, toDate) > 366) {
            throw invalid();
        }
        String normalizedStatus = normalizeStatus(status, INSTANCE_STATUSES);
        return mapper.findShiftInstances(
                        site.organizationId(), site.id(), fromDate, toDate,
                        normalizedStatus, offset, limit)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /** 将班次模板持久化投影转换为响应。 */
    static ShiftDefinitionResponse toResponse(OperationMapper.ShiftDefinitionRow row) {
        return new ShiftDefinitionResponse(
                row.id(), row.organizationId(), row.siteId(), row.definitionCode(), row.name(),
                row.startLocal(), row.endLocal(), row.crossesMidnight(), row.validFrom(),
                row.validTo(), row.status(), row.createdAt(), row.updatedAt(), row.archivedAt(),
                row.version());
    }

    /** 将班次实例持久化投影转换为响应。 */
    ShiftInstanceResponse toResponse(OperationMapper.ShiftInstanceRow row) {
        try {
            return new ShiftInstanceResponse(
                    row.id(), row.organizationId(), row.siteId(), row.shiftDefinitionId(),
                    row.businessDate(), row.plannedStartAt(), row.plannedEndAt(),
                    row.actualStartAt(), row.actualEndAt(), row.supervisorPersonId(),
                    objectMapper.readValue(row.definitionSnapshotJson(), SNAPSHOT_TYPE),
                    row.status(), row.openedAt(), row.closedAt(), row.cancelledAt(),
                    row.cancellationReason(), row.createdAt(), row.updatedAt(), row.version());
        } catch (JacksonException exception) {
            throw new IllegalStateException("班次冻结模板快照无法解析", exception);
        }
    }

    private void validatePage(long offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) {
            throw invalid();
        }
    }

    private String normalizeStatus(String status, Set<String> allowed) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw invalid();
        }
        return normalized;
    }

    private MineException invalid() {
        return new MineException(INVALID, Map.of());
    }
}
