package com.bosco.mine.server.integration;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.MineServerApplication;
import com.bosco.mine.server.modules.mine.dto.CoordinateSystemRequest;
import com.bosco.mine.server.modules.mine.dto.CoordinateSystemResponse;
import com.bosco.mine.server.modules.mine.dto.MineOrganizationResponse;
import com.bosco.mine.server.modules.mine.service.MineAssetCommandService;
import com.bosco.mine.server.modules.mine.service.MineRequestIdentity;
import com.bosco.mine.server.modules.mine.service.MineScopeCommandService;
import com.bosco.mine.server.modules.mine.spatial.SpatialRouteCommandService;
import com.bosco.mine.server.modules.mine.spatial.SpatialRouteQueryService;
import com.bosco.mine.server.modules.mine.spatial.dto.GeofenceVersionRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.GeofenceVersionResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.GeofenceVersionUpdateRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.RouteVersionRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.RouteVersionResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialDetectionPolicy;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialObjectResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialRouteWaypointRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.SpatialVertexRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.WaypointVersionRequest;
import com.bosco.mine.server.modules.mine.spatial.dto.WaypointVersionResponse;
import com.bosco.mine.server.modules.mine.spatial.dto.WaypointVersionUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 PostgreSQL 验证途径点、路线和电子围栏的矿区级管理闭环。
 */
@Testcontainers
@ActiveProfiles("test")
@Import(SpatialRouteManagementIT.IdentityTestConfiguration.class)
@SpringBootTest(
        classes = MineServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class SpatialRouteManagementIT {

    private static final Instant VALID_FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final SpatialDetectionPolicy DEFAULT_POLICY =
            new SpatialDetectionPolicy(0.5D, 2, 2, 30, 10, 60);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("mine")
            .withUsername("mine")
            .withPassword("mine-test-password");

    @Autowired
    private SpatialRouteCommandService commandService;

    @Autowired
    private SpatialRouteQueryService queryService;

    @Autowired
    private MineScopeCommandService scopeCommandService;

    @Autowired
    private MineAssetCommandService assetCommandService;

    @Autowired
    private MutableMineRequestIdentity identity;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID firstUserId;
    private UUID secondUserId;
    private UUID firstSiteId;
    private UUID secondSiteId;
    private CoordinateSystemResponse unifiedCoordinate;
    private CoordinateSystemResponse nonUnifiedCoordinate;

    @BeforeEach
    void 准备两个隔离矿区和双坐标系() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    mine_geofence_vertex,
                    mine_geofence_version,
                    mine_geofence,
                    mine_route_version_waypoint,
                    mine_route_version,
                    mine_route,
                    mine_waypoint_version,
                    mine_waypoint,
                    mine_coordinate_transform_version,
                    mine_coordinate_system,
                    mine_user_scope_grant,
                    mine_site,
                    mine_organization,
                    sys_operation_log
                CASCADE
                """);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE 'spatial-route-it-%'");
        firstUserId = createUser("first");
        secondUserId = createUser("second");

        identity.use(firstUserId);
        MineOrganizationResponse firstOrganization = scopeCommandService.createOrganization(
                "spatial-north", "空间北方集团");
        firstSiteId = scopeCommandService.createSite(
                firstOrganization.id(), "spatial-north-pit", "空间北矿", "Asia/Shanghai", null).id();
        unifiedCoordinate = assetCommandService.createCoordinateSystem(
                firstSiteId,
                new CoordinateSystemRequest(
                        "mine-unified", "矿区统一坐标", "LOCAL_CARTESIAN", "METER",
                        null, true, 0D, 10_000D, 0D, 8_000D, -500D, 2_000D));
        nonUnifiedCoordinate = assetCommandService.createCoordinateSystem(
                firstSiteId,
                new CoordinateSystemRequest(
                        "vendor-local", "厂商本地坐标", "LOCAL_CARTESIAN", "METER",
                        null, false, 0D, 10_000D, 0D, 8_000D, -500D, 2_000D));

        identity.use(secondUserId);
        MineOrganizationResponse secondOrganization = scopeCommandService.createOrganization(
                "spatial-south", "空间南方集团");
        secondSiteId = scopeCommandService.createSite(
                secondOrganization.id(), "spatial-south-pit", "空间南矿", "Asia/Shanghai", null).id();
        assetCommandService.createCoordinateSystem(
                secondSiteId,
                new CoordinateSystemRequest(
                        "south-unified", "南矿统一坐标", "LOCAL_CARTESIAN", "METER",
                        null, true, 0D, 5_000D, 0D, 4_000D, -500D, 1_000D));
        identity.use(firstUserId);
    }

    @Test
    void 途径点草稿必须使用统一坐标并支持乐观修改发布和不可变历史() {
        SpatialObjectResponse waypoint = commandService.createWaypoint(firstSiteId, "load-point-01");

        assertThatThrownBy(() -> commandService.createWaypointVersion(
                firstSiteId, waypoint.id(), waypointRequest(nonUnifiedCoordinate.id(), 1, "错误坐标点", 10D, 20D)))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SPATIAL-422-002"));

        WaypointVersionResponse draft = commandService.createWaypointVersion(
                firstSiteId, waypoint.id(), waypointRequest(unifiedCoordinate.id(), 1, "一号装载点", 100D, 200D));
        WaypointVersionResponse updated = commandService.updateWaypointVersion(
                firstSiteId, waypoint.id(), draft.id(),
                new WaypointVersionUpdateRequest(
                        unifiedCoordinate.id(), "一号主装载点", "LOAD", 101D, 201D, 20D,
                        15D, 0D, 80D, DEFAULT_POLICY, VALID_FROM, null, draft.version()));

        assertThatThrownBy(() -> commandService.updateWaypointVersion(
                firstSiteId, waypoint.id(), draft.id(),
                new WaypointVersionUpdateRequest(
                        unifiedCoordinate.id(), "并发覆盖", "LOAD", 102D, 202D, 20D,
                        15D, 0D, 80D, DEFAULT_POLICY, VALID_FROM, null, draft.version())))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SPATIAL-409-002"));

        WaypointVersionResponse published = commandService.publishWaypointVersion(
                firstSiteId, waypoint.id(), draft.id(), updated.version());
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.contentHash()).matches("sha256:[0-9a-f]{64}");
        assertThat(queryService.findWaypointVersion(firstSiteId, waypoint.id(), draft.id()))
                .isEqualTo(published);
        assertThatThrownBy(() -> commandService.updateWaypointVersion(
                firstSiteId, waypoint.id(), draft.id(),
                new WaypointVersionUpdateRequest(
                        unifiedCoordinate.id(), "发布后篡改", "LOAD", 101D, 201D, 20D,
                        15D, 0D, 80D, DEFAULT_POLICY, VALID_FROM, null, published.version())))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SPATIAL-409-002"));

        commandService.archiveWaypoint(firstSiteId, waypoint.id(), waypoint.version());
        assertThat(queryService.findWaypoints(firstSiteId, "load-point", "ARCHIVED", 1, 20).items())
                .extracting(SpatialObjectResponse::id)
                .containsExactly(waypoint.id());
        assertThat(queryService.findWaypointVersions(firstSiteId, waypoint.id()))
                .extracting(WaypointVersionResponse::contentHash)
                .containsExactly(published.contentHash());
    }

    @Test
    void 路线和围栏发布必须规范化子项并锁定有效引用() {
        WaypointVersionResponse firstWaypoint = publishedWaypoint("route-start", "路线起点", 100D, 100D);
        WaypointVersionResponse secondWaypoint = publishedWaypoint("route-end", "路线终点", 900D, 700D);

        SpatialObjectResponse route = commandService.createRoute(firstSiteId, "haul-route-01");
        RouteVersionResponse routeDraft = commandService.createRouteVersion(
                firstSiteId,
                route.id(),
                new RouteVersionRequest(
                        unifiedCoordinate.id(), 1, "北区运输路线", "BIDIRECTIONAL",
                        List.of("HAUL_TRUCK", "LIGHT_VEHICLE"), VALID_FROM, null,
                        List.of(
                                new SpatialRouteWaypointRequest(2, secondWaypoint.id(), false, null),
                                new SpatialRouteWaypointRequest(1, firstWaypoint.id(), true, 20))));
        RouteVersionResponse publishedRoute = commandService.publishRouteVersion(
                firstSiteId, route.id(), routeDraft.id(), routeDraft.version());

        assertThat(publishedRoute.status()).isEqualTo("PUBLISHED");
        assertThat(publishedRoute.contentHash()).matches("sha256:[0-9a-f]{64}");
        assertThat(publishedRoute.waypoints())
                .extracting(item -> item.sequenceNumber())
                .containsExactly(1, 2);

        SpatialObjectResponse geofence = commandService.createGeofence(firstSiteId, "work-zone-01");
        GeofenceVersionResponse geofenceDraft = commandService.createGeofenceVersion(
                firstSiteId,
                geofence.id(),
                new GeofenceVersionRequest(
                        unifiedCoordinate.id(), 1, "一号作业区", "WORK", 0D, 100D,
                        DEFAULT_POLICY, VALID_FROM, null,
                        List.of(
                                new SpatialVertexRequest(3, 500D, 400D),
                                new SpatialVertexRequest(1, 100D, 100D),
                                new SpatialVertexRequest(4, 100D, 400D),
                                new SpatialVertexRequest(2, 500D, 100D))));
        GeofenceVersionResponse changed = commandService.updateGeofenceVersion(
                firstSiteId,
                geofence.id(),
                geofenceDraft.id(),
                new GeofenceVersionUpdateRequest(
                        unifiedCoordinate.id(), "一号核心作业区", "WORK", 0D, 120D,
                        DEFAULT_POLICY, VALID_FROM, null,
                        List.of(
                                new SpatialVertexRequest(4, 100D, 420D),
                                new SpatialVertexRequest(2, 520D, 100D),
                                new SpatialVertexRequest(1, 100D, 100D),
                                new SpatialVertexRequest(3, 520D, 420D)),
                        geofenceDraft.version()));
        GeofenceVersionResponse publishedGeofence = commandService.publishGeofenceVersion(
                firstSiteId, geofence.id(), geofenceDraft.id(), changed.version());

        assertThat(publishedGeofence.vertices())
                .extracting(item -> item.sequenceNumber())
                .containsExactly(1, 2, 3, 4);
        assertThat(publishedGeofence.contentHash()).matches("sha256:[0-9a-f]{64}");
        assertThat(queryService.findRouteVersion(firstSiteId, route.id(), routeDraft.id()))
                .isEqualTo(publishedRoute);
        assertThat(queryService.findGeofenceVersion(firstSiteId, geofence.id(), geofenceDraft.id()))
                .isEqualTo(publishedGeofence);
    }

    @Test
    void 查询和写入都必须执行服务端矿区作用域并校验分页() {
        SpatialObjectResponse waypoint = commandService.createWaypoint(firstSiteId, "scope-point-01");
        assertThat(queryService.findWaypoints(firstSiteId, "scope", "ACTIVE", 1, 20).total())
                .isEqualTo(1);
        assertThat(queryService.findWaypoint(firstSiteId, waypoint.id())).isEqualTo(waypoint);

        identity.use(secondUserId);
        assertThatThrownBy(() -> queryService.findWaypoint(firstSiteId, waypoint.id()))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCOPE-403-002"));
        assertThatThrownBy(() -> commandService.createRoute(firstSiteId, "cross-scope-route"))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SCOPE-403-002"));

        identity.use(firstUserId);
        assertThatThrownBy(() -> queryService.findGeofences(firstSiteId, null, null, 1, 101))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("MINE-SPATIAL-422-001"));
    }

    private WaypointVersionResponse publishedWaypoint(
            String code, String name, double x, double y
    ) {
        SpatialObjectResponse waypoint = commandService.createWaypoint(firstSiteId, code);
        WaypointVersionResponse draft = commandService.createWaypointVersion(
                firstSiteId, waypoint.id(), waypointRequest(unifiedCoordinate.id(), 1, name, x, y));
        return commandService.publishWaypointVersion(
                firstSiteId, waypoint.id(), draft.id(), draft.version());
    }

    private WaypointVersionRequest waypointRequest(
            UUID coordinateSystemId, int versionNumber, String name, double x, double y
    ) {
        return new WaypointVersionRequest(
                coordinateSystemId, versionNumber, name, "LOAD", x, y, 20D,
                15D, 0D, 80D, DEFAULT_POLICY, VALID_FROM, null);
    }

    private UUID createUser(String suffix) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, username, password_hash, display_name)
                VALUES (?, ?, 'bcrypt:test', ?)
                """, id, "spatial-route-it-" + suffix, "空间路线测试用户" + suffix);
        return id;
    }

    /** 为集成测试提供不依赖 Redis 会话的稳定请求身份。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IdentityTestConfiguration {

        /** 返回可切换的测试身份。 */
        @Bean
        @Primary
        MutableMineRequestIdentity mutableMineRequestIdentity() {
            return new MutableMineRequestIdentity();
        }
    }

    /** 单线程集成测试使用的可切换身份。 */
    static final class MutableMineRequestIdentity implements MineRequestIdentity {
        private UUID userId;

        /** 切换当前测试用户。 */
        void use(UUID value) {
            userId = value;
        }

        /** 返回当前测试用户。 */
        @Override
        public UUID currentUserId() {
            return userId;
        }
    }
}
