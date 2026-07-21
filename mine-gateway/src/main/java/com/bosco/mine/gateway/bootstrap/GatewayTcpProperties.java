package com.bosco.mine.gateway.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Gateway 私有 TCP 二进制监听器及资源保护参数。
 *
 * @param enabled 是否启用监听器
 * @param bindHost 绑定地址
 * @param port 监听端口，0 仅用于自动化随机端口
 * @param protocolKey 监听端口绑定的离线审核插件键
 * @param mode 统一产品路由或兼容监听器模式
 * @param workerThreads Netty IO 工作线程数
 * @param businessThreads 协议解析和可靠发布线程数
 * @param backlog 内核接受队列上限
 * @param maxConnections 全局活动连接上限
 * @param maxConnectionsPerIp 单来源地址活动连接上限
 * @param readIdleSeconds 读空闲关闭秒数
 * @param shutdownTimeout 优雅关闭最大等待时间
 * @param tls TLS 证书和私钥文件参数
 */
@ConfigurationProperties("mine.gateway.tcp")
public record GatewayTcpProperties(
        Boolean enabled,
        String bindHost,
        Integer port,
        String protocolKey,
        ListenerMode mode,
        Integer workerThreads,
        Integer businessThreads,
        Integer backlog,
        Integer maxConnections,
        Integer maxConnectionsPerIp,
        Integer readIdleSeconds,
        Duration shutdownTimeout,
        Tls tls
) {

    private static final Pattern PROTOCOL_KEY = Pattern.compile("[a-z][a-z0-9-]{2,63}");

    /** 使用完整主构造器绑定 Spring Boot 配置属性。 */
    @ConstructorBinding
    public GatewayTcpProperties {
    }

    /** 校验全部显式值和启用时的必填参数。 */
    public void validate() {
        if (resolvedBindHost().isBlank() || resolvedBindHost().length() > 253) {
            throw new IllegalArgumentException("TCP bind-host 长度必须在 1 到 253 之间");
        }
        requireRange(resolvedPort(), 0, 65535, "TCP port");
        requireRange(resolvedWorkerThreads(), 1, 128, "TCP worker-threads");
        requireRange(resolvedBusinessThreads(), 1, 256, "TCP business-threads");
        requireRange(resolvedBacklog(), 16, 65535, "TCP backlog");
        requireRange(resolvedMaxConnections(), 1, 1_000_000, "TCP max-connections");
        requireRange(resolvedMaxConnectionsPerIp(), 1, resolvedMaxConnections(),
                "TCP max-connections-per-ip");
        requireRange(resolvedReadIdleSeconds(), 1, 86400, "TCP read-idle-seconds");
        Duration timeout = resolvedShutdownTimeout();
        if (timeout.compareTo(Duration.ofSeconds(1)) < 0
                || timeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("TCP shutdown-timeout 必须在 1 秒到 2 分钟之间");
        }
        if (resolvedEnabled() && resolvedMode() == ListenerMode.LEGACY
                && (protocolKey == null || !PROTOCOL_KEY.matcher(protocolKey).matches())) {
            throw new IllegalArgumentException("LEGACY TCP 必须使用受控格式显式配置 protocol-key");
        }
        if (resolvedEnabled() && resolvedMode() == ListenerMode.UNIFIED) {
            if (protocolKey != null && !protocolKey.isBlank()) {
                throw new IllegalArgumentException("UNIFIED TCP 不能静态配置 protocol-key");
            }
            if (!resolvedTls().resolvedEnabled()) {
                throw new IllegalArgumentException("UNIFIED TCP 必须启用 TLS");
            }
        }
        resolvedTls().validate();
    }

    /** @return 应用默认关闭 TCP 监听器 */
    public boolean resolvedEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /** @return 绑定地址，默认监听全部网卡 */
    public String resolvedBindHost() {
        return bindHost == null ? "0.0.0.0" : bindHost.trim();
    }

    /** @return 监听端口，默认 9000 */
    public int resolvedPort() {
        return port == null ? 9000 : port;
    }

    /** @return 显式绑定的协议插件键 */
    public String resolvedProtocolKey() {
        return protocolKey;
    }

    /** @return 监听器模式，默认保留兼容监听器行为 */
    public ListenerMode resolvedMode() {
        return mode == null ? ListenerMode.LEGACY : mode;
    }

    /** @return Netty IO 工作线程数 */
    public int resolvedWorkerThreads() {
        return workerThreads == null ? 2 : workerThreads;
    }

    /** @return 协议解析和同步 publisher confirm 工作线程数 */
    public int resolvedBusinessThreads() {
        return businessThreads == null ? 4 : businessThreads;
    }

    /** @return 内核连接接受队列上限 */
    public int resolvedBacklog() {
        return backlog == null ? 1024 : backlog;
    }

    /** @return 全局活动连接上限 */
    public int resolvedMaxConnections() {
        return maxConnections == null ? 10000 : maxConnections;
    }

    /** @return 单来源地址活动连接上限 */
    public int resolvedMaxConnectionsPerIp() {
        return maxConnectionsPerIp == null ? 100 : maxConnectionsPerIp;
    }

    /** @return 读空闲关闭秒数 */
    public int resolvedReadIdleSeconds() {
        return readIdleSeconds == null ? 120 : readIdleSeconds;
    }

    /** @return 优雅关闭最大等待时间 */
    public Duration resolvedShutdownTimeout() {
        return shutdownTimeout == null ? Duration.ofSeconds(30) : shutdownTimeout;
    }

    /** @return 非空 TLS 参数 */
    public Tls resolvedTls() {
        return tls == null ? new Tls(false, null, null) : tls;
    }

    /**
     * 使用旧参数创建兼容监听器配置。
     */
    public GatewayTcpProperties(
            Boolean enabled,
            String bindHost,
            Integer port,
            String protocolKey,
            Integer workerThreads,
            Integer businessThreads,
            Integer backlog,
            Integer maxConnections,
            Integer maxConnectionsPerIp,
            Integer readIdleSeconds,
            Duration shutdownTimeout,
            Tls tls
    ) {
        this(enabled, bindHost, port, protocolKey, ListenerMode.LEGACY, workerThreads,
                businessThreads, backlog, maxConnections, maxConnectionsPerIp,
                readIdleSeconds, shutdownTimeout, tls);
    }

    /** TCP 监听器路由模式。 */
    public enum ListenerMode {
        /** TLS 登录后按产品快照选择插件。 */
        UNIFIED,
        /** 端口静态绑定单一插件，用于不可改造设备。 */
        LEGACY
    }

    private void requireRange(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    field + " 必须在 " + minimum + " 到 " + maximum + " 之间");
        }
    }

    /**
     * TCP 服务端 PEM 证书和私钥 secret 文件。
     *
     * @param enabled 是否启用 TLS
     * @param certificate PEM 证书链绝对路径
     * @param privateKey PEM 私钥绝对路径
     */
    public record Tls(Boolean enabled, Path certificate, Path privateKey) {

        /** @return 只有显式 true 才启用 TLS */
        public boolean resolvedEnabled() {
            return Boolean.TRUE.equals(enabled);
        }

        /** 校验 TLS 文件必须成对并使用绝对路径。 */
        public void validate() {
            if (!resolvedEnabled()) return;
            if (certificate == null || privateKey == null
                    || !certificate.isAbsolute() || !privateKey.isAbsolute()) {
                throw new IllegalArgumentException("启用 TCP TLS 时必须提供证书和私钥绝对路径");
            }
        }
    }
}
