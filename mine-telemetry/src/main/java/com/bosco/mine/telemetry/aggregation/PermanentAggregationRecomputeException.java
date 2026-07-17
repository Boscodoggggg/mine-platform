package com.bosco.mine.telemetry.aggregation;

/**
 * 表示重算控制面响应或请求配置存在重试无法自动修复的永久错误。
 */
public final class PermanentAggregationRecomputeException extends RuntimeException {

    /** 创建带安全摘要的永久错误。 */
    public PermanentAggregationRecomputeException(String message) {
        super(message);
    }

    /** 创建保留底层原因的永久错误。 */
    public PermanentAggregationRecomputeException(String message, Throwable cause) {
        super(message, cause);
    }
}
