package com.bosco.mine.server.modules.notification.provider.im;

import com.bosco.mine.server.modules.notification.adapter.NotificationAdapterKey;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelAdapter;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryRequest;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryResult;
import com.bosco.mine.server.modules.notification.provider.NotificationProviderDefinition;
import com.bosco.mine.server.modules.notification.provider.NotificationProviderSupport;
import com.bosco.mine.server.modules.notification.provider.http.NotificationEndpointPolicy;
import com.bosco.mine.server.modules.notification.provider.http.NotificationHttpResults;
import com.bosco.mine.server.modules.notification.provider.http.NotificationHttpTransport;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 封装企业机器人 Provider 共用的安全端点、JSON 与 HTTP 结果处理。
 */
abstract class AbstractRobotNotificationAdapter implements NotificationChannelAdapter {

    private final NotificationAdapterKey key;
    private final NotificationProviderDefinition definition;
    protected final NotificationHttpTransport transport;
    protected final NotificationEndpointPolicy endpointPolicy;
    protected final ObjectMapper objectMapper;

    /** 创建企业机器人基类。 */
    AbstractRobotNotificationAdapter(
            NotificationAdapterKey key,
            String displayNameKey,
            List<NotificationProviderDefinition.Field> secrets,
            NotificationHttpTransport transport,
            NotificationEndpointPolicy endpointPolicy,
            ObjectMapper objectMapper
    ) {
        this.key = key;
        this.definition = new NotificationProviderDefinition(
                key, displayNameKey, false, List.of(), secrets);
        this.transport = transport;
        this.endpointPolicy = endpointPolicy;
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} */
    @Override
    public final NotificationAdapterKey key() {
        return key;
    }

    /** {@inheritDoc} */
    @Override
    public final NotificationProviderDefinition definition() {
        return definition;
    }

    /** {@inheritDoc} */
    @Override
    public void validateConfiguration(
            Map<String, Object> configuration,
            Map<String, String> credentials
    ) {
        endpointPolicy.validate(NotificationProviderSupport.requiredString(
                credentials, "webhookUrl"), Set.of(officialHost()));
    }

    /** {@inheritDoc} */
    @Override
    public final NotificationDeliveryResult deliver(NotificationDeliveryRequest request) {
        try {
            URI endpoint = endpoint(request);
            String body = json(payload(request));
            if (body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBodyBytes()) {
                return NotificationDeliveryResult.permanentFailure("ROBOT_PAYLOAD_TOO_LARGE");
            }
            NotificationHttpTransport.Response response = transport.post(
                    new NotificationHttpTransport.Request(
                            endpoint, Map.of(), body,
                            Duration.ofMillis(request.connectTimeoutMs()),
                            Duration.ofMillis(request.readTimeoutMs())));
            NotificationDeliveryResult failure = NotificationHttpResults.failure(response.statusCode());
            if (failure != null) {
                return failure;
            }
            return providerAccepted(response.body())
                    ? NotificationDeliveryResult.submitted(null)
                    : NotificationDeliveryResult.permanentFailure("ROBOT_REQUEST_REJECTED");
        } catch (NotificationHttpTransport.TransportException exception) {
            return NotificationHttpResults.transportFailure(exception);
        }
    }

    /** 返回供应商固定主机。 */
    protected abstract String officialHost();

    /** 生成供应商请求端点。 */
    protected URI endpoint(NotificationDeliveryRequest request) {
        return endpointPolicy.validate(NotificationProviderSupport.requiredCredential(
                request, "webhookUrl"), Set.of(officialHost()));
    }

    /** 生成供应商 JSON 对象。 */
    protected abstract Map<String, Object> payload(NotificationDeliveryRequest request);

    /** 判断供应商业务响应是否受理。 */
    protected abstract boolean providerAccepted(String responseBody);

    /** 返回供应商正文最大字节数。 */
    protected int maxBodyBytes() {
        return 64 * 1024;
    }

    /** 拼接已渲染通知标题和正文。 */
    protected String text(NotificationDeliveryRequest request) {
        return request.subject() + "\n" + request.body();
    }

    /** 安全解析供应商响应，解析失败视为未受理。 */
    protected JsonNode readTree(String responseBody) {
        try {
            return objectMapper.readTree(responseBody);
        } catch (JacksonException exception) {
            return objectMapper.createObjectNode();
        }
    }

    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("企业机器人正文无法序列化", exception);
        }
    }
}
