package com.bosco.mine.server.modules.device.repository;

import com.bosco.mine.model.message.UnifiedDeviceMessage.Quality;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository.Aggregation;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository.HistoryQuery;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository.HistorySource;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository.HistoryWatermarks;
import com.bosco.mine.server.modules.device.repository.DeviceTelemetryRepository.TelemetryValue;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用真实 Redis 和 TimescaleDB 校验设备遥测查询仓储。
 */
@Testcontainers
class RedisTimescaleDeviceTelemetryRepositoryIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");
    private static final UUID DEVICE_ID =
            UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e780");
    private static final Instant START = Instant.parse("2026-07-15T04:00:00Z");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine")
            .withExposedPorts(6379);

    @Container
    static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_telemetry")
            .withUsername("mine_ts")
            .withPassword("mine-timescale-test-password");

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static JdbcTemplate jdbcTemplate;
    private static RedisTimescaleDeviceTelemetryRepository repository;

    @BeforeAll
    static void 创建查询仓储() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword()
        );
        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:" + telemetryMigrationDirectory())
                .load()
                .migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new RedisTimescaleDeviceTelemetryRepository(redisTemplate, jdbcTemplate);
    }

    @AfterAll
    static void 关闭Redis连接() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void 准备遥测样本() {
        connectionFactory.getConnection().serverCommands().flushDb();
        jdbcTemplate.update("""
                TRUNCATE TABLE telemetry_numeric_rollup, telemetry_aggregation_execution,
                               telemetry_aggregation_policy, telemetry_inbox, telemetry_numeric,
                               telemetry_latest_numeric, telemetry_latest_state,
                               telemetry_retired_boot
                """);
        insert("speed", START, 10D, (short) 0, 1);
        insert("speed", START.plusSeconds(60), 20D, (short) 1, 2);
        insert("speed", START.plusSeconds(120), 40D, (short) 2, 3);
    }

    @Test
    void 应读取兼容写入服务格式的Redis最新值并仅在缺失时回填() {
        String key = "mine:latest:" + DEVICE_ID;
        redisTemplate.opsForHash().put(key, "speed", """
                {"value":88.5,"occurredAt":"2026-07-15T05:00:00Z",\
                "receivedAt":"2026-07-15T05:00:01Z","quality":"GOOD",\
                "eventId":"019f63e2-071f-706a-bd12-4dad7150e790",\
                "bootId":"boot-b","sequence":9}
                """);

        TelemetryValue cached = repository.findCachedLatest(DEVICE_ID, "speed").orElseThrow();

        assertThat(cached.value()).isEqualTo(88.5D);
        assertThat(cached.quality()).isEqualTo(Quality.GOOD);
        assertThat(cached.bootId()).isEqualTo("boot-b");
        assertThat(cached.sequence()).isEqualTo(9L);

        TelemetryValue older = repository.findLatestHistory(DEVICE_ID, "speed").orElseThrow();
        repository.cacheLatestIfAbsent(DEVICE_ID, older);

        assertThat(repository.findCachedLatest(DEVICE_ID, "speed").orElseThrow().value())
                .isEqualTo(88.5D);
        redisTemplate.delete(List.of(
                key,
                "mine:latest-order:" + DEVICE_ID,
                "mine:latest-state:" + DEVICE_ID,
                "mine:latest-retired-boots:" + DEVICE_ID
        ));
        repository.restoreLatestState(DEVICE_ID);
        assertThat(repository.cacheLatestIfAbsent(DEVICE_ID, older)).isTrue();
        assertThat(repository.findCachedLatest(DEVICE_ID, "speed").orElseThrow())
                .satisfies(value -> {
                    assertThat(value.value()).isEqualTo(40D);
                    assertThat(value.quality()).isEqualTo(Quality.BAD);
                });
        assertThat(redisTemplate.opsForHash().get(
                "mine:latest-state:" + DEVICE_ID, "latestEffectiveAt"
        )).isEqualTo(Long.toString(START.plusSeconds(120).toEpochMilli()));
        assertThat(redisTemplate.opsForHash().get(
                "mine:latest-state:" + DEVICE_ID, "highestSequence"
        )).isEqualTo("3");
        assertThat(redisTemplate.opsForHash().get(
                "mine:latest-state:" + DEVICE_ID, "currentBootId"
        )).isEqualTo("boot-a");
        assertThat(redisTemplate.opsForHash().get(
                "mine:latest-state:" + DEVICE_ID, "latestEventId"
        )).isEqualTo(older.eventId().toString());
        assertThat(redisTemplate.opsForHash().get(
                "mine:latest-order:" + DEVICE_ID, "speed"
        )).isEqualTo(START.plusSeconds(120).toEpochMilli()
                + "|boot-a|3|" + older.eventId());

    }

    @Test
    void 多属性最新值应通过一次批量查询返回() {
        insert("rpm", START.plusSeconds(90), 900D, (short) 0, 8);

        Map<String, TelemetryValue> values = repository.findLatestHistory(
                DEVICE_ID, List.of("speed", "rpm", "missing")
        );

        assertThat(values).containsOnlyKeys("speed", "rpm");
        assertThat(values.get("speed").value()).isEqualTo(40D);
        assertThat(values.get("rpm").value()).isEqualTo(900D);
    }

    @Test
    void 原始历史应按发生时间分页并返回质量和总数() {
        DeviceTelemetryRepository.HistoryPage page = repository.findHistory(new HistoryQuery(
                DEVICE_ID,
                "speed",
                START,
                START.plusSeconds(180),
                Aggregation.RAW,
                null,
                repository.currentIngestionWatermark(),
                0,
                1,
                1
        ));

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).singleElement().satisfies(point -> {
            assertThat(point.occurredAt()).isEqualTo(START.plusSeconds(60));
            assertThat(point.value()).isEqualTo(20D);
            assertThat(point.quality()).isEqualTo(Quality.UNCERTAIN);
            assertThat(point.eventId()).isNotNull();
            assertThat(point.sampleCount()).isOne();
        });
    }

    @Test
    void 摄取水位必须排除首页之后提交的弱网迟到数据() {
        long watermark = repository.currentIngestionWatermark();
        insert("speed", START.plusSeconds(30), 999D, (short) 0, 4);

        DeviceTelemetryRepository.HistoryPage stable = repository.findHistory(new HistoryQuery(
                DEVICE_ID,
                "speed",
                START,
                START.plusSeconds(180),
                Aggregation.RAW,
                null,
                watermark,
                0,
                0,
                100
        ));
        DeviceTelemetryRepository.HistoryPage current = repository.findHistory(new HistoryQuery(
                DEVICE_ID,
                "speed",
                START,
                START.plusSeconds(180),
                Aggregation.RAW,
                null,
                repository.currentIngestionWatermark(),
                0,
                0,
                100
        ));

        assertThat(stable.total()).isEqualTo(3L);
        assertThat(stable.items()).extracting(DeviceTelemetryRepository.HistoryPoint::value)
                .doesNotContain(999D);
        assertThat(current.total()).isEqualTo(4L);
    }

    @Test
    void 聚合历史应分页返回平均值样本数和桶内最差质量() {
        DeviceTelemetryRepository.HistoryPage page = repository.findHistory(new HistoryQuery(
                DEVICE_ID,
                "speed",
                START,
                START.plusSeconds(180),
                Aggregation.AVG,
                Duration.ofMinutes(2),
                repository.currentIngestionWatermark(),
                0,
                0,
                10
        ));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().getFirst()).satisfies(point -> {
            assertThat(point.occurredAt()).isEqualTo(START);
            assertThat(point.value()).isEqualTo(15D);
            assertThat(point.sampleCount()).isEqualTo(2);
            assertThat(point.quality()).isEqualTo(Quality.UNCERTAIN);
            assertThat(point.eventId()).isNull();
        });
        assertThat(page.items().getLast().quality()).isEqualTo(Quality.BAD);
    }

    @Test
    void 冷热历史应固定双水位并在后台重算后保持旧分页稳定() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Instant coldBucket = base.minus(10, ChronoUnit.MINUTES);
        Instant hotBucket = base.minus(2, ChronoUnit.MINUTES);
        insert("temperature", coldBucket.plusSeconds(10), 10D, (short) 0, 10);
        insert("temperature", coldBucket.plusSeconds(20), 20D, (short) 1, 11);
        insert("temperature", hotBucket.plusSeconds(10), 30D, (short) 0, 12);
        insert("temperature", hotBucket.plusSeconds(20), 50D, (short) 2, 13);
        insertAggregationPolicy("temperature", 60, 300);
        long sourceWatermark = repository.currentIngestionWatermark();
        insertRollup(coldBucket, sourceWatermark, 15D, 10D, 20D,
                2, (short) 1, UUID.fromString("019f7000-0000-7000-8000-000000000001"));
        HistoryWatermarks firstWatermarks = repository.currentHistoryWatermarks();

        DeviceTelemetryRepository.HistoryPage mixed = repository.findHistory(new HistoryQuery(
                DEVICE_ID, "temperature", coldBucket, base, Aggregation.AVG,
                Duration.ofMinutes(1), firstWatermarks.ingestionWatermark(),
                firstWatermarks.rollupWatermark(), 0, 100
        ));

        assertThat(mixed.items()).hasSize(2);
        assertThat(mixed.items().getFirst()).satisfies(point -> {
            assertThat(point.occurredAt()).isEqualTo(coldBucket);
            assertThat(point.value()).isEqualTo(15D);
            assertThat(point.sampleCount()).isEqualTo(2);
            assertThat(point.quality()).isEqualTo(Quality.UNCERTAIN);
            assertThat(point.source()).isEqualTo(HistorySource.ROLLUP);
        });
        assertThat(mixed.items().getLast()).satisfies(point -> {
            assertThat(point.occurredAt()).isEqualTo(hotBucket);
            assertThat(point.value()).isEqualTo(40D);
            assertThat(point.sampleCount()).isEqualTo(2);
            assertThat(point.quality()).isEqualTo(Quality.BAD);
            assertThat(point.source()).isEqualTo(HistorySource.RAW);
        });

        insertRollup(coldBucket, sourceWatermark, 999D, 999D, 999D,
                2, (short) 0, UUID.fromString("019f7000-0000-7000-8000-000000000002"));
        DeviceTelemetryRepository.HistoryPage stable = repository.findHistory(new HistoryQuery(
                DEVICE_ID, "temperature", coldBucket, hotBucket, Aggregation.AVG,
                Duration.ofMinutes(1), firstWatermarks.ingestionWatermark(),
                firstWatermarks.rollupWatermark(), 0, 100
        ));
        HistoryWatermarks latestWatermarks = repository.currentHistoryWatermarks();
        DeviceTelemetryRepository.HistoryPage latest = repository.findHistory(new HistoryQuery(
                DEVICE_ID, "temperature", coldBucket, hotBucket, Aggregation.AVG,
                Duration.ofMinutes(1), latestWatermarks.ingestionWatermark(),
                latestWatermarks.rollupWatermark(), 0, 100
        ));

        assertThat(stable.items()).singleElement()
                .extracting(DeviceTelemetryRepository.HistoryPoint::value).isEqualTo(15D);
        assertThat(latest.items()).singleElement()
                .extracting(DeviceTelemetryRepository.HistoryPoint::value).isEqualTo(999D);

        DeviceTelemetryRepository.HistoryPage boundary = repository.findHistory(new HistoryQuery(
                DEVICE_ID, "temperature", coldBucket.plusSeconds(15),
                coldBucket.plusSeconds(60), Aggregation.AVG, Duration.ofMinutes(1),
                latestWatermarks.ingestionWatermark(), latestWatermarks.rollupWatermark(),
                0, 100
        ));
        assertThat(boundary.items()).singleElement().satisfies(point -> {
            assertThat(point.value()).isEqualTo(20D);
            assertThat(point.sampleCount()).isOne();
            assertThat(point.source()).isEqualTo(HistorySource.RAW);
        });
    }

    @Test
    void 启动标识为空时仍应保留非空序号() {
        UUID eventId = UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e799");
        jdbcTemplate.update("""
                INSERT INTO telemetry_numeric (
                    time, occurred_at, device_id, product_key, property_id, value_double,
                    quality, boot_id, sequence, event_id, received_at, ingest_id
                ) VALUES (?, ?, ?, 'haul-truck', 'battery.voltage', 24.5, 0, NULL, 99, ?, ?,
                          nextval('telemetry_ingest_seq'))
                """,
                Timestamp.from(START),
                Timestamp.from(START),
                DEVICE_ID,
                eventId,
                Timestamp.from(START.plusSeconds(1))
        );
        upsertDurableLatest(
                "battery.voltage",
                START,
                START,
                24.5D,
                (short) 0,
                null,
                99L,
                eventId
        );

        TelemetryValue value = repository.findLatestHistory(
                DEVICE_ID, "battery.voltage"
        ).orElseThrow();

        assertThat(value.bootId()).isNull();
        assertThat(value.sequence()).isEqualTo(99L);
    }

    @Test
    void Redis丢失回源最新值必须按有效排序时间而不是设备原始时间() {
        insertWithEffectiveTime(
                "clock.skew",
                START.plusSeconds(60),
                START.plusSeconds(60),
                50D,
                (short) 0,
                "019f63e2-071f-706a-bd12-4dad7150e795"
        );
        insertWithEffectiveTime(
                "clock.skew",
                START.plusSeconds(30),
                START.plusSeconds(3_600),
                999D,
                (short) 3,
                "019f63e2-071f-706a-bd12-4dad7150e796"
        );

        TelemetryValue value = repository.findLatestHistory(
                DEVICE_ID, "clock.skew"
        ).orElseThrow();

        assertThat(value.value()).isEqualTo(50D);
        assertThat(value.occurredAt()).isEqualTo(START.plusSeconds(60));
        assertThat(value.quality()).isEqualTo(Quality.GOOD);
    }

    @Test
    void 属性丢失回源不得复活已退役启动周期的迟到历史() {
        UUID bootBEvent = UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e797");
        UUID lateBootAEvent = UUID.fromString("019f63e2-071f-706a-bd12-4dad7150e798");
        jdbcTemplate.update("""
                UPDATE telemetry_latest_numeric
                SET value_double = 55, effective_at = ?, occurred_at = ?, received_at = ?,
                    boot_id = 'boot-b', sequence = 0, event_id = ?
                WHERE device_id = ? AND property_id = 'speed'
                """,
                Timestamp.from(START.plusSeconds(180)),
                Timestamp.from(START.plusSeconds(180)),
                Timestamp.from(START.plusSeconds(181)),
                bootBEvent,
                DEVICE_ID
        );
        jdbcTemplate.update("""
                UPDATE telemetry_latest_state
                SET current_boot_id = 'boot-b', highest_sequence = 0,
                    latest_effective_at = ?, latest_event_id = ?
                WHERE device_id = ?
                """,
                Timestamp.from(START.plusSeconds(180)), bootBEvent, DEVICE_ID
        );
        jdbcTemplate.update("""
                INSERT INTO telemetry_retired_boot (device_id, boot_id)
                VALUES (?, 'boot-a')
                """, DEVICE_ID);
        jdbcTemplate.update("""
                INSERT INTO telemetry_numeric (
                    time, occurred_at, device_id, product_key, property_id, value_double,
                    quality, boot_id, sequence, event_id, received_at, ingest_id
                ) VALUES (?, ?, ?, 'haul-truck', 'speed', 999, 0, 'boot-a', 9, ?, ?,
                          nextval('telemetry_ingest_seq'))
                """,
                Timestamp.from(START.plusSeconds(240)),
                Timestamp.from(START.plusSeconds(240)),
                DEVICE_ID,
                lateBootAEvent,
                Timestamp.from(START.plusSeconds(241))
        );

        repository.restoreLatestState(DEVICE_ID);
        TelemetryValue durable = repository.findLatestHistory(DEVICE_ID, "speed").orElseThrow();
        assertThat(repository.cacheLatestIfAbsent(DEVICE_ID, durable)).isTrue();

        assertThat(repository.findCachedLatest(DEVICE_ID, "speed").orElseThrow())
                .satisfies(value -> {
                    assertThat(value.value()).isEqualTo(55D);
                    assertThat(value.bootId()).isEqualTo("boot-b");
                    assertThat(value.eventId()).isEqualTo(bootBEvent);
                });
        assertThat(redisTemplate.opsForSet().members(
                "mine:latest-retired-boots:" + DEVICE_ID
        )).contains("boot-a");
        assertThat(redisTemplate.opsForHash().get(
                "mine:latest-state:" + DEVICE_ID, "currentBootId"
        )).isEqualTo("boot-b");
    }

    private static void insert(
            String propertyId,
            Instant occurredAt,
            double value,
            short quality,
            int eventSuffix
    ) {
        UUID eventId = UUID.fromString(
                "019f63e2-071f-706a-bd12-4dad7150e7" + String.format("%02d", eventSuffix)
        );
        jdbcTemplate.update("""
                INSERT INTO telemetry_numeric (
                    time, occurred_at, device_id, product_key, property_id, value_double,
                    quality, boot_id, sequence, event_id, received_at, ingest_id
                ) VALUES (?, ?, ?, 'haul-truck', ?, ?, ?, 'boot-a', ?, ?, ?,
                          nextval('telemetry_ingest_seq'))
                """,
                Timestamp.from(occurredAt),
                Timestamp.from(occurredAt),
                DEVICE_ID,
                propertyId,
                value,
                quality,
                (long) eventSuffix,
                eventId,
                Timestamp.from(occurredAt.plusSeconds(1))
        );
        publishWatermark();
        upsertDurableLatest(propertyId, occurredAt, occurredAt, value, quality, "boot-a",
                (long) eventSuffix, eventId);
    }

    private static void insertWithEffectiveTime(
            String propertyId,
            Instant effectiveAt,
            Instant occurredAt,
            double value,
            short quality,
            String eventId
    ) {
        jdbcTemplate.update("""
                INSERT INTO telemetry_numeric (
                    time, occurred_at, device_id, product_key, property_id, value_double,
                    quality, boot_id, sequence, event_id, received_at, ingest_id
                ) VALUES (?, ?, ?, 'haul-truck', ?, ?, ?, 'boot-a', 10, ?, ?,
                          nextval('telemetry_ingest_seq'))
                """,
                Timestamp.from(effectiveAt),
                Timestamp.from(occurredAt),
                DEVICE_ID,
                propertyId,
                value,
                quality,
                UUID.fromString(eventId),
                Timestamp.from(effectiveAt.plusSeconds(1))
        );
        publishWatermark();
        upsertDurableLatest(
                propertyId,
                effectiveAt,
                occurredAt,
                value,
                quality,
                "boot-a",
                10L,
                UUID.fromString(eventId)
        );
    }

    private static void insertAggregationPolicy(
            String propertyId,
            int bucketSeconds,
            int correctionWindowSeconds
    ) {
        jdbcTemplate.update("""
                INSERT INTO telemetry_aggregation_policy (
                    device_id, product_id, runtime_revision, snapshot_id,
                    values_json, content_hash
                ) VALUES (?, ?, 3, ?, CAST(? AS JSONB), ?)
                """, DEVICE_ID,
                UUID.fromString("019f7000-0000-7000-8000-000000000010"),
                UUID.fromString("019f7000-0000-7000-8000-000000000011"),
                """
                {"policy":{
                  "enabled":true,
                  "correctionWindowSeconds":%d,
                  "rules":[{"propertyId":"%s","bucketSeconds":%d}]
                }}
                """.formatted(correctionWindowSeconds, propertyId, bucketSeconds),
                "sha256:" + "e".repeat(64));
    }

    private static void insertRollup(
            Instant bucketStart,
            long sourceWatermark,
            double average,
            double minimum,
            double maximum,
            long samples,
            short quality,
            UUID executionId
    ) {
        jdbcTemplate.update("""
                INSERT INTO telemetry_aggregation_execution (
                    execution_id, instance_id, status, policy_count, rule_count,
                    source_watermark, bucket_count, started_at, completed_at
                ) VALUES (?, 'server-query-it', 'SUCCEEDED', 1, 1, ?, 1,
                    CURRENT_TIMESTAMP - INTERVAL '1 second', CURRENT_TIMESTAMP)
                """, executionId, sourceWatermark);
        jdbcTemplate.update("""
                INSERT INTO telemetry_numeric_rollup (
                    device_id, product_key, property_id, bucket_seconds,
                    bucket_start, bucket_end, average_value, minimum_value,
                    maximum_value, sample_count, worst_quality, latest_received_at,
                    source_watermark, runtime_revision, snapshot_id,
                    policy_content_hash, execution_id
                ) VALUES (?, 'haul-truck', 'temperature', 60, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, 3, ?, ?, ?)
                """, DEVICE_ID, Timestamp.from(bucketStart),
                Timestamp.from(bucketStart.plusSeconds(60)), average, minimum, maximum,
                samples, quality, Timestamp.from(bucketStart.plusSeconds(20)),
                sourceWatermark,
                UUID.fromString("019f7000-0000-7000-8000-000000000011"),
                "sha256:" + "e".repeat(64), executionId);
    }

    private static void publishWatermark() {
        jdbcTemplate.update("""
                UPDATE telemetry_ingestion_watermark
                SET committed_ingest_id = (
                    SELECT COALESCE(MAX(ingest_id), 0) FROM telemetry_numeric
                ), updated_at = CURRENT_TIMESTAMP
                WHERE singleton_id = 1
                """);
    }

    private static void upsertDurableLatest(
            String propertyId,
            Instant effectiveAt,
            Instant occurredAt,
            double value,
            short quality,
            String bootId,
            long sequence,
            UUID eventId
    ) {
        jdbcTemplate.update("""
                INSERT INTO telemetry_latest_numeric (
                    device_id, property_id, value_double, occurred_at, effective_at,
                    received_at, quality, boot_id, sequence, event_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (device_id, property_id) DO UPDATE SET
                    value_double = EXCLUDED.value_double,
                    occurred_at = EXCLUDED.occurred_at,
                    effective_at = EXCLUDED.effective_at,
                    received_at = EXCLUDED.received_at,
                    quality = EXCLUDED.quality,
                    boot_id = EXCLUDED.boot_id,
                    sequence = EXCLUDED.sequence,
                    event_id = EXCLUDED.event_id
                WHERE EXCLUDED.effective_at > telemetry_latest_numeric.effective_at
                   OR (EXCLUDED.effective_at = telemetry_latest_numeric.effective_at
                       AND EXCLUDED.event_id > telemetry_latest_numeric.event_id)
                """,
                DEVICE_ID,
                propertyId,
                value,
                Timestamp.from(occurredAt),
                Timestamp.from(effectiveAt),
                Timestamp.from(effectiveAt.plusSeconds(1)),
                quality,
                bootId,
                sequence,
                eventId
        );
        if (bootId == null) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO telemetry_latest_state (
                    device_id, current_boot_id, highest_sequence,
                    latest_effective_at, latest_event_id
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (device_id) DO UPDATE SET
                    current_boot_id = CASE
                        WHEN EXCLUDED.latest_effective_at >= telemetry_latest_state.latest_effective_at
                        THEN EXCLUDED.current_boot_id ELSE telemetry_latest_state.current_boot_id END,
                    highest_sequence = GREATEST(
                        telemetry_latest_state.highest_sequence, EXCLUDED.highest_sequence
                    ),
                    latest_effective_at = GREATEST(
                        telemetry_latest_state.latest_effective_at, EXCLUDED.latest_effective_at
                    ),
                    latest_event_id = CASE
                        WHEN EXCLUDED.latest_effective_at >= telemetry_latest_state.latest_effective_at
                        THEN EXCLUDED.latest_event_id ELSE telemetry_latest_state.latest_event_id END
                """,
                DEVICE_ID,
                bootId,
                sequence,
                Timestamp.from(effectiveAt),
                eventId
        );
    }

    private static Path telemetryMigrationDirectory() {
        Path current = Path.of("").toAbsolutePath().normalize();
        Path fromRoot = current.resolve("mine-telemetry/src/main/resources/db/migration");
        Path fromModule = current.resolveSibling("mine-telemetry/src/main/resources/db/migration");
        Path directory = Files.isDirectory(fromRoot) ? fromRoot : fromModule;
        assertThat(directory).isDirectory();
        return directory;
    }
}
