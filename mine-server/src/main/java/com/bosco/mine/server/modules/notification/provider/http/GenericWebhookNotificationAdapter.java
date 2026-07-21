package com.bosco.mine.server.modules.notification.provider.http;

import com.bosco.mine.server.modules.notification.adapter.NotificationAdapterKey;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelAdapter;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryRequest;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryResult;
import com.bosco.mine.server.modules.notification.provider.NotificationProviderDefinition;
import com.bosco.mine.server.modules.notification.provider.NotificationProviderSupport;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 向客户明确白名单内的 HTTPS 服务发送平台标准 Webhook 事件。
 */
@Component
public class GenericWebhookNotificationAdapter implements NotificationChannelAdapter {

    private static final NotificationAdapterKey KEY =
            new NotificationAdapterKey(NotificationChannelType.WEBHOOK, "generic-webhook");
    private static final NotificationProviderDefinition DEFINITION =
            new NotificationProviderDefinition(
                    KEY, "mine.notification.provider.generic-webhook", false,
                    List.of(
                            NotificationProviderSupport.stringField("endpointUrl", true, 2048, null),
                            NotificationProviderSupport.stringListField("allowedHosts", true, 32),
                            NotificationProviderSupport.stringField(
                                    "signatureHeader", false, 128, "X-Mine-Signature")),
                    List.of(
                            NotificationProviderSupport.secretField("signingSecret", false, 4096),
                            NotificationProviderSupport.secretField("bearerToken", false, 8192)));

    private final NotificationHttpTransport transport;
    private final NotificationEndpointPolicy endpointPolicy;
    private final ObjectMapper objectMapper;

    /**
     * 创建通用 Webhook Provider。
     *
     * @param transport HTTP 传输
     * @param endpointPolicy 端点安全策略
     * @param objectMapper JSON 映射器
     */
    public GenericWebhookNotificationAdapter(
            NotificationHttpTransport transport,
            NotificationEndpointPolicy endpointPolicy,
            ObjectMapper objectMapper
    ) {
        this.transport = transport;
        this.endpointPolicy = endpointPolicy;
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} */
    @Override
    public NotificationAdapterKey key() {
        return KEY;
    }

    /** {@inheritDoc} */
    @Override
    public NotificationProviderDefinition definition() {
        return DEFINITION;
    }

    /** {@inheritDoc} */
    @Override
    public void validateConfiguration(
            Map<String, Object> configuration,
            Map<String, String> credentials
    ) {
        endpoint(configuration);
        String header = NotificationProviderSupport.optionalString(configuration, "signatureHeader");
        if (header != null && !header.matches("^[A-Za-z][A-Za-z0-9-]{0,127}$")) {
            throw new IllegalArgumentException("Webhook 签名请求头名称不合法");
        }
        String bearerToken = NotificationProviderSupport.optionalString(credentials, "bearerToken");
        if (bearerToken != null && (bearerToken.contains("\r") || bearerToken.contains("\n"))) {
            throw new IllegalArgumentException("Webhook 授权凭据包含非法控制字符");
        }
    }

    /** {@inheritDoc} */
    @Override
    public NotificationDeliveryResult deliver(NotificationDeliveryRequest request) {
        URI endpoint = endpoint(request.configuration());
        String body = body(request);
        Map<String, String> headers = new LinkedHashMap<>();
        String signingSecret = NotificationProviderSupport.optionalString(
                request.credentials(), "signingSecret");
        if (signingSecret != null) {
            String header = NotificationProviderSupport.optionalString(
                    request.configuration(), "signatureHeader");
            headers.put(header == null ? "X-Mine-Signature" : header,
                    "sha256=" + NotificationProviderSupport.hmacHex(body, signingSecret));
        }
        String bearerToken = NotificationProviderSupport.optionalString(
                request.credentials(), "bearerToken");
        if (bearerToken != null) {
            headers.put("Authorization", "Bearer " + bearerToken);
        }
        try {
            NotificationHttpTransport.Response response = transport.post(
                    new NotificationHttpTransport.Request(
                            endpoint, headers, body,
                            Duration.ofMillis(request.connectTimeoutMs()),
                            Duration.ofMillis(request.readTimeoutMs())));
            NotificationDeliveryResult failure = NotificationHttpResults.failure(response.statusCode());
            return failure == null ? NotificationDeliveryResult.submitted(null) : failure;
        } catch (NotificationHttpTransport.TransportException exception) {
            return NotificationHttpResults.transportFailure(exception);
        }
    }

    private URI endpoint(Map<String, ?> configuration) {
        String endpointUrl = NotificationProviderSupport.requiredString(configuration, "endpointUrl");
        List<String> allowedHosts = NotificationProviderSupport.stringList(
                configuration, "allowedHosts");
        if (allowedHosts.isEmpty()) {
            throw new IllegalArgumentException("Webhook 主机白名单不能为空");
        }
        return endpointPolicy.validate(endpointUrl, new LinkedHashSet<>(allowedHosts));
    }

    private String body(NotificationDeliveryRequest request) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("deliveryId", request.deliveryId().toString());
        event.put("idempotencyKey", request.idempotencyKey());
        event.put("recipient", request.recipient());
        event.put("subject", request.subject());
        event.put("body", request.body());
        event.put("variables", request.templateVariables());
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Webhook 事件无法序列化", exception);
        }
    }
}
