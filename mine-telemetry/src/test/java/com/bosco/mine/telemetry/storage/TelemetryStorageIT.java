package com.bosco.mine.telemetry.storage;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.model.message.TelemetryPayload;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import com.bosco.mine.model.message.UnifiedDeviceMessage.MessageKind;
import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.telemetry.consumer.NormalizedTelemetryConsumer;
import com.bosco.mine.telemetry.latest.LatestValueRepository;
import com.bosco.mine.telemetry.pipeline.TelemetrySample;
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
        execute("TRUNCATE TABLE telemetry_inbox, telemetry_numeric");
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
    void 历史批写应以事件和可靠业务键幂等且同一事件允许多个属性() throws Exception {
        TelemetrySample rpm = sample(EVENT_ID, 7L, "engine.rpm", 1280D, OCCURRED_AT);
        TelemetrySample temperature = sample(EVENT_ID, 7L, "engine.temperature", 83.5D, OCCURRED_AT);

        TelemetryBatchWriter.WriteResult first = telemetryBatchWriter.write(List.of(rpm, temperature));
        TelemetryBatchWriter.WriteResult repeated = telemetryBatchWriter.write(List.of(rpm, temperature));
        TelemetrySample sameBusinessKey = sample(
                UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e764"),
                7L,
                "engine.rpm",
                1290D,
                OCCURRED_AT.plusSeconds(1)
        );
        TelemetryBatchWriter.WriteResult businessRepeated = telemetryBatchWriter.write(List.of(sameBusinessKey));

        assertThat(first).isEqualTo(new TelemetryBatchWriter.WriteResult(1, 2));
        assertThat(repeated).isEqualTo(new TelemetryBatchWriter.WriteResult(0, 0));
        assertThat(businessRepeated).isEqualTo(new TelemetryBatchWriter.WriteResult(0, 0));
        assertThat(queryLong("SELECT count(*) FROM telemetry_inbox")).isEqualTo(1L);
        assertThat(queryLong("SELECT count(*) FROM telemetry_numeric")).isEqualTo(2L);
    }

    @Test
    void 历史批写失败时应回滚Inbox声明和全部历史点() throws Exception {
        execute("ALTER TABLE telemetry_numeric ADD CONSTRAINT ck_test_value CHECK (value_double <> 999)");
        try {
            TelemetrySample valid = sample(EVENT_ID, 7L, "engine.rpm", 1280D, OCCURRED_AT);
            TelemetrySample rejected = sample(EVENT_ID, 7L, "engine.temperature", 999D, OCCURRED_AT);

            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> telemetryBatchWriter.write(List.of(valid, rejected))
            ).isInstanceOf(TelemetryBatchWriter.TelemetryStorageException.class);

            assertThat(queryLong("SELECT count(*) FROM telemetry_inbox")).isZero();
            assertThat(queryLong("SELECT count(*) FROM telemetry_numeric")).isZero();
        } finally {
            execute("ALTER TABLE telemetry_numeric DROP CONSTRAINT ck_test_value");
        }
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
        NormalizedTelemetryConsumer consumer = new NormalizedTelemetryConsumer(
                latestValueRepository,
                telemetryBatchWriter
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
                assertThat(latestValueRepository.find(DEVICE_ID, "engine.rpm")).isPresent();
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
                RECEIVED_AT,
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
