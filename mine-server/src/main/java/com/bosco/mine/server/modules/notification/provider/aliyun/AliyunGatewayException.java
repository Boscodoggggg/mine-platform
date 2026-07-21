package com.bosco.mine.server.modules.notification.provider.aliyun;

/**
 * 表示官方 SDK 未返回可判定业务响应，异常中不携带凭据与请求正文。
 */
public final class AliyunGatewayException extends RuntimeException {

    /** 创建脱敏的阿里云网关异常。 */
    public AliyunGatewayException(Throwable cause) {
        super("阿里云通知调用未获得可判定业务响应", cause);
    }
}
