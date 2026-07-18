package com.bosco.mine.server.modules.mine.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验人员敏感字段 AES-256-GCM 密文格式和完整性保护。
 */
class AesGcmSensitiveFieldCipherTest {

    @Test
    void 相同明文每次加密必须使用随机随机数并可正确解密() {
        AesGcmSensitiveFieldCipher cipher = new AesGcmSensitiveFieldCipher(key((byte) 7));

        String first = cipher.encrypt("13800138000");
        String second = cipher.encrypt("13800138000");

        assertThat(first).startsWith("v1:").isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("13800138000");
        assertThat(cipher.decrypt(second)).isEqualTo("13800138000");
    }

    @Test
    void 错误密钥与篡改密文必须被认证标签拒绝() {
        AesGcmSensitiveFieldCipher encryptor = new AesGcmSensitiveFieldCipher(key((byte) 11));
        AesGcmSensitiveFieldCipher wrongKey = new AesGcmSensitiveFieldCipher(key((byte) 12));
        String ciphertext = encryptor.encrypt("410105198901011234");
        String[] parts = ciphertext.split(":", -1);
        byte[] encrypted = Base64.getUrlDecoder().decode(parts[2]);
        encrypted[0] ^= 1;
        String tampered = parts[0] + ":" + parts[1] + ":"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);

        assertThatThrownBy(() -> wrongKey.decrypt(ciphertext))
                .isInstanceOf(SensitiveFieldCipher.SensitiveFieldCipherException.class);
        assertThatThrownBy(() -> encryptor.decrypt(tampered))
                .isInstanceOf(SensitiveFieldCipher.SensitiveFieldCipherException.class);
    }

    @Test
    void 非二百五十六位密钥必须在构造时拒绝() {
        assertThatThrownBy(() -> new AesGcmSensitiveFieldCipher(
                "sixteen-byte-key".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256");
    }

    private byte[] key(byte value) {
        byte[] result = new byte[32];
        java.util.Arrays.fill(result, value);
        return result;
    }
}
