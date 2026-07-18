package com.bosco.mine.server.integration;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.messaging.realtime.RealtimeLocationEvent;
import com.bosco.mine.server.modules.mine.dto.CurrentLocationResponse;
import com.bosco.mine.server.modules.mine.dto.LocationTrajectoryResponse;
import com.bosco.mine.server.modules.mine.location.LocationQueryRepository;
import com.bosco.mine.server.modules.mine.location.LocationQueryService;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
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
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/** 使用真实 TimescaleDB 与 Redis 验证有界位置查询、降级回填和稳定轨迹分页。 */
@Testcontainers
class LocationQueryIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");
    private static final UUID ORGANIZATION_ID = uuid(1);
    private static final UUID SITE_ID = uuid(2);
    private static final UUID OTHER_SITE_ID = uuid(3);
    private static final UUID DEVICE_ID = uuid(4);
    private static final UUID TAG_ID = uuid(5);
    private static final UUID SOURCE_SYSTEM_ID = uuid(6);
    private static final UUID TARGET_SYSTEM_ID = uuid(7);
    private static final UUID TRANSFORM_ID = uuid(8);
    private static final Instant START = Instant.parse("2026-07-18T01:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine")
            .withExposedPorts(6379);

    @Container
    static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_location_query")
            .withUsername("mine_ts")
            .withPassword("mine-location-query-test-password");

    private static LettuceConnectionFactory redisConnectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static JdbcTemplate jdbcTemplate;
    private MineScopeAuthorizer authorizer;
    private LocationQueryService service;

    @BeforeAll
    static void 初始化真实查询存储() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword());
        Flyway.configure().dataSource(dataSource)
                .locations("filesystem:" + telemetryMigrationDirectory())
                .load().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        redisConnectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        redisConnectionFactory.afterPropertiesSet();
        redisConnectionFactory.start();
        redisTemplate = new StringRedisTemplate(redisConnectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void 关闭Redis连接() {
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
    }

    @BeforeEach
    void 清理并创建查询服务() {
        redisConnectionFactory.getConnection().serverCommands().flushDb();
        jdbcTemplate.update("""
                TRUNCATE TABLE location_quarantine, location_realtime_outbox,
                               location_point, location_inbox
                """);
        authorizer = mock(MineScopeAuthorizer.class);
        service = new LocationQueryService(
                new LocationQueryRepository(redisTemplate, jdbcTemplate, 3), authorizer);
    }

    @Test
    void 当前值应优先Redis并在缺失时从时序库降级回填() throws Exception {
        RealtimeLocationEvent event = event(101, SITE_ID, 1L, START, 10D, 20D);
        insertOutbox(event);
        cache(event);

        CurrentLocationResponse cached = service.findCurrent(SITE_ID, 100);

        assertThat(cached.source()).isEqualTo("REDIS");
        assertThat(cached.items()).singleElement()
                .extracting(CurrentLocationResponse.Item::eventId)
                .isEqualTo(event.eventId());
        verify(authorizer).requireSite(SITE_ID);

        redisConnectionFactory.getConnection().serverCommands().flushDb();
        reset(authorizer);
        CurrentLocationResponse recovered = service.findCurrent(SITE_ID, 100);

        assertThat(recovered.source()).isEqualTo("TIMESCALE");
        assertThat(recovered.items()).singleElement()
                .extracting(CurrentLocationResponse.Item::eventId)
                .isEqualTo(event.eventId());
        assertThat(redisTemplate.opsForHash().get(valueKey(SITE_ID, TAG_ID), "payload"))
                .isNotNull();
        verify(authorizer).requireSite(SITE_ID);
    }

    @Test
    void 轨迹应限制矿区和时间窗并用固定水位游标排除后续迟到点() {
        for (int index = 1; index <= 5; index++) {
            insertPoint(index, SITE_ID, index, START.plusSeconds(index), index, index * index);
        }
        insertPoint(90, OTHER_SITE_ID, 90, START.plusSeconds(2), 999D, 999D);

        LocationTrajectoryResponse first = service.findTrajectory(
                SITE_ID, "TAG", TAG_ID, START, START.plusSeconds(20),
                null, null, 2);

        assertThat(first.items()).hasSize(2);
        assertThat(first.sampled()).isTrue();
        assertThat(first.sourcePointCount()).isEqualTo(3);
        assertThat(first.watermark()).isEqualTo(5L);
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(first.items())
                .extracting(LocationTrajectoryResponse.Point::siteId)
                .containsOnly(SITE_ID);

        insertPoint(6, SITE_ID, 6, START.plusSeconds(10), 666D, 666D);
        LocationTrajectoryResponse second = service.findTrajectory(
                SITE_ID, "TAG", TAG_ID, START, START.plusSeconds(20),
                first.watermark(), first.nextCursor(), 2);

        assertThat(second.items()).extracting(LocationTrajectoryResponse.Point::ingestSequence)
                .containsExactly(4L, 5L);
        assertThat(second.nextCursor()).isNull();
        assertThat(second.items()).noneMatch(point -> point.localX() == 666D);
        verify(authorizer, org.mockito.Mockito.times(2)).requireSite(SITE_ID);

        assertThatThrownBy(() -> service.findTrajectory(
                SITE_ID, "TAG", TAG_ID, START, START.plusSeconds(86_401),
                null, null, 5_000))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code())
                                .isEqualTo("MINE-LOCATION-400-001"));
    }

    private void insertOutbox(RealtimeLocationEvent event) throws Exception {
        String payload = JSON.writeValueAsString(event);
        String hash = "sha256:" + "0".repeat(64);
        jdbcTemplate.update("""
                INSERT INTO location_inbox (
                    event_id, source_device_id, boot_id, source_sequence, payload_hash,
                    occurred_at, received_at, result, ingest_sequence, processed_at
                ) VALUES (?, ?, 'boot-a', ?, ?, ?, ?, 'STORED', ?, CURRENT_TIMESTAMP)
                """, event.eventId(), event.sourceDeviceId(), event.sourceSequence(), hash,
                Timestamp.from(event.occurredAt()), Timestamp.from(event.receivedAt()),
                event.sourceSequence());
        jdbcTemplate.update("""
                INSERT INTO location_realtime_outbox (
                    event_id, ingest_sequence, event_type, schema_version, payload,
                    payload_hash, headers, status, attempts, next_attempt_at,
                    occurred_at, created_at, published_at
                ) VALUES (?, ?, 'realtime.location.updated', 1, CAST(? AS JSONB), ?,
                    CAST(? AS JSONB), 'PUBLISHED', 1, CURRENT_TIMESTAMP, ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, event.eventId(), event.sourceSequence(), payload, hash,
                JSON.writeValueAsString(Map.of(
                        "partitionKey", event.siteId().toString(),
                        "tagId", event.tagId().toString())),
                Timestamp.from(event.occurredAt()));
    }

    private void cache(RealtimeLocationEvent event) throws Exception {
        String key = valueKey(event.siteId(), event.tagId());
        redisTemplate.opsForHash().putAll(key, Map.of(
                "quality", event.qualityStatus().name(),
                "occurredAt", Long.toString(event.occurredAt().toEpochMilli()),
                "ingestSequence", Long.toString(event.sourceSequence()),
                "payload", JSON.writeValueAsString(event)));
        redisTemplate.opsForSet().add(indexKey(event.siteId()), event.tagId().toString());
    }

    private void insertPoint(
            int eventSuffix,
            UUID siteId,
            long ingestSequence,
            Instant occurredAt,
            double x,
            double y
    ) {
        jdbcTemplate.update("""
                INSERT INTO location_point (
                    time, organization_id, site_id, source_device_id, tag_id,
                    source_coordinate_system_id, source_x, source_y,
                    target_coordinate_system_id, local_x, local_y,
                    transform_version_id, speed_meters_per_second, heading_degrees,
                    accuracy_meters, quality_status, boot_id, sequence, event_id,
                    ingest_sequence, received_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 2, 90, 1,
                    'GOOD', 'boot-a', ?, ?, ?, ?)
                """, Timestamp.from(occurredAt), ORGANIZATION_ID, siteId, DEVICE_ID, TAG_ID,
                SOURCE_SYSTEM_ID, x, y, TARGET_SYSTEM_ID, x, y, TRANSFORM_ID,
                ingestSequence, uuid(eventSuffix), ingestSequence,
                Timestamp.from(occurredAt.plusSeconds(1)));
    }

    private RealtimeLocationEvent event(
            int suffix,
            UUID siteId,
            long sequence,
            Instant occurredAt,
            double x,
            double y
    ) {
        return new RealtimeLocationEvent(
                uuid(suffix), ORGANIZATION_ID, siteId, DEVICE_ID, TAG_ID, "tag-01",
                TARGET_SYSTEM_ID, null, null, sequence, occurredAt, occurredAt.plusSeconds(1),
                x, y, null, 2D, 90D, 1D,
                RealtimeLocationEvent.QualityStatus.GOOD, null);
    }

    private static Path telemetryMigrationDirectory() {
        List<Path> candidates = List.of(
                Path.of("../mine-telemetry/src/main/resources/db/migration"),
                Path.of("mine-telemetry/src/main/resources/db/migration"));
        return candidates.stream().map(Path::toAbsolutePath).map(Path::normalize)
                .filter(Files::isDirectory).findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到 Telemetry 数据库迁移目录"));
    }

    private String valueKey(UUID siteId, UUID tagId) {
        return "mine:location:{" + siteId + "}:tag:" + tagId;
    }

    private String indexKey(UUID siteId) {
        return "mine:location:{" + siteId + "}:tags";
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019f7800-0000-7000-8000-%012d".formatted(suffix));
    }
}
