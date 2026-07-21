package com.bosco.mine.protocol.sdk.tcp;

import java.util.Objects;

/**
 * 表示第三方 TCP 插件可报告的受控解码失败。
 */
public final class TcpProtocolException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 创建不携带底层异常的协议失败。
     *
     * @param errorCode 固定低基数错误码
     * @param message 不含原始帧和凭据的安全摘要
     */
    public TcpProtocolException(ErrorCode errorCode, String message) {
        super(requireMessage(message));
        this.errorCode = Objects.requireNonNull(errorCode, "错误码不能为空");
    }

    /**
     * 创建携带内部原因的协议失败。
     *
     * @param errorCode 固定低基数错误码
     * @param message 不含原始帧和凭据的安全摘要
     * @param cause 内部异常原因
     */
    public TcpProtocolException(ErrorCode errorCode, String message, Throwable cause) {
        super(requireMessage(message), Objects.requireNonNull(cause, "异常原因不能为空"));
        this.errorCode = Objects.requireNonNull(errorCode, "错误码不能为空");
    }

    /**
     * @return 固定低基数错误码
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    private static String requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("错误摘要不能为空");
        }
        return message;
    }

    /**
     * 插件允许上报且可直接用于指标标签的固定错误分类。
     */
    public enum ErrorCode {
        /** 帧短于协议允许的最小结构。 */
        FRAME_TOO_SHORT,
        /** 帧头魔数不匹配。 */
        BAD_MAGIC,
        /** 协议版本不受支持。 */
        UNSUPPORTED_VERSION,
        /** 消息类型不受支持。 */
        UNSUPPORTED_MESSAGE_TYPE,
        /** 声明长度与实际完整帧不一致。 */
        LENGTH_MISMATCH,
        /** 帧校验码不匹配。 */
        CRC_MISMATCH,
        /** 帧内身份字段格式不合法。 */
        INVALID_IDENTITY,
        /** 业务字段值或范围不合法。 */
        INVALID_VALUE
    }
}
