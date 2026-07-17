package com.bosco.mine.simulator.payload;

import com.bosco.mine.simulator.profile.PayloadFormat;
import com.bosco.mine.simulator.profile.SimulatorProfile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/**
 * 将设备配置中的报文模板渲染为实际发送的字节数组。
 */
public final class PayloadRenderer {

    /**
     * 渲染单次上报报文。
     *
     * @param profile 设备配置
     * @param sequence 本次发送序号
     * @param timestamp 本次发送时间
     * @return 可直接交给传输层的报文字节
     */
    public byte[] render(SimulatorProfile profile, long sequence, Instant timestamp) {
        String rendered = replaceVariables(profile, sequence, timestamp);
        if (profile.payloadFormat() == PayloadFormat.JSON) {
            return rendered.getBytes(StandardCharsets.UTF_8);
        }
        return decodeHex(rendered);
    }

    private static String replaceVariables(SimulatorProfile profile, long sequence, Instant timestamp) {
        Map<String, String> variables = Map.of(
                "sequence", Long.toString(sequence),
                "timestamp", timestamp.toString(),
                "epochMillis", Long.toString(timestamp.toEpochMilli()),
                "productKey", escapeJson(profile.productKey()),
                "deviceCode", escapeJson(profile.deviceCode()),
                "clientId", escapeJson(profile.clientId()));
        String rendered = profile.payloadTemplate();
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            rendered = rendered.replace("${" + variable.getKey() + "}", variable.getValue());
        }
        return rendered;
    }

    private static byte[] decodeHex(String value) {
        String compact = value.replaceAll("\\s+", "");
        if ((compact.length() & 1) == 1) {
            throw new IllegalArgumentException("十六进制报文去除空白后的字符数必须是偶数");
        }
        byte[] result = new byte[compact.length() / 2];
        for (int index = 0; index < compact.length(); index += 2) {
            int high = Character.digit(compact.charAt(index), 16);
            int low = Character.digit(compact.charAt(index + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("报文包含非法十六进制字符");
            }
            result[index / 2] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
