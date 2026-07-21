package com.bosco.mine.server.integration;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.mine.dto.MineOrganizationResponse;
import com.bosco.mine.server.modules.mine.operation.OperationController;
import com.bosco.mine.server.modules.mine.operation.OperationCorrectionService;
import com.bosco.mine.server.modules.mine.operation.OperationSummaryService;
import com.bosco.mine.server.modules.mine.operation.ShiftCommandService;
import com.bosco.mine.server.modules.mine.operation.WorkOrderCommandService;
import com.bosco.mine.server.modules.mine.operation.WorkOrderQueryService;
import com.bosco.mine.server.modules.mine.operation.dto.OperationCorrectionRequest;
import com.bosco.mine.server.modules.mine.operation.dto.OperationRecordPageResponse;
import com.bosco.mine.server.modules.mine.operation.dto.OperationRecordResponse;
import com.bosco.mine.server.modules.mine.operation.dto.OperationSummaryResponse;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 验证运营记录固定水位分页和生产统计口径。
 */
@Testcontainers
@ActiveProfiles("test")
@Import(OperationSummaryQueryIT.IdentityTestConfiguration.class)
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class OperationSummaryQueryIT {

    private static final LocalDate BUSINESS_DATE = LocalDate.parse("2026-07-20");
    private static final Instant SHIFT_START = Instant.parse("2026-07-20T20:00:00Z");
    private static final Instant SHIFT_END = Instant.parse("2026-07-21T08:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine_operation_summary")
            .withUsername("mine")
            .withPassword("mine-operation-summary-test-password");

    @Autowired
    private OperationSummaryService summaryService;

    @Autowired
    private OperationCorrectionService correctionService;

    @Autowired
    private MineScopeCommandService scopeCommandService;

    @Autowired
    private ShiftCommandService shiftCommandService;

    @Autowired
    private WorkOrderCommandService workOrderCommandService;

    @Autowired
    private WorkOrderQueryService workOrderQueryService;

    @Autowired
    private MutableMineRequestIdentity identity;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ownerId;
    private UUID deniedId;
    private UUID organizationId;
    private UUID siteId;
    private UUID shiftId;
    private UUID workOrderId;
    private UUID assignmentId;
    private UUID vehicleId;
    private UUID driverId;
    private SeededRecords records;

    @BeforeEach
    void 准备跨午夜作业和修正事实() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE sys_operation_log, sys_inbox_event, mine_organization CASCADE
                """);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'operation-summary-it-%'");
        ownerId = createUser("owner");
        deniedId = createUser("denied");
        identity.use(ownerId);
        MineOrganizationResponse organization = scopeCommandService.createOrganization(
                "operation-summary-org", "运营统计测试组织");
        organizationId = organization.id();
        siteId = scopeCommandService.createSite(
                organizationId, "operation-summary-site", "运营统计测试矿区",
                "UTC", null).id();
        createRunningWorkOrder();
        records = seedRecords();
    }

    @Test
    void 汇总必须使用营业日期有效投影并区分未知吨位和零吨() {
        long shiftOpenSeconds = setClosedShiftWindow();
        OperationSummaryResponse summary = summaryService.summarize(
                siteId, BUSINESS_DATE, BUSINESS_DATE);

        assertThat(summary.siteId()).isEqualTo(siteId);
        assertThat(summary.workOrderCount()).isEqualTo(1);
        assertThat(summary.plannedCycles()).isEqualTo(5L);
        assertThat(summary.effectiveCompletedCycles()).isEqualTo(3L);
        assertThat(summary.anomalyCycles()).isEqualTo(1L);
        assertThat(summary.completionRatePercent()).isEqualByComparingTo("60.00");
        assertThat(summary.plannedTons()).isEqualByComparingTo("100.000");
        assertThat(summary.actualTons()).isEqualByComparingTo("12.000");
        assertThat(summary.knownTonsCycles()).isEqualTo(2L);
        assertThat(summary.unknownTonsCycles()).isEqualTo(1L);
        assertThat(summary.totalCycleSeconds()).isEqualTo(3_600L);
        assertThat(summary.averageCycleSeconds()).isEqualByComparingTo("1200.000");
        assertThat(summary.averageLoadingSeconds()).isEqualByComparingTo("300.000");
        assertThat(summary.averageHaulingSeconds()).isEqualByComparingTo("600.000");
        assertThat(summary.averageUnloadingSeconds()).isEqualByComparingTo("300.000");
        assertThat(summary.breakdownsTruncated()).isFalse();

        assertThat(summary.workOrders()).singleElement().satisfies(progress -> {
            assertThat(progress.workOrderId()).isEqualTo(workOrderId);
            assertThat(progress.plannedCycles()).isEqualTo(5);
            assertThat(progress.effectiveCompletedCycles()).isEqualTo(3L);
            assertThat(progress.anomalyCycles()).isEqualTo(1L);
            assertThat(progress.actualTons()).isEqualByComparingTo("12.000");
            assertThat(progress.unknownTonsCycles()).isEqualTo(1L);
        });
        assertThat(summary.vehicles()).singleElement().satisfies(vehicle -> {
            assertThat(vehicle.vehicleId()).isEqualTo(vehicleId);
            assertThat(vehicle.activeSeconds()).isEqualTo(4_200L);
            assertThat(vehicle.shiftOpenSeconds()).isEqualTo(shiftOpenSeconds);
            assertThat(vehicle.shiftOpenSeconds()).isBetween(43_200L, 43_205L);
            assertThat(vehicle.utilizationRatePercent()).isEqualByComparingTo("9.72");
        });
        assertThat(summary.drivers()).singleElement().satisfies(driver -> {
            assertThat(driver.driverPersonId()).isEqualTo(driverId);
            assertThat(driver.effectiveCycles()).isEqualTo(4L);
            assertThat(driver.effectiveCompletedCycles()).isEqualTo(3L);
            assertThat(driver.anomalyCycles()).isEqualTo(1L);
            assertThat(driver.actualTons()).isEqualByComparingTo("12.000");
            assertThat(driver.unknownTonsCycles()).isEqualTo(2L);
        });

        assertThat(summaryService.summarize(
                siteId, BUSINESS_DATE.plusDays(1), BUSINESS_DATE.plusDays(1))
                .effectiveCompletedCycles()).isZero();
        assertThat(summaryService.summarize(
                siteId, BUSINESS_DATE.plusDays(1), BUSINESS_DATE.plusDays(1))
                .actualTons()).isNull();
    }

    @Test
    void 汇总存在未知计划趟数时不得计算整体完成率() {
        workOrderCommandService.createWorkOrder(
                siteId, new WorkOrderRequest(
                        shiftId, "SUMMARY-UNKNOWN-PLAN", "未知计划趟数作业", "HAULAGE",
                        SHIFT_START, SHIFT_END, null, null, 10, 0L));

        OperationSummaryResponse summary = summaryService.summarize(
                siteId, BUSINESS_DATE, BUSINESS_DATE);

        assertThat(summary.workOrderCount()).isEqualTo(2);
        assertThat(summary.plannedCycles()).isEqualTo(5L);
        assertThat(summary.effectiveCompletedCycles()).isEqualTo(3L);
        assertThat(summary.completionRatePercent()).isNull();
        assertThat(summary.workOrders())
                .filteredOn(progress -> "SUMMARY-UNKNOWN-PLAN".equals(progress.orderNumber()))
                .singleElement()
                .satisfies(progress -> {
                    assertThat(progress.plannedCycles()).isNull();
                    assertThat(progress.completionRatePercent()).isNull();
                });
    }

    @Test
    void 车辆利用率必须使用班次开放时长而非短分配窗口() {
        jdbcTemplate.update("""
                UPDATE mine_work_order_assignment
                SET valid_to = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                WHERE id = ?
                """, Timestamp.from(SHIFT_START.plusSeconds(7_200)), assignmentId);
        long shiftOpenSeconds = setClosedShiftWindow();

        OperationSummaryResponse summary = summaryService.summarize(
                siteId, BUSINESS_DATE, BUSINESS_DATE);

        assertThat(summary.vehicles()).singleElement().satisfies(vehicle -> {
            assertThat(vehicle.shiftOpenSeconds()).isEqualTo(shiftOpenSeconds);
            assertThat(vehicle.shiftOpenSeconds()).isBetween(43_200L, 43_205L);
            assertThat(vehicle.activeSeconds()).isEqualTo(4_200L);
            assertThat(vehicle.utilizationRatePercent()).isEqualByComparingTo("9.72");
        });
    }

    @Test
    void 记录分页必须固定业务水位排除作废并返回最新修正投影() {
        OperationRecordPageResponse first = summaryService.findRecords(
                siteId, BUSINESS_DATE, BUSINESS_DATE,
                null, null, null, null, null, null, null, 2);

        assertThat(first.items()).hasSize(2);
        assertThat(first.watermark()).isPositive();
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(first.items()).extracting(OperationRecordPageResponse.Item::recordId)
                .containsExactly(records.abortedId(), records.unknownTonsId());
        assertThat(first.items()).allSatisfy(item ->
                assertThat(item.businessDate()).isEqualTo(BUSINESS_DATE));

        UUID lateId = addRecord(
                "late-after-watermark", Instant.parse("2026-07-21T03:00:00Z"),
                "COMPLETED", new BigDecimal("8.000"), "MANUAL").id();
        OperationRecordPageResponse second = summaryService.findRecords(
                siteId, BUSINESS_DATE, BUSINESS_DATE,
                first.watermark(), first.nextCursor(),
                null, null, null, null, null, 2);

        assertThat(second.items()).extracting(OperationRecordPageResponse.Item::recordId)
                .containsExactly(records.zeroTonsId(), records.correctedId())
                .doesNotContain(records.voidedId(), lateId);
        assertThat(second.nextCursor()).isNull();
        OperationRecordPageResponse.Item corrected = second.items().getLast();
        assertThat(corrected.actualTons()).isEqualByComparingTo("12.000");
        assertThat(corrected.correctionVersion()).isEqualTo(1);
        assertThat(corrected.totalDurationSeconds()).isEqualTo(1_200L);
        assertThat(corrected.loadingSeconds()).isEqualTo(300L);
        assertThat(corrected.haulingSeconds()).isEqualTo(600L);
        assertThat(corrected.unloadingSeconds()).isEqualTo(300L);
    }

    @Test
    void 查询必须限制三十一天并绑定游标条件和矿区权限() {
        assertCode(() -> summaryService.summarize(
                        siteId, LocalDate.parse("2026-01-01"),
                        LocalDate.parse("2026-02-01")),
                "MINE-OPERATION-422-003");
        assertCode(() -> summaryService.findRecords(
                        siteId, BUSINESS_DATE, BUSINESS_DATE,
                        null, "invalid", null, null, null, null, null, 20),
                "MINE-OPERATION-422-004");

        OperationRecordPageResponse first = summaryService.findRecords(
                siteId, BUSINESS_DATE, BUSINESS_DATE,
                null, null, null, null, null, null, null, 1);
        assertCode(() -> summaryService.findRecords(
                        siteId, BUSINESS_DATE.minusDays(1), BUSINESS_DATE,
                        first.watermark(), first.nextCursor(),
                        null, null, null, null, null, 1),
                "MINE-OPERATION-422-004");

        identity.use(deniedId);
        assertCode(() -> summaryService.summarize(siteId, BUSINESS_DATE, BUSINESS_DATE),
                "MINE-SCOPE-403-002");
        identity.use(ownerId);
        assertPermission("findOperationRecords", "mine:operation:view");
        assertPermission("summarizeOperations", "mine:operation:view");
    }

    @Test
    void 记录页执行计划必须先限定营业日期候选再按聚合索引取业务序号() {
        jdbcTemplate.update("""
                INSERT INTO mine_operation_outbox (
                    id, organization_id, site_id, aggregate_type, aggregate_id,
                    event_type, event_version, payload
                )
                SELECT gen_random_uuid(), ?, ?, 'SHIFT_INSTANCE', gen_random_uuid(),
                       'realtime.operation.updated', 1, '{}'::jsonb
                FROM generate_series(1, 2000)
                """, organizationId, siteId);
        jdbcTemplate.execute("ANALYZE mine_operation_outbox");
        jdbcTemplate.execute("ANALYZE mine_operation_effective_record");
        jdbcTemplate.execute("ANALYZE mine_shift_instance");
        Long watermark = jdbcTemplate.queryForObject("""
                SELECT MAX(business_sequence) FROM mine_operation_outbox WHERE site_id = ?
                """, Long.class, siteId);

        List<String> lines = jdbcTemplate.query("""
                EXPLAIN (COSTS OFF)
                WITH candidate_records AS MATERIALIZED (
                    SELECT effective.operation_record_id
                    FROM mine_operation_effective_record effective
                    JOIN mine_shift_instance shift_instance
                      ON shift_instance.id = effective.shift_instance_id
                     AND shift_instance.organization_id = effective.organization_id
                     AND shift_instance.site_id = effective.site_id
                    WHERE effective.organization_id = ? AND effective.site_id = ?
                      AND shift_instance.business_date BETWEEN ? AND ?
                      AND effective.effective
                )
                SELECT candidate.operation_record_id, sequence.record_sequence
                FROM candidate_records candidate
                JOIN LATERAL (
                    SELECT MIN(outbox.business_sequence) AS record_sequence
                    FROM mine_operation_outbox outbox
                    WHERE outbox.organization_id = ? AND outbox.site_id = ?
                      AND outbox.aggregate_type = 'OPERATION_RECORD'
                      AND outbox.aggregate_id = candidate.operation_record_id
                      AND outbox.business_sequence <= ?
                ) sequence ON sequence.record_sequence IS NOT NULL
                """, (resultSet, rowNumber) -> resultSet.getString(1),
                organizationId, siteId, BUSINESS_DATE, BUSINESS_DATE,
                organizationId, siteId, watermark);
        String plan = String.join("\n", lines);

        assertThat(plan).contains("CTE candidate_records")
                .contains("idx_mine_operation_outbox_aggregate")
                .doesNotContain("Seq Scan on mine_operation_outbox");
    }

    private SeededRecords seedRecords() {
        OperationRecordResponse corrected = addRecord(
                "corrected", Instant.parse("2026-07-20T22:00:00Z"),
                "COMPLETED", new BigDecimal("10.000"), "MANUAL");
        corrected = correctionService.correctRecord(
                siteId, corrected.id(), "summary-amend-key",
                correction("AMEND", 0, new BigDecimal("12.000"), "复核磅单"));

        OperationRecordResponse voided = addRecord(
                "voided", Instant.parse("2026-07-20T23:00:00Z"),
                "COMPLETED", new BigDecimal("20.000"), "MANUAL");
        correctionService.correctRecord(
                siteId, voided.id(), "summary-void-key",
                correction("VOID", 0, null, "重复记录作废"));

        OperationRecordResponse zero = addRecord(
                "zero", Instant.parse("2026-07-21T00:30:00Z"),
                "COMPLETED", BigDecimal.ZERO, "MANUAL");
        OperationRecordResponse unknown = addRecord(
                "unknown", Instant.parse("2026-07-21T01:30:00Z"),
                "COMPLETED", null, null);
        OperationRecordResponse aborted = addRecord(
                "aborted", Instant.parse("2026-07-21T02:00:00Z"),
                "ABORTED", null, null, 600L);
        return new SeededRecords(
                corrected.id(), voided.id(), zero.id(), unknown.id(), aborted.id());
    }

    private OperationRecordResponse addRecord(
            String key,
            Instant startedAt,
            String status,
            BigDecimal tons,
            String tonsSource
    ) {
        return addRecord(key, startedAt, status, tons, tonsSource, 1_200L);
    }

    private OperationRecordResponse addRecord(
            String key,
            Instant startedAt,
            String status,
            BigDecimal tons,
            String tonsSource,
            long durationSeconds
    ) {
        Instant completedAt = startedAt.plusSeconds(durationSeconds);
        Instant loadCompletedAt = "COMPLETED".equals(status)
                ? startedAt.plusSeconds(300) : null;
        Instant unloadingStartedAt = "COMPLETED".equals(status)
                ? startedAt.plusSeconds(900) : null;
        return correctionService.addRecord(
                siteId, "summary-" + key + "-key",
                new OperationCorrectionRequest(
                        "ADD", workOrderId, assignmentId, status,
                        startedAt, loadCompletedAt, unloadingStartedAt, completedAt,
                        new BigDecimal("1000.000"), tons, tonsSource,
                        null, "运营统计测试补录"));
    }

    private OperationCorrectionRequest correction(
            String action,
            int version,
            BigDecimal tons,
            String reason
    ) {
        return new OperationCorrectionRequest(
                action, null, null, null,
                null, null, null, null,
                "AMEND".equals(action) ? new BigDecimal("1000.000") : null,
                tons, "AMEND".equals(action) ? "MANUAL" : null,
                version, reason);
    }

    private void createRunningWorkOrder() {
        var definition = shiftCommandService.createDefinition(
                siteId, new ShiftDefinitionRequest(
                        "operation-summary-shift", "运营统计夜班",
                        LocalTime.of(20, 0), LocalTime.of(8, 0), true,
                        LocalDate.parse("2026-01-01"), null, 0L));
        ShiftInstanceResponse planned = shiftCommandService.createInstance(
                siteId, new ShiftInstanceRequest(definition.id(), BUSINESS_DATE, null));
        ShiftInstanceResponse opened = shiftCommandService.openInstance(
                siteId, planned.id(), planned.version());
        shiftId = opened.id();
        SpatialVersions spatial = insertPublishedSpatialVersions();
        vehicleId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_vehicle (
                    id, organization_id, site_id, vehicle_code, name, vehicle_type
                ) VALUES (?, ?, ?, 'summary-truck', '统计测试矿卡', 'HAUL_TRUCK')
                """, vehicleId, organizationId, siteId);
        driverId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_person (
                    id, organization_id, person_code, name, person_type,
                    phone_ciphertext, phone_masked,
                    certificate_ciphertext, certificate_masked
                ) VALUES (?, ?, 'summary-driver', '统计测试司机', 'DRIVER',
                          'v2:phone:cipher', '138****0000',
                          'v2:certificate:cipher', '证件****0000')
                """, driverId, organizationId);

        WorkOrderResponse draft = workOrderCommandService.createWorkOrder(
                siteId, new WorkOrderRequest(
                        shiftId, "SUMMARY-HAUL-001", "跨午夜运输作业", "HAULAGE",
                        SHIFT_START, SHIFT_END, 5, new BigDecimal("100.000"), 50, 0L));
        WorkOrderResponse assigned = workOrderCommandService.assignWorkOrder(
                siteId, draft.id(), new WorkOrderAssignmentRequest(
                        vehicleId, null, SHIFT_START, SHIFT_END,
                        "运营统计测试调度", draft.version()));
        assignmentId = assigned.assignments().getFirst().id();
        jdbcTemplate.update("""
                UPDATE mine_work_order_assignment
                SET driver_person_id = ?, updated_at = CURRENT_TIMESTAMP,
                    updated_by = ?, version = version + 1
                WHERE id = ?
                """, driverId, ownerId, assignmentId);
        WorkOrderResponse current = workOrderQueryService.findWorkOrder(siteId, draft.id());
        WorkOrderResponse released = workOrderCommandService.releaseWorkOrder(
                siteId, current.id(), new WorkOrderActionRequest(
                        current.version(), spatial.routeVersionId(),
                        spatial.loadVersionId(), spatial.unloadVersionId(), null));
        workOrderId = workOrderCommandService.startWorkOrder(
                siteId, released.id(), new WorkOrderActionRequest(
                        released.version(), null, null, null, null)).id();
    }

    private long setClosedShiftWindow() {
        jdbcTemplate.update("""
                UPDATE mine_shift_instance
                SET created_at = CURRENT_TIMESTAMP - INTERVAL '13 hours',
                    actual_start_at = CURRENT_TIMESTAMP - INTERVAL '12 hours',
                    opened_at = CURRENT_TIMESTAMP - INTERVAL '12 hours',
                    updated_at = CURRENT_TIMESTAMP, updated_by = ?, version = version + 1
                WHERE id = ?
                """, ownerId, shiftId);
        WorkOrderResponse current = workOrderQueryService.findWorkOrder(siteId, workOrderId);
        workOrderCommandService.completeWorkOrder(
                siteId, workOrderId,
                new WorkOrderActionRequest(current.version(), null, null, null, null));
        shiftCommandService.closeInstance(siteId, shiftId);
        Long seconds = jdbcTemplate.queryForObject("""
                SELECT EXTRACT(EPOCH FROM (actual_end_at - actual_start_at))::BIGINT
                FROM mine_shift_instance WHERE id = ?
                """, Long.class, shiftId);
        return seconds == null ? 0L : seconds;
    }

    private SpatialVersions insertPublishedSpatialVersions() {
        UUID coordinateSystemId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_coordinate_system (
                    id, organization_id, site_id, system_code, name,
                    coordinate_type, unit, unified,
                    min_x, max_x, min_y, max_y, min_z, max_z
                ) VALUES (?, ?, ?, 'summary-coordinate', '统计测试坐标系',
                          'LOCAL_CARTESIAN', 'METER', TRUE,
                          -1000, 1000, -1000, 1000, -100, 100)
                """, coordinateSystemId, organizationId, siteId);
        UUID routeId = UUID.randomUUID();
        UUID routeVersionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_route (id, organization_id, site_id, route_code)
                VALUES (?, ?, ?, 'summary-route')
                """, routeId, organizationId, siteId);
        jdbcTemplate.update("""
                INSERT INTO mine_route_version (
                    id, route_id, organization_id, site_id, coordinate_system_id,
                    version_number, name, direction, applicable_vehicle_types,
                    valid_from, status, content_hash, published_at
                ) VALUES (?, ?, ?, ?, ?, 1, '统计测试路线', 'FORWARD',
                          '["HAUL_TRUCK"]'::jsonb, TIMESTAMPTZ '2026-01-01 00:00:00Z',
                          'PUBLISHED', ?, CURRENT_TIMESTAMP)
                """, routeVersionId, routeId, organizationId, siteId,
                coordinateSystemId, "sha256:" + "a".repeat(64));
        UUID loadVersion = insertGeofence(
                coordinateSystemId, "summary-load", "统计装载区", "LOAD", "b");
        UUID unloadVersion = insertGeofence(
                coordinateSystemId, "summary-unload", "统计卸载区", "UNLOAD", "c");
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
                """, id, "operation-summary-it-" + suffix,
                "运营统计测试用户" + suffix);
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

    /** 为集成测试提供可切换请求身份。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IdentityTestConfiguration {

        /** 返回可切换测试身份。 */
        @Bean
        @Primary
        MutableMineRequestIdentity mutableMineRequestIdentity() {
            return new MutableMineRequestIdentity();
        }
    }

    /** 单线程集成测试当前身份。 */
    static final class MutableMineRequestIdentity implements MineRequestIdentity {
        private UUID userId;

        /** 切换身份。 */
        void use(UUID value) {
            userId = value;
        }

        /** 返回当前身份。 */
        @Override
        public UUID currentUserId() {
            return userId;
        }
    }

    /** 已发布空间版本。 */
    private record SpatialVersions(
            UUID routeVersionId,
            UUID loadVersionId,
            UUID unloadVersionId
    ) {
    }

    /** 汇总查询测试使用的记录标识。 */
    private record SeededRecords(
            UUID correctedId,
            UUID voidedId,
            UUID zeroTonsId,
            UUID unknownTonsId,
            UUID abortedId
    ) {
    }
}
