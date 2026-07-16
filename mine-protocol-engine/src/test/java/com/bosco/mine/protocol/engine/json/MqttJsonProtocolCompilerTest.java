package com.bosco.mine.protocol.engine.json;

import com.bosco.mine.protocol.engine.ProtocolValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验 MQTT JSON 协议定义在发布前能够被确定性编译。
 */
class MqttJsonProtocolCompilerTest {

    private final MqttJsonProtocolCompiler compiler = new MqttJsonProtocolCompiler();

    @Test
    void 应编译嵌套点路径并生成不可变执行计划() {
        MqttJsonExecutionPlan plan = compiler.compile(definition(List.of(
                field("payload.engine.speed", MqttJsonProtocolDefinition.ValueType.DECIMAL,
                        "engineRpm", FieldTransform.SCALE, new BigDecimal("0.5"), null, Map.of())
        )));

        assertThat(plan.protocolKey()).isEqualTo("haul-truck-json");
        assertThat(plan.version()).isEqualTo(1);
        assertThat(plan.fields()).hasSize(1);
        assertThatThrownBy(() -> plan.fields().add(plan.fields().getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 重复目标字段应阻止编译() {
        assertThatThrownBy(() -> compiler.compile(definition(List.of(
                field("rpm", MqttJsonProtocolDefinition.ValueType.INTEGER, "engineRpm"),
                field("backupRpm", MqttJsonProtocolDefinition.ValueType.INTEGER, "engineRpm")
        ))))
                .isInstanceOf(ProtocolValidationException.class)
                .hasMessageContaining("目标字段重复")
                .extracting("code").isEqualTo("PROTOCOL-TARGET-DUPLICATED");
    }

    @Test
    void 映射目标不在物模型中应阻止编译() {
        assertThatThrownBy(() -> compiler.compile(definition(List.of(
                field("speed", MqttJsonProtocolDefinition.ValueType.DECIMAL, "unknownProperty")
        ))))
                .isInstanceOf(ProtocolValidationException.class)
                .hasMessageContaining("物模型")
                .extracting("code").isEqualTo("PROTOCOL-TARGET-NOT-FOUND");
    }

    @Test
    void 非数值字段使用缩放转换应阻止编译() {
        assertThatThrownBy(() -> compiler.compile(definition(List.of(
                field("state", MqttJsonProtocolDefinition.ValueType.STRING,
                        "engineRpm", FieldTransform.SCALE, BigDecimal.ONE, null, Map.of())
        ))))
                .isInstanceOf(ProtocolValidationException.class)
                .hasMessageContaining("数值")
                .extracting("code").isEqualTo("PROTOCOL-TRANSFORM-INCOMPATIBLE");
    }

    @Test
    void 路径超过八层应阻止编译() {
        assertThatThrownBy(() -> compiler.compile(definition(List.of(
                field("a.b.c.d.e.f.g.h.i", MqttJsonProtocolDefinition.ValueType.INTEGER, "engineRpm")
        ))))
                .isInstanceOf(ProtocolValidationException.class)
                .extracting("code").isEqualTo("PROTOCOL-PATH-INVALID");
    }

    @Test
    void 字段数量超过五百应阻止编译() {
        List<MqttJsonProtocolDefinition.PropertyDefinition> properties = new ArrayList<>();
        List<MqttJsonProtocolDefinition.FieldMapping> fields = new ArrayList<>();
        for (int index = 0; index < 501; index++) {
            String identifier = "property" + Math.min(index, 499);
            if (index < 500) {
                properties.add(new MqttJsonProtocolDefinition.PropertyDefinition(
                        identifier, MqttJsonProtocolDefinition.ValueType.INTEGER));
            }
            fields.add(field("value" + index, MqttJsonProtocolDefinition.ValueType.INTEGER, identifier));
        }
        MqttJsonProtocolDefinition definition = new MqttJsonProtocolDefinition(
                "too-large", 1, metadata(), properties, fields);

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(ProtocolValidationException.class)
                .extracting("code").isEqualTo("PROTOCOL-FIELD-LIMIT");
    }

    private MqttJsonProtocolDefinition definition(List<MqttJsonProtocolDefinition.FieldMapping> fields) {
        return new MqttJsonProtocolDefinition(
                "haul-truck-json",
                1,
                metadata(),
                List.of(
                        new MqttJsonProtocolDefinition.PropertyDefinition(
                                "engineRpm", MqttJsonProtocolDefinition.ValueType.DECIMAL),
                        new MqttJsonProtocolDefinition.PropertyDefinition(
                                "engineState", MqttJsonProtocolDefinition.ValueType.ENUM)
                ),
                fields
        );
    }

    private MqttJsonProtocolDefinition.MetadataPaths metadata() {
        return new MqttJsonProtocolDefinition.MetadataPaths(
                "product", "device", "boot", "seq", "time", "replayed");
    }

    private MqttJsonProtocolDefinition.FieldMapping field(
            String sourcePath,
            MqttJsonProtocolDefinition.ValueType sourceType,
            String targetIdentifier
    ) {
        return field(sourcePath, sourceType, targetIdentifier, FieldTransform.NONE, null, null, Map.of());
    }

    private MqttJsonProtocolDefinition.FieldMapping field(
            String sourcePath,
            MqttJsonProtocolDefinition.ValueType sourceType,
            String targetIdentifier,
            FieldTransform transform,
            BigDecimal scale,
            BigDecimal offset,
            Map<String, Object> enumMapping
    ) {
        return new MqttJsonProtocolDefinition.FieldMapping(
                sourcePath, sourceType, targetIdentifier, transform, scale, offset, enumMapping, true);
    }
}
