package com.bosco.mine.server.bootstrap.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * 使用 Redis 按账号和来源地址限制连续登录失败，避免密码散列计算被滥用。
 */
@Component
public final class LoginAttemptLimiter {

    private static final String KEY_PREFIX = "mine:auth:login-failure:";
    private static final DefaultRedisScript<Long> RESERVE_ATTEMPT = new DefaultRedisScript<>("""
            local account = tonumber(redis.call('GET', KEYS[1]) or '0')
            local source = tonumber(redis.call('GET', KEYS[2]) or '0')
            if account >= tonumber(ARGV[1]) or source >= tonumber(ARGV[2]) then
                return 0
            end
            account = redis.call('INCR', KEYS[1])
            if account == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[3])
            end
            source = redis.call('INCR', KEYS[2])
            if source == 1 then
                redis.call('PEXPIRE', KEYS[2], ARGV[3])
            end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RETAIN_FAILURE = new DefaultRedisScript<>("""
            for index = 1, 2 do
                local current = tonumber(redis.call('GET', KEYS[index]) or '0')
                if current == 0 then
                    current = redis.call('INCR', KEYS[index])
                    redis.call('PEXPIRE', KEYS[index], ARGV[3])
                end
                if current >= tonumber(ARGV[index]) then
                    redis.call('PEXPIRE', KEYS[index], ARGV[4])
                end
            end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE_RESERVATION = new DefaultRedisScript<>("""
            for index = 1, 2 do
                local current = tonumber(redis.call('GET', KEYS[index]) or '0')
                if current <= 1 then
                    redis.call('DEL', KEYS[index])
                else
                    redis.call('DECR', KEYS[index])
                end
            end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE_SUCCESS = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            local source = tonumber(redis.call('GET', KEYS[2]) or '0')
            if source <= 1 then
                redis.call('DEL', KEYS[2])
            else
                redis.call('DECR', KEYS[2])
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final MineSecurityProperties.LoginProtection properties;

    /**
     * 创建登录失败限制器。
     *
     * @param redisTemplate Redis 字符串操作模板
     * @param securityProperties 平台安全配置
     */
    public LoginAttemptLimiter(
            StringRedisTemplate redisTemplate,
            MineSecurityProperties securityProperties
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = requireValid(securityProperties.loginProtection());
    }

    /**
     * 在执行密码散列前原子占用账号和来源地址配额。
     *
     * @param username 规范化登录名
     * @param clientIp 客户端来源地址
     * @return 是否允许继续登录
     */
    public boolean tryAcquire(String username, String clientIp) {
        Long result = redisTemplate.execute(
                RESERVE_ATTEMPT,
                keys(username, clientIp),
                Integer.toString(properties.maxFailuresPerAccount()),
                Integer.toString(properties.maxFailuresPerSource()),
                Long.toString(properties.attemptWindow().toMillis())
        );
        return Long.valueOf(1L).equals(result);
    }

    /**
     * 确认本次占位为失败，并在达到阈值后延长限制时间。
     *
     * @param username 规范化登录名
     * @param clientIp 客户端来源地址
     */
    public void recordFailure(String username, String clientIp) {
        redisTemplate.execute(
                RETAIN_FAILURE,
                keys(username, clientIp),
                Integer.toString(properties.maxFailuresPerAccount()),
                Integer.toString(properties.maxFailuresPerSource()),
                Long.toString(properties.attemptWindow().toMillis()),
                Long.toString(properties.lockDuration().toMillis())
        );
    }

    /**
     * 登录成功后清除账号失败计数，并释放本次来源地址占位。
     *
     * @param username 规范化登录名
     * @param clientIp 客户端来源地址
     */
    public void recordSuccess(String username, String clientIp) {
        redisTemplate.execute(RELEASE_SUCCESS, keys(username, clientIp));
    }

    /**
     * 在认证流程异常中断时释放尚未结算的占位。
     *
     * @param username 规范化登录名
     * @param clientIp 客户端来源地址
     */
    public void cancel(String username, String clientIp) {
        redisTemplate.execute(RELEASE_RESERVATION, keys(username, clientIp));
    }

    private List<String> keys(String username, String clientIp) {
        return List.of(accountKey(username), sourceKey(clientIp));
    }

    private String accountKey(String username) {
        return KEY_PREFIX + "account:" + sha256(username);
    }

    private String sourceKey(String clientIp) {
        return KEY_PREFIX + "source:" + sha256(clientIp == null ? "unknown" : clientIp);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行时不支持 SHA-256", exception);
        }
    }

    private MineSecurityProperties.LoginProtection requireValid(
            MineSecurityProperties.LoginProtection candidate
    ) {
        if (candidate == null
                || candidate.maxFailuresPerAccount() < 1
                || candidate.maxFailuresPerSource() < candidate.maxFailuresPerAccount()
                || !isPositive(candidate.attemptWindow())
                || !isPositive(candidate.lockDuration())) {
            throw new IllegalStateException("登录失败防护配置不合法");
        }
        return candidate;
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
