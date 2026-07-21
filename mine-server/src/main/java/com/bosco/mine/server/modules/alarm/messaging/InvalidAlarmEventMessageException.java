package com.bosco.mine.server.modules.alarm.messaging;

/**
 * 表示重投无法修复、应进入死信队列的非法告警事件。
 */
public final class InvalidAlarmEventMessageException extends RuntimeException {

    /**
     * 创建非法告警事件异常。
     *
     * @param message 安全错误说明
     */
    public InvalidAlarmEventMessageException(String message) {
        super(message);
    }

    /**
     * 创建带原因的非法告警事件异常。
     *
     * @param message 安全错误说明
     * @param cause 原始异常
     */
    public InvalidAlarmEventMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
