package com.bosco.mine.telemetry.strategy;

/**
 * 表示重新投递也无法修复的策略事件契约或完整性错误。
 */
public final class PermanentStrategySnapshotException extends RuntimeException {

    /** 创建不可重试策略快照异常。 */
    public PermanentStrategySnapshotException(String message) {
        super(message);
    }

    /** 创建带原因的不可重试策略快照异常。 */
    public PermanentStrategySnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}
