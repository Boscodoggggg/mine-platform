package com.bosco.mine.server.modules.notification.provider;

import com.bosco.mine.server.modules.notification.adapter.NotificationAdapterKey;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelAdapter;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryRequest;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryResult;
import org.junit.jupiter.api.Test;

import java.util.List;
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
