package com.bosco.mine.server.modules.notification.provider.im;

import com.bosco.mine.server.modules.notification.adapter.NotificationAdapterKey;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryRequest;
import com.bosco.mine.server.modules.notification.provider.NotificationProviderSupport;
import com.bosco.mine.server.modules.notification.provider.http.NotificationEndpointPolicy;
import com.bosco.mine.server.modules.notification.provider.http.NotificationHttpTransport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * 钉钉自定义机器人文本通知 Provider。
 */
@Component
public class DingTalkRobotNotificationAdapter extends AbstractRobotNotificationAdapter {

    private final Clock clock;

    /** 创建使用系统 UTC 时钟的钉钉 Provider。 */
    @Autowired
    public DingTalkRobotNotificationAdapter(
            NotificationHttpTransport transport,
            NotificationEndpointPolicy endpointPolicy,
            ObjectMapper objectMapper
    ) {
        this(transport, endpointPolicy, objectMapper, Clock.systemUTC());
    }

    /** 创建可注入时钟的钉钉 Provider。 */
    public DingTalkRobotNotificationAdapter(
            NotificationHttpTransport transport,
            NotificationEndpointPolicy endpointPolicy,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        super(new NotificationAdapterKey(NotificationChannelType.ENTERPRISE_IM, "dingtalk-robot"),
                "mine.notification.provider.dingtalk-robot",
                List.of(
                        NotificationProviderSupport.secretField("webhookUrl", true, 2048),
                        NotificationProviderSupport.secretField("signingSecret", false, 4096)),
                transport, endpointPolicy, objectMapper);
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    protected String officialHost() {
        return "oapi.dingtalk.com";
    }

    /** {@inheritDoc} */
    @Override
    protected URI endpoint(NotificationDeliveryRequest request) {
        URI base = super.endpoint(request);
        String secret = NotificationProviderSupport.optionalString(
                request.credentials(), "signingSecret");
        if (secret == null) {
            return base;
        }
        long timestamp = clock.instant().toEpochMilli();
        String sign = NotificationProviderSupport.hmacBase64(
                timestamp + "\n" + secret, secret);
        String separator = base.getRawQuery() == null ? "?" : "&";
        return URI.create(base + separator + "timestamp=" + timestamp + "&sign="
                + URLEncoder.encode(sign, StandardCharsets.UTF_8));
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
