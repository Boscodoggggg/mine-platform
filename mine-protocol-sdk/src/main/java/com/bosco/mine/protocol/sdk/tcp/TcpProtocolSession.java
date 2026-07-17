package com.bosco.mine.protocol.sdk.tcp;

/**
 * 表示一个 TCP 连接独占的协议解码和确认编码上下文。
 */
public interface TcpProtocolSession {

    /**
     * 解码已经由网关完成边界切分的完整帧。
     *
     * @param frame 完整帧的独立字节副本
     * @return 标准遥测中间结果
     * @throws TcpProtocolException 帧不符合协议时抛出受控异常
     */
    TcpDecodedMessage decode(byte[] frame);

    /**
     * 在平台可靠接收遥测后生成应用级成功确认。
     *
     * @param message 本会话刚解码并已可靠发布的消息
     * @return 完整确认帧的独立字节数组
     */
    byte[] encodeAcknowledgement(TcpDecodedMessage message);
}
