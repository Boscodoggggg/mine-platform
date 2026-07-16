package com.bosco.mine.server.modules.command.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 命令从平台创建到设备业务回复的稳定状态。
 */
public enum CommandStatus {
    /** 命令已在业务事务中创建。 */
    CREATED,
    /** 命令已写入可靠出站队列。 */
    QUEUED,
    /** Broker 或连接层已确认发送。 */
    SENT,
    /** 设备应用层已确认收到。 */
    DEVICE_RECEIVED,
    /** 设备执行成功。 */
    SUCCEEDED,
    /** 设备明确拒绝执行。 */
    REFUSED,
    /** 设备执行失败。 */
    FAILED,
    /** 命令在发送前超过 TTL。 */
    EXPIRED,
    /** 发送后等待设备回复超时。 */
    TIMEOUT,
    /** 命令在入队前被取消。 */
    CANCELLED;

    private static final Map<CommandStatus, Set<CommandStatus>> TRANSITIONS = Map.of(
            CREATED, EnumSet.of(QUEUED, CANCELLED),
            QUEUED, EnumSet.of(SENT, EXPIRED, CANCELLED),
            SENT, EnumSet.of(DEVICE_RECEIVED, TIMEOUT),
            DEVICE_RECEIVED, EnumSet.of(SUCCEEDED, REFUSED, FAILED, TIMEOUT)
    );

    /**
     * 判断当前状态是否允许进入目标状态。
     *
     * @param target 目标状态
     * @return 是否允许迁移
     */
    public boolean canTransitionTo(CommandStatus target) {
        return target != null && TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    /**
     * 校验并返回目标状态。
     *
     * @param target 目标状态
     * @return 通过校验的目标状态
     * @throws IllegalStateException 状态边不在允许矩阵中
     */
    public CommandStatus requireTransitionTo(CommandStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("命令状态不允许从 " + this + " 迁移到 " + target);
        }
        return target;
    }

    /**
     * 判断当前状态是否为不可再迁移的终态。
     *
     * @return 是否为终态
     */
    public boolean terminal() {
        return !TRANSITIONS.containsKey(this);
    }
}
