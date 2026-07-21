package com.bosco.mine.server.modules.notification.security;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验历史通用 Webhook 明文端点的启动加密迁移。
 */
class NotificationLegacySecretMigratorTest {

    @Test
    void 必须把历史Webhook端点移入凭据密文并提升配置版本() {
        NotificationSecretKeyGuardMapper mapper = mock(NotificationSecretKeyGuardMapper.class);
        NotificationSecretCipher cipher = mock(NotificationSecretCipher.class);
        UUID channelId = UUID.randomUUID();
        NotificationSecretKeyGuardMapper.LegacyWebhookChannel legacy =
                new NotificationSecretKeyGuardMapper.LegacyWebhookChannel(
                        channelId,
                        4,
                        "{\"allowedHosts\":[\"hooks.example.com\"],"
                                + "\"endpointUrl\":\"https://hooks.example.com/token\"}",
                        "v1:old:ciphertext"
                );
        when(mapper.findLegacyWebhookChannels()).thenReturn(List.of(legacy), List.of());
        when(cipher.decrypt(
                channelId,
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL,
                4,
                "v1:old:ciphertext"
        )).thenReturn("{\"bearerToken\":\"existing-token\"}");
        when(cipher.encrypt(
                eq(channelId),
                eq(NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL),
                eq(5L),
                org.mockito.ArgumentMatchers.anyString()
        )).thenReturn("v1:new:ciphertext");
        when(mapper.migrateLegacyWebhookChannel(
                eq(channelId), eq(4L), org.mockito.ArgumentMatchers.anyString(),
                eq("v1:new:ciphertext"))).thenReturn(1);

        new NotificationLegacySecretMigrator(
                mapper, cipher, new ObjectMapper()).migrate();

        ArgumentCaptor<String> plaintext = ArgumentCaptor.forClass(String.class);
        verify(cipher).encrypt(
                eq(channelId),
                eq(NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL),
                eq(5L),
                plaintext.capture()
        );
        assertThat(plaintext.getValue())
                .contains("https://hooks.example.com/token", "existing-token");

        ArgumentCaptor<String> configuration = ArgumentCaptor.forClass(String.class);
        verify(mapper).migrateLegacyWebhookChannel(
                eq(channelId), eq(4L), configuration.capture(), eq("v1:new:ciphertext"));
        assertThat(configuration.getValue())
                .contains("hooks.example.com")
                .doesNotContain("endpointUrl", "/token");
    }
}
