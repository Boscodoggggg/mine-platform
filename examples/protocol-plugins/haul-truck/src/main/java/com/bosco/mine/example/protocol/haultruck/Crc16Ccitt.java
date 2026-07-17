package com.bosco.mine.example.protocol.haultruck;

/**
 * 计算示例协议使用的 CRC16-CCITT-FALSE 校验值。
 */
final class Crc16Ccitt {

    private Crc16Ccitt() {
    }

    /**
     * 计算从数组首字节开始的指定长度校验值。
     *
     * @param bytes 原始字节
     * @param length 参与计算的字节数
     * @return 0～65535 的校验值
     */
    static int calculate(byte[] bytes, int length) {
        if (bytes == null || length < 0 || length > bytes.length) {
            throw new IllegalArgumentException("CRC 输入范围不合法");
        }
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
