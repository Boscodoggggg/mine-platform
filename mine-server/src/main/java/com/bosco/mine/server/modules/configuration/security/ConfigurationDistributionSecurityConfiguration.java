package com.bosco.mine.server.modules.configuration.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 注册独立于浏览器登录态的内部配置分发安全边界。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConfigurationDistributionProperties.class)
public class ConfigurationDistributionSecurityConfiguration {

    /**
     * 注册内部服务令牌过滤器。
     *
     * @param properties 配置分发安全参数
     * @return Servlet 过滤器注册项
     */
    @Bean
    FilterRegistrationBean<InternalServiceTokenFilter> internalServiceTokenFilter(
            ConfigurationDistributionProperties properties
    ) {
        FilterRegistrationBean<InternalServiceTokenFilter> registration =
                new FilterRegistrationBean<>(new InternalServiceTokenFilter(properties));
        registration.setName("internalServiceTokenFilter");
        registration.setUrlPatterns(java.util.List.of("/internal/*"));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
