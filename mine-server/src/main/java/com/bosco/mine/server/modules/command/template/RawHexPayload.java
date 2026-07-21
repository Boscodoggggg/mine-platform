package com.bosco.mine.server.modules.command.template;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 保存经过规范化和有界校验的 MQTT 原始十六进制载荷。
 *
 * @param bytes 原始字节快照
 * @param normalizedHex 无分隔符的大写十六进制
 * @param sha256 原始字节的小写 SHA-256 摘要
 */
public record RawHexPayload(byte[] bytes, String normalizedHex, String sha256) {
    /** 单条原始命令允许发布的最大字节数。 */
    public static final int MAX_BYTES = 4096;

    /**
     * 创建不可变载荷快照。
     */
    public RawHexPayload {
        bytes = Objects.requireNonNull(bytes, "原始字节不能为空").clone();
        normalizedHex = Objects.requireNonNull(normalizedHex, "规范化十六进制不能为空");
        sha256 = Objects.requireNonNull(sha256, "载荷摘要不能为空");
    }

    /**
     * 解析管理员输入，允许空白分隔和每个字节前的可选 {@code 0x} 前缀。
     *
     * @param input 管理员输入的十六进制
     * @return 有界原始载荷
     */
    public static RawHexPayload parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("十六进制载荷不能为空");
        }
        String normalized = input.replaceAll("(?i)0x", "")
                .replaceAll("\\s+", "")
                .toUpperCase();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("十六进制载荷不能为空");
        }
        if (!normalized.matches("[0-9A-F]+")) {
            throw new IllegalArgumentException("载荷只能包含十六进制字符和空白分隔符");
        }
        if ((normalized.length() & 1) != 0) {
            throw new IllegalArgumentException("十六进制字符数量必须为偶数");
        }
        byte[] bytes = HexFormat.of().parseHex(normalized);
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("原始载荷不能超过 4096 字节");
        }
        return new RawHexPayload(bytes, normalized, sha256(bytes));
    }

    /**
     * 返回字节副本，避免调用方修改已校验快照。
     *
     * @return 原始字节副本
     */
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /** @return 原始载荷字节数 */
    public int byteLength() {
        return bytes.length;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", exception);
        }
    }
}
