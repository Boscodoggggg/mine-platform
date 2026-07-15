package com.bosco.mine.server.modules.command;

import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.command.model.CommandInstance;
import com.bosco.mine.server.modules.command.model.CommandStatus;
import com.bosco.mine.server.modules.command.outbox.OutboxReadyEvent;
import com.bosco.mine.server.modules.command.outbox.OutboxRelayRepository;
import com.bosco.mine.server.modules.command.service.CommandService;
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
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @BeforeEach
    void 准备低风险命令测试数据() {
        probe.clear();
        jdbcTemplate.update("DELETE FROM sys_outbox_event");
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
                Map.of("includeLocation", true),
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
    void 应拒绝未发布的命令模板并且不留下半成品() {
        assertThatThrownBy(() -> commandService.createLowRiskCommand(
                USER_ID, DEVICE_ID, "RESTART_ENGINE", Map.of(), "request-invalid",
                300, 30, "trace-command-invalid"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("低风险命令模板");

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
