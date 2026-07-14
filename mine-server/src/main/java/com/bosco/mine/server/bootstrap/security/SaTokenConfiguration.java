package com.bosco.mine.server.bootstrap.security;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 配置 Sa-Token 请求鉴权、注解权限校验和密码散列算法。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MineSecurityProperties.class)
public class SaTokenConfiguration implements WebMvcConfigurer {

    private final MineSecurityProperties properties;

    /**
     * 创建安全配置。
     *
     * @param properties 平台安全配置
     */
    public SaTokenConfiguration(MineSecurityProperties properties) {
        this.properties = properties;
    }

    /**
     * 创建 BCrypt 密码编码器。
     *
     * @return 密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        int strength = properties.password().bcryptStrength();
        if (strength < 10 || strength > 16) {
            throw new IllegalStateException("BCrypt 强度必须在 10 到 16 之间");
        }
        return new BCryptPasswordEncoder(strength);
    }

    /**
     * 对所有业务 API 执行登录检查，并启用 Sa-Token 注解鉴权。
     * 登录接口和基础进程状态接口保持匿名可访问。
     *
     * @param registry MVC 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> StpUtil.checkLogin()))
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        "/api/v1/auth/login",
                        "/api/v1/system/status"
                );
    }
}
