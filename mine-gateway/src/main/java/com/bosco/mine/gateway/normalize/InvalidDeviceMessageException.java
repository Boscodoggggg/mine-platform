package com.bosco.mine.gateway.normalize;

/**
 * 表示原始设备报文因身份、结构或资源限制不满足要求而不可重试。
 */
public final class InvalidDeviceMessageException extends RuntimeException {

    /**
     * 创建不可重试的设备报文异常。
     *
     * @param message 对现场运维有意义且不包含密钥或完整原文的原因
     */
    public InvalidDeviceMessageException(String message) {
        super(message);
    }

    /**
     * 创建带解析原因的不可重试异常。
     *
     * @param message 安全错误描述
     * @param cause 原始异常
     */
    public InvalidDeviceMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
