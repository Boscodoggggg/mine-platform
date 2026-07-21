package com.bosco.mine.server.integration;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.time.MineClock;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import com.bosco.mine.server.modules.mine.spatial.SpatialEventQueryService;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialEventDetailResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialEventPageResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 使用真实 TimescaleDB 验证空间事件固定水位、筛选和证据详情查询。 */
@Testcontainers
class SpatialEventQueryIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");
    private static final UUID ORGANIZATION_ID = uuid(1);
    private static final UUID SITE_ID = uuid(2);
    private static final UUID OTHER_ORGANIZATION_ID = uuid(3);
    private static final UUID OTHER_SITE_ID = uuid(4);
    private static final UUID DEVICE_ID = uuid(5);
    private static final UUID TAG_ID = uuid(6);
    private static final UUID VEHICLE_ID = uuid(7);
    private static final UUID ZONE_ID = uuid(8);
    private static final UUID ZONE_VERSION_ID = uuid(9);
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Container
    static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_spatial_event_query")
            .withUsername("mine_ts")
            .withPassword("mine-spatial-event-query-test-password");

    private static JdbcTemplate jdbc;
    private MineScopeAuthorizer authorizer;
    private SpatialEventQueryService service;

    @BeforeAll
    static void 初始化空间事件查询库() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword());
        Flyway.configure().dataSource(dataSource)
                .locations("filesystem:" + telemetryMigrationDirectory())
                .load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void 清理并创建查询服务() {
        jdbc.update("TRUNCATE TABLE spatial_event_outbox, spatial_event, "
                + "spatial_evaluation_queue, location_inbox CASCADE");
        authorizer = mock(MineScopeAuthorizer.class);
        when(authorizer.requireSite(SITE_ID)).thenReturn(site(SITE_ID, ORGANIZATION_ID));
        when(authorizer.requireSite(OTHER_SITE_ID))
                .thenReturn(site(OTHER_SITE_ID, OTHER_ORGANIZATION_ID));
        service = new SpatialEventQueryService(
                jdbc, authorizer, MineClock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 分页应固定事件水位并排除查询后生成的迟到事件() {
        insertEvent(101, ORGANIZATION_ID, SITE_ID, 11L, NOW.minusSeconds(30), "ENTER");
        insertEvent(102, ORGANIZATION_ID, SITE_ID, 12L, NOW.minusSeconds(20), "DWELL");
        insertEvent(103, ORGANIZATION_ID, SITE_ID, 13L, NOW.minusSeconds(10), "EXIT");
        insertEvent(104, OTHER_ORGANIZATION_ID, OTHER_SITE_ID,
                14L, NOW.minusSeconds(5), "ENTER");

        SpatialEventPageResponse first = service.findEvents(
                SITE_ID, NOW.minusSeconds(3_600), NOW, null, null,
                null, null, null, null, null, 2);

        assertThat(first.items()).extracting(SpatialEventPageResponse.Item::eventType)
                .containsExactly("EXIT", "DWELL");
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(first.watermark()).isPositive();
        long fixedWatermark = first.watermark();

        insertEvent(105, ORGANIZATION_ID, SITE_ID, 99L, NOW.minusSeconds(25), "ENTER");
        SpatialEventPageResponse second = service.findEvents(
                SITE_ID, NOW.minusSeconds(3_600), NOW, fixedWatermark,
                first.nextCursor(), null, null, null, null, null, 2);

        assertThat(second.items()).extracting(SpatialEventPageResponse.Item::eventId)
                .containsExactly(uuid(101));
        assertThat(second.nextCursor()).isNull();
        assertThat(second.items()).noneMatch(item -> item.eventId().equals(uuid(105)));
        verify(authorizer, org.mockito.Mockito.times(2)).requireSite(SITE_ID);
    }

    @Test
    void 默认时间窗筛选和详情应同时受组织矿区保护() {
        insertEvent(201, ORGANIZATION_ID, SITE_ID, 21L, NOW.minusSeconds(120), "ENTER");
        insertEvent(202, ORGANIZATION_ID, SITE_ID, 22L,
                NOW.minusSeconds(24 * 3_600L + 1L), "EXIT");
        insertEvent(203, OTHER_ORGANIZATION_ID, SITE_ID, 23L, NOW.minusSeconds(60), "ENTER");

        SpatialEventPageResponse filtered = service.findEvents(
                SITE_ID, null, null, null, null,
                "GEOFENCE", ZONE_ID, "VEHICLE", VEHICLE_ID, "ENTER", 100);

        assertThat(filtered.from()).isEqualTo(NOW.minusSeconds(24 * 3_600L));
        assertThat(filtered.to()).isEqualTo(NOW);
        assertThat(filtered.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.eventId()).isEqualTo(uuid(201));
                    assertThat(item.ingestSequence()).isEqualTo(21L);
                });

        SpatialEventDetailResponse detail = service.findEvent(SITE_ID, uuid(201));
        assertThat(detail.anchorLocationEventId()).isEqualTo(uuid(1201));
        assertThat(detail.confirmationPoints()).isEqualTo(2);
        assertThat(detail.evidence().classification()).isEqualTo("INSIDE");
        assertThat(detail.evidence().zoneContentHash())
                .isEqualTo("sha256:" + "a".repeat(64));

        assertThatThrownBy(() -> service.findEvent(SITE_ID, uuid(203)))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code())
                                .isEqualTo("MINE-SPATIAL-EVENT-404-001"));
        assertThatThrownBy(() -> service.findEvents(
                SITE_ID, NOW.minusSeconds(31L * 86_400L + 1L), NOW,
                null, null, null, null, null, null, null, 100))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code())
                                .isEqualTo("MINE-SPATIAL-EVENT-400-001"));
    }

    @Test
    void 区域类型可独立筛选且游标必须绑定原查询() {
        insertEvent(301, ORGANIZATION_ID, SITE_ID, 31L, NOW.minusSeconds(30), "ENTER");
        insertEvent(302, ORGANIZATION_ID, SITE_ID, 32L, NOW.minusSeconds(20), "EXIT");

        SpatialEventPageResponse first = service.findEvents(
                SITE_ID, NOW.minusSeconds(3_600), NOW, null, null,
                "GEOFENCE", null, null, null, null, 1);

        assertThat(first.items()).singleElement();
        assertThat(first.nextCursor()).isNotBlank();
        assertThatThrownBy(() -> service.findEvents(
                SITE_ID, NOW.minusSeconds(3_600), NOW, first.watermark(),
                first.nextCursor(), "GEOFENCE", null,
                null, null, "ENTER", 1))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code())
                                .isEqualTo("MINE-SPATIAL-EVENT-400-003"));
        assertThatThrownBy(() -> service.findEvents(
                SITE_ID, NOW.minusSeconds(3_600), NOW, first.watermark(),
                "not-a-cursor", "GEOFENCE", null,
                null, null, null, 1))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code())
                                .isEqualTo("MINE-SPATIAL-EVENT-400-003"));
    }

    @Test
    void 事件序号分配必须按事务提交串行以保证固定水位() throws Exception {
        insertEvent(401, ORGANIZATION_ID, SITE_ID, 41L, NOW.minusSeconds(40), "ENTER");
        insertEvent(402, ORGANIZATION_ID, SITE_ID, 42L, NOW.minusSeconds(10), "EXIT");
        DriverManagerDataSource source = new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword());
        try (Connection firstConnection = source.getConnection();
             Connection secondConnection = source.getConnection();
             var executor = Executors.newSingleThreadExecutor()) {
            firstConnection.setAutoCommit(false);
            secondConnection.setAutoCommit(false);
            insertEvent(new JdbcTemplate(new SingleConnectionDataSource(firstConnection, true)),
                    403, ORGANIZATION_ID, SITE_ID, 43L, NOW.minusSeconds(30), "ENTER");

            var laterCommit = executor.submit(() -> {
                insertEvent(new JdbcTemplate(new SingleConnectionDataSource(secondConnection, true)),
                        404, ORGANIZATION_ID, SITE_ID, 44L, NOW.minusSeconds(20), "DWELL");
                secondConnection.commit();
                return null;
            });

            Thread.sleep(200L);
            assertThat(laterCommit).as("后续空间事件必须等待较早事务提交后才能分配序号")
                    .isNotDone();
            SpatialEventPageResponse first = service.findEvents(
                    SITE_ID, NOW.minusSeconds(3_600), NOW, null, null,
                    null, null, null, null, null, 1);

            firstConnection.commit();
            laterCommit.get(5, TimeUnit.SECONDS);
            SpatialEventPageResponse second = service.findEvents(
                    SITE_ID, NOW.minusSeconds(3_600), NOW, first.watermark(),
                    first.nextCursor(), null, null, null, null, null, 10);

            assertThat(first.items()).extracting(SpatialEventPageResponse.Item::eventId)
                    .containsExactly(uuid(402));
            assertThat(second.items()).extracting(SpatialEventPageResponse.Item::eventId)
                    .containsExactly(uuid(401));
        }
    }

    private void insertEvent(
            int suffix,
            UUID organizationId,
            UUID siteId,
            long ingestSequence,
            Instant occurredAt,
            String eventType
    ) {
        insertEvent(jdbc, suffix, organizationId, siteId, ingestSequence, occurredAt, eventType);
    }

    private void insertEvent(
            JdbcTemplate target,
            int suffix,
            UUID organizationId,
            UUID siteId,
            long ingestSequence,
            Instant occurredAt,
            String eventType
    ) {
        UUID locationEventId = uuid(suffix + 1_000);
        String previousState = "ENTER".equals(eventType) ? "OUTSIDE" : "INSIDE";
        String currentState = "EXIT".equals(eventType) ? "OUTSIDE" : "INSIDE";
        target.update("""
                INSERT INTO location_inbox (
                    event_id, source_device_id, boot_id, source_sequence,
                    payload_hash, occurred_at, received_at, result, ingest_sequence
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'STORED', ?)
                """, locationEventId, DEVICE_ID, "query-boot-" + suffix, ingestSequence,
                "sha256:" + "0".repeat(64), Timestamp.from(occurredAt),
                Timestamp.from(occurredAt.plusSeconds(1)), ingestSequence);
        target.update("""
                INSERT INTO spatial_evaluation_queue (
                    location_event_id, organization_id, site_id, tag_id,
                    occurred_at, ingest_sequence, runtime_revision,
                    status, processed_at
                ) VALUES (?, ?, ?, ?, ?, ?, 7, 'PROCESSED', CURRENT_TIMESTAMP)
                """, locationEventId, organizationId, siteId, TAG_ID,
                Timestamp.from(occurredAt), ingestSequence);
        target.update("""
                INSERT INTO spatial_event (
                    event_id, organization_id, site_id, zone_type, zone_id,
                    zone_version_id, tag_id, vehicle_id, event_type,
                    anchor_location_event_id, previous_state, current_state,
                    confirmation_points, runtime_revision, occurred_at, evidence
                ) VALUES (?, ?, ?, 'GEOFENCE', ?, ?, ?, ?, ?, ?, ?, ?, 2, 7, ?,
                          CAST(? AS JSONB))
                """, uuid(suffix), organizationId, siteId, ZONE_ID, ZONE_VERSION_ID,
                TAG_ID, VEHICLE_ID, eventType, locationEventId, previousState, currentState,
                Timestamp.from(occurredAt), """
                        {"localX":120.5,"localY":88.25,"localZ":null,
                         "classification":"INSIDE","qualityStatus":"GOOD",
                         "qualityReason":null,
                         "zoneContentHash":"sha256:%s"}
                        """.formatted("a".repeat(64)));
    }

    private static MineSite site(UUID siteId, UUID organizationId) {
        return new MineSite(
                siteId, organizationId, "site-code", "测试矿区", "Asia/Shanghai",
                null, "ACTIVE", NOW.minusSeconds(86_400), uuid(90),
                NOW.minusSeconds(60), uuid(90), null, 0L);
    }

    private static Path telemetryMigrationDirectory() {
        return List.of(
                        Path.of("../mine-telemetry/src/main/resources/db/migration"),
                        Path.of("mine-telemetry/src/main/resources/db/migration"))
                .stream().map(Path::toAbsolutePath).map(Path::normalize)
                .filter(Files::isDirectory).findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到 Telemetry 数据库迁移目录"));
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("019f7900-0000-7000-8000-%012d".formatted(suffix));
    }
}
