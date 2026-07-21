package com.bosco.mine.server.modules.command.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * 只调度已知事件标识的进程内重试，不执行数据库周期扫描。
 */
@FunctionalInterface
public interface KnownEventRetryScheduler {

    /**
     * 在指定时间重新唤醒同一事件。
     *
     * @param eventId 事件标识
     * @param retryAt 下次尝试时间
     */
    void schedule(UUID eventId, Instant retryAt);
}
