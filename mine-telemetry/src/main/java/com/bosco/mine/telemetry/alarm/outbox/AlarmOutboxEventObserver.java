package com.bosco.mine.telemetry.alarm.outbox;

import java.util.UUID;

/**
 * 在数据库事务提交后精确唤醒单个告警 Outbox 事件的发布边界。
 */
@FunctionalInterface
public interface AlarmOutboxEventObserver {

    /**
     * 观察一个已经提交的 Outbox 事件标识。
     *
     * @param eventId 告警 Outbox 事件标识
     */
    void observe(UUID eventId);
}
