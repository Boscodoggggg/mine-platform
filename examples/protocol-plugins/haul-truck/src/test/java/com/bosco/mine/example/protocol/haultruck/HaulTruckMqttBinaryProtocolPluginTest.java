package com.bosco.mine.example.protocol.haultruck;

import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryDecodeContext;
import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryDecodedMessage;
import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryProtocolException;
import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryProtocolPlugin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验矿卡示例 MQTT 二进制插件的黄金 Payload 和可信身份边界。
 */
class HaulTruckMqttBinaryProtocolPluginTest {

    private static final byte[] GOLDEN = HexFormat.of().parseHex("""
            4d500101003309747275636b2d3030310d626f6f742d3230323630373137
            0000002a000000006a597f0b000546007d01c8254a
            """.replaceAll("\\s", ""));

    @Test
    void 应按可信上下文解码完整MQTTPayload() {
        MqttBinaryProtocolPlugin plugin = new HaulTruckMqttBinaryProtocolPlugin();

        MqttBinaryDecodedMessage message = plugin.decode(
                new MqttBinaryDecodeContext("haul-truck", "credential-001", "truck-001"),
                GOLDEN
        );

        assertThat(plugin.protocolKey()).isEqualTo("haul-truck-mqtt-binary");
        assertThat(plugin.protocolVersion()).isEqualTo("haul-truck-mqtt-binary:v1");
        assertThat(message.sequence()).isEqualTo(42L);
        assertThat(message.properties())
                .containsEntry("engine.rpm", 1350)
                .containsEntry("vehicle.speed", new BigDecimal("12.5"));
    }

    @Test
    void 应拒绝Payload身份与可信上下文不一致() {
        assertThatThrownBy(() -> new HaulTruckMqttBinaryProtocolPlugin().decode(
                new MqttBinaryDecodeContext("haul-truck", "credential-001", "truck-999"),
                GOLDEN
        )).isInstanceOf(MqttBinaryProtocolException.class)
                .extracting("errorCode")
                .isEqualTo(MqttBinaryProtocolException.ErrorCode.INVALID_VALUE);
    }

    @Test
    void 应通过标准SPI发现示例插件() {
        assertThat(ServiceLoader.load(MqttBinaryProtocolPlugin.class).stream()
                .map(provider -> provider.get().protocolKey()))
                .contains("haul-truck-mqtt-binary");
    }
}
