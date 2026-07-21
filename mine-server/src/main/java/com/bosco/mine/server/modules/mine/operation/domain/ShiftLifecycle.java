package com.bosco.mine.server.modules.mine.operation.domain;

import java.util.Objects;

/**
 * 定义班次实例只允许通过显式业务动作执行的生命周期迁移。
 */
public final class ShiftLifecycle {

    private ShiftLifecycle() {
    }

    /**
     * 执行一次班次状态迁移。
     *
     * @param current 当前状态
     * @param action 业务动作
     * @return 迁移后的状态
     * @throws IllegalStateException 当前状态不允许执行该动作
     */
    public static Status transition(Status current, Action action) {
        Objects.requireNonNull(current, "班次当前状态不能为空");
        Objects.requireNonNull(action, "班次动作不能为空");
        return switch (current) {
            case PLANNED -> switch (action) {
                case OPEN -> Status.OPEN;
                case CANCEL -> Status.CANCELLED;
                default -> invalid(current, action);
            };
            case OPEN -> action == Action.CLOSE ? Status.CLOSED : invalid(current, action);
            case CLOSED, CANCELLED -> invalid(current, action);
        };
    }

    private static Status invalid(Status current, Action action) {
        throw new IllegalStateException("班次状态 " + current + " 不允许执行动作 " + action);
    }

    /** 班次实例状态。 */
    public enum Status {
        /** 已计划但尚未开启。 */
        PLANNED,
        /** 已开启并允许生产作业。 */
        OPEN,
        /** 已关账且不能自动追加普通作业。 */
        CLOSED,
        /** 尚未开启即被取消。 */
        CANCELLED
    }

    /** 班次业务动作。 */
    public enum Action {
        /** 开启计划班次。 */
        OPEN,
        /** 关闭并关账已开启班次。 */
        CLOSE,
        /** 取消尚未开启的班次。 */
        CANCEL
    }
}
