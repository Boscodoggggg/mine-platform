package com.bosco.mine.server.bootstrap.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * 承载平台认证、初始管理员和密码算法配置。
 *
 * @param bootstrapAdmin 初始超级管理员配置
 * @param password       密码算法配置
 * @param loginProtection 登录失败防护配置
 */
@ConfigurationProperties("mine.security")
public record MineSecurityProperties(
        BootstrapAdmin bootstrapAdmin,
        Password password,
        LoginProtection loginProtection
) {

    /**
     * 初始超级管理员配置。密码只允许从外部 secret 文件读取。
     *
     * @param enabled      是否执行首次初始化
     * @param id           超级管理员稳定标识
     * @param username     登录名
     * @param displayName  展示名称
     * @param passwordFile 初始密码 secret 文件
     */
    public record BootstrapAdmin(
            boolean enabled,
            UUID id,
            String username,
            String displayName,
            Path passwordFile
    ) {
    }

    /**
     * 密码散列算法配置。
     *
     * @param bcryptStrength BCrypt 计算强度
     */
    public record Password(int bcryptStrength) {
    }

    /**
     * 登录失败防护配置。
     *
     * @param maxFailuresPerAccount 单个账号在窗口内允许的最大失败次数
     * @param maxFailuresPerSource  单个来源地址在窗口内允许的最大失败次数
     * @param attemptWindow         普通失败计数窗口
     * @param lockDuration          达到阈值后的限制时长
     */
    public record LoginProtection(
            int maxFailuresPerAccount,
            int maxFailuresPerSource,
            Duration attemptWindow,
            Duration lockDuration
    ) {
    }
}
