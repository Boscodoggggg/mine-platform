package com.bosco.mine.examples.simulator;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验模拟器运行时的 MQTT 上报、断线恢复和安全查询命令回复。
 */
class SimulatorRuntimeTest {

    private static final Path SCENARIO_FILE = Path.of("scenarios/basic-mqtt.yaml");
    private static final Path WEAK_NETWORK_SCENARIO_FILE = Path.of("scenarios/weak-network.yaml");
    private static final String MQTT_CLIENT_ID = "device-credential-001";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void 应发布全部遥测并在断线后重新连接订阅() throws Exception {
        FakeTransport transport = new FakeTransport(null);
        List<Long> sleeps = new ArrayList<>();
        SimulatorRuntime runtime = new SimulatorRuntime(
                Scenario.load(SCENARIO_FILE),
                MQTT_CLIENT_ID,
                transport,
                sleeps::add,
                Clock.fixed(Instant.parse("2026-07-15T01:00:00Z"), ZoneOffset.UTC)
        );

        runtime.run();

        assertThat(transport.connectCount).isEqualTo(2);
        assertThat(transport.subscribeCount).isEqualTo(2);
        assertThat(transport.published.stream().filter(item -> item.topic().endsWith("/up/telemetry"))).hasSize(9);
        assertThat(transport.published.getFirst().topic())
                .isEqualTo("mine/v1/haul-truck/device-credential-001/up/telemetry");
        assertThat(sleeps).contains(3000L);
        assertThat(sleeps.stream().filter(value -> value == 1000L)).hasSize(9);
        JsonNode duplicate = JSON.readTree(transport.published.get(2).payload());
        assertThat(duplicate.get("sequence").asLong()).isEqualTo(3L);
        assertThat(duplicate.get("bootId").asString()).isEqualTo("simulator-boot-001");
        assertThat(duplicate.get("deviceCode").asString()).isEqualTo("simulator-001");
    }

    @Test
    void 收到只读查询命令时应发布业务成功回复() throws Exception {
        String command = """
                {"commandId":"command-001","command":"QUERY_STATUS","traceId":"trace-command-001","parameters":{}}
                """;
        FakeTransport transport = new FakeTransport(command.getBytes(StandardCharsets.UTF_8));
        SimulatorRuntime runtime = new SimulatorRuntime(
                Scenario.load(SCENARIO_FILE),
                MQTT_CLIENT_ID,
                transport,
                ignored -> { },
                Clock.fixed(Instant.parse("2026-07-15T01:00:00Z"), ZoneOffset.UTC)
        );

        runtime.run();

        Published ack = transport.published.stream()
                .filter(item -> item.topic().endsWith("/up/command-ack"))
                .findFirst()
                .orElseThrow();
        assertThat(ack.topic()).isEqualTo("mine/v1/haul-truck/device-credential-001/up/command-ack");
        JsonNode payload = JSON.readTree(ack.payload());
        assertThat(payload.get("commandId").asString()).isEqualTo("command-001");
        assertThat(payload.get("traceId").asString()).isEqualTo("trace-command-001");
        assertThat(payload.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(payload.get("occurredAt").asString()).isEqualTo("2026-07-15T01:00:00Z");
        assertThat(payload.get("result").get("online").asBoolean()).isTrue();
    }

    @Test
    void 弱网运行时应使用虚拟等待并在重启后发布新的启动标识() throws Exception {
        FakeTransport transport = new FakeTransport(null);
        List<Long> sleeps = new ArrayList<>();
        SimulatorRuntime runtime = new SimulatorRuntime(
                Scenario.load(WEAK_NETWORK_SCENARIO_FILE),
                MQTT_CLIENT_ID,
                transport,
                millis -> {
                    sleeps.add(millis);
                    transport.operations.add("sleep:" + millis);
                },
                Clock.fixed(Instant.parse("2026-07-15T01:00:00Z"), ZoneOffset.UTC)
        );

        runtime.run();

        List<JsonNode> telemetry = transport.published.stream()
                .filter(item -> item.topic().endsWith("/up/telemetry"))
                .map(item -> {
                    try {
                        return JSON.readTree(item.payload());
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                })
                .toList();
        assertThat(sleeps).contains(600_000L);
        assertThat(transport.connectCount).isEqualTo(3);
        int weakReconnect = transport.operations.indexOf("sleep:600000");
        assertThat(transport.operations.subList(weakReconnect - 1, weakReconnect + 4))
                .containsExactly(
                        "disconnect", "sleep:600000", "connect", "subscribe",
                        "publish:weak-boot-a:7"
                );
        assertThat(transport.operations).containsSubsequence(
                "disconnect",
                "sleep:600000",
                "connect",
                "subscribe",
                "publish:weak-boot-a:7",
                "publish:weak-boot-a:4",
                "publish:weak-boot-a:5",
                "publish:weak-boot-a:6"
        );
        assertThat(transport.operations).containsSubsequence(
                "disconnect",
                "connect",
                "subscribe",
                "publish:weak-boot-b:1"
        );
        int restartedPublish = transport.operations.indexOf("publish:weak-boot-b:1");
        assertThat(transport.operations.subList(restartedPublish - 3, restartedPublish + 1))
                .containsExactly(
                        "disconnect", "connect", "subscribe", "publish:weak-boot-b:1"
                );
        assertThat(telemetry.get(10).get("sequence").asLong()).isOne();
        assertThat(telemetry.get(10).get("bootId").asString()).isEqualTo("weak-boot-b");
        assertThat(telemetry.getLast().get("bootId").asString()).isEqualTo("weak-boot-a");
    }

    @Test
    void 重启后补传旧启动周期报文不得改变查询命令中的当前启动标识() throws Exception {
        String command = """
                {"commandId":"command-restart","command":"QUERY_STATUS","traceId":"trace-restart","parameters":{}}
                """;
        FakeTransport transport = new FakeTransport(null);
        SimulatorRuntime runtime = new SimulatorRuntime(
                Scenario.load(WEAK_NETWORK_SCENARIO_FILE),
                MQTT_CLIENT_ID,
                transport,
                ignored -> { },
                Clock.fixed(Instant.parse("2026-07-15T01:00:00Z"), ZoneOffset.UTC)
        );

        runtime.run();
        transport.deliverCommand(command.getBytes(StandardCharsets.UTF_8));

        Published ack = transport.published.stream()
                .filter(item -> item.topic().endsWith("/up/command-ack"))
                .findFirst()
                .orElseThrow();
        assertThat(JSON.readTree(ack.payload()).get("result").get("bootId").asString())
                .isEqualTo("weak-boot-b");
    }

    private static final class FakeTransport implements SimulatorRuntime.Transport {
        private final byte[] command;
        private final List<Published> published = new ArrayList<>();
        private final List<String> operations = new ArrayList<>();
        private int connectCount;
        private int subscribeCount;
        private Consumer<byte[]> commandHandler;

        private FakeTransport(byte[] command) {
            this.command = command;
        }

        @Override
        public void connect() {
            connectCount++;
            operations.add("connect");
        }

        @Override
        public void disconnect() {
            operations.add("disconnect");
        }

        @Override
        public void subscribe(String topic, int qos, Consumer<byte[]> handler) {
            subscribeCount++;
            operations.add("subscribe");
            commandHandler = handler;
            if (command != null && subscribeCount == 1) handler.accept(command);
        }

        private void deliverCommand(byte[] payload) {
            commandHandler.accept(payload);
        }

        @Override
        public void publish(String topic, byte[] payload, int qos) {
            published.add(new Published(topic, payload, qos));
            if (topic.endsWith("/up/telemetry")) {
                try {
                    JsonNode telemetry = JSON.readTree(payload);
                    operations.add("publish:" + telemetry.get("bootId").asString()
                            + ":" + telemetry.get("sequence").asLong());
                } catch (Exception exception) {
                    throw new IllegalArgumentException("测试遥测报文无法解析", exception);
                }
            }
        }

        @Override
        public void close() {
        }
    }

    private record Published(String topic, byte[] payload, int qos) {
    }
}
