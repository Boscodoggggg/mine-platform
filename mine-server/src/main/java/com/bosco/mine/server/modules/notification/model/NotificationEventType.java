package com.bosco.mine.server.modules.notification.model;

/**
 * 通知模板支持的稳定业务事件类型。
 */
public enum NotificationEventType {
    /** 告警首次产生。 */
    ALARM_RAISED,
    /** 告警恢复。 */
    ALARM_RECOVERED,
    /** 告警事件单升级。 */
    TICKET_ESCALATED
}
