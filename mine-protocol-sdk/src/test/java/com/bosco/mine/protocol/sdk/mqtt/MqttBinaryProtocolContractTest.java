package com.bosco.mine.protocol.sdk.mqtt;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验 MQTT 二进制插件可依赖的稳定契约和输入边界。
 */
class MqttBinaryProtocolContractTest {

    @Test
    void 应提供可信设备上下文且不允许空身份() {
        MqttBinaryDecodeContext context = new MqttBinaryDecodeContext(
                "haul-truck", "credential-001", "truck-001"
        );

        assertThat(context.productKey()).isEqualTo("haul-truck");
        assertThat(context.clientId()).isEqualTo("credential-001");
        assertThat(context.deviceCode()).isEqualTo("truck-001");
        assertThatThrownBy(() -> new MqttBinaryDecodeContext(
                "bad product", "credential-001", "truck-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productKey");
    }

    @Test
    void 解码结果应递归隔离插件提供的可变属性() {
        List<Object> samples = new ArrayList<>(List.of(1, 2));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("samples", samples);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("engine", nested);

        MqttBinaryDecodedMessage message = new MqttBinaryDecodedMessage(
                "boot-1", 7L, Instant.parse("2026-07-19T00:00:00Z"), false, properties
        );
        samples.add(3);
        nested.put("tampered", true);
        properties.put("other", 9);

        assertThat(message.properties()).containsOnlyKeys("engine");
        assertThat(message.properties().get("engine"))
                .isEqualTo(Map.of("samples", List.of(1, 2)));
        assertThatThrownBy(() -> message.properties().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 解码结果应拒绝非法序号和不支持的属性值() {
        assertThatThrownBy(() -> new MqttBinaryDecodedMessage(
                "boot-1", -1L, Instant.EPOCH, false, Map.of("rpm", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence");
        assertThatThrownBy(() -> new MqttBinaryDecodedMessage(
                "boot-1", 1L, Instant.EPOCH, false, Map.of("bad", new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("属性值类型");
    }

    @Test
    void 协议异常应只暴露固定错误码() {
        MqttBinaryProtocolException exception = new MqttBinaryProtocolException(
                MqttBinaryProtocolException.ErrorCode.CRC_MISMATCH, "CRC 校验失败"
        );

        assertThat(exception.errorCode())
                .isEqualTo(MqttBinaryProtocolException.ErrorCode.CRC_MISMATCH);
        assertThat(MqttBinaryProtocolException.ErrorCode.values()).containsExactly(
                MqttBinaryProtocolException.ErrorCode.PAYLOAD_TOO_SHORT,
                MqttBinaryProtocolException.ErrorCode.BAD_MAGIC,
                MqttBinaryProtocolException.ErrorCode.UNSUPPORTED_VERSION,
                MqttBinaryProtocolException.ErrorCode.UNSUPPORTED_MESSAGE_TYPE,
                MqttBinaryProtocolException.ErrorCode.LENGTH_MISMATCH,
                MqttBinaryProtocolException.ErrorCode.CRC_MISMATCH,
                MqttBinaryProtocolException.ErrorCode.INVALID_VALUE
        );
    }
}
