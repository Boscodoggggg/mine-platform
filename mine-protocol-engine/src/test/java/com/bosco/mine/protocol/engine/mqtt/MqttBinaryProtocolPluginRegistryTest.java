package com.bosco.mine.protocol.engine.mqtt;

import com.bosco.mine.protocol.engine.ProtocolValidationException;
import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryDecodeContext;
import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryDecodedMessage;
import com.bosco.mine.protocol.sdk.mqtt.MqttBinaryProtocolPlugin;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验 MQTT 二进制插件发现、兼容性和原子注册门禁。
 */
class MqttBinaryProtocolPluginRegistryTest {

    @Test
    void 应通过标准ServiceLoader发现插件() {
        MqttBinaryProtocolPluginRegistry registry = MqttBinaryProtocolPluginRegistry.load(
                Thread.currentThread().getContextClassLoader()
        );

        assertThat(registry.require("test-mqtt-binary").protocolVersion())
                .isEqualTo("test-mqtt-binary:v1");
        assertThat(registry.protocolKeys()).containsExactly("test-mqtt-binary");
    }

    @Test
    void 应支持显式插件集合并拒绝未知键() {
        MqttBinaryProtocolPlugin plugin = plugin("haul-truck-mqtt-binary", 1);
        MqttBinaryProtocolPluginRegistry registry =
                new MqttBinaryProtocolPluginRegistry(List.of(plugin));

        assertThat(registry.find("haul-truck-mqtt-binary")).containsSame(plugin);
        assertThatThrownBy(() -> registry.require("missing"))
                .isInstanceOf(ProtocolValidationException.class)
                .hasMessageContaining("未安装");
    }

    @Test
    void 应拒绝重复键API不兼容和非法元数据() {
        assertThatThrownBy(() -> new MqttBinaryProtocolPluginRegistry(List.of(
                plugin("haul-truck-mqtt-binary", 1), plugin("haul-truck-mqtt-binary", 1))))
                .isInstanceOf(ProtocolValidationException.class)
                .extracting("code")
                .isEqualTo("MQTT_BINARY_PLUGIN_DUPLICATE_KEY");
        assertThatThrownBy(() -> new MqttBinaryProtocolPluginRegistry(List.of(
                plugin("haul-truck-mqtt-binary", 2))))
                .isInstanceOf(ProtocolValidationException.class)
                .extracting("code")
                .isEqualTo("MQTT_BINARY_PLUGIN_API_INCOMPATIBLE");
        assertThatThrownBy(() -> new MqttBinaryProtocolPluginRegistry(List.of(
                plugin("Bad Key", 1))))
                .isInstanceOf(ProtocolValidationException.class)
                .extracting("code")
                .isEqualTo("MQTT_BINARY_PLUGIN_KEY_INVALID");
        assertThatThrownBy(() -> new MqttBinaryProtocolPluginRegistry(Arrays.asList(
                plugin("haul-truck-mqtt-binary", 1), null)))
                .isInstanceOf(ProtocolValidationException.class)
                .extracting("code")
                .isEqualTo("MQTT_BINARY_PLUGIN_INVALID");
    }

    private MqttBinaryProtocolPlugin plugin(String key, int apiVersion) {
        return new StubPlugin(key, key + ":v1", apiVersion);
    }

    private record StubPlugin(String protocolKey, String protocolVersion, int apiMajorVersion)
            implements MqttBinaryProtocolPlugin {

        @Override
        public MqttBinaryDecodedMessage decode(
                MqttBinaryDecodeContext context,
                byte[] payload
        ) {
            throw new UnsupportedOperationException("本测试不执行协议解码");
        }
    }
}
