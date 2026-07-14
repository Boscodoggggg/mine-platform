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
    private static final DefaultRedisScript<Long> REGISTER_FAILURE = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            if current >= tonumber(ARGV[2]) then
                redis.call('PEXPIRE', KEYS[1], ARGV[3])
            end
            return current
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
     * 判断账号和来源地址当前是否仍允许执行密码校验。
     *
     * @param username 规范化登录名
     * @param clientIp 客户端来源地址
     * @return 是否允许继续登录
     */
    public boolean isAllowed(String username, String clientIp) {
        return count(accountKey(username)) < properties.maxFailuresPerAccount()
                && count(sourceKey(clientIp)) < properties.maxFailuresPerSource();
    }

    /**
     * 同时累计账号和来源地址的失败次数。
     *
     * @param username 规范化登录名
     * @param clientIp 客户端来源地址
     */
    public void recordFailure(String username, String clientIp) {
        increment(accountKey(username), properties.maxFailuresPerAccount());
        increment(sourceKey(clientIp), properties.maxFailuresPerSource());
    }

    /**
     * 登录成功后清除账号维度的失败计数；来源维度保留用于阻止批量撞库。
     *
     * @param username 规范化登录名
     */
    public void recordSuccess(String username) {
        redisTemplate.delete(accountKey(username));
    }

    private long count(String key) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            redisTemplate.delete(key);
            return 0L;
        }
    }

    private void increment(String key, int threshold) {
        redisTemplate.execute(
                REGISTER_FAILURE,
                List.of(key),
                Long.toString(properties.attemptWindow().toMillis()),
                Integer.toString(threshold),
                Long.toString(properties.lockDuration().toMillis())
        );
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
