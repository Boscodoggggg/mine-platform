package com.bosco.mine.server.modules.notification.provider.aliyun;

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

import java.util.List;
import java.util.Map;

/**
 * 阿里云短信通知 Provider。
 */
@Component
public class AliyunSmsNotificationAdapter implements NotificationChannelAdapter {

    private static final String ENDPOINT = "dysmsapi.aliyuncs.com";
    private static final NotificationAdapterKey KEY =
            new NotificationAdapterKey(NotificationChannelType.SMS, "aliyun-sms");
    private static final NotificationProviderDefinition DEFINITION =
            new NotificationProviderDefinition(
                    KEY, "mine.notification.provider.aliyun-sms", false,
                    List.of(
                            NotificationProviderSupport.stringField("endpoint", true, 253, ENDPOINT),
                            NotificationProviderSupport.stringField("regionId", true, 64, "cn-hangzhou"),
                            NotificationProviderSupport.stringField("signName", true, 128, null),
                            NotificationProviderSupport.stringField("templateCode", true, 128, null)),
                    List.of(
                            NotificationProviderSupport.secretField("accessKeyId", true, 512),
                            NotificationProviderSupport.secretField("accessKeySecret", true, 4096)));

    private final AliyunSmsGateway gateway;
    private final ObjectMapper objectMapper;

    /** 创建阿里云短信 Provider。 */
    public AliyunSmsNotificationAdapter(AliyunSmsGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
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
        if (!ENDPOINT.equals(NotificationProviderSupport.requiredString(configuration, "endpoint"))) {
            throw new IllegalArgumentException("阿里云短信只允许官方服务端点");
        }
        validateRegion(configuration);
        String templateCode = NotificationProviderSupport.requiredString(
                configuration, "templateCode");
        if (!templateCode.matches("^[A-Za-z0-9_]{2,128}$")) {
            throw new IllegalArgumentException("阿里云短信模板编码格式不合法");
        }
    }

    /** {@inheritDoc} */
    @Override
    public NotificationDeliveryResult deliver(NotificationDeliveryRequest request) {
        if (!request.recipient().matches("^\\+?[0-9]{6,20}(?:,\\+?[0-9]{6,20})*$")) {
            return NotificationDeliveryResult.permanentFailure("ALIYUN_RECIPIENT_INVALID");
        }
        String templateParam = json(request.templateVariables());
        if (templateParam.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) {
            return NotificationDeliveryResult.permanentFailure("ALIYUN_TEMPLATE_PARAM_TOO_LARGE");
        }
        try {
            AliyunSmsGateway.Response response = gateway.send(new AliyunSmsGateway.Command(
                    ENDPOINT,
                    NotificationProviderSupport.requiredString(request.configuration(), "regionId"),
                    NotificationProviderSupport.requiredCredential(request, "accessKeyId"),
                    NotificationProviderSupport.requiredCredential(request, "accessKeySecret"),
                    request.recipient(),
                    NotificationProviderSupport.requiredString(request.configuration(), "signName"),
                    NotificationProviderSupport.requiredString(
                            request.configuration(), "templateCode"),
                    templateParam, outId(request),
                    request.connectTimeoutMs(), request.readTimeoutMs()));
            return AliyunProviderResults.from(response.code(), response.bizId());
        } catch (AliyunGatewayException exception) {
            return NotificationDeliveryResult.unknown("ALIYUN_DELIVERY_UNCERTAIN");
        }
    }

    private void validateRegion(Map<String, Object> configuration) {
        String region = NotificationProviderSupport.requiredString(configuration, "regionId");
        if (!region.matches("^[a-z0-9-]{2,64}$")) {
            throw new IllegalArgumentException("阿里云地域编码格式不合法");
        }
    }

    private String json(Map<String, Object> variables) {
        try {
            return objectMapper.writeValueAsString(variables);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("阿里云短信模板参数无法序列化", exception);
        }
    }

    private String outId(NotificationDeliveryRequest request) {
        return AliyunProviderResults.outId(request.deliveryId());
    }
}
