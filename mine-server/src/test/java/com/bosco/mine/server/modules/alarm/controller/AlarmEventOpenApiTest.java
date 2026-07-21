package com.bosco.mine.server.modules.alarm.controller;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验告警中心 OpenAPI 文件可解析且处置契约完整。
 */
class AlarmEventOpenApiTest {

    @Test
    void 应发布查询详情确认和关闭契约() throws IOException {
        Map<String, Object> root;
        try (java.io.Reader reader = Files.newBufferedReader(contractPath())) {
            root = map(new Yaml().load(reader));
        }

        assertThat(root.get("openapi")).isEqualTo("3.1.0");
        Map<String, Object> paths = map(root.get("paths"));
        assertThat(paths).containsKeys(
                "/alarms", "/alarms/{alarmId}",
                "/alarms/{alarmId}/acknowledgements", "/alarms/{alarmId}/close",
                "/mine/sites/{siteId}/alarms");
        Map<String, Object> acknowledgement = map(map(
                paths.get("/alarms/{alarmId}/acknowledgements")).get("post"));
        assertThat(list(acknowledgement.get("parameters")))
                .anySatisfy(parameter -> assertThat(map(parameter).get("$ref"))
                        .isEqualTo("#/components/parameters/IdempotencyKey"));
        Map<String, Object> listAlarms = map(map(paths.get("/alarms")).get("get"));
        assertThat(list(listAlarms.get("parameters")))
                .extracting(parameter -> map(parameter).get("name"))
                .contains("closed", "deviceKeyword")
                .doesNotContain("siteId");
        Map<String, Object> schemas = map(map(root.get("components")).get("schemas"));
        assertThat(list(map(schemas.get("AlarmStatus")).get("enum")))
                .containsExactly(
                        "ACTIVE_UNACKNOWLEDGED", "ACTIVE_ACKNOWLEDGED",
                        "RECOVERED_UNACKNOWLEDGED", "RECOVERED_ACKNOWLEDGED", "CLOSED");
        assertThat(map(map(schemas.get("AlarmDispositionRequest")).get("properties")))
                .containsKeys("note", "version");
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
