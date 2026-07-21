package com.bosco.mine.server.modules.notification.repository;

import com.bosco.mine.server.modules.notification.model.NotificationChannel;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 通知渠道配置数据访问接口。
 */
@Mapper
public interface NotificationChannelMapper {

    /**
     * 查询全部通知渠道。
     *
     * @return 稳定排序的通知渠道
     */
    @Select("""
            SELECT id, channel_code AS channelCode, name,
                   channel_type AS channelType, provider, status,
                   configuration::text AS configurationJson,
                   secret_ciphertext AS secretCiphertext,
                   connect_timeout_ms AS connectTimeoutMs,
                   read_timeout_ms AS readTimeoutMs,
                   last_verified_at AS lastVerifiedAt,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM notification_channel
            ORDER BY channel_type, name, id
            """)
    List<NotificationChannel> findAll();

    /**
     * 按标识查询通知渠道。
     *
     * @param id 渠道标识
     * @return 通知渠道
     */
    @Select("""
            SELECT id, channel_code AS channelCode, name,
                   channel_type AS channelType, provider, status,
                   configuration::text AS configurationJson,
                   secret_ciphertext AS secretCiphertext,
                   connect_timeout_ms AS connectTimeoutMs,
                   read_timeout_ms AS readTimeoutMs,
                   last_verified_at AS lastVerifiedAt,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM notification_channel
            WHERE id = #{id}
            """)
    Optional<NotificationChannel> findById(@Param("id") UUID id);

    /**
     * 按稳定编码查询通知渠道。
     *
     * @param channelCode 渠道编码
     * @return 通知渠道
     */
    @Select("""
            SELECT id, channel_code AS channelCode, name,
                   channel_type AS channelType, provider, status,
                   configuration::text AS configurationJson,
                   secret_ciphertext AS secretCiphertext,
                   connect_timeout_ms AS connectTimeoutMs,
                   read_timeout_ms AS readTimeoutMs,
                   last_verified_at AS lastVerifiedAt,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM notification_channel
            WHERE channel_code = #{channelCode}
            """)
    Optional<NotificationChannel> findByCode(@Param("channelCode") String channelCode);

    /**
     * 新增通知渠道。
     *
     * @param channel 渠道配置
     * @param createdBy 创建用户标识
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO notification_channel (
                id, channel_code, name, channel_type, provider, status,
                configuration, secret_ciphertext, connect_timeout_ms,
                read_timeout_ms, created_by, updated_by
            ) VALUES (
                #{channel.id}, #{channel.channelCode}, #{channel.name},
                #{channel.channelType}, #{channel.provider}, #{channel.status},
                CAST(#{channel.configurationJson} AS JSONB), #{channel.secretCiphertext},
                #{channel.connectTimeoutMs}, #{channel.readTimeoutMs},
                #{createdBy}, #{createdBy}
            )
            """)
    int insert(
            @Param("channel") NotificationChannel channel,
            @Param("createdBy") UUID createdBy
    );

    /**
     * 使用乐观锁修改通知渠道并清空旧验证结论。
     *
     * @param channel 新渠道配置
     * @param expectedVersion 期望版本号
     * @param updatedBy 修改用户标识
     * @return 影响行数
     */
    @Update("""
            UPDATE notification_channel
            SET name = #{channel.name}, channel_type = #{channel.channelType},
                provider = #{channel.provider}, status = #{channel.status},
                configuration = CAST(#{channel.configurationJson} AS JSONB),
                secret_ciphertext = #{channel.secretCiphertext},
                connect_timeout_ms = #{channel.connectTimeoutMs},
                read_timeout_ms = #{channel.readTimeoutMs},
                last_verified_at = NULL,
                updated_at = CURRENT_TIMESTAMP, updated_by = #{updatedBy},
                version = version + 1
            WHERE id = #{channel.id} AND channel_code = #{channel.channelCode}
              AND version = #{expectedVersion}
            """)
    int update(
            @Param("channel") NotificationChannel channel,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedBy") UUID updatedBy
    );
}
