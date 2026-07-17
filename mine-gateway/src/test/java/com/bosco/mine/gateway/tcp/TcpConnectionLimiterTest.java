package com.bosco.mine.gateway.tcp;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验 TCP 全局和单来源地址连接配额可原子申请并幂等释放。
 */
class TcpConnectionLimiterTest {

    @Test
    void 应同时限制全局和单IP连接数并在释放后恢复名额() throws Exception {
        TcpConnectionLimiter limiter = new TcpConnectionLimiter(2, 1);
        InetAddress firstIp = InetAddress.getByName("192.0.2.1");
        InetAddress secondIp = InetAddress.getByName("192.0.2.2");

        TcpConnectionLimiter.Lease first = limiter.tryAcquire(firstIp).orElseThrow();
        assertThat(limiter.tryAcquire(firstIp)).isEmpty();
        TcpConnectionLimiter.Lease second = limiter.tryAcquire(secondIp).orElseThrow();
        assertThat(limiter.tryAcquire(InetAddress.getByName("192.0.2.3"))).isEmpty();
        assertThat(limiter.activeConnections()).isEqualTo(2);

        first.close();
        first.close();
        assertThat(limiter.activeConnections()).isEqualTo(1);
        assertThat(limiter.tryAcquire(firstIp)).isPresent().get().satisfies(lease -> lease.close());
        second.close();
    }
}
