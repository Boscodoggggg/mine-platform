package com.bosco.mine.server.modules.mine.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.mine.spatial.SpatialEventController;
import com.bosco.mine.server.modules.mine.spatial.SpatialReplayController;
import com.bosco.mine.server.modules.mine.spatial.SpatialRouteController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验矿区档案、资产、人员、换绑和定位 OpenAPI 的产品边界。
 */
class MineManagementOpenApiTest {

    @Test
    void 应发布矿区管理全链路契约和有界查询参数() throws IOException {
        Map<String, Object> root;
        try (java.io.Reader reader = Files.newBufferedReader(contractPath())) {
            root = map(new Yaml().load(reader));
        }

        Map<String, Object> paths = map(root.get("paths"));
        assertThat(paths).containsKeys(
                "/mine/context",
                "/mine/organizations",
                "/mine/organizations/{organizationId}/sites",
                "/mine/scope-grants",
                "/mine/sites/{siteId}/vehicles",
                "/mine/sites/{siteId}/location-tags",
                "/mine/sites/{siteId}/coordinate-systems",
                "/mine/sites/{siteId}/coordinate-transforms/{transformId}/publish",
                "/mine/sites/{siteId}/waypoints",
                "/mine/sites/{siteId}/waypoints/{waypointId}",
                "/mine/sites/{siteId}/waypoints/{waypointId}/archive",
                "/mine/sites/{siteId}/waypoints/{waypointId}/versions",
                "/mine/sites/{siteId}/waypoints/{waypointId}/versions/{versionId}",
                "/mine/sites/{siteId}/waypoints/{waypointId}/versions/{versionId}/publish",
                "/mine/sites/{siteId}/routes",
                "/mine/sites/{siteId}/routes/{routeId}",
                "/mine/sites/{siteId}/routes/{routeId}/archive",
                "/mine/sites/{siteId}/routes/{routeId}/versions",
                "/mine/sites/{siteId}/routes/{routeId}/versions/{versionId}",
                "/mine/sites/{siteId}/routes/{routeId}/versions/{versionId}/publish",
                "/mine/sites/{siteId}/geofences",
                "/mine/sites/{siteId}/geofences/{geofenceId}",
                "/mine/sites/{siteId}/geofences/{geofenceId}/archive",
                "/mine/sites/{siteId}/geofences/{geofenceId}/versions",
                "/mine/sites/{siteId}/geofences/{geofenceId}/versions/{versionId}",
                "/mine/sites/{siteId}/geofences/{geofenceId}/versions/{versionId}/publish",
                "/mine/sites/{siteId}/spatial-regions/current",
                "/mine/sites/{siteId}/spatial-events",
                "/mine/sites/{siteId}/spatial-events/{eventId}",
                "/mine/sites/{siteId}/spatial-events/replays",
                "/mine/sites/{siteId}/people",
                "/mine/sites/{siteId}/people/{personId}/sensitive",
                "/mine/sites/{siteId}/bindings/preview",
                "/mine/sites/{siteId}/bindings/switch",
                "/mine/sites/{siteId}/locations/current",
                "/mine/sites/{siteId}/locations/trajectory"
        );

        Map<String, Object> bindingSwitch = map(map(
                paths.get("/mine/sites/{siteId}/bindings/switch")).get("post"));
        assertThat(list(bindingSwitch.get("parameters")))
                .anySatisfy(parameter -> assertThat(map(parameter).get("$ref"))
                        .isEqualTo("#/components/parameters/IdempotencyKey"));

        Map<String, Object> trajectory = map(map(
                paths.get("/mine/sites/{siteId}/locations/trajectory")).get("get"));
        Map<String, Object> maxPoints = namedParameter(trajectory, "maxPoints");
        assertThat(map(maxPoints.get("schema")))
                .containsEntry("minimum", 2)
                .containsEntry("maximum", 5000)
                .containsEntry("default", 5000);
        assertThat(namedParameter(trajectory, "to").get("description").toString())
                .contains("24 小时");

        Map<String, Object> schemas = map(map(root.get("components")).get("schemas"));
        assertThat(schemas).containsKeys(
                "MineContextResponse", "MineOrganizationPage", "MineSitePage",
                "VehiclePage", "LocationTagPage", "CoordinateSystemPage",
                "CoordinateTransformPage", "PersonPage", "BindingPreviewResponse",
                "CurrentLocationResponse", "LocationTrajectoryResponse",
                "SpatialObjectRequest", "SpatialObjectResponse", "SpatialObjectPage",
                "SpatialDetectionPolicy", "WaypointVersionRequest",
                "WaypointVersionUpdateRequest", "WaypointVersionResponse",
                "RouteVersionRequest", "RouteVersionUpdateRequest", "RouteVersionResponse",
                "GeofenceVersionRequest", "GeofenceVersionUpdateRequest",
                "GeofenceVersionResponse", "CurrentSpatialRegionResponse",
                "CurrentSpatialRegionItem", "SpatialEventPageResponse",
                "SpatialEventDetailResponse", "SpatialEventEvidence",
                "SpatialReplayRequest", "SpatialReplayResponse");
        assertThat(list(map(schemas.get("LocationSubjectType")).get("enum")))
                .containsExactly("TAG", "VEHICLE", "PERSON");
        assertThat(map(map(schemas.get("LocationTrajectoryResponse")).get("properties")))
                .containsKeys("watermark", "nextCursor", "sampled", "sourcePointCount", "items");

        Map<String, Object> routeRequest = map(schemas.get("RouteVersionRequest"));
        Map<String, Object> routeWaypoints = map(
                map(routeRequest.get("properties")).get("waypoints"));
        assertThat(routeWaypoints).containsEntry("minItems", 2).containsEntry("maxItems", 10000);
        Map<String, Object> geofenceRequest = map(schemas.get("GeofenceVersionRequest"));
        Map<String, Object> vertices = map(
                map(geofenceRequest.get("properties")).get("vertices"));
        assertThat(vertices).containsEntry("minItems", 3).containsEntry("maxItems", 10000);
        assertThat(list(map(schemas.get("SpatialVersionStatus")).get("enum")))
                .containsExactly("DRAFT", "PUBLISHED");

        Map<String, Object> eventQuery = map(map(
                paths.get("/mine/sites/{siteId}/spatial-events")).get("get"));
        assertThat(map(namedParameter(eventQuery, "pageSize").get("schema")))
                .containsEntry("minimum", 1)
                .containsEntry("maximum", 200)
                .containsEntry("default", 100);
        assertThat(eventQuery.get("description").toString()).contains("31 天", "watermark");
        Map<String, Object> replay = map(map(
                paths.get("/mine/sites/{siteId}/spatial-events/replays")).get("post"));
        assertThat(list(replay.get("parameters")))
                .anySatisfy(parameter -> assertThat(map(parameter).get("$ref"))
                        .isEqualTo("#/components/parameters/IdempotencyKey"));
    }

    @Test
    void 空间管理控制器必须使用精确权限和固定版本动作路径() {
        assertPermission("findCurrentSpatialRegions", "mine:spatial:view");
        assertPermission("findWaypoints", "mine:spatial:view");
        assertPermission("findWaypoint", "mine:spatial:view");
        assertPermission("findWaypointVersions", "mine:spatial:view");
        assertPermission("findWaypointVersion", "mine:spatial:view");
        assertPermission("createWaypoint", "mine:spatial:edit");
        assertPermission("archiveWaypoint", "mine:spatial:edit");
        assertPermission("createWaypointVersion", "mine:spatial:edit");
        assertPermission("updateWaypointVersion", "mine:spatial:edit");
        assertPermission("publishWaypointVersion", "mine:spatial:publish");

        assertPermission("findRoutes", "mine:spatial:view");
        assertPermission("findRoute", "mine:spatial:view");
        assertPermission("findRouteVersions", "mine:spatial:view");
        assertPermission("findRouteVersion", "mine:spatial:view");
        assertPermission("createRoute", "mine:spatial:edit");
        assertPermission("archiveRoute", "mine:spatial:edit");
        assertPermission("createRouteVersion", "mine:spatial:edit");
        assertPermission("updateRouteVersion", "mine:spatial:edit");
        assertPermission("publishRouteVersion", "mine:spatial:publish");

        assertPermission("findGeofences", "mine:spatial:view");
        assertPermission("findGeofence", "mine:spatial:view");
        assertPermission("findGeofenceVersions", "mine:spatial:view");
        assertPermission("findGeofenceVersion", "mine:spatial:view");
        assertPermission("createGeofence", "mine:spatial:edit");
        assertPermission("archiveGeofence", "mine:spatial:edit");
        assertPermission("createGeofenceVersion", "mine:spatial:edit");
        assertPermission("updateGeofenceVersion", "mine:spatial:edit");
        assertPermission("publishGeofenceVersion", "mine:spatial:publish");

        assertThat(method("findWaypoints").getAnnotation(GetMapping.class).value())
                .containsExactly("/waypoints");
        assertThat(method("findCurrentSpatialRegions").getAnnotation(GetMapping.class).value())
                .containsExactly("/spatial-regions/current");
        assertThat(method("createWaypointVersion").getAnnotation(PostMapping.class).value())
                .containsExactly("/waypoints/{waypointId}/versions");
        assertThat(method("publishWaypointVersion").getAnnotation(PostMapping.class).value())
                .containsExactly("/waypoints/{waypointId}/versions/{versionId}/publish");
        assertThat(method("publishRouteVersion").getAnnotation(PostMapping.class).value())
                .containsExactly("/routes/{routeId}/versions/{versionId}/publish");
        assertThat(method("publishGeofenceVersion").getAnnotation(PostMapping.class).value())
                .containsExactly("/geofences/{geofenceId}/versions/{versionId}/publish");
    }

    @Test
    void 空间事件与重放控制器必须使用独立最小权限() {
        assertThat(method(SpatialEventController.class, "findEvents")
                .getAnnotation(SaCheckPermission.class).value())
                .containsExactly("mine:spatial:event:view");
        assertThat(method(SpatialEventController.class, "findEvent")
                .getAnnotation(SaCheckPermission.class).value())
                .containsExactly("mine:spatial:event:view");
        assertThat(method(SpatialReplayController.class, "create")
                .getAnnotation(SaCheckPermission.class).value())
                .containsExactly("mine:spatial:replay");
    }

    private void assertPermission(String methodName, String permission) {
        assertThat(method(methodName).getAnnotation(SaCheckPermission.class).value())
                .containsExactly(permission);
    }

    private java.lang.reflect.Method method(String name) {
        return method(SpatialRouteController.class, name);
    }

    private java.lang.reflect.Method method(Class<?> controller, String name) {
        return java.util.Arrays.stream(controller.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private Map<String, Object> namedParameter(Map<String, Object> operation, String name) {
        return list(operation.get("parameters")).stream()
                .map(this::map)
                .filter(parameter -> name.equals(parameter.get("name")))
                .findFirst()
                .orElseThrow();
    }

    private Path contractPath() {
        Path current = Path.of("").toAbsolutePath().normalize();
        Path fromRoot = current.resolve("docs/openapi/mine-api.yaml");
        Path fromModule = current.resolveSibling("docs/openapi/mine-api.yaml");
        Path contract = Files.isRegularFile(fromRoot) ? fromRoot : fromModule;
        assertThat(contract).isRegularFile();
        return contract;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }
}
