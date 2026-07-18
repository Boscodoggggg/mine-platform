package com.bosco.mine.server.modules.mine.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * 使用 AES-256-GCM 加密人员敏感字段，密文格式为版本、随机数和带认证标签密文。
 */
@Component
@EnableConfigurationProperties(MineSensitiveDataProperties.class)
public class AesGcmSensitiveFieldCipher implements SensitiveFieldCipher {

    private static final String VERSION = "v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final Path keyFile;
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

    /** 加密敏感字段并为每次调用生成独立随机数。 */
    @Override
    public String encrypt(String plaintext) {
        Objects.requireNonNull(plaintext, "待加密字段不能为空");
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(VERSION.getBytes(StandardCharsets.US_ASCII));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION + ":" + ENCODER.encodeToString(nonce) + ":" + ENCODER.encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new SensitiveFieldCipherException("人员敏感字段加密失败", exception);
        }
    }

    /** 解密字段并拒绝错误密钥、未知版本和任何密文篡改。 */
    @Override
    public String decrypt(String ciphertext) {
        try {
            String[] parts = Objects.requireNonNull(ciphertext, "待解密字段不能为空").split(":", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("不支持的密文格式");
            }
            byte[] nonce = DECODER.decode(parts[1]);
            byte[] encrypted = DECODER.decode(parts[2]);
            if (nonce.length != NONCE_BYTES) {
                throw new IllegalArgumentException("密文随机数长度无效");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(VERSION.getBytes(StandardCharsets.US_ASCII));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (AEADBadTagException exception) {
            throw new SensitiveFieldCipherException("人员敏感字段认证失败", exception);
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException exception) {
            throw new SensitiveFieldCipherException("人员敏感字段密文无效", exception);
        }
    }

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

    private SecretKeySpec readKey(Path path) {
        try {
            String encoded = Files.readString(path, StandardCharsets.US_ASCII).trim();
            return keySpec(Base64.getDecoder().decode(encoded));
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("无法从人员数据 secret 文件加载 AES-256 密钥", exception);
        }
    }

    private static SecretKeySpec keySpec(byte[] rawKey) {
        Objects.requireNonNull(rawKey, "AES 密钥不能为空");
        if (rawKey.length != KEY_BYTES) {
            throw new IllegalArgumentException("人员敏感字段密钥必须为 256 位");
        }
        return new SecretKeySpec(rawKey.clone(), "AES");
    }
}
