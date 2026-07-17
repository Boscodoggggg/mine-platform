package com.bosco.mine.example.protocol.haultruck;

import com.bosco.mine.protocol.sdk.tcp.TcpDecodedMessage;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolException;
import com.bosco.mine.protocol.sdk.tcp.TcpProtocolSession;

import java.math.BigDecimal;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 严格解码矿卡示例协议并为最近一次成功遥测生成一次确认。
 */
public final class HaulTruckProtocolSession implements TcpProtocolSession {

    private static final int MAGIC = 0x4d50;
    private static final int VERSION = 1;
    private static final int TELEMETRY_TYPE = 0x01;
    private static final int ACK_TYPE = 0x81;
    private static final int ACK_LENGTH = 13;
    private static final int MIN_FRAME_LENGTH = 31;
    private static final long MIN_EPOCH_SECONDS = 946684800L;
    private static final long MAX_EPOCH_SECONDS = 4102444800L;
    private static final Pattern SAFE_IDENTITY = Pattern.compile("[A-Za-z0-9_.:-]{1,32}");

    private Long pendingAcknowledgementSequence;

    /** 创建连接隔离的协议会话。 */
    public HaulTruckProtocolSession() {
    }

    @Override
    public TcpDecodedMessage decode(byte[] frame) {
        if (frame == null || frame.length < MIN_FRAME_LENGTH) {
            throw failure(TcpProtocolException.ErrorCode.FRAME_TOO_SHORT, "示例协议帧长度不足");
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        if ((buffer.getShort() & 0xffff) != MAGIC) {
            throw failure(TcpProtocolException.ErrorCode.BAD_MAGIC, "示例协议魔数不匹配");
        }
        if ((buffer.get() & 0xff) != VERSION) {
            throw failure(TcpProtocolException.ErrorCode.UNSUPPORTED_VERSION, "示例协议版本不受支持");
        }
        if ((buffer.get() & 0xff) != TELEMETRY_TYPE) {
            throw failure(TcpProtocolException.ErrorCode.UNSUPPORTED_MESSAGE_TYPE, "示例协议消息类型不受支持");
        }
        int declaredLength = buffer.getShort() & 0xffff;
        if (declaredLength != frame.length || declaredLength > 256) {
            throw failure(TcpProtocolException.ErrorCode.LENGTH_MISMATCH, "示例协议声明长度不一致");
        }
        int expectedCrc = ByteBuffer.wrap(frame, frame.length - 2, 2)
                .order(ByteOrder.BIG_ENDIAN).getShort() & 0xffff;
        if (Crc16Ccitt.calculate(frame, frame.length - 2) != expectedCrc) {
            throw failure(TcpProtocolException.ErrorCode.CRC_MISMATCH, "示例协议 CRC 校验失败");
        }

        try {
            String deviceCode = readIdentity(buffer, "deviceCode");
            String bootId = readIdentity(buffer, "bootId");
            if (buffer.remaining() != 21) {
                throw failure(TcpProtocolException.ErrorCode.LENGTH_MISMATCH, "示例协议字段长度不一致");
            }
            long sequence = Integer.toUnsignedLong(buffer.getInt());
            long epochSeconds = buffer.getLong();
            int flags = buffer.get() & 0xff;
            int rpm = buffer.getShort() & 0xffff;
            int speedDeciKph = buffer.getShort() & 0xffff;
            int payloadDeciTon = buffer.getShort() & 0xffff;
            buffer.getShort();
            validateValues(epochSeconds, flags, rpm, speedDeciKph, payloadDeciTon);
            TcpDecodedMessage message = new TcpDecodedMessage(
                    "haul-truck", deviceCode, bootId, sequence,
                    Instant.ofEpochSecond(epochSeconds), (flags & 1) == 1,
                    Map.of(
                            "engine.rpm", rpm,
                            "vehicle.speed", BigDecimal.valueOf(speedDeciKph, 1),
                            "vehicle.payload", BigDecimal.valueOf(payloadDeciTon, 1)
                    )
            );
            pendingAcknowledgementSequence = sequence;
            return message;
        } catch (TcpProtocolException exception) {
            throw exception;
        } catch (BufferUnderflowException exception) {
            throw new TcpProtocolException(
                    TcpProtocolException.ErrorCode.LENGTH_MISMATCH,
                    "示例协议字段长度不足", exception);
        } catch (DateTimeException exception) {
            throw new TcpProtocolException(
                    TcpProtocolException.ErrorCode.INVALID_VALUE,
                    "示例协议发生时间无效", exception);
        }
    }

    @Override
    public byte[] encodeAcknowledgement(TcpDecodedMessage message) {
        Objects.requireNonNull(message, "待确认消息不能为空");
        if (pendingAcknowledgementSequence == null
                || !pendingAcknowledgementSequence.equals(message.sequence())) {
            throw new IllegalStateException("只能确认本会话最近一次尚未确认的消息");
        }
        if (message.sequence() > 0xffff_ffffL) {
            throw new IllegalArgumentException("示例协议确认序号超出无符号 32 位范围");
        }
        ByteBuffer buffer = ByteBuffer.allocate(ACK_LENGTH).order(ByteOrder.BIG_ENDIAN);
        buffer.putShort((short) MAGIC);
        buffer.put((byte) VERSION);
        buffer.put((byte) ACK_TYPE);
        buffer.putShort((short) ACK_LENGTH);
        buffer.putInt(message.sequence().intValue());
        buffer.put((byte) 0);
        buffer.putShort((short) Crc16Ccitt.calculate(buffer.array(), ACK_LENGTH - 2));
        pendingAcknowledgementSequence = null;
        return buffer.array();
    }

    private String readIdentity(ByteBuffer buffer, String field) {
        if (!buffer.hasRemaining()) {
            throw failure(TcpProtocolException.ErrorCode.LENGTH_MISMATCH, "示例协议身份长度缺失");
        }
        int length = buffer.get() & 0xff;
        if (length < 1 || length > 32 || buffer.remaining() < length) {
            throw failure(TcpProtocolException.ErrorCode.INVALID_IDENTITY,
                    "示例协议 " + field + " 长度不合法");
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        String value = new String(bytes, StandardCharsets.US_ASCII);
        if (!SAFE_IDENTITY.matcher(value).matches()) {
            throw failure(TcpProtocolException.ErrorCode.INVALID_IDENTITY,
                    "示例协议 " + field + " 格式不合法");
        }
        return value;
    }

    private void validateValues(
            long epochSeconds,
            int flags,
            int rpm,
            int speedDeciKph,
            int payloadDeciTon
    ) {
        if (epochSeconds < MIN_EPOCH_SECONDS || epochSeconds > MAX_EPOCH_SECONDS
                || (flags & ~1) != 0
                || rpm > 10000
                || speedDeciKph > 2500
                || payloadDeciTon > 2000) {
            throw failure(TcpProtocolException.ErrorCode.INVALID_VALUE, "示例协议业务字段超出安全范围");
        }
    }

    private TcpProtocolException failure(TcpProtocolException.ErrorCode code, String message) {
        return new TcpProtocolException(code, message);
    }
}
