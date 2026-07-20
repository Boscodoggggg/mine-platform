package com.bosco.mine.server.integration;

import com.bosco.mine.messaging.realtime.RealtimeSpatialEvent;
import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.mine.operation.runtime.OperationAutomationWorker;
import com.bosco.mine.server.modules.mine.operation.runtime.OperationEventQueue;
import com.bosco.mine.server.modules.mine.operation.runtime.OperationRuntimeCoordinator;
import com.bosco.mine.server.modules.mine.operation.runtime.OperationRuntimeRepository;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 验证空间事件到运输执行事实的事务性自动化闭环。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.datasource.hikari.maximum-pool-size=4"
)
class HaulageOperationAutomationIT {

    private static final Instant BASE_TIME = Instant.parse("2026-07-20T01:00:00Z");
    private static final Duration STEP_TIMEOUT = Duration.ofHours(1);
    private static final AtomicInteger EVENT_SEQUENCE = new AtomicInteger();
    private static final OperationEventQueue.ClaimPolicy POLICY =
            new OperationEventQueue.ClaimPolicy(
                    Duration.ZERO, Duration.ofMinutes(1), Duration.ofSeconds(30), 4,
                    Duration.ofMillis(1), Duration.ofSeconds(1));

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine_haulage_automation")
            .withUsername("mine")
            .withPassword("mine-haulage-automation-test-password");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OperationEventQueue eventQueue;

    @Autowired
    private OperationAutomationWorker worker;

    @Autowired
    private OperationRuntimeCoordinator coordinator;

    @Autowired
    private OperationAuditService auditService;

    @Autowired
    private OperationRuntimeRepository runtimeRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void 清理运营自动化事实() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE sys_operation_log, sys_inbox_event, mine_organization CASCADE
                """);
    }

    @Test
    void 唯一运行分配应按业务时间排序完成一趟并原子形成全部事实() {
        RuntimeContext context = createRunningContext("complete");
        List<RealtimeSpatialEvent> events = fullCycle(context);
        enqueue(events.get(4), events.get(2), events.get(0), events.get(3), events.get(1));

        assertThat(worker.drainBounded(POLICY, STEP_TIMEOUT, 10)).isEqualTo(5);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT cycle_number, cycle_status, last_ingest_sequence,
                       evidence_snapshot = '{}'::jsonb AS evidence_cleared
                FROM mine_operation_runtime_state WHERE assignment_id = ?
                """, context.assignmentId()))
                .containsEntry("cycle_number", 2L)
                .containsEntry("cycle_status", "WAITING_LOAD")
                .containsEntry("last_ingest_sequence", 5L)
                .containsEntry("evidence_cleared", true);
        Map<String, Object> record = jdbcTemplate.queryForMap("""
                SELECT record_status, record_source, cycle_number,
                       vehicle_id, route_version_id,
                       load_geofence_version_id, unload_geofence_version_id,
                       started_at, load_completed_at, unloading_started_at, completed_at
                FROM mine_operation_record WHERE assignment_id = ?
                """, context.assignmentId());
        assertThat(record)
                .containsEntry("record_status", "COMPLETED")
                .containsEntry("record_source", "AUTOMATIC")
                .containsEntry("cycle_number", 1L)
                .containsEntry("vehicle_id", context.vehicleId())
                .containsEntry("route_version_id", context.routeVersionId())
                .containsEntry("load_geofence_version_id", context.loadVersionId())
                .containsEntry("unload_geofence_version_id", context.unloadVersionId())
                .containsEntry("started_at", timestamp(BASE_TIME))
                .containsEntry("load_completed_at", timestamp(BASE_TIME.plusSeconds(60)))
                .containsEntry("unloading_started_at", timestamp(BASE_TIME.plusSeconds(180)))
                .containsEntry("completed_at", timestamp(BASE_TIME.plusSeconds(240)));
        assertThat(jdbcTemplate.queryForList("""
                SELECT evidence_role FROM mine_operation_evidence
                ORDER BY occurred_at, ingest_sequence
                """, String.class)).containsExactly(
                "LOAD_ENTER", "LOAD_DWELL", "LOAD_EXIT",
                "UNLOAD_ENTER", "UNLOAD_DWELL");
        assertThat(jdbcTemplate.queryForMap("""
                SELECT effective, record_status, correction_version,
                       started_at, completed_at
                FROM mine_operation_effective_record
                """))
                .containsEntry("effective", true)
                .containsEntry("record_status", "COMPLETED")
                .containsEntry("correction_version", 0)
                .containsEntry("started_at", timestamp(BASE_TIME))
                .containsEntry("completed_at", timestamp(BASE_TIME.plusSeconds(240)));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM mine_operation_outbox
                WHERE site_id = ? AND status = 'PENDING'
                """, Integer.class, context.siteId())).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                WHERE module = 'MINE_OPERATION'
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM mine_operation_spatial_event_queue
                WHERE status = 'PROCESSED'
                """, Integer.class)).isEqualTo(5);
    }

    @Test
    void 重复和迟到事件不得重复推进且迟到事件必须保留候选() {
        RuntimeContext context = createRunningContext("late");
        RealtimeSpatialEvent enter = event(
                context, context.loadGeofenceId(), context.loadVersionId(),
                RealtimeSpatialEvent.EventType.ENTER, BASE_TIME.plusSeconds(10), 2);
        enqueue(enter);
        assertThat(worker.drainBounded(POLICY, STEP_TIMEOUT, 1)).isEqualTo(1);
        assertThat(eventQueue.enqueue(enter)).isEqualTo(OperationEventQueue.EnqueueResult.DUPLICATE);

        RealtimeSpatialEvent late = event(
                context, context.loadGeofenceId(), context.loadVersionId(),
                RealtimeSpatialEvent.EventType.DWELL, BASE_TIME, 1);
        enqueue(late);
        assertThat(worker.drainBounded(POLICY, STEP_TIMEOUT, 1)).isEqualTo(1);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT cycle_status, last_spatial_event_id, last_ingest_sequence
                FROM mine_operation_runtime_state WHERE assignment_id = ?
                """, context.assignmentId()))
                .containsEntry("cycle_status", "LOADING")
                .containsEntry("last_spatial_event_id", enter.eventId())
                .containsEntry("last_ingest_sequence", 2L);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT candidate_type, reason_code, status
                FROM mine_operation_candidate WHERE spatial_event_id = ?
                """, late.eventId()))
                .containsEntry("candidate_type", "OUT_OF_ORDER")
                .containsEntry("reason_code", "TOO_LATE")
                .containsEntry("status", "PENDING");
    }

    @Test
    void 事件未携带车辆时必须按发生时间从标签历史绑定恢复车辆() {
        RuntimeContext context = createRunningContext("tag-history");
        RealtimeSpatialEvent resolved = event(
                context, context.loadGeofenceId(), context.loadVersionId(),
                RealtimeSpatialEvent.EventType.ENTER, BASE_TIME, 1);
        RealtimeSpatialEvent tagOnly = new RealtimeSpatialEvent(
                resolved.eventId(), resolved.organizationId(), resolved.siteId(),
                resolved.zoneType(), resolved.zoneId(), resolved.zoneVersionId(),
                resolved.tagId(), null, null, resolved.eventType(),
                resolved.anchorLocationEventId(), resolved.previousState(),
                resolved.currentState(), resolved.confirmationPoints(),
                resolved.ingestSequence(), resolved.runtimeRevision(),
                resolved.occurredAt(), resolved.evidence());

        enqueue(tagOnly);

        assertThat(worker.drainBounded(POLICY, STEP_TIMEOUT, 1)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT cycle_status, last_spatial_event_id
                FROM mine_operation_runtime_state WHERE assignment_id = ?
                """, context.assignmentId()))
                .containsEntry("cycle_status", "LOADING")
                .containsEntry("last_spatial_event_id", tagOnly.eventId());
    }

    @Test
    void 事件车辆与发生时标签绑定不一致必须进入固定身份候选() {
        RuntimeContext context = createRunningContext("identity-mismatch");
        UUID wrongVehicleId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_vehicle (
                    id, organization_id, site_id, vehicle_code, name, vehicle_type
                ) VALUES (?, ?, ?, ?, '错误身份矿卡', 'HAUL_TRUCK')
                """, wrongVehicleId, context.organizationId(), context.siteId(),
                "wrong-" + shortId(wrongVehicleId));
        RealtimeSpatialEvent resolved = event(
                context, context.loadGeofenceId(), context.loadVersionId(),
                RealtimeSpatialEvent.EventType.ENTER, BASE_TIME, 1);
        RealtimeSpatialEvent mismatch = new RealtimeSpatialEvent(
                resolved.eventId(), resolved.organizationId(), resolved.siteId(),
                resolved.zoneType(), resolved.zoneId(), resolved.zoneVersionId(),
                resolved.tagId(), wrongVehicleId, null, resolved.eventType(),
                resolved.anchorLocationEventId(), resolved.previousState(),
                resolved.currentState(), resolved.confirmationPoints(),
                resolved.ingestSequence(), resolved.runtimeRevision(),
                resolved.occurredAt(), resolved.evidence());

        enqueue(mismatch);
        assertThat(worker.drainBounded(POLICY, STEP_TIMEOUT, 1)).isEqualTo(1);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT candidate_type, reason_code
                FROM mine_operation_candidate WHERE spatial_event_id = ?
                """, mismatch.eventId()))
                .containsEntry("candidate_type", "VEHICLE_MISMATCH")
                .containsEntry("reason_code", "EVENT_VEHICLE_TAG_MISMATCH");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_operation_runtime_state",
                Integer.class)).isZero();
    }

    @Test
    void 作业暂停判断必须使用事件发生时状态而不是消费时当前状态() {
        RuntimeContext pausedAfterEvent = createRunningContext("pause-after-event");
        jdbcTemplate.update("""
                UPDATE mine_work_order
                SET status = 'PAUSED', paused_at = ?, paused_by = ?,
                    updated_at = ?, updated_by = ?, version = version + 1
                WHERE id = ?
                """, timestamp(BASE_TIME.plusSeconds(30)), pausedAfterEvent.userId(),
                timestamp(BASE_TIME.plusSeconds(30)), pausedAfterEvent.userId(),
                pausedAfterEvent.workOrderId());
        RealtimeSpatialEvent beforePause = event(
                pausedAfterEvent, pausedAfterEvent.loadGeofenceId(),
                pausedAfterEvent.loadVersionId(), RealtimeSpatialEvent.EventType.ENTER,
                BASE_TIME, 1);
        enqueue(beforePause);
        worker.drainBounded(POLICY, STEP_TIMEOUT, 1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT cycle_status FROM mine_operation_runtime_state
                WHERE assignment_id = ?
                """, String.class, pausedAfterEvent.assignmentId())).isEqualTo("LOADING");

        RuntimeContext resumedAfterEvent = createRunningContext("resume-after-event");
        jdbcTemplate.update("""
                UPDATE mine_work_order
                SET status = 'PAUSED', paused_at = ?, paused_by = ?,
                    updated_at = ?, updated_by = ?, version = version + 1
                WHERE id = ?
                """, timestamp(BASE_TIME.plusSeconds(30)), resumedAfterEvent.userId(),
                timestamp(BASE_TIME.plusSeconds(30)), resumedAfterEvent.userId(),
                resumedAfterEvent.workOrderId());
        jdbcTemplate.update("""
                UPDATE mine_work_order
                SET status = 'RUNNING', updated_at = ?, updated_by = ?,
                    version = version + 1
                WHERE id = ?
                """, timestamp(BASE_TIME.plusSeconds(90)), resumedAfterEvent.userId(),
                resumedAfterEvent.workOrderId());
        RealtimeSpatialEvent duringPause = event(
                resumedAfterEvent, resumedAfterEvent.loadGeofenceId(),
                resumedAfterEvent.loadVersionId(), RealtimeSpatialEvent.EventType.ENTER,
                BASE_TIME.plusSeconds(60), 1);
        enqueue(duringPause);
        worker.drainBounded(POLICY, STEP_TIMEOUT, 1);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT candidate_type, reason_code
                FROM mine_operation_candidate WHERE spatial_event_id = ?
                """, duringPause.eventId()))
                .containsEntry("candidate_type", "PAUSED_WORK_ORDER")
                .containsEntry("reason_code", "WORK_ORDER_PAUSED_AT_EVENT_TIME");
    }

    @Test
    void 自动匹配锁必须阻塞并发暂停直到状态推进事务结束() throws Exception {
        RuntimeContext context = createRunningContext("pause-lock");
        RealtimeSpatialEvent signal = event(
                context, context.loadGeofenceId(), context.loadVersionId(),
                RealtimeSpatialEvent.EventType.ENTER, BASE_TIME, 1);
        enqueue(signal);
        OperationEventQueue.ClaimedEvent claim = eventQueue.claimNext(POLICY).orElseThrow();
        CountDownLatch matchLocked = new CountDownLatch(1);
        CountDownLatch releaseMatch = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> matcher = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        runtimeRepository.lockMatches(claim);
                        matchLocked.countDown();
                        try {
                            assertThat(releaseMatch.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(exception);
                        }
                    }));
            assertThat(matchLocked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> pausing = executor.submit(() -> jdbcTemplate.update("""
                    UPDATE mine_work_order
                    SET status = 'PAUSED', paused_at = CURRENT_TIMESTAMP,
                        paused_by = ?, updated_at = CURRENT_TIMESTAMP,
                        updated_by = ?, version = version + 1
                    WHERE id = ?
                    """, context.userId(), context.userId(), context.workOrderId()));

            assertThatThrownBy(() -> pausing.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseMatch.countDown();
            matcher.get(5, TimeUnit.SECONDS);
            assertThat(pausing.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            releaseMatch.countDown();
        }
    }

    @Test
    void 首次匹配后新增分配并暂停也不得越过父级锁自动推进() throws Exception {
        RuntimeContext original = createRunningContext("phantom-original");
        WorkOrderParent replacement = createParallelRunningWorkOrder(
                original, "phantom-replacement");
        RealtimeSpatialEvent signal = event(
                original, original.loadGeofenceId(), original.loadVersionId(),
                RealtimeSpatialEvent.EventType.ENTER, BASE_TIME, 1);
        enqueue(signal);
        OperationEventQueue.ClaimedEvent claim = eventQueue.claimNext(POLICY).orElseThrow();
        CountDownLatch originalShiftLocked = new CountDownLatch(1);
        CountDownLatch releaseOriginalShift = new CountDownLatch(1);
        CountDownLatch replacementPaused = new CountDownLatch(1);
        CountDownLatch releaseReplacementPause = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            Future<?> blocker = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        jdbcTemplate.queryForObject("""
                                SELECT id FROM mine_shift_instance
                                WHERE id = ? FOR UPDATE
                                """, UUID.class, original.shiftId());
                        originalShiftLocked.countDown();
                        awaitLatch(releaseOriginalShift);
                    }));
            assertThat(originalShiftLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<OperationRuntimeCoordinator.ProcessResult> matcher = executor.submit(
                    () -> coordinator.process(claim, STEP_TIMEOUT));
            assertThatThrownBy(() -> matcher.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            jdbcTemplate.update("""
                    UPDATE mine_work_order_assignment
                    SET status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP,
                        updated_by = ?, version = version + 1
                    WHERE id = ?
                    """, original.userId(), original.assignmentId());
            UUID replacementAssignmentId = UUID.randomUUID();
            jdbcTemplate.update("""
                    INSERT INTO mine_work_order_assignment (
                        id, organization_id, site_id, work_order_id,
                        vehicle_id, valid_from, valid_to, created_by, updated_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, replacementAssignmentId, original.organizationId(), original.siteId(),
                    replacement.workOrderId(), original.vehicleId(),
                    timestamp(BASE_TIME.minusSeconds(1800)),
                    timestamp(BASE_TIME.plusSeconds(11 * 3600)),
                    original.userId(), original.userId());

            Future<?> pausing = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        jdbcTemplate.update("""
                                UPDATE mine_work_order
                                SET status = 'PAUSED', paused_at = ?, paused_by = ?,
                                    updated_at = ?, updated_by = ?, version = version + 1
                                WHERE id = ?
                                """, timestamp(BASE_TIME.minusSeconds(1)), original.userId(),
                                timestamp(BASE_TIME.minusSeconds(1)), original.userId(),
                                replacement.workOrderId());
                        replacementPaused.countDown();
                        awaitLatch(releaseReplacementPause);
                    }));
            assertThat(replacementPaused.await(5, TimeUnit.SECONDS)).isTrue();

            releaseOriginalShift.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> matcher.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseReplacementPause.countDown();
            pausing.get(5, TimeUnit.SECONDS);
            assertThat(matcher.get(5, TimeUnit.SECONDS))
                    .isEqualTo(OperationRuntimeCoordinator.ProcessResult.CANDIDATE);

            assertThat(jdbcTemplate.queryForMap("""
                    SELECT candidate_type, reason_code, work_order_id, assignment_id
                    FROM mine_operation_candidate WHERE spatial_event_id = ?
                    """, signal.eventId()))
                    .containsEntry("candidate_type", "PAUSED_WORK_ORDER")
                    .containsEntry("reason_code", "WORK_ORDER_PAUSED_AT_EVENT_TIME")
                    .containsEntry("work_order_id", replacement.workOrderId())
                    .containsEntry("assignment_id", replacementAssignmentId);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM mine_operation_runtime_state
                    WHERE work_order_id = ?
                    """, Integer.class, replacement.workOrderId())).isZero();
        } finally {
            releaseOriginalShift.countDown();
            releaseReplacementPause.countDown();
        }
    }

    @Test
    void 暂停作业和无唯一归属事件必须进入候选且多矿之间不能串单() {
        RuntimeContext running = createRunningContext("running");
        RuntimeContext otherSite = createRunningContext("other-site");
        jdbcTemplate.update("""
                UPDATE mine_work_order
                SET status = 'PAUSED', paused_at = ?, paused_by = ?,
                    updated_at = ?, updated_by = ?, version = version + 1
                WHERE id = ?
                """, timestamp(BASE_TIME.minusSeconds(1)), running.userId(),
                timestamp(BASE_TIME.minusSeconds(1)), running.userId(),
                running.workOrderId());
        RealtimeSpatialEvent paused = event(
                running, running.loadGeofenceId(), running.loadVersionId(),
                RealtimeSpatialEvent.EventType.ENTER, BASE_TIME, 1);
        enqueue(paused);
        assertThat(worker.drainBounded(POLICY, STEP_TIMEOUT, 1)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT candidate_type, reason_code FROM mine_operation_candidate
                WHERE spatial_event_id = ?
                """, paused.eventId()))
                .containsEntry("candidate_type", "PAUSED_WORK_ORDER")
                .containsEntry("reason_code", "WORK_ORDER_PAUSED_AT_EVENT_TIME");

        jdbcTemplate.update("""
                UPDATE mine_work_order_assignment SET status = 'CANCELLED'
                WHERE id = ?
                """, otherSite.assignmentId());
        RealtimeSpatialEvent unmatched = event(
                otherSite, otherSite.loadGeofenceId(), otherSite.loadVersionId(),
                RealtimeSpatialEvent.EventType.ENTER, BASE_TIME.plusSeconds(1), 1);
        enqueue(unmatched);
        assertThat(worker.drainBounded(POLICY, STEP_TIMEOUT, 1)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT site_id, work_order_id, assignment_id,
                       candidate_type, reason_code
                FROM mine_operation_candidate WHERE spatial_event_id = ?
                """, unmatched.eventId()))
                .containsEntry("site_id", otherSite.siteId())
                .containsEntry("candidate_type", "AMBIGUOUS_MATCH")
                .containsEntry("reason_code", "NO_UNIQUE_ACTIVE_ASSIGNMENT")
                .containsEntry("work_order_id", null)
                .containsEntry("assignment_id", null);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM mine_operation_runtime_state
                WHERE work_order_id = ?
                """, Integer.class, running.workOrderId())).isZero();
    }

    @Test
    void 超过步骤时限的下一信号必须转候选且不得破坏运行态() {
        RuntimeContext context = createRunningContext("timeout");
        RealtimeSpatialEvent enter = event(
                context, context.loadGeofenceId(), context.loadVersionId(),
                RealtimeSpatialEvent.EventType.ENTER, BASE_TIME, 1);
        enqueue(enter);
        worker.drainBounded(POLICY, Duration.ofMinutes(30), 1);
        RealtimeSpatialEvent overdue = event(
                context, context.loadGeofenceId(), context.loadVersionId(),
                RealtimeSpatialEvent.EventType.DWELL, BASE_TIME.plusSeconds(3600), 2);
        enqueue(overdue);

        assertThat(worker.drainBounded(POLICY, Duration.ofMinutes(30), 1)).isEqualTo(1);

        assertThat(jdbcTemplate.queryForMap("""
                SELECT cycle_status, last_spatial_event_id
                FROM mine_operation_runtime_state WHERE assignment_id = ?
                """, context.assignmentId()))
                .containsEntry("cycle_status", "LOADING")
                .containsEntry("last_spatial_event_id", enter.eventId());
        assertThat(jdbcTemplate.queryForMap("""
                SELECT candidate_type, reason_code FROM mine_operation_candidate
                WHERE spatial_event_id = ?
                """, overdue.eventId()))
                .containsEntry("candidate_type", "UNEXPECTED_EVENT")
                .containsEntry("reason_code", "STEP_TIMEOUT");
    }

    @Test
    void 处理租约失效必须回滚状态推进并允许新租约恢复() {
        RuntimeContext context = createRunningContext("lease");
        RealtimeSpatialEvent enter = event(
                context, context.loadGeofenceId(), context.loadVersionId(),
                RealtimeSpatialEvent.EventType.ENTER, BASE_TIME, 1);
        enqueue(enter);
        OperationEventQueue.ClaimedEvent claim = eventQueue.claimNext(POLICY).orElseThrow();
        jdbcTemplate.update("""
                UPDATE mine_operation_spatial_event_queue
                SET leased_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, claim.queueId());

        assertThatThrownBy(() -> coordinator.process(claim, STEP_TIMEOUT))
                .isInstanceOf(OperationRuntimeCoordinator.LeaseLostException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_operation_runtime_state",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_operation_outbox",
                Integer.class)).isZero();

        assertThat(worker.drainBounded(POLICY, STEP_TIMEOUT, 1)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT cycle_status FROM mine_operation_runtime_state
                WHERE assignment_id = ?
                """, String.class, context.assignmentId())).isEqualTo("LOADING");
    }

    @Test
    void 服务重启后必须从持久运行态继续完成当前趟次() {
        RuntimeContext context = createRunningContext("restart");
        List<RealtimeSpatialEvent> events = fullCycle(context);
        enqueue(events.toArray(RealtimeSpatialEvent[]::new));
        assertThat(worker.drainBounded(POLICY, STEP_TIMEOUT, 2)).isEqualTo(2);

        OperationRuntimeCoordinator restartedCoordinator = new OperationRuntimeCoordinator(
                new OperationRuntimeRepository(jdbcTemplate), eventQueue, auditService);
        OperationAutomationWorker restartedWorker =
                new OperationAutomationWorker(eventQueue, restartedCoordinator);

        assertThat(restartedWorker.drainBounded(POLICY, STEP_TIMEOUT, 10)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_operation_record",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT cycle_number FROM mine_operation_runtime_state
                WHERE assignment_id = ?
                """, Long.class, context.assignmentId())).isEqualTo(2L);
    }

    private void enqueue(RealtimeSpatialEvent... events) {
        for (RealtimeSpatialEvent event : events) {
            assertThat(eventQueue.enqueue(event))
                    .isEqualTo(OperationEventQueue.EnqueueResult.ACCEPTED);
            jdbcTemplate.update("""
                    UPDATE mine_operation_spatial_event_queue
                    SET received_at = CURRENT_TIMESTAMP - INTERVAL '5 minutes'
                    WHERE event_id = ?
                    """, event.eventId());
        }
    }

    private List<RealtimeSpatialEvent> fullCycle(RuntimeContext context) {
        return List.of(
                event(context, context.loadGeofenceId(), context.loadVersionId(),
                        RealtimeSpatialEvent.EventType.ENTER, BASE_TIME, 1),
                event(context, context.loadGeofenceId(), context.loadVersionId(),
                        RealtimeSpatialEvent.EventType.DWELL, BASE_TIME.plusSeconds(60), 2),
                event(context, context.loadGeofenceId(), context.loadVersionId(),
                        RealtimeSpatialEvent.EventType.EXIT, BASE_TIME.plusSeconds(120), 3),
                event(context, context.unloadGeofenceId(), context.unloadVersionId(),
                        RealtimeSpatialEvent.EventType.ENTER, BASE_TIME.plusSeconds(180), 4),
                event(context, context.unloadGeofenceId(), context.unloadVersionId(),
                        RealtimeSpatialEvent.EventType.DWELL, BASE_TIME.plusSeconds(240), 5));
    }

    private RealtimeSpatialEvent event(
            RuntimeContext context,
            UUID geofenceId,
            UUID geofenceVersionId,
            RealtimeSpatialEvent.EventType eventType,
            Instant occurredAt,
            long ingestSequence
    ) {
        RealtimeSpatialEvent.StableState previous = eventType == RealtimeSpatialEvent.EventType.ENTER
                ? RealtimeSpatialEvent.StableState.OUTSIDE
                : RealtimeSpatialEvent.StableState.INSIDE;
        RealtimeSpatialEvent.StableState current = eventType == RealtimeSpatialEvent.EventType.EXIT
                ? RealtimeSpatialEvent.StableState.OUTSIDE
                : RealtimeSpatialEvent.StableState.INSIDE;
        return new RealtimeSpatialEvent(
                uuidV7(EVENT_SEQUENCE.incrementAndGet()),
                context.organizationId(), context.siteId(),
                RealtimeSpatialEvent.ZoneType.GEOFENCE,
                geofenceId, geofenceVersionId, context.tagId(), context.vehicleId(), null,
                eventType, UUID.randomUUID(), previous, current,
                2, ingestSequence, 1, occurredAt,
                new RealtimeSpatialEvent.Evidence(
                        10D, 20D, null, current.name(), "GOOD", null,
                        "sha256:" + "a".repeat(64)));
    }

    private RuntimeContext createRunningContext(String suffix) {
        UUID organizationId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID vehicleId = UUID.randomUUID();
        UUID tagId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_organization (id, organization_code, name)
                VALUES (?, ?, '自动运输测试组织')
                """, organizationId, "auto-org-" + shortId(organizationId));
        jdbcTemplate.update("""
                INSERT INTO mine_site (id, organization_id, site_code, name, timezone)
                VALUES (?, ?, ?, '自动运输测试矿区', 'Asia/Shanghai')
                """, siteId, organizationId, "auto-site-" + shortId(siteId));
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password_hash, display_name)
                VALUES (?, ?, 'bcrypt:test', '自动运输测试用户')
                """, userId, "auto-user-" + shortId(userId));
        jdbcTemplate.update("""
                INSERT INTO mine_vehicle (
                    id, organization_id, site_id, vehicle_code, name, vehicle_type
                ) VALUES (?, ?, ?, ?, '自动运输矿卡', 'HAUL_TRUCK')
                """, vehicleId, organizationId, siteId, "truck-" + suffix);
        jdbcTemplate.update("""
                INSERT INTO mine_location_tag (
                    id, organization_id, site_id, tag_code
                ) VALUES (?, ?, ?, ?)
                """, tagId, organizationId, siteId, "tag-" + suffix);
        UUID vehicleTagBindingId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    INSERT INTO mine_location_tag_binding_claim (
                        binding_id, organization_id, site_id, binding_type,
                        tag_id, valid_from, valid_to
                    ) VALUES (?, ?, ?, 'VEHICLE', ?, ?, ?)
                    """, vehicleTagBindingId, organizationId, siteId, tagId,
                    timestamp(BASE_TIME.minusSeconds(3600)),
                    timestamp(BASE_TIME.plusSeconds(12 * 3600)));
            jdbcTemplate.update("""
                    INSERT INTO mine_vehicle_tag_binding (
                        id, organization_id, site_id, vehicle_id, tag_id,
                        valid_from, valid_to, created_by, updated_by
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, vehicleTagBindingId, organizationId, siteId, vehicleId, tagId,
                    timestamp(BASE_TIME.minusSeconds(3600)),
                    timestamp(BASE_TIME.plusSeconds(12 * 3600)), userId, userId);
        });

        SpatialVersions spatial = insertPublishedSpatialVersions(
                organizationId, siteId, suffix);
        UUID definitionId = UUID.randomUUID();
        UUID shiftId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_shift_definition (
                    id, organization_id, site_id, definition_code, name,
                    start_local, end_local, crosses_midnight, valid_from
                ) VALUES (?, ?, ?, ?, '自动运输班次', TIME '00:00', TIME '23:59',
                          FALSE, DATE '2026-01-01')
                """, definitionId, organizationId, siteId, "shift-" + suffix);
        jdbcTemplate.update("""
                INSERT INTO mine_shift_instance (
                    id, organization_id, site_id, shift_definition_id,
                    business_date, planned_start_at, planned_end_at,
                    actual_start_at, definition_snapshot,
                    status, opened_at, opened_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '{"name":"自动运输班次"}'::jsonb,
                          'OPEN', CURRENT_TIMESTAMP, ?)
                """, shiftId, organizationId, siteId, definitionId,
                LocalDate.parse("2026-07-20"), timestamp(BASE_TIME.minusSeconds(3600)),
                timestamp(BASE_TIME.plusSeconds(12 * 3600)),
                timestamp(BASE_TIME.minusSeconds(3600)), userId);
        UUID workOrderId = UUID.randomUUID();
        String releaseSnapshot = """
                {"orderNumber":"%s","title":"自动运输作业","workType":"HAULAGE",
                 "plannedStartAt":"%s","plannedEndAt":"%s",
                 "routeVersionId":"%s","loadGeofenceVersionId":"%s",
                 "unloadGeofenceVersionId":"%s","routeName":"主运输路线",
                 "loadZoneName":"装载区","unloadZoneName":"卸载区"}
                """.formatted(
                "order-" + suffix, BASE_TIME.minusSeconds(1800),
                BASE_TIME.plusSeconds(11 * 3600), spatial.routeVersionId(),
                spatial.loadVersionId(), spatial.unloadVersionId());
        jdbcTemplate.update("""
                INSERT INTO mine_work_order (
                    id, organization_id, site_id, shift_instance_id,
                    order_number, title, work_type, planned_start_at, planned_end_at,
                    planned_cycles, priority, route_version_id,
                    load_geofence_version_id, unload_geofence_version_id,
                    release_snapshot, status,
                    released_at, released_by, started_at, started_by,
                    created_at, created_by, updated_at, updated_by
                ) VALUES (?, ?, ?, ?, ?, '自动运输作业', 'HAULAGE', ?, ?, 10, 50,
                          ?, ?, ?, CAST(? AS JSONB), 'RUNNING',
                          ?, ?, ?, ?, ?, ?, ?, ?)
                """, workOrderId, organizationId, siteId, shiftId, "order-" + suffix,
                timestamp(BASE_TIME.minusSeconds(1800)),
                timestamp(BASE_TIME.plusSeconds(11 * 3600)),
                spatial.routeVersionId(), spatial.loadVersionId(), spatial.unloadVersionId(),
                releaseSnapshot, timestamp(BASE_TIME.minusSeconds(1800)), userId,
                timestamp(BASE_TIME.minusSeconds(1700)), userId,
                timestamp(BASE_TIME.minusSeconds(1800)), userId,
                timestamp(BASE_TIME.minusSeconds(1700)), userId);
        UUID assignmentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_work_order_assignment (
                    id, organization_id, site_id, work_order_id,
                    vehicle_id, valid_from, valid_to
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, assignmentId, organizationId, siteId, workOrderId, vehicleId,
                timestamp(BASE_TIME.minusSeconds(1800)),
                timestamp(BASE_TIME.plusSeconds(11 * 3600)));
        return new RuntimeContext(
                organizationId, siteId, userId, shiftId, workOrderId, assignmentId,
                vehicleId, tagId, spatial.routeVersionId(),
                spatial.loadGeofenceId(), spatial.loadVersionId(),
                spatial.unloadGeofenceId(), spatial.unloadVersionId());
    }

    private WorkOrderParent createParallelRunningWorkOrder(
            RuntimeContext context,
            String suffix
    ) {
        UUID definitionId = UUID.randomUUID();
        UUID shiftId = UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff");
        jdbcTemplate.update("""
                INSERT INTO mine_shift_definition (
                    id, organization_id, site_id, definition_code, name,
                    start_local, end_local, crosses_midnight, valid_from
                ) VALUES (?, ?, ?, ?, '并发替换班次', TIME '00:00', TIME '23:59',
                          FALSE, DATE '2026-01-01')
                """, definitionId, context.organizationId(), context.siteId(),
                "shift-" + suffix);
        jdbcTemplate.update("""
                INSERT INTO mine_shift_instance (
                    id, organization_id, site_id, shift_definition_id,
                    business_date, planned_start_at, planned_end_at,
                    actual_start_at, definition_snapshot,
                    status, opened_at, opened_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '{"name":"并发替换班次"}'::jsonb,
                          'OPEN', CURRENT_TIMESTAMP, ?)
                """, shiftId, context.organizationId(), context.siteId(), definitionId,
                LocalDate.parse("2026-07-20"), timestamp(BASE_TIME.minusSeconds(3600)),
                timestamp(BASE_TIME.plusSeconds(12 * 3600)),
                timestamp(BASE_TIME.minusSeconds(3600)), context.userId());
        UUID workOrderId = UUID.randomUUID();
        String releaseSnapshot = """
                {"orderNumber":"%s","title":"并发替换作业","workType":"HAULAGE",
                 "plannedStartAt":"%s","plannedEndAt":"%s",
                 "routeVersionId":"%s","loadGeofenceVersionId":"%s",
                 "unloadGeofenceVersionId":"%s","routeName":"主运输路线",
                 "loadZoneName":"装载区","unloadZoneName":"卸载区"}
                """.formatted(
                "order-" + suffix, BASE_TIME.minusSeconds(1800),
                BASE_TIME.plusSeconds(11 * 3600), context.routeVersionId(),
                context.loadVersionId(), context.unloadVersionId());
        jdbcTemplate.update("""
                INSERT INTO mine_work_order (
                    id, organization_id, site_id, shift_instance_id,
                    order_number, title, work_type, planned_start_at, planned_end_at,
                    planned_cycles, priority, route_version_id,
                    load_geofence_version_id, unload_geofence_version_id,
                    release_snapshot, status,
                    released_at, released_by, started_at, started_by,
                    created_at, created_by, updated_at, updated_by
                ) VALUES (?, ?, ?, ?, ?, '并发替换作业', 'HAULAGE', ?, ?, 10, 50,
                          ?, ?, ?, CAST(? AS JSONB), 'RUNNING',
                          ?, ?, ?, ?, ?, ?, ?, ?)
                """, workOrderId, context.organizationId(), context.siteId(), shiftId,
                "order-" + suffix, timestamp(BASE_TIME.minusSeconds(1800)),
                timestamp(BASE_TIME.plusSeconds(11 * 3600)), context.routeVersionId(),
                context.loadVersionId(), context.unloadVersionId(), releaseSnapshot,
                timestamp(BASE_TIME.minusSeconds(1800)), context.userId(),
                timestamp(BASE_TIME.minusSeconds(1700)), context.userId(),
                timestamp(BASE_TIME.minusSeconds(1800)), context.userId(),
                timestamp(BASE_TIME.minusSeconds(1700)), context.userId());
        return new WorkOrderParent(shiftId, workOrderId);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发测试等待超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private SpatialVersions insertPublishedSpatialVersions(
            UUID organizationId,
            UUID siteId,
            String suffix
    ) {
        UUID coordinateSystemId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_coordinate_system (
                    id, organization_id, site_id, system_code, name,
                    coordinate_type, unit, unified,
                    min_x, max_x, min_y, max_y, min_z, max_z
                ) VALUES (?, ?, ?, ?, '自动运输坐标系',
                          'LOCAL_CARTESIAN', 'METER', TRUE,
                          -1000, 1000, -1000, 1000, -100, 100)
                """, coordinateSystemId, organizationId, siteId, "coordinate-" + suffix);
        UUID routeId = UUID.randomUUID();
        UUID routeVersionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_route (id, organization_id, site_id, route_code)
                VALUES (?, ?, ?, ?)
                """, routeId, organizationId, siteId, "route-" + suffix);
        jdbcTemplate.update("""
                INSERT INTO mine_route_version (
                    id, route_id, organization_id, site_id, coordinate_system_id,
                    version_number, name, direction, applicable_vehicle_types,
                    valid_from, status, content_hash, published_at
                ) VALUES (?, ?, ?, ?, ?, 1, '主运输路线', 'FORWARD',
                          '["HAUL_TRUCK"]'::jsonb, TIMESTAMPTZ '2026-01-01 00:00:00Z',
                          'PUBLISHED', ?, CURRENT_TIMESTAMP)
                """, routeVersionId, routeId, organizationId, siteId, coordinateSystemId,
                "sha256:" + "a".repeat(64));
        GeofenceVersion load = insertPublishedGeofence(
                organizationId, siteId, coordinateSystemId, "load-" + suffix,
                "装载区", "LOAD", "b");
        GeofenceVersion unload = insertPublishedGeofence(
                organizationId, siteId, coordinateSystemId, "unload-" + suffix,
                "卸载区", "UNLOAD", "c");
        return new SpatialVersions(
                routeVersionId, load.geofenceId(), load.versionId(),
                unload.geofenceId(), unload.versionId());
    }

    private GeofenceVersion insertPublishedGeofence(
            UUID organizationId,
            UUID siteId,
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
        return new GeofenceVersion(geofenceId, versionId);
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static UUID uuidV7(int suffix) {
        return UUID.fromString("019f64e5-a000-7000-8000-" + "%012x".formatted(suffix));
    }

    private record RuntimeContext(
            UUID organizationId,
            UUID siteId,
            UUID userId,
            UUID shiftId,
            UUID workOrderId,
            UUID assignmentId,
            UUID vehicleId,
            UUID tagId,
            UUID routeVersionId,
            UUID loadGeofenceId,
            UUID loadVersionId,
            UUID unloadGeofenceId,
            UUID unloadVersionId
    ) {
    }

    private record SpatialVersions(
            UUID routeVersionId,
            UUID loadGeofenceId,
            UUID loadVersionId,
            UUID unloadGeofenceId,
            UUID unloadVersionId
    ) {
    }

    private record GeofenceVersion(UUID geofenceId, UUID versionId) {
    }

    private record WorkOrderParent(UUID shiftId, UUID workOrderId) {
    }
}
