package com.bosco.mine.server.bootstrap.websocket;

/**
 * 校验实时连接令牌并返回用户权限快照。
 */
@FunctionalInterface
public interface RealtimeAuthenticator {

    /**
     * 校验令牌。
     *
     * @param token 客户端提交的访问令牌
     * @return 已鉴权用户
     * @throws RealtimeAuthenticationException 令牌无效或已失效
     */
    RealtimePrincipal authenticate(String token);
}
