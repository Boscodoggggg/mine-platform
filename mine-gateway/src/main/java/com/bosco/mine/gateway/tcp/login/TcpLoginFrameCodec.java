package com.bosco.mine.gateway.tcp.login;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 编解码统一 TCP 登录帧并严格限制内存和字段边界。
 */
public final class TcpLoginFrameCodec {

    /** 登录帧最大长度。 */
    public static final int MAX_FRAME_LENGTH = 512;
    /** 登录帧长度字段相对帧起点的偏移。 */
    public static final int LENGTH_FIELD_OFFSET = 5;
    /** 登录帧长度字段字节数。 */
    public static final int LENGTH_FIELD_LENGTH = 2;

    private static final byte[] MAGIC = {0x4d, 0x4e, 0x54, 0x4c};
    private static final byte VERSION = 1;
    private static final int HEADER_LENGTH = 7;

    /**
     * 编码一条登录帧。
     *
     * @param frame 已校验登录请求
     * @return 独立字节数组
     */
    public byte[] encode(TcpLoginFrame frame) {
        if (frame == null) throw new IllegalArgumentException("TCP 登录帧不能为空");
        byte[][] fields = {
                bytes(frame.productKey()), bytes(frame.deviceCode()),
                bytes(frame.username()), bytes(frame.secret())
        };
        int length = HEADER_LENGTH + Arrays.stream(fields).mapToInt(value -> 2 + value.length).sum();
        if (length > MAX_FRAME_LENGTH) throw new IllegalArgumentException("TCP 登录帧超过长度上限");
        ByteBuffer output = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
        output.put(MAGIC).put(VERSION).putShort((short) length);
        for (byte[] field : fields) output.putShort((short) field.length).put(field);
        return output.array();
    }

    /**
     * 解码一条已经完成边界切分的登录帧。
     *
     * @param input 完整登录帧
     * @return 已校验登录请求
     */
    public TcpLoginFrame decode(byte[] input) {
        if (input == null || input.length < HEADER_LENGTH || input.length > MAX_FRAME_LENGTH) {
            throw invalid();
        }
        try {
            ByteBuffer source = ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN);
            byte[] magic = new byte[MAGIC.length];
            source.get(magic);
            int version = Byte.toUnsignedInt(source.get());
            int declaredLength = Short.toUnsignedInt(source.getShort());
            if (!Arrays.equals(MAGIC, magic) || version != VERSION || declaredLength != input.length) {
                throw invalid();
            }
            String productKey = field(source);
            String deviceCode = field(source);
            String username = field(source);
            String secret = field(source);
            if (source.hasRemaining()) throw invalid();
            return new TcpLoginFrame(productKey, deviceCode, username, secret);
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException argument
                    && "TCP 登录帧不合法".equals(argument.getMessage())) throw argument;
            throw invalid();
        }
    }

    private String field(ByteBuffer source) {
        if (source.remaining() < 2) throw invalid();
        int length = Short.toUnsignedInt(source.getShort());
        if (length == 0 || length > source.remaining()) throw invalid();
        byte[] value = new byte[length];
        source.get(value);
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException exception) {
            throw invalid();
        }
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("TCP 登录帧不合法");
    }
}
