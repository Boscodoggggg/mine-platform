package com.bosco.mine.examples.simulator;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 执行一个设备模拟场景，负责遥测发布、主动断线恢复和只读命令回复。
 */
public final class SimulatorRuntime {

    private static final String QUERY_STATUS = "QUERY_STATUS";

    private final Scenario scenario;
    private final String mqttClientId;
    private final Transport transport;
    private final Sleeper sleeper;
    private final Clock clock;
    private final JsonMapper jsonMapper;

    /**
     * 创建模拟器运行时。
     *
     * @param scenario 场景
     * @param mqttClientId 平台签发的 MQTT 客户端标识，与 MQTT 用户名一致
     * @param transport MQTT 传输
     * @param sleeper 等待器
     * @param clock 命令回复时钟
     */
    public SimulatorRuntime(
            Scenario scenario,
            String mqttClientId,
            Transport transport,
            Sleeper sleeper,
            Clock clock
    ) {
        this.scenario = Objects.requireNonNull(scenario, "scenario 不能为空");
        this.mqttClientId = Objects.requireNonNull(mqttClientId, "mqttClientId 不能为空");
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.jsonMapper = JsonMapper.builder().build();
    }

    /**
     * 执行完整场景。每次主动重连后重新订阅命令主题，避免依赖 Broker 持久会话。
     */
    public void run() {
        connectAndSubscribe();
        for (Scenario.Step step : scenario.steps()) {
            if (step instanceof Scenario.DisconnectStep disconnect) {
                transport.disconnect();
                sleeper.sleep(disconnect.durationMillis());
                connectAndSubscribe();
            } else if (step instanceof Scenario.TelemetryStep telemetry) {
                transport.publish(scenario.upTopic(mqttClientId), telemetryPayload(telemetry), scenario.qos());
                sleeper.sleep(scenario.publishIntervalMillis());
            }
        }
    }

    private void connectAndSubscribe() {
        transport.connect();
        transport.subscribe(scenario.downCommandTopic(mqttClientId), 1, this::handleCommand);
    }

    private byte[] telemetryPayload(Scenario.TelemetryStep telemetry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productKey", scenario.productKey());
        payload.put("deviceCode", scenario.deviceCode());
        payload.put("bootId", scenario.bootId());
        payload.put("sequence", telemetry.sequence());
        payload.put("occurredAt", telemetry.occurredAt().toString());
        payload.put("replayed", telemetry.replayed());
        payload.put("properties", telemetry.properties());
        return writeJson(payload);
    }

    private void handleCommand(byte[] bytes) {
        try {
            JsonNode command = jsonMapper.readTree(bytes);
            String commandId = requiredText(command, "commandId");
            String commandName = requiredText(command, "command");
            String traceId = requiredText(command, "traceId");
            boolean supported = QUERY_STATUS.equals(commandName);
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("commandId", commandId);
            reply.put("command", commandName);
            reply.put("status", supported ? "SUCCEEDED" : "REFUSED");
            reply.put("occurredAt", Instant.now(clock).toString());
            reply.put("traceId", traceId);
            reply.put("result", supported
                    ? Map.of("online", true, "bootId", scenario.bootId())
                    : Map.of("reason", "UNSUPPORTED_COMMAND"));
            transport.publish(scenario.upCommandAckTopic(mqttClientId), writeJson(reply), 1);
        } catch (IllegalArgumentException | JacksonException exception) {
            System.err.println("忽略无法解析的模拟命令: " + exception.getMessage());
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.asString();
    }

    private byte[] writeJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
        } catch (JacksonException exception) {
            throw new IllegalStateException("模拟报文序列化失败", exception);
        }
    }

    /** MQTT 传输边界。 */
    public interface Transport extends AutoCloseable {
        /** 建立连接。 */
        void connect();
        /** 断开连接。 */
        void disconnect();
        /** 订阅主题。 */
        void subscribe(String topic, int qos, Consumer<byte[]> handler);
        /** 发布消息。 */
        void publish(String topic, byte[] payload, int qos);
        /** 关闭资源。 */
        @Override
        void close();
    }

    /** 可替换的等待边界。 */
    @FunctionalInterface
    public interface Sleeper {
        /** 等待指定毫秒数。 */
        void sleep(long millis);
    }
}
