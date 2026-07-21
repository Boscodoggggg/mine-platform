package com.bosco.mine.simulator.transport;

/**
 * 传输连接状态。
 */
public enum TransportState {

    /** 尚未连接。 */
    DISCONNECTED,

    /** 正在建立连接。 */
    CONNECTING,

    /** 服务端已监听，正在等待平台接入。 */
    WAITING,

    /** 已建立连接，可以发送报文。 */
    CONNECTED,

    /** 最近一次连接或发送发生错误。 */
    FAILED
}
