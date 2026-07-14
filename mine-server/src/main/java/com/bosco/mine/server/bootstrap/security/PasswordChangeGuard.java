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
 * 阻止仍使用初始密码的账号访问业务接口。
 */
public final class PasswordChangeGuard implements HandlerInterceptor {

    private static final ErrorCode PASSWORD_CHANGE_REQUIRED =
            new ErrorCode("AUTH-403-003", "首次登录必须先修改初始密码");

    private final SystemAuthMapper authMapper;

    /**
     * 创建首次改密守卫。
     *
     * @param authMapper 系统认证数据访问接口
     */
    public PasswordChangeGuard(SystemAuthMapper authMapper) {
        this.authMapper = authMapper;
    }

    /**
     * 在控制器执行前实时检查账号的首次改密标记。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @param handler 目标处理器
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
            return true;
        }
        boolean changeRequired = authMapper.findUserById(userId)
                .map(SystemAuthMapper.AuthUserRecord::passwordChangeRequired)
                .orElse(false);
        if (changeRequired) {
            throw new MineException(PASSWORD_CHANGE_REQUIRED, Map.of());
        }
        return true;
    }
}
