package com.bosco.mine.protocol.engine;

/**
 * 表示协议定义在编译阶段存在确定性错误。
 */
public final class ProtocolValidationException extends IllegalArgumentException {

    private final String code;

    /**
     * 创建协议校验异常。
     *
     * @param code 稳定错误码
     * @param message 可供管理员定位问题的中文说明
     */
    public ProtocolValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 返回稳定错误码。
     *
     * @return 错误码
     */
    public String getCode() {
        return code;
    }

    /**
     * 返回稳定错误码。
     *
     * @return 错误码
     */
    public String code() {
        return code;
    }
}
