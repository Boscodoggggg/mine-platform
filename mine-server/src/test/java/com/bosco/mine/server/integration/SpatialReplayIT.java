package com.bosco.mine.server.integration;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.common.time.MineClock;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import com.bosco.mine.server.modules.mine.spatial.SpatialReplayService;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialReplayRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialReplayResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 使用真实 TimescaleDB 验证空间重放登记的幂等、重叠冲突和异步边界。 */
@Testcontainers
class SpatialReplayIT {

    private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
            .parse("timescale/timescaledb:2.28.2-pg18-oss@sha256:"
                    + "8ed8d7336f00a3a90bb6fe709d506172012c7e673e0cf71465491d06e2db5fbd")
            .asCompatibleSubstituteFor("postgres");
    private static final UUID ORGANIZATION_ID = uuid(1);
    private static final UUID SITE_ID = uuid(2);
    private static final UUID USER_ID = uuid(3);
    private static final UUID ZONE_VERSION_ID = uuid(4);
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Container
    static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(TIMESCALE_IMAGE)
            .withDatabaseName("mine_spatial_replay")
            .withUsername("mine_ts")
            .withPassword("mine-spatial-replay-test-password");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private MineScopeAuthorizer authorizer;
    private SpatialReplayService service;

    @BeforeAll
    static void 初始化空间重放库() {
        dataSource = new DriverManagerDataSource(
                TIMESCALE.getJdbcUrl(), TIMESCALE.getUsername(), TIMESCALE.getPassword());
        Flyway.configure().dataSource(dataSource)
                .locations("filesystem:" + telemetryMigrationDirectory())
                .load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void 清理并创建重放服务() {
        jdbc.update("""
                TRUNCATE TABLE spatial_replay_request, location_runtime_revision,
                    location_snapshot_stage CASCADE
                """);
        seedActiveZoneSnapshot();
        authorizer = mock(MineScopeAuthorizer.class);
        when(authorizer.requireSite(SITE_ID)).thenReturn(site());
        when(authorizer.currentUserId()).thenReturn(USER_ID);
        service = new SpatialReplayService(
                dataSource, authorizer,
                MineClock.fixed(NOW, ZoneOffset.UTC), IdGenerator.timeOrdered());
    }

    @Test
    void 相同幂等键应返回同一请求且载荷变化应冲突() {
        SpatialReplayRequest request = request(
                NOW.minusSeconds(3_600), NOW.minusSeconds(60), "修复历史围栏配置");

        SpatialReplayResponse first = service.create(SITE_ID, "replay-001", request);
        SpatialReplayResponse repeated = service.create(SITE_ID, "replay-001", request);

        assertThat(repeated.requestId()).isEqualTo(first.requestId());
        assertThat(repeated.status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM spatial_replay_request", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT zone_item_type || ':' || zone_runtime_revision || ':'
                    || zone_content_hash
                FROM spatial_replay_request WHERE request_id = ?
                """, String.class, first.requestId())).isEqualTo(
                "GEOFENCE_VERSION:7:" + activeZoneContentHash());
        assertThat(jdbc.queryForObject(
                "SELECT scanned_points FROM spatial_replay_request WHERE request_id = ?",
                Long.class, first.requestId())).isZero();

        assertThatThrownBy(() -> service.create(
                SITE_ID, "replay-001",
                request(request.from(), request.to(), "不同原因")))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code())
                                .isEqualTo("MINE-SPATIAL-REPLAY-409-001"));
    }

    @Test
    void 不同幂等键的重叠未完成重放在并发下只能接受一个() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> first = concurrentCreate("parallel-a", ready, start);
        Callable<String> second = concurrentCreate("parallel-b", ready, start);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(first);
            var secondFuture = executor.submit(second);
            ready.await();
            start.countDown();

            assertThat(List.of(firstFuture.get(), secondFuture.get()))
                    .containsExactlyInAnyOrder("ACCEPTED", "MINE-SPATIAL-REPLAY-409-002");
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM spatial_replay_request", Long.class)).isEqualTo(1L);
    }

    @Test
    void 已耗尽重试的DEAD任务不得阻塞同区域重新发起() {
        jdbc.update("""
                INSERT INTO spatial_replay_request (
                    request_id, organization_id, site_id, zone_type, zone_version_id,
                    requested_by, idempotency_key, reason, window_start, window_end,
                    status, attempts, source_watermark, source_snapshot,
                    completed_at, last_error
                ) VALUES (?, ?, ?, 'GEOFENCE', ?, ?, 'dead-existing', '历史任务已死信',
                          ?, ?, 'DEAD', 3, 0, pg_current_snapshot(), ?,
                          'REPLAY_DEAD:IllegalStateException')
                """, uuid(20), ORGANIZATION_ID, SITE_ID, ZONE_VERSION_ID, USER_ID,
                java.sql.Timestamp.from(NOW.minusSeconds(3_600)),
                java.sql.Timestamp.from(NOW.minusSeconds(60)), java.sql.Timestamp.from(NOW));

        SpatialReplayResponse response = service.create(
                SITE_ID, "retry-after-dead",
                request(NOW.minusSeconds(3_600), NOW.minusSeconds(60), "死信后重新发起"));

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM spatial_replay_request", Long.class)).isEqualTo(2L);
    }

    @Test
    void 超过三十一天或不存在发布版本应拒绝() {
        assertThatThrownBy(() -> service.create(
                SITE_ID, "too-large",
                request(NOW.minusSeconds(31L * 86_400L + 1L), NOW, "范围过大")))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code())
                                .isEqualTo("MINE-SPATIAL-REPLAY-400-001"));

        jdbc.update("DELETE FROM location_runtime_revision WHERE site_id = ?", SITE_ID);
        assertThatThrownBy(() -> service.create(
                SITE_ID, "unknown-version",
                request(NOW.minusSeconds(3_600), NOW, "检查不存在版本")))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code())
                                .isEqualTo("MINE-SPATIAL-REPLAY-404-001"));
    }

    private Callable<String> concurrentCreate(
            String idempotencyKey,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                service.create(SITE_ID, idempotencyKey,
                        request(NOW.minusSeconds(7_200), NOW.minusSeconds(60), "并发重放"));
                return "ACCEPTED";
            } catch (MineException exception) {
                return exception.errorCode().code();
            }
        };
    }

    private SpatialReplayRequest request(Instant from, Instant to, String reason) {
        return new SpatialReplayRequest(
                "GEOFENCE", ZONE_VERSION_ID, from, to, reason);
    }

    private void seedActiveZoneSnapshot() {
        UUID snapshotId = uuid(30);
        String content = activeZoneContent();
        String contentHash = activeZoneContentHash();
        jdbc.update("""
                INSERT INTO location_snapshot_stage (
                    snapshot_id, organization_id, site_id, runtime_revision,
                    item_key, item_type, content_json, content_hash,
                    expected_item_count, snapshot_content_hash, source_created_at
                ) VALUES (?, ?, ?, 7, ?, 'GEOFENCE_VERSION', CAST(? AS JSONB), ?,
                          1, ?, CURRENT_TIMESTAMP)
                """, snapshotId, ORGANIZATION_ID, SITE_ID,
                "GEOFENCE_VERSION:" + ZONE_VERSION_ID, content, contentHash,
                contentHash);
        jdbc.update("""
                INSERT INTO location_runtime_revision (
                    site_id, organization_id, runtime_revision, snapshot_id,
                    item_count, content_hash, source_created_at
                ) VALUES (?, ?, 7, ?, 1, ?, CURRENT_TIMESTAMP)
                """, SITE_ID, ORGANIZATION_ID, snapshotId, contentHash);
    }

    private static String activeZoneContent() {
        return """
                {"boundaryToleranceMeters":1,"contentHash":"sha256:0000000000000000000000000000000000000000000000000000000000000000","coordinateSystemId":"019f7a00-0000-7000-8000-000000000031","dwellSeconds":30,"enterConfirmPoints":2,"exitConfirmPoints":2,"geofenceId":"019f7a00-0000-7000-8000-000000000032","geofenceType":"WORK","idleFlushSeconds":2,"maxZ":100,"minZ":0,"validFrom":"2026-01-01T00:00:00Z","validTo":null,"versionId":"%s","versionNumber":1,"vertices":[{"sequenceNumber":1,"x":0,"y":0},{"sequenceNumber":2,"x":100,"y":0},{"sequenceNumber":3,"x":100,"y":100}]}
                """.formatted(ZONE_VERSION_ID).strip();
    }

    private static String activeZoneContentHash() {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            activeZoneContent().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JRE 不支持 SHA-256", exception);
        }
    }

    private static MineSite site() {
        return new MineSite(
                SITE_ID, ORGANIZATION_ID, "site-code", "测试矿区", "Asia/Shanghai",
                null, "ACTIVE", NOW.minusSeconds(86_400), USER_ID,
                NOW.minusSeconds(60), USER_ID, null, 0L);
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
        return UUID.fromString("019f7a00-0000-7000-8000-%012d".formatted(suffix));
    }
}
