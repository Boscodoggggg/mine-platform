package com.bosco.mine.gateway.bootstrap;

import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.gateway.mqtt.ConfirmedTelemetryPublisher;
import com.bosco.mine.gateway.configuration.ProtocolSnapshotRegistry;
import com.bosco.mine.gateway.session.DeviceSessionRegistry;
import com.bosco.mine.gateway.tcp.TcpGatewayServer;
import com.bosco.mine.gateway.tcp.TcpGatewayHealthIndicator;
import com.bosco.mine.gateway.tcp.TcpMessageNormalizer;
import com.bosco.mine.gateway.tcp.login.TcpLoginAuthenticator;
import com.bosco.mine.protocol.engine.tcp.TcpProtocolPluginRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;

/**
 * 在显式启用时装配私有 TCP 二进制监听器及离线插件。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayTcpProperties.class)
@ConditionalOnProperty(prefix = "mine.gateway.tcp", name = "enabled", havingValue = "true")
public class GatewayTcpConfiguration {

    /**
     * 从当前运行 classpath 原子加载全部离线审核插件。
     *
     * @return 不可变插件注册表
     */
    @Bean
    public TcpProtocolPluginRegistry tcpProtocolPluginRegistry() {
        return TcpProtocolPluginRegistry.load(GatewayTcpConfiguration.class.getClassLoader());
    }

    /**
     * 创建 TCP 帧到统一遥测的可信身份转换器。
     *
     * @param registry 设备身份快照
     * @return TCP 标准化器
     */
    @Bean
    public TcpMessageNormalizer tcpMessageNormalizer(DeviceSessionRegistry registry) {
        return new TcpMessageNormalizer(registry, IdGenerator.timeOrdered(), Clock.systemUTC());
    }

    /**
     * 创建只用于统一 TCP 密钥散列校验的 BCrypt 编码器。
     *
     * @return BCrypt 密钥编码器
     */
    @Bean
    public PasswordEncoder tcpLoginPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 创建统一 TCP 登录认证器。
     *
     * @param registry 可信设备身份快照
     * @param tcpLoginPasswordEncoder BCrypt 密钥编码器
     * @return TCP 登录认证器
     */
    @Bean
    public TcpLoginAuthenticator tcpLoginAuthenticator(
            DeviceSessionRegistry registry,
            PasswordEncoder tcpLoginPasswordEncoder
    ) {
        return new TcpLoginAuthenticator(registry, tcpLoginPasswordEncoder);
    }

    /**
     * 创建由 Spring 生命周期管理的 TCP 监听器。
     *
     * @param properties TCP 参数
     * @param plugins 插件注册表
     * @param normalizer TCP 标准化器
     * @param publisher RabbitMQ publisher confirm 发布器
     * @param meterRegistryProvider 指标注册表提供器
     * @param loginAuthenticator 统一 TCP 登录认证器
     * @param protocolSnapshots 产品协议绑定快照
     * @return TCP 监听器
     */
    @Bean
    public TcpGatewayServer tcpGatewayServer(
            GatewayTcpProperties properties,
            TcpProtocolPluginRegistry plugins,
            TcpMessageNormalizer normalizer,
            ConfirmedTelemetryPublisher publisher,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            TcpLoginAuthenticator loginAuthenticator,
            ProtocolSnapshotRegistry protocolSnapshots
    ) {
        return new TcpGatewayServer(
                properties, plugins, normalizer, publisher,
                meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new),
                loginAuthenticator, protocolSnapshots);
    }

    /**
     * 创建可供 Actuator 聚合的 TCP 监听器健康指示器。
     *
     * @param server TCP 监听器
     * @param properties TCP 参数
     * @return TCP 监听器健康指示器
     */
    @Bean(name = "tcpGatewayHealthIndicator")
    public TcpGatewayHealthIndicator tcpGatewayHealthIndicator(
            TcpGatewayServer server,
            GatewayTcpProperties properties
    ) {
        return new TcpGatewayHealthIndicator(server, properties);
    }
}
