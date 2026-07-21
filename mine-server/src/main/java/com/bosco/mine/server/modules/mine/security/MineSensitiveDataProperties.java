package com.bosco.mine.server.modules.mine.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 人员敏感字段加密参数。
 *
 * @param keyFile AES-256 密钥 secret 文件路径
 */
@ConfigurationProperties("mine.sensitive-data")
public record MineSensitiveDataProperties(String keyFile) {

    /**
     * 返回密钥文件路径，未配置时固定使用容器 secret 挂载点。
     *
     * @return 密钥文件路径
     */
    public String resolvedKeyFile() {
        return keyFile == null || keyFile.isBlank()
                ? "/run/secrets/mine_person_data_key"
                : keyFile;
    }
}
