package com.bosco.mine.telemetry.location;

import com.bosco.mine.messaging.realtime.RealtimeLocationEvent;
import com.bosco.mine.model.location.LocationObservation;
import com.bosco.mine.telemetry.location.snapshot.LocationRuntimeRegistry;
import com.bosco.mine.telemetry.location.transform.CoordinateTransformRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实 TimescaleDB 和 Redis 验证定位历史、发生时绑定、顺序缓存及恢复闭环。 */
@Testcontainers
class LocationPipelineIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");
    private static final UUID ORGANIZATION_ID = uuid(1);
    private static final UUID SITE_ID = uuid(2);
    private static final UUID DEVICE_ID = uuid(3);
    private static final UUID SOURCE_SYSTEM_ID = uuid(4);
    private static final UUID TARGET_SYSTEM_ID = uuid(5);
    private static final UUID TRANSFORM_ID = uuid(6);
    private static final UUID TAG_ID = uuid(7);
    private static final UUID UNBOUND_TAG_ID = uuid(8);
    private static final UUID ARCHIVED_TAG_ID = uuid(9);
    private static final UUID VEHICLE_A_ID = uuid(10);
    private static final UUID VEHICLE_B_ID = uuid(11);
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    @Container
    static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_location_pipeline")
            .withUsername("mine_ts")
            .withPassword("mine-location-pipeline-test-password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine")
            .withExposedPorts(6379);

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private LettuceConnectionFactory redisConnectionFactory;
    private StringRedisTemplate redisTemplate;
    private LocationFactWriter writer;
    private LocationLatestCache cache;

    @BeforeEach
    void 初始化真实存储和最后正确快照() throws Exception {
        dataSource = new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);

        redisConnectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        redisConnectionFactory.afterPropertiesSet();
        redisConnectionFactory.start();
        redisTemplate = new StringRedisTemplate(redisConnectionFactory);
        redisTemplate.afterPropertiesSet();
        REDIS.execInContainer("redis-cli", "FLUSHALL");

        CoordinateTransformRegistry transforms = new CoordinateTransformRegistry();
        LocationRuntimeRegistry registry = new LocationRuntimeRegistry(dataSource, transforms);
        activateSnapshot(registry);
        writer = new LocationFactWriter(
                dataSource, registry, transforms, new LocationQualityEvaluator());
        cache = new LocationLatestCache(redisTemplate, dataSource);
    }

    @AfterEach
    void 关闭Redis客户端() {
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
    }

    @Test
    void 应固化发生时绑定并幂等保存历史且可恢复不倒退的当前位置() throws Exception {
        LocationObservation beforeSwitch = observation(
                101, "tag-01", 1, "2026-07-18T00:30:00Z", 2D, 1_000D);
        LocationObservation afterSwitch = observation(
                102, "tag-01", 2, "2026-07-18T01:30:00Z", 2D, 2_000D);
        LocationObservation late = observation(
                103, "tag-01", 3, "2026-07-18T01:15:00Z", 2D, 1_500D);
        LocationObservation degraded = observation(
                104, "tag-01", 4, "2026-07-18T01:40:00Z", 100D, 2_500D);
        LocationObservation unbound = observation(
                105, "tag-unbound", 5, "2026-07-18T01:45:00Z", 2D, 3_000D);

        LocationFactWriter.WriteResult first = writer.write(beforeSwitch);
        LocationFactWriter.WriteResult second = writer.write(afterSwitch);
        assertThat(writer.write(beforeSwitch).outcome())
                .isEqualTo(LocationFactWriter.Outcome.DUPLICATE);
        LocationFactWriter.WriteResult lateResult = writer.write(late);
        LocationFactWriter.WriteResult degradedResult = writer.write(degraded);
        LocationFactWriter.WriteResult unboundResult = writer.write(unbound);

        assertThat(first.event().vehicleId()).isEqualTo(VEHICLE_A_ID);
        assertThat(second.event().vehicleId()).isEqualTo(VEHICLE_B_ID);
        assertThat(degradedResult.event().qualityStatus())
                .isEqualTo(RealtimeLocationEvent.QualityStatus.DEGRADED);
        assertThat(degradedResult.event().qualityReason()).isEqualTo("LOW_ACCURACY");
        assertThat(unboundResult.event().vehicleId()).isNull();
        assertThat(unboundResult.event().personId()).isNull();
        assertThat(query("SELECT COUNT(*)::TEXT FROM location_point"))
                .containsExactly("5");
        assertThat(query("""
                SELECT vehicle_id::TEXT
                FROM location_point
                WHERE event_id IN ('%s', '%s')
                ORDER BY time
                """.formatted(beforeSwitch.observationId(), afterSwitch.observationId())))
                .containsExactly(VEHICLE_A_ID.toString(), VEHICLE_B_ID.toString());

        assertThat(cache.updateIfNewer(second.event())).isTrue();
        assertThat(cache.updateIfNewer(lateResult.event())).isFalse();
        assertThat(cache.updateIfNewer(degradedResult.event())).isFalse();
        assertThat(cache.find(SITE_ID, TAG_ID)).get()
                .extracting(RealtimeLocationEvent::eventId)
                .isEqualTo(afterSwitch.observationId());

        REDIS.execInContainer("redis-cli", "FLUSHALL");
        assertThat(cache.find(SITE_ID, TAG_ID)).isEmpty();
        assertThat(cache.rebuildSite(SITE_ID, 100)).isEqualTo(2);
        assertThat(cache.find(SITE_ID, TAG_ID)).get()
                .extracting(RealtimeLocationEvent::eventId)
                .isEqualTo(afterSwitch.observationId());
        assertThat(query("""
                SELECT last_ingest_sequence::TEXT
                FROM location_current_rebuild_watermark
                WHERE site_id = '%s'
                """.formatted(SITE_ID))).hasSize(1);
    }

    @Test
    void 有界缓存重建应优先恢复最近活跃标签() throws Exception {
        LocationFactWriter.WriteResult older = writer.write(observation(
                106, "tag-01", 6, "2026-07-18T01:00:00Z", 2D, 1_000D));
        LocationFactWriter.WriteResult newer = writer.write(observation(
                107, "tag-unbound", 7, "2026-07-18T01:45:00Z", 2D, 3_000D));
        assertThat(older.outcome()).isEqualTo(LocationFactWriter.Outcome.STORED);
        assertThat(newer.outcome()).isEqualTo(LocationFactWriter.Outcome.STORED);
        REDIS.execInContainer("redis-cli", "FLUSHALL");

        assertThat(cache.rebuildSite(SITE_ID, 1)).isEqualTo(1);

        assertThat(cache.find(SITE_ID, TAG_ID)).isEmpty();
        assertThat(cache.find(SITE_ID, UNBOUND_TAG_ID)).get()
                .extracting(RealtimeLocationEvent::eventId)
                .isEqualTo(newer.event().eventId());
    }

    @Test
    void 未知归档越界和未授权来源应进入有界隔离且不写历史() {
        assertThat(writer.write(observation(
                201, "missing-tag", 11, "2026-07-18T01:00:00Z", 2D, 1_000D)).outcome())
                .isEqualTo(LocationFactWriter.Outcome.QUARANTINED);
        assertThat(writer.write(observation(
                202, "tag-archived", 12, "2026-07-18T01:00:00Z", 2D, 1_000D)).outcome())
                .isEqualTo(LocationFactWriter.Outcome.QUARANTINED);
        assertThat(writer.write(observation(
                203, "tag-01", 13, "2026-07-18T01:00:00Z", 2D, 2_000_000D)).outcome())
                .isEqualTo(LocationFactWriter.Outcome.QUARANTINED);
        LocationObservation unauthorized = new LocationObservation(
                uuid(204), uuid(999), "tag-01", "source-mm",
                1_000D, 2_000D, 300D, 5D, 180D, 2D,
                "boot-unauthorized", 1L,
                Instant.parse("2026-07-18T01:00:00Z"),
                Instant.parse("2026-07-18T01:00:01Z"));
        assertThat(writer.write(unauthorized).outcome())
                .isEqualTo(LocationFactWriter.Outcome.QUARANTINED);
        assertThat(writer.quarantineInvalid(new LocationFactWriter.InvalidObservationCandidate(
                uuid(205), DEVICE_ID, "boot-invalid", 1L,
                Instant.parse("2026-07-18T01:00:00Z"),
                Instant.parse("2026-07-18T01:00:01Z"),
                "{\"x\":\"not-a-number\"}".getBytes(StandardCharsets.UTF_8))).outcome())
                .isEqualTo(LocationFactWriter.Outcome.QUARANTINED);

        assertThat(query("SELECT COUNT(*)::TEXT FROM location_point"))
                .containsExactly("0");
        assertThat(query("""
                SELECT reason_code
                FROM location_quarantine
                ORDER BY reason_code
                """))
                .containsExactly(
                        "ARCHIVED_TAG", "INVALID_OBSERVATION", "OUT_OF_BOUNDS",
                        "SOURCE_DEVICE_UNAUTHORIZED", "UNKNOWN_TAG");
        assertThat(query("""
                SELECT COUNT(*)::TEXT
                FROM location_quarantine
                WHERE expires_at > created_at
                  AND expires_at <= created_at + INTERVAL '30 days'
                  AND pg_column_size(evidence) <= 4096
                """))
                .containsExactly("5");
    }

    @Test
    void 实时Outbox应使用租约发布并在确认后完成状态推进() {
        LocationFactWriter.WriteResult stored = writer.write(observation(
                301, "tag-01", 21, "2026-07-18T01:30:00Z", 2D, 2_000D));
        List<LocationRealtimeOutboxRelay.ClaimedEvent> published = new CopyOnWriteArrayList<>();
        LocationRealtimeOutboxRelay relay = new LocationRealtimeOutboxRelay(
                dataSource, published::add, (eventId, retryAt) -> { },
                "location-pipeline-it", Duration.ofSeconds(30), 3, 100,
                Duration.ofSeconds(1), Duration.ofMinutes(1), Clock.systemUTC());

        relay.publishById(stored.event().eventId());
        relay.publishById(stored.event().eventId());

        assertThat(published).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo(stored.event().eventId());
            assertThat(event.ingestSequence()).isEqualTo(stored.event().sourceSequence());
        });
        assertThat(query("""
                SELECT status || ':' || attempts
                FROM location_realtime_outbox
                WHERE event_id = '%s'
                """.formatted(stored.event().eventId())))
                .containsExactly("PUBLISHED:1");
    }

    @Test
    void 实时Outbox失败应退避重试并在达到上限后进入死信() {
        LocationFactWriter.WriteResult stored = writer.write(observation(
                302, "tag-01", 22, "2026-07-18T01:30:00Z", 2D, 2_000D));
        List<Instant> retryTimes = new CopyOnWriteArrayList<>();
        Instant now = Instant.parse("2026-07-18T02:00:00Z");
        LocationRealtimeOutboxRelay relay = new LocationRealtimeOutboxRelay(
                dataSource,
                event -> {
                    throw new IllegalStateException(
                            "broker password=never-store token:never-store unavailable");
                },
                (eventId, retryAt) -> retryTimes.add(retryAt),
                "location-pipeline-it", Duration.ofSeconds(30), 2, 100,
                Duration.ofSeconds(1), Duration.ofMinutes(1),
                Clock.fixed(now, java.time.ZoneOffset.UTC));

        relay.publishById(stored.event().eventId());

        assertThat(retryTimes).containsExactly(now.plusSeconds(1));
        assertThat(query("""
                SELECT status || ':' || attempts || ':' || last_error
                FROM location_realtime_outbox
                WHERE event_id = '%s'
                """.formatted(stored.event().eventId())))
                .containsExactly(
                        "FAILED:1:broker password=[REDACTED] token=[REDACTED] unavailable");

        jdbcTemplate.update("""
                UPDATE location_realtime_outbox
                SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE event_id = ?
                """, stored.event().eventId());
        relay.publishById(stored.event().eventId());

        assertThat(retryTimes).hasSize(1);
        assertThat(query("""
                SELECT status || ':' || attempts || ':' || last_error
                FROM location_realtime_outbox
                WHERE event_id = '%s'
                """.formatted(stored.event().eventId())))
                .containsExactly(
                        "DEAD:2:broker password=[REDACTED] token=[REDACTED] unavailable");
    }

    private LocationObservation observation(
            int id,
            String tagCode,
            long sequence,
            String occurredAt,
            Double accuracy,
            double x
    ) {
        Instant occurred = Instant.parse(occurredAt);
        return new LocationObservation(
                uuid(id), DEVICE_ID, tagCode, "source-mm",
                x, 2_000D, 300D, 5D, 180D, accuracy,
                "boot-a", sequence, occurred, occurred.plusSeconds(1));
    }

    private void activateSnapshot(LocationRuntimeRegistry registry) {
        List<Item> items = new ArrayList<>();
        items.add(item("SITE", SITE_ID, Map.of(
                "organizationId", ORGANIZATION_ID, "siteId", SITE_ID,
                "siteCode", "north-mine", "status", "ACTIVE",
                "timezone", "Asia/Shanghai")));
        items.add(item("SOURCE_DEVICE", DEVICE_ID, Map.of(
                "deviceId", DEVICE_ID, "status", "ACTIVE")));
        items.add(item("LOCATION_TAG", TAG_ID, Map.of(
                "tagId", TAG_ID, "tagCode", "tag-01", "status", "ACTIVE")));
        items.add(item("LOCATION_TAG", UNBOUND_TAG_ID, Map.of(
                "tagId", UNBOUND_TAG_ID, "tagCode", "tag-unbound", "status", "ACTIVE")));
        items.add(item("LOCATION_TAG", ARCHIVED_TAG_ID, Map.of(
                "tagId", ARCHIVED_TAG_ID, "tagCode", "tag-archived", "status", "ARCHIVED")));
        items.add(item("VEHICLE", VEHICLE_A_ID, Map.of(
                "vehicleId", VEHICLE_A_ID, "vehicleType", "HAUL_TRUCK", "status", "ACTIVE")));
        items.add(item("VEHICLE", VEHICLE_B_ID, Map.of(
                "vehicleId", VEHICLE_B_ID, "vehicleType", "HAUL_TRUCK", "status", "ACTIVE")));
        items.add(item("COORDINATE_SYSTEM", SOURCE_SYSTEM_ID, Map.ofEntries(
                Map.entry("coordinateSystemId", SOURCE_SYSTEM_ID),
                Map.entry("systemCode", "source-mm"),
                Map.entry("coordinateType", "LOCAL_CARTESIAN"),
                Map.entry("unit", "MILLIMETER"), Map.entry("unified", false),
                Map.entry("status", "ACTIVE"), Map.entry("minX", -1_000_000),
                Map.entry("maxX", 1_000_000), Map.entry("minY", -1_000_000),
                Map.entry("maxY", 1_000_000), Map.entry("minZ", -10_000),
                Map.entry("maxZ", 10_000))));
        items.add(item("COORDINATE_SYSTEM", TARGET_SYSTEM_ID, Map.ofEntries(
                Map.entry("coordinateSystemId", TARGET_SYSTEM_ID),
                Map.entry("systemCode", "mine-meter"),
                Map.entry("coordinateType", "LOCAL_CARTESIAN"),
                Map.entry("unit", "METER"), Map.entry("unified", true),
                Map.entry("status", "ACTIVE"), Map.entry("minX", -1_000),
                Map.entry("maxX", 1_000), Map.entry("minY", -1_000),
                Map.entry("maxY", 1_000), Map.entry("minZ", -100),
                Map.entry("maxZ", 100))));
        items.add(item("COORDINATE_TRANSFORM", TRANSFORM_ID, Map.of(
                "coordinateTransformId", TRANSFORM_ID,
                "sourceCoordinateSystemId", SOURCE_SYSTEM_ID,
                "targetCoordinateSystemId", TARGET_SYSTEM_ID,
                "versionNumber", 1, "algorithm", "AFFINE_3D",
                "parameters", Map.of("matrix", List.of(
                        0.001D, 0D, 0D, 0D,
                        0D, 0.001D, 0D, 0D,
                        0D, 0D, 0.001D, 0D,
                        0D, 0D, 0D, 1D)),
                "validFrom", "2026-01-01T00:00:00Z")));
        items.add(item("VEHICLE_DEVICE_BINDING", uuid(20), Map.of(
                "bindingId", uuid(20), "deviceId", DEVICE_ID,
                "vehicleId", VEHICLE_A_ID, "role", "PRIMARY",
                "validFrom", "2026-01-01T00:00:00Z")));
        items.add(item("VEHICLE_TAG_BINDING", uuid(21), Map.of(
                "bindingId", uuid(21), "tagId", TAG_ID,
                "vehicleId", VEHICLE_A_ID,
                "validFrom", "2026-01-01T00:00:00Z",
                "validTo", "2026-07-18T01:00:00Z")));
        items.add(item("VEHICLE_TAG_BINDING", uuid(22), Map.of(
                "bindingId", uuid(22), "tagId", TAG_ID,
                "vehicleId", VEHICLE_B_ID,
                "validFrom", "2026-07-18T01:00:00Z")));
        items.sort(Comparator.comparing(Item::key));
        StringBuilder digest = new StringBuilder();
        items.forEach(item -> digest.append(item.key()).append(':')
                .append(item.hash()).append('\n'));
        UUID snapshotId = uuid(30);
        var metadata = new LocationRuntimeRegistry.SnapshotMetadata(
                snapshotId, ORGANIZATION_ID, SITE_ID, 1L, items.size(),
                hash(digest.toString()), Instant.parse("2026-07-18T00:00:00Z"));
        registry.stage(metadata, items.stream().map(item ->
                new LocationRuntimeRegistry.StagedItem(
                        item.key(), item.type(), item.json(), item.hash())).toList());
        assertThat(registry.activate(metadata)).isTrue();
    }

    private Item item(String type, UUID id, Map<String, Object> content) {
        String json = canonicalJson(content);
        return new Item(type + ":" + id, type, json, hash(json));
    }

    private String canonicalJson(Object value) {
        try {
            return JSON.writeValueAsString(canonicalize(value));
        } catch (JacksonException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(
                    String.valueOf(key), canonicalize(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonicalize).toList();
        }
        return value;
    }

    private String hash(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<String> query(String sql) {
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> resultSet.getString(1));
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019f7500-0000-7000-8000-%012d".formatted(suffix));
    }

    private record Item(String key, String type, String json, String hash) {
    }
}
