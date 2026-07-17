package com.bosco.mine.gateway.tcp;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.example.protocol.haultruck.HaulTruckProtocolPlugin;
import com.bosco.mine.gateway.publish.TelemetryPublisher;
import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验 TCP 半包粘包、可信会话绑定和可靠发布后确认语义。
 */
class TcpDeviceChannelHandlerTest {

    private static final byte[] GOLDEN = HexFormat.of().parseHex("""
            4d500101003309747275636b2d3030310d626f6f742d3230323630373137
            0000002a000000006a597f0b000546007d01c8254a
            """.replaceAll("\\s", ""));

    @Test
    void 半包应等待完整帧且发布确认返回后才写ACK() {
        AtomicBoolean confirmed = new AtomicBoolean();
        EmbeddedChannel[] holder = new EmbeddedChannel[1];
        TelemetryPublisher publisher = envelope -> {
            assertThat((Object) holder[0].readOutbound()).isNull();
            confirmed.set(true);
        };
        EmbeddedChannel channel = channel(publisher);
        holder[0] = channel;

        channel.writeInbound(Unpooled.wrappedBuffer(GOLDEN, 0, 20));
        assertThat(confirmed).isFalse();
        assertThat((Object) channel.readOutbound()).isNull();

        channel.writeInbound(Unpooled.wrappedBuffer(GOLDEN, 20, GOLDEN.length - 20));

        assertThat(confirmed).isTrue();
        ByteBuf acknowledgement = channel.readOutbound();
        assertThat(bytes(acknowledgement)).hasSize(13);
        acknowledgement.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void 粘包应逐帧发布并分别确认() {
        AtomicInteger published = new AtomicInteger();
        EmbeddedChannel channel = channel(envelope -> published.incrementAndGet());
        byte[] sticky = new byte[GOLDEN.length * 2];
        System.arraycopy(GOLDEN, 0, sticky, 0, GOLDEN.length);
        System.arraycopy(GOLDEN, 0, sticky, GOLDEN.length, GOLDEN.length);

        channel.writeInbound(Unpooled.wrappedBuffer(sticky));

        assertThat(published).hasValue(2);
        ByteBuf first = channel.readOutbound();
        ByteBuf second = channel.readOutbound();
        assertThat(bytes(first)).hasSize(13);
        assertThat(bytes(second)).hasSize(13);
        first.release();
        second.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void 同一连接切换设备身份应关闭且不得发布第二帧() {
        AtomicInteger published = new AtomicInteger();
        EmbeddedChannel channel = channel(envelope -> published.incrementAndGet());
        channel.writeInbound(Unpooled.wrappedBuffer(GOLDEN));
        ByteBuf firstAck = channel.readOutbound();
        firstAck.release();

        channel.writeInbound(Unpooled.wrappedBuffer(withDevice("truck-002")));

        assertThat(published).hasValue(1);
        assertThat(channel.isOpen()).isFalse();
        assertThat((Object) channel.readOutbound()).isNull();
        channel.finishAndReleaseAll();
    }

    @Test
    void 发布失败应关闭连接且绝不发送ACK() {
        EmbeddedChannel channel = channel(envelope -> {
            throw new IllegalStateException("publisher confirm 超时");
        });

        channel.writeInbound(Unpooled.wrappedBuffer(GOLDEN));

        assertThat(channel.isOpen()).isFalse();
        assertThat((Object) channel.readOutbound()).isNull();
        channel.finishAndReleaseAll();
    }

    @Test
    void CRC错误和读空闲均应关闭连接且不发布() {
        AtomicInteger published = new AtomicInteger();
        EmbeddedChannel crcChannel = channel(envelope -> published.incrementAndGet());
        byte[] broken = GOLDEN.clone();
        broken[10] ^= 1;

        crcChannel.writeInbound(Unpooled.wrappedBuffer(broken));

        assertThat(crcChannel.isOpen()).isFalse();
        assertThat(published).hasValue(0);
        crcChannel.finishAndReleaseAll();

        EmbeddedChannel idleChannel = channel(envelope -> published.incrementAndGet());
        idleChannel.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT);
        assertThat(idleChannel.isOpen()).isFalse();
        idleChannel.finishAndReleaseAll();
    }

    @Test
    void 超过插件上限的声明长度应由拆帧器直接关闭() {
        EmbeddedChannel channel = channel(envelope -> { });
        byte[] oversizedHeader = {0x4d, 0x50, 0x01, 0x01, 0x01, 0x01};

        channel.writeInbound(Unpooled.wrappedBuffer(oversizedHeader));

        assertThat(channel.isOpen()).isFalse();
        assertThat((Object) channel.readOutbound()).isNull();
        channel.finishAndReleaseAll();
    }

    private EmbeddedChannel channel(TelemetryPublisher publisher) {
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        registry.activate(1, "a".repeat(64), List.of(
                identity("tcp-001", "019f63e2-071f-706a-bd12-4dad7150e75d", "truck-001"),
                identity("tcp-002", "019f63e2-071f-706a-bd12-4dad7150e75e", "truck-002")
        ));
        TcpMessageNormalizer normalizer = new TcpMessageNormalizer(
                registry, IdGenerator.timeOrdered(), Clock.systemUTC());
        return new EmbeddedChannel(new TcpChannelInitializer(
                new HaulTruckProtocolPlugin(), normalizer, publisher, 60
        ));
    }

    private DeviceSessionRegistry.DeviceIdentity identity(
            String clientId,
            String deviceId,
            String deviceCode
    ) {
        return new DeviceSessionRegistry.DeviceIdentity(
                "haul-truck", clientId, deviceId, deviceCode,
                "mine-site-001", "haul-truck-binary:v1"
        );
    }

    private byte[] withDevice(String deviceCode) {
        byte[] frame = GOLDEN.clone();
        byte[] replacement = deviceCode.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(replacement, 0, frame, 7, replacement.length);
        refreshCrc(frame);
        return frame;
    }

    private void refreshCrc(byte[] frame) {
        int crc = 0xffff;
        for (int index = 0; index < frame.length - 2; index++) {
            crc ^= (frame[index] & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xffff;
            }
        }
        ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
                .putShort(frame.length - 2, (short) crc);
    }

    private byte[] bytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }
}
