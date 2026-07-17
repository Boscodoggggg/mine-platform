package com.bosco.mine.simulator.payload;

import com.bosco.mine.simulator.profile.PayloadFormat;
import com.bosco.mine.simulator.profile.SimulatorProfile;
import com.bosco.mine.simulator.profile.TransportMode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 JSON 与十六进制报文模板渲染。
 */
class PayloadRendererTest {

    private final PayloadRenderer renderer = new PayloadRenderer();

    @Test
    void 应替换设备变量并生成UTF8报文() {
        SimulatorProfile profile = profile(PayloadFormat.JSON,
                "{\"seq\":${sequence},\"time\":\"${timestamp}\",\"product\":\"${productKey}\","
                        + "\"device\":\"${deviceCode}\",\"client\":\"${clientId}\"}");

        String rendered = new String(renderer.render(profile, 7, Instant.parse("2026-07-17T01:02:03Z")),
                StandardCharsets.UTF_8);

        assertThat(rendered).isEqualTo("{\"seq\":7,\"time\":\"2026-07-17T01:02:03Z\","
                + "\"product\":\"haul-truck\",\"device\":\"truck-001\",\"client\":\"client-001\"}");
    }

    @Test
    void 应忽略十六进制空白并转换为字节() {
        SimulatorProfile profile = profile(PayloadFormat.HEX, "68 01\n0A ff 16");

        assertThat(renderer.render(profile, 1, Instant.EPOCH))
                .containsExactly(0x68, 0x01, 0x0A, 0xFF, 0x16);
    }

    @Test
    void 应拒绝奇数长度或非法十六进制字符() {
        assertThatThrownBy(() -> renderer.render(profile(PayloadFormat.HEX, "ABC"), 1, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("偶数");
        assertThatThrownBy(() -> renderer.render(profile(PayloadFormat.HEX, "GG"), 1, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("十六进制");
    }

    private SimulatorProfile profile(PayloadFormat format, String template) {
        return new SimulatorProfile(
                "profile-1", "测试设备", TransportMode.MQTT, "127.0.0.1", 1883, false,
                "haul-truck", "truck-001", "client-001", "user", "mine/test/up",
                format, template, 1000, 1);
    }
}
