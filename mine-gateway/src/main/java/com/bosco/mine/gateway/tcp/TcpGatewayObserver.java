package com.bosco.mine.gateway.tcp;

/**
 * 接收 TCP 监听器固定低基数运行事件，供指标实现记录。
 */
public interface TcpGatewayObserver {

    /** 不记录任何指标的默认观察器。 */
    TcpGatewayObserver NOOP = new TcpGatewayObserver() { };

    /** 记录一个连接获得配额。 */
    default void connectionAccepted() { }

    /** 记录一个连接因固定原因被拒绝。 */
    default void connectionRejected() { }

    /** 记录一个已获得配额的连接关闭。 */
    default void connectionClosed() { }

    /** 记录一帧完成解析、可靠发布和确认编码。 */
    default void frameSucceeded() { }

    /**
     * 记录固定低基数原因的帧失败。
     *
     * @param reason 固定原因枚举文本
     */
    default void frameRejected(String reason) { }

    /** 记录内部可靠发布失败。 */
    default void publishFailed() { }

    /** 记录成功确认已提交到 Netty 出站队列。 */
    default void acknowledgementSent() { }
}
