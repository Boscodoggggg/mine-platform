package com.bosco.mine.telemetry.storage;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.model.message.TelemetryPayload;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import com.bosco.mine.model.message.UnifiedDeviceMessage.MessageKind;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.telemetry.alarm.AlarmPolicy;
import com.bosco.mine.telemetry.consumer.NormalizedTelemetryConsumer;
import com.bosco.mine.telemetry.latest.LatestValueRepository;
import com.bosco.mine.telemetry.pipeline.TelemetrySample;
import com.bosco.mine.telemetry.quality.DataQualityEvaluator;
import com.bosco.mine.telemetry.quality.DataQualityPolicyRepository;
import com.bosco.mine.telemetry.quality.DataQualityPolicy;
import com.bosco.mine.telemetry.quality.QualityEvaluationCoverage;
import com.bosco.mine.telemetry.quality.QualityEvidence;
import com.bosco.mine.telemetry.quality.QualityRuleCode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 使用真实 Redis 和 TimescaleDB 校验遥测最新值、幂等与历史批写。
 */
@Testcontainers
class TelemetryStorageIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");

    private static final UUID DEVICE_ID = UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e75d");
    private static final UUID EVENT_ID = UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e761");
    private static final Instant OCCURRED_AT = Instant.parse("2026-07-15T03:00:00Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-07-15T03:00:02Z");
    private static final String TEST_EXCHANGE = "mine.telemetry.test.x";
    private static final String TEST_QUEUE = "mine.telemetry.test.q";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine")
            .withExposedPorts(6379);

    @Container
    static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_telemetry")
            .withUsername("mine_ts")
            .withPassword("mine-timescale-test-password");

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:4.1.8-management");

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static LatestValueRepository latestValueRepository;
    private static DataSource dataSource;
    private static TelemetryBatchWriter telemetryBatchWriter;
    private static CachingConnectionFactory rabbitConnectionFactory;
    private static CachingConnectionFactory rabbitConsumerConnectionFactory;
    private static RabbitAdmin rabbitAdmin;

    @BeforeAll
    static void 创建Redis连接() throws Exception {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        latestValueRepository = new LatestValueRepository(redisTemplate);
        DriverManagerDataSource driverManagerDataSource = new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword()
        );
        dataSource = driverManagerDataSource;
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        telemetryBatchWriter = new TelemetryBatchWriter(dataSource);
        rabbitConnectionFactory = new CachingConnectionFactory(
                RABBITMQ.getHost(), RABBITMQ.getAmqpPort()
        );
        rabbitConnectionFactory.setUsername(RABBITMQ.getAdminUsername());
        rabbitConnectionFactory.setPassword(RABBITMQ.getAdminPassword());
        rabbitAdmin = new RabbitAdmin(rabbitConnectionFactory);
        TopicExchange exchange = new TopicExchange(TEST_EXCHANGE, true, false);
        Queue queue = new Queue(TEST_QUEUE, true, false, false);
        rabbitAdmin.declareExchange(exchange);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("telemetry.#"));
        assertThat(RABBITMQ.execInContainer(
                "rabbitmqctl", "add_user", "mine-telemetry-test", "telemetry-test-password"
        ).getExitCode()).isZero();
        assertThat(RABBITMQ.execInContainer(
                "rabbitmqctl", "set_permissions", "-p", "/", "mine-telemetry-test",
                "^$", "^$", "^mine\\.telemetry\\.test\\.q$"
        ).getExitCode()).isZero();
        rabbitConsumerConnectionFactory = new CachingConnectionFactory(
                RABBITMQ.getHost(), RABBITMQ.getAmqpPort()
        );
        rabbitConsumerConnectionFactory.setUsername("mine-telemetry-test");
        rabbitConsumerConnectionFactory.setPassword("telemetry-test-password");
    }

    @AfterAll
    static void 关闭Redis连接() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (rabbitConnectionFactory != null) {
            rabbitConnectionFactory.destroy();
        }
        if (rabbitConsumerConnectionFactory != null) {
            rabbitConsumerConnectionFactory.destroy();
        }
    }

    @BeforeEach
    void 清空存储() throws Exception {
        connectionFactory.getConnection().serverCommands().flushDb();
        execute("""
                TRUNCATE TABLE telemetry_outbox_event, telemetry_alarm_runtime_state,
                               telemetry_quality_evidence, telemetry_inbox, telemetry_numeric,
                               telemetry_latest_numeric, telemetry_latest_state,
                               telemetry_retired_boot, telemetry_data_quality_policy,
                               telemetry_alarm_policy
                """);
        rabbitAdmin.purgeQueue(TEST_QUEUE, false);
    }

    @Test
    void 最新值应原子拒绝迟到数据并允许相同时间的更高序号获胜() {
        TelemetrySample sequenceSeven = sample(EVENT_ID, 7L, 1280D, OCCURRED_AT);
        TelemetrySample late = sample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e762"),
                6L,
                900D,
                OCCURRED_AT.minusSeconds(30)
        );
        TelemetrySample sequenceEight = sample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e763"),
                8L,
                1350D,
                OCCURRED_AT
        );
        TelemetrySample outOfOrderWithLaterTime = sample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e764"),
                "boot-a",
                6L,
                "engine.rpm",
                1500D,
                OCCURRED_AT.plusSeconds(30)
        );
        TelemetrySample restarted = sample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e765"),
                "boot-b",
                0L,
                "engine.rpm",
                1400D,
                OCCURRED_AT
        );
        TelemetrySample retiredBoot = sample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e766"),
                "boot-a",
                9L,
                "engine.rpm",
                1600D,
                OCCURRED_AT.plusSeconds(60)
        );

        assertThat(latestValueRepository.updateIfNewer(sequenceSeven)).isTrue();
        assertThat(latestValueRepository.updateIfNewer(late)).isFalse();
        assertThat(latestValueRepository.updateIfNewer(outOfOrderWithLaterTime)).isFalse();
        assertThat(latestValueRepository.updateIfNewer(sequenceEight)).isTrue();
        assertThat(latestValueRepository.updateIfNewer(restarted)).isTrue();
        assertThat(latestValueRepository.updateIfNewer(retiredBoot)).isFalse();
        assertThat(latestValueRepository.updateIfNewer(restarted)).isFalse();

        LatestValueRepository.LatestValue latest = latestValueRepository
                .find(DEVICE_ID, "engine.rpm")
                .orElseThrow();
        assertThat(latest.value()).isEqualTo(1400D);
        assertThat(latest.eventId()).isEqualTo(restarted.eventId());
        assertThat(latest.bootId()).isEqualTo("boot-b");
        assertThat(latest.sequence()).isZero();
        assertThat(redisTemplate.getExpire("mine:latest:" + DEVICE_ID)).isEqualTo(-1L);
        assertThat(redisTemplate.getExpire("mine:latest-order:" + DEVICE_ID)).isEqualTo(-1L);
        assertThat(redisTemplate.getExpire("mine:latest-state:" + DEVICE_ID)).isEqualTo(-1L);
        assertThat(redisTemplate.getExpire("mine:latest-retired-boots:" + DEVICE_ID)).isEqualTo(-1L);
    }

    @Test
    void 持久最新值应拒绝已退役启动周期且历史仍保留迟到数据() throws Exception {
        TelemetrySample bootA = sample(EVENT_ID, 7L, 1280D, OCCURRED_AT);
        TelemetrySample bootB = sample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e767"),
                "boot-b",
                0L,
                "engine.rpm",
                1400D,
                OCCURRED_AT.plusSeconds(10)
        );
        TelemetrySample retiredBootA = sample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e768"),
                "boot-a",
                9L,
                "engine.rpm",
                1600D,
                OCCURRED_AT.plusSeconds(60)
        );

        assertThat(telemetryBatchWriter.write(List.of(bootA)).latestEventIds())
                .containsExactly(bootA.eventId());
        assertThat(telemetryBatchWriter.write(List.of(bootB)).latestEventIds())
                .containsExactly(bootB.eventId());
        assertThat(telemetryBatchWriter.write(List.of(retiredBootA)).latestEventIds()).isEmpty();

        assertThat(queryLong("SELECT count(*) FROM telemetry_numeric")).isEqualTo(3L);
        assertThat(queryLong("""
                SELECT count(*) FROM telemetry_latest_numeric
                WHERE device_id = '019f63e2-071f-706a-bd12-4dad7150e75d'
                  AND property_id = 'engine.rpm' AND value_double = 1400
                """)).isOne();
        assertThat(queryLong("""
                SELECT count(*) FROM telemetry_retired_boot
                WHERE device_id = '019f63e2-071f-706a-bd12-4dad7150e75d'
                  AND boot_id = 'boot-a'
                """)).isOne();
        assertThat(queryLong("""
                SELECT count(*) FROM telemetry_latest_state
                WHERE device_id = '019f63e2-071f-706a-bd12-4dad7150e75d'
                  AND current_boot_id = 'boot-b' AND highest_sequence = 0
                """)).isOne();
    }

    @Test
    void 设备序号从零跳到二时应记录一个缺失序号而不是回滚() throws Exception {
        TelemetrySample sequenceZero = sample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e770"),
                "boot-zero",
                0L,
                "engine.rpm",
                1000D,
                OCCURRED_AT
        );
        TelemetrySample sequenceTwo = sample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e772"),
                "boot-zero",
                2L,
                "engine.rpm",
                1002D,
                OCCURRED_AT.plusSeconds(2)
        );

        assertThat(telemetryBatchWriter.write(List.of(sequenceZero)).sequenceGaps()).isEmpty();
        assertThat(telemetryBatchWriter.write(List.of(sequenceTwo)).sequenceGaps())
                .containsExactly(new TelemetryBatchWriter.SequenceGap(
                        sequenceTwo.eventId(), DEVICE_ID, "boot-zero", 0L, 2L, 1L
                ));
        assertThat(queryLong("SELECT count(*) FROM telemetry_inbox")).isEqualTo(2L);
        assertThat(queryLong("SELECT count(*) FROM telemetry_numeric")).isEqualTo(2L);
    }

    @Test
    void 首个可靠启动周期即使时间较早也应记录序号并拒绝后续小序号() throws Exception {
        TelemetrySample unreliable = sample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e769"),
                null,
                null,
                "engine.rpm",
                1000D,
                OCCURRED_AT.plusSeconds(100)
        );
        TelemetrySample reliableTen = sample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e76a"),
                "boot-a",
                10L,
                "engine.rpm",
                1100D,
                OCCURRED_AT.plusSeconds(90)
        );
        TelemetrySample reliableFive = sample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e76b"),
                "boot-a",
                5L,
                "engine.rpm",
                1200D,
                OCCURRED_AT.plusSeconds(110)
        );

        assertThat(latestValueRepository.updateIfNewer(unreliable)).isTrue();
        assertThat(latestValueRepository.updateIfNewer(reliableTen)).isFalse();
        assertThat(latestValueRepository.updateIfNewer(reliableFive)).isFalse();
        assertThat(latestValueRepository.find(DEVICE_ID, "engine.rpm").orElseThrow().value())
                .isEqualTo(1000D);

        assertThat(telemetryBatchWriter.write(List.of(unreliable)).latestEventIds())
                .containsExactly(unreliable.eventId());
        assertThat(telemetryBatchWriter.write(List.of(reliableTen)).latestEventIds()).isEmpty();
        assertThat(telemetryBatchWriter.write(List.of(reliableFive)).latestEventIds()).isEmpty();
        assertThat(queryLong("""
                SELECT count(*) FROM telemetry_latest_state
                WHERE device_id = '019f63e2-071f-706a-bd12-4dad7150e75d'
                  AND current_boot_id = 'boot-a' AND highest_sequence = 10
                """)).isOne();
        assertThat(queryLong("""
                SELECT count(*) FROM telemetry_latest_numeric
                WHERE device_id = '019f63e2-071f-706a-bd12-4dad7150e75d'
                  AND property_id = 'engine.rpm' AND value_double = 1000
                """)).isOne();
    }

    @Test
    void 历史批写应以事件和可靠业务键幂等且同一事件允许多个属性() throws Exception {
        TelemetrySample rpm = sample(EVENT_ID, 7L, "engine.rpm", 1280D, OCCURRED_AT);
        TelemetrySample temperature = sample(EVENT_ID, 7L, "engine.temperature", 83.5D, OCCURRED_AT);
        QualityEvidence evidence = evidence(
                EVENT_ID, "engine.temperature", QualityRuleCode.ABOVE_MAXIMUM
        );

        TelemetryBatchWriter.WriteResult first = telemetryBatchWriter.write(
                List.of(rpm, temperature), List.of(evidence));
        TelemetrySample retryWithChangedPolicy = new TelemetrySample(
                rpm.eventId(), rpm.deviceId(), rpm.productKey(), rpm.propertyId(), rpm.value(),
                Quality.BAD, rpm.bootId(), rpm.sequence(), rpm.messageType(), rpm.occurredAt(),
                rpm.receivedAt(), rpm.receivedAt()
        );
        TelemetryBatchWriter.WriteResult repeated = telemetryBatchWriter.write(
                List.of(retryWithChangedPolicy, temperature), List.of(evidence));
        TelemetrySample sameBusinessKey = sample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e764"),
                7L,
                "engine.rpm",
                1290D,
                OCCURRED_AT.plusSeconds(1)
        );
        TelemetryBatchWriter.WriteResult businessRepeated = telemetryBatchWriter.write(List.of(sameBusinessKey));

        assertThat(first.claimedEvents()).isOne();
        assertThat(first.writtenPoints()).isEqualTo(2);
        assertThat(first.latestEventIds()).containsExactly(EVENT_ID);
        assertThat(first.latestEventIngestIds().get(EVENT_ID)).isPositive();
        assertThat(first.latestSamples().get(EVENT_ID))
                .extracting(TelemetrySample::quality)
                .containsOnly(Quality.GOOD);
        assertThat(first.qualityEvidence()).containsExactly(evidence);
        assertThat(repeated.claimedEvents()).isZero();
        assertThat(repeated.writtenPoints()).isZero();
        assertThat(repeated.latestEventIds()).containsExactly(EVENT_ID);
        assertThat(repeated.latestEventIngestIds())
                .containsEntry(EVENT_ID, first.latestEventIngestIds().get(EVENT_ID));
        assertThat(repeated.latestSamples().get(EVENT_ID))
                .extracting(TelemetrySample::quality)
                .containsOnly(Quality.GOOD);
        assertThat(repeated.qualityEvidence()).isEmpty();
        assertThat(businessRepeated.latestEventIds()).isEmpty();
        assertThat(businessRepeated.latestEventIngestIds()).isEmpty();
        assertThat(queryLong("SELECT count(*) FROM telemetry_inbox")).isEqualTo(1L);
        assertThat(queryLong("SELECT count(*) FROM telemetry_numeric")).isEqualTo(2L);
        assertThat(queryLong("SELECT count(*) FROM telemetry_quality_evidence")).isOne();
    }

    @Test
    void 历史批写失败时应回滚Inbox声明和全部历史点() throws Exception {
        execute("ALTER TABLE telemetry_numeric ADD CONSTRAINT ck_test_value CHECK (value_double <> 999)");
        try {
            TelemetrySample valid = sample(EVENT_ID, 7L, "engine.rpm", 1280D, OCCURRED_AT);
            TelemetrySample rejected = sample(EVENT_ID, 7L, "engine.temperature", 999D, OCCURRED_AT);
            QualityEvidence evidence = evidence(
                    EVENT_ID, "engine.temperature", QualityRuleCode.ABOVE_MAXIMUM
            );

            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> telemetryBatchWriter.write(List.of(valid, rejected), List.of(evidence))
            ).isInstanceOf(TelemetryBatchWriter.TelemetryStorageException.class);

            assertThat(queryLong("SELECT count(*) FROM telemetry_inbox")).isZero();
            assertThat(queryLong("SELECT count(*) FROM telemetry_numeric")).isZero();
            assertThat(queryLong("SELECT count(*) FROM telemetry_quality_evidence")).isZero();
        } finally {
            execute("ALTER TABLE telemetry_numeric DROP CONSTRAINT ck_test_value");
        }
    }

    @Test
    void 告警状态与Outbox应在遥测事务内幂等触发且迟到帧不得推进() throws Exception {
        AlarmPolicy policy = numericAlarmPolicy(0, 0, 60);
        persistAlarmPolicy(policy);
        TelemetrySample raised = sample(EVENT_ID, 7L, 96D, OCCURRED_AT);

        TelemetryBatchWriter.WriteResult first = telemetryBatchWriter.write(
                List.of(raised), List.of(), Map.of(DEVICE_ID, policy), Map.of());
        TelemetryBatchWriter.WriteResult repeated = telemetryBatchWriter.write(
                List.of(raised), List.of(), Map.of(DEVICE_ID, policy), Map.of());
        TelemetrySample late = sample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e762"),
                6L, 80D, OCCURRED_AT.minusSeconds(10));
        TelemetryBatchWriter.WriteResult lateResult = telemetryBatchWriter.write(
                List.of(late), List.of(), Map.of(DEVICE_ID, policy), Map.of());

        assertThat(first.alarmOutboxEventIds()).hasSize(1);
        assertThat(repeated.alarmOutboxEventIds()).isEmpty();
        assertThat(lateResult.latestEventIds()).isEmpty();
        assertThat(lateResult.alarmOutboxEventIds()).isEmpty();
        assertThat(queryLong("""
                SELECT count(*) FROM telemetry_alarm_runtime_state
                 WHERE device_id = '019f63e2-071f-706a-bd12-4dad7150e75d'
                   AND rule_key = 'engine-rpm-high' AND state = 'ACTIVE'
                """)).isOne();
        assertThat(queryLong("""
                SELECT count(*) FROM telemetry_outbox_event
                 WHERE event_type = 'alarm.raised' AND status = 'PENDING'
                """)).isOne();
    }

    @Test
    void 告警恢复应沿用同一周期并生成一次可靠恢复事件() throws Exception {
        AlarmPolicy policy = numericAlarmPolicy(0, 0, 60);
        persistAlarmPolicy(policy);
        TelemetrySample raised = sample(EVENT_ID, 7L, 96D, OCCURRED_AT);
        TelemetrySample recovered = sample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e763"),
                "boot-a", 8L, "engine.rpm", 89D, OCCURRED_AT.plusSeconds(1),
                RECEIVED_AT.plusSeconds(1));

        telemetryBatchWriter.write(
                List.of(raised), List.of(), Map.of(DEVICE_ID, policy), Map.of());
        TelemetryBatchWriter.WriteResult result = telemetryBatchWriter.write(
                List.of(recovered), List.of(), Map.of(DEVICE_ID, policy), Map.of());

        assertThat(result.alarmOutboxEventIds()).hasSize(1);
        assertThat(queryLong("""
                SELECT count(DISTINCT aggregate_id) FROM telemetry_outbox_event
                 WHERE event_type IN ('alarm.raised', 'alarm.recovered')
                """)).isOne();
        assertThat(queryLong("""
                SELECT count(*) FROM telemetry_alarm_runtime_state
                 WHERE rule_key = 'engine-rpm-high' AND state = 'COOLDOWN'
                """)).isOne();
    }

    @Test
    void 未变规则跨快照继续活动周期时恢复事件必须保留触发快照身份() throws Exception {
        AlarmPolicy original = numericAlarmPolicy(0, 0, 60);
        persistAlarmPolicy(original);
        telemetryBatchWriter.write(
                List.of(sample(EVENT_ID, 7L, 96D, OCCURRED_AT)),
                List.of(), Map.of(DEVICE_ID, original), Map.of());
        AlarmPolicy current = new AlarmPolicy(
                UUID.fromString("019f6e00-0000-7000-8000-000000000181"),
                9L, "sha256:" + "c".repeat(64), original.rules());
        execute("""
                UPDATE telemetry_alarm_policy
                   SET runtime_revision = 9,
                       snapshot_id = '019f6e00-0000-7000-8000-000000000181',
                       content_hash = 'sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'
                 WHERE device_id = '019f63e2-071f-706a-bd12-4dad7150e75d'
                """);
        UUID activeEventId = UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e766");
        telemetryBatchWriter.write(
                List.of(sample(activeEventId, 8L, 97D, OCCURRED_AT.plusSeconds(1))),
                List.of(), Map.of(DEVICE_ID, current), Map.of());
        UUID recoveredEventId = UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e767");
        telemetryBatchWriter.write(
                List.of(sample(recoveredEventId, 9L, 89D, OCCURRED_AT.plusSeconds(2))),
                List.of(), Map.of(DEVICE_ID, current), Map.of());

        assertThat(queryString("""
                SELECT payload ->> 'policySnapshotId'
                  FROM telemetry_outbox_event
                 WHERE event_type = 'alarm.recovered'
                """)).isEqualTo(original.snapshotId().toString());
        assertThat(queryString("""
                SELECT policy_snapshot_id::TEXT
                  FROM telemetry_alarm_runtime_state
                 WHERE rule_key = 'engine-rpm-high'
                """)).isEqualTo(original.snapshotId().toString());
    }

    @Test
    void 历史约束失败时应同时回滚告警状态与Outbox() throws Exception {
        execute("ALTER TABLE telemetry_numeric ADD CONSTRAINT ck_test_alarm_value CHECK (value_double <> 999)");
        try {
            AlarmPolicy policy = numericAlarmPolicy(0, 0, 60);
            persistAlarmPolicy(policy);
            TelemetrySample rejected = sample(EVENT_ID, 7L, 999D, OCCURRED_AT);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> telemetryBatchWriter.write(
                    List.of(rejected), List.of(), Map.of(DEVICE_ID, policy), Map.of()))
                    .isInstanceOf(TelemetryBatchWriter.TelemetryStorageException.class);

            assertThat(queryLong("SELECT count(*) FROM telemetry_inbox")).isZero();
            assertThat(queryLong("SELECT count(*) FROM telemetry_numeric")).isZero();
            assertThat(queryLong("SELECT count(*) FROM telemetry_alarm_runtime_state")).isZero();
            assertThat(queryLong("SELECT count(*) FROM telemetry_outbox_event")).isZero();
        } finally {
            execute("ALTER TABLE telemetry_numeric DROP CONSTRAINT ck_test_alarm_value");
        }
    }

    @Test
    void 事务开始前已过期的告警策略不得重新创建旧状态() throws Exception {
        AlarmPolicy stale = numericAlarmPolicy(0, 0, 60);
        persistAlarmPolicy(stale);
        execute("""
                UPDATE telemetry_alarm_policy
                   SET runtime_revision = 9,
                       snapshot_id = '019f6e00-0000-7000-8000-000000000181',
                       content_hash = 'sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'
                 WHERE device_id = '019f63e2-071f-706a-bd12-4dad7150e75d'
                """);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> telemetryBatchWriter.write(
                List.of(sample(EVENT_ID, 7L, 96D, OCCURRED_AT)),
                List.of(), Map.of(DEVICE_ID, stale), Map.of()))
                .isInstanceOf(TelemetryBatchWriter.TelemetryStorageException.class)
                .hasMessageContaining("遥测历史批写失败");

        assertThat(queryLong("SELECT count(*) FROM telemetry_inbox")).isZero();
        assertThat(queryLong("SELECT count(*) FROM telemetry_numeric")).isZero();
        assertThat(queryLong("SELECT count(*) FROM telemetry_alarm_runtime_state")).isZero();
        assertThat(queryLong("SELECT count(*) FROM telemetry_outbox_event")).isZero();
    }

    @Test
    void 事务开始前从无到有的告警策略必须拒绝旧的空策略视图() throws Exception {
        AlarmPolicy current = numericAlarmPolicy(0, 0, 60);
        persistAlarmPolicy(current);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> telemetryBatchWriter.write(
                List.of(sample(EVENT_ID, 7L, 96D, OCCURRED_AT)),
                List.of(), Map.of(), Map.of()))
                .isInstanceOf(TelemetryBatchWriter.TelemetryStorageException.class)
                .hasMessageContaining("遥测历史批写失败");

        assertThat(queryLong("SELECT count(*) FROM telemetry_inbox")).isZero();
        assertThat(queryLong("SELECT count(*) FROM telemetry_numeric")).isZero();
        assertThat(queryLong("SELECT count(*) FROM telemetry_alarm_runtime_state")).isZero();
        assertThat(queryLong("SELECT count(*) FROM telemetry_outbox_event")).isZero();
    }

    @Test
    void 质量告警只有对应检查确实执行时才允许恢复() throws Exception {
        AlarmPolicy policy = qualityAlarmPolicy();
        persistQualityAlarmPolicy(policy);
        DataQualityPolicy exactPolicy = new DataQualityPolicy(
                UUID.fromString("019f6e00-0000-7000-8000-000000000190"),
                3L, "sha256:" + "d".repeat(64), null, null,
                Set.of("engine.pressure"), Map.of(), Map.of());
        QualityEvaluationCoverage exactCoverage = QualityEvaluationCoverage.from(
                exactPolicy, List.of(sample(EVENT_ID, 7L, 96D, OCCURRED_AT)));
        QualityEvidence missing = new QualityEvidence(
                EVENT_ID, DEVICE_ID, "engine.pressure", QualityRuleCode.REQUIRED_MISSING,
                Quality.BAD, null, null, exactPolicy.runtimeRevision(),
                exactPolicy.snapshotId(), exactPolicy.contentHash());

        telemetryBatchWriter.write(
                List.of(sample(EVENT_ID, 7L, 96D, OCCURRED_AT)),
                List.of(missing), Map.of(DEVICE_ID, policy), Map.of(EVENT_ID, exactCoverage));
        UUID unknownEventId = UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e764");
        telemetryBatchWriter.write(
                List.of(sample(unknownEventId, 8L, 96D, OCCURRED_AT.plusSeconds(1))),
                List.of(), Map.of(DEVICE_ID, policy), Map.of());

        assertThat(queryLong("""
                SELECT count(*) FROM telemetry_alarm_runtime_state
                 WHERE rule_key = 'engine-pressure-required' AND state = 'ACTIVE'
                """)).isOne();
        assertThat(queryLong("""
                SELECT count(*) FROM telemetry_outbox_event
                 WHERE event_type = 'alarm.recovered'
                """)).isZero();

        UUID clearEventId = UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e765");
        telemetryBatchWriter.write(
                List.of(sample(clearEventId, 9L, 96D, OCCURRED_AT.plusSeconds(2))),
                List.of(), Map.of(DEVICE_ID, policy), Map.of(clearEventId, exactCoverage));

        assertThat(queryLong("""
                SELECT count(*) FROM telemetry_outbox_event
                 WHERE event_type = 'alarm.recovered'
                """)).isOne();
    }

    @Test
    void 有效首帧应固定首次接收时间且后续报文只推进最后接收时间() throws Exception {
        Instant firstReceivedAt = Instant.parse("2026-07-15T03:00:02Z");
        Instant lateReceivedAt = Instant.parse("2026-07-15T02:59:02Z");
        Instant latestReceivedAt = Instant.parse("2026-07-15T03:01:02Z");
        TelemetrySample first = sample(
                EVENT_ID, "boot-a", 7L, "engine.rpm", 1280D, OCCURRED_AT, firstReceivedAt
        );
        TelemetrySample second = sample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e762"),
                "boot-a", 8L, "engine.rpm", 1290D,
                OCCURRED_AT.minusSeconds(60), lateReceivedAt
        );
        TelemetrySample third = sample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e763"),
                "boot-a", 9L, "engine.rpm", 1300D,
                OCCURRED_AT.plusSeconds(60), latestReceivedAt
        );

        telemetryBatchWriter.write(List.of(first));
        telemetryBatchWriter.write(List.of(second));
        assertThat(queryInstant("SELECT first_seen_at FROM telemetry_latest_state WHERE device_id = '"
                + DEVICE_ID + "'")).isEqualTo(firstReceivedAt);
        assertThat(queryInstant("SELECT last_received_at FROM telemetry_latest_state WHERE device_id = '"
                + DEVICE_ID + "'")).isEqualTo(firstReceivedAt);
        telemetryBatchWriter.write(List.of(third));

        assertThat(queryInstant("SELECT first_seen_at FROM telemetry_latest_state WHERE device_id = '"
                + DEVICE_ID + "'")).isEqualTo(firstReceivedAt);
        assertThat(queryInstant("SELECT last_received_at FROM telemetry_latest_state WHERE device_id = '"
                + DEVICE_ID + "'")).isEqualTo(latestReceivedAt);
    }

    @Test
    void 设备时间不可信时应使用接收时间排序但保留原始发生时间() throws Exception {
        Instant rawOccurredAt = Instant.parse("2025-07-15T03:00:00Z");
        TelemetrySample untrusted = new TelemetrySample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e767"),
                DEVICE_ID,
                "haul-truck",
                "engine.rpm",
                1200D,
                Quality.TIME_UNTRUSTED,
                "boot-a",
                9L,
                MessageKind.TELEMETRY,
                rawOccurredAt,
                RECEIVED_AT,
                RECEIVED_AT
        );

        telemetryBatchWriter.write(List.of(untrusted));

        assertThat(queryInstant("SELECT time FROM telemetry_numeric")).isEqualTo(RECEIVED_AT);
        assertThat(queryInstant("SELECT occurred_at FROM telemetry_numeric")).isEqualTo(rawOccurredAt);
    }

    @Test
    void 真实RabbitMQ批监听器应在Redis和数据库成功后消费消息() throws Exception {
        execute("""
                INSERT INTO telemetry_data_quality_policy (
                    device_id, product_id, runtime_revision, snapshot_id,
                    values_json, content_hash
                ) VALUES (
                    '019f63e2-071f-706a-bd12-4dad7150e75d',
                    '019f6e00-0000-7000-8000-000000000171',
                    7,
                    '019f6e00-0000-7000-8000-000000000172',
                    '{"minimum.engine.rpm": 1300}'::JSONB,
                    'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
                )
                """);
        NormalizedTelemetryConsumer consumer = new NormalizedTelemetryConsumer(
                latestValueRepository,
                telemetryBatchWriter,
                event -> { },
                ignored -> { },
                new DataQualityPolicyRepository(dataSource),
                new DataQualityEvaluator(),
                ignored -> { }
        );
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(
                rabbitConsumerConnectionFactory
        );
        container.setQueueNames(TEST_QUEUE);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setPrefetchCount(10);
        container.setBatchSize(10);
        container.setConsumerBatchEnabled(true);
        container.setReceiveTimeout(100L);
        container.setBatchReceiveTimeout(100L);
        container.setMessageListener(consumer);
        container.start();
        try {
            new RabbitTemplate(rabbitConnectionFactory).send(
                    TEST_EXCHANGE,
                    "telemetry.normalized.haul-truck." + DEVICE_ID,
                    normalizedMessage()
            );

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(queryLong("SELECT count(*) FROM telemetry_inbox")).isEqualTo(1L);
                assertThat(queryLong("SELECT count(*) FROM telemetry_numeric")).isEqualTo(2L);
                assertThat(queryLong("""
                        SELECT count(*) FROM telemetry_quality_evidence
                        WHERE rule_code = 'BELOW_MINIMUM'
                          AND property_id = 'engine.rpm'
                        """)).isOne();
                assertThat(queryLong("""
                        SELECT quality FROM telemetry_numeric
                        WHERE property_id = 'engine.rpm'
                        """)).isEqualTo(2L);
                assertThat(queryLong("""
                        SELECT quality FROM telemetry_numeric
                        WHERE property_id = 'engine.temperature'
                        """)).isZero();
                assertThat(latestValueRepository.find(DEVICE_ID, "engine.rpm")).isPresent();
                assertThat(latestValueRepository.find(DEVICE_ID, "engine.rpm").orElseThrow().quality())
                        .isEqualTo(Quality.BAD);
                assertThat(latestValueRepository.find(
                        DEVICE_ID, "engine.temperature").orElseThrow().quality())
                        .isEqualTo(Quality.GOOD);
                assertThat(rabbitAdmin.getQueueInfo(TEST_QUEUE).getMessageCount()).isZero();
            });
        } finally {
            container.stop();
            container.destroy();
        }
    }

    private static TelemetrySample sample(
            UUID eventId,
            Long sequence,
            double value,
            Instant effectiveAt) {
        return sample(eventId, sequence, "engine.rpm", value, effectiveAt);
    }

    private static QualityEvidence evidence(
            UUID eventId,
            String propertyId,
            QualityRuleCode ruleCode
    ) {
        return new QualityEvidence(
                eventId,
                DEVICE_ID,
                propertyId,
                ruleCode,
                Quality.BAD,
                java.math.BigDecimal.valueOf(83.5D),
                java.math.BigDecimal.valueOf(80D),
                7L,
                UUID.fromString("019f6e00-0000-7000-8000-000000000120"),
                "sha256:" + "a".repeat(64)
        );
    }

    private static AlarmPolicy numericAlarmPolicy(
            int triggerSeconds,
            int recoverSeconds,
            int cooldownSeconds
    ) {
        return new AlarmPolicy(
                UUID.fromString("019f6e00-0000-7000-8000-000000000180"),
                8L,
                "sha256:" + "b".repeat(64),
                List.of(new AlarmPolicy.Rule(
                        "engine-rpm-high", "发动机转速过高",
                        AlarmPolicy.SourceType.NUMERIC,
                        AlarmPolicy.Severity.CRITICAL,
                        triggerSeconds, recoverSeconds, cooldownSeconds, true,
                        "engine.rpm", AlarmPolicy.Operator.GT,
                        new java.math.BigDecimal("95"),
                        new java.math.BigDecimal("90"), null))
        );
    }

    private static AlarmPolicy qualityAlarmPolicy() {
        return new AlarmPolicy(
                UUID.fromString("019f6e00-0000-7000-8000-000000000180"),
                8L, "sha256:" + "b".repeat(64),
                List.of(new AlarmPolicy.Rule(
                        "engine-pressure-required", "发动机压力缺失",
                        AlarmPolicy.SourceType.QUALITY, AlarmPolicy.Severity.WARNING,
                        0, 0, 60, true, "engine.pressure",
                        null, null, null, QualityRuleCode.REQUIRED_MISSING))
        );
    }

    private static void persistAlarmPolicy(AlarmPolicy policy) throws Exception {
        execute("""
                INSERT INTO telemetry_alarm_policy (
                    device_id, product_id, runtime_revision, snapshot_id,
                    values_json, content_hash
                ) VALUES (
                    '019f63e2-071f-706a-bd12-4dad7150e75d',
                    '019f6e00-0000-7000-8000-000000000171',
                    %d,
                    '%s',
                    '{"rules":[{
                      "key":"engine-rpm-high","name":"发动机转速过高",
                      "sourceType":"NUMERIC","severity":"CRITICAL",
                      "triggerDurationSeconds":0,"recoverDurationSeconds":0,
                      "cooldownSeconds":60,"enabled":true,
                      "propertyId":"engine.rpm","operator":"GT",
                      "threshold":95,"recoverThreshold":90
                    }]}'::JSONB,
                    '%s'
                )
                """.formatted(
                policy.runtimeRevision(), policy.snapshotId(), policy.contentHash()));
    }

    private static void persistQualityAlarmPolicy(AlarmPolicy policy) throws Exception {
        execute("""
                INSERT INTO telemetry_alarm_policy (
                    device_id, product_id, runtime_revision, snapshot_id,
                    values_json, content_hash
                ) VALUES (
                    '019f63e2-071f-706a-bd12-4dad7150e75d',
                    '019f6e00-0000-7000-8000-000000000171',
                    %d,
                    '%s',
                    '{"rules":[{
                      "key":"engine-pressure-required","name":"发动机压力缺失",
                      "sourceType":"QUALITY","severity":"WARNING",
                      "triggerDurationSeconds":0,"recoverDurationSeconds":0,
                      "cooldownSeconds":60,"enabled":true,
                      "propertyId":"engine.pressure","qualityRuleCode":"REQUIRED_MISSING"
                    }]}'::JSONB,
                    '%s'
                )
                """.formatted(
                policy.runtimeRevision(), policy.snapshotId(), policy.contentHash()));
    }

    private static TelemetrySample sample(
            UUID eventId,
            Long sequence,
            String propertyId,
            double value,
            Instant effectiveAt) {
        return sample(eventId, "boot-a", sequence, propertyId, value, effectiveAt);
    }

    private static TelemetrySample sample(
            UUID eventId,
            String bootId,
            Long sequence,
            String propertyId,
            double value,
            Instant effectiveAt) {
        return sample(eventId, bootId, sequence, propertyId, value, effectiveAt, RECEIVED_AT);
    }

    private static TelemetrySample sample(
            UUID eventId,
            String bootId,
            Long sequence,
            String propertyId,
            double value,
            Instant effectiveAt,
            Instant receivedAt) {
        return new TelemetrySample(
                eventId,
                DEVICE_ID,
                "haul-truck",
                propertyId,
                value,
                Quality.GOOD,
                bootId,
                sequence,
                MessageKind.TELEMETRY,
                effectiveAt,
                receivedAt,
                effectiveAt
        );
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private static long queryLong(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static Instant queryInstant(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getTimestamp(1).toInstant();
        }
    }

    private static String queryString(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static Message normalizedMessage() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("engine.rpm", 1280D);
        values.put("engine.temperature", 83.5D);
        UnifiedDeviceMessage payload = new UnifiedDeviceMessage(
                EVENT_ID,
                MessageKind.TELEMETRY,
                "haul-truck",
                DEVICE_ID.toString(),
                "v1",
                "boot-a",
                7L,
                OCCURRED_AT,
                RECEIVED_AT,
                false,
                new TelemetryPayload(values),
                Quality.GOOD,
                null
        );
        MessageEnvelope<UnifiedDeviceMessage> envelope = new MessageEnvelope<>(
                EVENT_ID,
                MessageType.of("telemetry.normalized"),
                1,
                OCCURRED_AT,
                RECEIVED_AT,
                "mine-gateway",
                "trace-001",
                null,
                DEVICE_ID.toString(),
                "mine-local",
                payload
        );
        return new Message(JsonMapper.builder().findAndAddModules().build().writeValueAsBytes(envelope));
    }
}
