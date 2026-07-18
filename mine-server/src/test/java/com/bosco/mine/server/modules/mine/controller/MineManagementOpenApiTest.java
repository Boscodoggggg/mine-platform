package com.bosco.mine.server.modules.mine.controller;

import org.junit.jupiter.api.Test;
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
                "CurrentLocationResponse", "LocationTrajectoryResponse");
        assertThat(list(map(schemas.get("LocationSubjectType")).get("enum")))
                .containsExactly("TAG", "VEHICLE", "PERSON");
        assertThat(map(map(schemas.get("LocationTrajectoryResponse")).get("properties")))
                .containsKeys("watermark", "nextCursor", "sampled", "sourcePointCount", "items");
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
