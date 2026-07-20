package com.bosco.mine.server.integration;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.mine.dto.MineOrganizationResponse;
import com.bosco.mine.server.modules.mine.operation.OperationActionIdempotencyService;
import com.bosco.mine.server.modules.mine.operation.ShiftCommandService;
import com.bosco.mine.server.modules.mine.operation.ShiftQueryService;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftDefinitionRequest;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftDefinitionResponse;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftInstanceRequest;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftInstanceResponse;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 验证矿区班次模板、实例、时区和生命周期闭环。
 */
@Testcontainers
@ActiveProfiles("test")
@Import(ShiftManagementIT.IdentityTestConfiguration.class)
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class ShiftManagementIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine_shift_management")
            .withUsername("mine")
            .withPassword("mine-shift-test-password");

    @Autowired
    private ShiftCommandService commandService;

    @Autowired
    private ShiftQueryService queryService;

    @Autowired
    private OperationActionIdempotencyService idempotencyService;

    @Autowired
    private MineScopeCommandService scopeCommandService;

    @Autowired
    private MutableMineRequestIdentity identity;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID firstUserId;
    private UUID secondUserId;
    private UUID firstSiteId;
    private UUID secondSiteId;

    @BeforeEach
    void 准备两个隔离矿区() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    mine_shift_definition,
                    mine_user_scope_grant,
                    mine_site,
                    mine_organization,
                    sys_operation_log
                CASCADE
                """);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'shift-management-it-%'");
        firstUserId = createUser("first");
        secondUserId = createUser("second");

        identity.use(firstUserId);
        MineOrganizationResponse firstOrganization = scopeCommandService.createOrganization(
                "shift-north", "班次北方集团");
        firstSiteId = scopeCommandService.createSite(
                firstOrganization.id(), "shift-north-pit", "班次北矿",
                "Asia/Shanghai", null).id();

        identity.use(secondUserId);
        MineOrganizationResponse secondOrganization = scopeCommandService.createOrganization(
                "shift-south", "班次南方集团");
        secondSiteId = scopeCommandService.createSite(
                secondOrganization.id(), "shift-south-pit", "班次南矿",
                "America/Denver", null).id();
        identity.use(firstUserId);
    }

    @Test
    void 跨午夜实例必须按矿区时区计算唯一UTC边界并冻结模板() {
        ShiftDefinitionResponse definition = commandService.createDefinition(
                firstSiteId,
                definition("night-shift", "夜班", "19:00", "07:00", true,
                        "2026-01-01", null, 0));

        ShiftInstanceResponse instance = commandService.createInstance(
                firstSiteId,
                new ShiftInstanceRequest(
                        definition.id(), LocalDate.parse("2026-07-20"), null));

        assertThat(instance.plannedStartAt()).isEqualTo(Instant.parse("2026-07-20T11:00:00Z"));
        assertThat(instance.plannedEndAt()).isEqualTo(Instant.parse("2026-07-20T23:00:00Z"));
        assertThat(instance.definitionSnapshot())
                .containsEntry("definitionCode", "night-shift")
                .containsEntry("timezone", "Asia/Shanghai")
                .containsEntry("crossesMidnight", true);
        assertThat(queryService.findInstance(firstSiteId, instance.id())).isEqualTo(instance);
    }

    @Test
    void 实例必须落在活动模板有效期且同营业日期只能创建一次() {
        ShiftDefinitionResponse definition = commandService.createDefinition(
                firstSiteId,
                definition("valid-shift", "有效班次", "08:00", "16:00", false,
                        "2026-07-01", "2026-07-31", 0));
        ShiftInstanceRequest request = new ShiftInstanceRequest(
                definition.id(), LocalDate.parse("2026-07-20"), null);

        commandService.createInstance(firstSiteId, request);

        assertCode(() -> commandService.createInstance(firstSiteId, request),
                "MINE-SHIFT-409-002");
        assertCode(() -> commandService.createInstance(
                        firstSiteId,
                        new ShiftInstanceRequest(
                                definition.id(), LocalDate.parse("2026-08-01"), null)),
                "MINE-SHIFT-422-002");
    }

    @Test
    void 动作幂等键必须持久返回首次结果并拒绝同键异请求() {
        ShiftDefinitionResponse definition = commandService.createDefinition(
                firstSiteId,
                definition("idempotent-shift", "幂等班次", "08:00", "16:00", false,
                        "2026-01-01", null, 0));
        ShiftInstanceResponse instance = commandService.createInstance(
                firstSiteId,
                new ShiftInstanceRequest(
                        definition.id(), LocalDate.parse("2026-07-20"), null));
        AtomicInteger calls = new AtomicInteger();

        ShiftInstanceResponse opened = idempotencyService.execute(
                firstSiteId, "open-shift-key", "OPEN_SHIFT", "SHIFT_INSTANCE", instance.id(),
                Map.of("version", instance.version()), ShiftInstanceResponse.class,
                () -> {
                    calls.incrementAndGet();
                    return commandService.openInstance(firstSiteId, instance.id(), instance.version());
                });
        ShiftInstanceResponse replayed = idempotencyService.execute(
                firstSiteId, "open-shift-key", "OPEN_SHIFT", "SHIFT_INSTANCE", instance.id(),
                Map.of("version", instance.version()), ShiftInstanceResponse.class,
                () -> {
                    calls.incrementAndGet();
                    throw new AssertionError("重放不得再次执行班次动作");
                });

        assertThat(replayed).isEqualTo(opened);
        assertThat(calls).hasValue(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_operation_action_idempotency", Long.class)).isOne();
        assertCode(() -> idempotencyService.execute(
                        firstSiteId, "open-shift-key", "OPEN_SHIFT", "SHIFT_INSTANCE", instance.id(),
                        Map.of("version", opened.version()), ShiftInstanceResponse.class,
                        () -> opened),
                "MINE-OPERATION-409-001");
    }

    @Test
    void 并发同键动作必须只执行一次并向双方返回同一冻结结果() throws Exception {
        ShiftDefinitionResponse definition = commandService.createDefinition(
                firstSiteId,
                definition("concurrent-shift", "并发班次", "08:00", "16:00", false,
                        "2026-01-01", null, 0));
        ShiftInstanceResponse instance = commandService.createInstance(
                firstSiteId,
                new ShiftInstanceRequest(
                        definition.id(), LocalDate.parse("2026-07-21"), null));
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var action = (java.util.concurrent.Callable<ShiftInstanceResponse>) () -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return idempotencyService.execute(
                        firstSiteId, "concurrent-open-key", "OPEN_SHIFT", "SHIFT_INSTANCE",
                        instance.id(), Map.of("version", instance.version()),
                        ShiftInstanceResponse.class,
                        () -> {
                            calls.incrementAndGet();
                            return commandService.openInstance(
                                    firstSiteId, instance.id(), instance.version());
                        });
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS))
                    .isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(calls).hasValue(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM mine_operation_action_idempotency
                WHERE idempotency_key = 'concurrent-open-key'
                """, Long.class)).isOne();
    }

    @Test
    void 模板修改和归档必须使用乐观版本且归档后禁止排班() {
        ShiftDefinitionResponse created = commandService.createDefinition(
                firstSiteId,
                definition("day-shift", "白班", "08:00", "16:00", false,
                        "2026-01-01", null, 0));
        ShiftDefinitionResponse updated = commandService.updateDefinition(
                firstSiteId, created.id(),
                definition("day-shift", "白班调整", "07:30", "15:30", false,
                        "2026-01-01", null, created.version()));

        assertThat(updated.name()).isEqualTo("白班调整");
        assertThat(updated.version()).isEqualTo(created.version() + 1);
        assertCode(() -> commandService.updateDefinition(
                        firstSiteId, created.id(),
                        definition("day-shift", "过期修改", "07:00", "15:00", false,
                                "2026-01-01", null, created.version())),
                "MINE-SHIFT-409-001");

        ShiftDefinitionResponse archived = commandService.archiveDefinition(
                firstSiteId, created.id(), updated.version());
        assertThat(archived.status()).isEqualTo("ARCHIVED");
        assertCode(() -> commandService.createInstance(
                        firstSiteId,
                        new ShiftInstanceRequest(
                                created.id(), LocalDate.parse("2026-07-20"), null)),
                "MINE-SHIFT-422-002");
    }

    @Test
    void 开启和关闭必须锁定实例并支持自然幂等重放() {
        ShiftInstanceResponse planned = createDayInstance("lifecycle-shift", "2026-07-20");

        ShiftInstanceResponse opened = commandService.openInstance(firstSiteId, planned.id());
        ShiftInstanceResponse openedAgain = commandService.openInstance(firstSiteId, planned.id());
        assertThat(openedAgain).isEqualTo(opened);
        assertThat(opened.status()).isEqualTo("OPEN");
        assertThat(opened.actualStartAt()).isNotNull();

        ShiftInstanceResponse closed = commandService.closeInstance(firstSiteId, planned.id());
        ShiftInstanceResponse closedAgain = commandService.closeInstance(firstSiteId, planned.id());
        assertThat(closedAgain).isEqualTo(closed);
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.actualEndAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                WHERE action IN ('OPEN_SHIFT', 'CLOSE_SHIFT')
                """, Long.class)).isEqualTo(2);
    }

    @Test
    void 取消必须要求原因且重复取消不得产生第二次审计() {
        ShiftInstanceResponse planned = createDayInstance("cancel-shift", "2026-07-21");

        assertCode(() -> commandService.cancelInstance(firstSiteId, planned.id(), " "),
                "MINE-SHIFT-422-001");
        ShiftInstanceResponse cancelled = commandService.cancelInstance(
                firstSiteId, planned.id(), "暴雨停产");
        ShiftInstanceResponse cancelledAgain = commandService.cancelInstance(
                firstSiteId, planned.id(), "暴雨停产");

        assertThat(cancelledAgain).isEqualTo(cancelled);
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancellationReason()).isEqualTo("暴雨停产");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log WHERE action = 'CANCEL_SHIFT'
                """, Long.class)).isOne();
    }

    @Test
    void 关闭班次必须拒绝遗留运输循环运行状态() {
        ShiftInstanceResponse opened = commandService.openInstance(
                firstSiteId, createDayInstance("runtime-shift", "2026-07-22").id());
        insertRuntimeState(opened);

        assertCode(() -> commandService.closeInstance(firstSiteId, opened.id()),
                "MINE-SHIFT-409-003");
        assertThat(queryService.findInstance(firstSiteId, opened.id()).status()).isEqualTo("OPEN");
    }

    @Test
    void 模板查询必须分页过滤且拒绝越权矿区访问() {
        commandService.createDefinition(
                firstSiteId,
                definition("query-a", "查询甲班", "08:00", "16:00", false,
                        "2026-01-01", null, 0));
        commandService.createDefinition(
                firstSiteId,
                definition("query-b", "查询乙班", "16:00", "23:00", false,
                        "2026-01-01", null, 0));

        assertThat(queryService.findDefinitions(firstSiteId, "ACTIVE", 1, 1))
                .hasSize(1)
                .allMatch(item -> item.siteId().equals(firstSiteId));

        identity.use(secondUserId);
        assertCode(() -> queryService.findDefinitions(firstSiteId, null, 0, 20),
                "MINE-SCOPE-403-002");
        assertCode(() -> commandService.createDefinition(
                        firstSiteId,
                        definition("cross-site", "跨矿班次", "08:00", "16:00", false,
                                "2026-01-01", null, 0)),
                "MINE-SCOPE-403-002");
    }

    @Test
    void 其他矿区模板不得用于当前矿区实例() {
        identity.use(secondUserId);
        ShiftDefinitionResponse otherDefinition = commandService.createDefinition(
                secondSiteId,
                definition("other-shift", "其他矿班次", "08:00", "16:00", false,
                        "2026-01-01", null, 0));

        identity.use(firstUserId);
        assertCode(() -> commandService.createInstance(
                        firstSiteId,
                        new ShiftInstanceRequest(
                                otherDefinition.id(), LocalDate.parse("2026-07-20"), null)),
                "MINE-SHIFT-404-001");
    }

    private ShiftInstanceResponse createDayInstance(String code, String businessDate) {
        ShiftDefinitionResponse definition = commandService.createDefinition(
                firstSiteId,
                definition(code, code, "08:00", "16:00", false,
                        "2026-01-01", null, 0));
        return commandService.createInstance(
                firstSiteId,
                new ShiftInstanceRequest(
                        definition.id(), LocalDate.parse(businessDate), null));
    }

    private void insertRuntimeState(ShiftInstanceResponse shift) {
        UUID vehicleId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID organizationId = shift.organizationId();
        jdbcTemplate.update("""
                INSERT INTO mine_vehicle (
                    id, organization_id, site_id, vehicle_code, name, vehicle_type
                ) VALUES (?, ?, ?, ?, '运行态矿卡', 'HAUL_TRUCK')
                """, vehicleId, organizationId, firstSiteId,
                "runtime-" + vehicleId.toString().substring(0, 8));
        jdbcTemplate.update("""
                INSERT INTO mine_work_order (
                    id, organization_id, site_id, shift_instance_id,
                    order_number, title, work_type, planned_start_at,
                    planned_end_at, planned_cycles, priority
                ) VALUES (?, ?, ?, ?, ?, '运行态作业', 'HAULAGE', ?, ?, 1, 50)
                """, orderId, organizationId, firstSiteId, shift.id(),
                "runtime-" + orderId.toString().substring(0, 8),
                Timestamp.from(shift.plannedStartAt()), Timestamp.from(shift.plannedEndAt()));
        jdbcTemplate.update("""
                INSERT INTO mine_work_order_assignment (
                    id, organization_id, site_id, work_order_id, vehicle_id,
                    valid_from, valid_to
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, assignmentId, organizationId, firstSiteId, orderId, vehicleId,
                Timestamp.from(shift.plannedStartAt()), Timestamp.from(shift.plannedEndAt()));
        jdbcTemplate.update("""
                INSERT INTO mine_operation_runtime_state (
                    id, organization_id, site_id, work_order_id, assignment_id,
                    cycle_status
                ) VALUES (?, ?, ?, ?, ?, 'LOADING')
                """, UUID.randomUUID(), organizationId, firstSiteId, orderId, assignmentId);
    }

    private ShiftDefinitionRequest definition(
            String code,
            String name,
            String start,
            String end,
            boolean crossesMidnight,
            String validFrom,
            String validTo,
            long version
    ) {
        return new ShiftDefinitionRequest(
                code, name, LocalTime.parse(start), LocalTime.parse(end), crossesMidnight,
                LocalDate.parse(validFrom), validTo == null ? null : LocalDate.parse(validTo), version);
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
                """, id, "shift-management-it-" + suffix, "班次测试用户" + suffix);
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
}
