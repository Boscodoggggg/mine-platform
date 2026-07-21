package com.bosco.mine.server.modules.notification.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * 使用独立 AES-256-GCM 密钥保护通知渠道凭据和接收目标。
 */
@Component
@EnableConfigurationProperties(NotificationSecretProperties.class)
public class AesGcmNotificationSecretCipher implements NotificationSecretCipher {

    private static final String VERSION = "v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String BLIND_INDEX_ALGORITHM = "HmacSHA256";
    private static final String BLIND_INDEX_VERSION = "notification-blind-index-v1";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_PLAINTEXT_BYTES = 32 * 1024;
    private static final int MAX_CIPHERTEXT_LENGTH = 64 * 1024;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final String keyFile;
    private final SecureRandom secureRandom = new SecureRandom();
    private volatile SecretKeySpec key;

    /**
     * 创建从外部 secret 文件延迟加载独立通知密钥的加密器。
     *
     * @param properties 通知密钥配置
     */
    @Autowired
    public AesGcmNotificationSecretCipher(NotificationSecretProperties properties) {
        this.keyFile = Objects.requireNonNull(properties, "通知密钥配置不能为空")
                .resolvedKeyFile();
    }

    /**
     * 仅供同包单元测试使用固定密钥创建加密器。
     *
     * @param rawKey 二百五十六位测试密钥
     */
    AesGcmNotificationSecretCipher(byte[] rawKey) {
        this.keyFile = null;
        this.key = keySpec(rawKey);
    }

    /** 主动加载并校验 AES-256 通知密钥。 */
    @Override
    public void validateConfiguration() {
        key();
    }

    /** 加密通知敏感值并为每次调用生成独立随机数。 */
    @Override
    public String encrypt(
            UUID ownerId,
            SecretPurpose purpose,
            long configurationVersion,
            String plaintext
    ) {
        validateContext(ownerId, purpose, configurationVersion);
        Objects.requireNonNull(plaintext, "待加密通知敏感值不能为空");
        byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
        if (plainBytes.length > MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("待加密通知敏感值超过长度上限");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(ownerId, purpose, configurationVersion));
            byte[] encrypted = cipher.doFinal(plainBytes);
            return VERSION + ":" + ENCODER.encodeToString(nonce)
                    + ":" + ENCODER.encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new NotificationSecretCipherException("通知敏感配置加密失败", exception);
        }
    }

    /** 解密通知敏感值并拒绝错误上下文、错误密钥和任何密文篡改。 */
    @Override
    public String decrypt(
            UUID ownerId,
            SecretPurpose purpose,
            long configurationVersion,
            String ciphertext
    ) {
        try {
            validateContext(ownerId, purpose, configurationVersion);
            String encoded = Objects.requireNonNull(ciphertext, "待解密通知密文不能为空");
            if (encoded.length() > MAX_CIPHERTEXT_LENGTH) {
                throw new IllegalArgumentException("通知密文超过长度上限");
            }
            String[] parts = encoded.split(":", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("不支持的通知密文格式");
            }
            byte[] nonce = DECODER.decode(parts[1]);
            byte[] encrypted = DECODER.decode(parts[2]);
            if (nonce.length != NONCE_BYTES || encrypted.length < TAG_BITS / Byte.SIZE) {
                throw new IllegalArgumentException("通知密文结构无效");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(ownerId, purpose, configurationVersion));
            byte[] plaintext = cipher.doFinal(encrypted);
            if (plaintext.length > MAX_PLAINTEXT_BYTES) {
                throw new IllegalArgumentException("通知明文超过长度上限");
            }
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (AEADBadTagException exception) {
            throw new NotificationSecretCipherException("通知敏感配置认证失败", exception);
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException exception) {
            throw new NotificationSecretCipherException("通知敏感配置密文无效", exception);
        }
    }

    /**
     * 使用独立通知密钥生成带用途域隔离的 HMAC-SHA-256 盲索引。
     */
    @Override
    public String blindIndex(SecretPurpose purpose, String normalizedValue) {
        Objects.requireNonNull(purpose, "通知敏感值用途不能为空");
        Objects.requireNonNull(normalizedValue, "通知敏感值不能为空");
        byte[] valueBytes = normalizedValue.getBytes(StandardCharsets.UTF_8);
        if (valueBytes.length == 0) {
            throw new IllegalArgumentException("通知敏感值不能为空");
        }
        if (valueBytes.length > MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("通知敏感值超过长度上限");
        }
        try {
            Mac mac = Mac.getInstance(BLIND_INDEX_ALGORITHM);
            mac.init(new SecretKeySpec(key().getEncoded(), BLIND_INDEX_ALGORITHM));
            mac.update((BLIND_INDEX_VERSION + "|" + purpose.name() + "|")
                    .getBytes(StandardCharsets.US_ASCII));
            return "sha256:" + HexFormat.of().formatHex(mac.doFinal(valueBytes));
        } catch (GeneralSecurityException exception) {
            throw new NotificationSecretCipherException("通知敏感值盲索引生成失败", exception);
        }
    }

    /**
     * 校验密文认证上下文，禁止负数配置版本。
     */
    private void validateContext(
            UUID ownerId,
            SecretPurpose purpose,
            long configurationVersion
    ) {
        Objects.requireNonNull(ownerId, "通知敏感值所有者不能为空");
        Objects.requireNonNull(purpose, "通知敏感值用途不能为空");
        if (configurationVersion < 0) {
            throw new IllegalArgumentException("通知敏感配置版本不能为负数");
        }
    }

    /**
     * 构造绑定密文版本、所有者、用途和配置版本的认证附加数据。
     */
    private byte[] aad(UUID ownerId, SecretPurpose purpose, long configurationVersion) {
        return (VERSION + "|" + ownerId + "|" + purpose.name() + "|" + configurationVersion)
                .getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * 延迟加载并缓存通知独立密钥。
     */
    private SecretKeySpec key() {
        SecretKeySpec current = key;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (key == null) {
                key = readKey(keyFile);
            }
            return key;
        }
    }

    /**
     * 从指定位置读取 Base64 编码的通知独立密钥。
     */
    private SecretKeySpec readKey(String location) {
        try {
            String encoded = readKeyText(location).trim();
            return keySpec(Base64.getDecoder().decode(encoded));
        } catch (IOException | IllegalArgumentException | NullPointerException exception) {
            throw new NotificationSecretCipherException(
                    "无法从通知 secret 文件加载 AES-256 密钥", exception);
        }
    }

    /**
     * 从外部文件或测试类路径资源读取通知密钥文本。
     */
    private String readKeyText(String location) throws IOException {
        if (!location.startsWith("classpath:")) {
            return Files.readString(Path.of(location), StandardCharsets.US_ASCII);
        }
        String resourceName = location.substring("classpath:".length());
        String normalized = resourceName.startsWith("/")
                ? resourceName.substring(1) : resourceName;
        try (InputStream input = AesGcmNotificationSecretCipher.class
                .getClassLoader().getResourceAsStream(normalized)) {
            if (input == null) {
                throw new IOException("通知 classpath 密钥资源不存在");
            }
            return new String(input.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    /**
     * 将二百五十六位原始密钥转换为不可被外部数组修改的 AES 密钥对象。
     */
    private static SecretKeySpec keySpec(byte[] rawKey) {
        Objects.requireNonNull(rawKey, "通知 AES 密钥不能为空");
        if (rawKey.length != KEY_BYTES) {
            throw new IllegalArgumentException("通知敏感配置密钥必须为 256 位");
        }
        return new SecretKeySpec(rawKey.clone(), "AES");
    }
}
