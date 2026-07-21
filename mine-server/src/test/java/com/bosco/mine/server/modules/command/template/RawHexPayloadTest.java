package com.bosco.mine.server.modules.command.template;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验管理员输入的十六进制载荷只能生成有界且确定的原始字节。
 */
class RawHexPayloadTest {

    @Test
    void 应移除分隔空白和每个字节的前缀并统一为大写() {
        RawHexPayload payload = RawHexPayload.parse("0xAA bb\n0x01");

        assertThat(payload.normalizedHex()).isEqualTo("AABB01");
        assertThat(payload.bytes()).containsExactly((byte) 0xAA, (byte) 0xBB, (byte) 0x01);
        assertThat(payload.byteLength()).isEqualTo(3);
        assertThat(payload.sha256()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void 应拒绝空载荷奇数长度和非法字符() {
        assertThatThrownBy(() -> RawHexPayload.parse(" \n "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> RawHexPayload.parse("ABC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("偶数");
        assertThatThrownBy(() -> RawHexPayload.parse("AA-GG"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("十六进制");
    }

    @Test
    void 应接受四千零九十六字节并拒绝更大载荷() {
        assertThat(RawHexPayload.parse("AA".repeat(4096)).byteLength()).isEqualTo(4096);
        assertThatThrownBy(() -> RawHexPayload.parse("AA".repeat(4097)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4096");
    }

    @Test
    void 返回字节副本不得允许调用方修改内部快照() {
        RawHexPayload payload = RawHexPayload.parse("AABB");
        byte[] first = payload.bytes();
        first[0] = 0;

        assertThat(payload.bytes()).containsExactly((byte) 0xAA, (byte) 0xBB);
    }
}
