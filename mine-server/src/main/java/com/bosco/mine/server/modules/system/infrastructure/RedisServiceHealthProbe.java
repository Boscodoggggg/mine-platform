package com.bosco.mine.server.modules.system.infrastructure;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 通过临时 Redis 连接执行 PING 的按需健康探针。
 */
public final class RedisServiceHealthProbe implements ServiceHealthProbe {

    private final RedisConnectionFactory connectionFactory;

    /**
     * 创建 Redis 按需健康探针。
     *
     * @param connectionFactory Redis 连接工厂
     */
    public RedisServiceHealthProbe(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /**
     * 建立临时连接并执行 PING，不返回任何连接配置或异常详情。
     *
     * @return 脱敏后的 Redis 状态
     */
    @Override
    public ProbeResult probe() {
        long startedAt = System.nanoTime();
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String response = connection.ping();
            long latency = elapsedMillis(startedAt);
            if ("PONG".equalsIgnoreCase(response)) {
                return new ProbeResult(key(), category(), "UP", latency, "connectionUp");
            }
            return new ProbeResult(key(), category(), "DOWN", latency, "connectionDown");
        } catch (RuntimeException exception) {
            return new ProbeResult(key(), category(), "DOWN", elapsedMillis(startedAt), "connectionDown");
        }
    }

    /**
     * 返回 Redis 检查项稳定标识。
     *
     * @return Redis 检查项标识
     */
    @Override
    public String key() {
        return "redis";
    }

    /**
     * 返回 Redis 所属运行检查分类。
     *
     * @return 存储与缓存分类
     */
    @Override
    public String category() {
        return "STORAGE";
    }

    /**
     * 计算本次 Redis 探测已消耗的毫秒数。
     *
     * @param startedAt 探测开始时的单调时钟纳秒值
     * @return 非负耗时毫秒数
     */
    private long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }
}
