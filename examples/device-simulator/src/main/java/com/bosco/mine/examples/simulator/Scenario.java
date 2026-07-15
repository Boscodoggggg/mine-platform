package com.bosco.mine.examples.simulator;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.regex.Pattern;

/**
 * 描述一个可重复执行的设备模拟场景，并把 YAML 配置编译为不可变步骤列表。
 */
public final class Scenario {

    private static final Pattern TOPIC_SEGMENT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final String productKey;
    private final String deviceCode;
    private final String bootId;
    private final int qos;
    private final long publishIntervalMillis;
    private final List<Step> steps;

    private Scenario(
            String productKey,
            String deviceCode,
            String bootId,
            int qos,
            long publishIntervalMillis,
            List<Step> steps
    ) {
        this.productKey = productKey;
        this.deviceCode = deviceCode;
        this.bootId = bootId;
        this.qos = qos;
        this.publishIntervalMillis = publishIntervalMillis;
        this.steps = List.copyOf(steps);
    }

    /**
     * 从受限 YAML 文件加载场景。解析器使用安全构造器，不允许 YAML 实例化任意 Java 类型。
     *
     * @param path 场景文件
     * @return 已校验并编译的场景
     */
    public static Scenario load(Path path) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        try (InputStream input = Files.newInputStream(path)) {
            Object loaded = new Yaml(new SafeConstructor(options)).load(input);
            return fromMap(requireMap(loaded));
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取场景文件: " + path, exception);
        }
    }

    private static Scenario fromMap(Map<String, Object> source) {
        String productKey = topicSegment(source, "productKey");
        String deviceCode = topicSegment(source, "deviceCode");
        String bootId = text(source, "bootId");
        long seed = longValue(source, "seed");
        Instant startAt = Instant.parse(text(source, "startAt"));
        long intervalMillis = positiveLong(source, "intervalMillis");
        List<Long> sequenceOrder = longList(source, "sequenceOrder");
        Set<Long> replayedSequences = Set.copyOf(longList(source, "replayedSequences"));
        Set<Integer> disconnectIndexes = new HashSet<>(integerList(source, "disconnectBeforeIndexes"));
        long disconnectMillis = positiveLong(source, "disconnectMillis");
        int qos = Math.toIntExact(longValue(source, "qos"));
        if (qos < 0 || qos > 1) {
            throw new IllegalArgumentException("qos 只允许 0 或 1");
        }

        List<Step> steps = new ArrayList<>();
        for (int index = 0; index < sequenceOrder.size(); index++) {
            if (disconnectIndexes.contains(index)) {
                steps.add(new DisconnectStep(disconnectMillis));
            }
            long sequence = sequenceOrder.get(index);
            if (sequence <= 0) throw new IllegalArgumentException("sequenceOrder 必须使用正整数");
            Instant occurredAt = startAt.plusMillis(Math.multiplyExact(sequence - 1, intervalMillis));
            steps.add(new TelemetryStep(
                    sequence,
                    occurredAt,
                    replayedSequences.contains(sequence),
                    telemetry(seed, sequence)
            ));
        }
        return new Scenario(productKey, deviceCode, bootId, qos, intervalMillis, steps);
    }

    private static Map<String, Object> telemetry(long seed, long sequence) {
        SplittableRandom random = new SplittableRandom(seed ^ Long.rotateLeft(sequence, 17));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("engine.rpm", 900 + random.nextInt(900));
        values.put("coolant.temperature", Math.round((70 + random.nextDouble(20)) * 10D) / 10D);
        values.put("vehicle.speed", Math.round(random.nextDouble(45) * 10D) / 10D);
        values.put("fuel.level", Math.round((30 + random.nextDouble(70)) * 10D) / 10D);
        return Map.copyOf(values);
    }

    private static Map<String, Object> requireMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("场景根节点必须是对象");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (!(key instanceof String textKey)) throw new IllegalArgumentException("场景字段名必须是字符串");
            result.put(textKey, item);
        });
        return result;
    }

    private static String topicSegment(Map<String, Object> source, String key) {
        String value = text(source, key);
        if (!TOPIC_SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException(key + " 只能包含字母、数字、下划线和连字符");
        }
        return value;
    }

    private static String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " 不能为空");
        return text;
    }

    private static long positiveLong(Map<String, Object> source, String key) {
        long value = longValue(source, key);
        if (value <= 0) throw new IllegalArgumentException(key + " 必须大于 0");
        return value;
    }

    private static long longValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(key + " 必须是整数");
        return number.longValue();
    }

    private static List<Long> longList(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException(key + " 必须是数组");
        return list.stream().map(item -> {
            if (!(item instanceof Number number)) throw new IllegalArgumentException(key + " 只能包含整数");
            return number.longValue();
        }).toList();
    }

    private static List<Integer> integerList(Map<String, Object> source, String key) {
        return longList(source, key).stream().map(Math::toIntExact).toList();
    }

    /** @return 产品标识 */
    public String productKey() {
        return productKey;
    }

    /** @return 设备编码，同时作为 MQTT clientId */
    public String deviceCode() {
        return deviceCode;
    }

    /** @return 设备启动周期标识 */
    public String bootId() {
        return bootId;
    }

    /** @return MQTT 服务质量等级 */
    public int qos() {
        return qos;
    }

    /** @return 两次遥测发布之间的等待毫秒数 */
    public long publishIntervalMillis() {
        return publishIntervalMillis;
    }

    /** @return 不可变有序步骤列表 */
    public List<Step> steps() {
        return steps;
    }

    /**
     * 生成遥测上行主题。
     *
     * @param mqttClientId 平台签发的 MQTT 客户端标识
     * @return 遥测上行主题
     */
    public String upTopic(String mqttClientId) {
        return topicRoot(mqttClientId) + "/up/telemetry";
    }

    /**
     * 生成命令下行主题。
     *
     * @param mqttClientId 平台签发的 MQTT 客户端标识
     * @return 命令下行主题
     */
    public String downCommandTopic(String mqttClientId) {
        return topicRoot(mqttClientId) + "/down/command";
    }

    /**
     * 生成命令回复上行主题。
     *
     * @param mqttClientId 平台签发的 MQTT 客户端标识
     * @return 命令回复上行主题
     */
    public String upCommandAckTopic(String mqttClientId) {
        return topicRoot(mqttClientId) + "/up/command-ack";
    }

    private String topicRoot(String mqttClientId) {
        if (mqttClientId == null || !TOPIC_SEGMENT.matcher(mqttClientId).matches()) {
            throw new IllegalArgumentException("mqttClientId 只能包含字母、数字、下划线和连字符");
        }
        return "mine/v1/" + productKey + "/" + mqttClientId;
    }

    /** 表示一个场景执行步骤。 */
    public sealed interface Step permits TelemetryStep, DisconnectStep {
    }

    /**
     * 表示一次确定性的遥测发布。
     *
     * @param sequence 序号
     * @param occurredAt 发生时间
     * @param replayed 是否补传
     * @param properties 遥测属性
     */
    public record TelemetryStep(long sequence, Instant occurredAt, boolean replayed, Map<String, Object> properties)
            implements Step {

        /** 创建不可变遥测步骤。 */
        public TelemetryStep {
            properties = Map.copyOf(properties);
        }
    }

    /**
     * 表示一次主动断线及恢复等待。
     *
     * @param durationMillis 断线毫秒数
     */
    public record DisconnectStep(long durationMillis) implements Step {
    }
}
