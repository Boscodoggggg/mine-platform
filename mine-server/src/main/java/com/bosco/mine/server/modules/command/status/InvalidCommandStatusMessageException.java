package com.bosco.mine.server.modules.command.status;

/**
 * 表示重试无法修复的命令状态消息结构错误。
 */
public final class InvalidCommandStatusMessageException extends RuntimeException {

    /**
     * 创建非法状态消息异常。
     *
     * @param message 错误说明
     */
    public InvalidCommandStatusMessageException(String message) {
        super(message);
    }

    /**
     * 创建带根因的非法状态消息异常。
     *
     * @param message 错误说明
     * @param cause 根因
     */
    public InvalidCommandStatusMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
