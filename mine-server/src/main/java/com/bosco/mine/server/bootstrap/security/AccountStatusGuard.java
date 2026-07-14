package com.bosco.mine.server.bootstrap.security;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.system.repository.SystemAuthMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.UUID;

/**
 * 在每个登录态请求中实时校验账号存在性和启用状态。
 */
public final class AccountStatusGuard implements HandlerInterceptor {

    private static final ErrorCode INVALID_SESSION =
            new ErrorCode("AUTH-401-002", "登录状态无效");

    private final SystemAuthMapper authMapper;

    /**
     * 创建账号状态守卫。
     *
     * @param authMapper 系统认证数据访问接口
     */
    public AccountStatusGuard(SystemAuthMapper authMapper) {
        this.authMapper = authMapper;
    }

    /**
     * 对控制器请求检查账号状态；账号删除或禁用时注销其全部会话。
     *
     * @param request  当前请求
     * @param response 当前响应
     * @param handler  目标处理器
     * @return 是否继续处理请求
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        UUID userId;
        try {
            userId = UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            throw new MineException(INVALID_SESSION, Map.of());
        }
        boolean enabled = authMapper.findUserById(userId)
                .map(user -> "ENABLED".equals(user.status()))
                .orElse(false);
        if (!enabled) {
            StpUtil.logout(userId.toString());
            throw new MineException(INVALID_SESSION, Map.of());
        }
        return true;
    }
}
