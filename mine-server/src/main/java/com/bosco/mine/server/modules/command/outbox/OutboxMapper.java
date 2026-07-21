package com.bosco.mine.server.modules.command.outbox;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

/**
 * 事务性 Outbox 写入接口。
 */
@Mapper
public interface OutboxMapper {

    /**
     * 写入待发布命令事件。
     *
     * @param eventId 事件标识
     * @param commandId 命令标识
     * @param payloadJson 命令载荷 JSON
     * @param headersJson 链路头 JSON
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO sys_outbox_event (
                id, aggregate_type, aggregate_id, event_type, schema_version,
                payload, headers
            ) VALUES (
                #{eventId}, 'COMMAND', #{commandId}, 'command.dispatch.requested', 1,
                CAST(#{payloadJson} AS JSONB), CAST(#{headersJson} AS JSONB)
            )
            """)
    int insertCommandDispatch(
            @Param("eventId") UUID eventId,
            @Param("commandId") UUID commandId,
            @Param("payloadJson") String payloadJson,
            @Param("headersJson") String headersJson
    );
}
