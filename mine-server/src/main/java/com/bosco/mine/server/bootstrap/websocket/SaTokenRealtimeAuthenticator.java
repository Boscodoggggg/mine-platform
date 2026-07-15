package com.bosco.mine.server.bootstrap.websocket;

import cn.dev33.satoken.stp.StpLogic;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 使用平台统一 Sa-Token 登录态校验实时连接令牌。
 */
public final class SaTokenRealtimeAuthenticator implements RealtimeAuthenticator {

    private final StpLogic stpLogic;

    /**
     * 创建 Sa-Token 实时鉴权器。
     *
     * @param stpLogic 平台登录逻辑
     */
    public SaTokenRealtimeAuthenticator(StpLogic stpLogic) {
        this.stpLogic = Objects.requireNonNull(stpLogic, "Sa-Token 登录逻辑不能为空");
    }

    /**
     * 校验令牌有效性并读取用户权限快照。
     *
     * @param token 客户端提交的访问令牌
     * @return 已鉴权用户
     */
    @Override
    public RealtimePrincipal authenticate(String token) {
        if (token == null || token.isBlank() || !stpLogic.isValidToken(token)) {
            throw new RealtimeAuthenticationException("令牌无效或已过期");
        }
        Object loginId = stpLogic.getLoginIdByToken(token);
        try {
            UUID userId = UUID.fromString(String.valueOf(loginId));
            return new RealtimePrincipal(
                    userId,
                    Set.copyOf(stpLogic.getPermissionList(loginId))
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new RealtimeAuthenticationException("令牌关联用户无效");
        }
    }
}
