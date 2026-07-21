package com.bosco.mine.server.bootstrap.websocket;

import com.bosco.mine.server.modules.mine.dto.CurrentLocationResponse;

import java.util.UUID;

/**
 * 为矿区位置订阅和重同步提供有界当前位置快照。
 */
@FunctionalInterface
public interface RealtimeLocationSnapshotProvider {

    /**
     * 读取用户有权访问的矿区当前位置。
     *
     * @param userId 用户标识
     * @param siteId 矿区标识
     * @return 当前位置快照
     */
    CurrentLocationResponse load(UUID userId, UUID siteId);
}
