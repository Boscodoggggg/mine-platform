package com.bosco.mine.server.modules.command.status;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 提供命令状态消费所需的 Inbox 幂等、行锁和状态更新操作。
 */
@Repository
public class CommandStatusRepository {
    private static final String CONSUMER_NAME = "mine-server-command-status";

    private final CommandStatusMapper mapper;

    /**
     * 创建命令状态仓储。
     *
     * @param mapper 命令状态数据访问接口
     */
    public CommandStatusRepository(CommandStatusMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 登记状态事件幂等记录。
     *
     * @param eventId 事件标识
     * @return 首次登记为真，重复事件为假
     */
    public boolean recordInbox(UUID eventId) {
        return mapper.recordInbox(CONSUMER_NAME, eventId) == 1;
    }

    /**
     * 锁定并读取命令当前状态。
     *
     * @param commandId 命令标识
     * @return 命令状态快照
     */
    public Optional<CommandStatusSnapshot> lockById(UUID commandId) {
        return mapper.lockById(commandId);
    }

    /**
     * 在持有行锁的事务内更新命令状态和时间线。
     *
     * @param commandId 命令标识
     * @param update 状态更新内容
     */
    public void update(UUID commandId, CommandStateUpdate update) {
        int changed = mapper.update(commandId, update);
        if (changed != 1) {
            throw new IllegalStateException("命令状态更新时记录已不存在: " + commandId);
        }
    }

    /**
     * 将已超过队列 TTL 的历史排队命令修复为过期状态。
     *
     * @param occurredAt 本次恢复时刻
     * @return 修复的命令数量
     */
    public int expireQueuedCommands(Instant occurredAt) {
        return mapper.expireQueuedCommands(occurredAt);
    }

    /**
     * 命令当前状态快照。
     *
     * @param deviceId 设备标识
     * @param templateKey 命令模板编码
     * @param status 当前状态
     * @param sentAt 发送时间
     * @param deviceReceivedAt 设备收到时间
     * @param completedAt 完成时间
     */
    public record CommandStatusSnapshot(
            UUID deviceId,
            String templateKey,
            String status,
            Instant sentAt,
            Instant deviceReceivedAt,
            Instant completedAt
    ) {
    }

    /**
     * 命令状态更新内容。
     *
     * @param status 目标状态
     * @param updatedAt 更新时间
     * @param sentAt 首次发送时间
     * @param deviceReceivedAt 设备收到时间
     * @param completedAt 完成时间
     * @param replyJson 回复 JSON
     * @param errorCode 错误码
     */
    public record CommandStateUpdate(
            String status,
            Instant updatedAt,
            Instant sentAt,
            Instant deviceReceivedAt,
            Instant completedAt,
            String replyJson,
            String errorCode
    ) {
    }
}
