package com.bosco.mine.common.error;

import java.util.Map;
import java.util.Objects;

/**
 * 平台业务与应用层统一抛出的可识别异常。
 */
public final class MineException extends RuntimeException {

    /** 稳定错误定义。 */
    private final ErrorCode errorCode;

    /** 不包含敏感信息的结构化错误详情。 */
    private final Map<String, Object> details;

    /**
     * 使用稳定错误定义和结构化详情创建异常。
     *
     * @param errorCode 错误定义
     * @param details   用于定位问题的结构化详情
     */
    public MineException(ErrorCode errorCode, Map<String, Object> details) {
        super(requireErrorCode(errorCode).message());
        this.errorCode = errorCode;
        this.details = Map.copyOf(Objects.requireNonNull(details, "错误详情不能为空"));
    }

    /**
     * 获取稳定错误定义。
     *
     * @return 错误定义
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 获取不可修改的结构化错误详情。
     *
     * @return 错误详情只读视图
     */
    public Map<String, Object> details() {
        return details;
    }

    private static ErrorCode requireErrorCode(ErrorCode errorCode) {
        return Objects.requireNonNull(errorCode, "错误定义不能为空");
    }
}
