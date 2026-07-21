package com.bosco.mine.server.modules.mine.operation;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.operation.dto.WorkOrderResponse;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 提供经过矿区作用域校验的运输作业单和调度分配查询。
 */
@Service
public class WorkOrderQueryService {

    private static final TypeReference<Map<String, Object>> SNAPSHOT_TYPE = new TypeReference<>() { };
    private static final Set<String> STATUSES = Set.of(
            "DRAFT", "RELEASED", "RUNNING", "PAUSED",
            "COMPLETED", "CANCELLED", "ABORTED");
    private static final ErrorCode NOT_FOUND =
            new ErrorCode("MINE-WORK-404-001", "运输作业单不存在");
    private static final ErrorCode INVALID =
            new ErrorCode("MINE-WORK-422-001", "运输作业单查询参数不合法");

    private final OperationMapper mapper;
    private final MineScopeAuthorizer authorizer;
    private final ObjectMapper objectMapper;

    /**
     * 创建运输作业单查询服务。
     *
     * @param mapper 运营控制面数据访问接口
     * @param authorizer 矿区作用域授权器
     * @param objectMapper JSON 映射器
     */
    public WorkOrderQueryService(
            OperationMapper mapper,
            MineScopeAuthorizer authorizer,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询一张运输作业单和全部分配事实。
     *
     * @param siteId 矿区标识
     * @param workOrderId 作业单标识
     * @return 作业单响应
     */
    @Transactional(readOnly = true)
    public WorkOrderResponse findWorkOrder(UUID siteId, UUID workOrderId) {
        MineSite site = authorizer.requireSite(siteId);
        OperationMapper.WorkOrderRow row = mapper.findWorkOrder(
                        site.organizationId(), site.id(), workOrderId)
                .orElseThrow(() -> new MineException(
                        NOT_FOUND, Map.of("workOrderId", workOrderId)));
        return toResponse(site, row);
    }

    /**
     * 分页查询矿区运输作业单。
     *
     * @param siteId 矿区标识
     * @param shiftInstanceId 可选班次实例标识
     * @param status 可选生命周期状态
     * @param offset 从零开始的偏移量
     * @param limit 每页数量，最大一百
     * @return 作业单响应列表
     */
    @Transactional(readOnly = true)
    public List<WorkOrderResponse> findWorkOrders(
            UUID siteId,
            UUID shiftInstanceId,
            String status,
            long offset,
            int limit
    ) {
        MineSite site = authorizer.requireSite(siteId);
        if (offset < 0 || limit < 1 || limit > 100) {
            throw invalid();
        }
        String normalizedStatus = normalizeStatus(status);
        return mapper.findWorkOrders(
                        site.organizationId(), site.id(), shiftInstanceId,
                        normalizedStatus, offset, limit)
                .stream()
                .map(row -> toResponse(site, row))
                .toList();
    }

    /** 将作业单持久化投影和分配事实转换为响应。 */
    WorkOrderResponse toResponse(MineSite site, OperationMapper.WorkOrderRow row) {
        Map<String, Object> snapshot = null;
        if (row.releaseSnapshotJson() != null) {
            try {
                snapshot = objectMapper.readValue(row.releaseSnapshotJson(), SNAPSHOT_TYPE);
            } catch (JacksonException exception) {
                throw new IllegalStateException("作业单发布冻结快照无法解析", exception);
            }
        }
        List<WorkOrderResponse.Assignment> assignments = mapper.findWorkOrderAssignments(
                        site.organizationId(), site.id(), row.id())
                .stream()
                .map(WorkOrderQueryService::toResponse)
                .toList();
        return new WorkOrderResponse(
                row.id(), row.organizationId(), row.siteId(), row.shiftInstanceId(),
                row.orderNumber(), row.title(), row.workType(), row.plannedStartAt(),
                row.plannedEndAt(), row.plannedCycles(), row.plannedTons(), row.priority(),
                row.routeVersionId(), row.loadGeofenceVersionId(), row.unloadGeofenceVersionId(),
                snapshot, row.status(), row.releasedAt(), row.startedAt(), row.pausedAt(),
                row.completedAt(), row.cancelledAt(), row.abortedAt(), row.terminalReason(),
                row.createdAt(), row.updatedAt(), row.version(), assignments);
    }

    /** 将调度分配持久化投影转换为响应。 */
    static WorkOrderResponse.Assignment toResponse(OperationMapper.WorkOrderAssignmentRow row) {
        return new WorkOrderResponse.Assignment(
                row.id(), row.vehicleId(), row.vehicleCode(), row.vehicleName(),
                row.vehicleType(), row.driverPersonId(), row.driverName(), row.validFrom(),
                row.validTo(), row.status(), row.assignmentReason(), row.createdAt(), row.version());
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw invalid();
        }
        return normalized;
    }

    private MineException invalid() {
        return new MineException(INVALID, Map.of());
    }
}
