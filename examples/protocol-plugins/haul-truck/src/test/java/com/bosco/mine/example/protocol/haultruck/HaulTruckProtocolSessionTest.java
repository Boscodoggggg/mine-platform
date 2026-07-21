package com.bosco.mine.example.protocol.haultruck;

import com.bosco.mine.protocol.sdk.tcp.TcpDecodedMessage;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolException;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolPlugin;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolSession;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用可复现黄金帧校验矿卡示例协议的严格解码和应用确认。
 */
class HaulTruckProtocolSessionTest {

    private static final String GOLDEN_HEX = """
            4d 50 01 01 00 33 09 74 72 75 63 6b 2d 30 30 31
            0d 62 6f 6f 74 2d 32 30 32 36 30 37 31 37 00 00
            00 2a 00 00 00 00 6a 59 7f 0b 00 05 46 00 7d 01
            c8 25 4a
            """;

    @Test
    void 应解码黄金遥测帧并生成带CRC的成功确认() {
        TcpProtocolPlugin plugin = new HaulTruckProtocolPlugin();
        TcpProtocolSession session = plugin.openSession();
        byte[] golden = frame(
                "truck-001", "boot-20260717", 42, 1784250123L,
                0, 1350, 125, 456
        );

        assertThat(golden).isEqualTo(HexFormat.of().parseHex(GOLDEN_HEX.replaceAll("\\s", "")));
        TcpDecodedMessage message = session.decode(golden);

        assertThat(plugin.protocolKey()).isEqualTo("haul-truck-binary");
        assertThat(plugin.protocolVersion()).isEqualTo("haul-truck-binary:v1");
        assertThat(plugin.frameDefinition().maxFrameLength()).isEqualTo(256);
        assertThat(message.productKey()).isEqualTo("haul-truck");
        assertThat(message.deviceCode()).isEqualTo("truck-001");
        assertThat(message.bootId()).isEqualTo("boot-20260717");
        assertThat(message.sequence()).isEqualTo(42);
        assertThat(message.occurredAt()).isEqualTo(Instant.ofEpochSecond(1784250123L));
        assertThat(message.replayed()).isFalse();
        assertThat(message.properties())
                .containsEntry("engine.rpm", 1350)
                .containsEntry("vehicle.speed", new BigDecimal("12.5"))
                .containsEntry("vehicle.payload", new BigDecimal("45.6"));

        byte[] acknowledgement = session.encodeAcknowledgement(message);
        assertThat(acknowledgement).hasSize(13);
        ByteBuffer ack = ByteBuffer.wrap(acknowledgement).order(ByteOrder.BIG_ENDIAN);
        assertThat(ack.getShort() & 0xffff).isEqualTo(0x4d50);
        assertThat(ack.get() & 0xff).isEqualTo(1);
        assertThat(ack.get() & 0xff).isEqualTo(0x81);
        assertThat(ack.getShort() & 0xffff).isEqualTo(13);
        assertThat(Integer.toUnsignedLong(ack.getInt())).isEqualTo(42);
        assertThat(ack.get() & 0xff).isZero();
        assertThat(ack.getShort() & 0xffff).isEqualTo(crc(acknowledgement, 11));
    }

    @Test
    void 应保留无符号序号和补传标志() {
        TcpDecodedMessage message = new HaulTruckProtocolPlugin().openSession().decode(frame(
                "truck-001", "boot-02", 0xffff_ffffL, 1784250123L,
                1, 900, 1, 0
        ));

        assertThat(message.sequence()).isEqualTo(0xffff_ffffL);
        assertThat(message.replayed()).isTrue();
    }

    @Test
    void 应拒绝CRC魔数版本类型和声明长度错误() {
        assertError(mutated(0, 0x00), TcpProtocolException.ErrorCode.BAD_MAGIC);
        assertError(mutated(2, 0x02), TcpProtocolException.ErrorCode.UNSUPPORTED_VERSION);
        assertError(mutated(3, 0x02), TcpProtocolException.ErrorCode.UNSUPPORTED_MESSAGE_TYPE);
        assertError(mutated(5, 0x01), TcpProtocolException.ErrorCode.LENGTH_MISMATCH);

        byte[] crcBroken = golden();
        crcBroken[10] ^= 0x01;
        assertError(crcBroken, TcpProtocolException.ErrorCode.CRC_MISMATCH);
    }

    @Test
    void 应拒绝截断帧非法身份标志和越界业务值() {
        assertError(new byte[8], TcpProtocolException.ErrorCode.FRAME_TOO_SHORT);

        byte[] identity = golden();
        identity[6] = 0;
        refreshCrc(identity);
        assertError(identity, TcpProtocolException.ErrorCode.INVALID_IDENTITY);

        byte[] flags = golden();
        int flagsIndex = flags.length - 9;
        flags[flagsIndex] = 2;
        refreshCrc(flags);
        assertError(flags, TcpProtocolException.ErrorCode.INVALID_VALUE);

        byte[] rpm = golden();
        int rpmIndex = rpm.length - 8;
        ByteBuffer.wrap(rpm).order(ByteOrder.BIG_ENDIAN).putShort(rpmIndex, (short) 12001);
        refreshCrc(rpm);
        assertError(rpm, TcpProtocolException.ErrorCode.INVALID_VALUE);
    }

    @Test
    void 应通过标准SPI发现示例插件() {
        assertThat(ServiceLoader.load(TcpProtocolPlugin.class).stream()
                .map(provider -> provider.get().protocolKey()))
                .contains("haul-truck-binary");
    }

    private void assertError(byte[] frame, TcpProtocolException.ErrorCode errorCode) {
        assertThatThrownBy(() -> new HaulTruckProtocolPlugin().openSession().decode(frame))
                .isInstanceOf(TcpProtocolException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }

    private byte[] mutated(int index, int value) {
        byte[] frame = golden();
        frame[index] = (byte) value;
        refreshCrc(frame);
        return frame;
    }

    private byte[] golden() {
        return frame("truck-001", "boot-20260717", 42, 1784250123L,
                0, 1350, 125, 456);
    }

    private byte[] frame(
            String deviceCode,
            String bootId,
            long sequence,
            long epochSeconds,
            int flags,
            int rpm,
            int speedDeciKph,
            int payloadDeciTon
    ) {
        byte[] device = deviceCode.getBytes(StandardCharsets.US_ASCII);
        byte[] boot = bootId.getBytes(StandardCharsets.US_ASCII);
        int length = 6 + 1 + device.length + 1 + boot.length + 4 + 8 + 1 + 2 + 2 + 2 + 2;
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
        buffer.putShort((short) 0x4d50);
        buffer.put((byte) 1);
        buffer.put((byte) 1);
        buffer.putShort((short) length);
        buffer.put((byte) device.length).put(device);
        buffer.put((byte) boot.length).put(boot);
        buffer.putInt((int) sequence);
        buffer.putLong(epochSeconds);
        buffer.put((byte) flags);
        buffer.putShort((short) rpm);
        buffer.putShort((short) speedDeciKph);
        buffer.putShort((short) payloadDeciTon);
        buffer.putShort((short) crc(buffer.array(), length - 2));
        return buffer.array();
    }

    private void refreshCrc(byte[] frame) {
        int value = crc(frame, frame.length - 2);
        ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
                .putShort(frame.length - 2, (short) value);
    }

    private int crc(byte[] bytes, int length) {
        int crc = 0xffff;
        for (int index = 0; index < length; index++) {
            crc ^= (bytes[index] & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xffff;
            }
        }
        return crc;
    }
}
