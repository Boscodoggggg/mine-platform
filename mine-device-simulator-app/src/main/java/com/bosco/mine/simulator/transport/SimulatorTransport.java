package com.bosco.mine.simulator.transport;

import java.io.IOException;

/**
 * 设备模拟器传输通道统一接口。
 */
public interface SimulatorTransport extends AutoCloseable {

    /**
     * 建立连接或开始监听。
     *
     * @throws IOException 连接失败
     */
    void connect() throws IOException;

    /**
     * 发送一帧报文。
     *
     * @param payload 报文字节
     * @throws IOException 发送失败或连接不可用
     */
    void send(byte[] payload) throws IOException;

    /**
     * 断开当前连接。
     */
    void disconnect();

    /**
     * 查询当前连接状态。
     *
     * @return 连接状态
     */
    TransportState state();

    @Override
    default void close() {
        disconnect();
    }
}
