package com.bosco.mine.server.modules.notification.repository;

import com.bosco.mine.server.modules.notification.adapter.NotificationChannelType;
import com.bosco.mine.server.modules.notification.model.NotificationRecipient;
import com.bosco.mine.server.modules.notification.model.NotificationRecipientGroup;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 通知接收组和接收目标数据访问接口。
 */
@Mapper
public interface NotificationRecipientMapper {

    /**
     * 查询全部通知接收组。
     *
     * @return 稳定排序的接收组
     */
    @Select("""
            SELECT id, group_code AS groupCode, name, description, status,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM notification_recipient_group
            ORDER BY name, id
            """)
    List<NotificationRecipientGroup> findGroups();

    /**
     * 按标识查询通知接收组。
     *
     * @param id 接收组标识
     * @return 接收组
     */
    @Select("""
            SELECT id, group_code AS groupCode, name, description, status,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM notification_recipient_group
            WHERE id = #{id}
            """)
    Optional<NotificationRecipientGroup> findGroupById(@Param("id") UUID id);

    /**
     * 按稳定编码查询通知接收组。
     *
     * @param groupCode 接收组编码
     * @return 接收组
     */
    @Select("""
            SELECT id, group_code AS groupCode, name, description, status,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM notification_recipient_group
            WHERE group_code = #{groupCode}
            """)
    Optional<NotificationRecipientGroup> findGroupByCode(
            @Param("groupCode") String groupCode);

    /**
     * 新增通知接收组。
     *
     * @param group 接收组配置
     * @param createdBy 创建用户标识
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO notification_recipient_group (
                id, group_code, name, description, status, created_by, updated_by
            ) VALUES (
                #{group.id}, #{group.groupCode}, #{group.name}, #{group.description},
                #{group.status}, #{createdBy}, #{createdBy}
            )
            """)
    int insertGroup(
            @Param("group") NotificationRecipientGroup group,
            @Param("createdBy") UUID createdBy
    );

    /**
     * 使用乐观锁修改通知接收组。
     *
     * @param group 新接收组配置
     * @param expectedVersion 期望版本号
     * @param updatedBy 修改用户标识
     * @return 影响行数
     */
    @Update("""
            UPDATE notification_recipient_group
            SET name = #{group.name}, description = #{group.description},
                status = #{group.status}, updated_at = CURRENT_TIMESTAMP,
                updated_by = #{updatedBy}, version = version + 1
            WHERE id = #{group.id} AND group_code = #{group.groupCode}
              AND version = #{expectedVersion}
            """)
    int updateGroup(
            @Param("group") NotificationRecipientGroup group,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedBy") UUID updatedBy
    );

    /**
     * 查询接收组内全部接收对象。
     *
     * @param groupId 接收组标识
     * @return 稳定排序的接收对象
     */
    @Select("""
            SELECT id, group_id AS groupId, recipient_type AS recipientType,
                   user_id AS userId, display_name AS displayName,
                   channel_type AS channelType, target_ciphertext AS targetCiphertext,
                   target_digest AS targetDigest, target_masked AS targetMasked,
                   status, created_at AS createdAt, updated_at AS updatedAt, version
            FROM notification_recipient
            WHERE group_id = #{groupId}
            ORDER BY channel_type, display_name, id
            """)
    List<NotificationRecipient> findRecipients(@Param("groupId") UUID groupId);

    /**
     * 按接收组和接收对象标识查询目标。
     *
     * @param groupId 接收组标识
     * @param recipientId 接收对象标识
     * @return 接收对象
     */
    @Select("""
            SELECT id, group_id AS groupId, recipient_type AS recipientType,
                   user_id AS userId, display_name AS displayName,
                   channel_type AS channelType, target_ciphertext AS targetCiphertext,
                   target_digest AS targetDigest, target_masked AS targetMasked,
                   status, created_at AS createdAt, updated_at AS updatedAt, version
            FROM notification_recipient
            WHERE group_id = #{groupId} AND id = #{recipientId}
            """)
    Optional<NotificationRecipient> findRecipientById(
            @Param("groupId") UUID groupId,
            @Param("recipientId") UUID recipientId
    );

    /**
     * 判断启用的平台用户是否存在。
     *
     * @param userId 用户标识
     * @return 是否存在并启用
     */
    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM sys_user WHERE id = #{userId} AND status = 'ENABLED'
            )
            """)
    boolean systemUserEnabled(@Param("userId") UUID userId);

    /**
     * 判断组内同渠道是否已经存在相同目标盲索引。
     *
     * @param groupId 接收组标识
     * @param channelType 渠道类型
     * @param targetDigest 目标盲索引
     * @param excludedId 修改时排除的接收对象标识
     * @return 是否存在重复目标
     */
    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM notification_recipient
                WHERE group_id = #{groupId}
                  AND channel_type = #{channelType}
                  AND target_digest = #{targetDigest}
                  AND (CAST(#{excludedId} AS UUID) IS NULL OR id <> #{excludedId})
            )
            """)
    boolean recipientTargetExists(
            @Param("groupId") UUID groupId,
            @Param("channelType") NotificationChannelType channelType,
            @Param("targetDigest") String targetDigest,
            @Param("excludedId") UUID excludedId
    );

    /**
     * 新增加密通知接收目标。
     *
     * @param recipient 接收对象配置
     * @param createdBy 创建用户标识
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO notification_recipient (
                id, group_id, recipient_type, user_id, display_name, channel_type,
                target_ciphertext, target_digest, target_masked, status,
                created_by, updated_by
            ) VALUES (
                #{recipient.id}, #{recipient.groupId}, #{recipient.recipientType},
                #{recipient.userId}, #{recipient.displayName}, #{recipient.channelType},
                #{recipient.targetCiphertext}, #{recipient.targetDigest},
                #{recipient.targetMasked}, #{recipient.status}, #{createdBy}, #{createdBy}
            )
            """)
    int insertRecipient(
            @Param("recipient") NotificationRecipient recipient,
            @Param("createdBy") UUID createdBy
    );

    /**
     * 使用乐观锁修改接收对象并替换新版本密文。
     *
     * @param recipient 新接收对象配置
     * @param expectedVersion 期望版本号
     * @param updatedBy 修改用户标识
     * @return 影响行数
     */
    @Update("""
            UPDATE notification_recipient
            SET recipient_type = #{recipient.recipientType}, user_id = #{recipient.userId},
                display_name = #{recipient.displayName},
                channel_type = #{recipient.channelType},
                target_ciphertext = #{recipient.targetCiphertext},
                target_digest = #{recipient.targetDigest},
                target_masked = #{recipient.targetMasked}, status = #{recipient.status},
                updated_at = CURRENT_TIMESTAMP, updated_by = #{updatedBy},
                version = version + 1
            WHERE id = #{recipient.id} AND group_id = #{recipient.groupId}
              AND version = #{expectedVersion}
            """)
    int updateRecipient(
            @Param("recipient") NotificationRecipient recipient,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedBy") UUID updatedBy
    );
}
