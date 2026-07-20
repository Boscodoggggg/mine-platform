package com.bosco.mine.server.modules.mine.operation;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.operation.dto.OperationCandidateResponse;
import com.bosco.mine.server.modules.mine.operation.dto.OperationRecordResponse;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 按已授权矿区查询运营候选、不可变执行事实和当前有效投影。
 */
@Service
public class OperationQueryService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final Set<String> CANDIDATE_STATUSES = Set.of(
            "PENDING", "RESOLVED", "IGNORED", "CONVERTED");
    private static final ErrorCode CANDIDATE_NOT_FOUND = new ErrorCode(
            "MINE-OPERATION-404-001", "运营候选不存在或不属于当前矿区");
    private static final ErrorCode RECORD_NOT_FOUND = new ErrorCode(
            "MINE-OPERATION-404-002", "运营执行记录不存在或不属于当前矿区");
    private static final ErrorCode INVALID = new ErrorCode(
            "MINE-OPERATION-422-002", "运营人工处置参数不合法");

    private final JdbcTemplate jdbcTemplate;
    private final MineScopeAuthorizer authorizer;
    private final ObjectMapper objectMapper;

    /**
     * 创建运营查询服务。
     *
     * @param jdbcTemplate 数据库访问模板
     * @param authorizer 矿区作用域授权器
     * @param objectMapper JSON 映射器
     */
    public OperationQueryService(
            JdbcTemplate jdbcTemplate,
            MineScopeAuthorizer authorizer,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "数据库访问模板不能为空");
        this.authorizer = Objects.requireNonNull(authorizer, "矿区作用域授权器不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 映射器不能为空");
    }

    /**
     * 分页查询矿区运营候选。
     *
     * @param siteId 矿区标识
     * @param status 可选候选状态
     * @param offset 偏移量
     * @param limit 返回上限
     * @return 候选列表
     */
    @Transactional(readOnly = true)
    public List<OperationCandidateResponse> findCandidates(
            UUID siteId,
            String status,
            long offset,
            int limit
    ) {
        MineSite site = authorizer.requireSite(siteId);
        if (offset < 0 || limit < 1 || limit > 100) {
            throw invalid();
        }
        String normalizedStatus = normalizeStatus(status);
        String sql = normalizedStatus == null ? """
                SELECT id, organization_id, site_id, spatial_event_id,
                       work_order_id, assignment_id, candidate_type, reason_code,
                       occurred_at, candidate_payload::TEXT AS candidate_payload_json,
                       status, resolution_action, resolution_reason,
                       resolved_record_id, resolved_at, resolved_by,
                       created_at, updated_at, version
                FROM mine_operation_candidate
                WHERE organization_id = ? AND site_id = ?
                ORDER BY occurred_at DESC, id
                LIMIT ? OFFSET ?
                """ : """
                SELECT id, organization_id, site_id, spatial_event_id,
                       work_order_id, assignment_id, candidate_type, reason_code,
                       occurred_at, candidate_payload::TEXT AS candidate_payload_json,
                       status, resolution_action, resolution_reason,
                       resolved_record_id, resolved_at, resolved_by,
                       created_at, updated_at, version
                FROM mine_operation_candidate
                WHERE organization_id = ? AND site_id = ? AND status = ?
                ORDER BY occurred_at DESC, id
                LIMIT ? OFFSET ?
                """;
        if (normalizedStatus == null) {
            return jdbcTemplate.query(sql, this::mapCandidate,
                    site.organizationId(), site.id(), limit, offset);
        }
        return jdbcTemplate.query(sql, this::mapCandidate,
                site.organizationId(), site.id(), normalizedStatus, limit, offset);
    }

    /**
     * 查询单个运营候选。
     *
     * @param siteId 矿区标识
     * @param candidateId 候选标识
     * @return 候选详情
     */
    @Transactional(readOnly = true)
    public OperationCandidateResponse findCandidate(UUID siteId, UUID candidateId) {
        MineSite site = authorizer.requireSite(siteId);
        return findCandidate(site, candidateId);
    }

    /**
     * 查询单个运营执行记录及其修正历史。
     *
     * @param siteId 矿区标识
     * @param recordId 执行记录标识
     * @return 执行记录详情
     */
    @Transactional(readOnly = true)
    public OperationRecordResponse findRecord(UUID siteId, UUID recordId) {
        MineSite site = authorizer.requireSite(siteId);
        return findRecord(site, recordId);
    }

    OperationCandidateResponse findCandidate(MineSite site, UUID candidateId) {
        if (candidateId == null) {
            throw invalid();
        }
        return jdbcTemplate.query("""
                SELECT id, organization_id, site_id, spatial_event_id,
                       work_order_id, assignment_id, candidate_type, reason_code,
                       occurred_at, candidate_payload::TEXT AS candidate_payload_json,
                       status, resolution_action, resolution_reason,
                       resolved_record_id, resolved_at, resolved_by,
                       created_at, updated_at, version
                FROM mine_operation_candidate
                WHERE id = ? AND organization_id = ? AND site_id = ?
                """, this::mapCandidate, candidateId, site.organizationId(), site.id())
                .stream().findFirst()
                .orElseThrow(() -> new MineException(
                        CANDIDATE_NOT_FOUND, Map.of("candidateId", candidateId)));
    }

    OperationRecordResponse findRecord(MineSite site, UUID recordId) {
        if (recordId == null) {
            throw invalid();
        }
        RecordSnapshot snapshot = jdbcTemplate.query("""
                SELECT record.id, record.organization_id, record.site_id,
                       record.shift_instance_id, record.work_order_id,
                       record.assignment_id, record.cycle_number,
                       record.work_type, record.record_status, record.record_source,
                       record.vehicle_id, record.driver_person_id,
                       record.started_at, record.load_completed_at,
                       record.unloading_started_at, record.completed_at,
                       record.distance_meters AS base_distance_meters,
                       record.actual_tons AS base_actual_tons,
                       record.tons_source AS base_tons_source,
                       record.frozen_snapshot::TEXT AS frozen_snapshot_json,
                       record.created_at, record.created_by,
                       effective.effective,
                       effective.distance_meters, effective.actual_tons,
                       effective.tons_source, effective.correction_version,
                       effective.projected_at,
                       correction.id AS correction_id,
                       correction.correction_version AS history_version,
                       correction.correction_type,
                       correction.before_values::TEXT AS before_values_json,
                       correction.after_values::TEXT AS after_values_json,
                       correction.reason AS correction_reason,
                       correction.created_at AS correction_created_at,
                       correction.created_by AS correction_created_by
                FROM mine_operation_record record
                JOIN mine_operation_effective_record effective
                  ON effective.operation_record_id = record.id
                 AND effective.organization_id = record.organization_id
                 AND effective.site_id = record.site_id
                LEFT JOIN mine_operation_correction correction
                  ON correction.operation_record_id = record.id
                 AND correction.organization_id = record.organization_id
                 AND correction.site_id = record.site_id
                WHERE record.id = ?
                  AND record.organization_id = ? AND record.site_id = ?
                ORDER BY correction.correction_version
                """, resultSet -> {
                    BaseRecord record = null;
                    List<OperationRecordResponse.Correction> corrections = new ArrayList<>();
                    while (resultSet.next()) {
                        if (record == null) {
                            record = mapBaseRecord(resultSet, 0);
                        }
                        if (resultSet.getObject("correction_id", UUID.class) != null) {
                            corrections.add(mapCorrection(resultSet, 0));
                        }
                    }
                    return record == null
                            ? null
                            : new RecordSnapshot(record, List.copyOf(corrections));
                }, recordId, site.organizationId(), site.id());
        if (snapshot == null) {
            throw new MineException(RECORD_NOT_FOUND, Map.of("recordId", recordId));
        }
        return snapshot.record().toResponse(snapshot.corrections());
    }

    private OperationCandidateResponse mapCandidate(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new OperationCandidateResponse(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("site_id", UUID.class),
                resultSet.getObject("spatial_event_id", UUID.class),
                resultSet.getObject("work_order_id", UUID.class),
                resultSet.getObject("assignment_id", UUID.class),
                resultSet.getString("candidate_type"),
                resultSet.getString("reason_code"),
                instant(resultSet, "occurred_at"),
                readMap(resultSet.getString("candidate_payload_json")),
                resultSet.getString("status"),
                resultSet.getString("resolution_action"),
                resultSet.getString("resolution_reason"),
                resultSet.getObject("resolved_record_id", UUID.class),
                instant(resultSet, "resolved_at"),
                resultSet.getObject("resolved_by", UUID.class),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"),
                resultSet.getLong("version"));
    }

    private BaseRecord mapBaseRecord(ResultSet resultSet, int rowNumber) throws SQLException {
        return new BaseRecord(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("site_id", UUID.class),
                resultSet.getObject("shift_instance_id", UUID.class),
                resultSet.getObject("work_order_id", UUID.class),
                resultSet.getObject("assignment_id", UUID.class),
                resultSet.getLong("cycle_number"),
                resultSet.getString("work_type"),
                resultSet.getString("record_status"),
                resultSet.getString("record_source"),
                resultSet.getObject("vehicle_id", UUID.class),
                resultSet.getObject("driver_person_id", UUID.class),
                instant(resultSet, "started_at"),
                instant(resultSet, "load_completed_at"),
                instant(resultSet, "unloading_started_at"),
                instant(resultSet, "completed_at"),
                resultSet.getBigDecimal("base_distance_meters"),
                resultSet.getBigDecimal("base_actual_tons"),
                resultSet.getString("base_tons_source"),
                readMap(resultSet.getString("frozen_snapshot_json")),
                instant(resultSet, "created_at"),
                resultSet.getObject("created_by", UUID.class),
                resultSet.getBoolean("effective"),
                resultSet.getBigDecimal("distance_meters"),
                resultSet.getBigDecimal("actual_tons"),
                resultSet.getString("tons_source"),
                resultSet.getInt("correction_version"),
                instant(resultSet, "projected_at"));
    }

    private OperationRecordResponse.Correction mapCorrection(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new OperationRecordResponse.Correction(
                resultSet.getObject("correction_id", UUID.class),
                resultSet.getInt("history_version"),
                resultSet.getString("correction_type"),
                readMap(resultSet.getString("before_values_json")),
                readMap(resultSet.getString("after_values_json")),
                resultSet.getString("correction_reason"),
                instant(resultSet, "correction_created_at"),
                resultSet.getObject("correction_created_by", UUID.class));
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JacksonException exception) {
            throw new IllegalStateException("运营事实 JSON 快照无法解析", exception);
        }
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.strip().toUpperCase(Locale.ROOT);
        if (!CANDIDATE_STATUSES.contains(normalized)) {
            throw invalid();
        }
        return normalized;
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private MineException invalid() {
        return new MineException(INVALID, Map.of());
    }

    /** 不可变基础事实与当前投影的内部联合查询结果。 */
    private record BaseRecord(
            UUID id,
            UUID organizationId,
            UUID siteId,
            UUID shiftInstanceId,
            UUID workOrderId,
            UUID assignmentId,
            long cycleNumber,
            String workType,
            String recordStatus,
            String recordSource,
            UUID vehicleId,
            UUID driverPersonId,
            Instant startedAt,
            Instant loadCompletedAt,
            Instant unloadingStartedAt,
            Instant completedAt,
            java.math.BigDecimal baseDistanceMeters,
            java.math.BigDecimal baseActualTons,
            String baseTonsSource,
            Map<String, Object> frozenSnapshot,
            Instant createdAt,
            UUID createdBy,
            boolean effective,
            java.math.BigDecimal distanceMeters,
            java.math.BigDecimal actualTons,
            String tonsSource,
            int correctionVersion,
            Instant projectedAt
    ) {
        /** 组装包含修正历史的稳定响应。 */
        OperationRecordResponse toResponse(
                List<OperationRecordResponse.Correction> corrections
        ) {
            return new OperationRecordResponse(
                    id, organizationId, siteId, shiftInstanceId, workOrderId,
                    assignmentId, cycleNumber, workType, recordStatus, recordSource,
                    vehicleId, driverPersonId, startedAt, loadCompletedAt,
                    unloadingStartedAt, completedAt, baseDistanceMeters,
                    baseActualTons, baseTonsSource, frozenSnapshot, createdAt, createdBy,
                    effective, distanceMeters, actualTons, tonsSource,
                    correctionVersion, projectedAt, corrections);
        }
    }

    /** 单条数据库语句读取的一致执行事实与修正历史快照。 */
    private record RecordSnapshot(
            BaseRecord record,
            List<OperationRecordResponse.Correction> corrections
    ) {
    }
}
