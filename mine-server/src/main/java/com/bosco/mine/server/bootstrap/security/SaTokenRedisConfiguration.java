package com.bosco.mine.server.bootstrap.security;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 为完整运行环境显式注册基于 Redis 的 Sa-Token 会话存储。
 * 该配置与 MVC 拦截器分离，控制器切片测试无需启动外部 Redis。
 */
@Configuration(proxyBeanMethods = false)
public class SaTokenRedisConfiguration {

    /**
     * 创建 Redis 会话存储配置。
     */
    public SaTokenRedisConfiguration() {
    }

    /**
     * 创建基于 Spring Data Redis 的 Sa-Token DAO。
     * 显式注册可避免依赖自动装配失效时静默退回单机内存会话。
     *
     * @param connectionFactory Redis 连接工厂
     * @return Redis 会话 DAO
     */
    @Bean
    @Primary
    public SaTokenDao redisSaTokenDao(RedisConnectionFactory connectionFactory) {
        SaTokenDaoForRedisTemplate dao = new SaTokenDaoForRedisTemplate();
        dao.init(connectionFactory);
        return dao;
    }
}
