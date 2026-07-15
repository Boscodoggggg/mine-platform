package com.bosco.mine.server.bootstrap.websocket;

/**
 * 表示实时连接令牌无效、过期或权限不足。
 */
public final class RealtimeAuthenticationException extends RuntimeException {

    /**
     * 创建实时鉴权异常。
     *
     * @param message 可安全返回给客户端的错误说明
     */
    public RealtimeAuthenticationException(String message) {
        super(message);
    }
}
