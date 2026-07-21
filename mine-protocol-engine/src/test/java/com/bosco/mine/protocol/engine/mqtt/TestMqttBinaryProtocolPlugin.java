package com.bosco.mine.protocol.engine.mqtt;

import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryDecodeContext;
import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryDecodedMessage;
import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryProtocolPlugin;

/**
 * 供标准 ServiceLoader 测试发现的最小 MQTT 二进制插件。
 */
public final class TestMqttBinaryProtocolPlugin implements MqttBinaryProtocolPlugin {

    /** 创建测试插件。 */
    public TestMqttBinaryProtocolPlugin() {
    }

    @Override
    public String protocolKey() {
        return "test-mqtt-binary";
    }

    @Override
    public String protocolVersion() {
        return "test-mqtt-binary:v1";
    }

    @Override
    public MqttBinaryDecodedMessage decode(MqttBinaryDecodeContext context, byte[] payload) {
        throw new UnsupportedOperationException("本测试不执行协议解码");
    }
}
