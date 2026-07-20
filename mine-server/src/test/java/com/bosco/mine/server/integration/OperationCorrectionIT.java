package com.bosco.mine.server.integration;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.mine.dto.MineOrganizationResponse;
import com.bosco.mine.server.modules.mine.operation.OperationController;
import com.bosco.mine.server.modules.mine.operation.OperationCorrectionService;
import com.bosco.mine.server.modules.mine.operation.OperationQueryService;
import com.bosco.mine.server.modules.mine.operation.ShiftCommandService;
import com.bosco.mine.server.modules.mine.operation.WorkOrderCommandService;
import com.bosco.mine.server.modules.mine.operation.dto.OperationCandidateResolutionRequest;
import com.bosco.mine.server.modules.mine.operation.dto.OperationCandidateResponse;
import com.bosco.mine.server.modules.mine.operation.dto.OperationCorrectionRequest;
import com.bosco.mine.server.modules.mine.operation.dto.OperationRecordResponse;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftDefinitionRequest;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftInstanceRequest;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftInstanceResponse;
import com.bosco.mine.server.modules.mine.operation.dto.WorkOrderActionRequest;
import com.bosco.mine.server.modules.mine.operation.dto.WorkOrderAssignmentRequest;
import com.bosco.mine.server.modules.mine.operation.dto.WorkOrderRequest;
import com.bosco.mine.server.modules.mine.operation.dto.WorkOrderResponse;
import com.bosco.mine.server.modules.mine.service.MineRequestIdentity;
import com.bosco.mine.server.modules.mine.service.MineScopeCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 验证候选处置、人工补录和追加式运营修正。
 */
@Testcontainers
@ActiveProfiles("test")
@Import(OperationCorrectionIT.IdentityTestConfiguration.class)
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class OperationCorrectionIT {

    private static final Instant SHIFT_START = Instant.parse("2026-07-20T00:00:00Z");
    private static final Instant SHIFT_END = Instant.parse("2026-07-20T12:00:00Z");
    private static final Instant TRIP_START = Instant.parse("2026-07-20T01:00:00Z");
    private static final Instant TRIP_END = Instant.parse("2026-07-20T01:20:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine_operation_correction")
            .withUsername("mine")
            .withPassword("mine-operation-correction-test-password");

    @Autowired
    private OperationCorrectionService correctionService;

    @Autowired
    private OperationQueryService queryService;

    @Autowired
    private MineScopeCommandService scopeCommandService;

    @Autowired
    private ShiftCommandService shiftCommandService;

    @Autowired
    private WorkOrderCommandService workOrderCommandService;

    @Autowired
    private MutableMineRequestIdentity identity;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID deniedUserId;
    private UUID organizationId;
    private UUID siteId;
    private UUID shiftId;
    private UUID workOrderId;
    private UUID assignmentId;
    private UUID vehicleId;
    private UUID loadVersionId;

    @BeforeEach
    void 准备运行作业和隔离身份() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE sys_operation_log, sys_inbox_event, mine_organization CASCADE
                """);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'operation-correction-it-%'");
        userId = createUser("owner");
        deniedUserId = createUser("denied");
        identity.use(userId);
        MineOrganizationResponse organization = scopeCommandService.createOrganization(
                "operation-correction-org", "运营修正测试组织");
        organizationId = organization.id();
        siteId = scopeCommandService.createSite(
                organizationId, "operation-correction-site", "运营修正测试矿区",
                "UTC", null).id();
        createRunningWorkOrder();
    }

    @Test
    void 候选必须支持关联忽略和转人工记录并保留审计证据() {
        UUID associatedId = insertCandidate("associate");
        OperationCandidateResolutionRequest associateRequest =
                new OperationCandidateResolutionRequest(
                        "ASSOCIATE", workOrderId, assignmentId, null,
                        "调度确认归属", 0L);
        OperationCandidateResponse associated = correctionService.resolveCandidate(
                siteId, associatedId, "candidate-associate-key",
                associateRequest);
        assertThat(associated.status()).isEqualTo("RESOLVED");
        assertThat(associated.resolutionAction()).isEqualTo("ASSOCIATE");
        assertThat(associated.workOrderId()).isEqualTo(workOrderId);
        assertThat(associated.assignmentId()).isEqualTo(assignmentId);
        assertThat(correctionService.resolveCandidate(
                siteId, associatedId, "candidate-associate-key", associateRequest))
                .isEqualTo(associated);
        assertCode(() -> correctionService.resolveCandidate(
                        siteId, associatedId, "candidate-associate-key",
                        new OperationCandidateResolutionRequest(
                                "IGNORE", null, null, null, "冲突处置", 0L)),
                "MINE-OPERATION-409-001");

        UUID ignoredId = insertCandidate("ignore");
        OperationCandidateResponse ignored = correctionService.resolveCandidate(
                siteId, ignoredId, "candidate-ignore-key",
                new OperationCandidateResolutionRequest(
                        "IGNORE", null, null, null,
                        "测试定位信号，明确忽略", 0L));
        assertThat(ignored.status()).isEqualTo("IGNORED");
        assertThat(ignored.resolvedRecordId()).isNull();

        UUID convertedId = insertCandidate("convert");
        OperationCorrectionRequest manual = manualAddRequest("候选人工补录");
        OperationCandidateResponse converted = correctionService.resolveCandidate(
                siteId, convertedId, "candidate-convert-key",
                new OperationCandidateResolutionRequest(
                        "CONVERT_MANUAL", workOrderId, assignmentId, manual,
                        "现场记录确认形成运输趟次", 0L));
        assertThat(converted.status()).isEqualTo("CONVERTED");
        assertThat(converted.resolvedRecordId()).isNotNull();
        OperationRecordResponse record = correctionService.findRecord(
                siteId, converted.resolvedRecordId());
        assertThat(record.recordSource()).isEqualTo("MANUAL");
        assertThat(record.effective()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM mine_operation_evidence
                WHERE operation_record_id = ? AND evidence_role = 'MANUAL_SUPPORT'
                """, Integer.class, record.id())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM mine_operation_outbox
                WHERE aggregate_id IN (?, ?, ?)
                """, Integer.class, associatedId, ignoredId, record.id())).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                WHERE module = 'MINE_OPERATION'
                  AND action IN (
                      'ASSOCIATE_OPERATION_CANDIDATE',
                      'IGNORE_OPERATION_CANDIDATE',
                      'CONVERT_OPERATION_CANDIDATE'
                  )
                """, Integer.class)).isEqualTo(3);
        assertThat(queryService.findCandidates(siteId, null, 0, 20)).hasSize(3);
        assertThat(queryService.findCandidates(siteId, "converted", 0, 20))
                .extracting(OperationCandidateResponse::id)
                .containsExactly(convertedId);
    }

    @Test
    void 人工补录修正和作废必须追加完整快照且基础事实不可变() {
        OperationRecordResponse added = correctionService.addRecord(
                siteId, "manual-add-key", manualAddRequest("人工补录首趟"));
        Map<String, Object> immutableBase = jdbcTemplate.queryForMap("""
                SELECT distance_meters, actual_tons, tons_source, frozen_snapshot::TEXT snapshot
                FROM mine_operation_record WHERE id = ?
                """, added.id());

        OperationRecordResponse amended = correctionService.correctRecord(
                siteId, added.id(), "manual-amend-key",
                correctionRequest("AMEND", 0, "磅房复核吨位", "1250.500", "38.250"));
        assertThat(amended.correctionVersion()).isEqualTo(1);
        assertThat(amended.distanceMeters()).isEqualByComparingTo("1250.500");
        assertThat(amended.actualTons()).isEqualByComparingTo("38.250");
        assertThat(amended.tonsSource()).isEqualTo("MANUAL");

        Map<String, Object> correction = jdbcTemplate.queryForMap("""
                SELECT correction_type, correction_version,
                       before_values::TEXT AS before_values,
                       after_values::TEXT AS after_values
                FROM mine_operation_correction
                WHERE operation_record_id = ? AND correction_version = 1
                """, added.id());
        assertThat(correction)
                .containsEntry("correction_type", "AMEND")
                .containsEntry("correction_version", 1);
        assertThat((String) correction.get("before_values"))
                .contains("effective", "distanceMeters", "actualTons", "tonsSource");
        assertThat((String) correction.get("after_values"))
                .contains("effective", "distanceMeters", "actualTons", "tonsSource");

        OperationRecordResponse voided = correctionService.correctRecord(
                siteId, added.id(), "manual-void-key",
                correctionRequest("VOID", 1, "重复补录，作废", null, null));
        assertThat(voided.effective()).isFalse();
        assertThat(voided.correctionVersion()).isEqualTo(2);
        assertThat(voided.distanceMeters()).isEqualByComparingTo("1250.500");
        assertThat(voided.actualTons()).isEqualByComparingTo("38.250");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT distance_meters, actual_tons, tons_source, frozen_snapshot::TEXT snapshot
                FROM mine_operation_record WHERE id = ?
                """, added.id())).isEqualTo(immutableBase);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM mine_operation_record WHERE id = ?", added.id()))
                .isInstanceOf(Exception.class);
    }

    @Test
    void 幂等键必须同请求重放异请求冲突且失败不能留下半成品() {
        OperationCorrectionRequest firstRequest = manualAddRequest("幂等补录");
        OperationRecordResponse first = correctionService.addRecord(
                siteId, "same-add-key", firstRequest);
        assertThat(first.id().version()).isEqualTo(7);
        OperationRecordResponse replay = correctionService.addRecord(
                siteId, "same-add-key", firstRequest);
        assertThat(replay).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_operation_record WHERE id = ?",
                Integer.class, first.id())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT aggregate_id FROM mine_operation_action_idempotency
                WHERE site_id = ? AND operator_id = ? AND idempotency_key = 'same-add-key'
                """, UUID.class, siteId, userId)).isEqualTo(first.id());

        assertCode(() -> correctionService.addRecord(
                        siteId, "same-add-key", manualAddRequest("不同补录原因")),
                "MINE-OPERATION-409-001");
        OperationRecordResponse amended = correctionService.correctRecord(
                siteId, first.id(), "same-correction-key",
                correctionRequest("AMEND", 0, "首次修正", "900", "30"));
        assertThat(correctionService.correctRecord(
                siteId, first.id(), "same-correction-key",
                correctionRequest("AMEND", 0, "首次修正", "900", "30")))
                .isEqualTo(amended);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM mine_operation_correction
                WHERE operation_record_id = ?
                """, Integer.class, first.id())).isEqualTo(1);
        assertCode(() -> correctionService.correctRecord(
                        siteId, first.id(), "stale-version-key",
                        correctionRequest("AMEND", 0, "过期版本", "901", "31")),
                "MINE-OPERATION-409-002");
        assertCode(() -> correctionService.correctRecord(
                        siteId, first.id(), "blank-reason-key",
                        correctionRequest("AMEND", 1, "   ", "901", "31")),
                "MINE-OPERATION-422-002");
        assertCode(() -> correctionService.correctRecord(
                        siteId, first.id(), "immutable-field-key",
                        new OperationCorrectionRequest(
                                "AMEND", workOrderId, null, null,
                                null, null, null, null,
                                new BigDecimal("901"), new BigDecimal("31"), "MANUAL",
                                1, "不得修改事实身份")),
                "MINE-OPERATION-422-002");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM mine_operation_action_idempotency
                WHERE idempotency_key IN ('blank-reason-key', 'immutable-field-key')
                """, Integer.class)).isZero();
    }

    @Test
    void 人工补录必须拒绝阶段越界半开边界和候选证据错配() {
        OperationCorrectionRequest lateLoad = new OperationCorrectionRequest(
                "ADD", workOrderId, assignmentId, "COMPLETED",
                TRIP_START, TRIP_END.plusSeconds(1), null, TRIP_END,
                new BigDecimal("1200"), new BigDecimal("35"), "MANUAL",
                null, "装载时间晚于完成时间");
        assertCode(() -> correctionService.addRecord(
                        siteId, "late-load-key", lateLoad),
                "MINE-OPERATION-422-002");

        OperationCorrectionRequest exclusiveEnd = new OperationCorrectionRequest(
                "ADD", workOrderId, assignmentId, "COMPLETED",
                SHIFT_END, SHIFT_END, SHIFT_END, SHIFT_END,
                new BigDecimal("1200"), new BigDecimal("35"), "MANUAL",
                null, "开始时间位于排班半开边界");
        assertCode(() -> correctionService.addRecord(
                        siteId, "exclusive-end-key", exclusiveEnd),
                "MINE-OPERATION-422-002");

        UUID candidateId = insertCandidate("trip_mismatch");
        Instant unrelatedStart = TRIP_START.plusSeconds(3600);
        OperationCorrectionRequest unrelatedTrip = new OperationCorrectionRequest(
                "ADD", workOrderId, assignmentId, "COMPLETED",
                unrelatedStart, unrelatedStart.plusSeconds(300),
                unrelatedStart.plusSeconds(900), unrelatedStart.plusSeconds(1200),
                new BigDecimal("1300"), new BigDecimal("36"), "MANUAL",
                null, "与候选事件不相交的趟次");
        assertCode(() -> correctionService.resolveCandidate(
                        siteId, candidateId, "candidate-trip-mismatch-key",
                        new OperationCandidateResolutionRequest(
                                "CONVERT_MANUAL", workOrderId, assignmentId,
                                unrelatedTrip, "拒绝错配候选证据", 0L)),
                "MINE-OPERATION-422-002");
        assertThat(queryService.findCandidate(siteId, candidateId).status())
                .isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_operation_record",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM mine_operation_action_idempotency
                WHERE idempotency_key IN (
                    'late-load-key', 'exclusive-end-key', 'candidate-trip-mismatch-key'
                )
                """, Integer.class)).isZero();
    }

    @Test
    void 关账后仍可追溯修正但越权矿区和缺少修正权限接口必须拒绝() throws Exception {
        OperationRecordResponse added = correctionService.addRecord(
                siteId, "closed-add-key", manualAddRequest("关账前补录"));
        jdbcTemplate.update("""
                UPDATE mine_work_order_assignment
                SET status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP,
                    updated_by = ?, version = version + 1
                WHERE id = ?
                """, userId, assignmentId);
        jdbcTemplate.update("""
                UPDATE mine_work_order
                SET status = 'COMPLETED', completed_at = ?, completed_by = ?,
                    updated_at = ?, updated_by = ?, version = version + 1
                WHERE id = ?
                """, Timestamp.from(SHIFT_END.minusSeconds(60)), userId,
                Timestamp.from(SHIFT_END.minusSeconds(60)), userId, workOrderId);
        jdbcTemplate.update("""
                UPDATE mine_shift_instance
                SET status = 'CLOSED', actual_end_at = ?, closed_at = ?, closed_by = ?,
                    updated_at = CURRENT_TIMESTAMP, updated_by = ?, version = version + 1
                WHERE id = ?
                """, Timestamp.from(SHIFT_END), Timestamp.from(SHIFT_END),
                userId, userId, shiftId);

        OperationRecordResponse corrected = correctionService.correctRecord(
                siteId, added.id(), "closed-amend-key",
                correctionRequest("AMEND", 0, "关账后磅单追溯", "1001", "36"));
        assertThat(corrected.correctionVersion()).isEqualTo(1);
        assertCode(() -> correctionService.addRecord(
                        siteId, "closed-add-rejected-key",
                        manualAddRequest("关账后不得新增普通事实")),
                "MINE-OPERATION-422-002");

        identity.use(deniedUserId);
        assertCode(() -> correctionService.findRecord(siteId, added.id()),
                "MINE-SCOPE-403-002");
        identity.use(userId);
        assertPermission("resolveCandidate", "mine:operation:correct");
        assertPermission("addRecord", "mine:operation:correct");
        assertPermission("correctRecord", "mine:operation:correct");
        assertPermission("findRecord", "mine:operation:view");
    }

    private void createRunningWorkOrder() {
        var definition = shiftCommandService.createDefinition(siteId, new ShiftDefinitionRequest(
                "operation-correction-shift", "运营修正班次",
                LocalTime.MIDNIGHT, LocalTime.NOON, false,
                LocalDate.parse("2026-01-01"), null, 0L));
        ShiftInstanceResponse planned = shiftCommandService.createInstance(
                siteId, new ShiftInstanceRequest(
                        definition.id(), LocalDate.parse("2026-07-20"), null));
        ShiftInstanceResponse opened = shiftCommandService.openInstance(
                siteId, planned.id(), planned.version());
        shiftId = opened.id();
        SpatialVersions spatial = insertPublishedSpatialVersions();
        loadVersionId = spatial.loadVersionId();
        vehicleId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_vehicle (
                    id, organization_id, site_id, vehicle_code, name, vehicle_type
                ) VALUES (?, ?, ?, 'correction-truck', '修正测试矿卡', 'HAUL_TRUCK')
                """, vehicleId, organizationId, siteId);

        WorkOrderResponse draft = workOrderCommandService.createWorkOrder(
                siteId, new WorkOrderRequest(
                        shiftId, "CORRECTION-HAUL-001", "运营修正运输作业", "HAULAGE",
                        SHIFT_START, SHIFT_END, 20, new BigDecimal("700"), 50, 0L));
        WorkOrderResponse assigned = workOrderCommandService.assignWorkOrder(
                siteId, draft.id(), new WorkOrderAssignmentRequest(
                        vehicleId, null, SHIFT_START, SHIFT_END,
                        "运营修正测试调度", draft.version()));
        assignmentId = assigned.assignments().getFirst().id();
        WorkOrderResponse released = workOrderCommandService.releaseWorkOrder(
                siteId, assigned.id(), new WorkOrderActionRequest(
                        assigned.version(), spatial.routeVersionId(),
                        spatial.loadVersionId(), spatial.unloadVersionId(), null));
        WorkOrderResponse running = workOrderCommandService.startWorkOrder(
                siteId, released.id(), new WorkOrderActionRequest(
                        released.version(), null, null, null, null));
        workOrderId = running.id();
    }

    private OperationCorrectionRequest manualAddRequest(String reason) {
        return new OperationCorrectionRequest(
                "ADD", workOrderId, assignmentId, "COMPLETED",
                TRIP_START, TRIP_START.plusSeconds(300),
                TRIP_START.plusSeconds(900), TRIP_END,
                new BigDecimal("1200.000"), new BigDecimal("35.500"), "MANUAL",
                null, reason);
    }

    private OperationCorrectionRequest correctionRequest(
            String action,
            int projectionVersion,
            String reason,
            String distanceMeters,
            String actualTons
    ) {
        return new OperationCorrectionRequest(
                action, null, null, null,
                null, null, null, null,
                distanceMeters == null ? null : new BigDecimal(distanceMeters),
                actualTons == null ? null : new BigDecimal(actualTons),
                actualTons == null ? null : "MANUAL",
                projectionVersion, reason);
    }

    private UUID insertCandidate(String suffix) {
        UUID queueId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_operation_spatial_event_queue (
                    id, event_id, organization_id, site_id, vehicle_id,
                    zone_type, zone_version_id, event_type,
                    occurred_at, ingest_sequence, payload,
                    status, attempt_count, processed_at
                ) VALUES (?, ?, ?, ?, ?, 'GEOFENCE', ?, 'ENTER', ?, 1,
                          '{"source":"operation-correction-it"}'::jsonb,
                          'PROCESSED', 1, CURRENT_TIMESTAMP)
                """, queueId, eventId, organizationId, siteId, vehicleId,
                loadVersionId, Timestamp.from(TRIP_START));
        UUID candidateId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_operation_candidate (
                    id, organization_id, site_id, spatial_queue_id, spatial_event_id,
                    candidate_type, reason_code, occurred_at, candidate_payload
                ) VALUES (?, ?, ?, ?, ?, 'AMBIGUOUS_MATCH', ?, ?,
                          '{"source":"operation-correction-it"}'::jsonb)
                """, candidateId, organizationId, siteId, queueId, eventId,
                "TEST_" + suffix.toUpperCase(), Timestamp.from(TRIP_START));
        return candidateId;
    }

    private SpatialVersions insertPublishedSpatialVersions() {
        UUID coordinateSystemId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_coordinate_system (
                    id, organization_id, site_id, system_code, name,
                    coordinate_type, unit, unified,
                    min_x, max_x, min_y, max_y, min_z, max_z
                ) VALUES (?, ?, ?, 'correction-coordinate', '修正测试坐标系',
                          'LOCAL_CARTESIAN', 'METER', TRUE,
                          -1000, 1000, -1000, 1000, -100, 100)
                """, coordinateSystemId, organizationId, siteId);
        UUID routeId = UUID.randomUUID();
        UUID routeVersionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_route (id, organization_id, site_id, route_code)
                VALUES (?, ?, ?, 'correction-route')
                """, routeId, organizationId, siteId);
        jdbcTemplate.update("""
                INSERT INTO mine_route_version (
                    id, route_id, organization_id, site_id, coordinate_system_id,
                    version_number, name, direction, applicable_vehicle_types,
                    valid_from, status, content_hash, published_at
                ) VALUES (?, ?, ?, ?, ?, 1, '修正测试路线', 'FORWARD',
                          '["HAUL_TRUCK"]'::jsonb, TIMESTAMPTZ '2026-01-01 00:00:00Z',
                          'PUBLISHED', ?, CURRENT_TIMESTAMP)
                """, routeVersionId, routeId, organizationId, siteId, coordinateSystemId,
                "sha256:" + "a".repeat(64));
        UUID loadVersion = insertGeofence(
                coordinateSystemId, "correction-load", "修正测试装载区", "LOAD", "b");
        UUID unloadVersion = insertGeofence(
                coordinateSystemId, "correction-unload", "修正测试卸载区", "UNLOAD", "c");
        return new SpatialVersions(routeVersionId, loadVersion, unloadVersion);
    }

    private UUID insertGeofence(
            UUID coordinateSystemId,
            String code,
            String name,
            String type,
            String hashCharacter
    ) {
        UUID geofenceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_geofence (id, organization_id, site_id, geofence_code)
                VALUES (?, ?, ?, ?)
                """, geofenceId, organizationId, siteId, code);
        jdbcTemplate.update("""
                INSERT INTO mine_geofence_version (
                    id, geofence_id, organization_id, site_id,
                    coordinate_system_id, version_number, name, geofence_type,
                    boundary_tolerance_meters, enter_confirm_points,
                    exit_confirm_points, dwell_seconds,
                    allowed_lateness_seconds, idle_flush_seconds,
                    valid_from, status, content_hash, published_at
                ) VALUES (?, ?, ?, ?, ?, 1, ?, ?, 1, 2, 2, 30, 30, 60,
                          TIMESTAMPTZ '2026-01-01 00:00:00Z',
                          'PUBLISHED', ?, CURRENT_TIMESTAMP)
                """, versionId, geofenceId, organizationId, siteId,
                coordinateSystemId, name, type,
                "sha256:" + hashCharacter.repeat(64));
        return versionId;
    }

    private UUID createUser(String suffix) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password_hash, display_name)
                VALUES (?, ?, 'bcrypt:test', ?)
                """, id, "operation-correction-it-" + suffix,
                "运营修正测试用户" + suffix);
        return id;
    }

    private void assertPermission(String methodName, String expectedPermission) {
        Method method = java.util.Arrays.stream(OperationController.class.getDeclaredMethods())
                .filter(candidate -> methodName.equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        SaCheckPermission permission = method.getAnnotation(SaCheckPermission.class);
        assertThat(permission).isNotNull();
        assertThat(permission.value()).containsExactly(expectedPermission);
    }

    private void assertCode(Runnable action, String expectedCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo(expectedCode));
    }

    /** 为集成测试提供可切换且不依赖会话的请求身份。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IdentityTestConfiguration {

        /** 返回可切换测试身份。 */
        @Bean
        @Primary
        MutableMineRequestIdentity mutableMineRequestIdentity() {
            return new MutableMineRequestIdentity();
        }
    }

    /** 单线程集成测试使用的当前用户身份。 */
    static final class MutableMineRequestIdentity implements MineRequestIdentity {
        private UUID userId;

        /** 切换当前测试用户。 */
        void use(UUID value) {
            userId = value;
        }

        /** 返回当前测试用户。 */
        @Override
        public UUID currentUserId() {
            return userId;
        }
    }

    /** 已发布运输空间版本。 */
    private record SpatialVersions(
            UUID routeVersionId,
            UUID loadVersionId,
            UUID unloadVersionId
    ) {
    }
}
