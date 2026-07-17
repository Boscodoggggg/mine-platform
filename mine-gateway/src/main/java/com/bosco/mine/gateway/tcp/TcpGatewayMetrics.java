package com.bosco.mine.gateway.tcp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 使用协议键、结果和固定原因标签记录 TCP 监听器低基数指标。
 */
public final class TcpGatewayMetrics implements TcpGatewayObserver {

    private final MeterRegistry registry;
    private final String protocolKey;
    private final AtomicInteger listenerUp = new AtomicInteger();
    private final AtomicInteger activeConnections = new AtomicInteger();
    private final Counter acceptedConnections;
    private final Counter rejectedConnections;
    private final Counter successfulFrames;
    private final Counter publishFailures;
    private final Counter acknowledgements;
    private final Map<String, Counter> rejectedFrames = new ConcurrentHashMap<>();

    /**
     * 创建并注册一个监听端口的指标。
     *
     * @param registry Micrometer 注册表
     * @param protocolKey 监听器绑定的受控协议键
     */
    public TcpGatewayMetrics(MeterRegistry registry, String protocolKey) {
        this.registry = Objects.requireNonNull(registry, "指标注册表不能为空");
        this.protocolKey = Objects.requireNonNull(protocolKey, "协议键不能为空");
        Gauge.builder("mine.gateway.tcp.listener.up", listenerUp, AtomicInteger::get)
                .tag("protocol", protocolKey).register(registry);
        Gauge.builder("mine.gateway.tcp.connections.active", activeConnections, AtomicInteger::get)
                .tag("protocol", protocolKey).register(registry);
        acceptedConnections = counter("mine.gateway.tcp.connections", "result", "accepted");
        rejectedConnections = counter("mine.gateway.tcp.connections", "result", "rejected");
        successfulFrames = Counter.builder("mine.gateway.tcp.frames")
                .tag("protocol", protocolKey)
                .tag("result", "success")
                .tag("reason", "none")
                .register(registry);
        publishFailures = counter("mine.gateway.tcp.publish.failures", "result", "failure");
        acknowledgements = counter("mine.gateway.tcp.acknowledgements", "result", "success");
    }

    /**
     * 设置监听 socket 是否处于可接受连接状态。
     *
     * @param up true 表示已成功绑定
     */
    public void listenerUp(boolean up) {
        listenerUp.set(up ? 1 : 0);
    }

    @Override
    public void connectionAccepted() {
        activeConnections.incrementAndGet();
        acceptedConnections.increment();
    }

    @Override
    public void connectionRejected() {
        rejectedConnections.increment();
    }

    @Override
    public void connectionClosed() {
        activeConnections.updateAndGet(current -> Math.max(0, current - 1));
    }

    @Override
    public void frameSucceeded() {
        successfulFrames.increment();
    }

    @Override
    public void frameRejected(String reason) {
        String safeReason = requireReason(reason);
        rejectedFrames.computeIfAbsent(safeReason, value -> Counter.builder("mine.gateway.tcp.frames")
                        .tag("protocol", protocolKey)
                        .tag("result", "rejected")
                        .tag("reason", value)
                        .register(registry))
                .increment();
    }

    @Override
    public void publishFailed() {
        publishFailures.increment();
    }

    @Override
    public void acknowledgementSent() {
        acknowledgements.increment();
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        return Counter.builder(name)
                .tag("protocol", protocolKey)
                .tag(tagKey, tagValue)
                .register(registry);
    }

    private String requireReason(String reason) {
        if (reason == null || !reason.matches("[A-Z_]{2,64}")) {
            throw new IllegalArgumentException("TCP 指标原因必须是固定大写枚举文本");
        }
        return reason;
    }
}
