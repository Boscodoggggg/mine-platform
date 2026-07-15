package com.bosco.mine.telemetry.e2e;

import com.bosco.mine.messaging.MessageEnvelope;
import com.bosco.mine.messaging.MessageType;
import com.bosco.mine.messaging.realtime.RealtimeTelemetryEvent;
import com.bosco.mine.model.message.TelemetryPayload;
import com.bosco.mine.model.message.UnifiedDeviceMessage;
import com.bosco.mine.telemetry.consumer.NormalizedTelemetryConsumer;
import com.bosco.mine.telemetry.latest.LatestValueRepository;
import com.bosco.mine.telemetry.storage.TelemetryBatchWriter;
import com.rabbitmq.client.Channel;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 使用真实 TimescaleDB 和 Redis 验证十分钟断网、乱序补传、重复投递和设备重启的遥测闭环。
 */
@Testcontainers
class WeakNetworkE2EIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");
    private static final UUID DEVICE_ID = UUID.fromString("019f6572-cc00-7000-8000-000000000001");
    private static final String BOOT_A = "weak-boot-a";
    private static final String BOOT_B = "weak-boot-b";
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine")
            .withExposedPorts(6379);

    @Container
    static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_telemetry")
            .withUsername("mine_ts")
            .withPassword("mine-timescale-test-password");

    private static LettuceConnectionFactory connectionFactory;
    private static LatestValueRepository latestValues;
    private static DataSource dataSource;
    private static TelemetryBatchWriter batchWriter;

    @BeforeAll
    static void 创建真实存储连接() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        latestValues = new LatestValueRepository(redisTemplate);
        dataSource = new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword()
        );
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        batchWriter = new TelemetryBatchWriter(dataSource);
    }

    @AfterAll
    static void 关闭真实存储连接() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void 清空弱网验收数据() throws Exception {
        connectionFactory.getConnection().serverCommands().flushDb();
        execute("""
                TRUNCATE TABLE telemetry_inbox, telemetry_numeric, telemetry_latest_numeric,
                               telemetry_latest_state, telemetry_retired_boot
                """);
    }

    @Test
    void 十分钟断网补传和重启不得丢历史或重复业务副作用() throws Exception {
        List<WeakFrame> frames = weakFrames();
        assertThat(Duration.between(frames.get(3).receivedAt(), frames.get(4).receivedAt()))
                .isGreaterThanOrEqualTo(Duration.ofMinutes(10));
        List<RealtimeTelemetryEvent> realtimeEvents = new ArrayList<>();
        List<TelemetryBatchWriter.SequenceGap> sequenceGaps = new ArrayList<>();
        NormalizedTelemetryConsumer consumer = new NormalizedTelemetryConsumer(
                latestValues, batchWriter, realtimeEvents::add, sequenceGaps::add
        );
        Channel channel = mock(Channel.class);

        for (int index = 0; index < frames.size(); index++) {
            consumer.onMessageBatch(List.of(message(frames.get(index), index + 1L)), channel);
        }

        assertThat(queryLong("SELECT count(*) FROM telemetry_inbox")).isEqualTo(11L);
        assertThat(queryLong("SELECT count(*) FROM telemetry_numeric")).isEqualTo(22L);
        assertThat(queryLong("SELECT count(*) FROM telemetry_latest_numeric")).isEqualTo(2L);
        assertThat(queryLong("""
                SELECT count(*) FROM telemetry_latest_numeric
                WHERE device_id = '019f6572-cc00-7000-8000-000000000001'
                  AND boot_id = 'weak-boot-b'
                  AND sequence = 2
                  AND event_id = '019f6572-cc00-7000-8000-000000000066'
                  AND ((property_id = 'engine.rpm' AND value_double = 2002)
                    OR (property_id = 'vehicle.speed' AND value_double = 20))
                """)).isEqualTo(2L);
        assertThat(queryLong("""
                SELECT count(*) FROM telemetry_retired_boot
                WHERE device_id = '019f6572-cc00-7000-8000-000000000001'
                  AND boot_id = 'weak-boot-a'
                """)).isOne();
        assertThat(queryText("""
                SELECT current_boot_id FROM telemetry_latest_state
                WHERE device_id = '019f6572-cc00-7000-8000-000000000001'
                """)).isEqualTo(BOOT_B);
        assertThat(queryLong("""
                SELECT highest_sequence FROM telemetry_latest_state
                WHERE device_id = '019f6572-cc00-7000-8000-000000000001'
                """)).isEqualTo(2L);
        assertThat(latestValues.find(DEVICE_ID, "engine.rpm").orElseThrow())
                .satisfies(latest -> {
                    assertThat(latest.value()).isEqualTo(2002D);
                    assertThat(latest.bootId()).isEqualTo(BOOT_B);
                    assertThat(latest.sequence()).isEqualTo(2L);
                });

        verify(channel, times(frames.size())).basicAck(anyLong(), eq(false));
        verify(channel, never()).basicNack(anyLong(), eq(false), eq(true));
        verify(channel, never()).basicReject(anyLong(), eq(false));

        assertThat(realtimeEvents).hasSize(9);
        assertThat(realtimeEvents.stream()
                .filter(event -> event.eventId().equals(eventId(BOOT_A, 3L)))
                .map(RealtimeTelemetryEvent::sourceSequence)
                .distinct()).hasSize(1);
        assertThat(sequenceGaps).containsExactly(new TelemetryBatchWriter.SequenceGap(
                eventId(BOOT_A, 7L), DEVICE_ID, BOOT_A, 3L, 7L, 3L
        ));
    }

    private static List<WeakFrame> weakFrames() throws Exception {
        Map<String, Object> source = loadWeakScenario();
        List<Long> sequences = longList(source, "sequenceOrder");
        List<String> bootIds = stringList(source, "bootOrder");
        List<Long> occurredOffsets = longList(source, "occurredAtOffsetsMillis");
        Set<Integer> replayedIndexes = new HashSet<>(integerList(source, "replayedIndexes"));
        Set<Integer> disconnectIndexes = new HashSet<>(integerList(
                source, "disconnectBeforeIndexes"
        ));
        long intervalMillis = ((Number) source.get("intervalMillis")).longValue();
        long disconnectMillis = ((Number) source.get("disconnectMillis")).longValue();
        Instant startAt = Instant.parse((String) source.get("startAt"));
        List<WeakFrame> frames = new ArrayList<>(sequences.size());
        long receivedOffsetMillis = 0L;
        for (int index = 0; index < sequences.size(); index++) {
            if (index > 0) {
                receivedOffsetMillis += intervalMillis;
            }
            if (disconnectIndexes.contains(index)) {
                receivedOffsetMillis += disconnectMillis;
            }
            long sequence = sequences.get(index);
            String bootId = bootIds.get(index);
            frames.add(new WeakFrame(
                    eventId(bootId, sequence),
                    bootId,
                    sequence,
                    startAt.plusMillis(occurredOffsets.get(index)),
                    startAt.plusMillis(receivedOffsetMillis),
                    replayedIndexes.contains(index)
            ));
        }
        return List.copyOf(frames);
    }

    private static Map<String, Object> loadWeakScenario() throws Exception {
        Path scenario = Path.of(
                System.getProperty("maven.multiModuleProjectDirectory", "."),
                "examples", "device-simulator", "scenarios", "weak-network.yaml"
        ).toAbsolutePath().normalize();
        if (!Files.exists(scenario)) {
            scenario = Path.of("..", "examples", "device-simulator", "scenarios",
                    "weak-network.yaml").toAbsolutePath().normalize();
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object loaded;
        try (java.io.InputStream input = Files.newInputStream(scenario)) {
            loaded = new Yaml(new SafeConstructor(options)).load(input);
        }
        if (!(loaded instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("弱网场景根节点必须是对象");
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> result.put((String) key, value));
        return result;
    }

    private static List<Long> longList(Map<String, Object> source, String key) {
        return ((List<?>) source.get(key)).stream()
                .map(value -> ((Number) value).longValue())
                .toList();
    }

    private static List<Integer> integerList(Map<String, Object> source, String key) {
        return longList(source, key).stream().map(Math::toIntExact).toList();
    }

    private static List<String> stringList(Map<String, Object> source, String key) {
        return ((List<?>) source.get(key)).stream().map(String.class::cast).toList();
    }

    private static UUID eventId(String bootId, long sequence) {
        long suffix = (BOOT_B.equals(bootId) ? 100L : 0L) + sequence;
        return UUID.fromString("019f6572-cc00-7000-8000-" + "%012x".formatted(suffix));
    }

    private static Message message(WeakFrame frame, long deliveryTag) throws Exception {
        double rpm = BOOT_B.equals(frame.bootId()) ? 2000D + frame.sequence() : 1000D + frame.sequence();
        UnifiedDeviceMessage payload = new UnifiedDeviceMessage(
                frame.eventId(),
                UnifiedDeviceMessage.MessageKind.TELEMETRY,
                "haul-truck",
                DEVICE_ID.toString(),
                "v1",
                frame.bootId(),
                frame.sequence(),
                frame.occurredAt(),
                frame.receivedAt(),
                frame.replayed(),
                new TelemetryPayload(Map.of(
                        "engine.rpm", rpm,
                        "vehicle.speed", frame.sequence() * 10D
                )),
                UnifiedDeviceMessage.Quality.GOOD,
                null
        );
        MessageEnvelope<UnifiedDeviceMessage> envelope = new MessageEnvelope<>(
                frame.eventId(),
                MessageType.of("telemetry.normalized"),
                1,
                frame.occurredAt(),
                frame.receivedAt(),
                "mine-gateway",
                "trace-weak-network-" + deliveryTag,
                null,
                DEVICE_ID.toString(),
                "mine-local",
                payload
        );
        Message message = new Message(JSON.writeValueAsBytes(envelope));
        message.getMessageProperties().setDeliveryTag(deliveryTag);
        return message;
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

    private static String queryText(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    /**
     * 表示按弱网场景到达遥测服务的一帧标准化消息。
     *
     * @param eventId 事件标识
     * @param bootId 启动周期标识
     * @param sequence 启动周期内序号
     * @param occurredAt 设备发生时间
     * @param receivedAt 平台接收时间
     * @param replayed 是否为断网补传
     */
    private record WeakFrame(
            UUID eventId,
            String bootId,
            long sequence,
            Instant occurredAt,
            Instant receivedAt,
            boolean replayed
    ) {
    }
}
