package com.bosco.mine.simulator.transport;

/**
 * 将接收报文转换为长度受限的十六进制日志预览。
 */
final class PayloadPreview {

    private static final int MAX_PREVIEW_BYTES = 64;

    private PayloadPreview() {
    }

    /**
     * 生成十六进制预览。
     *
     * @param bytes 缓冲区
     * @param length 有效字节数
     * @return 十六进制预览
     */
    static String hex(byte[] bytes, int length) {
        int previewLength = Math.min(length, MAX_PREVIEW_BYTES);
        StringBuilder builder = new StringBuilder(previewLength * 2 + 3);
        for (int index = 0; index < previewLength; index++) {
            builder.append(Character.forDigit((bytes[index] >>> 4) & 0x0F, 16));
            builder.append(Character.forDigit(bytes[index] & 0x0F, 16));
        }
        if (length > previewLength) {
            builder.append("...");
        }
        return builder.toString().toUpperCase();
    }
}
