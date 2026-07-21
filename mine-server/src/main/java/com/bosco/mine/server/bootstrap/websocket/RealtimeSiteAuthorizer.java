package com.bosco.mine.server.bootstrap.websocket;

import java.util.UUID;

/**
 * 在实时连接动作发生时复核用户的矿区数据作用域。
 */
@FunctionalInterface
public interface RealtimeSiteAuthorizer {

    /**
     * 要求用户当前仍可访问矿区。
     *
     * @param userId 用户标识
     * @param siteId 矿区标识
     */
    void requireAccess(UUID userId, UUID siteId);
}
