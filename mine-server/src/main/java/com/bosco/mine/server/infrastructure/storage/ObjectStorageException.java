package com.bosco.mine.server.infrastructure.storage;

/**
 * 对象存储访问失败，消息不得包含凭据或内部服务地址。
 */
public final class ObjectStorageException extends RuntimeException {

    /**
     * 创建对象存储异常。
     *
     * @param message 面向服务端日志的固定错误描述
     * @param cause 原始异常
     */
    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
