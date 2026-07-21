package com.bosco.mine.server.modules.notification.repository;

import com.bosco.mine.server.modules.notification.model.NotificationPolicy;
import com.bosco.mine.server.modules.notification.model.NotificationPolicyDependencyStatus;
import com.bosco.mine.server.modules.notification.model.NotificationPolicyOverlapCandidate;
import com.bosco.mine.server.modules.notification.model.NotificationPolicyStep;
import com.bosco.mine.server.modules.notification.model.NotificationPolicyVersion;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 通知升级策略根、版本和步骤数据访问接口。
 */
@Mapper
public interface NotificationPolicyMapper {

    /** 返回全部策略根。 */
    @Select("""
            SELECT id, policy_code AS policyCode, name, description, priority, status,
                   created_at AS createdAt, updated_at AS updatedAt,
                   archived_at AS archivedAt, version
            FROM notification_policy
            ORDER BY status, priority DESC, name, id
            """)
    List<NotificationPolicy> findAll();

    /** 按标识查询策略根。 */
    @Select("""
            SELECT id, policy_code AS policyCode, name, description, priority, status,
                   created_at AS createdAt, updated_at AS updatedAt,
                   archived_at AS archivedAt, version
            FROM notification_policy
            WHERE id = #{id}
            """)
    Optional<NotificationPolicy> findById(@Param("id") UUID id);

    /** 按稳定编码查询策略根。 */
    @Select("""
            SELECT id, policy_code AS policyCode, name, description, priority, status,
                   created_at AS createdAt, updated_at AS updatedAt,
                   archived_at AS archivedAt, version
            FROM notification_policy
            WHERE policy_code = #{policyCode}
            """)
    Optional<NotificationPolicy> findByCode(@Param("policyCode") String policyCode);

    /** 锁定策略根以串行化草稿和发布动作。 */
    @Select("""
            SELECT id, policy_code AS policyCode, name, description, priority, status,
                   created_at AS createdAt, updated_at AS updatedAt,
                   archived_at AS archivedAt, version
            FROM notification_policy
            WHERE id = #{id}
            FOR UPDATE
            """)
    Optional<NotificationPolicy> lockById(@Param("id") UUID id);

    /** 查询当前草稿。 */
    @Select("""
            SELECT id, policy_id AS policyId, version_number AS versionNumber, state,
                   match_conditions::text AS matchConditionsJson,
                   severity_filter::text AS severityFilterJson,
                   stop_on_ack AS stopOnAck, notify_on_recovery AS notifyOnRecovery,
                   published_at AS publishedAt, published_by AS publishedBy,
                   publication_reason AS publicationReason, retired_at AS retiredAt,
                   created_at AS createdAt
            FROM notification_policy_version
            WHERE policy_id = #{policyId} AND state = 'DRAFT'
            ORDER BY version_number DESC
            LIMIT 1
            """)
    Optional<NotificationPolicyVersion> findDraft(@Param("policyId") UUID policyId);

    /** 查询当前已发布版本。 */
    @Select("""
            SELECT id, policy_id AS policyId, version_number AS versionNumber, state,
                   match_conditions::text AS matchConditionsJson,
                   severity_filter::text AS severityFilterJson,
                   stop_on_ack AS stopOnAck, notify_on_recovery AS notifyOnRecovery,
                   published_at AS publishedAt, published_by AS publishedBy,
                   publication_reason AS publicationReason, retired_at AS retiredAt,
                   created_at AS createdAt
            FROM notification_policy_version
            WHERE policy_id = #{policyId} AND state = 'PUBLISHED'
            """)
    Optional<NotificationPolicyVersion> findPublished(@Param("policyId") UUID policyId);

    /** 按聚合和版本标识查询版本。 */
    @Select("""
            SELECT id, policy_id AS policyId, version_number AS versionNumber, state,
                   match_conditions::text AS matchConditionsJson,
                   severity_filter::text AS severityFilterJson,
                   stop_on_ack AS stopOnAck, notify_on_recovery AS notifyOnRecovery,
                   published_at AS publishedAt, published_by AS publishedBy,
                   publication_reason AS publicationReason, retired_at AS retiredAt,
                   created_at AS createdAt
            FROM notification_policy_version
            WHERE policy_id = #{policyId} AND id = #{versionId}
            """)
    Optional<NotificationPolicyVersion> findVersionById(
            @Param("policyId") UUID policyId,
            @Param("versionId") UUID versionId
    );

    /** 查询策略版本的稳定步骤。 */
    @Select("""
            SELECT id, policy_version_id AS policyVersionId, step_order AS stepOrder,
                   delay_seconds AS delaySeconds, channel_id AS channelId,
                   recipient_group_id AS recipientGroupId, template_id AS templateId,
                   recovery_template_id AS recoveryTemplateId,
                   ticket_action AS ticketAction, ticket_priority AS ticketPriority,
                   created_at AS createdAt
            FROM notification_policy_step
            WHERE policy_version_id = #{versionId}
            ORDER BY step_order, id
            """)
    List<NotificationPolicyStep> findSteps(@Param("versionId") UUID versionId);

    /** 新增策略根。 */
    @Insert("""
            INSERT INTO notification_policy (
                id, policy_code, name, description, priority, status,
                created_by, updated_by
            ) VALUES (
                #{policy.id}, #{policy.policyCode}, #{policy.name}, #{policy.description},
                #{policy.priority}, #{policy.status}, #{createdBy}, #{createdBy}
            )
            """)
    int insertPolicy(
            @Param("policy") NotificationPolicy policy,
            @Param("createdBy") UUID createdBy
    );

    /** 新增草稿策略版本。 */
    @Insert("""
            INSERT INTO notification_policy_version (
                id, policy_id, version_number, state, match_conditions,
                severity_filter, stop_on_ack, notify_on_recovery, created_by
            ) VALUES (
                #{version.id}, #{version.policyId}, #{version.versionNumber},
                #{version.state}, CAST(#{version.matchConditionsJson} AS JSONB),
                CAST(#{version.severityFilterJson} AS JSONB), #{version.stopOnAck},
                #{version.notifyOnRecovery}, #{createdBy}
            )
            """)
    int insertVersion(
            @Param("version") NotificationPolicyVersion version,
            @Param("createdBy") UUID createdBy
    );

    /** 批量新增策略步骤，由 XML 生成有界批量 SQL。 */
    int insertSteps(@Param("steps") List<NotificationPolicyStep> steps);

    /** 使用聚合乐观锁修改策略根元数据。 */
    @Update("""
            UPDATE notification_policy
            SET name = #{policy.name}, description = #{policy.description},
                priority = #{policy.priority}, updated_at = CURRENT_TIMESTAMP,
                updated_by = #{updatedBy}, version = version + 1
            WHERE id = #{policy.id} AND policy_code = #{policy.policyCode}
              AND status = 'ACTIVE' AND version = #{expectedVersion}
            """)
    int updatePolicy(
            @Param("policy") NotificationPolicy policy,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedBy") UUID updatedBy
    );

    /** 修改仍处于草稿状态的版本内容。 */
    @Update("""
            UPDATE notification_policy_version
            SET match_conditions = CAST(#{version.matchConditionsJson} AS JSONB),
                severity_filter = CAST(#{version.severityFilterJson} AS JSONB),
                stop_on_ack = #{version.stopOnAck},
                notify_on_recovery = #{version.notifyOnRecovery}
            WHERE id = #{version.id} AND policy_id = #{version.policyId}
              AND state = 'DRAFT'
            """)
    int updateDraft(@Param("version") NotificationPolicyVersion version);

    /** 删除草稿的旧步骤以便整体替换。 */
    @Delete("""
            DELETE FROM notification_policy_step
            WHERE policy_version_id = #{versionId}
              AND EXISTS (
                  SELECT 1 FROM notification_policy_version
                  WHERE id = #{versionId} AND state = 'DRAFT'
              )
            """)
    int deleteDraftSteps(@Param("versionId") UUID versionId);

    /** 将当前已发布版本退役。 */
    @Update("""
            UPDATE notification_policy_version
            SET state = 'RETIRED', retired_at = #{retiredAt}, retired_by = #{retiredBy}
            WHERE id = #{versionId} AND state = 'PUBLISHED'
            """)
    int retireVersion(
            @Param("versionId") UUID versionId,
            @Param("retiredAt") Instant retiredAt,
            @Param("retiredBy") UUID retiredBy
    );

    /** 将指定草稿发布为不可变版本。 */
    @Update("""
            UPDATE notification_policy_version
            SET state = 'PUBLISHED', published_at = #{publishedAt},
                published_by = #{publishedBy}, publication_reason = #{reason}
            WHERE id = #{versionId} AND state = 'DRAFT'
            """)
    int publishVersion(
            @Param("versionId") UUID versionId,
            @Param("publishedAt") Instant publishedAt,
            @Param("publishedBy") UUID publishedBy,
            @Param("reason") String reason
    );

    /** 仅推进聚合版本号和更新时间。 */
    @Update("""
            UPDATE notification_policy
            SET updated_at = CURRENT_TIMESTAMP, updated_by = #{updatedBy},
                version = version + 1
            WHERE id = #{policyId} AND status = 'ACTIVE'
              AND version = #{expectedVersion}
            """)
    int touchPolicy(
            @Param("policyId") UUID policyId,
            @Param("expectedVersion") long expectedVersion,
            @Param("updatedBy") UUID updatedBy
    );

    /** 使用乐观锁归档策略根。 */
    @Update("""
            UPDATE notification_policy
            SET status = 'ARCHIVED', archived_at = #{archivedAt},
                archived_by = #{archivedBy}, updated_at = #{archivedAt},
                updated_by = #{archivedBy}, version = version + 1
            WHERE id = #{policyId} AND status = 'ACTIVE'
              AND version = #{expectedVersion}
            """)
    int archivePolicy(
            @Param("policyId") UUID policyId,
            @Param("expectedVersion") long expectedVersion,
            @Param("archivedAt") Instant archivedAt,
            @Param("archivedBy") UUID archivedBy
    );

    /** 查询步骤依赖状态，由 XML 完成多表关联和接收对象统计。 */
    List<NotificationPolicyDependencyStatus> findDependencyStatuses(
            @Param("steps") List<NotificationPolicyStep> steps);

    /** 查询同优先级已发布策略，由 XML 提供冲突候选。 */
    List<NotificationPolicyOverlapCandidate> findOverlapCandidates(
            @Param("priority") int priority,
            @Param("excludedPolicyId") UUID excludedPolicyId
    );
}
