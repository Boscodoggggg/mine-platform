package com.bosco.mine.server.modules.mine.operation.runtime;

import com.bosco.mine.server.modules.mine.operation.domain.HaulCycleStateMachine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 持久化运输循环匹配、运行态、候选、执行事实和同事务运营事件。
 */
@Repository
public class OperationRuntimeRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建运输作业运行仓储。
     *
     * @param jdbcTemplate 管理数据库访问模板
     */
    public OperationRuntimeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "数据库访问模板不能为空");
    }

    /**
     * 按事件发生时间校验标签车辆身份，并按班次、作业单、分配顺序锁定匹配上下文。
     *
     * @param event 已认领空间事件
     * @return 身份校验和同矿区发生时可能匹配的分配
     */
    public MatchSelection lockSelection(OperationEventQueue.ClaimedEvent event) {
        VehicleResolution resolution = resolveVehicle(event);
        if (resolution.status() != IdentityStatus.MATCHED) {
            return new MatchSelection(resolution, List.of());
        }
        ParentLockScope lockScope = lockAggregateScope(event);
        List<AssignmentMatch> matches = queryLockedMatches(event, resolution.vehicleId());
        if (matches.stream().anyMatch(match -> !lockScope.contains(match))) {
            throw new MatchScopeChangedException("运营自动匹配父级范围在锁定期间发生变化");
        }
        return new MatchSelection(resolution, matches);
    }

    /**
     * 锁定匹配上下文的兼容入口，供并发锁契约测试使用。
     *
     * @param event 已认领空间事件
     * @return 身份一致时的活动作业分配
     */
    public List<AssignmentMatch> lockMatches(OperationEventQueue.ClaimedEvent event) {
        return lockSelection(event).matches();
    }

    private List<AssignmentMatch> queryLockedMatches(
            OperationEventQueue.ClaimedEvent event,
            UUID vehicleId
    ) {
        return jdbcTemplate.query("""
                SELECT assignment.id AS assignment_id,
                       assignment.organization_id,
                       assignment.site_id,
                       assignment.vehicle_id,
                       assignment.driver_person_id,
                       assignment.valid_from,
                       assignment.valid_to,
                       work_order.id AS work_order_id,
                       work_order.status AS work_order_status,
                       status_history.status AS work_order_status_at_event,
                       work_order.shift_instance_id,
                       work_order.planned_start_at AS work_order_start_at,
                       work_order.planned_end_at AS work_order_end_at,
                       work_order.route_version_id,
                       work_order.load_geofence_version_id,
                       work_order.unload_geofence_version_id,
                       work_order.release_snapshot::TEXT AS release_snapshot_json,
                       shift_instance.status AS shift_status,
                       shift_instance.planned_start_at AS shift_planned_start_at,
                       shift_instance.planned_end_at AS shift_planned_end_at,
                       shift_instance.actual_start_at AS shift_actual_start_at,
                       shift_instance.actual_end_at AS shift_actual_end_at
                FROM mine_work_order_assignment assignment
                JOIN mine_work_order work_order
                  ON work_order.id = assignment.work_order_id
                 AND work_order.organization_id = assignment.organization_id
                 AND work_order.site_id = assignment.site_id
                JOIN mine_shift_instance shift_instance
                  ON shift_instance.id = work_order.shift_instance_id
                 AND shift_instance.organization_id = work_order.organization_id
                 AND shift_instance.site_id = work_order.site_id
                LEFT JOIN mine_work_order_status_history status_history
                  ON status_history.work_order_id = work_order.id
                 AND status_history.organization_id = work_order.organization_id
                 AND status_history.site_id = work_order.site_id
                 AND status_history.status_period @> CAST(? AS TIMESTAMPTZ)
                WHERE assignment.organization_id = ?
                  AND assignment.site_id = ?
                  AND assignment.vehicle_id = ?
                  AND assignment.status = 'ACTIVE'
                  AND assignment.active_period @> CAST(? AS TIMESTAMPTZ)
                  AND work_order.planned_start_at <= ?
                  AND work_order.planned_end_at > ?
                  AND shift_instance.planned_start_at <= ?
                  AND shift_instance.planned_end_at > ?
                ORDER BY work_order.id, assignment.id
                FOR UPDATE OF assignment
                """, this::mapAssignmentMatch,
                Timestamp.from(event.occurredAt()),
                event.organizationId(), event.siteId(), vehicleId,
                Timestamp.from(event.occurredAt()), Timestamp.from(event.occurredAt()),
                Timestamp.from(event.occurredAt()), Timestamp.from(event.occurredAt()),
                Timestamp.from(event.occurredAt()));
    }

    private VehicleResolution resolveVehicle(OperationEventQueue.ClaimedEvent event) {
        List<UUID> vehicles = jdbcTemplate.queryForList("""
                SELECT binding.vehicle_id
                FROM mine_vehicle_tag_binding binding
                WHERE binding.organization_id = ?
                  AND binding.site_id = ?
                  AND binding.tag_id = ?
                  AND binding.valid_period @> CAST(? AS TIMESTAMPTZ)
                ORDER BY binding.id
                """, UUID.class, event.organizationId(), event.siteId(),
                event.locationTagId(), Timestamp.from(event.occurredAt()));
        if (vehicles.size() != 1) {
            return new VehicleResolution(
                    vehicles.isEmpty()
                            ? IdentityStatus.NO_TAG_BINDING
                            : IdentityStatus.AMBIGUOUS_TAG_BINDING,
                    null);
        }
        UUID resolved = vehicles.getFirst();
        if (event.vehicleId() != null && !event.vehicleId().equals(resolved)) {
            return new VehicleResolution(IdentityStatus.EVENT_VEHICLE_TAG_MISMATCH, resolved);
        }
        return new VehicleResolution(IdentityStatus.MATCHED, resolved);
    }

    private ParentLockScope lockAggregateScope(OperationEventQueue.ClaimedEvent event) {
        Timestamp occurredAt = Timestamp.from(event.occurredAt());
        List<UUID> shiftIds = jdbcTemplate.queryForList("""
                SELECT shift_instance.id
                FROM mine_shift_instance shift_instance
                WHERE shift_instance.organization_id = ?
                  AND shift_instance.site_id = ?
                  AND shift_instance.planned_start_at <= ?
                  AND shift_instance.planned_end_at > ?
                ORDER BY shift_instance.id
                FOR UPDATE OF shift_instance
                """, UUID.class, event.organizationId(), event.siteId(),
                occurredAt, occurredAt);
        List<UUID> workOrderIds = jdbcTemplate.queryForList("""
                SELECT work_order.id
                FROM mine_work_order work_order
                JOIN mine_shift_instance shift_instance
                  ON shift_instance.id = work_order.shift_instance_id
                 AND shift_instance.organization_id = work_order.organization_id
                 AND shift_instance.site_id = work_order.site_id
                WHERE work_order.organization_id = ?
                  AND work_order.site_id = ?
                  AND shift_instance.planned_start_at <= ?
                  AND shift_instance.planned_end_at > ?
                  AND work_order.planned_start_at <= ?
                  AND work_order.planned_end_at > ?
                ORDER BY work_order.shift_instance_id, work_order.id
                FOR UPDATE OF work_order
                """, UUID.class, event.organizationId(), event.siteId(),
                occurredAt, occurredAt, occurredAt, occurredAt);
        return new ParentLockScope(Set.copyOf(shiftIds), Set.copyOf(workOrderIds));
    }

    /**
     * 锁定分配当前运输循环状态。
     *
     * @param assignmentId 作业分配标识
     * @return 当前持久运行态
     */
    public Optional<RuntimeState> lockRuntimeState(UUID assignmentId) {
        return jdbcTemplate.query("""
                SELECT id, organization_id, site_id, work_order_id, assignment_id,
                       cycle_number, cycle_status, last_spatial_event_id,
                       last_occurred_at, last_ingest_sequence,
                       evidence_snapshot::TEXT AS evidence_snapshot_json,
                       started_at, version
                FROM mine_operation_runtime_state
                WHERE assignment_id = ?
                FOR UPDATE
                """, this::mapRuntimeState, assignmentId).stream().findFirst();
    }

    /** 创建第一趟等待装载的运行态。 */
    public void insertInitialRuntimeState(UUID id, AssignmentMatch match) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO mine_operation_runtime_state (
                    id, organization_id, site_id, work_order_id, assignment_id
                ) VALUES (?, ?, ?, ?, ?)
                """, id, match.organizationId(), match.siteId(),
                match.workOrderId(), match.assignmentId());
        if (inserted != 1) {
            throw new IllegalStateException("运输循环初始运行态未能持久化");
        }
    }

    /** 使用已经锁定的记录推进或重置运输循环运行态。 */
    public void updateRuntimeState(
            UUID runtimeStateId,
            HaulCycleStateMachine.CycleState state,
            UUID lastSpatialEventId,
            String evidenceSnapshotJson,
            Instant startedAt
    ) {
        int changed = jdbcTemplate.update("""
                UPDATE mine_operation_runtime_state
                SET cycle_number = ?, cycle_status = ?,
                    last_spatial_event_id = ?, last_occurred_at = ?,
                    last_ingest_sequence = ?, evidence_snapshot = CAST(? AS JSONB),
                    started_at = ?, updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                WHERE id = ?
                """, state.cycleNumber(), state.status().name(), lastSpatialEventId,
                timestamp(state.lastOccurredAt()), state.lastIngestSequence(),
                evidenceSnapshotJson, timestamp(startedAt), runtimeStateId);
        if (changed != 1) {
            throw new IllegalStateException("运输循环运行态推进失败");
        }
    }

    /** 幂等保存不能安全自动推进的空间事件候选。 */
    public boolean insertCandidate(
            UUID id,
            OperationEventQueue.ClaimedEvent event,
            AssignmentMatch match,
            String candidateType,
            String reasonCode,
            String candidatePayloadJson
    ) {
        return jdbcTemplate.update("""
                INSERT INTO mine_operation_candidate (
                    id, organization_id, site_id,
                    spatial_queue_id, spatial_event_id,
                    work_order_id, assignment_id,
                    candidate_type, reason_code, occurred_at, candidate_payload
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB))
                ON CONFLICT (spatial_event_id, candidate_type, reason_code) DO NOTHING
                """, id, event.organizationId(), event.siteId(), event.queueId(), event.eventId(),
                match == null ? null : match.workOrderId(),
                match == null ? null : match.assignmentId(),
                candidateType, reasonCode, Timestamp.from(event.occurredAt()),
                candidatePayloadJson) == 1;
    }

    /** 保存自动完成的一趟不可变执行事实。 */
    public void insertCompletedRecord(CompletedRecord record) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO mine_operation_record (
                    id, organization_id, site_id, shift_instance_id,
                    work_order_id, assignment_id, cycle_number,
                    work_type, record_status, vehicle_id, driver_person_id,
                    route_version_id, load_geofence_version_id,
                    unload_geofence_version_id,
                    started_at, load_completed_at, unloading_started_at, completed_at,
                    frozen_snapshot, record_source
                ) VALUES (?, ?, ?, ?, ?, ?, ?,
                          'HAULAGE', 'COMPLETED', ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          CAST(? AS JSONB), 'AUTOMATIC')
                """, record.id(), record.match().organizationId(), record.match().siteId(),
                record.match().shiftInstanceId(), record.match().workOrderId(),
                record.match().assignmentId(), record.cycleNumber(),
                record.match().vehicleId(), record.match().driverPersonId(),
                record.match().routeVersionId(), record.match().loadGeofenceVersionId(),
                record.match().unloadGeofenceVersionId(), timestamp(record.startedAt()),
                timestamp(record.loadCompletedAt()), timestamp(record.unloadingStartedAt()),
                timestamp(record.completedAt()), record.frozenSnapshotJson());
        if (inserted != 1) {
            throw new IllegalStateException("自动运输执行事实未能持久化");
        }
    }

    /** 保存执行事实的当前有效投影。 */
    public void insertEffectiveRecord(CompletedRecord record) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO mine_operation_effective_record (
                    operation_record_id, organization_id, site_id,
                    shift_instance_id, work_order_id, assignment_id,
                    cycle_number, work_type, record_status, effective,
                    vehicle_id, driver_person_id, started_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'HAULAGE', 'COMPLETED', TRUE,
                          ?, ?, ?, ?)
                """, record.id(), record.match().organizationId(), record.match().siteId(),
                record.match().shiftInstanceId(), record.match().workOrderId(),
                record.match().assignmentId(), record.cycleNumber(),
                record.match().vehicleId(), record.match().driverPersonId(),
                timestamp(record.startedAt()), timestamp(record.completedAt()));
        if (inserted != 1) {
            throw new IllegalStateException("自动运输有效投影未能持久化");
        }
    }

    /** 保存完成运输循环的一条固定角色空间证据。 */
    public void insertEvidence(
            UUID id,
            CompletedRecord record,
            HaulCycleStateMachine.Evidence evidence,
            String payloadJson
    ) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO mine_operation_evidence (
                    id, organization_id, site_id, operation_record_id,
                    evidence_role, spatial_event_id, occurred_at,
                    ingest_sequence, evidence_payload
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB))
                """, id, record.match().organizationId(), record.match().siteId(),
                record.id(), evidence.role().name(), evidence.eventId(),
                Timestamp.from(evidence.occurredAt()), evidence.ingestSequence(), payloadJson);
        if (inserted != 1) {
            throw new IllegalStateException("自动运输空间证据未能持久化");
        }
    }

    /** 返回持久空间事件的规范化载荷。 */
    public String findSpatialPayload(UUID eventId) {
        return jdbcTemplate.queryForObject("""
                SELECT payload::TEXT FROM mine_operation_spatial_event_queue
                WHERE event_id = ?
                """, String.class, eventId);
    }

    /** 保存待可靠发布的运营变更事件。 */
    public void insertOutbox(
            UUID id,
            UUID organizationId,
            UUID siteId,
            String aggregateType,
            UUID aggregateId,
            String payloadJson
    ) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO mine_operation_outbox (
                    id, organization_id, site_id,
                    aggregate_type, aggregate_id,
                    event_type, event_version, payload
                ) VALUES (?, ?, ?, ?, ?, 'realtime.operation.updated', 1,
                          CAST(? AS JSONB))
                """, id, organizationId, siteId, aggregateType, aggregateId, payloadJson);
        if (inserted != 1) {
            throw new IllegalStateException("运营变更 Outbox 未能持久化");
        }
    }

    private AssignmentMatch mapAssignmentMatch(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new AssignmentMatch(
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("site_id", UUID.class),
                resultSet.getObject("shift_instance_id", UUID.class),
                resultSet.getString("shift_status"),
                instant(resultSet, "shift_planned_start_at"),
                instant(resultSet, "shift_planned_end_at"),
                instant(resultSet, "shift_actual_start_at"),
                instant(resultSet, "shift_actual_end_at"),
                resultSet.getObject("work_order_id", UUID.class),
                resultSet.getString("work_order_status"),
                resultSet.getString("work_order_status_at_event"),
                instant(resultSet, "work_order_start_at"),
                instant(resultSet, "work_order_end_at"),
                resultSet.getObject("assignment_id", UUID.class),
                resultSet.getObject("vehicle_id", UUID.class),
                resultSet.getObject("driver_person_id", UUID.class),
                instant(resultSet, "valid_from"),
                instant(resultSet, "valid_to"),
                resultSet.getObject("route_version_id", UUID.class),
                resultSet.getObject("load_geofence_version_id", UUID.class),
                resultSet.getObject("unload_geofence_version_id", UUID.class),
                resultSet.getString("release_snapshot_json"));
    }

    private RuntimeState mapRuntimeState(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new RuntimeState(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("site_id", UUID.class),
                resultSet.getObject("work_order_id", UUID.class),
                resultSet.getObject("assignment_id", UUID.class),
                resultSet.getLong("cycle_number"),
                resultSet.getString("cycle_status"),
                resultSet.getObject("last_spatial_event_id", UUID.class),
                instant(resultSet, "last_occurred_at"),
                resultSet.getLong("last_ingest_sequence"),
                resultSet.getString("evidence_snapshot_json"),
                instant(resultSet, "started_at"),
                resultSet.getLong("version"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    /** 发生时唯一候选作业、班次、分配和冻结空间上下文。 */
    public record AssignmentMatch(
            UUID organizationId,
            UUID siteId,
            UUID shiftInstanceId,
            String shiftStatus,
            Instant shiftPlannedStartAt,
            Instant shiftPlannedEndAt,
            Instant shiftActualStartAt,
            Instant shiftActualEndAt,
            UUID workOrderId,
            String workOrderStatus,
            String workOrderStatusAtEvent,
            Instant workOrderStartAt,
            Instant workOrderEndAt,
            UUID assignmentId,
            UUID vehicleId,
            UUID driverPersonId,
            Instant assignmentValidFrom,
            Instant assignmentValidTo,
            UUID routeVersionId,
            UUID loadGeofenceVersionId,
            UUID unloadGeofenceVersionId,
            String releaseSnapshotJson
    ) {
    }

    /** 标签车辆身份校验与聚合匹配结果。 */
    public record MatchSelection(
            VehicleResolution vehicleResolution,
            List<AssignmentMatch> matches
    ) {
    }

    /** 事件发生时解析到的车辆和身份结果。 */
    public record VehicleResolution(IdentityStatus status, UUID vehicleId) {
    }

    /** 固定低基数身份校验结果。 */
    public enum IdentityStatus {
        /** 标签唯一解析且与事件车辆一致。 */
        MATCHED,
        /** 发生时标签没有车辆绑定。 */
        NO_TAG_BINDING,
        /** 发生时标签错误地对应多个车辆。 */
        AMBIGUOUS_TAG_BINDING,
        /** 事件车辆与发生时标签车辆不一致。 */
        EVENT_VEHICLE_TAG_MISMATCH
    }

    private record ParentLockScope(Set<UUID> shiftIds, Set<UUID> workOrderIds) {

        private boolean contains(AssignmentMatch match) {
            return shiftIds.contains(match.shiftInstanceId())
                    && workOrderIds.contains(match.workOrderId());
        }
    }

    /**
     * 表示最终匹配发现了父级锁范围之外的并发新增任务，当前事务必须回滚重试。
     */
    public static final class MatchScopeChangedException extends RuntimeException {

        /**
         * 创建父级匹配范围变化异常。
         *
         * @param message 固定内部错误摘要
         */
        public MatchScopeChangedException(String message) {
            super(message);
        }
    }

    /** 已锁定的运输循环运行态持久投影。 */
    public record RuntimeState(
            UUID id,
            UUID organizationId,
            UUID siteId,
            UUID workOrderId,
            UUID assignmentId,
            long cycleNumber,
            String cycleStatus,
            UUID lastSpatialEventId,
            Instant lastOccurredAt,
            long lastIngestSequence,
            String evidenceSnapshotJson,
            Instant startedAt,
            long version
    ) {
    }

    /** 自动完成的一趟运输执行事实写入参数。 */
    public record CompletedRecord(
            UUID id,
            AssignmentMatch match,
            long cycleNumber,
            Instant startedAt,
            Instant loadCompletedAt,
            Instant unloadingStartedAt,
            Instant completedAt,
            String frozenSnapshotJson
    ) {
    }
}
