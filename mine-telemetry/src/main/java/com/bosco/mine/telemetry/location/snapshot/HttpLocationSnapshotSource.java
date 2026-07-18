package com.bosco.mine.telemetry.location.snapshot;

import com.bosco.mine.telemetry.bootstrap.LocationDistributionProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 使用外部 secret 服务令牌从 mine-server 拉取不可变定位运行快照。 */
public final class HttpLocationSnapshotSource implements LocationSnapshotSource {

    private static final String TOKEN_HEADER = "X-Mine-Service-Token";
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

    private final URI serverUrl;
    private final java.nio.file.Path tokenFile;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建定位快照 HTTP 源。
     *
     * @param properties 定位分发参数
     */
    public HttpLocationSnapshotSource(LocationDistributionProperties properties) {
        Objects.requireNonNull(properties, "定位分发参数不能为空");
        this.serverUrl = validateServerUrl(properties.resolvedServerUrl());
        this.tokenFile = properties.resolvedServiceTokenFile();
        this.requestTimeout = properties.resolvedRequestTimeout();
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("定位快照请求超时必须大于 0");
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** {@inheritDoc} */
    @Override
    public SnapshotPage fetch(UUID snapshotId, String afterItemKey, int limit) {
        if (snapshotId == null || limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("定位快照请求参数不合法");
        }
        StringBuilder path = new StringBuilder(serverUrl.toString())
                .append("/api/v1/internal/location-snapshots/")
                .append(snapshotId)
                .append("?limit=")
                .append(limit);
        if (afterItemKey != null) {
            path.append("&afterItemKey=").append(URLEncoder.encode(
                    afterItemKey, StandardCharsets.UTF_8));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(path.toString()))
                .timeout(requestTimeout)
                .header(TOKEN_HEADER, readToken())
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 200) {
                    return decode(readBounded(body));
                }
            }
            if (response.statusCode() >= 400 && response.statusCode() < 500
                    && response.statusCode() != 401 && response.statusCode() != 429) {
                throw new PermanentLocationSnapshotException(
                        "控制面拒绝定位快照请求，HTTP " + response.statusCode());
            }
            throw new IllegalStateException(
                    "控制面定位快照暂时不可用，HTTP " + response.statusCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("定位快照请求被中断", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("无法访问控制面定位快照接口", exception);
        }
    }

    private SnapshotPage decode(byte[] body) {
        try {
            SnapshotResponse response = jsonMapper.readValue(body, SnapshotResponse.class);
            if (response.snapshotId() == null || response.organizationId() == null
                    || response.siteId() == null || response.revision() < 1
                    || response.itemCount() < 1 || response.contentHash() == null
                    || response.createdAt() == null || response.items() == null) {
                throw new PermanentLocationSnapshotException(
                        "控制面定位快照响应缺少必要字段");
            }
            List<SnapshotItem> items = response.items().stream().map(item -> {
                if (item.itemKey() == null || item.itemType() == null
                        || item.content() == null || !item.content().isObject()
                        || item.contentHash() == null) {
                    throw new PermanentLocationSnapshotException(
                            "控制面定位快照条目不合法");
                }
                return new SnapshotItem(
                        item.itemKey(), item.itemType(), item.content(), item.contentHash());
            }).toList();
            return new SnapshotPage(
                    response.snapshotId(), response.organizationId(), response.siteId(),
                    response.revision(), response.itemCount(), response.contentHash(),
                    response.createdAt(), items, response.nextAfterItemKey());
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            if (exception instanceof PermanentLocationSnapshotException permanent) {
                throw permanent;
            }
            throw new PermanentLocationSnapshotException(
                    "控制面定位快照响应无法解析", exception);
        }
    }

    private byte[] readBounded(InputStream input) throws IOException {
        byte[] body = input.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new PermanentLocationSnapshotException("控制面定位快照响应超过大小上限");
        }
        return body;
    }

    private String readToken() {
        try {
            String token = Files.readString(tokenFile).strip();
            if (token.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException("定位分发服务令牌长度不足");
            }
            return token;
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取定位分发服务令牌", exception);
        }
    }

    private URI validateServerUrl(URI value) {
        String scheme = value.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || value.getHost() == null || value.getUserInfo() != null
                || value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException("定位分发服务地址必须是无凭据的 HTTP(S) 根地址");
        }
        String normalized = value.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized);
    }

    private record SnapshotResponse(
            UUID snapshotId,
            UUID organizationId,
            UUID siteId,
            long revision,
            String changeReason,
            int itemCount,
            String contentHash,
            Instant createdAt,
            List<SnapshotItemResponse> items,
            String nextAfterItemKey
    ) {
    }

    private record SnapshotItemResponse(
            String itemKey,
            String itemType,
            JsonNode content,
            String contentHash
    ) {
    }
}
