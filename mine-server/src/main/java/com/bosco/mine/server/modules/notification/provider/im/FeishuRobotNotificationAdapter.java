package com.bosco.mine.server.modules.notification.provider.im;

import com.bosco.mine.server.modules.notification.adapter.NotificationAdapterKey;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryRequest;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryResult;
import com.bosco.mine.server.modules.notification.provider.NotificationProviderSupport;
import com.bosco.mine.server.modules.notification.provider.http.NotificationEndpointPolicy;
import com.bosco.mine.server.modules.notification.provider.http.NotificationHttpTransport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞书自定义机器人文本通知 Provider。
 */
@Component
public class FeishuRobotNotificationAdapter extends AbstractRobotNotificationAdapter {

    private final Clock clock;

    /** 创建使用系统 UTC 时钟的飞书 Provider。 */
    @Autowired
    public FeishuRobotNotificationAdapter(
            NotificationHttpTransport transport,
            NotificationEndpointPolicy endpointPolicy,
            ObjectMapper objectMapper
    ) {
        this(transport, endpointPolicy, objectMapper, Clock.systemUTC());
    }

    /** 创建可注入时钟的飞书 Provider。 */
    public FeishuRobotNotificationAdapter(
            NotificationHttpTransport transport,
            NotificationEndpointPolicy endpointPolicy,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        super(new NotificationAdapterKey(NotificationChannelType.ENTERPRISE_IM, "feishu-robot"),
                "mine.notification.provider.feishu-robot",
                List.of(
                        NotificationProviderSupport.secretField("webhookUrl", true, 2048),
                        NotificationProviderSupport.secretField("signingSecret", false, 4096)),
                transport, endpointPolicy, objectMapper);
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    protected String officialHost() {
        return "open.feishu.cn";
    }

    /** {@inheritDoc} */
    @Override
    protected Map<String, Object> payload(NotificationDeliveryRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String secret = NotificationProviderSupport.optionalString(
                request.credentials(), "signingSecret");
        if (secret != null) {
            long timestamp = clock.instant().getEpochSecond();
            payload.put("timestamp", String.valueOf(timestamp));
            payload.put("sign", NotificationProviderSupport.hmacBase64(
                    "", timestamp + "\n" + secret));
        }
        payload.put("msg_type", "text");
        payload.put("content", Map.of("text", text(request)));
        return payload;
    }

    /** {@inheritDoc} */
    @Override
    protected NotificationDeliveryResult providerResult(String responseBody) {
        JsonNode root = readTree(responseBody);
        JsonNode codeNode = root.has("code") ? root.path("code") : root.path("StatusCode");
        if (codeNode.isMissingNode() || !codeNode.canConvertToInt()) {
            return uncertain();
        }
        int code = codeNode.asInt();
        if (code == 0) {
            return NotificationDeliveryResult.submitted(null);
        }
        return switch (code) {
            case 11232, 11233, 11247, 230020, 99991400 -> rateLimited();
            default -> rejected();
        };
    }

    /** {@inheritDoc} */
    @Override
    protected int maxBodyBytes() {
        return 20 * 1024;
    }
}
