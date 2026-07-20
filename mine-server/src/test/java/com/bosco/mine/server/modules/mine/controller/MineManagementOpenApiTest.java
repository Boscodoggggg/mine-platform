package com.bosco.mine.server.modules.mine.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.mine.operation.ShiftController;
import com.bosco.mine.server.modules.mine.operation.OperationController;
import com.bosco.mine.server.modules.mine.operation.WorkOrderController;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftActionRequest;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftDefinitionRequest;
import com.bosco.mine.server.modules.mine.operation.dto.WorkOrderActionRequest;
import com.bosco.mine.server.modules.mine.operation.dto.WorkOrderAssignmentRequest;
import com.bosco.mine.server.modules.mine.operation.dto.WorkOrderRequest;
import com.bosco.mine.server.modules.mine.spatial.SpatialEventController;
import com.bosco.mine.server.modules.mine.spatial.SpatialReplayController;
import com.bosco.mine.server.modules.mine.spatial.SpatialRouteController;
import com.bosco.mine.server.modules.mine.scene.SceneAssetController;
import com.bosco.mine.server.modules.mine.scene.SceneController;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
                "/mine/sites/{siteId}/shift-definitions",
                "/mine/sites/{siteId}/shift-definitions/{definitionId}",
                "/mine/sites/{siteId}/shift-definitions/{definitionId}/archive",
                "/mine/sites/{siteId}/shift-instances",
                "/mine/sites/{siteId}/shift-instances/{shiftId}",
                "/mine/sites/{siteId}/shift-instances/{shiftId}/open",
                "/mine/sites/{siteId}/shift-instances/{shiftId}/close",
                "/mine/sites/{siteId}/shift-instances/{shiftId}/cancel",
                "/mine/sites/{siteId}/work-orders",
                "/mine/sites/{siteId}/work-orders/{workOrderId}",
                "/mine/sites/{siteId}/work-orders/{workOrderId}/assignments",
                "/mine/sites/{siteId}/work-orders/{workOrderId}/release",
                "/mine/sites/{siteId}/work-orders/{workOrderId}/start",
                "/mine/sites/{siteId}/work-orders/{workOrderId}/pause",
                "/mine/sites/{siteId}/work-orders/{workOrderId}/resume",
                "/mine/sites/{siteId}/work-orders/{workOrderId}/complete",
                "/mine/sites/{siteId}/work-orders/{workOrderId}/cancel",
                "/mine/sites/{siteId}/work-orders/{workOrderId}/abort",
                "/mine/sites/{siteId}/operation-candidates",
                "/mine/sites/{siteId}/operation-candidates/{candidateId}/resolve",
                "/mine/sites/{siteId}/operation-records",
                "/mine/sites/{siteId}/operation-records/{recordId}",
                "/mine/sites/{siteId}/operation-records/{recordId}/corrections",
                "/mine/sites/{siteId}/operation-summary",
                "/mine/sites/{siteId}/scene-assets",
                "/mine/sites/{siteId}/scene-assets/{assetId}",
                "/mine/sites/{siteId}/scene-assets/{assetId}/archive",
                "/mine/sites/{siteId}/scene-assets/{assetId}/files/{relativePath}",
                "/mine/sites/{siteId}/scenes",
                "/mine/sites/{siteId}/scenes/{sceneId}",
                "/mine/sites/{siteId}/scenes/{sceneId}/archive",
                "/mine/sites/{siteId}/scenes/{sceneId}/make-default",
                "/mine/sites/{siteId}/scenes/{sceneId}/versions",
                "/mine/sites/{siteId}/scenes/{sceneId}/versions/next-draft",
                "/mine/sites/{siteId}/scenes/{sceneId}/versions/{versionId}",
                "/mine/sites/{siteId}/scenes/{sceneId}/versions/{versionId}/layers",
                "/mine/sites/{siteId}/scenes/{sceneId}/versions/{versionId}/publish",
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
                "SpatialReplayRequest", "SpatialReplayResponse",
                "ShiftDefinitionRequest", "ShiftDefinitionResponse",
                "ShiftInstanceRequest", "ShiftInstanceResponse", "ShiftActionRequest",
                "ShiftCancellationRequest",
                "WorkOrderRequest", "WorkOrderAssignmentRequest",
                "WorkOrderActionRequest", "WorkOrderTerminalActionRequest",
                "WorkOrderResponse", "OperationCandidateResolutionRequest",
                "OperationCandidateResponse", "OperationCorrectionRequest",
                "OperationRecordResponse", "OperationRecordPageResponse",
                "OperationRecordPageItem", "OperationSummaryResponse",
                "OperationWorkOrderProgress", "OperationVehicleUtilization",
                "OperationDriverWorkload", "SceneAssetPage", "SceneAssetResponse",
                "SceneAssetFilePage", "SceneResponse", "ScenePage",
                "SceneVersionRequest", "SceneVersionResponse", "SceneVersionPage",
                "SceneLayerRequest");
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

        Map<String, Object> shifts = map(map(
                paths.get("/mine/sites/{siteId}/shift-instances")).get("get"));
        assertThat(map(namedParameter(shifts, "limit").get("schema")))
                .containsEntry("minimum", 1)
                .containsEntry("maximum", 100)
                .containsEntry("default", 20);
        assertThat(list(map(namedParameter(shifts, "status").get("schema")).get("enum")))
                .containsExactly("PLANNED", "OPEN", "CLOSED", "CANCELLED");
        Map<String, Object> archiveDefinition = map(map(paths.get(
                "/mine/sites/{siteId}/shift-definitions/{definitionId}/archive")).get("post"));
        assertThat(map(map(map(map(archiveDefinition.get("requestBody"))
                .get("content")).get("application/json")).get("schema")).get("$ref"))
                .isEqualTo("#/components/schemas/ShiftActionRequest");
        assertThat(map(archiveDefinition.get("responses"))).doesNotContainKey("422");

        Map<String, Object> workOrders = map(map(
                paths.get("/mine/sites/{siteId}/work-orders")).get("get"));
        assertThat(map(namedParameter(workOrders, "limit").get("schema")))
                .containsEntry("minimum", 1)
                .containsEntry("maximum", 100)
                .containsEntry("default", 20);
        assertThat(list(map(namedParameter(workOrders, "status").get("schema")).get("enum")))
                .containsExactly("DRAFT", "RELEASED", "RUNNING", "PAUSED",
                        "COMPLETED", "CANCELLED", "ABORTED");

        Map<String, Object> operationRecords = map(map(
                paths.get("/mine/sites/{siteId}/operation-records")).get("get"));
        assertThat(map(namedParameter(operationRecords, "pageSize").get("schema")))
                .containsEntry("minimum", 1)
                .containsEntry("maximum", 200)
                .containsEntry("default", 50);
        assertThat(operationRecords.get("description").toString())
                .contains("31 天", "watermark", "营业日期");
        assertThat(namedParameter(operationRecords, "fromDate"))
                .containsEntry("required", true);
        assertThat(namedParameter(operationRecords, "toDate"))
                .containsEntry("required", true);
        assertThat(map(operationRecords.get("responses"))).containsKey("404");
        Map<String, Object> summary = map(map(
                paths.get("/mine/sites/{siteId}/operation-summary")).get("get"));
        assertThat(summary.get("description").toString())
                .contains("31 天", "未知", "零吨", "作废", "任一", "去重开放时长");
        assertThat(map(summary.get("responses"))).containsKey("404");
        Map<String, Object> vehicleUtilization = map(
                schemas.get("OperationVehicleUtilization"));
        assertThat(list(vehicleUtilization.get("required")))
                .contains("shiftOpenSeconds")
                .doesNotContain("assignmentSeconds");
        assertThat(map(vehicleUtilization.get("properties")))
                .containsKey("shiftOpenSeconds")
                .doesNotContainKey("assignmentSeconds");
        Map<String, Object> summaryProperties = map(
                map(schemas.get("OperationSummaryResponse")).get("properties"));
        assertThat(map(summaryProperties.get("completionRatePercent"))
                .get("description").toString())
                .contains("任一作业单计划趟数未知");

        for (String path : List.of(
                "/mine/sites/{siteId}/shift-instances/{shiftId}/open",
                "/mine/sites/{siteId}/shift-instances/{shiftId}/close",
                "/mine/sites/{siteId}/shift-instances/{shiftId}/cancel",
                "/mine/sites/{siteId}/work-orders/{workOrderId}/release",
                "/mine/sites/{siteId}/work-orders/{workOrderId}/start",
                "/mine/sites/{siteId}/work-orders/{workOrderId}/pause",
                "/mine/sites/{siteId}/work-orders/{workOrderId}/resume",
                "/mine/sites/{siteId}/work-orders/{workOrderId}/complete",
                "/mine/sites/{siteId}/work-orders/{workOrderId}/cancel",
                "/mine/sites/{siteId}/work-orders/{workOrderId}/abort")) {
            Map<String, Object> action = map(map(paths.get(path)).get("post"));
            assertThat(list(action.get("parameters")))
                    .anySatisfy(parameter -> assertThat(map(parameter).get("$ref"))
                            .isEqualTo("#/components/parameters/IdempotencyKey"));
        }

        assertThat(list(map(schemas.get("ShiftActionRequest")).get("required")))
                .contains("version");
        assertThat(list(map(schemas.get("WorkOrderActionRequest")).get("required")))
                .contains("version");
        assertThat(list(map(schemas.get("WorkOrderAssignmentRequest")).get("required")))
                .contains("version");
        assertThat(list(map(schemas.get("ShiftCancellationRequest")).get("required")))
                .contains("version", "reason");
        assertThat(list(map(schemas.get("WorkOrderTerminalActionRequest")).get("required")))
                .contains("version", "reason");
        assertThat(list(map(map(map(schemas.get("WorkOrderAssignmentResponse"))
                .get("properties")).get("status")).get("enum")))
                .containsExactly("ACTIVE", "COMPLETED", "CANCELLED");
        assertThat(map(schemas.get("WorkOrderRequest")).get("properties").toString())
                .doesNotContain("status");
        assertThat(root.toString()).contains(
                "MINE-SHIFT-409-003", "MINE-SHIFT-409-004",
                "MINE-WORK-409-004", "MINE-WORK-409-005",
                "MINE-OPERATION-409-001", "MINE-SCOPE-404-002",
                "MINE-SCENE-409-005", "MINE-SCENE-ASSET-404-002");

        Map<String, Object> sceneList = map(map(
                paths.get("/mine/sites/{siteId}/scenes")).get("get"));
        assertThat(map(namedParameter(sceneList, "size").get("schema")))
                .containsEntry("minimum", 1)
                .containsEntry("maximum", 100)
                .containsEntry("default", 20);
        Map<String, Object> versionList = map(map(
                paths.get("/mine/sites/{siteId}/scenes/{sceneId}/versions")).get("get"));
        assertThat(map(namedParameter(versionList, "size").get("schema")))
                .containsEntry("maximum", 100);
        Map<String, Object> assetDetail = map(map(
                paths.get("/mine/sites/{siteId}/scene-assets/{assetId}")).get("get"));
        assertThat(map(namedParameter(assetDetail, "fileSize").get("schema")))
                .containsEntry("maximum", 500);
        Map<String, Object> assetDownload = map(map(paths.get(
                "/mine/sites/{siteId}/scene-assets/{assetId}/files/{relativePath}")).get("get"));
        assertThat(list(assetDownload.get("parameters")))
                .anySatisfy(parameter -> assertThat(map(parameter))
                        .containsEntry("name", "Range")
                        .containsEntry("in", "header"));
        assertThat(map(assetDownload.get("responses"))).containsKeys("200", "206", "304", "416");

        Map<String, Object> replaceLayers = map(map(paths.get(
                "/mine/sites/{siteId}/scenes/{sceneId}/versions/{versionId}/layers")).get("put"));
        Map<String, Object> layerBatchSchema = map(map(map(map(
                replaceLayers.get("requestBody")).get("content")).get("application/json"))
                .get("schema"));
        assertThat(layerBatchSchema).containsEntry("maxItems", 256);
        assertThat(map(schemas.get("SceneLayerResponse")))
                .doesNotContainKey("allOf");
        assertThat(map(map(schemas.get("SceneLayerResponse")).get("properties")))
                .containsKeys("id", "layerCode", "layerType", "options");

        for (String actionPath : List.of(
                "/mine/sites/{siteId}/scene-assets/{assetId}/archive",
                "/mine/sites/{siteId}/scenes/{sceneId}/archive",
                "/mine/sites/{siteId}/scenes/{sceneId}/make-default",
                "/mine/sites/{siteId}/scenes/{sceneId}/versions/{versionId}/publish")) {
            Map<String, Object> action = map(map(paths.get(actionPath)).get("post"));
            assertThat(list(action.get("parameters")))
                    .anySatisfy(parameter -> assertThat(map(parameter).get("$ref"))
                            .isEqualTo("#/components/parameters/IdempotencyKey"));
            assertThat(map(action.get("responses"))).containsKeys("403", "409");
        }
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

    @Test
    void 生产运营控制器必须使用精确权限动作路径和乐观版本() {
        assertRequiredVersion(ShiftDefinitionRequest.class);
        assertRequiredVersion(ShiftActionRequest.class);
        assertRequiredVersion(WorkOrderRequest.class);
        assertRequiredVersion(WorkOrderAssignmentRequest.class);
        assertRequiredVersion(WorkOrderActionRequest.class);

        assertPermission(ShiftController.class, "findDefinitions", "mine:operation:view");
        assertPermission(ShiftController.class, "findDefinition", "mine:operation:view");
        assertPermission(ShiftController.class, "findInstances", "mine:operation:view");
        assertPermission(ShiftController.class, "findInstance", "mine:operation:view");
        assertPermission(ShiftController.class, "createDefinition", "mine:shift:edit");
        assertPermission(ShiftController.class, "updateDefinition", "mine:shift:edit");
        assertPermission(ShiftController.class, "archiveDefinition", "mine:shift:edit");
        assertPermission(ShiftController.class, "createInstance", "mine:shift:edit");
        assertPermission(ShiftController.class, "openInstance", "mine:shift:edit");
        assertPermission(ShiftController.class, "cancelInstance", "mine:shift:edit");
        assertPermission(ShiftController.class, "closeInstance", "mine:shift:close");

        assertPermission(WorkOrderController.class, "findWorkOrders", "mine:operation:view");
        assertPermission(WorkOrderController.class, "findWorkOrder", "mine:operation:view");
        assertPermission(WorkOrderController.class, "createWorkOrder", "mine:work-order:edit");
        assertPermission(WorkOrderController.class, "updateWorkOrder", "mine:work-order:edit");
        assertPermission(WorkOrderController.class, "assignWorkOrder", "mine:work-order:dispatch");
        for (String method : List.of(
                "releaseWorkOrder", "startWorkOrder", "pauseWorkOrder", "resumeWorkOrder",
                "completeWorkOrder", "cancelWorkOrder", "abortWorkOrder")) {
            assertPermission(WorkOrderController.class, method, "mine:work-order:dispatch");
            assertThat(method(WorkOrderController.class, method).getAnnotation(PostMapping.class))
                    .isNotNull();
        }

        assertThat(method(ShiftController.class, "updateDefinition")
                .getAnnotation(PatchMapping.class).value())
                .containsExactly("/shift-definitions/{definitionId}");
        assertThat(method(WorkOrderController.class, "updateWorkOrder")
                .getAnnotation(PatchMapping.class).value())
                .containsExactly("/work-orders/{workOrderId}");
        assertThat(method(ShiftController.class, "openInstance")
                .getAnnotation(PostMapping.class).value()).containsExactly("/shift-instances/{shiftId}/open");
        assertThat(method(ShiftController.class, "closeInstance")
                .getAnnotation(PostMapping.class).value()).containsExactly("/shift-instances/{shiftId}/close");
        assertThat(method(WorkOrderController.class, "releaseWorkOrder")
                .getAnnotation(PostMapping.class).value()).containsExactly("/work-orders/{workOrderId}/release");
        assertThat(method(WorkOrderController.class, "abortWorkOrder")
                .getAnnotation(PostMapping.class).value()).containsExactly("/work-orders/{workOrderId}/abort");

        assertPermission(OperationController.class, "findCandidates", "mine:operation:view");
        assertPermission(OperationController.class, "findRecord", "mine:operation:view");
        assertPermission(OperationController.class, "findOperationRecords", "mine:operation:view");
        assertPermission(OperationController.class, "summarizeOperations", "mine:operation:view");
        assertPermission(OperationController.class, "resolveCandidate", "mine:operation:correct");
        assertPermission(OperationController.class, "addRecord", "mine:operation:correct");
        assertPermission(OperationController.class, "correctRecord", "mine:operation:correct");
    }

    @Test
    void 三维场景控制器必须使用精确权限和固定动作路径() {
        for (String methodName : List.of(
                "findAssets", "findAsset", "readFile")) {
            assertPermission(SceneAssetController.class, methodName, "mine:scene:view");
        }
        assertPermission(SceneAssetController.class, "upload", "mine:scene:asset:upload");
        assertPermission(SceneAssetController.class, "archive", "mine:scene:asset:upload");

        for (String methodName : List.of(
                "findScenes", "findScene", "findVersions", "findVersion")) {
            assertPermission(SceneController.class, methodName, "mine:scene:view");
        }
        for (String methodName : List.of(
                "createScene", "updateScene", "archiveScene", "createDraft",
                "createNextDraft", "updateDraft", "replaceLayers")) {
            assertPermission(SceneController.class, methodName, "mine:scene:edit");
        }
        assertPermission(SceneController.class, "setDefault", "mine:scene:publish");
        assertPermission(SceneController.class, "publish", "mine:scene:publish");

        assertThat(method(SceneController.class, "updateScene")
                .getAnnotation(PatchMapping.class).value())
                .containsExactly("/scenes/{sceneId}");
        assertThat(method(SceneController.class, "setDefault")
                .getAnnotation(PostMapping.class).value())
                .containsExactly("/scenes/{sceneId}/make-default");
        assertThat(method(SceneController.class, "publish")
                .getAnnotation(PostMapping.class).value())
                .containsExactly("/scenes/{sceneId}/versions/{versionId}/publish");
    }

    private void assertPermission(String methodName, String permission) {
        assertThat(method(methodName).getAnnotation(SaCheckPermission.class).value())
                .containsExactly(permission);
    }

    private void assertPermission(
            Class<?> controller, String methodName, String permission
    ) {
        assertThat(method(controller, methodName).getAnnotation(SaCheckPermission.class).value())
                .containsExactly(permission);
    }

    private void assertRequiredVersion(Class<?> requestType) {
        try {
            var accessor = requestType.getDeclaredMethod("version");
            assertThat(accessor.getReturnType()).isEqualTo(Long.class);
            assertThat(accessor.getAnnotation(NotNull.class)).isNotNull();
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(requestType.getSimpleName() + " 缺少 version 字段", exception);
        }
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
