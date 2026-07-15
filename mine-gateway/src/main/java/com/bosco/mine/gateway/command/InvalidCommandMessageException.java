package com.bosco.mine.gateway.command;

/**
 * 表示重试无法修复的命令或设备 ACK 报文错误。
 */
public final class InvalidCommandMessageException extends RuntimeException {

    /**
     * 创建非法命令消息异常。
     *
     * @param message 错误说明
     */
    public InvalidCommandMessageException(String message) {
        super(message);
    }

    /**
     * 创建带根因的非法命令消息异常。
     *
     * @param message 错误说明
     * @param cause 根因
     */
    public InvalidCommandMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
