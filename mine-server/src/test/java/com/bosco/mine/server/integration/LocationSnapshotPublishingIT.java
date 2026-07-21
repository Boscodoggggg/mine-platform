package com.bosco.mine.server.integration;

import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.command.outbox.OutboxRelayRepository;
import com.bosco.mine.server.modules.mine.location.LocationSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 校验定位运行快照的幂等、修订、分页、摘要和事务一致性。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class LocationSnapshotPublishingIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private LocationSnapshotService snapshotService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OutboxRelayRepository outboxRelayRepository;

    private UUID organizationId;
    private UUID siteId;

    @BeforeEach
    void 准备空矿区() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    mine_location_runtime_snapshot_item,
                    mine_location_runtime_snapshot,
                    mine_location_tag,
                    mine_site,
                    mine_organization,
                    sys_outbox_event
                CASCADE
                """);
        organizationId = UUID.randomUUID();
        siteId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_organization (id, organization_code, name)
                VALUES (?, ?, '定位快照测试组织')
                """, organizationId, "snapshot-org-" + shortId(organizationId));
        jdbcTemplate.update("""
                INSERT INTO mine_site (id, organization_id, site_code, name, timezone)
                VALUES (?, ?, ?, '定位快照测试矿区', 'Asia/Shanghai')
                """, siteId, organizationId, "snapshot-site-" + shortId(siteId));
    }

    @Test
    void 同一规范内容必须幂等且修订摘要分页完整() {
        UUID firstTagId = insertTag("snapshot-tag-a", "ACTIVE");
        UUID secondTagId = insertTag("snapshot-tag-b", "LOST");

        LocationSnapshotService.SnapshotView first = snapshotService.captureSite(
                organizationId, siteId, LocationSnapshotService.ChangeReason.ASSET_CHANGE)
                .orElseThrow();

        assertThat(snapshotService.captureSite(
                organizationId, siteId, LocationSnapshotService.ChangeReason.ASSET_CHANGE))
                .isEmpty();
        assertThat(first.revision()).isOne();
        assertThat(first.contentHash()).matches("sha256:[0-9a-f]{64}");
        assertThat(first.itemCount()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_location_runtime_snapshot", Long.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_outbox_event
                WHERE aggregate_type = 'LOCATION_RUNTIME_SNAPSHOT'
                  AND event_type = 'location.runtime.changed'
                  AND (SELECT COUNT(*) FROM jsonb_object_keys(payload)) = 3
                  AND payload ?& ARRAY['snapshotId', 'revision', 'contentHash']
                """, Long.class)).isOne();
        assertThat(outboxRelayRepository.findRecoverableEventIds(10))
                .contains(first.outboxEventId());
        assertThat(outboxRelayRepository.claimById(
                first.outboxEventId(), "location-snapshot-it", Duration.ofSeconds(30)))
                .get()
                .extracting(event -> event.eventType())
                .isEqualTo("location.runtime.changed");

        List<LocationSnapshotService.SnapshotItemView> allItems = new ArrayList<>();
        String cursor = null;
        do {
            LocationSnapshotService.SnapshotPage page = snapshotService.findSnapshotPage(
                    first.snapshotId(), cursor, 1);
            assertThat(page.snapshotId()).isEqualTo(first.snapshotId());
            assertThat(page.revision()).isEqualTo(first.revision());
            assertThat(page.contentHash()).isEqualTo(first.contentHash());
            allItems.addAll(page.items());
            cursor = page.nextAfterItemKey();
        } while (cursor != null);

        assertThat(allItems)
                .extracting(LocationSnapshotService.SnapshotItemView::itemKey)
                .containsExactlyElementsOf(List.of(
                        "LOCATION_TAG:" + firstTagId,
                        "LOCATION_TAG:" + secondTagId,
                        "SITE:" + siteId).stream().sorted().toList());
        assertThat(allItems)
                .extracting(LocationSnapshotService.SnapshotItemView::itemType)
                .containsExactly("LOCATION_TAG", "LOCATION_TAG", "SITE");

        jdbcTemplate.update(
                "UPDATE mine_location_tag SET status = 'ACTIVE' WHERE id = ?", secondTagId);
        LocationSnapshotService.SnapshotView second = snapshotService.captureSite(
                organizationId, siteId, LocationSnapshotService.ChangeReason.ASSET_CHANGE)
                .orElseThrow();
        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.contentHash()).isNotEqualTo(first.contentHash());

        jdbcTemplate.update(
                "UPDATE mine_location_tag SET status = 'LOST' WHERE id = ?", secondTagId);
        LocationSnapshotService.SnapshotView reverted = snapshotService.captureSite(
                organizationId, siteId, LocationSnapshotService.ChangeReason.ASSET_CHANGE)
                .orElseThrow();
        assertThat(reverted.revision()).isEqualTo(3);
        assertThat(reverted.contentHash()).isEqualTo(first.contentHash());
    }

    @Test
    void 快照必须不可变且外层事务回滚不得留下快照或Outbox() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            insertTag("rolled-back-tag", "ACTIVE");
            assertThat(snapshotService.captureSite(
                    organizationId, siteId, LocationSnapshotService.ChangeReason.ASSET_CHANGE))
                    .isPresent();
            status.setRollbackOnly();
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mine_location_runtime_snapshot", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sys_outbox_event
                WHERE event_type = 'location.runtime.changed'
                """, Long.class)).isZero();

        LocationSnapshotService.SnapshotView committed = snapshotService.captureSite(
                organizationId, siteId, LocationSnapshotService.ChangeReason.SITE_CHANGE)
                .orElseThrow();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE mine_location_runtime_snapshot
                SET content_hash = ?
                WHERE id = ?
                """, "sha256:" + "0".repeat(64), committed.snapshotId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                DELETE FROM mine_location_runtime_snapshot_item
                WHERE snapshot_id = ?
                """, committed.snapshotId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 空间快照必须只下发活动对象的已发布版本并保持引用与几何顺序() {
        UUID coordinateId = UUID.randomUUID();
        UUID firstWaypointId = UUID.randomUUID();
        UUID firstWaypointVersionId = UUID.randomUUID();
        UUID draftWaypointVersionId = UUID.randomUUID();
        UUID secondWaypointId = UUID.randomUUID();
        UUID secondWaypointVersionId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID routeVersionId = UUID.randomUUID();
        UUID geofenceId = UUID.randomUUID();
        UUID geofenceVersionId = UUID.randomUUID();

        insertUnifiedCoordinate(coordinateId);
        insertWaypoint(firstWaypointId, "snapshot-waypoint-a");
        insertWaypointVersion(
                firstWaypointVersionId, firstWaypointId, coordinateId, 1, "PUBLISHED", 100D, 200D);
        insertWaypointVersion(
                draftWaypointVersionId, firstWaypointId, coordinateId, 2, "DRAFT", 110D, 210D);
        insertWaypoint(secondWaypointId, "snapshot-waypoint-b");
        insertWaypointVersion(
                secondWaypointVersionId, secondWaypointId, coordinateId, 1, "PUBLISHED", 300D, 400D);
        insertRoute(routeId, routeVersionId, coordinateId,
                firstWaypointVersionId, secondWaypointVersionId);
        insertGeofence(geofenceId, geofenceVersionId, coordinateId);

        LocationSnapshotService.SnapshotView snapshot = snapshotService.captureSite(
                organizationId, siteId, LocationSnapshotService.ChangeReason.SPATIAL_CHANGE)
                .orElseThrow();

        assertThat(snapshot.changeReason()).isEqualTo(LocationSnapshotService.ChangeReason.SPATIAL_CHANGE);
        assertThat(jdbcTemplate.queryForList("""
                SELECT item_type
                FROM mine_location_runtime_snapshot_item
                WHERE snapshot_id = ? AND item_type LIKE '%_VERSION'
                ORDER BY item_type, item_key
                """, String.class, snapshot.snapshotId()))
                .containsExactly(
                        "GEOFENCE_VERSION", "ROUTE_VERSION",
                        "WAYPOINT_VERSION", "WAYPOINT_VERSION");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM mine_location_runtime_snapshot_item
                WHERE snapshot_id = ? AND item_key = ?
                """, Long.class, snapshot.snapshotId(), "WAYPOINT_VERSION:" + draftWaypointVersionId))
                .isZero();
        assertThat(jdbcTemplate.queryForList("""
                SELECT child ->> 'waypointVersionId'
                FROM mine_location_runtime_snapshot_item item,
                     jsonb_array_elements(item.content -> 'waypoints') child
                WHERE item.snapshot_id = ? AND item.item_key = ?
                ORDER BY (child ->> 'sequenceNumber')::INTEGER
                """, String.class, snapshot.snapshotId(), "ROUTE_VERSION:" + routeVersionId))
                .containsExactly(firstWaypointVersionId.toString(), secondWaypointVersionId.toString());
        assertThat(jdbcTemplate.queryForList("""
                SELECT (vertex ->> 'sequenceNumber')::INTEGER
                FROM mine_location_runtime_snapshot_item item,
                     jsonb_array_elements(item.content -> 'vertices') vertex
                WHERE item.snapshot_id = ? AND item.item_key = ?
                """, Integer.class, snapshot.snapshotId(), "GEOFENCE_VERSION:" + geofenceVersionId))
                .containsExactly(1, 2, 3);
        assertThat(snapshotService.captureSite(
                organizationId, siteId, LocationSnapshotService.ChangeReason.SPATIAL_CHANGE))
                .isEmpty();
    }

    private UUID insertTag(String code, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_location_tag (
                    id, organization_id, site_id, tag_code, status, archived_at
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    CASE WHEN ? = 'ARCHIVED' THEN CURRENT_TIMESTAMP ELSE NULL END
                )
                """, id, organizationId, siteId,
                code + "-" + shortId(id), status, status);
        return id;
    }

    private void insertUnifiedCoordinate(UUID coordinateId) {
        jdbcTemplate.update("""
                INSERT INTO mine_coordinate_system (
                    id, organization_id, site_id, system_code, name,
                    coordinate_type, unit, unified,
                    min_x, max_x, min_y, max_y, min_z, max_z
                ) VALUES (?, ?, ?, 'snapshot-unified', '快照统一坐标',
                          'LOCAL_CARTESIAN', 'METER', TRUE,
                          0, 10000, 0, 10000, -500, 2000)
                """, coordinateId, organizationId, siteId);
    }

    private void insertWaypoint(UUID waypointId, String code) {
        jdbcTemplate.update("""
                INSERT INTO mine_waypoint (id, organization_id, site_id, waypoint_code)
                VALUES (?, ?, ?, ?)
                """, waypointId, organizationId, siteId, code);
    }

    private void insertWaypointVersion(
            UUID versionId,
            UUID waypointId,
            UUID coordinateId,
            int versionNumber,
            String status,
            double x,
            double y
    ) {
        jdbcTemplate.update("""
                INSERT INTO mine_waypoint_version (
                    id, waypoint_id, organization_id, site_id, coordinate_system_id,
                    version_number, name, waypoint_type, x, y, radius_meters,
                    boundary_tolerance_meters, enter_confirm_points, exit_confirm_points,
                    dwell_seconds, allowed_lateness_seconds, idle_flush_seconds,
                    valid_from, status, content_hash, published_at
                ) VALUES (?, ?, ?, ?, ?, ?, '快照途径点', 'CHECK', ?, ?, 20,
                          0.5, 2, 2, 30, 10, 60,
                          '2026-01-01T00:00:00Z', ?,
                          CASE WHEN ? = 'PUBLISHED' THEN ? ELSE NULL END,
                          CASE WHEN ? = 'PUBLISHED' THEN CURRENT_TIMESTAMP ELSE NULL END)
                """, versionId, waypointId, organizationId, siteId, coordinateId,
                versionNumber, x, y, status, status, testHash(versionNumber), status);
    }

    private void insertRoute(
            UUID routeId,
            UUID routeVersionId,
            UUID coordinateId,
            UUID firstWaypointVersionId,
            UUID secondWaypointVersionId
    ) {
        jdbcTemplate.update("""
                INSERT INTO mine_route (id, organization_id, site_id, route_code)
                VALUES (?, ?, ?, 'snapshot-route')
                """, routeId, organizationId, siteId);
        jdbcTemplate.update("""
                INSERT INTO mine_route_version (
                    id, route_id, organization_id, site_id, coordinate_system_id,
                    version_number, name, direction, applicable_vehicle_types,
                    valid_from
                ) VALUES (?, ?, ?, ?, ?, 1, '快照路线', 'BIDIRECTIONAL',
                          '["HAUL_TRUCK"]'::JSONB, '2026-01-01T00:00:00Z')
                """, routeVersionId, routeId, organizationId, siteId, coordinateId);
        jdbcTemplate.update("""
                INSERT INTO mine_route_version_waypoint (
                    route_version_id, waypoint_version_id, organization_id,
                    site_id, sequence_number, stop_required, expected_dwell_seconds
                ) VALUES
                    (?, ?, ?, ?, 2, FALSE, NULL),
                    (?, ?, ?, ?, 1, TRUE, 20)
                """, routeVersionId, secondWaypointVersionId, organizationId, siteId,
                routeVersionId, firstWaypointVersionId, organizationId, siteId);
        jdbcTemplate.update("""
                UPDATE mine_route_version
                SET status = 'PUBLISHED', content_hash = ?, published_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, testHash(11), routeVersionId);
    }

    private void insertGeofence(UUID geofenceId, UUID versionId, UUID coordinateId) {
        jdbcTemplate.update("""
                INSERT INTO mine_geofence (id, organization_id, site_id, geofence_code)
                VALUES (?, ?, ?, 'snapshot-geofence')
                """, geofenceId, organizationId, siteId);
        jdbcTemplate.update("""
                INSERT INTO mine_geofence_version (
                    id, geofence_id, organization_id, site_id, coordinate_system_id,
                    version_number, name, geofence_type,
                    boundary_tolerance_meters, enter_confirm_points, exit_confirm_points,
                    dwell_seconds, allowed_lateness_seconds, idle_flush_seconds,
                    valid_from
                ) VALUES (?, ?, ?, ?, ?, 1, '快照围栏', 'WORK',
                          0.5, 2, 2, 30, 10, 60,
                          '2026-01-01T00:00:00Z')
                """, versionId, geofenceId, organizationId, siteId, coordinateId);
        jdbcTemplate.update("""
                INSERT INTO mine_geofence_vertex (
                    geofence_version_id, organization_id, site_id, sequence_number, x, y
                ) VALUES
                    (?, ?, ?, 3, 100, 500),
                    (?, ?, ?, 1, 100, 100),
                    (?, ?, ?, 2, 500, 100)
                """, versionId, organizationId, siteId,
                versionId, organizationId, siteId,
                versionId, organizationId, siteId);
        jdbcTemplate.update("""
                UPDATE mine_geofence_version
                SET status = 'PUBLISHED', content_hash = ?, published_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, testHash(21), versionId);
    }

    private String testHash(int discriminator) {
        return "sha256:" + Integer.toHexString(discriminator % 16).repeat(64);
    }

    private String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
