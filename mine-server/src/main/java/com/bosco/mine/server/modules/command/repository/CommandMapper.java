package com.bosco.mine.server.modules.command.repository;

import com.bosco.mine.server.modules.command.model.CommandInstance;
import com.bosco.mine.server.modules.command.dto.CommandTargetResponse;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 命令实例的 MyBatis 数据访问接口。
 */
@Mapper
public interface CommandMapper {

    /**
     * 以共享行锁读取设备状态，使并发禁用与命令提交形成明确顺序。
     *
     * @param deviceId 设备标识
     * @return 设备状态
     */
    @Select("SELECT status FROM dev_device WHERE id = #{deviceId} FOR SHARE")
    Optional<String> lockDeviceStatus(@Param("deviceId") UUID deviceId);

    /**
     * 分页读取启用的命令目标设备。
     *
     * @param keyword 设备编码或名称关键词，可为空
     * @param offset 起始偏移
     * @param limit 返回上限
     * @return 目标设备摘要
     */
    @Select("""
            <script>
            SELECT id, device_code AS deviceCode, name
            FROM dev_device
            WHERE status = 'ENABLED'
            <if test="keyword != null and keyword != ''">
              AND (device_code ILIKE CONCAT('%', #{keyword}, '%')
                   OR name ILIKE CONCAT('%', #{keyword}, '%'))
            </if>
            ORDER BY name, device_code, id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<CommandTargetResponse> findEnabledTargets(
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    /**
     * 统计启用的命令目标设备。
     *
     * @param keyword 设备编码或名称关键词，可为空
     * @return 目标设备数量
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM dev_device
            WHERE status = 'ENABLED'
            <if test="keyword != null and keyword != ''">
              AND (device_code ILIKE CONCAT('%', #{keyword}, '%')
                   OR name ILIKE CONCAT('%', #{keyword}, '%'))
            </if>
            </script>
            """)
    long countEnabledTargets(@Param("keyword") String keyword);

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

    /**
     * 按标识读取命令。
     *
     * @param commandId 命令标识
     * @return 命令持久化行
     */
    @Select("""
            SELECT id, device_id AS deviceId, template_key AS templateKey,
                   template_version AS templateVersion, parameters::TEXT AS parametersJson,
                   reason, risk_level AS riskLevel, status, ttl_seconds AS ttlSeconds,
                   timeout_seconds AS timeoutSeconds, idempotency_key AS idempotencyKey,
                   protocol_version AS protocolVersion, config_release_id AS configReleaseId,
                   created_by AS createdBy, created_at AS createdAt, updated_at AS updatedAt,
                   queued_at AS queuedAt, sent_at AS sentAt,
                   device_received_at AS deviceReceivedAt, completed_at AS completedAt,
                   expires_at AS expiresAt, last_error_code AS lastErrorCode,
                   reply::TEXT AS replyJson, version
            FROM cmd_instance WHERE id = #{commandId}
            """)
    Optional<StoredCommand> findById(@Param("commandId") UUID commandId);

    /**
     * 按设备和状态分页查询命令。
     *
     * @param deviceId 设备筛选，可为空
     * @param status 状态筛选，可为空
     * @param offset 起始偏移
     * @param limit 返回上限
     * @return 命令列表
     */
    @Select("""
            <script>
            SELECT id, device_id AS deviceId, template_key AS templateKey,
                   template_version AS templateVersion, parameters::TEXT AS parametersJson,
                   reason, risk_level AS riskLevel, status, ttl_seconds AS ttlSeconds,
                   timeout_seconds AS timeoutSeconds, idempotency_key AS idempotencyKey,
                   protocol_version AS protocolVersion, config_release_id AS configReleaseId,
                   created_by AS createdBy, created_at AS createdAt, updated_at AS updatedAt,
                   queued_at AS queuedAt, sent_at AS sentAt,
                   device_received_at AS deviceReceivedAt, completed_at AS completedAt,
                   expires_at AS expiresAt, last_error_code AS lastErrorCode,
                   reply::TEXT AS replyJson, version
            FROM cmd_instance
            WHERE 1 = 1
            <if test="deviceId != null">AND device_id = #{deviceId}</if>
            <if test="status != null and status != ''">AND status = #{status}</if>
            ORDER BY created_at DESC, id DESC
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<StoredCommand> findCommands(
            @Param("deviceId") UUID deviceId,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    /**
     * 统计符合条件的命令。
     *
     * @param deviceId 设备筛选，可为空
     * @param status 状态筛选，可为空
     * @return 命令数量
     */
    @Select("""
            <script>
            SELECT COUNT(*) FROM cmd_instance
            WHERE 1 = 1
            <if test="deviceId != null">AND device_id = #{deviceId}</if>
            <if test="status != null and status != ''">AND status = #{status}</if>
            </script>
            """)
    long countCommands(@Param("deviceId") UUID deviceId, @Param("status") String status);
}
