package com.bosco.mine.server.modules.mine.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * 使用 AES-256-GCM 加密人员敏感字段，密文格式为版本、随机数和带认证标签密文。
 */
@Component
@EnableConfigurationProperties(MineSensitiveDataProperties.class)
public class AesGcmSensitiveFieldCipher implements SensitiveFieldCipher {

    private static final String VERSION = "v2";
    private static final String LEGACY_VERSION = "v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final String keyFile;
    private final SecureRandom secureRandom = new SecureRandom();
    private volatile SecretKeySpec key;

    /**
     * 创建从外部 secret 文件延迟加载密钥的加密器。
     *
     * @param properties 敏感数据配置
     */
    @Autowired
    public AesGcmSensitiveFieldCipher(MineSensitiveDataProperties properties) {
        this.keyFile = Objects.requireNonNull(properties, "敏感数据配置不能为空").resolvedKeyFile();
    }

    /**
     * 仅供同包单元测试使用固定密钥创建加密器。
     *
     * @param rawKey 二百五十六位测试密钥
     */
    AesGcmSensitiveFieldCipher(byte[] rawKey) {
        this.keyFile = null;
        this.key = keySpec(rawKey);
    }

    /** 主动加载并校验 AES-256 密钥。 */
    @Override
    public void validateConfiguration() {
        key();
    }

    /** 加密敏感字段并为每次调用生成独立随机数。 */
    @Override
    public String encrypt(UUID personId, FieldType fieldType, String plaintext) {
        Objects.requireNonNull(personId, "人员标识不能为空");
        Objects.requireNonNull(fieldType, "敏感字段类型不能为空");
        Objects.requireNonNull(plaintext, "待加密字段不能为空");
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(VERSION, personId, fieldType));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION + ":" + ENCODER.encodeToString(nonce) + ":" + ENCODER.encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new SensitiveFieldCipherException("人员敏感字段加密失败", exception);
        }
    }

    /** 解密字段并拒绝错误密钥、未知版本和任何密文篡改。 */
    @Override
    public String decrypt(UUID personId, FieldType fieldType, String ciphertext) {
        try {
            Objects.requireNonNull(personId, "人员标识不能为空");
            Objects.requireNonNull(fieldType, "敏感字段类型不能为空");
            String[] parts = Objects.requireNonNull(ciphertext, "待解密字段不能为空").split(":", -1);
            if (parts.length != 3
                    || (!VERSION.equals(parts[0]) && !LEGACY_VERSION.equals(parts[0]))) {
                throw new IllegalArgumentException("不支持的密文格式");
            }
            byte[] nonce = DECODER.decode(parts[1]);
            byte[] encrypted = DECODER.decode(parts[2]);
            if (nonce.length != NONCE_BYTES) {
                throw new IllegalArgumentException("密文随机数长度无效");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(LEGACY_VERSION.equals(parts[0])
                    ? LEGACY_VERSION.getBytes(StandardCharsets.US_ASCII)
                    : aad(VERSION, personId, fieldType));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (AEADBadTagException exception) {
            throw new SensitiveFieldCipherException("人员敏感字段认证失败", exception);
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException exception) {
            throw new SensitiveFieldCipherException("人员敏感字段密文无效", exception);
        }
    }

    /**
     * 构造绑定密文版本、人员和字段类型的认证附加数据。
     */
    private byte[] aad(String version, UUID personId, FieldType fieldType) {
        return (version + "|" + personId + "|" + fieldType.name())
                .getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * 延迟加载并缓存人员敏感字段密钥。
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
     * 从指定位置读取 Base64 编码密钥并校验长度。
     */
    private SecretKeySpec readKey(String location) {
        try {
            String encoded = readKeyText(location).trim();
            return keySpec(Base64.getDecoder().decode(encoded));
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("无法从人员数据 secret 文件加载 AES-256 密钥", exception);
        }
    }

    /**
     * 从外部文件或测试类路径资源读取密钥文本。
     */
    private String readKeyText(String location) throws IOException {
        if (!location.startsWith("classpath:")) {
            return Files.readString(java.nio.file.Path.of(location), StandardCharsets.US_ASCII);
        }
        String resourceName = location.substring("classpath:".length());
        String normalized = resourceName.startsWith("/") ? resourceName.substring(1) : resourceName;
        try (InputStream input = AesGcmSensitiveFieldCipher.class
                .getClassLoader().getResourceAsStream(normalized)) {
            if (input == null) {
                throw new IOException("人员数据 classpath 密钥资源不存在");
            }
            return new String(input.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    /**
     * 将二百五十六位原始密钥转换为不可被外部数组修改的 AES 密钥对象。
     */
    private static SecretKeySpec keySpec(byte[] rawKey) {
        Objects.requireNonNull(rawKey, "AES 密钥不能为空");
        if (rawKey.length != KEY_BYTES) {
            throw new IllegalArgumentException("人员敏感字段密钥必须为 256 位");
        }
        return new SecretKeySpec(rawKey.clone(), "AES");
    }
}
