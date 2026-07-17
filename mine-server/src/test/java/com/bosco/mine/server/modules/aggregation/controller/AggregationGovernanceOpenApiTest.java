package com.bosco.mine.server.modules.aggregation.controller;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验聚合治理 OpenAPI 包含显式重算和运行历史的稳定边界。
 */
class AggregationGovernanceOpenApiTest {

    @Test
    void 应发布重算登记请求历史和执行历史契约() throws IOException {
        Map<String, Object> root;
        try (java.io.Reader reader = Files.newBufferedReader(contractPath())) {
            root = map(new Yaml().load(reader));
        }

        Map<String, Object> paths = map(root.get("paths"));
        Map<String, Object> recomputations = map(paths.get("/aggregation-recomputations"));
        assertThat(map(recomputations.get("post")).get("operationId"))
                .isEqualTo("createAggregationRecomputation");
        assertThat(map(map(recomputations.get("post")).get("responses"))).containsKey("202");
        assertThat(map(recomputations.get("get")).get("operationId"))
                .isEqualTo("listAggregationRecomputations");
        assertThat(map(map(paths.get("/aggregation-executions")).get("get")).get("operationId"))
                .isEqualTo("listAggregationExecutions");

        Map<String, Object> schemas = map(map(root.get("components")).get("schemas"));
        assertThat(schemas).containsKeys(
                "AggregationRecomputeRequest", "AggregationRecompute", "AggregationExecution");
        assertThat(list(map(schemas.get("AggregationRecomputeRequest")).get("required")))
                .containsExactly("deviceId", "propertyId", "bucket", "from", "to", "reason");
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
