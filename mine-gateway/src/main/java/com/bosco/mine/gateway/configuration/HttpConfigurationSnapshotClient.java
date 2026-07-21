package com.bosco.mine.gateway.configuration;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 使用 JDK HTTP 客户端和外部服务令牌拉取有界配置快照。
 */
public final class HttpConfigurationSnapshotClient implements ConfigurationSnapshotClient {

    private static final String TOKEN_HEADER = "X-Mine-Service-Token";

    private final GatewayConfigurationDistributionProperties properties;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建内部配置快照客户端。
     *
     * @param properties 配置分发参数
     */
    public HttpConfigurationSnapshotClient(GatewayConfigurationDistributionProperties properties) {
        this.properties = Objects.requireNonNull(properties, "配置分发参数不能为空");
        Duration timeout = properties.resolvedRequestTimeout();
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * 拉取并限制指定发布的完整快照响应大小。
     *
     * @param releaseId 配置发布标识
     * @return 完整配置快照
     */
    @Override
    public ConfigurationSnapshot fetch(UUID releaseId) {
        Objects.requireNonNull(releaseId, "releaseId 不能为空");
        HttpRequest request = HttpRequest.newBuilder(snapshotUri(releaseId))
                .timeout(properties.resolvedRequestTimeout())
                .header("Accept", "application/json")
                .header(TOKEN_HEADER, readToken())
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new ConfigurationFetchException(
                            "配置管理服务返回非成功状态: " + response.statusCode());
                }
                int maxBytes = properties.resolvedMaxSnapshotBytes();
                byte[] bytes = body.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    throw new InvalidConfigurationSnapshotException("配置快照响应超过大小上限");
                }
                return parse(bytes);
            }
        } catch (InvalidConfigurationSnapshotException | ConfigurationFetchException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ConfigurationFetchException("配置快照拉取被中断", exception);
        } catch (IOException exception) {
            throw new ConfigurationFetchException("配置快照拉取失败", exception);
        }
    }

    /**
     * 向管理服务回报当前 Gateway 节点已加载并校验指定快照。
     *
     * @param releaseId 配置发布标识
     * @param actualHash 节点实际加载的内容哈希
     */
    @Override
    public void reportLoaded(UUID releaseId, String actualHash) {
        Objects.requireNonNull(releaseId, "releaseId 不能为空");
        Objects.requireNonNull(actualHash, "actualHash 不能为空");
        reportStatus(releaseId, "LOADED", actualHash, "配置快照已校验并原子激活");
    }

    /**
     * 向管理服务回报当前 Gateway 节点永久无法加载指定快照。
     *
     * @param releaseId 配置发布标识
     * @param safeSummary 已脱敏且有界的错误摘要
     */
    @Override
    public void reportFailed(UUID releaseId, String safeSummary) {
        Objects.requireNonNull(releaseId, "releaseId 不能为空");
        String summary = Objects.requireNonNull(safeSummary, "错误摘要不能为空").strip();
        if (summary.isEmpty() || summary.length() > 500) {
            throw new IllegalArgumentException("配置失败摘要必须为 1 到 500 个字符");
        }
        reportStatus(releaseId, "FAILED", null, summary);
    }

    private void reportStatus(UUID releaseId, String status, String actualHash, String message) {
        HttpRequest request = HttpRequest.newBuilder(nodeStatusUri(releaseId))
                .timeout(properties.resolvedRequestTimeout())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header(TOKEN_HEADER, readToken())
                .POST(HttpRequest.BodyPublishers.ofByteArray(statusBody(status, actualHash, message)))
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 204) {
                throw new ConfigurationFetchException(
                        "配置加载状态回报返回非成功状态: " + response.statusCode());
            }
        } catch (ConfigurationFetchException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ConfigurationFetchException("配置加载状态回报被中断", exception);
        } catch (IOException exception) {
            throw new ConfigurationFetchException("配置加载状态回报失败", exception);
        }
    }

    private ConfigurationSnapshot parse(byte[] bytes) {
        try {
            JsonNode root = jsonMapper.readTree(bytes);
            JsonNode snapshot = root == null ? null : root.get("snapshot");
            if (root == null || !root.isObject() || snapshot == null || !snapshot.isObject()) {
                throw new InvalidConfigurationSnapshotException("配置快照响应结构不合法");
            }
            return new ConfigurationSnapshot(
                    uuid(root, "releaseId"),
                    uuid(root, "productId"),
                    uuid(root, "packageId"),
                    positiveLong(root, "releaseSequence"),
                    text(root, "contentHash"),
                    jsonMapper.writeValueAsString(snapshot)
            );
        } catch (InvalidConfigurationSnapshotException exception) {
            throw exception;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new InvalidConfigurationSnapshotException("配置快照响应 JSON 无法解析", exception);
        }
    }

    private UUID uuid(JsonNode root, String field) {
        return UUID.fromString(text(root, field));
    }

    private long positiveLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.asLong() < 1) {
            throw new InvalidConfigurationSnapshotException("配置快照字段不合法: " + field);
        }
        return value.asLong();
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new InvalidConfigurationSnapshotException("配置快照字段不合法: " + field);
        }
        return value.asString();
    }

    private URI snapshotUri(UUID releaseId) {
        return URI.create(baseUrl() + "/internal/v1/config-releases/" + releaseId);
    }

    private URI nodeStatusUri(UUID releaseId) {
        return URI.create(baseUrl() + "/internal/v1/config-releases/" + releaseId
                + "/nodes/" + properties.resolvedNodeId());
    }

    private String baseUrl() {
        String base = properties.resolvedServerBaseUrl().toString();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private byte[] statusBody(String status, String actualHash, String message) {
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("nodeType", "GATEWAY");
            body.put("status", status);
            if (actualHash != null) body.put("actualHash", actualHash);
            body.put("message", message);
            return jsonMapper.writeValueAsBytes(body);
        } catch (JacksonException exception) {
            throw new IllegalStateException("配置加载状态无法序列化", exception);
        }
    }

    private String readToken() {
        try {
            String token = Files.readString(properties.resolvedServiceTokenFile()).strip();
            if (token.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new ConfigurationFetchException("配置分发服务令牌长度不足");
            }
            return token;
        } catch (IOException exception) {
            throw new ConfigurationFetchException("无法读取配置分发服务令牌", exception);
        }
    }
}
