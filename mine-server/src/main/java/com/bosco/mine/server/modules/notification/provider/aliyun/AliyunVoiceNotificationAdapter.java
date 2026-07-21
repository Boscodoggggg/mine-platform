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
 * 阿里云 TTS 语音电话通知 Provider。
 */
@Component
public class AliyunVoiceNotificationAdapter implements NotificationChannelAdapter {

    private static final String ENDPOINT = "dyvmsapi.aliyuncs.com";
    private static final NotificationAdapterKey KEY =
            new NotificationAdapterKey(NotificationChannelType.VOICE, "aliyun-voice");
    private static final NotificationProviderDefinition DEFINITION =
            new NotificationProviderDefinition(
                    KEY, "mine.notification.provider.aliyun-voice", true,
                    List.of(
                            NotificationProviderSupport.stringField("endpoint", true, 253, ENDPOINT),
                            NotificationProviderSupport.stringField("regionId", true, 64, "cn-hangzhou"),
                            NotificationProviderSupport.stringField("calledShowNumber", true, 32, null),
                            NotificationProviderSupport.stringField("ttsCode", true, 128, null),
                            NotificationProviderSupport.integerField("playTimes", false, 1),
                            NotificationProviderSupport.integerField("volume", false, 100),
                            NotificationProviderSupport.integerField("speed", false, 0)),
                    List.of(
                            NotificationProviderSupport.secretField("accessKeyId", true, 512),
                            NotificationProviderSupport.secretField("accessKeySecret", true, 4096)));

    private final AliyunVoiceGateway gateway;
    private final ObjectMapper objectMapper;

    /** 创建阿里云语音 Provider。 */
    public AliyunVoiceNotificationAdapter(AliyunVoiceGateway gateway, ObjectMapper objectMapper) {
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
            throw new IllegalArgumentException("阿里云语音只允许官方服务端点");
        }
        String region = NotificationProviderSupport.requiredString(configuration, "regionId");
        if (!region.matches("^[a-z0-9-]{2,64}$")) {
            throw new IllegalArgumentException("阿里云地域编码格式不合法");
        }
        String ttsCode = NotificationProviderSupport.requiredString(configuration, "ttsCode");
        if (!ttsCode.matches("^[A-Za-z0-9_]{2,128}$")) {
            throw new IllegalArgumentException("阿里云语音模板编码格式不合法");
        }
        validateRange(configuration, "playTimes", 1, 3, 1);
        validateRange(configuration, "volume", 0, 100, 100);
        validateRange(configuration, "speed", -500, 500, 0);
    }

    /** {@inheritDoc} */
    @Override
    public NotificationDeliveryResult deliver(NotificationDeliveryRequest request) {
        if (!request.recipient().matches("^\\+?[0-9]{6,20}$")) {
            return NotificationDeliveryResult.permanentFailure("ALIYUN_RECIPIENT_INVALID");
        }
        String ttsParam = json(request.templateVariables());
        if (ttsParam.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) {
            return NotificationDeliveryResult.permanentFailure("ALIYUN_TEMPLATE_PARAM_TOO_LARGE");
        }
        try {
            AliyunVoiceGateway.Response response = gateway.call(new AliyunVoiceGateway.Command(
                    ENDPOINT,
                    NotificationProviderSupport.requiredString(request.configuration(), "regionId"),
                    NotificationProviderSupport.requiredCredential(request, "accessKeyId"),
                    NotificationProviderSupport.requiredCredential(request, "accessKeySecret"),
                    request.recipient(),
                    NotificationProviderSupport.requiredString(
                            request.configuration(), "calledShowNumber"),
                    NotificationProviderSupport.requiredString(request.configuration(), "ttsCode"),
                    ttsParam,
                    NotificationProviderSupport.integer(request.configuration(), "playTimes", 1),
                    NotificationProviderSupport.integer(request.configuration(), "volume", 100),
                    NotificationProviderSupport.integer(request.configuration(), "speed", 0),
                    request.deliveryId().toString().replace("-", "").substring(0, 15),
                    request.connectTimeoutMs(), request.readTimeoutMs()));
            return AliyunProviderResults.from(response.code(), response.callId());
        } catch (AliyunGatewayException exception) {
            return NotificationDeliveryResult.unknown("ALIYUN_DELIVERY_UNCERTAIN");
        }
    }

    private void validateRange(
            Map<String, Object> configuration,
            String key,
            int min,
            int max,
            int defaultValue
    ) {
        int value = NotificationProviderSupport.integer(configuration, key, defaultValue);
        if (value < min || value > max) {
            throw new IllegalArgumentException("阿里云语音数值字段超出范围: " + key);
        }
    }

    private String json(Map<String, Object> variables) {
        try {
            return objectMapper.writeValueAsString(variables);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("阿里云语音模板参数无法序列化", exception);
        }
    }
}
