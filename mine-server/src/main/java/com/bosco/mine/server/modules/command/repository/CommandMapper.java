package com.bosco.mine.server.modules.command.repository;

import com.bosco.mine.server.modules.command.model.CommandInstance;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;
import java.util.UUID;

/**
 * 命令实例的 MyBatis 数据访问接口。
 */
@Mapper
public interface CommandMapper {

    /**
     * 判断设备是否存在且允许接收命令。
     *
     * @param deviceId 设备标识
     * @return 是否为启用设备
     */
    @Select("SELECT EXISTS (SELECT 1 FROM dev_device WHERE id = #{deviceId} AND status = 'ENABLED')")
    boolean isEnabledDevice(@Param("deviceId") UUID deviceId);

    /**
     * 插入命令；同一创建人和幂等键已存在时不修改原记录。
     *
     * @param command 已入队命令
     * @param parametersJson 命令参数 JSON
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO cmd_instance (
                id, device_id, template_key, template_version, parameters,
                reason, risk_level, status, ttl_seconds, timeout_seconds,
                idempotency_key, protocol_version, config_release_id, created_by,
                created_at, updated_at, queued_at, expires_at, version
            ) VALUES (
                #{command.id}, #{command.deviceId}, #{command.templateKey},
                #{command.templateVersion}, CAST(#{parametersJson} AS JSONB),
                #{command.reason}, #{command.riskLevel}, #{command.status},
                #{command.ttlSeconds}, #{command.timeoutSeconds},
                #{command.idempotencyKey}, #{command.protocolVersion},
                #{command.configReleaseId}, #{command.createdBy},
                #{command.createdAt}, #{command.updatedAt}, #{command.queuedAt},
                #{command.expiresAt}, #{command.version}
            )
            ON CONFLICT (created_by, idempotency_key) DO NOTHING
            """)
    int insert(
            @Param("command") CommandInstance command,
            @Param("parametersJson") String parametersJson
    );

    /**
     * 按创建人和幂等键读取原命令。
     *
     * @param createdBy 创建人标识
     * @param idempotencyKey 幂等键
     * @return 命令持久化行
     */
    @Select("""
            SELECT id, device_id AS deviceId, template_key AS templateKey,
                   template_version AS templateVersion,
                   parameters::TEXT AS parametersJson, reason,
                   risk_level AS riskLevel, status,
                   ttl_seconds AS ttlSeconds, timeout_seconds AS timeoutSeconds,
                   idempotency_key AS idempotencyKey,
                   protocol_version AS protocolVersion,
                   config_release_id AS configReleaseId, created_by AS createdBy,
                   created_at AS createdAt, updated_at AS updatedAt,
                   queued_at AS queuedAt, sent_at AS sentAt,
                   device_received_at AS deviceReceivedAt,
                   completed_at AS completedAt, expires_at AS expiresAt,
                   last_error_code AS lastErrorCode, reply::TEXT AS replyJson,
                   version
            FROM cmd_instance
            WHERE created_by = #{createdBy}
              AND idempotency_key = #{idempotencyKey}
            """)
    Optional<StoredCommand> findByIdempotencyKey(
            @Param("createdBy") UUID createdBy,
            @Param("idempotencyKey") String idempotencyKey
    );
}
