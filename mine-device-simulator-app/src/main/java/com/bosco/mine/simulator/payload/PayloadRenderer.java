package com.bosco.mine.simulator.payload;

import com.bosco.mine.simulator.location.LocationRouteGenerator;
import com.bosco.mine.simulator.profile.PayloadFormat;
import com.bosco.mine.simulator.profile.SimulatorProfile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将设备配置中的报文模板渲染为实际发送的字节数组。
 */
public final class PayloadRenderer {

    private static final Pattern LOCATION_SCENARIO = Pattern.compile(
            "\\$\\{locationScenario:([A-Z_]+)}");
    private final LocationRouteGenerator locationGenerator;

    /** 使用平台演示固定种子创建报文渲染器。 */
    public PayloadRenderer() {
        this(new LocationRouteGenerator(20260718L));
    }

    /**
     * 创建可替换定位路线生成器的报文渲染器。
     *
     * @param locationGenerator 定位路线生成器
     */
    public PayloadRenderer(LocationRouteGenerator locationGenerator) {
        this.locationGenerator = java.util.Objects.requireNonNull(
                locationGenerator, "定位路线生成器不能为空");
    }

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

    private String replaceVariables(SimulatorProfile profile, long sequence, Instant timestamp) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("sequence", Long.toString(sequence));
        variables.put("timestamp", timestamp.toString());
        variables.put("epochMillis", Long.toString(timestamp.toEpochMilli()));
        variables.put("productKey", escapeJson(profile.productKey()));
        variables.put("deviceCode", escapeJson(profile.deviceCode()));
        variables.put("clientId", escapeJson(profile.clientId()));
        String rendered = profile.payloadTemplate();
        if (rendered.contains("${location")) {
            if (profile.payloadFormat() != PayloadFormat.JSON) {
                throw new IllegalArgumentException("定位模拟变量只允许用于 JSON 报文");
            }
            LocationRouteGenerator.Scenario scenario = scenario(rendered);
            LocationRouteGenerator.Sample sample = locationGenerator.generate(
                    scenario, sequence, timestamp, profile.intervalMillis(), profile.deviceCode());
            variables.put("locationScenario", scenario.name());
            variables.put("locationTagCode", escapeJson(sample.tagCode()));
            variables.put("locationBootId", escapeJson(bootId(profile.id())));
            variables.put("locationSequence", Long.toString(sample.sourceSequence()));
            variables.put("locationOccurredAt", sample.occurredAt().toString());
            variables.put("locationX", Double.toString(sample.x()));
            variables.put("locationY", Double.toString(sample.y()));
            variables.put("locationZ", Double.toString(sample.z()));
            variables.put("locationSpeed", Double.toString(sample.speedMetersPerSecond()));
            variables.put("locationHeading", Double.toString(sample.headingDegrees()));
            variables.put("locationAccuracy", Double.toString(sample.accuracyMeters()));
            rendered = LOCATION_SCENARIO.matcher(rendered).replaceAll(
                    Matcher.quoteReplacement("${locationScenario}"));
        }
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            rendered = rendered.replace("${" + variable.getKey() + "}", variable.getValue());
        }
        return rendered;
    }

    private LocationRouteGenerator.Scenario scenario(String template) {
        Matcher matcher = LOCATION_SCENARIO.matcher(template);
        if (!matcher.find()) {
            return LocationRouteGenerator.Scenario.CIRCLE;
        }
        try {
            return LocationRouteGenerator.Scenario.valueOf(matcher.group(1));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("不支持的定位模拟场景：" + matcher.group(1), exception);
        }
    }

    private String bootId(String profileId) {
        String normalized = profileId.replaceAll("[^A-Za-z0-9_-]", "-");
        return "simulator-" + normalized.substring(0, Math.min(normalized.length(), 96));
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
