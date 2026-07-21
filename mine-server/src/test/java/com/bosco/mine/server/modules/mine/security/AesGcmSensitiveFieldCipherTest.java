package com.bosco.mine.server.modules.mine.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验人员敏感字段 AES-256-GCM 密文格式和完整性保护。
 */
class AesGcmSensitiveFieldCipherTest {

    @Test
    void 相同明文每次加密必须使用随机随机数并可正确解密() {
        AesGcmSensitiveFieldCipher cipher = new AesGcmSensitiveFieldCipher(key((byte) 7));
        UUID personId = UUID.randomUUID();

        String first = cipher.encrypt(personId, SensitiveFieldCipher.FieldType.PHONE, "13800138000");
        String second = cipher.encrypt(personId, SensitiveFieldCipher.FieldType.PHONE, "13800138000");

        assertThat(first).startsWith("v2:").isNotEqualTo(second);
        assertThat(cipher.decrypt(personId, SensitiveFieldCipher.FieldType.PHONE, first))
                .isEqualTo("13800138000");
        assertThat(cipher.decrypt(personId, SensitiveFieldCipher.FieldType.PHONE, second))
                .isEqualTo("13800138000");
    }

    @Test
    void 错误密钥与篡改密文必须被认证标签拒绝() {
        AesGcmSensitiveFieldCipher encryptor = new AesGcmSensitiveFieldCipher(key((byte) 11));
        AesGcmSensitiveFieldCipher wrongKey = new AesGcmSensitiveFieldCipher(key((byte) 12));
        UUID personId = UUID.randomUUID();
        String ciphertext = encryptor.encrypt(
                personId, SensitiveFieldCipher.FieldType.CERTIFICATE, "410105198901011234");
        String[] parts = ciphertext.split(":", -1);
        byte[] encrypted = Base64.getUrlDecoder().decode(parts[2]);
        encrypted[0] ^= 1;
        String tampered = parts[0] + ":" + parts[1] + ":"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);

        assertThatThrownBy(() -> wrongKey.decrypt(
                personId, SensitiveFieldCipher.FieldType.CERTIFICATE, ciphertext))
                .isInstanceOf(SensitiveFieldCipher.SensitiveFieldCipherException.class);
        assertThatThrownBy(() -> encryptor.decrypt(
                personId, SensitiveFieldCipher.FieldType.CERTIFICATE, tampered))
                .isInstanceOf(SensitiveFieldCipher.SensitiveFieldCipherException.class);
    }

    @Test
    void 密文必须绑定人员与字段上下文() {
        AesGcmSensitiveFieldCipher cipher = new AesGcmSensitiveFieldCipher(key((byte) 17));
        UUID owner = UUID.randomUUID();
        String ciphertext = cipher.encrypt(
                owner, SensitiveFieldCipher.FieldType.PHONE, "13800138000");

        assertThatThrownBy(() -> cipher.decrypt(
                UUID.randomUUID(), SensitiveFieldCipher.FieldType.PHONE, ciphertext))
                .isInstanceOf(SensitiveFieldCipher.SensitiveFieldCipherException.class);
        assertThatThrownBy(() -> cipher.decrypt(
                owner, SensitiveFieldCipher.FieldType.CERTIFICATE, ciphertext))
                .isInstanceOf(SensitiveFieldCipher.SensitiveFieldCipherException.class);
    }

    @Test
    void 升级后必须继续解密已有的一版密文() throws Exception {
        byte[] key = key((byte) 19);
        AesGcmSensitiveFieldCipher cipher = new AesGcmSensitiveFieldCipher(key);
        String legacyCiphertext = legacyEncrypt(key, "13800138000");

        assertThat(cipher.decrypt(
                UUID.randomUUID(), SensitiveFieldCipher.FieldType.PHONE, legacyCiphertext))
                .isEqualTo("13800138000");
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

    private String legacyEncrypt(byte[] key, String plaintext) throws Exception {
        byte[] nonce = new byte[12];
        java.util.Arrays.fill(nonce, (byte) 3);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD("v1".getBytes(StandardCharsets.US_ASCII));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        return "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
                + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
    }
}
