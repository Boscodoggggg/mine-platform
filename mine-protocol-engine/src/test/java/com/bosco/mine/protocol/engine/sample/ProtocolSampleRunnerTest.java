package com.bosco.mine.protocol.engine.sample;

import com.bosco.mine.protocol.engine.json.FieldTransform;
import com.bosco.mine.protocol.engine.json.MqttJsonExecutionPlan;
import com.bosco.mine.protocol.engine.json.MqttJsonProtocolCompiler;
import com.bosco.mine.protocol.engine.json.MqttJsonProtocolDefinition;
import com.bosco.mine.protocol.engine.snapshot.ProtocolSnapshotHasher;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验发布门禁使用的黄金样本回放和规范化哈希。
 */
class ProtocolSampleRunnerTest {

    @Test
    void 三种差异报文都符合期望时门禁应通过() {
        MqttJsonExecutionPlan plan = plan();
        ProtocolSampleRunner runner = new ProtocolSampleRunner();

        ProtocolSampleRunner.TestReport report = runner.run(
                plan,
                new MqttJsonExecutionPlan.TrustedIdentity("truck", "001"),
                List.of(
                        success("flat", """
                                {"product":"truck","device":"001","time":"2026-07-16T00:00:00Z",
                                 "replayed":false,"rpm":1200}
                                """, 1200),
                        success("nested", """
                                {"product":"truck","device":"001","time":"2026-07-16T00:00:01Z",
                                 "replayed":false,"rpm":1300,"extra":{"source":"nested"}}
                                """, 1300),
                        new ProtocolSample("invalid", json("""
                                {"product":"truck","device":"other","time":"2026-07-16T00:00:02Z",
                                 "replayed":false,"rpm":1400}
                                """), false, Map.of(), "PROTOCOL-IDENTITY-MISMATCH")
                )
        );

        assertThat(report.passed()).isTrue();
        assertThat(report.results()).hasSize(3).allMatch(ProtocolSampleRunner.SampleResult::passed);
    }

    @Test
    void 破坏性字段修改导致样本不匹配时门禁应失败() {
        ProtocolSampleRunner.TestReport report = new ProtocolSampleRunner().run(
                plan(),
                new MqttJsonExecutionPlan.TrustedIdentity("truck", "001"),
                List.of(success("breaking", """
                        {"product":"truck","device":"001","time":"2026-07-16T00:00:00Z",
                         "replayed":false,"rpm":1200}
                        """, 999))
        );

        assertThat(report.passed()).isFalse();
        assertThat(report.results().getFirst().message()).contains("属性不符合预期");
    }

    @Test
    void JSON对象字段顺序不应改变快照哈希() {
        ProtocolSnapshotHasher hasher = new ProtocolSnapshotHasher();

        assertThat(hasher.sha256("{\"protocol\":\"truck\",\"version\":1,\"mapping\":{\"b\":2,\"a\":1}}"))
                .isEqualTo(hasher.sha256("{\"mapping\":{\"a\":1,\"b\":2},\"version\":1,\"protocol\":\"truck\"}"))
                .startsWith("sha256:");
    }

    private MqttJsonExecutionPlan plan() {
        MqttJsonProtocolDefinition definition = new MqttJsonProtocolDefinition(
                "three-json-shapes",
                1,
                new MqttJsonProtocolDefinition.MetadataPaths(
                        "product", "device", null, null, "time", "replayed"),
                List.of(new MqttJsonProtocolDefinition.PropertyDefinition(
                        "engineRpm", MqttJsonProtocolDefinition.ValueType.INTEGER)),
                List.of(new MqttJsonProtocolDefinition.FieldMapping(
                        "rpm", MqttJsonProtocolDefinition.ValueType.INTEGER, "engineRpm",
                        FieldTransform.NONE, null, null, Map.of(), true))
        );
        return new MqttJsonProtocolCompiler().compile(definition);
    }

    private ProtocolSample success(String name, String payload, int rpm) {
        return new ProtocolSample(name, json(payload), true, Map.of("engineRpm", rpm), null);
    }

    private byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
