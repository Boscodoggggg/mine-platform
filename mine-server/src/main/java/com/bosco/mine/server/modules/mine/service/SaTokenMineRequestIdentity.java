package com.bosco.mine.server.modules.mine.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 从 Sa-Token 登录上下文读取矿区业务请求身份。
 */
@Component
public final class SaTokenMineRequestIdentity implements MineRequestIdentity {

    private static final ErrorCode AUTH_REQUIRED =
            new ErrorCode("MINE-SCOPE-401-001", "请先登录后再访问矿区数据");

    /**
     * 返回当前 Sa-Token 登录用户。
     *
     * @return 当前用户标识
     */
    @Override
    public UUID currentUserId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            throw new MineException(AUTH_REQUIRED, Map.of());
        }
    }
}
