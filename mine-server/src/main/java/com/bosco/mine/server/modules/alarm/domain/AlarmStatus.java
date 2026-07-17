package com.bosco.mine.server.modules.alarm.domain;

import java.time.Instant;

/**
 * 由告警触发、恢复、确认和关闭事实推导出的展示状态。
 */
public enum AlarmStatus {
    /** 未恢复且未确认。 */
    ACTIVE_UNACKNOWLEDGED,
    /** 未恢复但已确认。 */
    ACTIVE_ACKNOWLEDGED,
    /** 已恢复但未确认。 */
    RECOVERED_UNACKNOWLEDGED,
    /** 已恢复且已确认。 */
    RECOVERED_ACKNOWLEDGED,
    /** 已由操作人关闭。 */
    CLOSED;

    /**
     * 从互相独立的时间事实推导当前状态。
     *
     * @param recoveredAt 恢复时间
     * @param acknowledgedAt 确认时间
     * @param closedAt 关闭时间
     * @return 当前告警状态
     */
    public static AlarmStatus derive(
            Instant recoveredAt,
            Instant acknowledgedAt,
            Instant closedAt
    ) {
        if (closedAt != null) {
            return CLOSED;
        }
        if (recoveredAt == null) {
            return acknowledgedAt == null ? ACTIVE_UNACKNOWLEDGED : ACTIVE_ACKNOWLEDGED;
        }
        return acknowledgedAt == null ? RECOVERED_UNACKNOWLEDGED : RECOVERED_ACKNOWLEDGED;
    }
}
