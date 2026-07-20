package com.bosco.mine.server.modules.mine.operation;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.operation.dto.OperationCandidateResolutionRequest;
import com.bosco.mine.server.modules.mine.operation.dto.OperationCandidateResponse;
import com.bosco.mine.server.modules.mine.operation.dto.OperationCorrectionRequest;
import com.bosco.mine.server.modules.mine.operation.dto.OperationRecordResponse;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 在单事务内处置运营候选、补录不可变执行事实并追加人工修正。
 */
@Service
public class OperationCorrectionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final Set<String> CANDIDATE_ACTIONS = Set.of(
            "ASSOCIATE", "IGNORE", "CONVERT_MANUAL");
    private static final Set<String> CORRECTION_ACTIONS = Set.of(
            "AMEND", "VOID", "RESTORE");
    private static final Set<String> RECORD_STATUSES = Set.of("COMPLETED", "ABORTED");
    private static final Set<String> TONS_SOURCES = Set.of(
            "SCALE", "VEHICLE", "MANUAL", "ESTIMATED");
    private static final Set<String> MANUAL_TARGET_STATUSES = Set.of(
            "RELEASED", "RUNNING", "PAUSED", "COMPLETED", "ABORTED");
    private static final ErrorCode VERSION_CONFLICT = new ErrorCode(
            "MINE-OPERATION-409-002", "运营有效投影版本已变化，请刷新后重试");
    private static final ErrorCode CANDIDATE_CONFLICT = new ErrorCode(
            "MINE-OPERATION-409-003", "运营候选已被其他请求处置");
    private static final ErrorCode CANDIDATE_NOT_FOUND = new ErrorCode(
            "MINE-OPERATION-404-001", "运营候选不存在或不属于当前矿区");
    private static final ErrorCode RECORD_NOT_FOUND = new ErrorCode(
            "MINE-OPERATION-404-002", "运营执行记录不存在或不属于当前矿区");
    private static final ErrorCode INVALID = new ErrorCode(
            "MINE-OPERATION-422-002", "运营人工处置参数不合法");

    private final JdbcTemplate jdbcTemplate;
    private final MineScopeAuthorizer authorizer;
    private final OperationQueryService queryService;
    private final OperationActionIdempotencyService idempotencyService;
    private final OperationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建运营人工处置服务。
     *
     * @param jdbcTemplate 数据库访问模板
     * @param authorizer 矿区作用域授权器
     * @param queryService 运营查询服务
     * @param idempotencyService 运营动作幂等服务
     * @param auditService 操作审计服务
     * @param objectMapper JSON 映射器
     */
    public OperationCorrectionService(
            JdbcTemplate jdbcTemplate,
            MineScopeAuthorizer authorizer,
            OperationQueryService queryService,
            OperationActionIdempotencyService idempotencyService,
            OperationAuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "数据库访问模板不能为空");
        this.authorizer = Objects.requireNonNull(authorizer, "矿区作用域授权器不能为空");
        this.queryService = Objects.requireNonNull(queryService, "运营查询服务不能为空");
        this.idempotencyService = Objects.requireNonNull(
                idempotencyService, "运营动作幂等服务不能为空");
        this.auditService = Objects.requireNonNull(auditService, "操作审计服务不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 映射器不能为空");
    }

    /**
     * 人工关联、忽略或将候选转为补录执行记录。
     *
     * @param siteId 矿区标识
     * @param candidateId 候选标识
     * @param idempotencyKey 幂等键
     * @param request 候选处置请求
     * @return 候选处置结果
     */
    public OperationCandidateResponse resolveCandidate(
            UUID siteId,
            UUID candidateId,
            String idempotencyKey,
            OperationCandidateResolutionRequest request
    ) {
        if (siteId == null || candidateId == null || request == null) {
            throw invalid();
        }
        return idempotencyService.execute(
                siteId, idempotencyKey, "RESOLVE_OPERATION_CANDIDATE",
                "OPERATION_CANDIDATE", candidateId, request,
                OperationCandidateResponse.class,
                () -> resolveCandidateOnce(siteId, candidateId, request));
    }

    /**
     * 人工补录一条运输执行事实。
     *
     * @param siteId 矿区标识
     * @param idempotencyKey 幂等键
     * @param request 补录请求
     * @return 新增执行记录
     */
    public OperationRecordResponse addRecord(
            UUID siteId,
            String idempotencyKey,
            OperationCorrectionRequest request
    ) {
        if (siteId == null || request == null) {
            throw invalid();
        }
        return idempotencyService.executeCreate(
                siteId, idempotencyKey, "ADD_OPERATION_RECORD",
                "OPERATION_RECORD", request,
                OperationRecordResponse.class,
                recordId -> addRecordOnce(siteId, recordId, request));
    }

    /**
     * 追加一条修正并原子更新执行事实的有效投影。
     *
     * @param siteId 矿区标识
     * @param recordId 执行记录标识
     * @param idempotencyKey 幂等键
     * @param request 修正请求
     * @return 修正后的执行记录
     */
    public OperationRecordResponse correctRecord(
            UUID siteId,
            UUID recordId,
            String idempotencyKey,
            OperationCorrectionRequest request
    ) {
        if (siteId == null || recordId == null || request == null) {
            throw invalid();
        }
        return idempotencyService.execute(
                siteId, idempotencyKey, "CORRECT_OPERATION_RECORD",
                "OPERATION_RECORD", recordId, request,
                OperationRecordResponse.class,
                () -> correctRecordOnce(siteId, recordId, idempotencyKey, request));
    }

    /**
     * 查询一条执行事实、有效投影和全部修正历史。
     *
     * @param siteId 矿区标识
     * @param recordId 执行记录标识
     * @return 执行记录详情
     */
    public OperationRecordResponse findRecord(UUID siteId, UUID recordId) {
        return queryService.findRecord(siteId, recordId);
    }

    private OperationCandidateResponse resolveCandidateOnce(
            UUID siteId,
            UUID candidateId,
            OperationCandidateResolutionRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        UUID operatorId = authorizer.currentUserId();
        CandidateRow candidate = lockCandidate(site, candidateId);
        String action = normalized(request.action(), CANDIDATE_ACTIONS);
        String reason = required(request.reason(), 500);
        if (request.version() == null || request.version() != candidate.version()
                || !"PENDING".equals(candidate.status())) {
            throw candidateConflict(candidateId);
        }

        UUID workOrderId = candidate.workOrderId();
        UUID assignmentId = candidate.assignmentId();
        UUID resolvedRecordId = null;
        String status;
        if ("IGNORE".equals(action)) {
            requireAbsent(request.workOrderId(), request.assignmentId(), request.manualRecord());
            status = "IGNORED";
        } else {
            workOrderId = required(request.workOrderId());
            assignmentId = required(request.assignmentId());
            LockedTarget target = lockTarget(site, workOrderId, assignmentId);
            validateOccurredAt(target, candidate.occurredAt());
            if ("ASSOCIATE".equals(action)) {
                if (request.manualRecord() != null) {
                    throw invalid();
                }
                status = "RESOLVED";
            } else {
                OperationCorrectionRequest manual = request.manualRecord();
                if (manual == null || !"ADD".equals(normalized(manual.action()))) {
                    throw invalid();
                }
                if (manual.workOrderId() != null && !workOrderId.equals(manual.workOrderId())) {
                    throw invalid();
                }
                if (manual.assignmentId() != null && !assignmentId.equals(manual.assignmentId())) {
                    throw invalid();
                }
                validateCandidateTrip(candidate.occurredAt(), manual);
                resolvedRecordId = insertManualRecord(
                        site, target, manual, required(manual.reason(), 1000),
                        candidate.id(), candidate.spatialEventId(), operatorId, null);
                status = "CONVERTED";
            }
        }

        int changed = jdbcTemplate.update("""
                UPDATE mine_operation_candidate
                SET work_order_id = ?, assignment_id = ?, status = ?,
                    resolution_action = ?, resolution_reason = ?,
                    resolved_record_id = ?, resolved_at = CURRENT_TIMESTAMP,
                    resolved_by = ?, updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                WHERE id = ? AND organization_id = ? AND site_id = ?
                  AND status = 'PENDING' AND version = ?
                """, workOrderId, assignmentId, status, action, reason,
                resolvedRecordId, operatorId, candidate.id(),
                site.organizationId(), site.id(), candidate.version());
        if (changed != 1) {
            throw candidateConflict(candidateId);
        }

        UUID aggregateId = resolvedRecordId == null ? candidate.id() : resolvedRecordId;
        String aggregateType = resolvedRecordId == null
                ? "OPERATION_CANDIDATE" : "OPERATION_RECORD";
        Map<String, Object> outboxPayload = new TreeMap<>();
        outboxPayload.put("action", action);
        outboxPayload.put("candidateId", candidate.id().toString());
        outboxPayload.put("siteId", site.id().toString());
        outboxPayload.put("status", status);
        outboxPayload.put("updateType", candidateUpdateType(action));
        if (resolvedRecordId != null) {
            outboxPayload.put("operationRecordId", resolvedRecordId.toString());
        }
        insertOutbox(site, aggregateType, aggregateId, outboxPayload);
        OperationCandidateResponse response = queryService.findCandidate(site, candidate.id());
        auditService.recordSuccess(
                "MINE_OPERATION", candidateAuditAction(action), "mine:operation:correct",
                "OPERATION_CANDIDATE", candidate.id(),
                Map.of("status", candidate.status(), "version", candidate.version()),
                candidateSummary(response));
        return response;
    }

    private OperationRecordResponse addRecordOnce(
            UUID siteId,
            UUID recordId,
            OperationCorrectionRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        UUID operatorId = authorizer.currentUserId();
        if (!"ADD".equals(normalized(request.action()))) {
            throw invalid();
        }
        LockedTarget target = lockTarget(
                site, required(request.workOrderId()), required(request.assignmentId()));
        insertManualRecord(
                site, target, request, required(request.reason(), 1000),
                null, null, operatorId, recordId);
        insertOutbox(site, "OPERATION_RECORD", recordId, Map.of(
                "action", "ADD",
                "operationRecordId", recordId.toString(),
                "siteId", site.id().toString(),
                "updateType", "RECORD_ADDED"));
        OperationRecordResponse response = queryService.findRecord(site, recordId);
        auditService.recordSuccess(
                "MINE_OPERATION", "ADD_OPERATION_RECORD", "mine:operation:correct",
                "OPERATION_RECORD", recordId, Map.of(), recordSummary(response));
        return response;
    }

    private OperationRecordResponse correctRecordOnce(
            UUID siteId,
            UUID recordId,
            String idempotencyKey,
            OperationCorrectionRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        UUID operatorId = authorizer.currentUserId();
        String action = normalized(request.action(), CORRECTION_ACTIONS);
        String reason = required(request.reason(), 1000);
        RecordScope scope = observeRecordScope(site, recordId);
        lockTarget(site, scope.workOrderId(), scope.assignmentId());
        EffectiveRow effective = lockEffective(site, recordId);
        if (!scope.shiftInstanceId().equals(effective.shiftInstanceId())
                || !scope.workOrderId().equals(effective.workOrderId())
                || !scope.assignmentId().equals(effective.assignmentId())) {
            throw versionConflict(recordId);
        }
        if (request.projectionVersion() == null
                || request.projectionVersion() != effective.correctionVersion()) {
            throw versionConflict(recordId);
        }

        ProjectionValues before = effective.values();
        ProjectionValues after;
        if ("AMEND".equals(action)) {
            requireAbsent(
                    request.workOrderId(), request.assignmentId(), request.recordStatus(),
                    request.startedAt(), request.loadCompletedAt(),
                    request.unloadingStartedAt(), request.completedAt());
            validateMeasurements(
                    request.distanceMeters(), request.actualTons(), request.tonsSource());
            after = new ProjectionValues(
                    before.effective(), request.distanceMeters(), request.actualTons(),
                    normalizeTonsSource(request.tonsSource()));
        } else {
            requireAbsent(
                    request.workOrderId(), request.assignmentId(), request.recordStatus(),
                    request.startedAt(), request.loadCompletedAt(),
                    request.unloadingStartedAt(), request.completedAt(),
                    request.distanceMeters(), request.actualTons(), request.tonsSource());
            after = new ProjectionValues(
                    "RESTORE".equals(action), before.distanceMeters(),
                    before.actualTons(), before.tonsSource());
        }
        int nextVersion = effective.correctionVersion() + 1;
        UUID correctionId = idGenerator.next();
        UUID correctionKey = deterministicUuid(
                "OPERATION_CORRECTION", site.id(),
                operatorId + ":" + normalizedIdempotencyKey(idempotencyKey));
        int inserted = jdbcTemplate.update("""
                INSERT INTO mine_operation_correction (
                    id, organization_id, site_id, operation_record_id,
                    correction_version, correction_type,
                    before_values, after_values, reason,
                    idempotency_key, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB),
                          ?, ?, ?)
                """, correctionId, site.organizationId(), site.id(), recordId,
                nextVersion, action, write(before.toMap()), write(after.toMap()),
                reason, correctionKey, operatorId);
        if (inserted != 1) {
            throw new IllegalStateException("运营修正事实未能追加");
        }
        int changed = jdbcTemplate.update("""
                UPDATE mine_operation_effective_record
                SET effective = ?, distance_meters = ?, actual_tons = ?,
                    tons_source = ?, correction_version = ?,
                    projected_at = CURRENT_TIMESTAMP
                WHERE operation_record_id = ?
                  AND organization_id = ? AND site_id = ?
                  AND correction_version = ?
                """, after.effective(), after.distanceMeters(), after.actualTons(),
                after.tonsSource(), nextVersion, recordId,
                site.organizationId(), site.id(), effective.correctionVersion());
        if (changed != 1) {
            throw versionConflict(recordId);
        }
        insertOutbox(site, "OPERATION_RECORD", recordId, Map.of(
                "action", action,
                "correctionVersion", nextVersion,
                "operationRecordId", recordId.toString(),
                "siteId", site.id().toString(),
                "updateType", correctionUpdateType(action)));
        OperationRecordResponse response = queryService.findRecord(site, recordId);
        auditService.recordSuccess(
                "MINE_OPERATION", "CORRECT_OPERATION_RECORD", "mine:operation:correct",
                "OPERATION_RECORD", recordId, before.toMap(), after.toMap());
        return response;
    }

    private UUID insertManualRecord(
            MineSite site,
            LockedTarget target,
            OperationCorrectionRequest request,
            String reason,
            UUID candidateId,
            UUID spatialEventId,
            UUID operatorId,
            UUID requestedRecordId
    ) {
        validateManualRequest(target, request);
        long cycleNumber = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(cycle_number), 0) + 1
                FROM mine_operation_record
                WHERE assignment_id = ?
                """, Long.class, target.assignmentId());
        UUID recordId = requestedRecordId == null ? idGenerator.next() : requestedRecordId;
        Map<String, Object> frozenSnapshot = manualSnapshot(
                target, request, reason, cycleNumber, candidateId, spatialEventId);
        int inserted = jdbcTemplate.update("""
                INSERT INTO mine_operation_record (
                    id, organization_id, site_id, shift_instance_id,
                    work_order_id, assignment_id, cycle_number,
                    work_type, record_status, vehicle_id, driver_person_id,
                    route_version_id, load_geofence_version_id,
                    unload_geofence_version_id,
                    started_at, load_completed_at, unloading_started_at, completed_at,
                    distance_meters, actual_tons, tons_source,
                    frozen_snapshot, record_source, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'HAULAGE', ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), 'MANUAL', ?)
                """, recordId, site.organizationId(), site.id(),
                target.shiftInstanceId(), target.workOrderId(), target.assignmentId(),
                cycleNumber, normalized(request.recordStatus(), RECORD_STATUSES),
                target.vehicleId(), target.driverPersonId(), target.routeVersionId(),
                target.loadGeofenceVersionId(), target.unloadGeofenceVersionId(),
                timestamp(request.startedAt()), timestamp(request.loadCompletedAt()),
                timestamp(request.unloadingStartedAt()), timestamp(request.completedAt()),
                request.distanceMeters(), request.actualTons(),
                normalizeTonsSource(request.tonsSource()), write(frozenSnapshot), operatorId);
        if (inserted != 1) {
            throw new IllegalStateException("人工运营执行事实未能保存");
        }
        int projected = jdbcTemplate.update("""
                INSERT INTO mine_operation_effective_record (
                    operation_record_id, organization_id, site_id,
                    shift_instance_id, work_order_id, assignment_id,
                    cycle_number, work_type, record_status, effective,
                    vehicle_id, driver_person_id, started_at, completed_at,
                    distance_meters, actual_tons, tons_source
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'HAULAGE', ?, TRUE,
                          ?, ?, ?, ?, ?, ?, ?)
                """, recordId, site.organizationId(), site.id(),
                target.shiftInstanceId(), target.workOrderId(), target.assignmentId(),
                cycleNumber, normalized(request.recordStatus(), RECORD_STATUSES),
                target.vehicleId(), target.driverPersonId(),
                timestamp(request.startedAt()), timestamp(request.completedAt()),
                request.distanceMeters(), request.actualTons(),
                normalizeTonsSource(request.tonsSource()));
        if (projected != 1) {
            throw new IllegalStateException("人工运营有效投影未能建立");
        }
        Map<String, Object> evidence = new TreeMap<>();
        evidence.put("reason", reason);
        evidence.put("recordSource", "MANUAL");
        if (candidateId != null) {
            evidence.put("candidateId", candidateId.toString());
        }
        if (spatialEventId != null) {
            evidence.put("spatialEventId", spatialEventId.toString());
        }
        int supported = jdbcTemplate.update("""
                INSERT INTO mine_operation_evidence (
                    id, organization_id, site_id, operation_record_id,
                    evidence_role, occurred_at, evidence_payload, created_by
                ) VALUES (?, ?, ?, ?, 'MANUAL_SUPPORT', ?, CAST(? AS JSONB), ?)
                """, idGenerator.next(), site.organizationId(), site.id(), recordId,
                timestamp(request.completedAt()), write(evidence), operatorId);
        if (supported != 1) {
            throw new IllegalStateException("人工运营支持证据未能保存");
        }
        return recordId;
    }

    private void validateManualRequest(
            LockedTarget target,
            OperationCorrectionRequest request
    ) {
        if (!"ADD".equals(normalized(request.action()))
                || !target.workOrderId().equals(required(request.workOrderId()))
                || !target.assignmentId().equals(required(request.assignmentId()))
                || request.projectionVersion() != null
                || !"OPEN".equals(target.shiftStatus())
                || !MANUAL_TARGET_STATUSES.contains(target.workOrderStatus())
                || "CANCELLED".equals(target.assignmentStatus())) {
            throw invalid();
        }
        normalized(request.recordStatus(), RECORD_STATUSES);
        Instant startedAt = required(request.startedAt());
        Instant completedAt = required(request.completedAt());
        if (completedAt.isBefore(startedAt)
                || startedAt.isBefore(target.validFrom())
                || !startedAt.isBefore(target.validTo())
                || completedAt.isAfter(target.validTo())
                || startedAt.isBefore(target.plannedStartAt())
                || !startedAt.isBefore(target.plannedEndAt())
                || completedAt.isAfter(target.plannedEndAt())) {
            throw invalid();
        }
        Instant loadedAt = request.loadCompletedAt();
        Instant unloadingAt = request.unloadingStartedAt();
        if ((loadedAt != null && loadedAt.isBefore(startedAt))
                || (loadedAt != null && loadedAt.isAfter(completedAt))
                || (unloadingAt != null && loadedAt == null)
                || (unloadingAt != null && unloadingAt.isBefore(loadedAt))
                || (unloadingAt != null && completedAt.isBefore(unloadingAt))) {
            throw invalid();
        }
        validateMeasurements(
                request.distanceMeters(), request.actualTons(), request.tonsSource());
    }

    private void validateCandidateTrip(
            Instant candidateOccurredAt,
            OperationCorrectionRequest request
    ) {
        Instant startedAt = required(request.startedAt());
        Instant completedAt = required(request.completedAt());
        if (candidateOccurredAt == null
                || candidateOccurredAt.isBefore(startedAt)
                || candidateOccurredAt.isAfter(completedAt)) {
            throw invalid();
        }
    }

    private void validateMeasurements(
            BigDecimal distanceMeters,
            BigDecimal actualTons,
            String tonsSource
    ) {
        if (!validDecimal(distanceMeters, 16, 3)
                || !validDecimal(actualTons, 14, 3)) {
            throw invalid();
        }
        String normalizedSource = normalizeTonsSource(tonsSource);
        if ((actualTons == null) != (normalizedSource == null)) {
            throw invalid();
        }
    }

    private boolean validDecimal(BigDecimal value, int precision, int scale) {
        return value == null
                || (value.signum() >= 0
                && value.scale() <= scale
                && value.precision() <= precision
                && value.precision() - value.scale() <= precision - scale);
    }

    private void validateOccurredAt(LockedTarget target, Instant occurredAt) {
        if (occurredAt == null
                || occurredAt.isBefore(target.validFrom())
                || !occurredAt.isBefore(target.validTo())
                || occurredAt.isBefore(target.plannedStartAt())
                || !occurredAt.isBefore(target.plannedEndAt())) {
            throw invalid();
        }
    }

    private LockedTarget lockTarget(
            MineSite site,
            UUID workOrderId,
            UUID assignmentId
    ) {
        UUID shiftInstanceId = jdbcTemplate.query("""
                SELECT shift_instance_id
                FROM mine_work_order
                WHERE id = ? AND organization_id = ? AND site_id = ?
                """, (resultSet, rowNumber) -> resultSet.getObject(
                        "shift_instance_id", UUID.class),
                workOrderId, site.organizationId(), site.id())
                .stream().findFirst().orElseThrow(this::invalid);
        String shiftStatus = jdbcTemplate.query("""
                SELECT status FROM mine_shift_instance
                WHERE id = ? AND organization_id = ? AND site_id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> resultSet.getString("status"),
                shiftInstanceId, site.organizationId(), site.id())
                .stream().findFirst().orElseThrow(this::invalid);
        LockedOrder order = jdbcTemplate.query("""
                SELECT id, shift_instance_id, status,
                       planned_start_at, planned_end_at,
                       route_version_id, load_geofence_version_id,
                       unload_geofence_version_id,
                       release_snapshot::TEXT AS release_snapshot_json
                FROM mine_work_order
                WHERE id = ? AND shift_instance_id = ?
                  AND organization_id = ? AND site_id = ?
                FOR UPDATE
                """, this::mapLockedOrder, workOrderId, shiftInstanceId,
                site.organizationId(), site.id())
                .stream().findFirst().orElseThrow(this::invalid);
        LockedAssignment assignment = jdbcTemplate.query("""
                SELECT id, work_order_id, vehicle_id, driver_person_id,
                       valid_from, valid_to, status
                FROM mine_work_order_assignment
                WHERE id = ? AND work_order_id = ?
                  AND organization_id = ? AND site_id = ?
                FOR UPDATE
                """, this::mapLockedAssignment, assignmentId, workOrderId,
                site.organizationId(), site.id())
                .stream().findFirst().orElseThrow(this::invalid);
        return new LockedTarget(
                shiftInstanceId, order.id(), assignment.id(), assignment.vehicleId(),
                assignment.driverPersonId(), order.routeVersionId(),
                order.loadGeofenceVersionId(), order.unloadGeofenceVersionId(),
                order.plannedStartAt(), order.plannedEndAt(),
                assignment.validFrom(), assignment.validTo(), order.status(),
                assignment.status(), shiftStatus, order.releaseSnapshotJson());
    }

    private CandidateRow lockCandidate(MineSite site, UUID candidateId) {
        return jdbcTemplate.query("""
                SELECT id, spatial_event_id, work_order_id, assignment_id,
                       occurred_at, status, version
                FROM mine_operation_candidate
                WHERE id = ? AND organization_id = ? AND site_id = ?
                FOR UPDATE
                """, this::mapCandidateRow, candidateId,
                site.organizationId(), site.id())
                .stream().findFirst()
                .orElseThrow(() -> new MineException(
                        CANDIDATE_NOT_FOUND, Map.of("candidateId", candidateId)));
    }

    private RecordScope observeRecordScope(MineSite site, UUID recordId) {
        return jdbcTemplate.query("""
                SELECT shift_instance_id, work_order_id, assignment_id
                FROM mine_operation_record
                WHERE id = ? AND organization_id = ? AND site_id = ?
                """, (resultSet, rowNumber) -> new RecordScope(
                        resultSet.getObject("shift_instance_id", UUID.class),
                        resultSet.getObject("work_order_id", UUID.class),
                        resultSet.getObject("assignment_id", UUID.class)),
                recordId, site.organizationId(), site.id())
                .stream().findFirst()
                .orElseThrow(() -> new MineException(
                        RECORD_NOT_FOUND, Map.of("recordId", recordId)));
    }

    private EffectiveRow lockEffective(MineSite site, UUID recordId) {
        return jdbcTemplate.query("""
                SELECT shift_instance_id, work_order_id, assignment_id,
                       effective, distance_meters, actual_tons, tons_source,
                       correction_version
                FROM mine_operation_effective_record
                WHERE operation_record_id = ?
                  AND organization_id = ? AND site_id = ?
                FOR UPDATE
                """, this::mapEffectiveRow, recordId,
                site.organizationId(), site.id())
                .stream().findFirst()
                .orElseThrow(() -> new MineException(
                        RECORD_NOT_FOUND, Map.of("recordId", recordId)));
    }

    private Map<String, Object> manualSnapshot(
            LockedTarget target,
            OperationCorrectionRequest request,
            String reason,
            long cycleNumber,
            UUID candidateId,
            UUID spatialEventId
    ) {
        Map<String, Object> snapshot = new TreeMap<>(readMap(target.releaseSnapshotJson()));
        snapshot.put("assignmentId", target.assignmentId().toString());
        snapshot.put("completedAt", request.completedAt().toString());
        snapshot.put("cycleNumber", cycleNumber);
        snapshot.put("manualReason", reason);
        snapshot.put("recordSource", "MANUAL");
        snapshot.put("startedAt", request.startedAt().toString());
        snapshot.put("vehicleId", target.vehicleId().toString());
        if (target.driverPersonId() != null) {
            snapshot.put("driverPersonId", target.driverPersonId().toString());
        }
        if (candidateId != null) {
            snapshot.put("candidateId", candidateId.toString());
        }
        if (spatialEventId != null) {
            snapshot.put("spatialEventId", spatialEventId.toString());
        }
        return snapshot;
    }

    private void insertOutbox(
            MineSite site,
            String aggregateType,
            UUID aggregateId,
            Map<String, ?> payload
    ) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO mine_operation_outbox (
                    id, organization_id, site_id,
                    aggregate_type, aggregate_id,
                    event_type, event_version, payload
                ) VALUES (?, ?, ?, ?, ?, 'realtime.operation.updated', 1,
                          CAST(? AS JSONB))
                """, idGenerator.next(), site.organizationId(), site.id(),
                aggregateType, aggregateId, write(payload));
        if (inserted != 1) {
            throw new IllegalStateException("人工运营变更 Outbox 未能保存");
        }
    }

    private Map<String, Object> candidateSummary(OperationCandidateResponse response) {
        Map<String, Object> summary = new TreeMap<>();
        summary.put("status", response.status());
        summary.put("version", response.version());
        if (response.workOrderId() != null) {
            summary.put("workOrderId", response.workOrderId().toString());
        }
        if (response.assignmentId() != null) {
            summary.put("assignmentId", response.assignmentId().toString());
        }
        if (response.resolvedRecordId() != null) {
            summary.put("resolvedRecordId", response.resolvedRecordId().toString());
        }
        return summary;
    }

    private Map<String, Object> recordSummary(OperationRecordResponse response) {
        return Map.of(
                "assignmentId", response.assignmentId().toString(),
                "correctionVersion", response.correctionVersion(),
                "cycleNumber", response.cycleNumber(),
                "effective", response.effective(),
                "recordStatus", response.recordStatus());
    }

    private String candidateAuditAction(String action) {
        return switch (action) {
            case "ASSOCIATE" -> "ASSOCIATE_OPERATION_CANDIDATE";
            case "IGNORE" -> "IGNORE_OPERATION_CANDIDATE";
            case "CONVERT_MANUAL" -> "CONVERT_OPERATION_CANDIDATE";
            default -> throw invalid();
        };
    }

    private String candidateUpdateType(String action) {
        return switch (action) {
            case "ASSOCIATE" -> "CANDIDATE_RESOLVED";
            case "IGNORE" -> "CANDIDATE_IGNORED";
            case "CONVERT_MANUAL" -> "RECORD_ADDED";
            default -> throw invalid();
        };
    }

    private String correctionUpdateType(String action) {
        return switch (action) {
            case "AMEND" -> "RECORD_CORRECTED";
            case "VOID" -> "RECORD_VOIDED";
            case "RESTORE" -> "RECORD_RESTORED";
            default -> throw invalid();
        };
    }

    private LockedOrder mapLockedOrder(ResultSet resultSet, int rowNumber) throws SQLException {
        return new LockedOrder(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("shift_instance_id", UUID.class),
                resultSet.getString("status"),
                instant(resultSet, "planned_start_at"),
                instant(resultSet, "planned_end_at"),
                resultSet.getObject("route_version_id", UUID.class),
                resultSet.getObject("load_geofence_version_id", UUID.class),
                resultSet.getObject("unload_geofence_version_id", UUID.class),
                resultSet.getString("release_snapshot_json"));
    }

    private LockedAssignment mapLockedAssignment(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new LockedAssignment(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("work_order_id", UUID.class),
                resultSet.getObject("vehicle_id", UUID.class),
                resultSet.getObject("driver_person_id", UUID.class),
                instant(resultSet, "valid_from"),
                instant(resultSet, "valid_to"),
                resultSet.getString("status"));
    }

    private CandidateRow mapCandidateRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new CandidateRow(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("spatial_event_id", UUID.class),
                resultSet.getObject("work_order_id", UUID.class),
                resultSet.getObject("assignment_id", UUID.class),
                instant(resultSet, "occurred_at"),
                resultSet.getString("status"),
                resultSet.getLong("version"));
    }

    private EffectiveRow mapEffectiveRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new EffectiveRow(
                resultSet.getObject("shift_instance_id", UUID.class),
                resultSet.getObject("work_order_id", UUID.class),
                resultSet.getObject("assignment_id", UUID.class),
                resultSet.getBoolean("effective"),
                resultSet.getBigDecimal("distance_meters"),
                resultSet.getBigDecimal("actual_tons"),
                resultSet.getString("tons_source"),
                resultSet.getInt("correction_version"));
    }

    private Map<String, Object> readMap(String value) {
        if (value == null) {
            throw invalid();
        }
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JacksonException exception) {
            MineException invalid = invalid();
            invalid.addSuppressed(exception);
            throw invalid;
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("运营人工处置 JSON 快照无法生成", exception);
        }
    }

    private String normalized(String value) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        return value.strip().toUpperCase(Locale.ROOT);
    }

    private String normalized(String value, Set<String> allowed) {
        String normalized = normalized(value);
        if (!allowed.contains(normalized)) {
            throw invalid();
        }
        return normalized;
    }

    private String normalizeTonsSource(String value) {
        if (value == null) {
            return null;
        }
        String normalized = normalized(value);
        if (!TONS_SOURCES.contains(normalized)) {
            throw invalid();
        }
        return normalized;
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

    private <T> T required(T value) {
        if (value == null) {
            throw invalid();
        }
        return value;
    }

    private void requireAbsent(Object... values) {
        for (Object value : values) {
            if (value != null) {
                throw invalid();
            }
        }
    }

    private UUID deterministicUuid(String namespace, UUID siteId, String value) {
        String source = namespace + ":" + siteId + ":" + String.valueOf(value);
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    private String normalizedIdempotencyKey(String value) {
        return value == null ? "" : value.strip();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private MineException invalid() {
        return new MineException(INVALID, Map.of());
    }

    private MineException versionConflict(UUID recordId) {
        return new MineException(VERSION_CONFLICT, Map.of("recordId", recordId));
    }

    private MineException candidateConflict(UUID candidateId) {
        return new MineException(CANDIDATE_CONFLICT, Map.of("candidateId", candidateId));
    }

    /** 已锁定作业单的稳定发布上下文。 */
    private record LockedOrder(
            UUID id,
            UUID shiftInstanceId,
            String status,
            Instant plannedStartAt,
            Instant plannedEndAt,
            UUID routeVersionId,
            UUID loadGeofenceVersionId,
            UUID unloadGeofenceVersionId,
            String releaseSnapshotJson
    ) {
    }

    /** 已锁定的调度分配上下文。 */
    private record LockedAssignment(
            UUID id,
            UUID workOrderId,
            UUID vehicleId,
            UUID driverPersonId,
            Instant validFrom,
            Instant validTo,
            String status
    ) {
    }

    /** 按班次、作业单、调度分配顺序锁定后的补录目标。 */
    private record LockedTarget(
            UUID shiftInstanceId,
            UUID workOrderId,
            UUID assignmentId,
            UUID vehicleId,
            UUID driverPersonId,
            UUID routeVersionId,
            UUID loadGeofenceVersionId,
            UUID unloadGeofenceVersionId,
            Instant plannedStartAt,
            Instant plannedEndAt,
            Instant validFrom,
            Instant validTo,
            String workOrderStatus,
            String assignmentStatus,
            String shiftStatus,
            String releaseSnapshotJson
    ) {
    }

    /** 已锁定候选的并发控制字段。 */
    private record CandidateRow(
            UUID id,
            UUID spatialEventId,
            UUID workOrderId,
            UUID assignmentId,
            Instant occurredAt,
            String status,
            long version
    ) {
    }

    /** 执行事实不可变父级标识。 */
    private record RecordScope(
            UUID shiftInstanceId,
            UUID workOrderId,
            UUID assignmentId
    ) {
    }

    /** 已锁定的当前有效投影。 */
    private record EffectiveRow(
            UUID shiftInstanceId,
            UUID workOrderId,
            UUID assignmentId,
            boolean effective,
            BigDecimal distanceMeters,
            BigDecimal actualTons,
            String tonsSource,
            int correctionVersion
    ) {
        /** 转换为修正前完整度量快照。 */
        ProjectionValues values() {
            return new ProjectionValues(
                    effective, distanceMeters, actualTons, tonsSource);
        }
    }

    /** 数据库修正约束要求的完整有效度量快照。 */
    private record ProjectionValues(
            boolean effective,
            BigDecimal distanceMeters,
            BigDecimal actualTons,
            String tonsSource
    ) {
        /** 生成保留空值键的完整 JSON 对象。 */
        Map<String, Object> toMap() {
            Map<String, Object> values = new TreeMap<>();
            values.put("actualTons", actualTons);
            values.put("distanceMeters", distanceMeters);
            values.put("effective", effective);
            values.put("tonsSource", tonsSource);
            return values;
        }
    }
}
