package com.bosco.mine.gateway.tcp.login;

import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 校验统一 TCP 登录帧的有界解析和凭据认证边界。
 */
class TcpLoginFrameCodecTest {

    private final TcpLoginFrameCodec codec = new TcpLoginFrameCodec();

    @Test
    void 应往返编码受控登录帧() {
        TcpLoginFrame frame = new TcpLoginFrame(
                "haul-truck", "truck-001", "tcp-truck-001", "secret-value");

        byte[] encoded = codec.encode(frame);

        assertThat(encoded.length).isLessThanOrEqualTo(TcpLoginFrameCodec.MAX_FRAME_LENGTH);
        assertThat(codec.decode(encoded)).isEqualTo(frame);
    }

    @Test
    void 应拒绝截断和超长帧且错误信息不得泄露密钥() {
        String secret = "do-not-leak-this-secret";
        byte[] encoded = codec.encode(new TcpLoginFrame(
                "haul-truck", "truck-001", "tcp-truck-001", secret));

        assertThatThrownBy(() -> codec.decode(Arrays.copyOf(encoded, encoded.length - 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(secret);
        assertThatThrownBy(() -> codec.decode(new byte[TcpLoginFrameCodec.MAX_FRAME_LENGTH + 1]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(secret);
    }

    @Test
    void 应使用身份快照中的Bcrypt散列认证且不接受错误密钥() {
        DeviceSessionRegistry registry = new DeviceSessionRegistry();
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        DeviceSessionRegistry.DeviceIdentity identity = new DeviceSessionRegistry.DeviceIdentity(
                "haul-truck", "mqtt-truck-001", UUID.randomUUID().toString(), "truck-001",
                "mine-a", "2.1.0", "tcp-truck-001", encoder.encode("correct-secret"));
        registry.activate(1, "a".repeat(64), List.of(identity));
        TcpLoginAuthenticator authenticator = new TcpLoginAuthenticator(registry, encoder);

        assertThat(authenticator.authenticate(new TcpLoginFrame(
                "haul-truck", "truck-001", "tcp-truck-001", "correct-secret")))
                .isEqualTo(identity);
        assertThatThrownBy(() -> authenticator.authenticate(new TcpLoginFrame(
                "haul-truck", "truck-001", "tcp-truck-001", "wrong-secret")))
                .isInstanceOf(TcpLoginAuthenticationException.class)
                .hasMessageNotContaining("wrong-secret");
    }
}
