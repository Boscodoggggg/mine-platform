package com.bosco.mine.server.modules.notification.controller;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验内置通知 Provider 与数据库渠道配置的 OpenAPI 安全边界。
 */
class NotificationChannelOpenApiTest {

    @Test
    void 应发布Provider目录和只写凭据渠道契约() throws IOException {
        Map<String, Object> root;
        try (java.io.Reader reader = Files.newBufferedReader(contractPath())) {
            root = map(new Yaml().load(reader));
        }

        Map<String, Object> paths = map(root.get("paths"));
        assertThat(paths).containsKeys(
                "/notifications/providers", "/notifications/channels",
                "/notifications/channels/{channelId}");
        Map<String, Object> schemas = map(map(root.get("components")).get("schemas"));
        assertThat(list(map(schemas.get("NotificationProviderCode")).get("enum")))
                .containsExactly(
                        "smtp", "wecom-robot", "dingtalk-robot", "feishu-robot",
                        "generic-webhook", "aliyun-sms", "aliyun-voice");
        Map<String, Object> requestProperties = map(map(
                schemas.get("NotificationChannelRequest")).get("properties"));
        assertThat(map(requestProperties.get("secrets")))
                .containsEntry("writeOnly", true);
        Map<String, Object> responseProperties = map(map(
                schemas.get("NotificationChannelResponse")).get("properties"));
        assertThat(responseProperties)
                .containsKey("secretConfigured")
                .doesNotContainKeys("secrets", "secretCiphertext");
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
