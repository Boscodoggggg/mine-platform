package com.bosco.mine.server.bootstrap.websocket;

/**
 * 表示实时连接尚未完成 AUTH 动作。
 */
public final class RealtimeAuthenticationRequiredException extends RuntimeException {

    /**
     * 创建未鉴权异常。
     */
    public RealtimeAuthenticationRequiredException() {
        super("连接尚未鉴权");
    }
}
