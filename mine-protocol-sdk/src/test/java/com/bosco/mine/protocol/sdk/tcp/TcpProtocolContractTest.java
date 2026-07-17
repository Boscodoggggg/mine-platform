package com.bosco.mine.protocol.sdk.tcp;

import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验第三方 TCP 协议插件可依赖的稳定契约和输入边界。
 */
class TcpProtocolContractTest {

    @Test
    void 应接受有界长度字段拆帧定义() {
        TcpFrameDefinition definition = new TcpFrameDefinition(
                ByteOrder.BIG_ENDIAN, 256, 4, 2, -6, 0
        );

        assertThat(definition.byteOrder()).isEqualTo(ByteOrder.BIG_ENDIAN);
        assertThat(definition.maxFrameLength()).isEqualTo(256);
        assertThat(definition.lengthFieldOffset()).isEqualTo(4);
        assertThat(definition.lengthFieldLength()).isEqualTo(2);
        assertThat(definition.lengthAdjustment()).isEqualTo(-6);
        assertThat(definition.initialBytesToStrip()).isZero();
    }

    @Test
    void 应拒绝无界或不受支持的拆帧定义() {
        assertThatThrownBy(() -> new TcpFrameDefinition(
                ByteOrder.BIG_ENDIAN, 31, 4, 2, -6, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最大帧");
        assertThatThrownBy(() -> new TcpFrameDefinition(
                ByteOrder.BIG_ENDIAN, 1024 * 1024 + 1, 4, 2, -6, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最大帧");
        assertThatThrownBy(() -> new TcpFrameDefinition(
                ByteOrder.BIG_ENDIAN, 256, 4, 3, -6, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("长度字段");
        assertThatThrownBy(() -> new TcpFrameDefinition(
                ByteOrder.BIG_ENDIAN, 256, -1, 2, -6, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("偏移");
    }

    @Test
    void 解码消息应递归隔离插件提供的可变属性() {
        List<Object> samples = new ArrayList<>(List.of(1, 2));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("samples", samples);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("engine", nested);

        TcpDecodedMessage message = new TcpDecodedMessage(
                "haul-truck", "truck-001", "boot-01", 7L,
                Instant.parse("2026-07-17T01:02:03Z"), false, properties
        );
        samples.add(3);
        nested.put("tampered", true);
        properties.put("other", 9);

        assertThat(message.properties()).containsOnlyKeys("engine");
        Map<?, ?> nestedView = (Map<?, ?>) message.properties().get("engine");
        assertThat(nestedView).isEqualTo(Map.of("samples", List.of(1, 2)));
        assertThatThrownBy(() -> message.properties().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 解码消息应拒绝非法身份和不支持的属性值() {
        assertThatThrownBy(() -> new TcpDecodedMessage(
                "bad product", "truck-001", "boot-01", 1L,
                Instant.EPOCH, false, Map.of("rpm", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productKey");
        assertThatThrownBy(() -> new TcpDecodedMessage(
                "haul-truck", "truck-001", "boot-01", 1L,
                Instant.EPOCH, false, Map.of("bad", new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("属性值类型");
    }

    @Test
    void 协议异常应只暴露固定错误码() {
        TcpProtocolException exception = new TcpProtocolException(
                TcpProtocolException.ErrorCode.CRC_MISMATCH, "CRC 校验失败"
        );

        assertThat(exception.errorCode()).isEqualTo(TcpProtocolException.ErrorCode.CRC_MISMATCH);
        assertThat(TcpProtocolException.ErrorCode.values()).containsExactly(
                TcpProtocolException.ErrorCode.FRAME_TOO_SHORT,
                TcpProtocolException.ErrorCode.BAD_MAGIC,
                TcpProtocolException.ErrorCode.UNSUPPORTED_VERSION,
                TcpProtocolException.ErrorCode.UNSUPPORTED_MESSAGE_TYPE,
                TcpProtocolException.ErrorCode.LENGTH_MISMATCH,
                TcpProtocolException.ErrorCode.CRC_MISMATCH,
                TcpProtocolException.ErrorCode.INVALID_IDENTITY,
                TcpProtocolException.ErrorCode.INVALID_VALUE
        );
    }
}
