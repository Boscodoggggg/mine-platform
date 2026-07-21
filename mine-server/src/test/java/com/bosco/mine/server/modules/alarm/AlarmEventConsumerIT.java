package com.bosco.mine.server.modules.alarm;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.alarm.AlarmEvidencePayload;
import com.bosco.mine.messaging.alarm.AlarmRaisedPayload;
import com.bosco.mine.messaging.alarm.AlarmRecoveredPayload;
import com.bosco.mine.messaging.topology.MineTopology;
import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.alarm.domain.AlarmStatus;
import com.bosco.mine.server.modules.alarm.messaging.AlarmEventConsumer;
import com.bosco.mine.server.modules.alarm.repository.AlarmEventRepository;
import com.bosco.mine.server.modules.alarm.repository.AlarmEventRepository.AlarmQuery;
import com.bosco.mine.server.modules.alarm.service.AlarmEventService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 使用真实 PostgreSQL 校验告警消息 Inbox、恢复先到和触发迟到补齐事务。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(classes = MineServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AlarmEventConsumerIT {
    private static final UUID PRODUCT_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000431");
    private static final UUID DEVICE_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000432");
    private static final UUID ALARM_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000433");
    private static final UUID SNAPSHOT_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000434");
    private static final UUID OPERATOR_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000437");
    private static final UUID RECOVERED_EVENT_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000435");
    private static final UUID RAISED_EVENT_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000436");
    private static final UUID ORGANIZATION_A_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000451");
    private static final UUID ORGANIZATION_B_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000452");
    private static final UUID SITE_A_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000453");
    private static final UUID SITE_B_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000454");
    private static final UUID VEHICLE_A_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000455");
    private static final UUID VEHICLE_B_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000456");
    private static final UUID DEVICE_B_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000457");
    private static final UUID ALARM_B_ID = UUID.fromString("019f64e5-a000-7000-8000-000000000458");
    private static final Instant RAISED_AT = Instant.parse("2026-07-17T01:00:00Z");
    private static final Instant RECOVERED_AT = Instant.parse("2026-07-17T01:05:00Z");
    private static final String HASH = "sha256:" + "a".repeat(64);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AlarmEventService service;

    @Autowired
    private AlarmEventRepository repository;

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @BeforeEach
    void 创建设备台账() {
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password_hash, display_name)
                VALUES (?, 'alarm-it-operator', 'not-a-real-password-hash', '告警集成测试操作人')
                """, OPERATOR_ID);
        jdbcTemplate.update("""
                INSERT INTO dev_product (id, product_key, name, device_type)
                VALUES (?, 'haul-truck', '矿卡产品', 'VEHICLE')
                """, PRODUCT_ID);
        jdbcTemplate.update("""
                INSERT INTO dev_device (id, product_id, device_code, name)
                VALUES (?, ?, 'truck-alarm-it', '告警集成测试矿卡')
                """, DEVICE_ID, PRODUCT_ID);
    }

    @AfterEach
    void 清理告警和设备台账() {
        jdbcTemplate.update(
                "DELETE FROM sys_operation_log WHERE resource_type = 'ALARM_EVENT' AND resource_id = ?",
                ALARM_ID.toString());
        jdbcTemplate.update("DELETE FROM sys_inbox_event WHERE consumer_name = 'mine-server-alarm-event'");
        jdbcTemplate.update("DELETE FROM alarm_acknowledgement WHERE alarm_id = ?", ALARM_ID);
        jdbcTemplate.update("DELETE FROM alarm_event WHERE id = ?", ALARM_ID);
        jdbcTemplate.update("DELETE FROM dev_device WHERE id = ?", DEVICE_ID);
        jdbcTemplate.update("DELETE FROM dev_product WHERE id = ?", PRODUCT_ID);
        jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", OPERATOR_ID);
    }

    @Test
    void 恢复先到并触发后到应保留恢复事实同时补齐触发证据() throws Exception {
        Channel channel = mock(Channel.class);
        AlarmEventConsumer consumer = new AlarmEventConsumer(service);

        consumer.onMessage(message(81L, recoveredEnvelope()), channel);
        consumer.onMessage(message(82L, raisedEnvelope()), channel);

        verify(channel).basicAck(81L, false);
        verify(channel).basicAck(82L, false);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT raised_event_id, recovered_event_id, recovered_at,
                       raised_evidence IS NOT NULL AS has_raised_evidence,
                       recovered_evidence IS NOT NULL AS has_recovered_evidence,
                       version
                FROM alarm_event
                WHERE id = ?
                """, ALARM_ID))
                .containsEntry("raised_event_id", RAISED_EVENT_ID)
                .containsEntry("recovered_event_id", RECOVERED_EVENT_ID)
                .containsEntry("has_raised_evidence", true)
                .containsEntry("has_recovered_evidence", true)
                .containsEntry("version", 1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_inbox_event
                WHERE consumer_name = 'mine-server-alarm-event'
                """, Long.class)).isEqualTo(2L);
        assertThat(repository.countActiveBySeverity("CRITICAL")).isZero();
    }

    @Test
    void 同一消息重复投递应确认但只落一条Inbox和告警记录() throws Exception {
        Channel channel = mock(Channel.class);
        AlarmEventConsumer consumer = new AlarmEventConsumer(service);
        Message message = message(83L, raisedEnvelope());

        consumer.onMessage(message, channel);
        MessageProperties repeatedProperties = new MessageProperties();
        repeatedProperties.setDeliveryTag(84L);
        consumer.onMessage(new Message(message.getBody(), repeatedProperties), channel);

        verify(channel).basicAck(83L, false);
        verify(channel).basicAck(84L, false);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alarm_event WHERE id = ?", Long.class, ALARM_ID)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_inbox_event
                WHERE consumer_name = 'mine-server-alarm-event' AND event_id = ?
                """, Long.class, RAISED_EVENT_ID)).isEqualTo(1L);
    }

    @Test
    void 确认和关闭重试应各只写一次事实和审计() throws Exception {
        AlarmEventConsumer consumer = new AlarmEventConsumer(service);
        consumer.onMessage(message(85L, recoveredEnvelope()), mock(Channel.class));

        var acknowledged = service.acknowledge(
                ALARM_ID, OPERATOR_ID, "已通知调度", "ack-it-001", 0);
        var repeatedAcknowledgement = service.acknowledge(
                ALARM_ID, OPERATOR_ID, "重复确认", "ack-it-002", 0);
        var closed = service.close(
                ALARM_ID, OPERATOR_ID, "现场复核完成", "close-it-001", 1);
        var repeatedClose = service.close(
                ALARM_ID, OPERATOR_ID, "重复关闭", "close-it-002", 1);

        assertThat(acknowledged.status()).isEqualTo(AlarmStatus.RECOVERED_ACKNOWLEDGED);
        assertThat(repeatedAcknowledgement.version()).isEqualTo(1);
        assertThat(closed.status()).isEqualTo(AlarmStatus.CLOSED);
        assertThat(repeatedClose.version()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alarm_acknowledgement WHERE alarm_id = ?",
                Long.class, ALARM_ID)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_operation_log
                WHERE module = 'ALARM' AND resource_type = 'ALARM_EVENT' AND resource_id = ?
                """, Long.class, ALARM_ID.toString())).isEqualTo(2L);
    }

    @Test
    void 告警查询应支持状态设备规则等级和半开时间范围组合过滤() throws Exception {
        new AlarmEventConsumer(service).onMessage(
                message(86L, raisedEnvelope()), mock(Channel.class));

        var page = service.findGlobal(
                null, false, AlarmStatus.ACTIVE_UNACKNOWLEDGED, "CRITICAL", DEVICE_ID,
                "集成测试矿卡", "engine-hot", RAISED_AT.minusSeconds(1), RAISED_AT.plusSeconds(1), 1, 20);
        var excluded = service.findGlobal(
                null, false, AlarmStatus.ACTIVE_UNACKNOWLEDGED, "WARNING", DEVICE_ID,
                "不存在设备", "engine-hot", RAISED_AT, RAISED_AT.plusSeconds(1), 1, 20);

        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.items()).singleElement()
                .satisfies(alarm -> {
                    assertThat(alarm.id()).isEqualTo(ALARM_ID);
                    assertThat(alarm.status()).isEqualTo(AlarmStatus.ACTIVE_UNACKNOWLEDGED);
                    assertThat(alarm.raisedEvidence()).containsEntry("operator", "GT");
                });
        assertThat(excluded.total()).isZero();
        assertThat(repository.countActiveBySeverity("CRITICAL")).isEqualTo(1L);
    }

    @Test
    void 默认未关闭查询应包含恢复但尚未确认的告警() throws Exception {
        new AlarmEventConsumer(service).onMessage(
                message(89L, recoveredEnvelope()), mock(Channel.class));

        var page = service.findGlobal(
                null, false, null, null, null, "truck-alarm", null,
                null, null, 1, 20);

        assertThat(page.items()).singleElement()
                .satisfies(alarm -> assertThat(alarm.status())
                        .isEqualTo(AlarmStatus.RECOVERED_UNACKNOWLEDGED));
    }

    @Test
    void 矿区查询应在分页前按触发时设备绑定隔离两个组织的告警() throws Exception {
        try {
            创建双组织矿区绑定();
            new AlarmEventConsumer(service).onMessage(
                    message(90L, raisedEnvelope()), mock(Channel.class));
            jdbcTemplate.update("""
                    INSERT INTO alarm_event (
                        id, device_id, product_key, rule_key, rule_name, source_type, severity,
                        property_id, policy_runtime_revision, policy_snapshot_id, policy_content_hash,
                        raised_at, raised_evidence, raised_event_id
                    ) VALUES (?, ?, 'haul-truck', 'engine-hot', '发动机过热', 'NUMERIC', 'CRITICAL',
                              'engineTemp', 7, ?, ?, ?, '{}'::jsonb, ?)
                    """, ALARM_B_ID, DEVICE_B_ID, SNAPSHOT_ID, HASH,
                    java.sql.Timestamp.from(RAISED_AT.plusSeconds(10)), UUID.randomUUID());

            AlarmQuery siteA = new AlarmQuery(
                    SITE_A_ID, null, false, null, null, null, null, null, null, null);
            AlarmQuery siteB = new AlarmQuery(
                    SITE_B_ID, null, false, null, null, null, null, null, null, null);

            assertThat(repository.find(siteA, 1, 1))
                    .singleElement()
                    .satisfies(alarm -> assertThat(alarm.id()).isEqualTo(ALARM_ID));
            assertThat(repository.count(siteA)).isEqualTo(1L);
            assertThat(repository.find(siteB, 1, 1))
                    .singleElement()
                    .satisfies(alarm -> assertThat(alarm.id()).isEqualTo(ALARM_B_ID));
            assertThat(repository.count(siteB)).isEqualTo(1L);
        } finally {
            清理双组织矿区绑定();
        }
    }

    @Test
    void 属性级质量告警应能完成触发和恢复闭环() throws Exception {
        Channel channel = mock(Channel.class);
        AlarmEventConsumer consumer = new AlarmEventConsumer(service);

        consumer.onMessage(message(87L, qualityRaisedEnvelope()), channel);
        consumer.onMessage(message(88L, qualityRecoveredEnvelope()), channel);

        verify(channel).basicAck(87L, false);
        verify(channel).basicAck(88L, false);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT source_type, property_id, raised_event_id, recovered_event_id, recovered_at
                FROM alarm_event
                WHERE id = ?
                """, ALARM_ID))
                .containsEntry("source_type", "QUALITY")
                .containsEntry("property_id", "signalQuality")
                .containsEntry("raised_event_id", RAISED_EVENT_ID)
                .containsEntry("recovered_event_id", RECOVERED_EVENT_ID);
    }

    private void 创建双组织矿区绑定() {
        jdbcTemplate.update("""
                INSERT INTO mine_organization (id, organization_code, name)
                VALUES (?, 'alarm-org-a', '告警组织甲'), (?, 'alarm-org-b', '告警组织乙')
                """, ORGANIZATION_A_ID, ORGANIZATION_B_ID);
        jdbcTemplate.update("""
                INSERT INTO mine_site (id, organization_id, site_code, name, timezone)
                VALUES (?, ?, 'alarm-site-a', '告警矿区甲', 'Asia/Shanghai'),
                       (?, ?, 'alarm-site-b', '告警矿区乙', 'Asia/Shanghai')
                """, SITE_A_ID, ORGANIZATION_A_ID, SITE_B_ID, ORGANIZATION_B_ID);
        jdbcTemplate.update("""
                INSERT INTO dev_device (id, product_id, device_code, name)
                VALUES (?, ?, 'truck-alarm-it-b', '告警集成测试矿卡乙')
                """, DEVICE_B_ID, PRODUCT_ID);
        jdbcTemplate.update("""
                INSERT INTO mine_vehicle (
                    id, organization_id, site_id, vehicle_code, name, vehicle_type
                ) VALUES (?, ?, ?, 'alarm-truck-a', '告警矿卡甲', 'HAUL_TRUCK'),
                         (?, ?, ?, 'alarm-truck-b', '告警矿卡乙', 'HAUL_TRUCK')
                """, VEHICLE_A_ID, ORGANIZATION_A_ID, SITE_A_ID,
                VEHICLE_B_ID, ORGANIZATION_B_ID, SITE_B_ID);
        jdbcTemplate.update("""
                INSERT INTO mine_vehicle_device_binding (
                    id, organization_id, site_id, vehicle_id, device_id,
                    binding_role, valid_from, reason
                ) VALUES (?, ?, ?, ?, ?, 'PRIMARY', ?, '告警矿区隔离测试'),
                         (?, ?, ?, ?, ?, 'PRIMARY', ?, '告警矿区隔离测试')
                """, UUID.randomUUID(), ORGANIZATION_A_ID, SITE_A_ID, VEHICLE_A_ID, DEVICE_ID,
                java.sql.Timestamp.from(RAISED_AT.minusSeconds(3600)), UUID.randomUUID(), ORGANIZATION_B_ID,
                SITE_B_ID, VEHICLE_B_ID, DEVICE_B_ID,
                java.sql.Timestamp.from(RAISED_AT.minusSeconds(3600)));
    }

    private void 清理双组织矿区绑定() {
        jdbcTemplate.update("DELETE FROM alarm_event WHERE id = ?", ALARM_B_ID);
        jdbcTemplate.update(
                "DELETE FROM mine_vehicle_device_binding WHERE site_id IN (?, ?)", SITE_A_ID, SITE_B_ID);
        jdbcTemplate.update("DELETE FROM mine_vehicle WHERE site_id IN (?, ?)", SITE_A_ID, SITE_B_ID);
        jdbcTemplate.update("DELETE FROM mine_site WHERE id IN (?, ?)", SITE_A_ID, SITE_B_ID);
        jdbcTemplate.update(
                "DELETE FROM mine_organization WHERE id IN (?, ?)", ORGANIZATION_A_ID, ORGANIZATION_B_ID);
        jdbcTemplate.update("DELETE FROM dev_device WHERE id = ?", DEVICE_B_ID);
    }

    private byte[] raisedEnvelope() throws Exception {
        AlarmRaisedPayload payload = new AlarmRaisedPayload(
                ALARM_ID, DEVICE_ID, "haul-truck", "engine-hot", "发动机过热",
                "NUMERIC", "CRITICAL", "engineTemp", evidence("90"),
                7, SNAPSHOT_ID, HASH, RAISED_AT);
        return envelope(RAISED_EVENT_ID, MineTopology.ALARM_RAISED_ROUTING_KEY, RAISED_AT, payload);
    }

    private byte[] recoveredEnvelope() throws Exception {
        AlarmRecoveredPayload payload = new AlarmRecoveredPayload(
                ALARM_ID, DEVICE_ID, "haul-truck", "engine-hot", "发动机过热",
                "NUMERIC", "CRITICAL", "engineTemp", evidence("70"),
                7, SNAPSHOT_ID, HASH, RAISED_AT, RECOVERED_AT, "CONDITION_CLEARED");
        return envelope(
                RECOVERED_EVENT_ID, MineTopology.ALARM_RECOVERED_ROUTING_KEY, RECOVERED_AT, payload);
    }

    private byte[] qualityRaisedEnvelope() throws Exception {
        AlarmRaisedPayload payload = new AlarmRaisedPayload(
                ALARM_ID, DEVICE_ID, "haul-truck", "signal-invalid", "信号质量异常",
                "QUALITY", "WARNING", "signalQuality", qualityEvidence(),
                8, SNAPSHOT_ID, HASH, RAISED_AT);
        return envelope(RAISED_EVENT_ID, MineTopology.ALARM_RAISED_ROUTING_KEY, RAISED_AT, payload);
    }

    private byte[] qualityRecoveredEnvelope() throws Exception {
        AlarmRecoveredPayload payload = new AlarmRecoveredPayload(
                ALARM_ID, DEVICE_ID, "haul-truck", "signal-invalid", "信号质量异常",
                "QUALITY", "WARNING", "signalQuality", qualityEvidence(),
                8, SNAPSHOT_ID, HASH, RAISED_AT, RECOVERED_AT, "CONDITION_CLEARED");
        return envelope(
                RECOVERED_EVENT_ID, MineTopology.ALARM_RECOVERED_ROUTING_KEY, RECOVERED_AT, payload);
    }

    private byte[] envelope(UUID eventId, String eventType, Instant occurredAt, Object payload)
            throws Exception {
        MessageEnvelope<Object> envelope = new MessageEnvelope<>(
                eventId, MessageType.of(eventType), 1, occurredAt, occurredAt.plusSeconds(1),
                "mine-telemetry", "trace-alarm-it", ALARM_ID.toString(), DEVICE_ID.toString(),
                "mine-local", payload);
        return jsonMapper.writeValueAsBytes(envelope);
    }

    private AlarmEvidencePayload evidence(String observed) {
        return new AlarmEvidencePayload(
                new BigDecimal(observed), "GT", new BigDecimal("80"),
                new BigDecimal("75"), null);
    }

    private AlarmEvidencePayload qualityEvidence() {
        return new AlarmEvidencePayload(null, null, null, null, "REQUIRED_MISSING");
    }

    private Message message(long deliveryTag, byte[] body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        return new Message(body, properties);
    }
}
