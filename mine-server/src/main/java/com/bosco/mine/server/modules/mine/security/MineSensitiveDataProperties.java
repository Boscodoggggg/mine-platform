package com.bosco.mine.server.modules.mine.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * 人员敏感字段加密参数。
 *
 * @param keyFile AES-256 密钥 secret 文件路径
 */
@ConfigurationProperties("mine.sensitive-data")
public record MineSensitiveDataProperties(Path keyFile) {

    /**
     * 返回密钥文件路径，未配置时固定使用容器 secret 挂载点。
     *
     * @return 密钥文件路径
     */
    public Path resolvedKeyFile() {
        return keyFile == null ? Path.of("/run/secrets/mine_person_data_key") : keyFile;
    }
}
