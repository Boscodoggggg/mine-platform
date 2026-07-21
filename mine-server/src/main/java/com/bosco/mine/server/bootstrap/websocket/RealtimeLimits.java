package com.bosco.mine.server.bootstrap.websocket;

/**
 * 单个实时连接的资源限制。
 *
 * @param maxSubscriptions 最大订阅条目数
 * @param maxDevices 最大设备数
 * @param maxProperties 最大属性数
 * @param maxMessageBytes 最大客户端文本帧字节数
 */
public record RealtimeLimits(
        int maxSubscriptions,
        int maxDevices,
        int maxProperties,
        int maxMessageBytes
) {

    /**
     * 校验实时连接限制。
     */
    public RealtimeLimits {
        if (maxSubscriptions < 1 || maxDevices < 1 || maxProperties < 1 || maxMessageBytes < 256) {
            throw new IllegalArgumentException("实时连接限制必须为正数且消息上限不得小于 256 字节");
        }
    }
}
