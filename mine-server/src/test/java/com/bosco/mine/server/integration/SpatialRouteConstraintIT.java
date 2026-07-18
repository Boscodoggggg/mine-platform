package com.bosco.mine.server.integration;

import com.bosco.mine.server.MineServerApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 校验路线、途径点和电子围栏版本模型的数据库不变量。
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class SpatialRouteConstraintIT {

    private static final List<String> SPATIAL_TABLES = List.of(
            "mine_waypoint",
            "mine_waypoint_version",
            "mine_route",
            "mine_route_version",
            "mine_route_version_waypoint",
            "mine_geofence",
            "mine_geofence_version",
            "mine_geofence_vertex");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID organizationId;
    private UUID siteId;
    private UUID unifiedCoordinateSystemId;

    @BeforeEach
    void 准备矿区与统一坐标系() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    mine_route_version_waypoint,
                    mine_geofence_vertex,
                    mine_route_version,
                    mine_waypoint_version,
                    mine_geofence_version,
                    mine_route,
                    mine_waypoint,
                    mine_geofence,
                    mine_coordinate_transform_version,
                    mine_coordinate_system,
                    mine_site,
                    mine_organization
                CASCADE
                """);
        organizationId = UUID.randomUUID();
        siteId = UUID.randomUUID();
        unifiedCoordinateSystemId = UUID.randomUUID();
        insertSite(organizationId, siteId, "spatial-main");
        insertCoordinateSystem(
                organizationId, siteId, unifiedCoordinateSystemId, "spatial-local", true);
    }

    @Test
    void 八张空间控制面表及每个字段都必须有中文注释() {
        for (String table : SPATIAL_TABLES) {
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT obj_description(to_regclass(?), 'pg_class')
                    """, String.class, "public." + table))
                    .as("表 %s 的中文注释", table)
                    .isNotBlank();
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.columns column_info
                    WHERE column_info.table_schema = 'public'
                      AND column_info.table_name = ?
                      AND col_description(
                            to_regclass('public.' || column_info.table_name),
                            column_info.ordinal_position
                          ) IS NULL
                    """, Integer.class, table))
                    .as("表 %s 缺少注释的字段数", table)
                    .isZero();
        }
    }

    @Test
    void 稳定编码和坐标系引用必须限制在同一矿区() {
        UUID waypointId = insertWaypoint("load-a");
        assertThatThrownBy(() -> insertWaypoint("load-a"))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID otherOrganizationId = UUID.randomUUID();
        UUID otherSiteId = UUID.randomUUID();
        UUID otherCoordinateSystemId = UUID.randomUUID();
        insertSite(otherOrganizationId, otherSiteId, "spatial-other");
        insertCoordinateSystem(
                otherOrganizationId, otherSiteId, otherCoordinateSystemId,
                "spatial-other-local", true);

        assertThatThrownBy(() -> insertWaypointVersion(
                waypointId, otherCoordinateSystemId, 1, "2026-07-18T00:00:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 围栏发布必须拒绝自相交且发布版本不可修改并禁止有效期重叠() {
        UUID invalidGeofenceId = insertGeofence("invalid-zone");
        UUID invalidVersionId = insertGeofenceVersion(
                invalidGeofenceId, 1, "2026-07-18T00:00:00Z");
        insertVertices(invalidVersionId, new double[][]{
                {0D, 0D}, {10D, 10D}, {0D, 10D}, {10D, 0D}
        });

        assertThatThrownBy(() -> publish("mine_geofence_version", invalidVersionId))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID geofenceId = insertGeofence("crusher-zone");
        UUID firstVersionId = insertGeofenceVersion(
                geofenceId, 1, "2026-07-18T00:00:00Z");
        insertVertices(firstVersionId, square(0D));
        publish("mine_geofence_version", firstVersionId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE mine_geofence_version SET name = '禁止原地修改' WHERE id = ?
                """, firstVersionId))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID overlappingVersionId = insertGeofenceVersion(
                geofenceId, 2, "2026-07-18T01:00:00Z");
        insertVertices(overlappingVersionId, square(20D));
        assertThatThrownBy(() -> publish("mine_geofence_version", overlappingVersionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 路线发布必须引用至少两个已发布途径点并保持唯一顺序() {
        UUID waypointA = insertWaypoint("route-a");
        UUID waypointB = insertWaypoint("route-b");
        UUID waypointVersionA = insertWaypointVersion(
                waypointA, unifiedCoordinateSystemId, 1, "2026-07-18T00:00:00Z");
        UUID waypointVersionB = insertWaypointVersion(
                waypointB, unifiedCoordinateSystemId, 1, "2026-07-18T00:00:00Z");
        publish("mine_waypoint_version", waypointVersionA);
        publish("mine_waypoint_version", waypointVersionB);

        UUID routeId = insertRoute("haul-main");
        UUID routeVersionId = insertRouteVersion(routeId);
        insertRouteWaypoint(routeVersionId, waypointVersionA, 1);

        assertThatThrownBy(() -> publish("mine_route_version", routeVersionId))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertRouteWaypoint(routeVersionId, waypointVersionB, 2);
        assertThatThrownBy(() -> insertRouteWaypoint(routeVersionId, waypointVersionA, 2))
                .isInstanceOf(DataIntegrityViolationException.class);

        publish("mine_route_version", routeVersionId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM mine_route_version WHERE id = ?
                """, String.class, routeVersionId)).isEqualTo("PUBLISHED");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                DELETE FROM mine_route_version_waypoint
                WHERE route_version_id = ? AND sequence_number = 1
                """, routeVersionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertSite(UUID organization, UUID site, String codePrefix) {
        jdbcTemplate.update("""
                INSERT INTO mine_organization (id, organization_code, name)
                VALUES (?, ?, '空间模型测试组织')
                """, organization, codePrefix + "-org-" + shortId(organization));
        jdbcTemplate.update("""
                INSERT INTO mine_site (id, organization_id, site_code, name, timezone)
                VALUES (?, ?, ?, '空间模型测试矿区', 'Asia/Shanghai')
                """, site, organization, codePrefix + "-site-" + shortId(site));
    }

    private void insertCoordinateSystem(
            UUID organization,
            UUID site,
            UUID coordinateSystem,
            String code,
            boolean unified
    ) {
        jdbcTemplate.update("""
                INSERT INTO mine_coordinate_system (
                    id, organization_id, site_id, system_code, name,
                    coordinate_type, unit, unified,
                    min_x, max_x, min_y, max_y, min_z, max_z
                ) VALUES (?, ?, ?, ?, '测试统一坐标系',
                          'LOCAL_CARTESIAN', 'METER', ?,
                          -1000, 1000, -1000, 1000, -100, 100)
                """, coordinateSystem, organization, site, code, unified);
    }

    private UUID insertWaypoint(String code) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_waypoint (id, organization_id, site_id, waypoint_code)
                VALUES (?, ?, ?, ?)
                """, id, organizationId, siteId, code);
        return id;
    }

    private UUID insertWaypointVersion(
            UUID waypointId,
            UUID coordinateSystemId,
            int versionNumber,
            String validFrom
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_waypoint_version (
                    id, waypoint_id, organization_id, site_id, coordinate_system_id,
                    version_number, name, waypoint_type, x, y, z, radius_meters,
                    boundary_tolerance_meters, enter_confirm_points, exit_confirm_points,
                    dwell_seconds, allowed_lateness_seconds, idle_flush_seconds, valid_from
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, '测试途径点', 'CHECK', 10, 20, 5, 8,
                    1, 2, 2, 60, 5, 15, ?::timestamptz
                )
                """, id, waypointId, organizationId, siteId, coordinateSystemId,
                versionNumber, validFrom);
        return id;
    }

    private UUID insertGeofence(String code) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_geofence (id, organization_id, site_id, geofence_code)
                VALUES (?, ?, ?, ?)
                """, id, organizationId, siteId, code);
        return id;
    }

    private UUID insertGeofenceVersion(UUID geofenceId, int versionNumber, String validFrom) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_geofence_version (
                    id, geofence_id, organization_id, site_id, coordinate_system_id,
                    version_number, name, geofence_type, min_z, max_z,
                    boundary_tolerance_meters, enter_confirm_points, exit_confirm_points,
                    dwell_seconds, allowed_lateness_seconds, idle_flush_seconds, valid_from
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, '测试围栏', 'WORK', -10, 50,
                    1, 2, 2, 60, 5, 15, ?::timestamptz
                )
                """, id, geofenceId, organizationId, siteId,
                unifiedCoordinateSystemId, versionNumber, validFrom);
        return id;
    }

    private void insertVertices(UUID versionId, double[][] points) {
        for (int index = 0; index < points.length; index++) {
            jdbcTemplate.update("""
                    INSERT INTO mine_geofence_vertex (
                        geofence_version_id, organization_id, site_id,
                        sequence_number, x, y
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, versionId, organizationId, siteId,
                    index + 1, points[index][0], points[index][1]);
        }
    }

    private UUID insertRoute(String code) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_route (id, organization_id, site_id, route_code)
                VALUES (?, ?, ?, ?)
                """, id, organizationId, siteId, code);
        return id;
    }

    private UUID insertRouteVersion(UUID routeId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO mine_route_version (
                    id, route_id, organization_id, site_id, coordinate_system_id,
                    version_number, name, direction, applicable_vehicle_types, valid_from
                ) VALUES (
                    ?, ?, ?, ?, ?, 1, '测试运输路线', 'BIDIRECTIONAL',
                    '["HAUL_TRUCK"]'::jsonb, '2026-07-18T00:00:00Z'
                )
                """, id, routeId, organizationId, siteId, unifiedCoordinateSystemId);
        return id;
    }

    private void insertRouteWaypoint(UUID routeVersionId, UUID waypointVersionId, int sequence) {
        jdbcTemplate.update("""
                INSERT INTO mine_route_version_waypoint (
                    route_version_id, waypoint_version_id, organization_id, site_id,
                    sequence_number
                ) VALUES (?, ?, ?, ?, ?)
                """, routeVersionId, waypointVersionId, organizationId, siteId, sequence);
    }

    private void publish(String table, UUID id) {
        if (!List.of(
                "mine_waypoint_version", "mine_route_version", "mine_geofence_version")
                .contains(table)) {
            throw new IllegalArgumentException("不支持的空间版本表");
        }
        jdbcTemplate.update("UPDATE " + table + " SET "
                + "status = 'PUBLISHED', content_hash = ?, "
                + "published_at = CURRENT_TIMESTAMP WHERE id = ?",
                "sha256:" + "1".repeat(64), id);
    }

    private double[][] square(double offset) {
        return new double[][]{
                {offset, offset},
                {offset + 10D, offset},
                {offset + 10D, offset + 10D},
                {offset, offset + 10D}
        };
    }

    private String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
