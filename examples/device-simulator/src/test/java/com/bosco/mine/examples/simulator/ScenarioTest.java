package com.bosco.mine.examples.simulator;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验设备模拟场景在固定种子下能够稳定复现重复、乱序和断线步骤。
 */
class ScenarioTest {

    private static final Path SCENARIO_FILE = Path.of("scenarios/basic-mqtt.yaml");

    @Test
    void 固定种子应生成稳定的序号时间和值() {
        Scenario first = Scenario.load(SCENARIO_FILE);
        Scenario second = Scenario.load(SCENARIO_FILE);

        List<Scenario.TelemetryStep> firstTelemetry = first.steps().stream()
                .filter(Scenario.TelemetryStep.class::isInstance)
                .map(Scenario.TelemetryStep.class::cast)
                .toList();
        List<Scenario.TelemetryStep> secondTelemetry = second.steps().stream()
                .filter(Scenario.TelemetryStep.class::isInstance)
                .map(Scenario.TelemetryStep.class::cast)
                .toList();

        assertThat(firstTelemetry.stream().map(Scenario.TelemetryStep::sequence).toList())
                .containsExactly(1L, 2L, 3L, 3L, 5L, 4L, 6L, 7L, 8L);
        assertThat(firstTelemetry).isEqualTo(secondTelemetry);
        assertThat(firstTelemetry.get(2).occurredAt()).isEqualTo(firstTelemetry.get(3).occurredAt());
        assertThat(firstTelemetry.get(4).occurredAt()).isEqualTo(Instant.parse("2026-07-15T00:00:04Z"));
        assertThat(firstTelemetry.get(5).occurredAt()).isBefore(firstTelemetry.get(4).occurredAt());
        assertThat(firstTelemetry.get(5).replayed()).isTrue();
    }

    @Test
    void 配置的断线步骤应插入指定位置且包含恢复等待时间() {
        Scenario scenario = Scenario.load(SCENARIO_FILE);

        assertThat(scenario.steps()).hasSize(10);
        assertThat(scenario.steps().get(6))
                .isEqualTo(new Scenario.DisconnectStep(3000));
        assertThat(scenario.upTopic("device-credential-001"))
                .isEqualTo("mine/v1/haul-truck/device-credential-001/up/telemetry");
        assertThat(scenario.downCommandTopic("device-credential-001"))
                .isEqualTo("mine/v1/haul-truck/device-credential-001/down/command");
        assertThat(scenario.upCommandAckTopic("device-credential-001"))
                .isEqualTo("mine/v1/haul-truck/device-credential-001/up/command-ack");
    }
}
