package com.bosco.mine.server.modules.device.controller;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验设备遥测 OpenAPI 文件可解析且包含已发布接口的关键边界。
 */
class DeviceTelemetryOpenApiTest {

    @Test
    void 应发布最新值与历史查询契约() throws IOException {
        Path contract = contractPath();
        Map<String, Object> root;
        try (java.io.Reader reader = Files.newBufferedReader(contract)) {
            root = map(new Yaml().load(reader));
        }

        assertThat(root.get("openapi")).isEqualTo("3.1.0");
        Map<String, Object> paths = map(root.get("paths"));
        assertThat(paths).containsKeys(
                "/devices/{deviceId}/latest",
                "/devices/{deviceId}/telemetry"
        );
        Map<String, Object> latestGet = map(map(
                paths.get("/devices/{deviceId}/latest")
        ).get("get"));
        assertThat(latestGet.get("operationId")).isEqualTo("getDeviceLatestTelemetry");
        Map<String, Object> historyGet = map(map(
                paths.get("/devices/{deviceId}/telemetry")
        ).get("get"));
        assertThat(historyGet.get("operationId")).isEqualTo("getDeviceTelemetryHistory");
        assertThat(map(historyGet.get("responses"))).containsKeys(
                "200", "400", "401", "403", "404", "503"
        );
        assertThat(list(historyGet.get("parameters")))
                .anySatisfy(parameter -> assertThat(map(parameter))
                        .containsEntry("name", "watermark"))
                .anySatisfy(parameter -> assertThat(map(parameter))
                        .containsEntry("name", "rollupWatermark"));
        Map<String, Object> schemas = map(map(root.get("components")).get("schemas"));
        assertThat(map(map(schemas.get("TelemetryHistory")).get("properties")))
                .containsKeys("watermark", "rollupWatermark");
        assertThat(map(map(schemas.get("TelemetryHistoryPoint")).get("properties")))
                .containsKey("source");
        Map<String, Object> securitySchemes = map(map(
                root.get("components")
        ).get("securitySchemes"));
        assertThat(map(securitySchemes.get("MineToken")))
                .containsEntry("name", "X-Mine-Token")
                .containsEntry("in", "header");
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
    private java.util.List<Object> list(Object value) {
        assertThat(value).isInstanceOf(java.util.List.class);
        return (java.util.List<Object>) value;
    }
}
