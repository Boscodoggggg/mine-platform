package com.bosco.mine.server.modules.command.status;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 提供命令状态消费所需的 Inbox 幂等、行锁和状态更新操作。
 */
@Repository
public class CommandStatusRepository {
    private static final String CONSUMER_NAME = "mine-server-command-status";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建命令状态仓储。
     *
     * @param jdbcTemplate 数据库访问模板
     */
    public CommandStatusRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 登记状态事件幂等记录。
     *
     * @param eventId 事件标识
     * @return 首次登记为真，重复事件为假
     */
    public boolean recordInbox(UUID eventId) {
        return jdbcTemplate.update("""
                INSERT INTO sys_inbox_event (consumer_name, event_id, result)
                VALUES (?, ?, 'SUCCESS')
                ON CONFLICT (consumer_name, event_id) DO NOTHING
                """, CONSUMER_NAME, eventId) == 1;
    }

    /**
     * 锁定并读取命令当前状态。
     *
     * @param commandId 命令标识
     * @return 命令状态快照
     */
    public Optional<CommandStatusSnapshot> lockById(UUID commandId) {
        List<CommandStatusSnapshot> rows = jdbcTemplate.query("""
                SELECT device_id, template_key, status, sent_at, device_received_at, completed_at
                FROM cmd_instance
                WHERE id = ?
                FOR UPDATE
                """, (resultSet, rowNumber) -> new CommandStatusSnapshot(
                resultSet.getObject("device_id", UUID.class),
                resultSet.getString("template_key"),
                resultSet.getString("status"),
                instant(resultSet.getTimestamp("sent_at")),
                instant(resultSet.getTimestamp("device_received_at")),
                instant(resultSet.getTimestamp("completed_at"))
        ), commandId);
        return rows.stream().findFirst();
    }

    /**
     * 在持有行锁的事务内更新命令状态和时间线。
     *
     * @param commandId 命令标识
     * @param update 状态更新内容
     */
    public void update(UUID commandId, CommandStateUpdate update) {
        int changed = jdbcTemplate.update("""
                UPDATE cmd_instance
                SET status = ?, updated_at = ?,
                    sent_at = COALESCE(sent_at, ?),
                    device_received_at = COALESCE(device_received_at, ?),
                    completed_at = COALESCE(completed_at, ?),
                    reply = CAST(? AS JSONB),
                    last_error_code = ?,
                    version = version + 1
                WHERE id = ?
                """,
                update.status(), timestamp(update.updatedAt()), timestamp(update.sentAt()),
                timestamp(update.deviceReceivedAt()), timestamp(update.completedAt()),
                update.replyJson(), update.errorCode(), commandId
        );
        if (changed != 1) {
            throw new IllegalStateException("命令状态更新时记录已不存在: " + commandId);
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
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
