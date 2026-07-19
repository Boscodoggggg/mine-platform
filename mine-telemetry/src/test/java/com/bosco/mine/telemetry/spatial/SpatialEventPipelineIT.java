package com.bosco.mine.telemetry.spatial;

import com.bosco.mine.model.location.LocationObservation;
import com.bosco.mine.telemetry.location.LocationFactWriter;
import com.bosco.mine.telemetry.location.LocationQualityEvaluator;
import com.bosco.mine.telemetry.location.snapshot.LocationRuntimeRegistry;
import com.bosco.mine.telemetry.location.transform.CoordinateTransformRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 TimescaleDB 和 RabbitMQ 验证空间事件从定位事实到确认发布的可靠纵向闭环。
 */
@Testcontainers
class SpatialEventPipelineIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");
    private static final UUID ORGANIZATION_ID = uuid(1);
    private static final UUID SITE_ID = uuid(2);
    private static final UUID SOURCE_DEVICE_ID = uuid(3);
    private static final UUID TAG_ID = uuid(4);
    private static final UUID VEHICLE_ID = uuid(5);
    private static final UUID SOURCE_COORDINATE_ID = uuid(6);
    private static final UUID TARGET_COORDINATE_ID = uuid(7);
    private static final UUID TRANSFORM_ID = uuid(8);
    private static final UUID DEVICE_BINDING_ID = uuid(9);
    private static final UUID TAG_BINDING_ID = uuid(10);
    private static final UUID GEOFENCE_ID = uuid(11);
    private static final UUID GEOFENCE_VERSION_ID = uuid(12);
    private static final UUID NEXT_GEOFENCE_VERSION_ID = uuid(13);
    private static final String REALTIME_EXCHANGE = "mine.realtime.x";
    private static final String TEST_QUEUE = "mine.spatial.pipeline-it.q";
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    @Container
    static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_spatial_pipeline")
            .withUsername("mine_ts")
            .withPassword("mine-spatial-pipeline-test-password");

    @Container
    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:4.1.8-management");

    private static CachingConnectionFactory rabbitConnectionFactory;
    private static RabbitTemplate rabbitTemplate;
    private static RabbitAdmin rabbitAdmin;

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private LocationRuntimeRegistry registry;
    private SpatialRuntimeCatalog runtimeCatalog;
    private SpatialEvaluationQueue queue;
    private LocationFactWriter writer;
    private SpatialEvaluationWorker worker;
    private Binding spatialBinding;

    @BeforeAll
    static void 创建RabbitMQ确认连接() {
        rabbitConnectionFactory = new CachingConnectionFactory(
                RABBITMQ.getHost(), RABBITMQ.getAmqpPort());
        rabbitConnectionFactory.setPublisherConfirmType(
                CachingConnectionFactory.ConfirmType.CORRELATED);
        rabbitConnectionFactory.setPublisherReturns(true);
        rabbitTemplate = new RabbitTemplate(rabbitConnectionFactory);
        rabbitTemplate.setMandatory(true);
        rabbitAdmin = new RabbitAdmin(rabbitTemplate);
    }

    @AfterAll
    static void 关闭RabbitMQ连接() {
        if (rabbitConnectionFactory != null) {
            rabbitConnectionFactory.destroy();
        }
    }

    @BeforeEach
    void 初始化真实运行环境() {
        dataSource = new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .load().migrate();
        jdbc = new JdbcTemplate(dataSource);

        CoordinateTransformRegistry transforms = new CoordinateTransformRegistry();
        registry = new LocationRuntimeRegistry(dataSource, transforms);
        activateSnapshot(7L, true);
        runtimeCatalog = new SpatialRuntimeCatalog(dataSource, registry);
        queue = new SpatialEvaluationQueue(dataSource, runtimeCatalog);
        writer = new LocationFactWriter(
                dataSource, registry, transforms, new LocationQualityEvaluator(), queue);
        worker = new SpatialEvaluationWorker(
                dataSource, runtimeCatalog, queue, "spatial-pipeline-it",
                Duration.ofSeconds(30), 3,
                Duration.ofMillis(10), Duration.ofSeconds(1));

        rabbitAdmin.deleteQueue(TEST_QUEUE);
        TopicExchange exchange = new TopicExchange(REALTIME_EXCHANGE, true, false);
        Queue rabbitQueue = new Queue(TEST_QUEUE, false, false, true);
        rabbitAdmin.declareExchange(exchange);
        rabbitAdmin.declareQueue(rabbitQueue);
        spatialBinding = BindingBuilder.bind(rabbitQueue).to(exchange).with("realtime.spatial.#");
        rabbitAdmin.declareBinding(spatialBinding);
    }

    @Test
    void 定位事实应与空间队列原子提交且重复消息不得重复入队() {
        LocationObservation first = observation(
                101, 1, "2026-07-19T01:00:00Z", 0D, 0D);

        assertThat(writer.write(first).outcome()).isEqualTo(LocationFactWriter.Outcome.STORED);
        assertThat(writer.write(first).outcome()).isEqualTo(LocationFactWriter.Outcome.DUPLICATE);
        assertThat(count("location_point", first.observationId())).isEqualTo(1);
        assertThat(count("spatial_evaluation_queue", first.observationId())).isEqualTo(1);
        assertThat(queryInstant("""
                SELECT enqueued_at
                FROM spatial_evaluation_queue
                WHERE location_event_id = ?
                """, first.observationId())).isAfter(first.receivedAt());
        assertThat(queryText("""
                SELECT allowed_lateness_millis || ':' || idle_flush_millis
                FROM spatial_evaluation_queue
                WHERE location_event_id = ?
                """, first.observationId())).containsExactly("1000:2000");

        activateSnapshot(8L, true);
        jdbc.execute("""
                CREATE FUNCTION reject_spatial_enqueue() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'forced spatial enqueue rollback';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_spatial_enqueue_trigger
                BEFORE INSERT ON spatial_evaluation_queue
                FOR EACH ROW EXECUTE FUNCTION reject_spatial_enqueue()
                """);
        LocationObservation rejected = observation(
                102, 2, "2026-07-19T01:00:01Z", 0D, 0D);

        assertThatThrownBy(() -> writer.write(rejected))
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .hasMessageContaining("forced spatial enqueue rollback");
        assertThat(count("location_inbox", rejected.observationId())).isZero();
        assertThat(count("location_point", rejected.observationId())).isZero();
        assertThat(count("location_realtime_outbox", rejected.observationId())).isZero();
        assertThat(count("spatial_evaluation_queue", rejected.observationId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM spatial_runtime_snapshot WHERE runtime_revision = 8
                """, Long.class)).isZero();

        jdbc.execute("DROP TRIGGER reject_spatial_enqueue_trigger ON spatial_evaluation_queue");
        jdbc.execute("DROP FUNCTION reject_spatial_enqueue()");
        assertThat(writer.write(rejected).outcome()).isEqualTo(LocationFactWriter.Outcome.STORED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM spatial_runtime_snapshot WHERE runtime_revision = 8
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    void 状态事件与Outbox应同事务推进且失败重试和重放保持幂等() {
        writeTransitionToInside(200);
        Instant processAt = Instant.now().plusSeconds(3);

        assertThat(worker.processReady(processAt, 100)).isEqualTo(4);
        UUID enterEventId = singleEventId("ENTER");
        assertThat(queryText("""
                SELECT stable_state || ':' || candidate_count
                FROM spatial_zone_state
                WHERE zone_version_id = ? AND tag_id = ?
                """, GEOFENCE_VERSION_ID, TAG_ID)).containsExactly("INSIDE:0");
        assertThat(queryText("""
                SELECT event_type || ':' || status
                FROM spatial_event_outbox
                WHERE event_id = ?
                """, enterEventId)).containsExactly(
                "realtime.spatial.event.created:PENDING");

        UUID anchorId = queryUuid("""
                SELECT anchor_location_event_id FROM spatial_event WHERE event_id = ?
                """, enterEventId);
        jdbc.update("""
                UPDATE spatial_evaluation_queue
                SET status = 'PENDING', result_reason = NULL, processed_at = NULL,
                    next_attempt_at = TIMESTAMPTZ '2026-07-19 01:00:00Z'
                WHERE location_event_id = ?
                """, anchorId);
        assertThat(worker.processReady(processAt.plusSeconds(1), 10)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM spatial_event", Long.class)).isEqualTo(1L);

        writer.write(observation(205, 5, "2026-07-19T01:00:04Z", 0D, 0D));
        writer.write(observation(206, 6, "2026-07-19T01:00:05Z", 0D, 0D));
        assertThat(worker.processReady(processAt.plusSeconds(2), 1)).isEqualTo(1);
        jdbc.execute("""
                CREATE FUNCTION reject_spatial_outbox() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'forced spatial outbox rollback';
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_spatial_outbox_trigger
                BEFORE INSERT ON spatial_event_outbox
                FOR EACH ROW EXECUTE FUNCTION reject_spatial_outbox()
                """);

        assertThat(worker.processReady(processAt.plusSeconds(3), 1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM spatial_event WHERE event_type = 'EXIT'
                """, Long.class)).isZero();
        assertThat(queryText("""
                SELECT stable_state || ':' || candidate_count
                FROM spatial_zone_state
                WHERE zone_version_id = ? AND tag_id = ?
                """, GEOFENCE_VERSION_ID, TAG_ID)).containsExactly("INSIDE:1");
        assertThat(queryText("""
                SELECT status || ':' || result_reason
                FROM spatial_evaluation_queue
                WHERE location_event_id = ?
                """, uuid(206))).containsExactly("FAILED:EVALUATION_FAILED");

        jdbc.execute("DROP TRIGGER reject_spatial_outbox_trigger ON spatial_event_outbox");
        jdbc.execute("DROP FUNCTION reject_spatial_outbox()");
        jdbc.update("""
                UPDATE spatial_evaluation_queue
                SET next_attempt_at = TIMESTAMPTZ '2026-07-19 01:00:00Z'
                WHERE location_event_id = ?
                """, uuid(206));
        assertThat(worker.processReady(processAt.plusSeconds(4), 1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM spatial_event WHERE event_type = 'EXIT'
                """, Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM spatial_event_outbox", Long.class)).isEqualTo(2L);
    }

    @Test
    void 空间Outbox应等待真实RabbitMQ确认后再标记发布成功() {
        UUID eventId = createEnterEvent(300);
        var publisher = new ConfirmedSpatialEventPublisher(rabbitTemplate, 3_000L);
        var relay = relay(publisher, 3, Clock.systemUTC(), new CopyOnWriteArrayList<>());

        relay.publishById(eventId);

        Message message = rabbitTemplate.receive(TEST_QUEUE, 3_000L);
        assertThat(message).isNotNull();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8))
                .contains("\"eventType\":\"realtime.spatial.event.created\"")
                .contains("\"zoneVersionId\":\"" + GEOFENCE_VERSION_ID + "\"")
                .contains("\"eventType\":\"ENTER\"");
        assertThat(queryText("""
                SELECT status || ':' || attempts
                FROM spatial_event_outbox WHERE event_id = ?
                """, eventId)).containsExactly("PUBLISHED:1");
    }

    @Test
    void 空间Outbox不可路由时应指数退避并在真实发布重试上限后进入DEAD() {
        UUID eventId = createEnterEvent(400);
        rabbitAdmin.removeBinding(spatialBinding);
        Instant now = Instant.now().plusSeconds(1);
        List<Instant> retryTimes = new CopyOnWriteArrayList<>();
        var relay = relay(
                new ConfirmedSpatialEventPublisher(rabbitTemplate, 3_000L),
                2, Clock.fixed(now, ZoneOffset.UTC), retryTimes);

        relay.publishById(eventId);

        assertThat(retryTimes).containsExactly(now.plusMillis(10));
        assertThat(queryText("""
                SELECT status || ':' || attempts
                FROM spatial_event_outbox WHERE event_id = ?
                """, eventId)).containsExactly("FAILED:1");
        jdbc.update("""
                UPDATE spatial_event_outbox
                SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE event_id = ?
                """, eventId);

        relay.publishById(eventId);

        assertThat(retryTimes).hasSize(1);
        assertThat(queryText("""
                SELECT status || ':' || attempts
                FROM spatial_event_outbox WHERE event_id = ?
                """, eventId)).containsExactly("DEAD:2");
    }

    @Test
    void 缓冲期间切换运行快照仍应使用入队时固化的精确区域修订() {
        writeTransitionToInside(500);
        activateSnapshot(8L, false);

        assertThat(worker.processReady(Instant.now().plusSeconds(3), 100)).isEqualTo(4);

        assertThat(queryText("""
                SELECT event_type || ':' || runtime_revision
                FROM spatial_event
                """)).containsExactly("ENTER:7");
    }

    @Test
    void 空间Outbox租约过期重领后旧令牌不得覆盖新发布结果() {
        UUID eventId = createEnterEvent(600);
        var replacement = relay(event -> { }, 3, Clock.systemUTC(), new CopyOnWriteArrayList<>());
        var stale = relay(event -> {
            jdbc.update("""
                    UPDATE spatial_event_outbox
                    SET locked_at = CURRENT_TIMESTAMP - INTERVAL '2 seconds',
                        lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                    WHERE event_id = ?
                    """, event.eventId());
            replacement.publishById(event.eventId());
        }, 3, Clock.systemUTC(), new CopyOnWriteArrayList<>());

        assertThatThrownBy(() -> stale.publishById(eventId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租约已变化");
        assertThat(queryText("""
                SELECT status || ':' || attempts
                FROM spatial_event_outbox WHERE event_id = ?
                """, eventId)).containsExactly("PUBLISHED:2");
    }

    @Test
    void 跨版本有效期边界应采用全部发布版本的保守迟到策略() {
        activateSnapshot(9L, true, true);
        LocationObservation firstNewVersionFact = observation(
                701, 1, "2026-07-19T01:00:01Z", 200_000D, 200_000D);

        assertThat(writer.write(firstNewVersionFact).outcome())
                .isEqualTo(LocationFactWriter.Outcome.STORED);

        assertThat(queryText("""
                SELECT allowed_lateness_millis || ':' || idle_flush_millis
                FROM spatial_evaluation_queue
                WHERE location_event_id = ?
                """, firstNewVersionFact.observationId()))
                .containsExactly("300000:301000");
    }

    @Test
    void 重启加载精确快照时应拒绝与摘要不一致的区域JSON() {
        LocationObservation observation = observation(
                801, 1, "2026-07-19T01:00:00Z", 200_000D, 200_000D);
        assertThat(writer.write(observation).outcome())
                .isEqualTo(LocationFactWriter.Outcome.STORED);
        jdbc.update("""
                UPDATE spatial_runtime_snapshot_item
                SET content_json = jsonb_set(
                    content_json, '{allowedLatenessSeconds}', '99'::JSONB)
                WHERE site_id = ? AND runtime_revision = 7
                  AND item_key = ?
                """, SITE_ID, "GEOFENCE_VERSION:" + GEOFENCE_VERSION_ID);
        SpatialRuntimeCatalog reloaded = new SpatialRuntimeCatalog(dataSource, registry);

        assertThatThrownBy(() -> reloaded.find(SITE_ID, 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("摘要");
    }

    private SpatialEventOutboxRelay relay(
            SpatialEventOutboxRelay.Publisher publisher,
            int maxAttempts,
            Clock clock,
            List<Instant> retryTimes
    ) {
        return new SpatialEventOutboxRelay(
                dataSource, publisher, (eventId, retryAt) -> retryTimes.add(retryAt),
                "spatial-outbox-it", Duration.ofSeconds(30), maxAttempts, 100,
                Duration.ofMillis(10), Duration.ofSeconds(1), clock);
    }

    private UUID createEnterEvent(int baseId) {
        writeTransitionToInside(baseId);
        assertThat(worker.processReady(
                Instant.now().plusSeconds(3), 100)).isEqualTo(4);
        return singleEventId("ENTER");
    }

    private void writeTransitionToInside(int baseId) {
        writer.write(observation(baseId + 1, 1, "2026-07-19T01:00:00Z", 0D, 0D));
        writer.write(observation(baseId + 2, 2, "2026-07-19T01:00:01Z", 0D, 0D));
        writer.write(observation(baseId + 3, 3, "2026-07-19T01:00:02Z", 200_000D, 200_000D));
        writer.write(observation(baseId + 4, 4, "2026-07-19T01:00:03Z", 200_000D, 200_000D));
    }

    private LocationObservation observation(
            int id,
            long sequence,
            String occurredAt,
            double x,
            double y
    ) {
        Instant occurred = Instant.parse(occurredAt);
        return new LocationObservation(
                uuid(id), SOURCE_DEVICE_ID, "tag-01", "source-mm",
                x, y, 300D, 5D, 180D, 2D,
                "boot-spatial", sequence, occurred, occurred.plusSeconds(1));
    }

    private void activateSnapshot(long revision, boolean includeGeofence) {
        activateSnapshot(revision, includeGeofence, false);
    }

    private void activateSnapshot(
            long revision,
            boolean includeGeofence,
            boolean includePolicyBoundary
    ) {
        List<Item> items = new ArrayList<>();
        items.add(item("SITE", SITE_ID, Map.of(
                "organizationId", ORGANIZATION_ID, "siteId", SITE_ID,
                "siteCode", "north-mine", "status", "ACTIVE",
                "timezone", "Asia/Shanghai")));
        items.add(item("SOURCE_DEVICE", SOURCE_DEVICE_ID, Map.of(
                "deviceId", SOURCE_DEVICE_ID, "status", "ACTIVE")));
        items.add(item("LOCATION_TAG", TAG_ID, Map.of(
                "tagId", TAG_ID, "tagCode", "tag-01", "status", "ACTIVE")));
        items.add(item("VEHICLE", VEHICLE_ID, Map.of(
                "vehicleId", VEHICLE_ID, "vehicleType", "HAUL_TRUCK", "status", "ACTIVE")));
        items.add(item("COORDINATE_SYSTEM", SOURCE_COORDINATE_ID, Map.ofEntries(
                Map.entry("coordinateSystemId", SOURCE_COORDINATE_ID),
                Map.entry("systemCode", "source-mm"),
                Map.entry("coordinateType", "LOCAL_CARTESIAN"),
                Map.entry("unit", "MILLIMETER"), Map.entry("unified", false),
                Map.entry("status", "ACTIVE"), Map.entry("minX", -1_000_000),
                Map.entry("maxX", 1_000_000), Map.entry("minY", -1_000_000),
                Map.entry("maxY", 1_000_000), Map.entry("minZ", -10_000),
                Map.entry("maxZ", 10_000))));
        items.add(item("COORDINATE_SYSTEM", TARGET_COORDINATE_ID, Map.ofEntries(
                Map.entry("coordinateSystemId", TARGET_COORDINATE_ID),
                Map.entry("systemCode", "mine-meter"),
                Map.entry("coordinateType", "LOCAL_CARTESIAN"),
                Map.entry("unit", "METER"), Map.entry("unified", true),
                Map.entry("status", "ACTIVE"), Map.entry("minX", -1_000),
                Map.entry("maxX", 1_000), Map.entry("minY", -1_000),
                Map.entry("maxY", 1_000), Map.entry("minZ", -100),
                Map.entry("maxZ", 100))));
        items.add(item("COORDINATE_TRANSFORM", TRANSFORM_ID, Map.of(
                "coordinateTransformId", TRANSFORM_ID,
                "sourceCoordinateSystemId", SOURCE_COORDINATE_ID,
                "targetCoordinateSystemId", TARGET_COORDINATE_ID,
                "versionNumber", 1, "algorithm", "AFFINE_3D",
                "parameters", Map.of("matrix", List.of(
                        0.001D, 0D, 0D, 0D,
                        0D, 0.001D, 0D, 0D,
                        0D, 0D, 0.001D, 0D,
                        0D, 0D, 0D, 1D)),
                "validFrom", "2026-01-01T00:00:00Z")));
        items.add(item("VEHICLE_DEVICE_BINDING", DEVICE_BINDING_ID, Map.of(
                "bindingId", DEVICE_BINDING_ID, "deviceId", SOURCE_DEVICE_ID,
                "vehicleId", VEHICLE_ID, "role", "POSITION_SOURCE",
                "validFrom", "2026-01-01T00:00:00Z")));
        items.add(item("VEHICLE_TAG_BINDING", TAG_BINDING_ID, Map.of(
                "bindingId", TAG_BINDING_ID, "tagId", TAG_ID,
                "vehicleId", VEHICLE_ID,
                "validFrom", "2026-01-01T00:00:00Z")));
        if (includeGeofence) {
            if (includePolicyBoundary) {
                items.add(geofenceItem(
                        GEOFENCE_VERSION_ID, 1, 300, 301,
                        "2026-01-01T00:00:00Z", "2026-07-19T01:00:00Z"));
                items.add(geofenceItem(
                        NEXT_GEOFENCE_VERSION_ID, 2, 0, 2,
                        "2026-07-19T01:00:00Z", null));
            } else {
                items.add(geofenceItem(
                        GEOFENCE_VERSION_ID, 1, 1, 2,
                        "2026-01-01T00:00:00Z", null));
            }
        }
        items.sort(Comparator.comparing(Item::key));
        StringBuilder digest = new StringBuilder();
        items.forEach(item -> digest.append(item.key()).append(':')
                .append(item.hash()).append('\n'));
        UUID snapshotId = uuid(20 + (int) revision);
        var metadata = new LocationRuntimeRegistry.SnapshotMetadata(
                snapshotId, ORGANIZATION_ID, SITE_ID, revision, items.size(),
                hash(digest.toString()), Instant.parse("2026-07-19T00:00:00Z"));
        registry.stage(metadata, items.stream().map(item ->
                new LocationRuntimeRegistry.StagedItem(
                        item.key(), item.type(), item.json(), item.hash())).toList());
        assertThat(registry.activate(metadata)).isTrue();
    }

    private Item geofenceItem(
            UUID versionId,
            int versionNumber,
            int allowedLatenessSeconds,
            int idleFlushSeconds,
            String validFrom,
            String validTo
    ) {
        Map<String, Object> content = new TreeMap<>();
        content.put("versionId", versionId);
        content.put("geofenceId", GEOFENCE_ID);
        content.put("coordinateSystemId", TARGET_COORDINATE_ID);
        content.put("versionNumber", versionNumber);
        content.put("geofenceType", "WORK");
        content.put("boundaryToleranceMeters", 0.5D);
        content.put("enterConfirmPoints", 2);
        content.put("exitConfirmPoints", 2);
        content.put("dwellSeconds", 30);
        content.put("allowedLatenessSeconds", allowedLatenessSeconds);
        content.put("idleFlushSeconds", idleFlushSeconds);
        content.put("validFrom", validFrom);
        if (validTo != null) {
            content.put("validTo", validTo);
        }
        content.put("contentHash", "sha256:" + "d".repeat(64));
        content.put("vertices", List.of(
                Map.of("sequenceNumber", 1, "x", 100D, "y", 100D),
                Map.of("sequenceNumber", 2, "x", 500D, "y", 100D),
                Map.of("sequenceNumber", 3, "x", 100D, "y", 500D)));
        return item("GEOFENCE_VERSION", versionId, content);
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
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalize(item)));
            return sorted;
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

    private long count(String table, UUID eventId) {
        String column = "location_point".equals(table) ? "event_id" : "event_id";
        if ("spatial_evaluation_queue".equals(table)) {
            column = "location_event_id";
        }
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Long.class, eventId);
    }

    private UUID singleEventId(String eventType) {
        return queryUuid("SELECT event_id FROM spatial_event WHERE event_type = ?", eventType);
    }

    private UUID queryUuid(String sql, Object... args) {
        return jdbc.queryForObject(sql, UUID.class, args);
    }

    private Instant queryInstant(String sql, Object... args) {
        return jdbc.queryForObject(
                sql, java.time.OffsetDateTime.class, args).toInstant();
    }

    private List<String> queryText(String sql, Object... args) {
        return jdbc.query(sql, (resultSet, rowNumber) -> resultSet.getString(1), args);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019f7c00-0000-7000-8000-%012d".formatted(suffix));
    }

    private record Item(String key, String type, String json, String hash) {
    }
}
