package com.bosco.mine.telemetry.strategy;

import com.bosco.mine.telemetry.bootstrap.StrategyDistributionProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 使用外部 secret 中的服务令牌从 mine-server 拉取不可变策略运行快照。
 */
public final class HttpStrategySnapshotSource implements StrategySnapshotSource {

    private static final String TOKEN_HEADER = "X-Mine-Service-Token";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final URI serverUrl;
    private final java.nio.file.Path tokenFile;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /** 创建基于 JDK HTTP 客户端的策略快照源。 */
    public HttpStrategySnapshotSource(StrategyDistributionProperties properties) {
        Objects.requireNonNull(properties, "策略分发参数不能为空");
        this.serverUrl = validateServerUrl(properties.resolvedServerUrl());
        this.tokenFile = properties.resolvedServiceTokenFile();
        this.requestTimeout = properties.resolvedRequestTimeout();
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("策略快照请求超时必须大于 0");
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public SnapshotPage fetch(UUID snapshotId, UUID afterDeviceId, int limit) {
        StringBuilder path = new StringBuilder(serverUrl.toString())
                .append("/internal/v1/strategy-runtime-snapshots/")
                .append(snapshotId)
                .append("?limit=")
                .append(limit);
        if (afterDeviceId != null) {
            path.append("&afterDeviceId=").append(afterDeviceId);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(path.toString()))
                .timeout(requestTimeout)
                .header(TOKEN_HEADER, readToken())
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                return decode(response.body());
            }
            if (response.statusCode() >= 400 && response.statusCode() < 500
                    && response.statusCode() != 401 && response.statusCode() != 429) {
                throw new PermanentStrategySnapshotException(
                        "控制面拒绝策略快照请求，HTTP " + response.statusCode());
            }
            throw new IllegalStateException(
                    "控制面策略快照暂时不可用，HTTP " + response.statusCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("策略快照请求被中断", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("无法访问控制面策略快照接口", exception);
        }
    }

    private SnapshotPage decode(byte[] body) {
        try {
            SnapshotResponse response = jsonMapper.readValue(body, SnapshotResponse.class);
            if (response.snapshotId() == null || response.productId() == null
                    || response.strategyType() == null || response.runtimeRevision() < 1
                    || response.itemCount() < 0 || response.contentHash() == null
                    || response.items() == null) {
                throw new PermanentStrategySnapshotException("控制面策略快照响应缺少必要字段");
            }
            List<SnapshotItem> items = response.items().stream().map(item -> {
                if (item.deviceId() == null || item.values() == null || item.contentHash() == null
                        || !item.values().isObject()) {
                    throw new PermanentStrategySnapshotException("控制面策略快照条目不合法");
                }
                return new SnapshotItem(
                        item.deviceId(), jsonMapper.convertValue(item.values(), MAP_TYPE),
                        item.contentHash());
            }).toList();
            return new SnapshotPage(
                    response.snapshotId(), response.productId(), response.strategyType(),
                    response.runtimeRevision(), response.itemCount(), response.contentHash(),
                    items, response.nextAfterDeviceId());
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            if (exception instanceof PermanentStrategySnapshotException permanent) {
                throw permanent;
            }
            throw new PermanentStrategySnapshotException("控制面策略快照响应无法解析", exception);
        }
    }

    private String readToken() {
        try {
            String token = Files.readString(tokenFile).strip();
            if (token.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException("策略分发服务令牌长度不足");
            }
            return token;
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取策略分发服务令牌", exception);
        }
    }

    private URI validateServerUrl(URI value) {
        String scheme = value.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || value.getHost() == null || value.getUserInfo() != null
                || value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException("策略分发服务地址必须是无凭据的 HTTP(S) 根地址");
        }
        String normalized = value.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized);
    }

    private record SnapshotResponse(
            UUID snapshotId,
            UUID productId,
            String strategyType,
            long runtimeRevision,
            int itemCount,
            String contentHash,
            List<SnapshotItemResponse> items,
            UUID nextAfterDeviceId
    ) {
    }

    private record SnapshotItemResponse(UUID deviceId, JsonNode values, String contentHash) {
    }
}
