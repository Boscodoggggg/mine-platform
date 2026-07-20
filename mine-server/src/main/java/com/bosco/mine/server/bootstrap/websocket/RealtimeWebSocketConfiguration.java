package com.bosco.mine.server.bootstrap.websocket;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.server.modules.device.service.DeviceTelemetryQueryService;
import com.bosco.mine.server.modules.mine.location.LocationQueryService;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * 装配统一 WebSocket 实时端点及其本实例内存订阅表。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@EnableConfigurationProperties(RealtimeProperties.class)
@ConditionalOnProperty(prefix = "mine.realtime", name = "enabled", havingValue = "true")
public class RealtimeWebSocketConfiguration {

    /**
     * 创建本服务实例的实时会话注册表。
     *
     * @param properties 实时通道配置
     * @return 实时会话注册表
     */
    @Bean
    public RealtimeSessionRegistry realtimeSessionRegistry(RealtimeProperties properties) {
        return new RealtimeSessionRegistry(
                JsonMapper.builder().findAndAddModules().build(),
                Clock.systemUTC(), properties.resolvedAuthorizationTtl());
    }

    /**
     * 创建基于 Sa-Token 的实时连接鉴权器。
     *
     * @return 实时连接鉴权器
     */
    @Bean
    public RealtimeAuthenticator realtimeAuthenticator() {
        return new SaTokenRealtimeAuthenticator(StpUtil.getStpLogic());
    }

    /**
     * 将实时订阅的首次快照读取委托给统一设备遥测查询服务。
     *
     * @param queryService 设备遥测查询服务
     * @return 实时快照读取器
     */
    @Bean
    public RealtimeSnapshotProvider realtimeSnapshotProvider(
            DeviceTelemetryQueryService queryService
    ) {
        return queryService::findLatest;
    }

    /**
     * 将位置订阅快照委托给带显式用户作用域校验的位置查询服务。
     *
     * @param queryService 位置查询服务
     * @return 位置实时快照读取器
     */
    @Bean
    public RealtimeLocationSnapshotProvider realtimeLocationSnapshotProvider(
            LocationQueryService queryService
    ) {
        return (userId, siteId) -> queryService.findCurrentForUser(
                userId, siteId, 10_000);
    }

    /**
     * 将实时矿区授权复核委托给统一矿区作用域授权器。
     *
     * @param authorizer 矿区作用域授权器
     * @return 实时矿区授权器
     */
    @Bean
    public RealtimeSiteAuthorizer realtimeSiteAuthorizer(MineScopeAuthorizer authorizer) {
        return (userId, siteId) -> authorizer.requireSiteForUser(userId, siteId);
    }

    /**
     * 创建实时连接资源限制。
     *
     * @param properties 实时通道配置
     * @return 实时连接限制
     */
    @Bean
    public RealtimeLimits realtimeLimits(RealtimeProperties properties) {
        return properties.resolvedLimits();
    }

    /**
     * 创建统一实时消息处理器。
     *
     * @param authenticator 实时鉴权器
     * @param snapshots 快照读取器
     * @param locationSnapshots 位置快照读取器
     * @param siteAuthorizer 矿区作用域授权器
     * @param registry 会话注册表
     * @param limits 连接限制
     * @return WebSocket 文本消息处理器
     */
    @Bean
    public RealtimeWebSocketHandler realtimeWebSocketHandler(
            RealtimeAuthenticator authenticator,
            RealtimeSnapshotProvider snapshots,
            RealtimeLocationSnapshotProvider locationSnapshots,
            RealtimeSiteAuthorizer siteAuthorizer,
            RealtimeSessionRegistry registry,
            RealtimeLimits limits
    ) {
        return new RealtimeWebSocketHandler(
                authenticator, snapshots, locationSnapshots, siteAuthorizer,
                registry, limits);
    }

    /**
     * 注册单一实时端点。浏览器连接后必须先发送 AUTH 消息完成鉴权。
     *
     * @param handler 实时消息处理器
     * @param properties 实时通道配置
     * @return WebSocket 端点注册器
     */
    @Bean
    public WebSocketConfigurer realtimeWebSocketConfigurer(
            RealtimeWebSocketHandler handler,
            RealtimeProperties properties
    ) {
        return registry -> registry.addHandler(handler, properties.resolvedPath());
    }
}
