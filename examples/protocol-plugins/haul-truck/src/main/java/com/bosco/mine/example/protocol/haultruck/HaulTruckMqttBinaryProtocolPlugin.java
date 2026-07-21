package com.bosco.mine.example.protocol.haultruck;

import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryDecodeContext;
import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryDecodedMessage;
import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryProtocolException;
import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryProtocolPlugin;
import com.bosco.mine.protocol.sdk.tcp.TcpDecodedMessage;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolException;

/**
 * 使用矿卡黄金帧格式解码完整 MQTT 二进制 Payload 的示例插件。
 */
public final class HaulTruckMqttBinaryProtocolPlugin implements MqttBinaryProtocolPlugin {

    /** 创建无状态示例插件。 */
    public HaulTruckMqttBinaryProtocolPlugin() {
    }

    @Override
    public String protocolKey() {
        return "haul-truck-mqtt-binary";
    }

    @Override
    public String protocolVersion() {
        return "haul-truck-mqtt-binary:v1";
    }

    @Override
    public MqttBinaryDecodedMessage decode(MqttBinaryDecodeContext context, byte[] payload) {
        try {
            TcpDecodedMessage decoded = new HaulTruckProtocolSession().decode(payload);
            if (!context.productKey().equals(decoded.productKey())
                    || !context.deviceCode().equals(decoded.deviceCode())) {
                throw failure(MqttBinaryProtocolException.ErrorCode.INVALID_VALUE,
                        "示例协议 Payload 身份与可信上下文不一致");
            }
            return new MqttBinaryDecodedMessage(
                    decoded.bootId(), decoded.sequence(), decoded.occurredAt(),
                    decoded.replayed(), decoded.properties()
            );
        } catch (TcpProtocolException exception) {
            throw new MqttBinaryProtocolException(
                    map(exception.errorCode()), exception.getMessage(), exception);
        }
    }

    private MqttBinaryProtocolException.ErrorCode map(TcpProtocolException.ErrorCode code) {
        return switch (code) {
            case FRAME_TOO_SHORT -> MqttBinaryProtocolException.ErrorCode.PAYLOAD_TOO_SHORT;
            case BAD_MAGIC -> MqttBinaryProtocolException.ErrorCode.BAD_MAGIC;
            case UNSUPPORTED_VERSION -> MqttBinaryProtocolException.ErrorCode.UNSUPPORTED_VERSION;
            case UNSUPPORTED_MESSAGE_TYPE ->
                    MqttBinaryProtocolException.ErrorCode.UNSUPPORTED_MESSAGE_TYPE;
            case LENGTH_MISMATCH -> MqttBinaryProtocolException.ErrorCode.LENGTH_MISMATCH;
            case CRC_MISMATCH -> MqttBinaryProtocolException.ErrorCode.CRC_MISMATCH;
            case INVALID_IDENTITY, INVALID_VALUE ->
                    MqttBinaryProtocolException.ErrorCode.INVALID_VALUE;
        };
    }

    private MqttBinaryProtocolException failure(
            MqttBinaryProtocolException.ErrorCode code,
            String message
    ) {
        return new MqttBinaryProtocolException(code, message);
    }
}
