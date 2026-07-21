package com.bosco.mine.protocol.engine.tcp;

import com.bosco.mine.protocol.sdk.tcp.TcpDecodedMessage;
import com.bosco.mine.protocol.sdk.tcp.TcpFrameDefinition;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolPlugin;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolSession;

import java.nio.ByteOrder;

/**
 * 仅供 ServiceLoader 自动发现测试使用的无状态插件。
 */
public final class TestTcpProtocolPlugin implements TcpProtocolPlugin {

    /** 创建测试插件。 */
    public TestTcpProtocolPlugin() {
    }

    @Override
    public String protocolKey() {
        return "test-tcp-binary";
    }

    @Override
    public String protocolVersion() {
        return "test-tcp-binary:v1";
    }

    @Override
    public TcpFrameDefinition frameDefinition() {
        return new TcpFrameDefinition(ByteOrder.BIG_ENDIAN, 256, 4, 2, -6, 0);
    }

    @Override
    public TcpProtocolSession openSession() {
        return new TcpProtocolSession() {
            @Override
            public TcpDecodedMessage decode(byte[] frame) {
                throw new UnsupportedOperationException("本测试不执行解码");
            }

            @Override
            public byte[] encodeAcknowledgement(TcpDecodedMessage message) {
                throw new UnsupportedOperationException("本测试不执行确认编码");
            }
        };
    }
}
