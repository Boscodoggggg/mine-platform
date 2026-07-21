package com.bosco.mine.server.modules.notification.security;

/**
 * 表示通知密钥加载、密文格式或完整性认证失败。
 */
public final class NotificationSecretCipherException extends RuntimeException {

    /**
     * 创建不包含通知明文的安全异常。
     *
     * @param message 固定安全错误说明
     * @param cause 原始异常
     */
    public NotificationSecretCipherException(String message, Throwable cause) {
        super(message, cause);
    }
}
