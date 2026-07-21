package com.bosco.mine.server.modules.command.status;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 命令状态消费幂等、行锁和状态推进的数据访问接口。
 */
@Mapper
public interface CommandStatusMapper {

    /** 登记命令状态消费幂等事实。 */
    @Insert("""
            INSERT INTO sys_inbox_event (consumer_name, event_id, result)
            VALUES (#{consumerName}, #{eventId}, 'SUCCESS')
            ON CONFLICT (consumer_name, event_id) DO NOTHING
            """)
    int recordInbox(@Param("consumerName") String consumerName, @Param("eventId") UUID eventId);

    /** 锁定并读取命令当前状态。 */
    @Select("""
            SELECT device_id AS deviceId, template_key AS templateKey, status,
                   sent_at AS sentAt, device_received_at AS deviceReceivedAt,
                   completed_at AS completedAt
            FROM cmd_instance
            WHERE id = #{commandId}
            FOR UPDATE
            """)
    Optional<CommandStatusRepository.CommandStatusSnapshot> lockById(
            @Param("commandId") UUID commandId);

    /** 更新命令状态和时间线。 */
    @Update("""
            UPDATE cmd_instance
            SET status = #{update.status}, updated_at = #{update.updatedAt},
                sent_at = COALESCE(sent_at, #{update.sentAt}),
                device_received_at = COALESCE(device_received_at, #{update.deviceReceivedAt}),
                completed_at = COALESCE(completed_at, #{update.completedAt}),
                reply = CAST(#{update.replyJson} AS JSONB),
                last_error_code = #{update.errorCode}, version = version + 1
            WHERE id = #{commandId}
            """)
    int update(
            @Param("commandId") UUID commandId,
            @Param("update") CommandStatusRepository.CommandStateUpdate update);

    /** 将服务停机期间遗留且已超过 TTL 的排队命令批量修复为过期。 */
    @Update("""
            UPDATE cmd_instance
            SET status = 'EXPIRED', updated_at = #{occurredAt},
                completed_at = expires_at, reply = COALESCE(reply, '{}'::JSONB),
                last_error_code = 'COMMAND_TTL_EXPIRED', version = version + 1
            WHERE status = 'QUEUED' AND expires_at <= #{occurredAt}
            """)
    int expireQueuedCommands(@Param("occurredAt") Instant occurredAt);
}
