package com.bosco.mine.server.modules.strategy.controller;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验策略 OpenAPI 已发布数据保留契约、统计上限和不可用响应。
 */
class StrategyConfigurationOpenApiTest {

    @Test
    void 应发布保留预览执行历史和类型化策略契约() throws IOException {
        Map<String, Object> root;
        try (java.io.Reader reader = Files.newBufferedReader(contractPath())) {
            root = map(new Yaml().load(reader));
        }

        Map<String, Object> paths = map(root.get("paths"));
        Map<String, Object> preview = map(map(paths.get("/retention-previews")).get("post"));
        assertThat(preview.get("operationId")).isEqualTo("previewRetentionImpact");
        assertThat(map(preview.get("responses")))
                .containsKeys("200", "400", "401", "403", "422", "503");

        Map<String, Object> executions = map(
                map(paths.get("/retention-executions")).get("get"));
        assertThat(executions.get("operationId")).isEqualTo("listRetentionExecutions");
        assertThat(list(executions.get("parameters")))
                .anySatisfy(parameter -> assertThat(map(map(parameter).get("schema")))
                        .containsEntry("maximum", 100));
        assertThat(map(executions.get("responses")))
                .containsKeys("200", "400", "401", "403", "503");

        Map<String, Object> schemas = map(map(root.get("components")).get("schemas"));
        assertThat(list(map(schemas.get("StrategyType")).get("enum")))
                .contains("RETENTION");
        assertThat(map(map(schemas.get("RetentionPolicy")).get("properties")))
                .containsKeys(
                        "enabled", "retentionClass",
                        "rawTelemetryDays", "qualityEvidenceDays");
        assertThat(map(schemas.get("RetentionPolicy")))
                .containsEntry("additionalProperties", false);
        assertThat(list(map(schemas.get("RetentionExecutionStatus")).get("enum")))
                .containsExactly("RUNNING", "SUCCEEDED", "PARTIAL", "FAILED");
        assertThat(list(map(schemas.get("RetentionDatasetImpact")).get("required")))
                .containsExactly("eligibleRows", "atLeast");
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
