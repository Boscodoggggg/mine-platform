package com.bosco.mine.server.modules.notification.provider.im;

import com.bosco.mine.server.modules.notification.adapter.NotificationAdapterKey;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryRequest;
import com.bosco.mine.server.modules.notification.provider.NotificationProviderSupport;
import com.bosco.mine.server.modules.notification.provider.http.NotificationEndpointPolicy;
import com.bosco.mine.server.modules.notification.provider.http.NotificationHttpTransport;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 企业微信群机器人文本通知 Provider。
 */
@Component
public class WeComRobotNotificationAdapter extends AbstractRobotNotificationAdapter {

    /** 创建企业微信机器人 Provider。 */
    public WeComRobotNotificationAdapter(
            NotificationHttpTransport transport,
            NotificationEndpointPolicy endpointPolicy,
            ObjectMapper objectMapper
    ) {
        super(new NotificationAdapterKey(NotificationChannelType.ENTERPRISE_IM, "wecom-robot"),
                "mine.notification.provider.wecom-robot",
                List.of(NotificationProviderSupport.secretField("webhookUrl", true, 2048)),
                transport, endpointPolicy, objectMapper);
    }

    /** {@inheritDoc} */
    @Override
    protected String officialHost() {
        return "qyapi.weixin.qq.com";
    }

    /** {@inheritDoc} */
    @Override
    protected Map<String, Object> payload(NotificationDeliveryRequest request) {
        return Map.of("msgtype", "text", "text", Map.of("content", text(request)));
    }

    /** {@inheritDoc} */
    @Override
    protected boolean providerAccepted(String responseBody) {
        return readTree(responseBody).path("errcode").asInt(-1) == 0;
    }
}
