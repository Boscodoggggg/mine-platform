package com.bosco.mine.protocol.sdk.tcp;

import java.nio.ByteOrder;
import java.util.Objects;

/**
 * 描述可由网关安全执行的长度字段拆帧规则。
 *
 * @param byteOrder 长度字段字节序
 * @param maxFrameLength 单帧最大字节数
 * @param lengthFieldOffset 长度字段相对帧首偏移
 * @param lengthFieldLength 长度字段字节数，只允许 1、2 或 4
 * @param lengthAdjustment 长度值到完整帧末尾的修正量
 * @param initialBytesToStrip 完成拆帧后从帧首移除的字节数
 */
public record TcpFrameDefinition(
        ByteOrder byteOrder,
        int maxFrameLength,
        int lengthFieldOffset,
        int lengthFieldLength,
        int lengthAdjustment,
        int initialBytesToStrip
) {

    /** TCP 插件允许声明的最小单帧上限。 */
    public static final int MIN_MAX_FRAME_LENGTH = 32;
    /** TCP 插件允许声明的最大单帧上限。 */
    public static final int MAX_MAX_FRAME_LENGTH = 1024 * 1024;

    /**
     * 校验拆帧规则，防止插件声明无界缓冲或越界长度字段。
     */
    public TcpFrameDefinition {
        Objects.requireNonNull(byteOrder, "字节序不能为空");
        if (maxFrameLength < MIN_MAX_FRAME_LENGTH || maxFrameLength > MAX_MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("最大帧长度必须在 32 字节到 1 MiB 之间");
        }
        if (lengthFieldOffset < 0) {
            throw new IllegalArgumentException("长度字段偏移不能为负数");
        }
        if (lengthFieldLength != 1 && lengthFieldLength != 2 && lengthFieldLength != 4) {
            throw new IllegalArgumentException("长度字段只允许 1、2 或 4 字节");
        }
        if ((long) lengthFieldOffset + lengthFieldLength > maxFrameLength) {
            throw new IllegalArgumentException("长度字段不能越过最大帧边界");
        }
        if (Math.abs((long) lengthAdjustment) > maxFrameLength) {
            throw new IllegalArgumentException("长度修正量不能超过最大帧长度");
        }
        if (initialBytesToStrip < 0 || initialBytesToStrip > maxFrameLength) {
            throw new IllegalArgumentException("帧首移除字节数必须位于最大帧范围内");
        }
    }
}
