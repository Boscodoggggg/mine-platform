package com.bosco.mine.server.modules.notification.security;

import java.util.UUID;

/**
 * 对通知渠道凭据和接收目标执行带上下文认证的可逆加密。
 */
public interface NotificationSecretCipher {

    /**
     * 主动加载并校验通知独立密钥配置。
     */
    void validateConfiguration();

    /**
     * 加密通知敏感值。
     *
     * @param ownerId 密文所属渠道或接收对象标识
     * @param purpose 密文用途
     * @param configurationVersion 绑定的配置版本号
     * @param plaintext 待加密明文
     * @return 带算法版本的认证密文
     */
    String encrypt(
            UUID ownerId,
            SecretPurpose purpose,
            long configurationVersion,
            String plaintext
    );

    /**
     * 解密并认证通知敏感值。
     *
     * @param ownerId 密文所属渠道或接收对象标识
     * @param purpose 密文用途
     * @param configurationVersion 绑定的配置版本号
     * @param ciphertext 带算法版本的认证密文
     * @return 解密后的明文
     */
    String decrypt(
            UUID ownerId,
            SecretPurpose purpose,
            long configurationVersion,
            String ciphertext
    );

    /**
     * 区分渠道凭据和接收目标，防止合法密文跨用途替换。
     */
    enum SecretPurpose {
        /** 供应商访问密钥、签名密钥或邮件认证凭据。 */
        CHANNEL_CREDENTIAL,
        /** 手机号、邮箱地址或机器人地址等接收目标。 */
        RECIPIENT_TARGET
    }
}
