package com.bosco.mine.example.protocol.haultruck;

import com.bosco.mine.protocol.sdk.tcp.TcpFrameDefinition;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolPlugin;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolSession;

import java.nio.ByteOrder;

/**
 * 提供可复现矿卡遥测帧的示例 TCP 二进制协议插件。
 *
 * <p>该协议用于验证平台插件纵向链路，不对应任何真实厂商或行业标准。</p>
 */
public final class HaulTruckProtocolPlugin implements TcpProtocolPlugin {

    private static final TcpFrameDefinition FRAME_DEFINITION = new TcpFrameDefinition(
            ByteOrder.BIG_ENDIAN, 256, 4, 2, -6, 0
    );

    /** 创建无状态示例插件。 */
    public HaulTruckProtocolPlugin() {
    }

    @Override
    public String protocolKey() {
        return "haul-truck-binary";
    }

    @Override
    public String protocolVersion() {
        return "haul-truck-binary:v1";
    }

    @Override
    public TcpFrameDefinition frameDefinition() {
        return FRAME_DEFINITION;
    }

    @Override
    public TcpProtocolSession openSession() {
        return new HaulTruckProtocolSession();
    }
}
