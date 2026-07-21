package com.bosco.mine.gateway.configuration;

/**
 * 表示管理服务或网络暂时不可用，配置事件可以安全重新投递。
 */
public final class ConfigurationFetchException extends RuntimeException {

    /**
     * 创建配置拉取异常。
     *
     * @param message 安全错误摘要
     */
    public ConfigurationFetchException(String message) {
        super(message);
    }

    /**
     * 创建带根因的配置拉取异常。
     *
     * @param message 安全错误摘要
     * @param cause 根因
     */
    public ConfigurationFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
