package com.bosco.mine.server.integration;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.mine.dto.MineOrganizationResponse;
import com.bosco.mine.server.modules.mine.operation.ShiftCommandService;
import com.bosco.mine.server.modules.mine.operation.WorkOrderCommandService;
import com.bosco.mine.server.modules.mine.operation.WorkOrderQueryService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 验证运输作业单、调度分配、发布冻结和生命周期。
 */
@Testcontainers
@ActiveProfiles("test")
@Import(WorkOrderManagementIT.IdentityTestConfiguration.class)
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class WorkOrderManagementIT {

    private static final Instant SHIFT_START = Instant.parse("2026-07-20T00:00:00Z");
    private static final Instant SHIFT_END = Instant.parse("2026-07-20T12:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine_work_order_management")
            .withUsername("mine")
            .withPassword("mine-work-order-test-password");

    @Autowired
    private WorkOrderCommandService commandService;

    @Autowired
    private WorkOrderQueryService queryService;

    @Autowired
    private ShiftCommandService shiftCommandService;

    @Autowired
    private MineScopeCommandService scopeCommandService;

    @Autowired
    private MutableMineRequestIdentity identity;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID firstUserId;
    private UUID secondUserId;
    private UUID firstOrganizationId;
    private UUID secondOrganizationId;
    private UUID firstSiteId;
    private UUID secondSiteId;

    @BeforeEach
    void 准备两个隔离矿区() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    mine_shift_definition,
                    mine_coordinate_system,
                    mine_person_site_assignment,
                    mine_driver_qualification,
                    mine_person,
                    mine_vehicle,
                    mine_user_scope_grant,
                    mine_site,
                    mine_organization,
                    sys_operation_log
                CASCADE
                """);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'work-order-it-%'");
        firstUserId = createUser("first");
        secondUserId = createUser("second");

        identity.use(firstUserId);
        MineOrganizationResponse firstOrganization = scopeCommandService.createOrganization(
                "work-order-north", "作业北方集团");
        firstOrganizationId = firstOrganization.id();
        firstSiteId = scopeCommandService.createSite(
                firstOrganization.id(), "work-order-north-pit", "作业北矿",
                "UTC", null).id();

        identity.use(secondUserId);
        MineOrganizationResponse secondOrganization = scopeCommandService.createOrganization(
                "work-order-south", "作业南方集团");
        secondOrganizationId = secondOrganization.id();
        secondSiteId = scopeCommandService.createSite(
                secondOrganization.id(), "work-order-south-pit", "作业南矿",
                "UTC", null).id();
        identity.use(firstUserId);
    }

    @Test
    void 草稿必须限制矿区单号HAULAGE并使用乐观锁修改() {
        ShiftInstanceResponse shift = createShift("draft-shift", "2026-07-20");
        WorkOrderResponse created = commandService.createWorkOrder(
                firstSiteId, request(shift, "HAUL-001", "一号运输", "HAULAGE", 0));

        assertThat(created.status()).isEqualTo("DRAFT");
        assertCode(() -> commandService.createWorkOrder(
                        firstSiteId, request(shift, "HAUL-001", "重复运输", "HAULAGE", 0)),
                "MINE-WORK-409-001");
        assertCode(() -> commandService.createWorkOrder(
                        firstSiteId, request(shift, "DRILL-001", "钻孔作业", "DRILLING", 0)),
                "MINE-WORK-422-001");

        WorkOrderResponse updated = commandService.updateWorkOrder(
                firstSiteId, created.id(),
                request(shift, "HAUL-001", "一号运输调整", "HAULAGE", created.version()));
        assertThat(updated.title()).isEqualTo("一号运输调整");
        assertThat(updated.version()).isEqualTo(created.version() + 1);
        assertCode(() -> commandService.updateWorkOrder(
                        firstSiteId, created.id(),
                        request(shift, "HAUL-001", "过期修改", "HAULAGE", created.version())),
                "MINE-WORK-409-002");
    }

    @Test
    void 多车调度必须允许合法分配并拒绝车辆和司机时间重叠() {
        ShiftInstanceResponse shift = createShift("dispatch-shift", "2026-07-20");
        WorkOrderResponse firstOrder = commandService.createWorkOrder(
                firstSiteId, request(shift, "HAUL-A", "运输甲", "HAULAGE", 0));
        WorkOrderResponse secondOrder = commandService.createWorkOrder(
                firstSiteId, request(shift, "HAUL-B", "运输乙", "HAULAGE", 0));
        UUID firstVehicle = insertVehicle(firstOrganizationId, firstSiteId, "truck-a", "HAUL_TRUCK");
        UUID secondVehicle = insertVehicle(firstOrganizationId, firstSiteId, "truck-b", "HAUL_TRUCK");
        UUID thirdVehicle = insertVehicle(firstOrganizationId, firstSiteId, "truck-c", "HAUL_TRUCK");
        UUID firstDriver = insertQualifiedDriver("driver-a", SHIFT_START, SHIFT_END, true);
        UUID secondDriver = insertQualifiedDriver("driver-b", SHIFT_START, SHIFT_END, true);

        WorkOrderResponse withFirst = commandService.assignWorkOrder(
                firstSiteId, firstOrder.id(),
                assignment(firstVehicle, firstDriver, SHIFT_START, SHIFT_END,
                        firstOrder.version()));
        WorkOrderResponse withSecond = commandService.assignWorkOrder(
                firstSiteId, firstOrder.id(),
                assignment(secondVehicle, secondDriver, SHIFT_START, SHIFT_END,
                        withFirst.version()));

        assertThat(withFirst.assignments()).hasSize(1);
        assertThat(withSecond.assignments()).hasSize(2);
        assertCode(() -> commandService.assignWorkOrder(
                        firstSiteId, secondOrder.id(),
                        assignment(firstVehicle, null, SHIFT_START.plusSeconds(60), SHIFT_END,
                                secondOrder.version())),
                "MINE-WORK-409-003");
        assertCode(() -> commandService.assignWorkOrder(
                        firstSiteId, secondOrder.id(),
                        assignment(thirdVehicle, firstDriver, SHIFT_START, SHIFT_END,
                                secondOrder.version())),
                "MINE-WORK-409-003");
        assertCode(() -> commandService.assignWorkOrder(
                        firstSiteId, firstOrder.id(),
                        assignment(thirdVehicle, null, SHIFT_START, SHIFT_END,
                                firstOrder.version())),
                "MINE-WORK-409-002");
        assertThat(withSecond.version()).isEqualTo(firstOrder.version() + 2);
    }

    @Test
    void 分配必须校验发生时任职司机车型资质和矿卡车型() {
        ShiftInstanceResponse shift = createShift("qualification-shift", "2026-07-20");
        WorkOrderResponse order = commandService.createWorkOrder(
                firstSiteId, request(shift, "QUAL-001", "资质运输", "HAULAGE", 0));
        UUID haulTruck = insertVehicle(
                firstOrganizationId, firstSiteId, "qualified-truck", "HAUL_TRUCK");
        UUID excavator = insertVehicle(
                firstOrganizationId, firstSiteId, "wrong-excavator", "EXCAVATOR");
        UUID unqualifiedDriver = insertQualifiedDriver(
                "unqualified", SHIFT_START, SHIFT_END, false);
        UUID expiredAssignmentDriver = insertQualifiedDriver(
                "expired-site", SHIFT_START.minusSeconds(7200),
                SHIFT_START.minusSeconds(3600), true);

        assertCode(() -> commandService.assignWorkOrder(
                        firstSiteId, order.id(),
                        assignment(excavator, null, SHIFT_START, SHIFT_END, order.version())),
                "MINE-WORK-422-003");
        assertCode(() -> commandService.assignWorkOrder(
                        firstSiteId, order.id(),
                        assignment(haulTruck, unqualifiedDriver, SHIFT_START, SHIFT_END,
                                order.version())),
                "MINE-WORK-422-004");
        assertCode(() -> commandService.assignWorkOrder(
                        firstSiteId, order.id(),
                        assignment(haulTruck, expiredAssignmentDriver, SHIFT_START, SHIFT_END,
                                order.version())),
                "MINE-WORK-422-004");
    }

    @Test
    void 发布必须冻结同矿区已发布空间版本调度显示信息和规范摘要() {
        ShiftInstanceResponse shift = createShift("release-shift", "2026-07-20");
        WorkOrderResponse order = commandService.createWorkOrder(
                firstSiteId, request(shift, "REL-001", "发布运输", "HAULAGE", 0));
        UUID vehicle = insertVehicle(firstOrganizationId, firstSiteId, "release-truck", "HAUL_TRUCK");
        UUID driver = insertQualifiedDriver("release-driver", SHIFT_START, SHIFT_END, true);
        WorkOrderResponse assigned = commandService.assignWorkOrder(
                firstSiteId, order.id(),
                assignment(vehicle, driver, SHIFT_START, SHIFT_END, order.version()));
        SpatialVersions versions = insertPublishedSpatialVersions(
                firstOrganizationId, firstSiteId, "release");

        WorkOrderResponse released = commandService.releaseWorkOrder(
                firstSiteId, order.id(), action(assigned.version(), versions, null));

        assertThat(released.status()).isEqualTo("RELEASED");
        assertCode(() -> commandService.releaseWorkOrder(
                        firstSiteId, order.id(), action(assigned.version(), versions, null)),
                "MINE-WORK-409-002");
        assertThat(released.releaseSnapshot())
                .containsEntry("routeName", "主运输路线-release")
                .containsEntry("loadZoneName", "装载区-release")
                .containsEntry("unloadZoneName", "卸载区-release")
                .containsKey("assignments");
        assertThat((String) released.releaseSnapshot().get("contentHash"))
                .startsWith("sha256:")
                .hasSize(71);
        assertCode(() -> commandService.updateWorkOrder(
                        firstSiteId, order.id(),
                        request(shift, "REL-001", "禁止改写", "HAULAGE", released.version())),
                "MINE-WORK-409-002");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log WHERE action = 'RELEASE_WORK_ORDER'
                """, Long.class)).isOne();
    }

    @Test
    void 发布必须拒绝无分配草稿和其他矿区空间版本() {
        ShiftInstanceResponse shift = createShift("invalid-release-shift", "2026-07-20");
        WorkOrderResponse order = commandService.createWorkOrder(
                firstSiteId, request(shift, "REL-INVALID", "非法发布", "HAULAGE", 0));
        SpatialVersions firstVersions = insertPublishedSpatialVersions(
                firstOrganizationId, firstSiteId, "first");
        SpatialVersions otherVersions = insertPublishedSpatialVersions(
                secondOrganizationId, secondSiteId, "other");

        assertCode(() -> commandService.releaseWorkOrder(
                        firstSiteId, order.id(), action(order.version(), firstVersions, null)),
                "MINE-WORK-422-005");
        UUID vehicle = insertVehicle(firstOrganizationId, firstSiteId, "invalid-release-truck", "HAUL_TRUCK");
        WorkOrderResponse assigned = commandService.assignWorkOrder(
                firstSiteId, order.id(),
                assignment(vehicle, null, SHIFT_START, SHIFT_END, order.version()));
        assertCode(() -> commandService.releaseWorkOrder(
                        firstSiteId, order.id(), action(assigned.version(), otherVersions, null)),
                "MINE-WORK-422-002");
    }

    @Test
    void 发布启动暂停恢复完成必须遵循状态机并拒绝新键旧版本() {
        ShiftInstanceResponse shift = createShift("lifecycle-order-shift", "2026-07-20");
        WorkOrderResponse order = releasableOrder(shift, "LIFE-001", "life");
        WorkOrderResponse released = release(order, "life");
        shiftCommandService.openInstance(firstSiteId, shift.id());

        WorkOrderResponse running = commandService.startWorkOrder(
                firstSiteId, order.id(), action(released.version(), null, null));
        assertCode(() -> commandService.startWorkOrder(
                        firstSiteId, order.id(), action(released.version(), null, null)),
                "MINE-WORK-409-002");
        WorkOrderResponse paused = commandService.pauseWorkOrder(
                firstSiteId, order.id(), action(running.version(), null, null));
        assertCode(() -> commandService.pauseWorkOrder(
                        firstSiteId, order.id(), action(running.version(), null, null)),
                "MINE-WORK-409-002");
        WorkOrderResponse resumed = commandService.resumeWorkOrder(
                firstSiteId, order.id(), action(paused.version(), null, null));
        assertCode(() -> commandService.resumeWorkOrder(
                        firstSiteId, order.id(), action(paused.version(), null, null)),
                "MINE-WORK-409-002");
        WorkOrderResponse completed = commandService.completeWorkOrder(
                firstSiteId, order.id(), action(resumed.version(), null, null));
        assertCode(() -> commandService.completeWorkOrder(
                        firstSiteId, order.id(), action(resumed.version(), null, null)),
                "MINE-WORK-409-002");

        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertCode(() -> commandService.pauseWorkOrder(
                        firstSiteId, order.id(), action(completed.version(), null, null)),
                "MINE-WORK-409-004");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                WHERE action IN (
                    'START_WORK_ORDER', 'PAUSE_WORK_ORDER',
                    'RESUME_WORK_ORDER', 'COMPLETE_WORK_ORDER'
                )
                """, Long.class)).isEqualTo(4);
    }

    @Test
    void 取消和异常终止必须保留原因且终态不可继续迁移() {
        ShiftInstanceResponse shift = createShift("terminal-order-shift", "2026-07-20");
        WorkOrderResponse draft = commandService.createWorkOrder(
                firstSiteId, request(shift, "CANCEL-001", "取消运输", "HAULAGE", 0));
        WorkOrderResponse cancelled = commandService.cancelWorkOrder(
                firstSiteId, draft.id(), action(draft.version(), null, "计划取消"));
        assertCode(() -> commandService.cancelWorkOrder(
                        firstSiteId, draft.id(), action(draft.version(), null, "计划取消")),
                "MINE-WORK-409-002");
        assertThat(cancelled.terminalReason()).isEqualTo("计划取消");

        WorkOrderResponse runningOrder = releasableOrder(shift, "ABORT-001", "abort");
        WorkOrderResponse released = release(runningOrder, "abort");
        shiftCommandService.openInstance(firstSiteId, shift.id());
        WorkOrderResponse running = commandService.startWorkOrder(
                firstSiteId, runningOrder.id(), action(released.version(), null, null));
        WorkOrderResponse aborted = commandService.abortWorkOrder(
                firstSiteId, running.id(), action(running.version(), null, "道路中断"));
        assertCode(() -> commandService.abortWorkOrder(
                        firstSiteId, running.id(), action(running.version(), null, "道路中断")),
                "MINE-WORK-409-002");
        assertThat(aborted.status()).isEqualTo("ABORTED");
        assertCode(() -> commandService.completeWorkOrder(
                        firstSiteId, running.id(), action(aborted.version(), null, null)),
                "MINE-WORK-409-004");
    }

    @Test
    void 班次取消后必须拒绝发布已建立的作业单草稿() {
        ShiftInstanceResponse shift = createShift("cancelled-release-shift", "2026-07-20");
        WorkOrderResponse order = releasableOrder(shift, "CANCELLED-REL-001", "cancelled-release");
        SpatialVersions versions = insertPublishedSpatialVersions(
                firstOrganizationId, firstSiteId, "cancelled-release");

        assertCode(() -> shiftCommandService.cancelInstance(
                        firstSiteId, shift.id(), "班次计划取消"),
                "MINE-SHIFT-409-003");
        WorkOrderResponse cancelled = commandService.cancelWorkOrder(
                firstSiteId, order.id(), action(order.version(), null, "随班次取消"));
        shiftCommandService.cancelInstance(firstSiteId, shift.id(), "班次计划取消");

        assertCode(() -> commandService.releaseWorkOrder(
                        firstSiteId, order.id(), action(cancelled.version(), versions, null)),
                "MINE-WORK-409-004");
        assertThat(queryService.findWorkOrder(firstSiteId, order.id()).status())
                .isEqualTo("CANCELLED");
    }

    @Test
    void 班次关闭与作业启动并发时只能成功一个() throws Exception {
        ShiftInstanceResponse shift = createShift("close-start-race-shift", "2026-07-20");
        WorkOrderResponse order = releasableOrder(shift, "RACE-001", "close-start-race");
        WorkOrderResponse released = release(order, "close-start-race");
        ShiftInstanceResponse opened = shiftCommandService.openInstance(firstSiteId, shift.id());
        CountDownLatch shiftLocked = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var startFuture = executor.submit(() -> {
                assertThat(shiftLocked.await(5, TimeUnit.SECONDS)).isTrue();
                return commandService.startWorkOrder(
                        firstSiteId, order.id(), action(released.version(), null, null));
            });
            assertThatThrownBy(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        jdbcTemplate.queryForObject("""
                                SELECT id FROM mine_shift_instance
                                WHERE id = ? FOR UPDATE
                                """, UUID.class, shift.id());
                        shiftLocked.countDown();
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(exception);
                        }
                        jdbcTemplate.update("""
                                UPDATE mine_shift_instance
                                SET status = 'CLOSED', actual_end_at = CURRENT_TIMESTAMP,
                                    closed_at = CURRENT_TIMESTAMP, closed_by = ?,
                                    updated_at = CURRENT_TIMESTAMP, updated_by = ?,
                                    version = version + 1
                                WHERE id = ? AND status = 'OPEN' AND version = ?
                                """, firstUserId, firstUserId, shift.id(), opened.version());
                    })).isInstanceOf(DataIntegrityViolationException.class);

            assertThat(startFuture.get(5, TimeUnit.SECONDS).status()).isEqualTo("RUNNING");
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM mine_shift_instance WHERE id = ?",
                String.class, shift.id())).isEqualTo("OPEN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM mine_work_order WHERE id = ?",
                String.class, order.id())).isEqualTo("RUNNING");
    }

    @Test
    void 作业终态必须拒绝未完成循环并清理空闲运行态() {
        ShiftInstanceResponse shift = createShift("runtime-terminal-shift", "2026-07-20");
        SpatialVersions versions = insertPublishedSpatialVersions(
                firstOrganizationId, firstSiteId, "runtime-terminal");
        WorkOrderResponse partialOrder = releasableOrder(
                shift, "RUNTIME-PARTIAL", "runtime-partial");
        WorkOrderResponse partialReleased = commandService.releaseWorkOrder(
                firstSiteId, partialOrder.id(), action(partialOrder.version(), versions, null));
        shiftCommandService.openInstance(firstSiteId, shift.id());
        WorkOrderResponse partialRunning = commandService.startWorkOrder(
                firstSiteId, partialOrder.id(), action(partialReleased.version(), null, null));
        insertRuntimeState(partialRunning, "LOADING");

        assertCode(() -> commandService.completeWorkOrder(
                        firstSiteId, partialOrder.id(),
                        action(partialRunning.version(), null, null)),
                "MINE-WORK-409-005");
        WorkOrderResponse aborted = commandService.abortWorkOrder(
                firstSiteId, partialOrder.id(),
                action(partialRunning.version(), null, "装载设备故障"));

        assertThat(aborted.status()).isEqualTo("ABORTED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM mine_operation_runtime_state
                WHERE work_order_id = ?
                """, Long.class, partialOrder.id())).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM mine_operation_record
                WHERE work_order_id = ?
                  AND record_status = 'ABORTED'
                  AND record_source = 'MANUAL'
                  AND frozen_snapshot ->> 'abortReason' = '装载设备故障'
                """, Long.class, partialOrder.id())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM mine_operation_effective_record effective_record
                JOIN mine_operation_record record
                  ON record.id = effective_record.operation_record_id
                WHERE record.work_order_id = ?
                  AND effective_record.record_status = 'ABORTED'
                  AND effective_record.effective
                """, Long.class, partialOrder.id())).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM mine_operation_evidence evidence
                JOIN mine_operation_record record
                  ON record.id = evidence.operation_record_id
                WHERE record.work_order_id = ?
                  AND evidence.evidence_role = 'MANUAL_SUPPORT'
                """, Long.class, partialOrder.id())).isOne();

        WorkOrderResponse idleOrder = releasableOrder(
                shift, "RUNTIME-IDLE", "runtime-idle");
        WorkOrderResponse idleReleased = commandService.releaseWorkOrder(
                firstSiteId, idleOrder.id(), action(idleOrder.version(), versions, null));
        WorkOrderResponse idleRunning = commandService.startWorkOrder(
                firstSiteId, idleOrder.id(), action(idleReleased.version(), null, null));
        insertRuntimeState(idleRunning, "WAITING_LOAD");
        WorkOrderResponse completed = commandService.completeWorkOrder(
                firstSiteId, idleOrder.id(), action(idleRunning.version(), null, null));

        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM mine_operation_runtime_state
                WHERE work_order_id = ?
                """, Long.class, idleOrder.id())).isZero();
    }

    @Test
    void 新增运行态必须与作业异常终止共享父级互斥锁() throws Exception {
        ShiftInstanceResponse shift = createShift("runtime-insert-race-shift", "2026-07-20");
        WorkOrderResponse order = releasableOrder(
                shift, "RUNTIME-INSERT-RACE", "runtime-insert-race");
        WorkOrderResponse released = release(order, "runtime-insert-race");
        shiftCommandService.openInstance(firstSiteId, shift.id());
        WorkOrderResponse running = commandService.startWorkOrder(
                firstSiteId, order.id(), action(released.version(), null, null));
        CountDownLatch terminalParentsLocked = new CountDownLatch(1);
        CompletableFuture<Integer> insertBackendPid = new CompletableFuture<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var terminalFuture = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        jdbcTemplate.queryForObject("""
                                SELECT id FROM mine_shift_instance
                                WHERE id = ? FOR UPDATE
                                """, UUID.class, shift.id());
                        jdbcTemplate.queryForObject("""
                                SELECT id FROM mine_work_order
                                WHERE id = ? FOR UPDATE
                                """, UUID.class, order.id());
                        terminalParentsLocked.countDown();
                        try {
                            awaitBackendBlocked(insertBackendPid.get(5, TimeUnit.SECONDS));
                        } catch (Exception exception) {
                            if (exception instanceof InterruptedException) {
                                Thread.currentThread().interrupt();
                            }
                            throw new AssertionError(exception);
                        } catch (AssertionError error) {
                            throw error;
                        }
                        return commandService.abortWorkOrder(
                                firstSiteId, order.id(),
                                action(running.version(), null, "并发终止"));
                    }));
            var insertFuture = executor.submit(() -> {
                assertThat(terminalParentsLocked.await(5, TimeUnit.SECONDS)).isTrue();
                return new TransactionTemplate(transactionManager).execute(status -> {
                    insertBackendPid.complete(jdbcTemplate.queryForObject(
                            "SELECT pg_backend_pid()", Integer.class));
                    return insertRuntimeState(running, "WAITING_LOAD");
                });
            });

            assertThat(terminalFuture.get(5, TimeUnit.SECONDS).status()).isEqualTo("ABORTED");
            assertThatThrownBy(() -> insertFuture.get(5, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ExecutionException.class, exception ->
                            assertThat(exception.getCause())
                                    .isInstanceOf(DataIntegrityViolationException.class));
        }
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM mine_operation_runtime_state
                WHERE work_order_id = ?
                """, Long.class, order.id())).isZero();
    }

    @Test
    void 关账必须锁定全部运行态并拒绝并发推进空闲循环() throws Exception {
        ShiftInstanceResponse shift = createShift("runtime-close-race-shift", "2026-07-20");
        WorkOrderResponse order = releasableOrder(
                shift, "RUNTIME-CLOSE-RACE", "runtime-close-race");
        WorkOrderResponse released = release(order, "runtime-close-race");
        shiftCommandService.openInstance(firstSiteId, shift.id());
        WorkOrderResponse running = commandService.startWorkOrder(
                firstSiteId, order.id(), action(released.version(), null, null));
        UUID runtimeId = insertRuntimeState(running, "WAITING_LOAD");
        jdbcTemplate.execute("""
                ALTER TABLE mine_work_order
                DISABLE TRIGGER trg_mine_work_order_validate_window
                """);
        try {
            jdbcTemplate.update("""
                    UPDATE mine_work_order
                    SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP,
                        completed_by = ?, updated_at = CURRENT_TIMESTAMP,
                        updated_by = ?, version = version + 1
                    WHERE id = ?
                    """, firstUserId, firstUserId, order.id());
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE mine_work_order
                    ENABLE TRIGGER trg_mine_work_order_validate_window
                    """);
        }
        CountDownLatch runtimeLocked = new CountDownLatch(1);
        CountDownLatch allowRuntimeUpdate = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var runtimeFuture = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        jdbcTemplate.queryForObject("""
                                SELECT id FROM mine_operation_runtime_state
                                WHERE id = ? FOR UPDATE
                                """, UUID.class, runtimeId);
                        runtimeLocked.countDown();
                        try {
                            assertThat(allowRuntimeUpdate.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(exception);
                        }
                        jdbcTemplate.update("""
                                UPDATE mine_operation_runtime_state
                                SET cycle_status = 'LOADING', started_at = CURRENT_TIMESTAMP,
                                    updated_at = CURRENT_TIMESTAMP, version = version + 1
                                WHERE id = ?
                                """, runtimeId);
                    }));
            assertThat(runtimeLocked.await(5, TimeUnit.SECONDS)).isTrue();
            var closeFuture = executor.submit(() ->
                    shiftCommandService.closeInstance(firstSiteId, shift.id()));
            Thread.sleep(500);
            allowRuntimeUpdate.countDown();
            assertThatThrownBy(() -> runtimeFuture.get(5, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ExecutionException.class, exception ->
                            assertThat(exception.getCause())
                                    .isInstanceOf(DataIntegrityViolationException.class));
            assertThatThrownBy(() -> closeFuture.get(5, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ExecutionException.class, exception ->
                            assertThat(exception.getCause())
                                    .isInstanceOfSatisfying(MineException.class, cause ->
                                            assertThat(cause.errorCode().code())
                                                    .isEqualTo("MINE-SHIFT-409-003")));
        }
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM mine_shift_instance WHERE id = ?",
                String.class, shift.id())).isEqualTo("OPEN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cycle_status FROM mine_operation_runtime_state WHERE id = ?",
                String.class, runtimeId)).isEqualTo("WAITING_LOAD");
    }

    @Test
    void 查询和命令必须拒绝其他用户跨矿区访问() {
        ShiftInstanceResponse shift = createShift("scope-order-shift", "2026-07-20");
        WorkOrderResponse order = commandService.createWorkOrder(
                firstSiteId, request(shift, "SCOPE-001", "作用域运输", "HAULAGE", 0));
        assertThat(queryService.findWorkOrders(firstSiteId, shift.id(), "DRAFT", 0, 20))
                .extracting(WorkOrderResponse::id)
                .containsExactly(order.id());

        identity.use(secondUserId);
        assertCode(() -> queryService.findWorkOrder(firstSiteId, order.id()),
                "MINE-SCOPE-403-002");
        assertCode(() -> commandService.cancelWorkOrder(
                        firstSiteId, order.id(), action(order.version(), null, "越权取消")),
                "MINE-SCOPE-403-002");
    }

    private WorkOrderResponse releasableOrder(
            ShiftInstanceResponse shift, String orderNumber, String suffix
    ) {
        WorkOrderResponse order = commandService.createWorkOrder(
                firstSiteId, request(shift, orderNumber, orderNumber, "HAULAGE", 0));
        UUID vehicle = insertVehicle(
                firstOrganizationId, firstSiteId, "truck-" + suffix, "HAUL_TRUCK");
        return commandService.assignWorkOrder(
                firstSiteId, order.id(),
                assignment(vehicle, null, SHIFT_START, SHIFT_END, order.version()));
    }

    private WorkOrderResponse release(WorkOrderResponse order, String suffix) {
        SpatialVersions versions = insertPublishedSpatialVersions(
                firstOrganizationId, firstSiteId, suffix);
        return commandService.releaseWorkOrder(
                firstSiteId, order.id(), action(order.version(), versions, null));
    }

    private ShiftInstanceResponse createShift(String code, String businessDate) {
        var definition = shiftCommandService.createDefinition(
                firstSiteId,
                new ShiftDefinitionRequest(
                        code, code, LocalTime.of(0, 0), LocalTime.of(12, 0), false,
                        LocalDate.parse("2026-01-01"), null, 0L));
        return shiftCommandService.createInstance(
                firstSiteId,
                new ShiftInstanceRequest(
                        definition.id(), LocalDate.parse(businessDate), null));
    }

    private WorkOrderRequest request(
            ShiftInstanceResponse shift,
            String orderNumber,
            String title,
            String workType,
            long version
    ) {
        return new WorkOrderRequest(
                shift.id(), orderNumber, title, workType,
                shift.plannedStartAt(), shift.plannedEndAt(),
                12, new BigDecimal("360.000"), 50, version);
    }

    private WorkOrderAssignmentRequest assignment(
            UUID vehicleId, UUID driverId, Instant validFrom, Instant validTo, long version
    ) {
        return new WorkOrderAssignmentRequest(
                vehicleId, driverId, validFrom, validTo, "INITIAL_DISPATCH", version);
    }

    private UUID insertRuntimeState(WorkOrderResponse order, String cycleStatus) {
        UUID runtimeId = UUID.randomUUID();
        UUID assignmentId = order.assignments().getFirst().id();
        jdbcTemplate.update("""
                INSERT INTO mine_operation_runtime_state (
                    id, organization_id, site_id, work_order_id, assignment_id,
                    cycle_status, evidence_snapshot, started_at
                ) VALUES (?, ?, ?, ?, ?, ?, jsonb_build_object('cycleStatus', ?),
                          CASE WHEN ? = 'WAITING_LOAD' THEN NULL::TIMESTAMPTZ
                               ELSE CAST(? AS TIMESTAMPTZ) END)
                """, runtimeId, order.organizationId(), order.siteId(), order.id(),
                assignmentId, cycleStatus, cycleStatus, cycleStatus,
                Timestamp.from(SHIFT_START.plusSeconds(3600)));
        return runtimeId;
    }

    private void awaitBackendBlocked(int backendPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Boolean blocked = jdbcTemplate.queryForObject("""
                    SELECT cardinality(pg_blocking_pids(?)) > 0
                    """, Boolean.class, backendPid);
            if (Boolean.TRUE.equals(blocked)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("运行态新增事务未等待父级互斥锁");
    }

    private WorkOrderActionRequest action(
            long version, SpatialVersions versions, String reason
    ) {
        return new WorkOrderActionRequest(
                version,
                versions == null ? null : versions.routeVersionId(),
                versions == null ? null : versions.loadVersionId(),
                versions == null ? null : versions.unloadVersionId(),
                reason);
    }

    private UUID insertVehicle(
            UUID organizationId, UUID siteId, String code, String vehicleType
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_vehicle (
                    id, organization_id, site_id, vehicle_code, name, vehicle_type
                ) VALUES (?, ?, ?, ?, '测试车辆', ?)
                """, id, organizationId, siteId, code, vehicleType);
        return id;
    }

    private UUID insertQualifiedDriver(
            String code, Instant assignmentFrom, Instant assignmentTo, boolean qualified
    ) {
        UUID personId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_person (
                    id, organization_id, person_code, name, person_type,
                    phone_ciphertext, phone_masked,
                    certificate_ciphertext, certificate_masked
                ) VALUES (?, ?, ?, '测试司机', 'DRIVER',
                          'v2:phone:cipher', '138****0000',
                          'v2:certificate:cipher', '证件****0000')
                """, personId, firstOrganizationId, code);
        jdbcTemplate.update("""
                INSERT INTO mine_person_site_assignment (
                    id, organization_id, site_id, person_id,
                    valid_from, valid_to, reason
                ) VALUES (?, ?, ?, ?, ?, ?, '作业测试任职')
                """, UUID.randomUUID(), firstOrganizationId, firstSiteId, personId,
                Timestamp.from(assignmentFrom), Timestamp.from(assignmentTo));
        if (qualified) {
            jdbcTemplate.update("""
                    INSERT INTO mine_driver_qualification (
                        id, organization_id, person_id, qualification_code, name,
                        allowed_vehicle_types, valid_from, valid_to
                    ) VALUES (?, ?, ?, ?, '矿卡驾驶资质', '["HAUL_TRUCK"]'::jsonb,
                              DATE '2026-01-01', DATE '2026-12-31')
                    """, UUID.randomUUID(), firstOrganizationId, personId,
                    "qualification-" + code);
        }
        return personId;
    }

    private SpatialVersions insertPublishedSpatialVersions(
            UUID organizationId, UUID siteId, String suffix
    ) {
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
                "coordinate-" + suffix);
        UUID routeId = UUID.randomUUID();
        UUID routeVersionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_route (id, organization_id, site_id, route_code)
                VALUES (?, ?, ?, ?)
                """, routeId, organizationId, siteId, "route-" + suffix);
        jdbcTemplate.update("""
                INSERT INTO mine_route_version (
                    id, route_id, organization_id, site_id,
                    coordinate_system_id, version_number, name, direction,
                    applicable_vehicle_types, valid_from, status,
                    content_hash, published_at
                ) VALUES (?, ?, ?, ?, ?, 1, ?, 'FORWARD',
                          '["HAUL_TRUCK"]'::jsonb, TIMESTAMPTZ '2026-01-01 00:00:00Z',
                          'PUBLISHED', ?, CURRENT_TIMESTAMP)
                """, routeVersionId, routeId, organizationId, siteId,
                coordinateSystemId, "主运输路线-" + suffix, "sha256:" + "a".repeat(64));
        UUID loadVersionId = insertPublishedGeofenceVersion(
                organizationId, siteId, coordinateSystemId,
                "load-" + suffix, "装载区-" + suffix, "LOAD", "b");
        UUID unloadVersionId = insertPublishedGeofenceVersion(
                organizationId, siteId, coordinateSystemId,
                "unload-" + suffix, "卸载区-" + suffix, "UNLOAD", "c");
        return new SpatialVersions(routeVersionId, loadVersionId, unloadVersionId);
    }

    private UUID insertPublishedGeofenceVersion(
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
                coordinateSystemId, name, type, "sha256:" + hashCharacter.repeat(64));
        return versionId;
    }

    private void assertCode(Runnable action, String expectedCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo(expectedCode));
    }

    private UUID createUser(String suffix) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password_hash, display_name)
                VALUES (?, ?, 'bcrypt:test', ?)
                """, id, "work-order-it-" + suffix, "作业单测试用户" + suffix);
        return id;
    }

    /** 为集成测试提供不依赖 Redis 会话的稳定请求身份。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IdentityTestConfiguration {

        /** 返回可切换的测试身份。 */
        @Bean
        @Primary
        MutableMineRequestIdentity mutableMineRequestIdentity() {
            return new MutableMineRequestIdentity();
        }
    }

    /** 单线程集成测试使用的可切换身份。 */
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

    /** 已发布路线和装卸区域版本标识。 */
    private record SpatialVersions(
            UUID routeVersionId,
            UUID loadVersionId,
            UUID unloadVersionId
    ) {
    }
}
