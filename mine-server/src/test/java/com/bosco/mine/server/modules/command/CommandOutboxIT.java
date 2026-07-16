package com.bosco.mine.server.modules.command;

import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.command.model.CommandInstance;
import com.bosco.mine.server.modules.command.model.CommandStatus;
import com.bosco.mine.server.modules.command.dto.CommandResponse;
import com.bosco.mine.server.modules.command.outbox.OutboxReadyEvent;
import com.bosco.mine.server.modules.command.outbox.OutboxRelayRepository;
import com.bosco.mine.server.modules.command.repository.CommandMapper;
import com.bosco.mine.server.modules.command.service.CommandService;
import com.bosco.mine.server.modules.command.service.CommandQueryService;
import com.bosco.mine.messaging.command.CommandStatusPayload;
import com.bosco.mine.server.modules.command.status.CommandStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

/**
 * 使用真实 PostgreSQL 校验命令、幂等键和提交后 Outbox 唤醒。
 */
@Testcontainers
@ActiveProfiles("test")
@Import(CommandOutboxIT.ProbeConfiguration.class)
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class CommandOutboxIT {

    private static final UUID USER_ID = UUID.fromString("019f64b3-7364-7b6c-a889-618d3a09f401");
    private static final UUID PRODUCT_ID = UUID.fromString("019f64b3-7364-7b6c-a889-618d3a09f402");
    private static final UUID DEVICE_ID = UUID.fromString("019f64b3-7364-7b6c-a889-618d3a09f403");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private CommandService commandService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxWakeupProbe probe;

    @Autowired
    private OutboxRelayRepository outboxRelayRepository;

    @Autowired
    private CommandStatusService commandStatusService;

    @Autowired
    private CommandQueryService commandQueryService;

    @Autowired
    private CommandMapper commandMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void 准备低风险命令测试数据() {
        probe.clear();
        jdbcTemplate.update("DELETE FROM sys_outbox_event");
        jdbcTemplate.update("DELETE FROM sys_inbox_event WHERE consumer_name = 'mine-server-command-status'");
        jdbcTemplate.update("DELETE FROM cmd_instance");
        jdbcTemplate.update("DELETE FROM dev_device");
        jdbcTemplate.update("DELETE FROM dev_product");
        jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", USER_ID);
        jdbcTemplate.update("""
                INSERT INTO sys_user (
                    id, username, password_hash, display_name, status, password_change_required
                ) VALUES (?, 'command-operator', 'test-hash', '命令测试员', 'ENABLED', FALSE)
                """, USER_ID);
        jdbcTemplate.update("""
                INSERT INTO dev_product (id, product_key, name, device_type, status)
                VALUES (?, 'command-terminal', '命令终端', 'VEHICLE', 'ACTIVE')
                """, PRODUCT_ID);
        jdbcTemplate.update("""
                INSERT INTO dev_device (id, product_id, device_code, name, status)
                VALUES (?, ?, 'command-device-001', '命令测试设备', 'ENABLED')
                """, DEVICE_ID, PRODUCT_ID);
    }

    @Test
    void 应在同一事务写入命令与Outbox并在提交后按事件唤醒() {
        CommandInstance command = commandService.createLowRiskCommand(
                USER_ID,
                DEVICE_ID,
                "QUERY_STATUS",
                Map.of(),
                "request-001",
                300,
                30,
                "trace-command-001"
        );

        assertThat(command.status()).isEqualTo(CommandStatus.QUEUED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cmd_instance WHERE id = ?",
                Long.class,
                command.id()
        )).isOne();
        UUID eventId = jdbcTemplate.queryForObject("""
                SELECT id
                FROM sys_outbox_event
                WHERE aggregate_type = 'COMMAND'
                  AND aggregate_id = ?
                  AND event_type = 'command.dispatch.requested'
                  AND status = 'PENDING'
                """, UUID.class, command.id());
        assertThat(eventId).isNotNull();
        assertThat(probe.committedEventIds()).containsExactly(eventId);
        assertThat(probe.statusObservedAfterCommit()).containsExactly("PENDING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT payload ->> 'templateKey'
                FROM sys_outbox_event
                WHERE id = ?
                """, String.class, eventId)).isEqualTo("QUERY_STATUS");
    }

    @Test
    void 重复幂等键应返回原命令且不重复写出站事件() {
        CommandInstance first = createCommand("request-same");
        CommandInstance second = createCommand("request-same");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cmd_instance", Long.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_outbox_event", Long.class)).isOne();
        assertThat(probe.committedEventIds()).hasSize(1);
    }

    @Test
    void 原命令创建后设备被停用仍应允许精确幂等重试读取原结果() {
        CommandInstance first = createCommand("request-retry-after-disabled");
        jdbcTemplate.update("UPDATE dev_device SET status = 'DISABLED' WHERE id = ?", DEVICE_ID);

        CommandInstance retried = createCommand("request-retry-after-disabled");

        assertThat(retried.id()).isEqualTo(first.id());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cmd_instance", Long.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_outbox_event", Long.class)).isOne();
    }

    @Test
    void 相同幂等键绑定不同请求时应拒绝而不是返回旧命令() {
        createCommand("request-conflict");

        assertThatThrownBy(() -> commandService.createLowRiskCommand(
                USER_ID, DEVICE_ID, "QUERY_STATUS", Map.of(), "request-conflict",
                600, 30, "trace-command-conflict"
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("COMMAND-409-001"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cmd_instance", Long.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_outbox_event", Long.class)).isOne();
    }

    @Test
    void 无参数模板应拒绝夹带参数且不写命令() {
        assertThatThrownBy(() -> commandService.createLowRiskCommand(
                USER_ID, DEVICE_ID, "QUERY_STATUS", Map.of("secret", "unexpected"),
                "request-parameters", 300, 30, "trace-command-parameters"
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("COMMAND-422-003"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cmd_instance", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_outbox_event", Long.class)).isZero();
    }

    @Test
    void 回复超时大于TTL时应返回可识别业务错误() {
        assertThatThrownBy(() -> commandService.createLowRiskCommand(
                USER_ID, DEVICE_ID, "QUERY_STATUS", Map.of(), "request-time-invalid",
                30, 31, "trace-command-time-invalid"
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("COMMAND-422-004"));
    }

    @Test
    void 禁用设备应返回可识别业务错误() {
        jdbcTemplate.update("UPDATE dev_device SET status = 'DISABLED' WHERE id = ?", DEVICE_ID);

        assertThatThrownBy(() -> createCommand("request-disabled"))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("COMMAND-422-002"));
    }

    @Test
    void 命令提交持有的设备共享锁应阻止并发禁用越过检查窗口() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var lockHolder = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                assertThat(commandMapper.lockDeviceStatus(DEVICE_ID)).contains("ENABLED");
                locked.countDown();
                try {
                    assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待释放设备锁时被中断", exception);
                }
                return null;
            }));
            assertThat(locked.await(2, TimeUnit.SECONDS)).isTrue();
            var disable = executor.submit(() -> jdbcTemplate.update(
                    "UPDATE dev_device SET status = 'DISABLED' WHERE id = ?", DEVICE_ID
            ));

            assertThatThrownBy(() -> disable.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            lockHolder.get(2, TimeUnit.SECONDS);
            assertThat(disable.get(2, TimeUnit.SECONDS)).isOne();
        } finally {
            release.countDown();
        }
    }

    @Test
    void 应按设备和状态查询命令详情与分页() {
        CommandInstance command = createCommand("request-query");

        CommandResponse detail = commandQueryService.findById(command.id());
        var page = commandQueryService.findCommands(DEVICE_ID, "queued", 1, 20);
        var targets = commandQueryService.findTargets("命令", 1, 100);

        assertThat(detail.id()).isEqualTo(command.id());
        assertThat(detail.parameters()).isEmpty();
        assertThat(page.total()).isOne();
        assertThat(page.items()).extracting(CommandResponse::id).containsExactly(command.id());
        assertThat(commandQueryService.findCommands(DEVICE_ID, "SUCCEEDED", 1, 20).items()).isEmpty();
        assertThat(targets.items()).singleElement().satisfies(target -> {
            assertThat(target.id()).isEqualTo(DEVICE_ID);
            assertThat(target.deviceCode()).isEqualTo("command-device-001");
        });
    }

    @Test
    void 极大页码应返回可识别参数错误而不是算术溢出() {
        assertThatThrownBy(() -> commandQueryService.findCommands(
                null, null, Integer.MAX_VALUE, 100
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("COMMAND-400-001"));
    }

    @Test
    void 管理端下发命令应在同一事务记录脱敏操作审计() {
        try (var stpUtil = mockStatic(cn.dev33.satoken.stp.StpUtil.class)) {
            stpUtil.when(cn.dev33.satoken.stp.StpUtil::getLoginIdAsString)
                    .thenReturn(USER_ID.toString());
            CommandResponse response = commandService.createCurrentUserCommand(
                    DEVICE_ID, "QUERY_STATUS", Map.of(), "request-audit",
                    300, 30, "trace-command-audit"
            );

            Map<String, Object> audit = jdbcTemplate.queryForMap("""
                    SELECT module, action, permission, resource_type, resource_id,
                           after_summary ->> 'templateKey' AS template_key,
                           after_summary -> 'parameters' IS NOT NULL AS contains_parameters
                    FROM sys_operation_log
                    WHERE resource_id = ?
                    """, response.id().toString());
            assertThat(audit.get("module")).isEqualTo("COMMAND_CENTER");
            assertThat(audit.get("action")).isEqualTo("SUBMIT_COMMAND");
            assertThat(audit.get("permission")).isEqualTo("command:instance:create");
            assertThat(audit.get("resource_type")).isEqualTo("COMMAND");
            assertThat(audit.get("template_key")).isEqualTo("QUERY_STATUS");
            assertThat(audit.get("contains_parameters")).isEqualTo(false);
        }
    }

    @Test
    void 排队命令应在出站事件被认领前原子取消() {
        CommandInstance command = createCommand("request-cancel");
        try (var stpUtil = mockStatic(cn.dev33.satoken.stp.StpUtil.class)) {
            stpUtil.when(cn.dev33.satoken.stp.StpUtil::getLoginIdAsString)
                    .thenReturn(USER_ID.toString());

            CommandResponse cancelled = commandService.cancelCurrentUserCommand(
                    command.id(), command.version(), "现场作业计划变更"
            );

            assertThat(cancelled.status()).isEqualTo("CANCELLED");
            assertThat(cancelled.cancelReason()).isEqualTo("现场作业计划变更");
            assertThat(cancelled.cancelledBy()).isEqualTo(USER_ID);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM sys_outbox_event WHERE aggregate_id = ?",
                    String.class,
                    command.id()
            )).isEqualTo("DEAD");
        }
    }

    @Test
    void 应拒绝未发布的命令模板并且不留下半成品() {
        assertThatThrownBy(() -> commandService.createLowRiskCommand(
                USER_ID, DEVICE_ID, "RESTART_ENGINE", Map.of(), "request-invalid",
                300, 30, "trace-command-invalid"
        )).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("COMMAND-422-001"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cmd_instance", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_outbox_event", Long.class)).isZero();
        assertThat(probe.committedEventIds()).isEmpty();
    }

    @Test
    void 命令模块不得使用Scheduled定时扫表() throws IOException {
        Path commandModule = Path.of("src/main/java/com/bosco/mine/server/modules/command");
        try (var files = Files.walk(commandModule)) {
            assertThat(files.filter(path -> path.toString().endsWith(".java")))
                    .allSatisfy(path -> assertThat(Files.readString(path)).doesNotContain("@Scheduled"));
        }
    }

    @Test
    void 应使用原子条件只认领指定Outbox事件() {
        UUID first = insertOutbox("first");
        UUID second = insertOutbox("second");

        var claimed = outboxRelayRepository.claimById(
                second, "relay-it", Duration.ofSeconds(30)
        );

        assertThat(claimed).isPresent();
        assertThat(claimed.orElseThrow().eventId()).isEqualTo(second);
        assertThat(claimed.orElseThrow().attempts()).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM sys_outbox_event WHERE id = ?",
                String.class, first
        )).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM sys_outbox_event WHERE id = ?",
                String.class, second
        )).isEqualTo("PUBLISHING");
        assertThat(outboxRelayRepository.claimById(
                second, "other-relay", Duration.ofSeconds(30)
        )).isEmpty();
    }

    @Test
    void 启动恢复查询必须遵守固定数量上限() {
        insertOutbox("recovery-1");
        insertOutbox("recovery-2");
        insertOutbox("recovery-3");

        assertThat(outboxRelayRepository.findRecoverableEventIds(2)).hasSize(2);
    }

    @Test
    void 命令中继不得认领其他业务模块的Outbox事件() {
        UUID commandEvent = insertOutbox("command-only");
        UUID configEvent = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_outbox_event (
                    id, aggregate_type, aggregate_id, event_type, schema_version, payload
                ) VALUES (?, 'CONFIG', ?, 'config.release.requested', 1, '{}'::JSONB)
                """, configEvent, UUID.randomUUID());

        assertThat(outboxRelayRepository.findRecoverableEventIds(10))
                .containsExactly(commandEvent);
        assertThat(outboxRelayRepository.claimById(
                configEvent, "relay-it", Duration.ofSeconds(30)
                )).isEmpty();
    }

    @Test
    void 平台中继应认领精确的配置发布事件() {
        UUID configEvent = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_outbox_event (
                    id, aggregate_type, aggregate_id, event_type, schema_version, payload, headers
                ) VALUES (?, 'CONFIG_RELEASE', ?, 'config.release.created', 1, '{}'::JSONB, '{}'::JSONB)
                """, configEvent, UUID.randomUUID());

        assertThat(outboxRelayRepository.claimById(
                configEvent, "relay-it", Duration.ofSeconds(30)
        )).isPresent();
    }

    @Test
    void 设备终态先于发送状态到达时应补全时间线并保持终态() {
        CommandInstance command = createCommand("request-out-of-order");
        Instant receivedAt = Instant.parse("2026-07-15T08:00:02Z");
        Instant completedAt = receivedAt.plusSeconds(1);
        UUID succeededEventId = UUID.fromString("019f64e5-a000-7000-8000-000000000071");

        assertThat(commandStatusService.apply(succeededEventId, new CommandStatusPayload(
                command.id(), DEVICE_ID, "SUCCEEDED", completedAt, receivedAt,
                Map.of("online", true, "secretToken", "must-not-be-stored"), null
        ))).isTrue();
        assertThat(commandStatusService.apply(succeededEventId, new CommandStatusPayload(
                command.id(), DEVICE_ID, "SUCCEEDED", completedAt, receivedAt,
                Map.of("online", true), null
        ))).isFalse();
        assertThat(commandStatusService.apply(
                UUID.fromString("019f64e5-a000-7000-8000-000000000072"),
                new CommandStatusPayload(
                        command.id(), DEVICE_ID, "SENT", completedAt.plusSeconds(1),
                        null, Map.of(), null
                )
        )).isFalse();

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT status, sent_at, device_received_at, completed_at,
                       reply ->> 'online' AS online,
                       reply -> 'secretToken' IS NOT NULL AS contains_secret, version
                FROM cmd_instance
                WHERE id = ?
                """, command.id());
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(((java.sql.Timestamp) row.get("sent_at")).toInstant()).isEqualTo(receivedAt);
        assertThat(((java.sql.Timestamp) row.get("device_received_at")).toInstant()).isEqualTo(receivedAt);
        assertThat(((java.sql.Timestamp) row.get("completed_at")).toInstant()).isEqualTo(completedAt);
        assertThat(row.get("online")).isEqualTo("true");
        assertThat(row.get("contains_secret")).isEqualTo(false);
        assertThat(commandQueryService.findById(command.id()).reply())
                .containsEntry("online", true)
                .doesNotContainKey("secretToken");
        assertThat(((Number) row.get("version")).longValue()).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_inbox_event
                WHERE consumer_name = 'mine-server-command-status'
                """, Long.class)).isEqualTo(2L);
    }

    @Test
    void 发送后未收到设备回复时应进入超时终态() {
        CommandInstance command = createCommand("request-timeout");
        Instant sentAt = Instant.parse("2026-07-15T08:10:00Z");
        Instant timeoutAt = sentAt.plusSeconds(30);

        assertThat(commandStatusService.apply(
                UUID.fromString("019f64e5-a000-7000-8000-000000000073"),
                new CommandStatusPayload(command.id(), DEVICE_ID, "SENT", sentAt, null, Map.of(), null)
        )).isTrue();
        assertThat(commandStatusService.apply(
                UUID.fromString("019f64e5-a000-7000-8000-000000000074"),
                new CommandStatusPayload(
                        command.id(), DEVICE_ID, "TIMEOUT", timeoutAt, null,
                        Map.of(), "DEVICE_ACK_TIMEOUT"
                )
        )).isTrue();

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT status, sent_at, completed_at, last_error_code, version
                FROM cmd_instance
                WHERE id = ?
                """, command.id());
        assertThat(row.get("status")).isEqualTo("TIMEOUT");
        assertThat(((java.sql.Timestamp) row.get("sent_at")).toInstant()).isEqualTo(sentAt);
        assertThat(((java.sql.Timestamp) row.get("completed_at")).toInstant()).isEqualTo(timeoutAt);
        assertThat(row.get("last_error_code")).isEqualTo("DEVICE_ACK_TIMEOUT");
        assertThat(((Number) row.get("version")).longValue()).isEqualTo(3L);
    }

    private CommandInstance createCommand(String idempotencyKey) {
        return commandService.createLowRiskCommand(
                USER_ID, DEVICE_ID, "QUERY_STATUS", Map.of(), idempotencyKey,
                300, 30, "trace-command-idempotent"
        );
    }

    private UUID insertOutbox(String aggregateIdSeed) {
        UUID eventId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_outbox_event (
                    id, aggregate_type, aggregate_id, event_type, schema_version, payload
                ) VALUES (?, 'COMMAND', ?, 'command.dispatch.requested', 1, '{}'::JSONB)
                """, eventId, UUID.nameUUIDFromBytes(aggregateIdSeed.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return eventId;
    }

    /**
     * 提供只用于集成测试的提交事件观测器。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        /**
         * 创建 Outbox 提交后唤醒观测器。
         *
         * @param jdbcTemplate 数据库访问模板
         * @return 观测器
         */
        @Bean
        OutboxWakeupProbe outboxWakeupProbe(JdbcTemplate jdbcTemplate) {
            return new OutboxWakeupProbe(jdbcTemplate);
        }
    }

    /**
     * 在应用事件回调中立即查询 Outbox，验证回调发生在事务提交之后。
     */
    static final class OutboxWakeupProbe implements ApplicationListener<OutboxReadyEvent> {
        private final JdbcTemplate jdbcTemplate;
        private final List<UUID> eventIds = new ArrayList<>();
        private final List<String> observedStatuses = new ArrayList<>();

        OutboxWakeupProbe(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public void onApplicationEvent(OutboxReadyEvent event) {
            eventIds.add(event.eventId());
            observedStatuses.add(jdbcTemplate.queryForObject(
                    "SELECT status FROM sys_outbox_event WHERE id = ?",
                    String.class,
                    event.eventId()
            ));
        }

        List<UUID> committedEventIds() {
            return List.copyOf(eventIds);
        }

        List<String> statusObservedAfterCommit() {
            return List.copyOf(observedStatuses);
        }

        void clear() {
            eventIds.clear();
            observedStatuses.clear();
        }
    }
}
