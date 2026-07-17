package com.bosco.mine.gateway.tcp;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在连接建立阶段同时执行全局和单来源地址配额控制。
 */
public final class TcpConnectionLimiter {

    private final int maxConnections;
    private final int maxConnectionsPerIp;
    private final Map<InetAddress, Integer> activeByAddress = new HashMap<>();

    private int activeConnections;

    /**
     * 创建连接配额控制器。
     *
     * @param maxConnections 全局活动连接上限
     * @param maxConnectionsPerIp 单来源地址活动连接上限
     */
    public TcpConnectionLimiter(int maxConnections, int maxConnectionsPerIp) {
        if (maxConnections < 1) throw new IllegalArgumentException("全局连接上限必须大于 0");
        if (maxConnectionsPerIp < 1 || maxConnectionsPerIp > maxConnections) {
            throw new IllegalArgumentException("单 IP 连接上限必须在 1 到全局上限之间");
        }
        this.maxConnections = maxConnections;
        this.maxConnectionsPerIp = maxConnectionsPerIp;
    }

    /**
     * 原子申请一个全局和单来源地址名额。
     *
     * @param address 已接受连接的来源地址
     * @return 成功时返回必须在连接关闭后释放的租约，超限时为空
     */
    public synchronized Optional<Lease> tryAcquire(InetAddress address) {
        Objects.requireNonNull(address, "来源地址不能为空");
        int addressConnections = activeByAddress.getOrDefault(address, 0);
        if (activeConnections >= maxConnections || addressConnections >= maxConnectionsPerIp) {
            return Optional.empty();
        }
        activeConnections++;
        activeByAddress.put(address, addressConnections + 1);
        return Optional.of(new Lease(this, address));
    }

    /**
     * @return 当前全局活动连接数
     */
    public synchronized int activeConnections() {
        return activeConnections;
    }

    private synchronized void release(InetAddress address) {
        Integer count = activeByAddress.get(address);
        if (count == null || count < 1 || activeConnections < 1) {
            throw new IllegalStateException("TCP 连接租约计数不一致");
        }
        activeConnections--;
        if (count == 1) activeByAddress.remove(address);
        else activeByAddress.put(address, count - 1);
    }

    /**
     * 表示一个已成功占用且可幂等释放的连接名额。
     */
    public static final class Lease implements AutoCloseable {

        private final TcpConnectionLimiter limiter;
        private final InetAddress address;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(TcpConnectionLimiter limiter, InetAddress address) {
            this.limiter = limiter;
            this.address = address;
        }

        /** 仅第一次调用时释放全局和单来源地址名额。 */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) limiter.release(address);
        }
    }
}
