package com.bosco.mine.server.modules.mine.operation.domain;

import java.util.Objects;

/**
 * 定义运输作业单由草稿到终态的显式生命周期迁移。
 */
public final class WorkOrderLifecycle {

    private WorkOrderLifecycle() {
    }

    /**
     * 执行一次作业单状态迁移。
     *
     * @param current 当前状态
     * @param action 业务动作
     * @return 迁移后的状态
     * @throws IllegalStateException 当前状态不允许执行该动作
     */
    public static Status transition(Status current, Action action) {
        Objects.requireNonNull(current, "作业单当前状态不能为空");
        Objects.requireNonNull(action, "作业单动作不能为空");
        return switch (current) {
            case DRAFT -> switch (action) {
                case RELEASE -> Status.RELEASED;
                case CANCEL -> Status.CANCELLED;
                default -> invalid(current, action);
            };
            case RELEASED -> switch (action) {
                case START -> Status.RUNNING;
                case CANCEL -> Status.CANCELLED;
                default -> invalid(current, action);
            };
            case RUNNING -> switch (action) {
                case PAUSE -> Status.PAUSED;
                case COMPLETE -> Status.COMPLETED;
                case ABORT -> Status.ABORTED;
                default -> invalid(current, action);
            };
            case PAUSED -> switch (action) {
                case RESUME -> Status.RUNNING;
                case COMPLETE -> Status.COMPLETED;
                case ABORT -> Status.ABORTED;
                default -> invalid(current, action);
            };
            case COMPLETED, ABORTED, CANCELLED -> invalid(current, action);
        };
    }

    private static Status invalid(Status current, Action action) {
        throw new IllegalStateException("作业单状态 " + current + " 不允许执行动作 " + action);
    }

    /** 作业单状态。 */
    public enum Status {
        /** 可编辑草稿。 */
        DRAFT,
        /** 已冻结计划并允许调度启动。 */
        RELEASED,
        /** 正在执行。 */
        RUNNING,
        /** 暂停自动推进。 */
        PAUSED,
        /** 正常完成。 */
        COMPLETED,
        /** 运行中异常终止。 */
        ABORTED,
        /** 未运行即取消。 */
        CANCELLED
    }

    /** 作业单业务动作。 */
    public enum Action {
        /** 发布并冻结草稿。 */
        RELEASE,
        /** 启动作业。 */
        START,
        /** 暂停运行作业。 */
        PAUSE,
        /** 恢复暂停作业。 */
        RESUME,
        /** 正常完成作业。 */
        COMPLETE,
        /** 取消尚未运行的作业。 */
        CANCEL,
        /** 异常终止运行或暂停作业。 */
        ABORT
    }
}
