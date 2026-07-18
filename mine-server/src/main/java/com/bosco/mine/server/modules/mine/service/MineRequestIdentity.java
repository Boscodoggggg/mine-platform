package com.bosco.mine.server.modules.mine.service;

import java.util.UUID;

/**
 * 提供当前已认证请求的稳定用户标识。
 */
@FunctionalInterface
public interface MineRequestIdentity {

    /**
     * 返回当前用户标识，未认证时抛出统一认证异常。
     *
     * @return 当前用户标识
     */
    UUID currentUserId();
}
