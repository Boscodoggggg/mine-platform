package com.bosco.mine.server.modules.strategy.service;

import java.util.Collection;
import java.util.UUID;

/**
 * 在策略作用范围发生变更后精确失效运行时有效策略缓存。
 */
public interface EffectiveStrategyCacheInvalidator {

    /**
     * 在当前事务成功提交后失效指定设备的有效策略缓存。
     *
     * @param strategyType 策略类型
     * @param deviceIds    受影响设备标识
     * @param reason       失效原因
     */
    void invalidateAfterCommit(
            String strategyType,
            Collection<UUID> deviceIds,
            InvalidationReason reason
    );

    /**
     * 缓存失效的低基数原因。
     */
    enum InvalidationReason {
        /** 策略发布。 */
        PUBLISH,
        /** 策略回滚。 */
        ROLLBACK,
        /** 设备组成员变化。 */
        MEMBERSHIP,
        /** 设备组优先级变化。 */
        PRIORITY,
        /** 设备组归档。 */
        ARCHIVE
    }
}
