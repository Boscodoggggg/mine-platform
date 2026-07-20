package com.bosco.mine.server.modules.mine.operation;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.operation.domain.WorkOrderLifecycle;
import com.bosco.mine.server.modules.mine.operation.dto.WorkOrderActionRequest;
import com.bosco.mine.server.modules.mine.operation.dto.WorkOrderAssignmentRequest;
import com.bosco.mine.server.modules.mine.operation.dto.WorkOrderRequest;
import com.bosco.mine.server.modules.mine.operation.dto.WorkOrderResponse;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 管理运输作业单草稿、调度分配、发布冻结和显式生命周期动作。
 */
@Service
public class WorkOrderCommandService {

    private static final Pattern ORDER_NUMBER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{1,63}");
    private static final ErrorCode NOT_FOUND =
            new ErrorCode("MINE-WORK-404-001", "运输作业单不存在");
    private static final ErrorCode ORDER_NUMBER_CONFLICT =
            new ErrorCode("MINE-WORK-409-001", "矿区内作业单号已存在");
    private static final ErrorCode VERSION_CONFLICT =
            new ErrorCode("MINE-WORK-409-002", "作业单已发布或被其他调度员修改");
    private static final ErrorCode ASSIGNMENT_CONFLICT =
            new ErrorCode("MINE-WORK-409-003", "车辆或司机在分配时段已被占用");
    private static final ErrorCode LIFECYCLE_CONFLICT =
            new ErrorCode("MINE-WORK-409-004", "当前作业单状态不允许执行该动作");
    private static final ErrorCode INVALID =
            new ErrorCode("MINE-WORK-422-001", "运输作业单计划或动作参数不合法");
    private static final ErrorCode SPATIAL_INVALID =
            new ErrorCode("MINE-WORK-422-002", "路线和装卸区域必须是当前矿区有效的已发布版本");
    private static final ErrorCode VEHICLE_INVALID =
            new ErrorCode("MINE-WORK-422-003", "调度车辆必须是当前矿区启用的运输矿卡");
    private static final ErrorCode DRIVER_INVALID =
            new ErrorCode("MINE-WORK-422-004", "司机在完整分配时段内缺少矿区任职或车型资质");
    private static final ErrorCode ASSIGNMENT_REQUIRED =
            new ErrorCode("MINE-WORK-422-005", "发布运输作业单前至少需要一条活动车辆分配");

    private final OperationMapper mapper;
    private final WorkOrderQueryService queryService;
    private final MineScopeAuthorizer authorizer;
    private final OperationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建运输作业单命令服务。
     *
     * @param mapper 运营控制面数据访问接口
     * @param queryService 作业单查询服务
     * @param authorizer 矿区作用域授权器
     * @param auditService 操作审计服务
     * @param objectMapper JSON 映射器
     */
    public WorkOrderCommandService(
            OperationMapper mapper,
            WorkOrderQueryService queryService,
            MineScopeAuthorizer authorizer,
            OperationAuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.queryService = queryService;
        this.authorizer = authorizer;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建运输作业单草稿。
     *
     * @param siteId 矿区标识
     * @param request 作业单草稿请求
     * @return 新建作业单
     */
    @Transactional
    public WorkOrderResponse createWorkOrder(UUID siteId, WorkOrderRequest request) {
        MineSite site = authorizer.requireSite(siteId);
        WorkOrderRequest normalized = normalize(request);
        requirePlanningShift(site, normalized.shiftInstanceId(), normalized);
        UUID workOrderId = idGenerator.next();
        try {
            mapper.insertWorkOrder(
                    workOrderId, site.organizationId(), site.id(),
                    normalized.shiftInstanceId(), normalized.orderNumber(), normalized.title(),
                    normalized.workType(), normalized.plannedStartAt(), normalized.plannedEndAt(),
                    normalized.plannedCycles(), normalized.plannedTons(), normalized.priority(),
                    authorizer.currentUserId());
        } catch (DuplicateKeyException exception) {
            throw new MineException(
                    ORDER_NUMBER_CONFLICT, Map.of("orderNumber", normalized.orderNumber()));
        } catch (DataIntegrityViolationException exception) {
            throw invalid(exception);
        }
        WorkOrderResponse created = queryService.findWorkOrder(site.id(), workOrderId);
        audit("CREATE_WORK_ORDER", "mine:work-order:edit", workOrderId,
                Map.of(), summary(created));
        return created;
    }

    /**
     * 使用乐观锁修改运输作业单草稿。
     *
     * @param siteId 矿区标识
     * @param workOrderId 作业单标识
     * @param request 作业单草稿请求和当前版本
     * @return 修改后的作业单
     */
    @Transactional
    public WorkOrderResponse updateWorkOrder(
            UUID siteId, UUID workOrderId, WorkOrderRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        WorkOrderRequest normalized = normalize(request);
        OperationMapper.WorkOrderRow before = requireWorkOrder(site, workOrderId, false);
        if (!"DRAFT".equals(before.status())) {
            throw versionConflict(workOrderId);
        }
        requirePlanningShift(site, normalized.shiftInstanceId(), normalized);
        try {
            if (mapper.updateWorkOrder(
                    workOrderId, site.organizationId(), site.id(),
                    normalized.shiftInstanceId(), normalized.orderNumber(), normalized.title(),
                    normalized.workType(), normalized.plannedStartAt(), normalized.plannedEndAt(),
                    normalized.plannedCycles(), normalized.plannedTons(), normalized.priority(),
                    normalized.version(), authorizer.currentUserId()) == 0) {
                throw versionConflict(workOrderId);
            }
        } catch (DuplicateKeyException exception) {
            throw new MineException(
                    ORDER_NUMBER_CONFLICT, Map.of("orderNumber", normalized.orderNumber()));
        } catch (DataIntegrityViolationException exception) {
            throw invalid(exception);
        }
        WorkOrderResponse updated = queryService.findWorkOrder(site.id(), workOrderId);
        audit("UPDATE_WORK_ORDER", "mine:work-order:edit", workOrderId,
                summary(queryService.toResponse(site, before)), summary(updated));
        return updated;
    }

    /**
     * 向作业单追加车辆和可选司机调度分配事实。
     *
     * @param siteId 矿区标识
     * @param workOrderId 作业单标识
     * @param request 分配请求
     * @return 包含全部分配的作业单
     */
    @Transactional
    public WorkOrderResponse assignWorkOrder(
            UUID siteId, UUID workOrderId, WorkOrderAssignmentRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        OperationMapper.WorkOrderRow order = requireWorkOrder(site, workOrderId, true);
        if (!List.of("DRAFT", "RELEASED", "RUNNING", "PAUSED").contains(order.status())) {
            throw lifecycleConflict(workOrderId, "ASSIGN");
        }
        WorkOrderAssignmentRequest normalized = normalize(request, order);
        OperationMapper.DispatchVehicleRow vehicle = mapper.lockDispatchVehicle(
                        site.organizationId(), site.id(), normalized.vehicleId())
                .filter(item -> "ACTIVE".equals(item.status()))
                .filter(item -> "HAUL_TRUCK".equals(item.vehicleType()))
                .orElseThrow(() -> new MineException(
                        VEHICLE_INVALID, Map.of("vehicleId", normalized.vehicleId())));
        if (normalized.driverPersonId() != null) {
            ZoneId zoneId;
            try {
                zoneId = ZoneId.of(site.timezone());
            } catch (RuntimeException exception) {
                throw invalid(exception);
            }
            var fromDate = normalized.validFrom().atZone(zoneId).toLocalDate();
            var toDate = normalized.validTo().minusNanos(1).atZone(zoneId).toLocalDate();
            if (!mapper.isDriverEligible(
                    site.organizationId(), site.id(), normalized.driverPersonId(),
                    vehicle.vehicleType(), normalized.validFrom(), normalized.validTo(),
                    fromDate, toDate)) {
                throw new MineException(
                        DRIVER_INVALID, Map.of("driverPersonId", normalized.driverPersonId()));
            }
        }
        UUID assignmentId = idGenerator.next();
        try {
            mapper.insertWorkOrderAssignment(
                    assignmentId, site.organizationId(), site.id(), workOrderId,
                    normalized.vehicleId(), normalized.driverPersonId(), normalized.validFrom(),
                    normalized.validTo(), normalized.assignmentReason(), authorizer.currentUserId());
        } catch (DataIntegrityViolationException exception) {
            throw assignmentConflict(workOrderId, exception);
        }
        WorkOrderResponse assigned = queryService.findWorkOrder(site.id(), workOrderId);
        audit("ASSIGN_WORK_ORDER", "mine:work-order:dispatch", workOrderId,
                Map.of("assignmentCount", assigned.assignments().size() - 1),
                Map.of("assignmentCount", assigned.assignments().size(),
                        "assignmentId", assignmentId.toString()));
        return assigned;
    }

    /**
     * 发布并冻结运输作业单空间版本、计划和调度显示快照。
     *
     * @param siteId 矿区标识
     * @param workOrderId 作业单标识
     * @param request 发布动作请求
     * @return 已发布作业单
     */
    @Transactional
    public WorkOrderResponse releaseWorkOrder(
            UUID siteId, UUID workOrderId, WorkOrderActionRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        WorkOrderActionRequest requiredRequest = requireAction(request);
        OperationMapper.WorkOrderRow before = requireWorkOrder(site, workOrderId, true);
        if ("RELEASED".equals(before.status())) {
            if (Objects.equals(before.routeVersionId(), requiredRequest.routeVersionId())
                    && Objects.equals(
                    before.loadGeofenceVersionId(), requiredRequest.loadGeofenceVersionId())
                    && Objects.equals(
                    before.unloadGeofenceVersionId(), requiredRequest.unloadGeofenceVersionId())) {
                return queryService.toResponse(site, before);
            }
            throw lifecycleConflict(workOrderId, "RELEASE");
        }
        requireActionVersion(before, requiredRequest);
        requireTransition(before, WorkOrderLifecycle.Action.RELEASE);
        if (requiredRequest.routeVersionId() == null
                || requiredRequest.loadGeofenceVersionId() == null
                || requiredRequest.unloadGeofenceVersionId() == null
                || requiredRequest.loadGeofenceVersionId()
                .equals(requiredRequest.unloadGeofenceVersionId())) {
            throw spatialInvalid(workOrderId);
        }
        List<OperationMapper.WorkOrderAssignmentRow> assignments =
                mapper.findWorkOrderAssignments(site.organizationId(), site.id(), workOrderId)
                        .stream()
                        .filter(item -> "ACTIVE".equals(item.status()))
                        .toList();
        if (assignments.isEmpty()) {
            throw new MineException(ASSIGNMENT_REQUIRED, Map.of("workOrderId", workOrderId));
        }
        OperationMapper.SpatialReleaseRow route = requireSpatial(
                mapper.findReleaseRoute(
                        site.organizationId(), site.id(), requiredRequest.routeVersionId()),
                before, null);
        OperationMapper.SpatialReleaseRow load = requireSpatial(
                mapper.findReleaseGeofence(
                        site.organizationId(), site.id(), requiredRequest.loadGeofenceVersionId()),
                before, "LOAD");
        OperationMapper.SpatialReleaseRow unload = requireSpatial(
                mapper.findReleaseGeofence(
                        site.organizationId(), site.id(), requiredRequest.unloadGeofenceVersionId()),
                before, "UNLOAD");
        Map<String, Object> snapshot = releaseSnapshot(before, route, load, unload, assignments);
        Instant actionAt = Instant.now();
        try {
            if (mapper.releaseWorkOrder(
                    site.organizationId(), site.id(), workOrderId, before.version(),
                    route.id(), load.id(), unload.id(), toJson(snapshot), actionAt,
                    authorizer.currentUserId()) == 0) {
                throw versionConflict(workOrderId);
            }
        } catch (DataIntegrityViolationException exception) {
            MineException invalid = spatialInvalid(workOrderId);
            invalid.addSuppressed(exception);
            throw invalid;
        }
        WorkOrderResponse released = queryService.findWorkOrder(site.id(), workOrderId);
        audit("RELEASE_WORK_ORDER", "mine:work-order:dispatch", workOrderId,
                summary(queryService.toResponse(site, before)), summary(released));
        return released;
    }

    /** 启动已发布作业单；同一已完成动作可安全重放。 */
    @Transactional
    public WorkOrderResponse startWorkOrder(
            UUID siteId, UUID workOrderId, WorkOrderActionRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        WorkOrderActionRequest action = requireAction(request);
        OperationMapper.WorkOrderRow before = requireWorkOrder(site, workOrderId, true);
        if ("RUNNING".equals(before.status()) && before.pausedAt() == null) {
            return queryService.toResponse(site, before);
        }
        requireActionVersion(before, action);
        requireTransition(before, WorkOrderLifecycle.Action.START);
        OperationMapper.ShiftInstanceRow shift = mapper.findShiftInstance(
                        site.organizationId(), site.id(), before.shiftInstanceId())
                .orElseThrow(() -> lifecycleConflict(workOrderId, "START"));
        if (!"OPEN".equals(shift.status())) {
            throw lifecycleConflict(workOrderId, "START");
        }
        return applySimpleAction(site, before, "START_WORK_ORDER", "mine:work-order:dispatch",
                (time, userId) -> mapper.startWorkOrder(
                        site.organizationId(), site.id(), workOrderId,
                        before.version(), time, userId));
    }

    /** 暂停运行作业单；同一已完成动作可安全重放。 */
    @Transactional
    public WorkOrderResponse pauseWorkOrder(
            UUID siteId, UUID workOrderId, WorkOrderActionRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        WorkOrderActionRequest action = requireAction(request);
        OperationMapper.WorkOrderRow before = requireWorkOrder(site, workOrderId, true);
        if ("PAUSED".equals(before.status())) {
            return queryService.toResponse(site, before);
        }
        requireActionVersion(before, action);
        requireTransition(before, WorkOrderLifecycle.Action.PAUSE);
        return applySimpleAction(site, before, "PAUSE_WORK_ORDER", "mine:work-order:dispatch",
                (time, userId) -> mapper.pauseWorkOrder(
                        site.organizationId(), site.id(), workOrderId,
                        before.version(), time, userId));
    }

    /** 恢复暂停作业单；同一已完成动作可安全重放。 */
    @Transactional
    public WorkOrderResponse resumeWorkOrder(
            UUID siteId, UUID workOrderId, WorkOrderActionRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        WorkOrderActionRequest action = requireAction(request);
        OperationMapper.WorkOrderRow before = requireWorkOrder(site, workOrderId, true);
        if ("RUNNING".equals(before.status()) && before.pausedAt() != null) {
            return queryService.toResponse(site, before);
        }
        requireActionVersion(before, action);
        requireTransition(before, WorkOrderLifecycle.Action.RESUME);
        return applySimpleAction(site, before, "RESUME_WORK_ORDER", "mine:work-order:dispatch",
                (time, userId) -> mapper.resumeWorkOrder(
                        site.organizationId(), site.id(), workOrderId,
                        before.version(), time, userId));
    }

    /** 完成运行或暂停作业单；同一已完成动作可安全重放。 */
    @Transactional
    public WorkOrderResponse completeWorkOrder(
            UUID siteId, UUID workOrderId, WorkOrderActionRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        WorkOrderActionRequest action = requireAction(request);
        OperationMapper.WorkOrderRow before = requireWorkOrder(site, workOrderId, true);
        if ("COMPLETED".equals(before.status())) {
            return queryService.toResponse(site, before);
        }
        requireActionVersion(before, action);
        requireTransition(before, WorkOrderLifecycle.Action.COMPLETE);
        return applySimpleAction(site, before, "COMPLETE_WORK_ORDER", "mine:work-order:dispatch",
                (time, userId) -> mapper.completeWorkOrder(
                        site.organizationId(), site.id(), workOrderId,
                        before.version(), time, userId));
    }

    /** 取消草稿或已发布作业单，并保留终态原因。 */
    @Transactional
    public WorkOrderResponse cancelWorkOrder(
            UUID siteId, UUID workOrderId, WorkOrderActionRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        WorkOrderActionRequest action = requireAction(request);
        String reason = required(action.reason(), 500);
        OperationMapper.WorkOrderRow before = requireWorkOrder(site, workOrderId, true);
        if ("CANCELLED".equals(before.status())) {
            if (reason.equals(before.terminalReason())) {
                return queryService.toResponse(site, before);
            }
            throw lifecycleConflict(workOrderId, "CANCEL");
        }
        requireActionVersion(before, action);
        requireTransition(before, WorkOrderLifecycle.Action.CANCEL);
        return applySimpleAction(site, before, "CANCEL_WORK_ORDER", "mine:work-order:dispatch",
                (time, userId) -> mapper.cancelWorkOrder(
                        site.organizationId(), site.id(), workOrderId,
                        before.version(), time, reason, userId));
    }

    /** 异常终止运行或暂停作业单，并保留终态原因。 */
    @Transactional
    public WorkOrderResponse abortWorkOrder(
            UUID siteId, UUID workOrderId, WorkOrderActionRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        WorkOrderActionRequest action = requireAction(request);
        String reason = required(action.reason(), 500);
        OperationMapper.WorkOrderRow before = requireWorkOrder(site, workOrderId, true);
        if ("ABORTED".equals(before.status())) {
            if (reason.equals(before.terminalReason())) {
                return queryService.toResponse(site, before);
            }
            throw lifecycleConflict(workOrderId, "ABORT");
        }
        requireActionVersion(before, action);
        requireTransition(before, WorkOrderLifecycle.Action.ABORT);
        return applySimpleAction(site, before, "ABORT_WORK_ORDER", "mine:work-order:dispatch",
                (time, userId) -> mapper.abortWorkOrder(
                        site.organizationId(), site.id(), workOrderId,
                        before.version(), time, reason, userId));
    }

    private WorkOrderResponse applySimpleAction(
            MineSite site,
            OperationMapper.WorkOrderRow before,
            String auditAction,
            String permission,
            ActionUpdate update
    ) {
        if (update.apply(Instant.now(), authorizer.currentUserId()) == 0) {
            throw lifecycleConflict(before.id(), auditAction);
        }
        WorkOrderResponse result = queryService.findWorkOrder(site.id(), before.id());
        audit(auditAction, permission, before.id(),
                summary(queryService.toResponse(site, before)), summary(result));
        return result;
    }

    private WorkOrderRequest normalize(WorkOrderRequest request) {
        if (request == null || request.shiftInstanceId() == null
                || request.plannedStartAt() == null || request.plannedEndAt() == null
                || !request.plannedEndAt().isAfter(request.plannedStartAt())) {
            throw invalid();
        }
        String number = required(request.orderNumber(), 64);
        String title = required(request.title(), 128);
        String workType = required(request.workType(), 24).toUpperCase(Locale.ROOT);
        if (!ORDER_NUMBER.matcher(number).matches() || !"HAULAGE".equals(workType)
                || (request.plannedCycles() != null
                && (request.plannedCycles() < 1 || request.plannedCycles() > 1_000_000))
                || (request.plannedTons() != null
                && request.plannedTons().compareTo(BigDecimal.ZERO) <= 0)
                || request.priority() < 1 || request.priority() > 100) {
            throw invalid();
        }
        return new WorkOrderRequest(
                request.shiftInstanceId(), number, title, workType,
                request.plannedStartAt(), request.plannedEndAt(), request.plannedCycles(),
                request.plannedTons(), request.priority(), request.version());
    }

    private WorkOrderAssignmentRequest normalize(
            WorkOrderAssignmentRequest request, OperationMapper.WorkOrderRow order
    ) {
        if (request == null || request.vehicleId() == null
                || request.validFrom() == null || request.validTo() == null
                || !request.validTo().isAfter(request.validFrom())
                || request.validFrom().isBefore(order.plannedStartAt())
                || request.validTo().isAfter(order.plannedEndAt())) {
            throw invalid();
        }
        return new WorkOrderAssignmentRequest(
                request.vehicleId(), request.driverPersonId(), request.validFrom(), request.validTo(),
                required(request.assignmentReason(), 500));
    }

    private void requirePlanningShift(
            MineSite site, UUID shiftInstanceId, WorkOrderRequest request
    ) {
        OperationMapper.ShiftInstanceRow shift = mapper.findShiftInstance(
                        site.organizationId(), site.id(), shiftInstanceId)
                .orElseThrow(this::invalid);
        if (!List.of("PLANNED", "OPEN").contains(shift.status())
                || request.plannedStartAt().isBefore(shift.plannedStartAt())
                || request.plannedEndAt().isAfter(shift.plannedEndAt())) {
            throw invalid();
        }
    }

    private OperationMapper.SpatialReleaseRow requireSpatial(
            java.util.Optional<OperationMapper.SpatialReleaseRow> candidate,
            OperationMapper.WorkOrderRow order,
            String expectedGeofenceType
    ) {
        OperationMapper.SpatialReleaseRow value = candidate
                .orElseThrow(() -> spatialInvalid(order.id()));
        boolean valid = "PUBLISHED".equals(value.status())
                && "ACTIVE".equals(value.parentStatus())
                && !value.validFrom().isAfter(order.plannedStartAt())
                && (value.validTo() == null || !value.validTo().isBefore(order.plannedEndAt()))
                && value.contentHash() != null
                && value.contentHash().matches("sha256:[0-9a-f]{64}")
                && value.vehicleCompatible()
                && (expectedGeofenceType == null
                || expectedGeofenceType.equals(value.geofenceType()));
        if (!valid) {
            throw spatialInvalid(order.id());
        }
        return value;
    }

    private Map<String, Object> releaseSnapshot(
            OperationMapper.WorkOrderRow order,
            OperationMapper.SpatialReleaseRow route,
            OperationMapper.SpatialReleaseRow load,
            OperationMapper.SpatialReleaseRow unload,
            List<OperationMapper.WorkOrderAssignmentRow> assignments
    ) {
        Map<String, Object> snapshot = new TreeMap<>();
        snapshot.put("loadGeofenceVersionId", load.id().toString());
        snapshot.put("loadZoneCode", load.objectCode());
        snapshot.put("loadZoneHash", load.contentHash());
        snapshot.put("loadZoneName", load.name());
        snapshot.put("orderNumber", order.orderNumber());
        snapshot.put("plannedEndAt", order.plannedEndAt().toString());
        snapshot.put("plannedStartAt", order.plannedStartAt().toString());
        if (order.plannedCycles() != null) {
            snapshot.put("plannedCycles", order.plannedCycles());
        }
        if (order.plannedTons() != null) {
            snapshot.put("plannedTons", order.plannedTons());
        }
        snapshot.put("priority", order.priority());
        snapshot.put("routeCode", route.objectCode());
        snapshot.put("routeHash", route.contentHash());
        snapshot.put("routeName", route.name());
        snapshot.put("routeVersionId", route.id().toString());
        snapshot.put("shiftInstanceId", order.shiftInstanceId().toString());
        snapshot.put("title", order.title());
        snapshot.put("unloadGeofenceVersionId", unload.id().toString());
        snapshot.put("unloadZoneCode", unload.objectCode());
        snapshot.put("unloadZoneHash", unload.contentHash());
        snapshot.put("unloadZoneName", unload.name());
        snapshot.put("workType", order.workType());
        List<Map<String, Object>> assignmentSnapshots = new ArrayList<>();
        for (OperationMapper.WorkOrderAssignmentRow assignment : assignments) {
            Map<String, Object> item = new TreeMap<>();
            item.put("assignmentId", assignment.id().toString());
            item.put("validFrom", assignment.validFrom().toString());
            item.put("validTo", assignment.validTo().toString());
            item.put("vehicleCode", assignment.vehicleCode());
            item.put("vehicleId", assignment.vehicleId().toString());
            item.put("vehicleName", assignment.vehicleName());
            item.put("vehicleType", assignment.vehicleType());
            if (assignment.driverPersonId() != null) {
                item.put("driverName", assignment.driverName());
                item.put("driverPersonId", assignment.driverPersonId().toString());
            }
            assignmentSnapshots.add(Map.copyOf(item));
        }
        snapshot.put("assignments", List.copyOf(assignmentSnapshots));
        snapshot.put("contentHash", sha256(toJson(snapshot)));
        return Map.copyOf(snapshot);
    }

    private OperationMapper.WorkOrderRow requireWorkOrder(
            MineSite site, UUID workOrderId, boolean lock
    ) {
        return (lock
                ? mapper.lockWorkOrder(site.organizationId(), site.id(), workOrderId)
                : mapper.findWorkOrder(site.organizationId(), site.id(), workOrderId))
                .orElseThrow(() -> new MineException(
                        NOT_FOUND, Map.of("workOrderId", workOrderId)));
    }

    private WorkOrderActionRequest requireAction(WorkOrderActionRequest request) {
        if (request == null) {
            throw invalid();
        }
        return request;
    }

    private void requireActionVersion(
            OperationMapper.WorkOrderRow order, WorkOrderActionRequest request
    ) {
        if (request.version() != order.version()) {
            throw versionConflict(order.id());
        }
    }

    private void requireTransition(
            OperationMapper.WorkOrderRow order, WorkOrderLifecycle.Action action
    ) {
        try {
            WorkOrderLifecycle.transition(
                    WorkOrderLifecycle.Status.valueOf(order.status()), action);
        } catch (RuntimeException exception) {
            throw lifecycleConflict(order.id(), action.name());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("作业单冻结快照序列化失败", exception);
        }
    }

    private String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256 摘要算法", exception);
        }
    }

    private Map<String, Object> summary(WorkOrderResponse response) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderNumber", response.orderNumber());
        summary.put("status", response.status());
        summary.put("version", response.version());
        summary.put("assignmentCount", response.assignments().size());
        return Map.copyOf(summary);
    }

    private void audit(
            String action,
            String permission,
            UUID workOrderId,
            Map<String, ?> before,
            Map<String, ?> after
    ) {
        auditService.recordSuccess(
                "MINE_OPERATION", action, permission, "MINE_WORK_ORDER",
                workOrderId, before, after);
    }

    private String required(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw invalid();
        }
        return normalized;
    }

    private MineException versionConflict(UUID workOrderId) {
        return new MineException(VERSION_CONFLICT, Map.of("workOrderId", workOrderId));
    }

    private MineException assignmentConflict(UUID workOrderId, RuntimeException exception) {
        MineException conflict = new MineException(
                ASSIGNMENT_CONFLICT, Map.of("workOrderId", workOrderId));
        conflict.addSuppressed(exception);
        return conflict;
    }

    private MineException lifecycleConflict(UUID workOrderId, String action) {
        return new MineException(
                LIFECYCLE_CONFLICT, Map.of("workOrderId", workOrderId, "action", action));
    }

    private MineException spatialInvalid(UUID workOrderId) {
        return new MineException(SPATIAL_INVALID, Map.of("workOrderId", workOrderId));
    }

    private MineException invalid() {
        return new MineException(INVALID, Map.of());
    }

    private MineException invalid(RuntimeException exception) {
        MineException invalid = invalid();
        invalid.addSuppressed(exception);
        return invalid;
    }

    /** 执行一条带动作时刻和操作人的状态更新。 */
    @FunctionalInterface
    private interface ActionUpdate {
        /** 返回实际修改行数。 */
        int apply(Instant actionAt, UUID operatorId);
    }
}
