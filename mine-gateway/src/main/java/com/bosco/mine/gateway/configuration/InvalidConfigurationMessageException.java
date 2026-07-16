package com.bosco.mine.gateway.configuration;

/**
 * 表示配置事件信封损坏或不受支持，重新投递相同消息无法恢复。
 */
public final class InvalidConfigurationMessageException extends RuntimeException {

    /**
     * 创建不可重试配置消息异常。
     *
     * @param message 安全错误摘要
     */
    public InvalidConfigurationMessageException(String message) {
        super(message);
    }

    /**
     * 创建带根因的不可重试配置消息异常。
     *
     * @param message 安全错误摘要
     * @param cause 根因
     */
    public InvalidConfigurationMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
