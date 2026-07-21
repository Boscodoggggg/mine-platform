package com.bosco.mine.gateway.configuration;

/**
 * 表示配置快照损坏、不兼容或违反单调版本规则，重试相同发布无法恢复。
 */
public final class InvalidConfigurationSnapshotException extends RuntimeException {

    /**
     * 创建不可重试配置快照异常。
     *
     * @param message 安全错误摘要
     */
    public InvalidConfigurationSnapshotException(String message) {
        super(message);
    }

    /**
     * 创建带根因的不可重试配置快照异常。
     *
     * @param message 安全错误摘要
     * @param cause 根因
     */
    public InvalidConfigurationSnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}
