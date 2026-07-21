package com.bosco.mine.server.integration;

import com.bosco.mine.server.MineServerApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 校验班次、作业单和运营事实控制面的数据库不变量。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class OperationControlPlaneConstraintIT {

    private static final List<String> OPERATION_TABLES = List.of(
            "mine_shift_definition",
            "mine_shift_instance",
            "mine_work_order",
            "mine_work_order_assignment",
            "mine_operation_spatial_event_queue",
            "mine_operation_runtime_state",
            "mine_operation_candidate",
            "mine_operation_record",
            "mine_operation_evidence",
            "mine_operation_correction",
            "mine_operation_effective_record",
            "mine_operation_outbox");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine_operation_control_plane")
            .withUsername("mine")
            .withPassword("mine-operation-test-password");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID organizationId;
    private UUID siteId;
    private UUID otherSiteId;

    @BeforeEach
    void 准备矿区() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    mine_operation_outbox,
                    mine_operation_effective_record,
                    mine_operation_correction,
                    mine_operation_evidence,
                    mine_operation_record,
                    mine_operation_candidate,
                    mine_operation_runtime_state,
                    mine_operation_spatial_event_queue,
                    mine_work_order_assignment,
                    mine_work_order,
                    mine_shift_instance,
                    mine_shift_definition,
                    mine_vehicle,
                    mine_site,
                    mine_organization
                CASCADE
                """);
        organizationId = UUID.randomUUID();
        siteId = UUID.randomUUID();
        otherSiteId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_organization (id, organization_code, name)
                VALUES (?, ?, '运营测试组织')
                """, organizationId, "operation-" + shortId(organizationId));
        jdbcTemplate.update("""
                INSERT INTO mine_site (id, organization_id, site_code, name, timezone)
                VALUES (?, ?, ?, '运营测试主矿区', 'Asia/Shanghai'),
                       (?, ?, ?, '运营测试其他矿区', 'Asia/Shanghai')
                """, siteId, organizationId, "main-" + shortId(siteId),
                otherSiteId, organizationId, "other-" + shortId(otherSiteId));
    }

    @Test
    void 十二张运营表及每个字段都必须有中文注释() {
        for (String table : OPERATION_TABLES) {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT obj_description(to_regclass(?), 'pg_class')
                    """, String.class, "public." + table))
                    .as("表 %s 的中文注释", table)
                    .matches(".*[\\u4e00-\\u9fff].*");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.columns column_info
                    WHERE column_info.table_schema = 'public'
                      AND column_info.table_name = ?
                          AND COALESCE(col_description(
                            to_regclass('public.' || column_info.table_name),
                            column_info.ordinal_position
                          ), '') !~ '[一-龥]'
                    """, Integer.class, table))
                    .as("表 %s 缺少中文注释的字段数", table)
                    .isZero();
        }
    }

    @Test
    void 分配区间不可变事实和可领取队列必须具备数据库级结构保护() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT conname
                FROM pg_constraint
                WHERE conname IN (
                    'ex_mine_work_order_assignment_vehicle',
                    'ex_mine_work_order_assignment_driver'
                )
                ORDER BY conname
                """, String.class)).containsExactly(
                "ex_mine_work_order_assignment_driver",
                "ex_mine_work_order_assignment_vehicle");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT is_generated
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'mine_work_order_assignment'
                  AND column_name = 'active_period'
                """, String.class)).isEqualTo("ALWAYS");
        assertThat(jdbcTemplate.queryForList("""
                SELECT DISTINCT trigger_name
                FROM information_schema.triggers
                WHERE trigger_schema = 'public'
                  AND trigger_name IN (
                    'trg_mine_shift_instance_closed_immutable',
                    'trg_mine_work_order_snapshot_immutable',
                    'trg_mine_operation_record_immutable',
                    'trg_mine_operation_evidence_immutable',
                    'trg_mine_operation_correction_immutable'
                  )
                ORDER BY trigger_name
                """, String.class)).hasSize(5);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname IN (
                    'idx_mine_operation_queue_claim',
                    'idx_mine_operation_outbox_claim'
                  )
                  AND indexdef LIKE '% WHERE %'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("""
                SELECT conname
                FROM pg_constraint
                WHERE conname IN (
                    'uk_mine_vehicle_scope',
                    'uk_mine_location_tag_scope',
                    'uk_mine_vehicle_operation_scope',
                    'uk_mine_location_tag_operation_scope'
                )
                ORDER BY conname
                """, String.class)).containsExactly(
                "uk_mine_location_tag_scope", "uk_mine_vehicle_scope");
    }

    @Test
    void 班次必须限制作用域营业日期和二十四小时计划边界() {
        UUID definitionId = insertShiftDefinition(siteId, "day-shift");
        Instant plannedStart = Instant.parse("2026-07-19T23:00:00Z");
        Instant plannedEnd = Instant.parse("2026-07-20T11:00:00Z");
        insertShiftInstance(
                UUID.randomUUID(), siteId, definitionId,
                LocalDate.parse("2026-07-20"), plannedStart, plannedEnd);

        assertRejected(() -> insertShiftInstance(
                UUID.randomUUID(), siteId, definitionId,
                LocalDate.parse("2026-07-20"), plannedStart, plannedEnd));
        assertRejected(() -> insertShiftInstance(
                UUID.randomUUID(), siteId, definitionId,
                LocalDate.parse("2026-07-21"), plannedStart,
                plannedStart.plusSeconds(24 * 60 * 60L + 1)));
        assertRejected(() -> insertShiftInstance(
                UUID.randomUUID(), otherSiteId, definitionId,
                LocalDate.parse("2026-07-20"), plannedStart, plannedEnd));
        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_shift_definition (
                    id, organization_id, site_id, definition_code, name,
                    start_local, end_local, crosses_midnight, valid_from
                ) VALUES (?, ?, ?, 'broken-shift', '非法班次',
                          TIME '19:00', TIME '07:00', FALSE, DATE '2026-01-01')
                """, UUID.randomUUID(), organizationId, siteId));
    }

    @Test
    void 作业窗口发布快照和车辆活动分配必须由数据库兜底() {
        UUID userId = insertUser();
        UUID definitionId = insertShiftDefinition(siteId, "haul-shift");
        UUID shiftId = UUID.randomUUID();
        Instant shiftStart = Instant.parse("2026-07-19T23:00:00Z");
        Instant shiftEnd = Instant.parse("2026-07-20T11:00:00Z");
        insertShiftInstance(
                shiftId, siteId, definitionId, LocalDate.parse("2026-07-20"),
                shiftStart, shiftEnd);

        assertRejected(() -> insertWorkOrder(
                UUID.randomUUID(), shiftId, "outside-window",
                shiftStart.minusSeconds(1), shiftEnd));
        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_work_order (
                    id, organization_id, site_id, shift_instance_id,
                    order_number, title, work_type, planned_start_at,
                    planned_end_at, planned_cycles, priority, status
                ) VALUES (?, ?, ?, ?, 'released-without-snapshot', '缺少发布快照',
                          'HAULAGE', ?, ?, 1, 50, 'RELEASED')
                """, UUID.randomUUID(), organizationId, siteId, shiftId,
                timestamp(shiftStart), timestamp(shiftEnd)));

        UUID orderId = UUID.randomUUID();
        insertWorkOrder(orderId, shiftId, "haul-order", shiftStart, shiftEnd);
        assertRejected(() -> jdbcTemplate.update("""
                UPDATE mine_work_order
                SET status = 'CANCELLED', cancelled_at = created_at - INTERVAL '1 second',
                    cancelled_by = ?, terminal_reason = '非法取消时间'
                WHERE id = ?
                """, userId, orderId));
        UUID vehicleId = insertVehicle("truck-a");
        insertAssignment(
                UUID.randomUUID(), orderId, vehicleId,
                shiftStart, shiftStart.plusSeconds(3600));

        assertRejected(() -> insertAssignment(
                UUID.randomUUID(), orderId, vehicleId,
                shiftStart.plusSeconds(1800), shiftStart.plusSeconds(5400)));
    }

    @Test
    void 已发布作业司机排斥和终态班次必须通过真实行为验证() {
        UUID userId = insertUser();
        UUID definitionId = insertShiftDefinition(siteId, "published-shift");
        UUID shiftId = UUID.randomUUID();
        Instant start = Instant.parse("2026-07-19T23:00:00Z");
        Instant end = Instant.parse("2026-07-20T11:00:00Z");
        insertShiftInstance(
                shiftId, siteId, definitionId, LocalDate.parse("2026-07-20"), start, end);
        UUID orderId = UUID.randomUUID();
        insertWorkOrder(orderId, shiftId, "published-order", start, end);
        SpatialVersions versions = insertPublishedSpatialVersions();
        String releaseSnapshot = """
                {
                  "orderNumber":"published-order",
                  "title":"运输作业",
                  "workType":"HAULAGE",
                  "plannedStartAt":"%s",
                  "plannedEndAt":"%s",
                  "routeVersionId":"%s",
                  "loadGeofenceVersionId":"%s",
                  "unloadGeofenceVersionId":"%s",
                  "routeName":"主运输路线",
                  "loadZoneName":"装载区",
                  "unloadZoneName":"卸载区"
                }
                """.formatted(
                start, end, versions.routeVersionId(),
                versions.loadGeofenceVersionId(), versions.unloadGeofenceVersionId());
        jdbcTemplate.update("""
                UPDATE mine_work_order
                SET route_version_id = ?, load_geofence_version_id = ?,
                    unload_geofence_version_id = ?, release_snapshot = ?::jsonb,
                    status = 'RELEASED', released_at = CURRENT_TIMESTAMP,
                    released_by = ?, updated_at = CURRENT_TIMESTAMP,
                    updated_by = ?, version = version + 1
                WHERE id = ?
                """, versions.routeVersionId(), versions.loadGeofenceVersionId(),
                versions.unloadGeofenceVersionId(), releaseSnapshot,
                userId, userId, orderId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM mine_work_order WHERE id = ?
                """, String.class, orderId)).isEqualTo("RELEASED");
        assertRejected(() -> jdbcTemplate.update("""
                UPDATE mine_work_order SET title = '禁止改写' WHERE id = ?
                """, orderId));
        assertRejected(() -> jdbcTemplate.update("""
                UPDATE mine_work_order
                SET status = 'RUNNING', started_at = released_at - INTERVAL '1 second',
                    started_by = ?
                WHERE id = ?
                """, userId, orderId));
        jdbcTemplate.update("""
                UPDATE mine_shift_instance
                SET status = 'OPEN', actual_start_at = CURRENT_TIMESTAMP,
                    opened_at = CURRENT_TIMESTAMP, opened_by = ?,
                    updated_at = CURRENT_TIMESTAMP, updated_by = ?, version = version + 1
                WHERE id = ?
                """, userId, userId, shiftId);
        jdbcTemplate.update("""
                UPDATE mine_work_order
                SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP,
                    started_by = ?, updated_at = CURRENT_TIMESTAMP,
                    updated_by = ?, version = version + 1
                WHERE id = ?
                """, userId, userId, orderId);
        assertRejected(() -> jdbcTemplate.update("""
                UPDATE mine_shift_instance
                SET status = 'CLOSED', actual_end_at = CURRENT_TIMESTAMP,
                    closed_at = CURRENT_TIMESTAMP, closed_by = ?,
                    updated_at = CURRENT_TIMESTAMP, updated_by = ?, version = version + 1
                WHERE id = ?
                """, userId, userId, shiftId));

        UUID driverId = insertDriver(start, end);
        UUID firstVehicleId = insertVehicle("driver-truck-a");
        UUID secondVehicleId = insertVehicle("driver-truck-b");
        insertAssignmentWithDriver(
                UUID.randomUUID(), orderId, firstVehicleId, driverId, start, end);
        assertRejected(() -> insertAssignmentWithDriver(
                UUID.randomUUID(), orderId, secondVehicleId, driverId, start, end));

        UUID terminalShiftId = UUID.randomUUID();
        insertShiftInstance(
                terminalShiftId, siteId, definitionId,
                LocalDate.parse("2026-07-21"), start.plusSeconds(86400),
                end.plusSeconds(86400));
        jdbcTemplate.update("""
                UPDATE mine_shift_instance
                SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP,
                    cancelled_by = ?, cancellation_reason = '计划调整',
                    updated_at = CURRENT_TIMESTAMP, updated_by = ?, version = version + 1
                WHERE id = ?
                """, userId, userId, terminalShiftId);
        assertRejected(() -> jdbcTemplate.update("""
                UPDATE mine_shift_instance SET business_date = DATE '2026-07-22'
                WHERE id = ?
                """, terminalShiftId));
        assertRejected(() -> insertWorkOrder(
                UUID.randomUUID(), terminalShiftId, "cancelled-shift-order",
                start.plusSeconds(86400), end.plusSeconds(86400)));
    }

    @Test
    void 空间事件队列和业务出站箱必须限制状态租约白名单及载荷大小() {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_operation_spatial_event_queue (
                    id, event_id, organization_id, site_id, zone_type,
                    zone_version_id, event_type, occurred_at,
                    ingest_sequence, payload
                ) VALUES (?, ?, ?, ?, 'GEOFENCE', ?, 'ENTER',
                          CURRENT_TIMESTAMP, 1, '{"subject":"unresolved"}'::jsonb)
                """, UUID.randomUUID(), eventId, organizationId, siteId, UUID.randomUUID());
        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_operation_spatial_event_queue (
                    id, event_id, organization_id, site_id, zone_type,
                    zone_version_id, event_type, occurred_at,
                    ingest_sequence, payload, status
                ) VALUES (?, ?, ?, ?, 'GEOFENCE', ?, 'ENTER',
                          CURRENT_TIMESTAMP, 2, '{}'::jsonb, 'PROCESSING')
                """, UUID.randomUUID(), UUID.randomUUID(), organizationId,
                siteId, UUID.randomUUID()));
        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_operation_spatial_event_queue (
                    id, event_id, organization_id, site_id, zone_type,
                    zone_version_id, event_type, occurred_at,
                    ingest_sequence, payload
                ) VALUES (?, ?, ?, ?, 'GEOFENCE', ?, 'UNKNOWN',
                          CURRENT_TIMESTAMP, 3, '{}'::jsonb)
                """, UUID.randomUUID(), UUID.randomUUID(), organizationId,
                siteId, UUID.randomUUID()));
        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_operation_spatial_event_queue (
                    id, event_id, organization_id, site_id, zone_type,
                    zone_version_id, event_type, occurred_at,
                    ingest_sequence, payload
                ) VALUES (?, ?, ?, ?, 'GEOFENCE', ?, 'ENTER',
                          CURRENT_TIMESTAMP, 4,
                          jsonb_build_object('data', repeat('x', 70000)))
                """, UUID.randomUUID(), UUID.randomUUID(), organizationId,
                siteId, UUID.randomUUID()));

        insertOutbox(UUID.randomUUID(), "PENDING", null, null, "{}".repeat(1));
        assertRejected(() -> insertOutbox(
                UUID.randomUUID(), "PROCESSING", null, null, "{}"));
        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_operation_outbox (
                    id, organization_id, site_id, aggregate_type, aggregate_id,
                    event_type, event_version, payload
                ) VALUES (?, ?, ?, 'WORK_ORDER', ?,
                          'realtime.unknown', 1, '{}'::jsonb)
                """, UUID.randomUUID(), organizationId, siteId, UUID.randomUUID()));
        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_operation_outbox (
                    id, organization_id, site_id, aggregate_type, aggregate_id,
                    event_type, event_version, payload
                ) VALUES (?, ?, ?, 'WORK_ORDER', ?,
                          'realtime.operation.updated', 1,
                          jsonb_build_object('data', repeat('x', 70000)))
                """, UUID.randomUUID(), organizationId, siteId, UUID.randomUUID()));
    }

    @Test
    void 运行状态和候选必须与作业分配及来源队列保持同一上下文() {
        UUID userId = insertUser();
        UUID definitionId = insertShiftDefinition(siteId, "context-shift");
        UUID shiftId = UUID.randomUUID();
        Instant start = Instant.parse("2026-07-19T23:00:00Z");
        Instant end = Instant.parse("2026-07-20T11:00:00Z");
        insertShiftInstance(
                shiftId, siteId, definitionId, LocalDate.parse("2026-07-20"), start, end);
        UUID firstOrderId = UUID.randomUUID();
        UUID secondOrderId = UUID.randomUUID();
        insertWorkOrder(firstOrderId, shiftId, "context-order-a", start, end);
        insertWorkOrder(secondOrderId, shiftId, "context-order-b", start, end);
        UUID vehicleId = insertVehicle("truck-context");
        UUID assignmentId = UUID.randomUUID();
        insertAssignment(assignmentId, firstOrderId, vehicleId, start, end);

        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_operation_runtime_state (
                    id, organization_id, site_id, work_order_id, assignment_id
                ) VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), organizationId, siteId,
                secondOrderId, assignmentId));
        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_operation_runtime_state (
                    id, organization_id, site_id, work_order_id, assignment_id
                ) VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), organizationId, siteId,
                firstOrderId, assignmentId));

        UUID queueId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_operation_spatial_event_queue (
                    id, event_id, organization_id, site_id, zone_type,
                    zone_version_id, event_type, occurred_at,
                    ingest_sequence, payload
                ) VALUES (?, ?, ?, ?, 'GEOFENCE', ?, 'ENTER',
                          CURRENT_TIMESTAMP, 1, '{"subject":"unresolved"}'::jsonb)
                """, queueId, eventId, organizationId, siteId, UUID.randomUUID());

        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_operation_candidate (
                    id, organization_id, site_id, spatial_queue_id,
                    spatial_event_id, candidate_type, reason_code,
                    occurred_at, candidate_payload
                ) VALUES (?, ?, ?, ?, ?, 'AMBIGUOUS_MATCH',
                          'MULTIPLE_WORK_ORDERS', CURRENT_TIMESTAMP, '{}'::jsonb)
                """, UUID.randomUUID(), organizationId, otherSiteId,
                queueId, eventId));
        UUID candidateId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_operation_candidate (
                    id, organization_id, site_id, spatial_queue_id,
                    spatial_event_id, candidate_type, reason_code,
                    occurred_at, candidate_payload
                ) VALUES (?, ?, ?, ?, ?, 'AMBIGUOUS_MATCH',
                          'MULTIPLE_WORK_ORDERS', CURRENT_TIMESTAMP, '{}'::jsonb)
                """, candidateId, organizationId, siteId, queueId, eventId);
        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_operation_candidate (
                    id, organization_id, site_id, spatial_queue_id,
                    spatial_event_id, candidate_type, reason_code,
                    occurred_at, candidate_payload, status,
                    resolution_action, resolution_reason, resolved_at
                ) VALUES (?, ?, ?, ?, ?, 'OUT_OF_ORDER', 'LATE_EVENT',
                          CURRENT_TIMESTAMP, '{}'::jsonb, 'IGNORED',
                          'IGNORE', '缺少处置人', CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), organizationId, siteId, queueId, eventId));
        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_operation_candidate (
                    id, organization_id, site_id, spatial_queue_id,
                    spatial_event_id, candidate_type, reason_code,
                    occurred_at, candidate_payload, status,
                    resolution_action, resolution_reason, resolved_at, resolved_by
                ) VALUES (?, ?, ?, ?, ?, 'OUT_OF_ORDER', 'LATE_EVENT',
                          CURRENT_TIMESTAMP, '{}'::jsonb, 'IGNORED',
                          'CONVERT_MANUAL', '错误动作组合', CURRENT_TIMESTAMP, ?)
                """, UUID.randomUUID(), organizationId, siteId, queueId, eventId, userId));
        jdbcTemplate.update("""
                UPDATE mine_operation_candidate
                SET status = 'IGNORED', resolution_action = 'IGNORE',
                    resolution_reason = '已确认忽略', resolved_at = CURRENT_TIMESTAMP,
                    resolved_by = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ?
                """, userId, candidateId);
    }

    @Test
    void 执行记录证据和修正必须追加写且保持唯一版本() {
        UUID userId = insertUser();
        UUID definitionId = insertShiftDefinition(siteId, "fact-shift");
        UUID shiftId = UUID.randomUUID();
        Instant start = Instant.parse("2026-07-19T23:00:00Z");
        Instant end = Instant.parse("2026-07-20T11:00:00Z");
        insertShiftInstance(
                shiftId, siteId, definitionId, LocalDate.parse("2026-07-20"), start, end);
        UUID orderId = UUID.randomUUID();
        insertWorkOrder(orderId, shiftId, "fact-order", start, end);
        UUID vehicleId = insertVehicle("truck-fact");
        UUID assignmentId = UUID.randomUUID();
        insertAssignment(assignmentId, orderId, vehicleId, start, end);

        UUID recordId = UUID.randomUUID();
        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_operation_record (
                    id, organization_id, site_id, shift_instance_id,
                    work_order_id, assignment_id, cycle_number, work_type,
                    record_status, vehicle_id, started_at, completed_at,
                    frozen_snapshot, record_source
                ) VALUES (?, ?, ?, ?, ?, ?, 99, 'HAULAGE', 'COMPLETED', ?,
                          ?, ?, '{}'::jsonb, 'AUTOMATIC')
                """, UUID.randomUUID(), organizationId, siteId, shiftId, orderId,
                assignmentId, vehicleId, timestamp(start),
                timestamp(start.plusSeconds(1800))));
        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_operation_record (
                    id, organization_id, site_id, shift_instance_id,
                    work_order_id, assignment_id, cycle_number, work_type,
                    record_status, vehicle_id, started_at, completed_at,
                    frozen_snapshot, record_source
                ) VALUES (?, ?, ?, ?, ?, ?, 98, 'HAULAGE', 'COMPLETED', ?,
                          ?, ?, '{}'::jsonb, 'MANUAL')
                """, UUID.randomUUID(), organizationId, siteId, shiftId, orderId,
                assignmentId, vehicleId, timestamp(start),
                timestamp(start.plusSeconds(1800))));
        jdbcTemplate.update("""
                INSERT INTO mine_operation_record (
                    id, organization_id, site_id, shift_instance_id,
                    work_order_id, assignment_id, cycle_number, work_type,
                    record_status, vehicle_id, started_at, completed_at,
                    frozen_snapshot, record_source, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, 1, 'HAULAGE', 'COMPLETED', ?,
                          ?, ?, '{}'::jsonb, 'MANUAL', ?)
                """, recordId, organizationId, siteId, shiftId, orderId,
                assignmentId, vehicleId, timestamp(start),
                timestamp(start.plusSeconds(1800)), userId);
        UUID evidenceId = UUID.randomUUID();
        UUID spatialEventId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_operation_evidence (
                    id, organization_id, site_id, operation_record_id,
                    evidence_role, spatial_event_id, occurred_at,
                    ingest_sequence, evidence_payload
                ) VALUES (?, ?, ?, ?, 'LOAD_ENTER', ?, ?, 1, '{}'::jsonb)
                """, evidenceId, organizationId, siteId, recordId,
                spatialEventId, timestamp(start));
        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_operation_evidence (
                    id, organization_id, site_id, operation_record_id,
                    evidence_role, occurred_at, evidence_payload
                ) VALUES (?, ?, ?, ?, 'LOAD_DWELL', ?, '{"manual":true}'::jsonb)
                """, UUID.randomUUID(), organizationId, siteId, recordId,
                timestamp(start.plusSeconds(10))));
        jdbcTemplate.update("""
                INSERT INTO mine_operation_evidence (
                    id, organization_id, site_id, operation_record_id,
                    evidence_role, occurred_at, evidence_payload, created_by
                ) VALUES (?, ?, ?, ?, 'LOAD_DWELL', ?, '{"manual":true}'::jsonb, ?),
                         (?, ?, ?, ?, 'LOAD_EXIT', ?, '{"manual":true}'::jsonb, ?)
                """, UUID.randomUUID(), organizationId, siteId, recordId,
                timestamp(start.plusSeconds(10)), userId,
                UUID.randomUUID(), organizationId, siteId, recordId,
                timestamp(start.plusSeconds(20)), userId);
        UUID correctionId = UUID.randomUUID();
        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_operation_correction (
                    id, organization_id, site_id, operation_record_id,
                    correction_version, correction_type, before_values,
                    after_values, reason, idempotency_key
                ) VALUES (?, ?, ?, ?, 1, 'AMEND',
                          '{"effective":true,"distanceMeters":null,
                            "actualTons":null,"tonsSource":null}'::jsonb,
                          '{"effective":true,"distanceMeters":null,
                            "actualTons":12,"tonsSource":"MANUAL"}'::jsonb,
                          '缺少修正人', ?)
                """, UUID.randomUUID(), organizationId, siteId, recordId,
                UUID.randomUUID()));
        jdbcTemplate.update("""
                INSERT INTO mine_operation_correction (
                    id, organization_id, site_id, operation_record_id,
                    correction_version, correction_type, before_values,
                    after_values, reason, idempotency_key, created_by
                ) VALUES (?, ?, ?, ?, 1, 'AMEND',
                          '{"effective":true,"distanceMeters":null,
                            "actualTons":null,"tonsSource":null}'::jsonb,
                          '{"effective":true,"distanceMeters":null,
                            "actualTons":12,"tonsSource":"MANUAL"}'::jsonb,
                          '地磅数据补录', ?, ?)
                """, correctionId, organizationId, siteId, recordId,
                UUID.randomUUID(), userId);

        assertRejected(() -> jdbcTemplate.update("""
                UPDATE mine_operation_record SET actual_tons = 12 WHERE id = ?
                """, recordId));
        assertRejected(() -> jdbcTemplate.update("""
                DELETE FROM mine_operation_evidence WHERE id = ?
                """, evidenceId));
        assertRejected(() -> jdbcTemplate.update("""
                UPDATE mine_operation_correction SET reason = '禁止改写' WHERE id = ?
                """, correctionId));
        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_operation_evidence (
                    id, organization_id, site_id, operation_record_id,
                    evidence_role, spatial_event_id, occurred_at,
                    ingest_sequence, evidence_payload
                ) VALUES (?, ?, ?, ?, 'LOAD_ENTER', ?, ?, 2, '{}'::jsonb)
                """, UUID.randomUUID(), organizationId, siteId, recordId,
                UUID.randomUUID(), timestamp(start.plusSeconds(1))));
        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_operation_correction (
                    id, organization_id, site_id, operation_record_id,
                    correction_version, correction_type, before_values,
                    after_values, reason, idempotency_key, created_by
                ) VALUES (?, ?, ?, ?, 1, 'VOID',
                          '{"effective":true,"distanceMeters":null,
                            "actualTons":null,"tonsSource":null}'::jsonb,
                          '{"effective":false,"distanceMeters":null,
                            "actualTons":null,"tonsSource":null}'::jsonb,
                          '重复修订版本', ?, ?)
                """, UUID.randomUUID(), organizationId, siteId, recordId,
                UUID.randomUUID(), userId));

        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_operation_effective_record (
                    operation_record_id, organization_id, site_id,
                    shift_instance_id, work_order_id, assignment_id,
                    cycle_number, work_type, record_status, vehicle_id,
                    started_at, completed_at, actual_tons, tons_source,
                    correction_version
                ) VALUES (?, ?, ?, ?, ?, ?, 1, 'HAULAGE', 'COMPLETED', ?, ?, ?,
                          12, 'MANUAL', 2)
                """, recordId, organizationId, siteId, shiftId, orderId,
                assignmentId, vehicleId, timestamp(start),
                timestamp(start.plusSeconds(1800))));
        jdbcTemplate.update("""
                INSERT INTO mine_operation_effective_record (
                    operation_record_id, organization_id, site_id,
                    shift_instance_id, work_order_id, assignment_id,
                    cycle_number, work_type, record_status, vehicle_id,
                    started_at, completed_at, actual_tons, tons_source,
                    correction_version
                ) VALUES (?, ?, ?, ?, ?, ?, 1, 'HAULAGE', 'COMPLETED', ?, ?, ?,
                          12, 'MANUAL', 1)
                """, recordId, organizationId, siteId, shiftId, orderId,
                assignmentId, vehicleId, timestamp(start),
                timestamp(start.plusSeconds(1800)));
        assertRejected(() -> jdbcTemplate.update("""
                UPDATE mine_operation_effective_record
                SET actual_tons = 13
                WHERE operation_record_id = ?
                """, recordId));

        UUID secondRecordId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_operation_record (
                    id, organization_id, site_id, shift_instance_id,
                    work_order_id, assignment_id, cycle_number, work_type,
                    record_status, vehicle_id, started_at, completed_at,
                    frozen_snapshot, record_source, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, 2, 'HAULAGE', 'COMPLETED', ?,
                          ?, ?, '{}'::jsonb, 'MANUAL', ?)
                """, secondRecordId, organizationId, siteId, shiftId, orderId,
                assignmentId, vehicleId, timestamp(start.plusSeconds(1801)),
                timestamp(start.plusSeconds(3600)), userId);
        assertRejected(() -> jdbcTemplate.update("""
                INSERT INTO mine_operation_evidence (
                    id, organization_id, site_id, operation_record_id,
                    evidence_role, spatial_event_id, occurred_at,
                    ingest_sequence, evidence_payload
                ) VALUES (?, ?, ?, ?, 'LOAD_ENTER', ?, ?, 1, '{}'::jsonb)
                """, UUID.randomUUID(), organizationId, siteId, secondRecordId,
                spatialEventId, timestamp(start.plusSeconds(1801))));
    }

    private UUID insertShiftDefinition(UUID targetSiteId, String code) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_shift_definition (
                    id, organization_id, site_id, definition_code, name,
                    start_local, end_local, crosses_midnight, valid_from
                ) VALUES (?, ?, ?, ?, '白班', TIME '07:00', TIME '19:00',
                          FALSE, DATE '2026-01-01')
                """, id, organizationId, targetSiteId, code);
        return id;
    }

    private void insertShiftInstance(
            UUID id,
            UUID targetSiteId,
            UUID definitionId,
            LocalDate businessDate,
            Instant plannedStart,
            Instant plannedEnd
    ) {
        jdbcTemplate.update("""
                INSERT INTO mine_shift_instance (
                    id, organization_id, site_id, shift_definition_id,
                    business_date, planned_start_at, planned_end_at,
                    definition_snapshot
                ) VALUES (?, ?, ?, ?, ?, ?, ?, '{"name":"白班"}'::jsonb)
                """, id, organizationId, targetSiteId, definitionId,
                businessDate, timestamp(plannedStart), timestamp(plannedEnd));
    }

    private void insertWorkOrder(
            UUID id,
            UUID shiftId,
            String orderNumber,
            Instant plannedStart,
            Instant plannedEnd
    ) {
        jdbcTemplate.update("""
                INSERT INTO mine_work_order (
                    id, organization_id, site_id, shift_instance_id,
                    order_number, title, work_type, planned_start_at,
                    planned_end_at, planned_cycles, priority
                ) VALUES (?, ?, ?, ?, ?, '运输作业', 'HAULAGE', ?, ?, 10, 50)
                """, id, organizationId, siteId, shiftId, orderNumber,
                timestamp(plannedStart), timestamp(plannedEnd));
    }

    private UUID insertVehicle(String code) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_vehicle (
                    id, organization_id, site_id, vehicle_code, name, vehicle_type
                ) VALUES (?, ?, ?, ?, '测试矿卡', 'HAUL_TRUCK')
                """, id, organizationId, siteId, code);
        return id;
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password_hash, display_name)
                VALUES (?, ?, 'bcrypt:test', '运营测试用户')
                """, id, "operation-user-" + shortId(id));
        return id;
    }

    private UUID insertDriver(Instant validFrom, Instant validTo) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_person (
                    id, organization_id, person_code, name, person_type,
                    phone_ciphertext, phone_masked,
                    certificate_ciphertext, certificate_masked
                ) VALUES (?, ?, ?, '运营测试司机', 'DRIVER',
                          'v2:phone:cipher', '138****0000',
                          'v2:certificate:cipher', '证件****0000')
                """, id, organizationId, "driver-" + shortId(id));
        jdbcTemplate.update("""
                INSERT INTO mine_person_site_assignment (
                    id, organization_id, site_id, person_id,
                    valid_from, valid_to, reason
                ) VALUES (?, ?, ?, ?, ?, ?, '运营测试任职')
                """, UUID.randomUUID(), organizationId, siteId, id,
                timestamp(validFrom), timestamp(validTo));
        return id;
    }

    private SpatialVersions insertPublishedSpatialVersions() {
        UUID coordinateSystemId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_coordinate_system (
                    id, organization_id, site_id, system_code, name,
                    coordinate_type, unit, unified,
                    min_x, max_x, min_y, max_y, min_z, max_z
                ) VALUES (?, ?, ?, ?, '运营统一坐标系',
                          'LOCAL_CARTESIAN', 'METER', TRUE,
                          -1000, 1000, -1000, 1000, -100, 100)
                """, coordinateSystemId, organizationId, siteId,
                "operation-coordinate-" + shortId(coordinateSystemId));
        UUID routeId = UUID.randomUUID();
        UUID routeVersionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_route (
                    id, organization_id, site_id, route_code
                ) VALUES (?, ?, ?, ?)
                """, routeId, organizationId, siteId,
                "operation-route-" + shortId(routeId));
        jdbcTemplate.update("""
                INSERT INTO mine_route_version (
                    id, route_id, organization_id, site_id,
                    coordinate_system_id, version_number, name, direction,
                    applicable_vehicle_types, valid_from, status,
                    content_hash, published_at
                ) VALUES (?, ?, ?, ?, ?, 1, '主运输路线', 'FORWARD',
                          '["HAUL_TRUCK"]'::jsonb, TIMESTAMPTZ '2026-01-01 00:00:00Z',
                          'PUBLISHED', ?, CURRENT_TIMESTAMP)
                """, routeVersionId, routeId, organizationId, siteId,
                coordinateSystemId, "sha256:" + "a".repeat(64));
        UUID loadVersionId = insertPublishedGeofenceVersion(
                coordinateSystemId, "operation-load", "装载区", "LOAD", "b");
        UUID unloadVersionId = insertPublishedGeofenceVersion(
                coordinateSystemId, "operation-unload", "卸载区", "UNLOAD", "c");
        return new SpatialVersions(routeVersionId, loadVersionId, unloadVersionId);
    }

    private UUID insertPublishedGeofenceVersion(
            UUID coordinateSystemId,
            String codePrefix,
            String name,
            String geofenceType,
            String hashCharacter
    ) {
        UUID geofenceId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_geofence (
                    id, organization_id, site_id, geofence_code
                ) VALUES (?, ?, ?, ?)
                """, geofenceId, organizationId, siteId,
                codePrefix + "-" + shortId(geofenceId));
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
                coordinateSystemId, name, geofenceType,
                "sha256:" + hashCharacter.repeat(64));
        return versionId;
    }

    private void insertAssignment(
            UUID id,
            UUID workOrderId,
            UUID vehicleId,
            Instant validFrom,
            Instant validTo
    ) {
        jdbcTemplate.update("""
                INSERT INTO mine_work_order_assignment (
                    id, organization_id, site_id, work_order_id,
                    vehicle_id, valid_from, valid_to
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, organizationId, siteId, workOrderId,
                vehicleId, timestamp(validFrom), timestamp(validTo));
    }

    private void insertAssignmentWithDriver(
            UUID id,
            UUID workOrderId,
            UUID vehicleId,
            UUID driverId,
            Instant validFrom,
            Instant validTo
    ) {
        jdbcTemplate.update("""
                INSERT INTO mine_work_order_assignment (
                    id, organization_id, site_id, work_order_id,
                    vehicle_id, driver_person_id, valid_from, valid_to
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, organizationId, siteId, workOrderId,
                vehicleId, driverId, timestamp(validFrom), timestamp(validTo));
    }

    private void insertOutbox(
            UUID id,
            String status,
            UUID leaseToken,
            Instant leasedUntil,
            String payload
    ) {
        jdbcTemplate.update("""
                INSERT INTO mine_operation_outbox (
                    id, organization_id, site_id, aggregate_type, aggregate_id,
                    event_type, event_version, payload, status,
                    lease_token, leased_until
                ) VALUES (?, ?, ?, 'WORK_ORDER', ?,
                          'realtime.operation.updated', 1, ?::jsonb, ?, ?, ?)
                """, id, organizationId, siteId, UUID.randomUUID(), payload,
                status, leaseToken, leasedUntil == null ? null : timestamp(leasedUntil));
    }

    private void assertRejected(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private record SpatialVersions(
            UUID routeVersionId,
            UUID loadGeofenceVersionId,
            UUID unloadGeofenceVersionId
    ) {
    }
}
