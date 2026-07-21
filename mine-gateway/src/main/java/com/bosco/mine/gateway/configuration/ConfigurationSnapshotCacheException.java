package com.bosco.mine.gateway.configuration;

/**
 * 表示最后可用配置缓存无法安全读取或原子写入。
 */
public final class ConfigurationSnapshotCacheException extends RuntimeException {

    /**
     * 创建缓存异常。
     *
     * @param message 安全错误摘要
     */
    public ConfigurationSnapshotCacheException(String message) {
        super(message);
    }

    /**
     * 创建带根因的缓存异常。
     *
     * @param message 安全错误摘要
     * @param cause 根因
     */
    public ConfigurationSnapshotCacheException(String message, Throwable cause) {
        super(message, cause);
    }
}
