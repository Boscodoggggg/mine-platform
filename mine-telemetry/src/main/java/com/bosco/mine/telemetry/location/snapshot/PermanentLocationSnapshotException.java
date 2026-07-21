package com.bosco.mine.telemetry.location.snapshot;

/** 定位运行快照存在重试无法修复的契约或完整性错误。 */
public final class PermanentLocationSnapshotException extends RuntimeException {

    /**
     * 创建不包含敏感业务数据的永久错误。
     *
     * @param message 固定错误摘要
     */
    public PermanentLocationSnapshotException(String message) {
        super(message);
    }

    /**
     * 创建带内部根因的永久错误。
     *
     * @param message 固定错误摘要
     * @param cause 内部根因
     */
    public PermanentLocationSnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}
