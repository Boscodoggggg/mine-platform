package com.bosco.mine.server.modules.notification.provider;

import com.bosco.mine.server.modules.notification.adapter.NotificationAdapterKey;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelAdapter;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryRequest;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryResult;
import com.bosco.mine.server.modules.notification.provider.aliyun.AliyunSmsGateway;
import com.bosco.mine.server.modules.notification.provider.aliyun.AliyunSmsNotificationAdapter;
import com.bosco.mine.server.modules.notification.provider.aliyun.AliyunVoiceGateway;
import com.bosco.mine.server.modules.notification.provider.aliyun.AliyunVoiceNotificationAdapter;
import com.bosco.mine.server.modules.notification.provider.http.GenericWebhookNotificationAdapter;
import com.bosco.mine.server.modules.notification.provider.http.NotificationEndpointPolicy;
import com.bosco.mine.server.modules.notification.provider.http.NotificationHttpTransport;
import com.bosco.mine.server.modules.notification.provider.im.DingTalkRobotNotificationAdapter;
import com.bosco.mine.server.modules.notification.provider.im.FeishuRobotNotificationAdapter;
import com.bosco.mine.server.modules.notification.provider.im.WeComRobotNotificationAdapter;
import com.bosco.mine.server.modules.notification.provider.smtp.SmtpNotificationAdapter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验平台内置 Provider 的封闭注册和字段配置规则。
 */
class NotificationProviderRegistryTest {

    @Test
    void 注册表必须拒绝重复Provider键() {
        NotificationChannelAdapter first = adapter("smtp", NotificationChannelType.EMAIL);
        NotificationChannelAdapter duplicate = adapter("smtp", NotificationChannelType.EMAIL);

        assertThatThrownBy(() -> new NotificationProviderRegistry(List.of(first, duplicate)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("smtp");
    }

    @Test
    void 未注册或渠道类型不匹配的Provider必须被拒绝() {
        NotificationProviderRegistry registry = new NotificationProviderRegistry(List.of(
                adapter("smtp", NotificationChannelType.EMAIL)));

        assertThatThrownBy(() -> registry.require(
                new NotificationAdapterKey(NotificationChannelType.SMS, "aliyun-sms")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.require(
                new NotificationAdapterKey(NotificationChannelType.SMS, "smtp")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 禁用草稿可以缺少密钥但启用时必须满足必填字段() {
        NotificationProviderRegistry registry = new NotificationProviderRegistry(List.of(
                adapter("smtp", NotificationChannelType.EMAIL)));
        NotificationAdapterKey key = new NotificationAdapterKey(
                NotificationChannelType.EMAIL, "smtp");

        registry.validate(key, Map.of("host", "mail.mine.local"), Map.of(), false);
        assertThatThrownBy(() -> registry.validate(
                key, Map.of("host", "mail.mine.local"), Map.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");
        assertThatThrownBy(() -> registry.validate(
                key, Map.of("host", "mail.mine.local", "unknown", true),
                Map.of("password", "secret"), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void 显式空值必须按缺失字段处理而不是触发内部异常() {
        NotificationProviderRegistry registry = new NotificationProviderRegistry(List.of(
                new SmtpNotificationAdapter(command -> { })));
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("host", "mail.mine.local");
        configuration.put("port", 587);
        configuration.put("securityMode", "STARTTLS");
        configuration.put("fromAddress", "alarm@example.com");
        configuration.put("fromName", null);

        registry.validate(new NotificationAdapterKey(NotificationChannelType.EMAIL, "smtp"),
                configuration, Map.of(), true);
    }

    @Test
    void Provider目录必须稳定排序且只暴露字段元数据() {
        NotificationProviderRegistry registry = new NotificationProviderRegistry(List.of(
                adapter("wecom-robot", NotificationChannelType.ENTERPRISE_IM),
                adapter("smtp", NotificationChannelType.EMAIL)));

        assertThat(registry.catalog())
                .extracting(definition -> definition.key().provider())
                .containsExactly("smtp", "wecom-robot");
        assertThat(registry.catalog().getFirst().secretFields())
                .extracting(NotificationProviderDefinition.Field::key)
                .containsExactly("password");
    }

    @Test
    void 平台发行版固定包含七种内置Provider() throws Exception {
        NotificationHttpTransport transport = request ->
                new NotificationHttpTransport.Response(200, "{}", Map.of());
        NotificationEndpointPolicy endpointPolicy = new NotificationEndpointPolicy(
                host -> List.of(InetAddress.getByName("8.8.8.8")));
        var json = JsonMapper.builder().build();
        NotificationProviderRegistry registry = new NotificationProviderRegistry(List.of(
                new SmtpNotificationAdapter(command -> { }),
                new WeComRobotNotificationAdapter(transport, endpointPolicy, json),
                new DingTalkRobotNotificationAdapter(transport, endpointPolicy, json),
                new FeishuRobotNotificationAdapter(transport, endpointPolicy, json),
                new GenericWebhookNotificationAdapter(transport, endpointPolicy, json),
                new AliyunSmsNotificationAdapter(
                        command -> new AliyunSmsGateway.Response("OK", "biz"), json),
                new AliyunVoiceNotificationAdapter(
                        command -> new AliyunVoiceGateway.Response("OK", "call"), json)));

        assertThat(registry.catalog())
                .extracting(definition -> definition.key().provider())
                .containsExactly(
                        "aliyun-sms", "aliyun-voice", "dingtalk-robot", "feishu-robot",
                        "generic-webhook", "smtp", "wecom-robot");
    }

    private NotificationChannelAdapter adapter(
            String provider,
            NotificationChannelType channelType
    ) {
        NotificationProviderDefinition definition = new NotificationProviderDefinition(
                new NotificationAdapterKey(channelType, provider),
                "mine.notification.provider." + provider,
                false,
                List.of(new NotificationProviderDefinition.Field(
                        "host", NotificationProviderDefinition.FieldType.STRING,
                        true, 255, null, List.of())),
                List.of(new NotificationProviderDefinition.Field(
                        "password", NotificationProviderDefinition.FieldType.SECRET,
                        true, 512, null, List.of()))
        );
        return new NotificationChannelAdapter() {
            @Override
            public NotificationAdapterKey key() {
                return definition.key();
            }

            @Override
            public NotificationProviderDefinition definition() {
                return definition;
            }

            @Override
            public void validateConfiguration(
                    Map<String, Object> configuration,
                    Map<String, String> credentials
            ) {
                // 测试适配器不增加通用字段以外的约束。
            }

            @Override
            public NotificationDeliveryResult deliver(NotificationDeliveryRequest request) {
                return NotificationDeliveryResult.submitted(null);
            }
        };
    }
}
