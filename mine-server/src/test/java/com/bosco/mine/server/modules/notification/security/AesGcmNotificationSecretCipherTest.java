package com.bosco.mine.server.modules.notification.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验通知凭据和接收目标的 AES-256-GCM 加密边界。
 */
class AesGcmNotificationSecretCipherTest {

    private static final String SECRET = "sms-access-secret-value";

    @TempDir
    Path tempDirectory;

    @Test
    void 相同明文必须产生不同密文并可在相同上下文解密() {
        AesGcmNotificationSecretCipher cipher =
                new AesGcmNotificationSecretCipher(key((byte) 7));
        UUID channelId = UUID.randomUUID();

        String first = cipher.encrypt(channelId,
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL, 3, SECRET);
        String second = cipher.encrypt(channelId,
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL, 3, SECRET);

        assertThat(first).startsWith("v1:").isNotEqualTo(second);
        assertThat(cipher.decrypt(channelId,
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL, 3, first))
                .isEqualTo(SECRET);
        assertThat(cipher.decrypt(channelId,
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL, 3, second))
                .isEqualTo(SECRET);
    }

    @Test
    void 合法Provider聚合凭据必须能在统一上限内加解密() {
        AesGcmNotificationSecretCipher cipher =
                new AesGcmNotificationSecretCipher(key((byte) 9));
        UUID channelId = UUID.randomUUID();
        String aggregateCredentials = "{\"endpointUrl\":\"https://hooks.example.com/"
                + "x".repeat(2_000) + "\",\"bearerToken\":\""
                + "y".repeat(8_000) + "\",\"signingSecret\":\""
                + "z".repeat(4_000) + "\"}";

        String encrypted = cipher.encrypt(channelId,
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL,
                0, aggregateCredentials);

        assertThat(cipher.decrypt(channelId,
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL,
                0, encrypted)).isEqualTo(aggregateCredentials);
    }

    @Test
    void 密文必须绑定所有者用途和配置版本() {
        AesGcmNotificationSecretCipher cipher =
                new AesGcmNotificationSecretCipher(key((byte) 11));
        UUID channelId = UUID.randomUUID();
        String ciphertext = cipher.encrypt(channelId,
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL, 8, SECRET);

        assertAuthenticationRejected(() -> cipher.decrypt(UUID.randomUUID(),
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL, 8, ciphertext));
        assertAuthenticationRejected(() -> cipher.decrypt(channelId,
                NotificationSecretCipher.SecretPurpose.RECIPIENT_TARGET, 8, ciphertext));
        assertAuthenticationRejected(() -> cipher.decrypt(channelId,
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL, 9, ciphertext));
    }

    @Test
    void 错误密钥和篡改必须被拒绝且异常不得泄露明文() {
        AesGcmNotificationSecretCipher encryptor =
                new AesGcmNotificationSecretCipher(key((byte) 17));
        AesGcmNotificationSecretCipher wrongKey =
                new AesGcmNotificationSecretCipher(key((byte) 18));
        UUID recipientId = UUID.randomUUID();
        String ciphertext = encryptor.encrypt(recipientId,
                NotificationSecretCipher.SecretPurpose.RECIPIENT_TARGET, 0, SECRET);
        String[] parts = ciphertext.split(":", -1);
        byte[] encrypted = Base64.getUrlDecoder().decode(parts[2]);
        encrypted[0] ^= 1;
        String tampered = parts[0] + ":" + parts[1] + ":"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);

        assertAuthenticationRejected(() -> wrongKey.decrypt(recipientId,
                NotificationSecretCipher.SecretPurpose.RECIPIENT_TARGET, 0, ciphertext));
        assertAuthenticationRejected(() -> encryptor.decrypt(recipientId,
                NotificationSecretCipher.SecretPurpose.RECIPIENT_TARGET, 0, tampered));
    }

    @Test
    void 未知版本和非法上下文必须被安全拒绝() {
        AesGcmNotificationSecretCipher cipher =
                new AesGcmNotificationSecretCipher(key((byte) 23));

        assertAuthenticationRejected(() -> cipher.decrypt(UUID.randomUUID(),
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL, 0, "v2:bad:data"));
        assertThatThrownBy(() -> cipher.encrypt(UUID.randomUUID(),
                NotificationSecretCipher.SecretPurpose.CHANNEL_CREDENTIAL, -1, SECRET))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void 非二百五十六位密钥必须在构造时拒绝() {
        assertThatThrownBy(() -> new AesGcmNotificationSecretCipher(
                "sixteen-byte-key".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void 外部密钥文件缺失或内容不合法必须在主动校验时失败() throws Exception {
        Path invalidKey = tempDirectory.resolve("invalid-key.secret");
        Files.writeString(invalidKey,
                Base64.getEncoder().encodeToString(new byte[16]), StandardCharsets.US_ASCII);
        AesGcmNotificationSecretCipher missing = new AesGcmNotificationSecretCipher(
                new NotificationSecretProperties(tempDirectory.resolve("missing.secret").toString()));
        AesGcmNotificationSecretCipher invalid = new AesGcmNotificationSecretCipher(
                new NotificationSecretProperties(invalidKey.toString()));

        assertThatThrownBy(missing::validateConfiguration)
                .isInstanceOf(NotificationSecretCipherException.class)
                .hasMessageContaining("密钥")
                .hasMessageNotContaining(SECRET);
        assertThatThrownBy(invalid::validateConfiguration)
                .isInstanceOf(NotificationSecretCipherException.class)
                .hasMessageContaining("密钥")
                .hasMessageNotContaining(SECRET);
    }

    private void assertAuthenticationRejected(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(NotificationSecretCipherException.class)
                .hasMessageNotContaining(SECRET);
    }

    private byte[] key(byte value) {
        byte[] result = new byte[32];
        Arrays.fill(result, value);
        return result;
    }
}
