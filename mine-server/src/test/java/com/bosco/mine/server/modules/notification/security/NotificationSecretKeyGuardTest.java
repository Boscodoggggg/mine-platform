package com.bosco.mine.server.modules.notification.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationSecretKeyGuardTest {

    private NotificationSecretKeyGuardMapper mapper;
    private NotificationSecretCipher cipher;
    private NotificationLegacySecretMigrator legacySecretMigrator;
    private NotificationSecretKeyGuard guard;

    @BeforeEach
    void 创建密钥门禁测试环境() {
        mapper = mock(NotificationSecretKeyGuardMapper.class);
        cipher = mock(NotificationSecretCipher.class);
        legacySecretMigrator = mock(NotificationLegacySecretMigrator.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        guard = new NotificationSecretKeyGuard(
                mapper, cipher, legacySecretMigrator, transactionManager);
    }

    @Test
    void 首次启动必须先验证既有密文再建立身份哨兵() {
        UUID channelId = UUID.randomUUID();
        when(mapper.findVerificationCiphertext()).thenReturn(Optional.empty());
        when(mapper.findCredentialSample()).thenReturn(Optional.of(
                new NotificationSecretKeyGuardMapper.CredentialSample(
                        channelId, 3, "v1:sample:ciphertext")));
        when(cipher.encrypt(any(), eq(NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL),
                eq(0L), any())).thenReturn("v1:guard:ciphertext");
        when(mapper.insertVerificationCiphertext("v1:guard:ciphertext")).thenReturn(1);

        guard.initialize();

        verify(cipher).validateConfiguration();
        verify(cipher).decrypt(channelId,
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL,
                3, "v1:sample:ciphertext");
        verify(mapper).insertVerificationCiphertext("v1:guard:ciphertext");
        verify(legacySecretMigrator).migrate();
    }

    @Test
    void 已有哨兵必须使用当前密钥解密并校验固定明文() {
        when(mapper.findVerificationCiphertext()).thenReturn(Optional.of("v1:guard:ciphertext"));
        when(cipher.decrypt(any(), eq(NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL),
                eq(0L), eq("v1:guard:ciphertext")))
                .thenReturn("mine-notification-data-key-verification-v1");

        guard.initialize();

        verify(mapper, never()).insertVerificationCiphertext(any());
        verify(legacySecretMigrator).migrate();
    }

    @Test
    void 错误密钥身份必须阻断启动() {
        when(mapper.findVerificationCiphertext()).thenReturn(Optional.of("v1:guard:ciphertext"));
        when(cipher.decrypt(any(), eq(NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL),
                anyLong(), any())).thenReturn("wrong-key-marker");

        assertThatThrownBy(guard::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("身份校验失败");
    }
}
