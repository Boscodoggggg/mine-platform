package com.bosco.mine.protocol.engine.json;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验 MQTT JSON 执行计划只使用受控转换生成统一解析结果。
 */
class MqttJsonExecutionPlanTest {

    private final MqttJsonProtocolCompiler compiler = new MqttJsonProtocolCompiler();

    @Test
    void 应解析元数据并执行缩放偏移和枚举转换() {
        MqttJsonExecutionPlan plan = compiler.compile(definition());

        ProtocolExecutionResult result = plan.execute(json("""
                {
                  "meta":{"product":"truck","device":"001","boot":"b-1","seq":9,
                          "time":"2026-07-16T01:02:03Z","replayed":false},
                  "data":{"rpm":2700,"temperature":160,"state":"RUN"}
                }
                """), new MqttJsonExecutionPlan.TrustedIdentity("truck", "001"));

        assertThat(result.success()).isTrue();
        assertThat(result.productKey()).isEqualTo("truck");
        assertThat(result.deviceCode()).isEqualTo("001");
        assertThat(result.bootId()).isEqualTo("b-1");
        assertThat(result.sequence()).isEqualTo(9L);
        assertThat(result.occurredAt()).isEqualTo(Instant.parse("2026-07-16T01:02:03Z"));
        assertThat(result.replayed()).isFalse();
        assertThat(result.properties())
                .containsEntry("engineRpm", new BigDecimal("1350.0"))
                .containsEntry("coolantTemperature", new BigDecimal("82.0"))
                .containsEntry("engineState", "RUNNING");
    }

    @Test
    void 报文身份与可信身份不一致时应返回结构化错误() {
        ProtocolExecutionResult result = compiler.compile(definition()).execute(
                json("""
                        {"meta":{"product":"truck","device":"other","boot":"b-1","seq":9,
                        "time":"2026-07-16T01:02:03Z","replayed":false},
                        "data":{"rpm":2700,"temperature":160,"state":"RUN"}}
                        """),
                new MqttJsonExecutionPlan.TrustedIdentity("truck", "001")
        );

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PROTOCOL-IDENTITY-MISMATCH");
        assertThat(result.properties()).isEmpty();
    }

    @Test
    void 必填字段缺失时应返回字段错误且不产生部分属性() {
        ProtocolExecutionResult result = compiler.compile(definition()).execute(
                json("""
                        {"meta":{"product":"truck","device":"001","boot":"b-1","seq":9,
                        "time":"2026-07-16T01:02:03Z","replayed":false},
                        "data":{"rpm":2700,"state":"RUN"}}
                        """),
                new MqttJsonExecutionPlan.TrustedIdentity("truck", "001")
        );

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PROTOCOL-REQUIRED-FIELD-MISSING");
        assertThat(result.errorMessage()).contains("data.temperature");
        assertThat(result.properties()).isEmpty();
    }

    @Test
    void 未声明的枚举码应返回转换错误() {
        ProtocolExecutionResult result = compiler.compile(definition()).execute(
                json("""
                        {"meta":{"product":"truck","device":"001","boot":"b-1","seq":9,
                        "time":"2026-07-16T01:02:03Z","replayed":false},
                        "data":{"rpm":2700,"temperature":160,"state":"BROKEN"}}
                        """),
                new MqttJsonExecutionPlan.TrustedIdentity("truck", "001")
        );

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PROTOCOL-TRANSFORM-FAILED");
    }

    private MqttJsonProtocolDefinition definition() {
        return new MqttJsonProtocolDefinition(
                "truck-vendor-json",
                3,
                new MqttJsonProtocolDefinition.MetadataPaths(
                        "meta.product", "meta.device", "meta.boot", "meta.seq", "meta.time", "meta.replayed"),
                List.of(
                        property("engineRpm", MqttJsonProtocolDefinition.ValueType.DECIMAL),
                        property("coolantTemperature", MqttJsonProtocolDefinition.ValueType.DECIMAL),
                        property("engineState", MqttJsonProtocolDefinition.ValueType.ENUM)
                ),
                List.of(
                        field("data.rpm", MqttJsonProtocolDefinition.ValueType.DECIMAL, "engineRpm",
                                FieldTransform.SCALE, new BigDecimal("0.5"), null, Map.of()),
                        field("data.temperature", MqttJsonProtocolDefinition.ValueType.DECIMAL, "coolantTemperature",
                                FieldTransform.SCALE_OFFSET, new BigDecimal("0.5"), new BigDecimal("2"), Map.of()),
                        field("data.state", MqttJsonProtocolDefinition.ValueType.STRING, "engineState",
                                FieldTransform.ENUM, null, null, Map.of("RUN", "RUNNING", "STOP", "STOPPED"))
                )
        );
    }

    private MqttJsonProtocolDefinition.PropertyDefinition property(
            String identifier,
            MqttJsonProtocolDefinition.ValueType type
    ) {
        return new MqttJsonProtocolDefinition.PropertyDefinition(identifier, type);
    }

    private MqttJsonProtocolDefinition.FieldMapping field(
            String path,
            MqttJsonProtocolDefinition.ValueType sourceType,
            String target,
            FieldTransform transform,
            BigDecimal scale,
            BigDecimal offset,
            Map<String, Object> enumMapping
    ) {
        return new MqttJsonProtocolDefinition.FieldMapping(
                path, sourceType, target, transform, scale, offset, enumMapping, true);
    }

    private byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
