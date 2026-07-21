package com.bosco.mine.telemetry.aggregation;

import com.bosco.mine.telemetry.bootstrap.StrategyDistributionProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用外部服务令牌调用 mine-server 显式聚合重算租约接口的 JDK HTTP 客户端。
 */
public final class HttpAggregationRecomputeClient implements AggregationRecomputeClient {

    private static final String TOKEN_HEADER = "X-Mine-Service-Token";
    private static final String INTERNAL_PATH = "/internal/v1/aggregation-recomputations";

    private final URI serverUrl;
    private final Path tokenFile;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    /**
     * 创建复用策略分发控制面地址、服务令牌和请求超时的重算客户端。
     *
     * @param properties 策略分发内部连接参数
     */
    public HttpAggregationRecomputeClient(StrategyDistributionProperties properties) {
        Objects.requireNonNull(properties, "显式重算内部连接参数不能为空");
        this.serverUrl = validateServerUrl(properties.resolvedServerUrl());
        this.tokenFile = properties.resolvedServiceTokenFile();
        this.requestTimeout = properties.resolvedRequestTimeout();
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("显式重算请求超时必须大于零");
        }
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Claim> claim(String instanceId, Duration leaseDuration) {
        String normalizedInstance = requireText(instanceId, "Telemetry 实例标识", 128);
        if (leaseDuration == null
                || leaseDuration.compareTo(Duration.ofMinutes(1)) < 0
                || leaseDuration.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("显式重算租约时长必须在 1 分钟到 1 小时之间");
        }
        byte[] requestBody = encode(Map.of(
                "instanceId", normalizedInstance,
                "leaseDuration", leaseDuration));
        HttpResponse<byte[]> response = send(post(INTERNAL_PATH + "/claim", requestBody));
        if (response.statusCode() == 204) return Optional.empty();
        if (response.statusCode() == 200) return Optional.of(decodeClaim(response.body()));
        throw responseFailure("认领", response.statusCode());
    }

    /** {@inheritDoc} */
    @Override
    public CompletionResult complete(Claim claim, CompletionStatus status, String error) {
        Objects.requireNonNull(claim, "显式重算当前租约不能为空");
        Objects.requireNonNull(status, "显式重算完成状态不能为空");
        if ((status == CompletionStatus.SUCCEEDED && error != null)
                || (status == CompletionStatus.FAILED && (error == null || error.isBlank()))) {
            throw new IllegalArgumentException("显式重算完成状态与错误摘要不一致");
        }
        byte[] requestBody = encode(new CompletionRequest(
                claim.executionId(), claim.claimToken(), status.name(), error));
        HttpResponse<byte[]> response = send(post(
                INTERNAL_PATH + "/" + claim.requestId() + "/complete", requestBody));
        if (response.statusCode() == 204) return CompletionResult.COMPLETED;
        if (response.statusCode() == 409) return CompletionResult.LEASE_LOST;
        throw responseFailure("完成", response.statusCode());
    }

    private HttpRequest post(String path, byte[] body) {
        return HttpRequest.newBuilder(URI.create(serverUrl + path))
                .timeout(requestTimeout)
                .header(TOKEN_HEADER, readToken())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("显式重算控制面请求被中断", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("无法访问显式重算控制面接口", exception);
        }
    }

    private byte[] encode(Object value) {
        try {
            return jsonMapper.writeValueAsBytes(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法编码显式重算控制面请求", exception);
        }
    }

    private Claim decodeClaim(byte[] body) {
        try {
            ClaimResponse response = jsonMapper.readValue(body, ClaimResponse.class);
            return new Claim(
                    response.requestId(), response.executionId(), response.claimToken(),
                    response.deviceId(), response.propertyId(), response.bucket(),
                    response.from(), response.to(), response.leaseUntil(),
                    response.attemptCount());
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            throw new PermanentAggregationRecomputeException(
                    "显式重算认领响应无法严格解析", exception);
        }
    }

    private RuntimeException responseFailure(String action, int statusCode) {
        String message = "显式重算控制面" + action + "失败，HTTP " + statusCode;
        if (statusCode >= 400 && statusCode < 500
                && statusCode != 401 && statusCode != 408 && statusCode != 429) {
            return new PermanentAggregationRecomputeException(message);
        }
        return new IllegalStateException(message);
    }

    private String readToken() {
        try {
            String token = Files.readString(tokenFile).strip();
            if (token.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException("显式重算服务令牌长度不足");
            }
            return token;
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取显式重算服务令牌", exception);
        }
    }

    private URI validateServerUrl(URI value) {
        String scheme = value.getScheme();
        String path = value.getPath();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || value.getHost() == null || value.getUserInfo() != null
                || value.getQuery() != null || value.getFragment() != null
                || !(path == null || path.isEmpty() || "/".equals(path))) {
            throw new IllegalArgumentException("显式重算服务地址必须是无凭据的 HTTP(S) 根地址");
        }
        String normalized = value.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return URI.create(normalized);
    }

    private String requireText(String value, String label, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(label + "不能为空且长度不能超过" + maximumLength);
        }
        return value.strip();
    }

    private record ClaimResponse(
            UUID requestId,
            UUID executionId,
            UUID claimToken,
            UUID deviceId,
            String propertyId,
            Duration bucket,
            Instant from,
            Instant to,
            Instant leaseUntil,
            int attemptCount
    ) {
    }

    private record CompletionRequest(
            UUID executionId,
            UUID claimToken,
            String status,
            String error
    ) {
    }
}
