package com.bosco.mine.server.modules.notification.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.notification.adapter.NotificationAdapterKey;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelAdapter;
import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryRequest;
import com.bosco.mine.server.modules.notification.adapter.NotificationDeliveryResult;
import com.bosco.mine.server.modules.notification.dto.NotificationChannelRequest;
import com.bosco.mine.server.modules.notification.dto.NotificationChannelResponse;
import com.bosco.mine.server.modules.notification.dto.NotificationChannelSecretsResponse;
import com.bosco.mine.server.modules.notification.model.NotificationChannel;
import com.bosco.mine.server.modules.notification.provider.NotificationProviderDefinition;
import com.bosco.mine.server.modules.notification.provider.NotificationProviderRegistry;
import com.bosco.mine.server.modules.notification.repository.NotificationChannelMapper;
import com.bosco.mine.server.modules.notification.security.NotificationSecretCipher;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验数据库通知渠道配置、密钥版本绑定和乐观锁规则。
 */
class NotificationChannelServiceTest {

    private NotificationChannelMapper mapper;
    private NotificationSecretCipher cipher;
    private OperationAuditService auditService;
    private NotificationChannelService service;
    private NotificationProviderRegistry registry;

    @BeforeEach
    void 创建通知渠道服务测试环境() {
        mapper = mock(NotificationChannelMapper.class);
        cipher = mock(NotificationSecretCipher.class);
        auditService = mock(OperationAuditService.class);
        registry = new NotificationProviderRegistry(List.of(smtpAdapter(), webhookAdapter()));
        service = new NotificationChannelService(
                mapper, registry, cipher, auditService, new ObjectMapper());
    }

    @Test
    void 创建启用渠道必须加密凭据且响应不得暴露密文() {
        when(cipher.encrypt(any(), eq(NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL),
                eq(0L), any())).thenReturn("v1:nonce:ciphertext");
        when(mapper.findByCode("critical-email"))
                .thenReturn(Optional.empty(), Optional.of(channel(
                        UUID.randomUUID(), "critical-email", "ENABLED",
                        "v1:nonce:ciphertext", 0)));
        when(mapper.insert(any(), any())).thenReturn(1);

        NotificationChannelResponse response = service.create(request(
                "critical-email", "ENABLED", Map.of("password", "mail-secret"), 0));

        ArgumentCaptor<NotificationChannel> captor = ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mapper).insert(captor.capture(), any());
        assertThat(captor.getValue().secretCiphertext()).isEqualTo("v1:nonce:ciphertext");
        assertThat(response.secretConfigured()).isTrue();
        assertThat(response.configuration()).containsEntry("host", "mail.mine.local");
        assertThat(response.toString()).doesNotContain("mail-secret", "v1:nonce:ciphertext");
    }

    @Test
    void 修改渠道必须解密旧版本并使用新版本重新加密完整凭据() {
        UUID channelId = UUID.randomUUID();
        NotificationChannel existing = channel(
                channelId, "critical-email", "DISABLED", "v1:old:cipher", 2);
        NotificationChannel saved = channel(
                channelId, "critical-email", "ENABLED", "v1:new:cipher", 3);
        when(mapper.findById(channelId)).thenReturn(Optional.of(existing), Optional.of(saved));
        when(cipher.decrypt(channelId,
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL,
                2, "v1:old:cipher")).thenReturn("{\"password\":\"old\"}");
        when(cipher.encrypt(eq(channelId),
                eq(NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL),
                eq(3L), any())).thenReturn("v1:new:cipher");
        when(mapper.update(any(), eq(2L), any())).thenReturn(1);

        NotificationChannelResponse response = service.update(channelId, request(
                "critical-email", "ENABLED", Map.of("password", "new"), 2));

        verify(cipher).decrypt(channelId,
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL,
                2, "v1:old:cipher");
        verify(cipher).encrypt(eq(channelId),
                eq(NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL),
                eq(3L), any());
        assertThat(response.version()).isEqualTo(3);
    }

    @Test
    void 未注册Provider不得写入数据库() {
        NotificationChannelRequest request = new NotificationChannelRequest(
                "unknown-sms", "未知短信", NotificationChannelType.SMS,
                "unknown-sms", "DISABLED", Map.of(), Map.of(), 3000, 5000, 0);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(MineException.class)
                .hasMessageContaining("供应商");
    }

    @Test
    void 乐观锁冲突必须返回稳定业务异常() {
        UUID channelId = UUID.randomUUID();
        when(mapper.findById(channelId)).thenReturn(Optional.of(channel(
                channelId, "critical-email", "DISABLED", null, 4)));
        when(mapper.update(any(), eq(4L), any())).thenReturn(0);

        assertThatThrownBy(() -> service.update(channelId, request(
                "critical-email", "DISABLED", Map.of(), 4)))
                .isInstanceOf(MineException.class)
                .hasMessageContaining("刷新");
    }

    @Test
    void 聚合凭据超过加密上限必须返回稳定配置错误() {
        when(mapper.findByCode("oversized-email")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(
                "oversized-email", "ENABLED", Map.of("password", "x".repeat(32 * 1024)), 0)))
                .isInstanceOf(MineException.class)
                .hasMessageContaining("配置不合法");
    }

    @Test
    void 响应必须兜底移除误放在非敏感配置中的Provider凭据字段() {
        UUID channelId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-21T00:00:00Z");
        when(mapper.findById(channelId)).thenReturn(Optional.of(new NotificationChannel(
                channelId,
                "legacy-webhook",
                "历史 Webhook",
                NotificationChannelType.WEBHOOK,
                "generic-webhook",
                "DISABLED",
                "{\"allowedHosts\":[\"hooks.example.com\"],"
                        + "\"endpointUrl\":\"https://hooks.example.com/secret\"}",
                null,
                3000,
                5000,
                null,
                now,
                now,
                0
        )));

        NotificationChannelResponse response = service.get(channelId);

        assertThat(response.configuration())
                .containsKey("allowedHosts")
                .doesNotContainKey("endpointUrl");
        assertThat(response.toString()).doesNotContain("hooks.example.com/secret");
    }

    @Test
    void 显式读取渠道凭据必须解密真实值并写入无敏感值审计() {
        UUID channelId = UUID.randomUUID();
        NotificationChannel existing = channel(
                channelId, "critical-email", "ENABLED", "v1:nonce:ciphertext", 3);
        when(mapper.findById(channelId)).thenReturn(Optional.of(existing));
        when(cipher.decrypt(
                channelId,
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL,
                3,
                "v1:nonce:ciphertext"
        )).thenReturn("{\"username\":\"mine-admin\",\"password\":\"mail-secret\"}");

        NotificationChannelSecretsResponse response = service.readSecrets(channelId);

        assertThat(response.channelId()).isEqualTo(channelId);
        assertThat(response.provider()).isEqualTo("smtp");
        assertThat(response.version()).isEqualTo(3);
        assertThat(response.secrets()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "username", "mine-admin",
                "password", "mail-secret"
        ));
        assertThat(response.toString()).doesNotContain("mine-admin", "mail-secret");
        verify(auditService).recordSuccess(
                "NOTIFICATION",
                "READ_NOTIFICATION_CHANNEL_SECRETS",
                "notification:channel:secret:view",
                "NOTIFICATION_CHANNEL",
                channelId,
                Map.of(),
                Map.of(
                        "provider", "smtp",
                        "secretKeys", List.of("password", "username"),
                        "result", "GRANTED"
                )
        );
    }

    private NotificationChannelRequest request(
            String code,
            String status,
            Map<String, String> secrets,
            long version
    ) {
        return new NotificationChannelRequest(
                code, "严重告警邮件", NotificationChannelType.EMAIL,
                "smtp", status,
                Map.of("host", "mail.mine.local"), secrets,
                3000, 5000, version);
    }

    private NotificationChannel channel(
            UUID id,
            String code,
            String status,
            String secretCiphertext,
            long version
    ) {
        Instant now = Instant.parse("2026-07-21T00:00:00Z");
        return new NotificationChannel(
                id, code, "严重告警邮件", NotificationChannelType.EMAIL,
                "smtp", status, "{\"host\":\"mail.mine.local\"}", secretCiphertext,
                3000, 5000, null, now, now, version);
    }

    private NotificationChannelAdapter smtpAdapter() {
        NotificationProviderDefinition definition = new NotificationProviderDefinition(
                new NotificationAdapterKey(NotificationChannelType.EMAIL, "smtp"),
                "mine.notification.provider.smtp", false,
                List.of(new NotificationProviderDefinition.Field(
                        "host", NotificationProviderDefinition.FieldType.STRING,
                        true, 255, null, List.of())),
                List.of(new NotificationProviderDefinition.Field(
                        "password", NotificationProviderDefinition.FieldType.SECRET,
                        true, 32768, null, List.of()))
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

    private NotificationChannelAdapter webhookAdapter() {
        NotificationProviderDefinition definition = new NotificationProviderDefinition(
                new NotificationAdapterKey(NotificationChannelType.WEBHOOK, "generic-webhook"),
                "mine.notification.provider.generic-webhook", false,
                List.of(new NotificationProviderDefinition.Field(
                        "allowedHosts", NotificationProviderDefinition.FieldType.STRING_LIST,
                        true, 32, null, List.of())),
                List.of(new NotificationProviderDefinition.Field(
                        "endpointUrl", NotificationProviderDefinition.FieldType.SECRET,
                        true, 2048, null, List.of()))
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
