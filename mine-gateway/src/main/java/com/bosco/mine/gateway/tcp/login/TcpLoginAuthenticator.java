package com.bosco.mine.gateway.tcp.login;

import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Objects;

/**
 * 使用平台可信身份快照和不可逆散列认证统一 TCP 登录。
 */
public final class TcpLoginAuthenticator {

    private final DeviceSessionRegistry registry;
    private final PasswordEncoder passwordEncoder;

    /**
     * 创建 TCP 登录认证器。
     *
     * @param registry 可信设备身份快照
     * @param passwordEncoder BCrypt 密钥校验器
     */
    public TcpLoginAuthenticator(DeviceSessionRegistry registry, PasswordEncoder passwordEncoder) {
        this.registry = Objects.requireNonNull(registry, "身份注册表不能为空");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "密钥校验器不能为空");
    }

    /**
     * 认证登录帧并返回快照中的可信身份。
     *
     * @param frame 登录请求
     * @return 平台可信设备身份
     */
    public DeviceSessionRegistry.DeviceIdentity authenticate(TcpLoginFrame frame) {
        if (frame == null) throw new TcpLoginAuthenticationException();
        DeviceSessionRegistry.DeviceIdentity identity = registry.findByDeviceCode(
                        frame.productKey(), frame.deviceCode())
                .orElseThrow(TcpLoginAuthenticationException::new);
        if (!Objects.equals(identity.tcpUsername(), frame.username())
                || identity.tcpSecretHash() == null
                || !passwordEncoder.matches(frame.secret(), identity.tcpSecretHash())) {
            throw new TcpLoginAuthenticationException();
        }
        return identity;
    }
}
