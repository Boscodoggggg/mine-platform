package com.bosco.mine.server.modules.notification.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通知敏感配置加密参数。
 *
 * @param keyFile AES-256 独立密钥 secret 文件路径
 */
@ConfigurationProperties("mine.notification.security")
public record NotificationSecretProperties(String keyFile) {

    /**
     * 返回通知密钥文件路径，未配置时使用固定容器 secret 挂载点。
     *
     * @return 通知密钥文件路径
     */
    public String resolvedKeyFile() {
        return keyFile == null || keyFile.isBlank()
                ? "/run/secrets/mine_notification_data_key"
                : keyFile;
    }
}
