package com.bosco.mine.server.modules.strategy.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 持久化并锁定数据保留影响预览的一次性激活凭证。
 */
@Mapper
public interface RetentionPreviewEvidenceMapper {

    /**
     * 保存短期有效的精确预览凭证。
     *
     * @param evidence 预览凭证
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO dev_retention_preview_evidence (
                id, operator_id, activation_type, change_type, group_version,
                strategy_version_id, product_id, scope_type, scope_id, group_priority,
                candidate_hash, resolution_hash, observed_at, expires_at
            ) VALUES (
                #{id}, #{operatorId}, #{activationType}, #{changeType}, #{groupVersion},
                #{strategyVersionId}, #{productId}, #{scopeType}, #{scopeId}, #{groupPriority},
                #{candidateHash}, #{resolutionHash}, #{observedAt}, #{expiresAt}
            )
            """)
    int insert(PreviewEvidence evidence);

    /**
     * 使用行锁读取待激活凭证，串行化并发重复激活。
     *
     * @param id 凭证标识
     * @return 凭证快照
     */
    @Select("""
            SELECT id, operator_id AS operatorId, activation_type AS activationType,
                   change_type AS changeType, group_version AS groupVersion,
                   strategy_version_id AS strategyVersionId,
                   product_id AS productId, scope_type AS scopeType, scope_id AS scopeId,
                   group_priority AS groupPriority, candidate_hash AS candidateHash,
                   resolution_hash AS resolutionHash, observed_at AS observedAt,
                   expires_at AS expiresAt, activated_release_id AS activatedReleaseId,
                   activated_at AS activatedAt
              FROM dev_retention_preview_evidence
             WHERE id = #{id}
             FOR UPDATE
            """)
    Optional<PreviewEvidence> findForUpdate(@Param("id") UUID id);

    /**
     * 把未使用凭证原子绑定到新创建的策略发布。
     *
     * @param id        凭证标识
     * @param releaseId 策略发布标识
     * @return 影响行数
     */
    @Update("""
            UPDATE dev_retention_preview_evidence
               SET activated_release_id = #{releaseId},
                   activated_at = CURRENT_TIMESTAMP
             WHERE id = #{id}
               AND activated_release_id IS NULL
               AND activated_at IS NULL
               AND expires_at > CURRENT_TIMESTAMP
            """)
    int markActivated(@Param("id") UUID id, @Param("releaseId") UUID releaseId);

    /**
     * 把未使用凭证原子绑定到设备组变化，设备组标识由凭证作用域约束。
     *
     * @param id      凭证标识
     * @param groupId 设备组标识
     * @return 影响行数
     */
    @Update("""
            UPDATE dev_retention_preview_evidence
               SET activated_at = CURRENT_TIMESTAMP
             WHERE id = #{id}
               AND activation_type = 'GROUP_CHANGE'
               AND scope_type = 'GROUP'
               AND scope_id = #{groupId}
               AND activated_at IS NULL
               AND expires_at > CURRENT_TIMESTAMP
            """)
    int markGroupChangeActivated(@Param("id") UUID id, @Param("groupId") UUID groupId);

    /**
     * 数据保留预览凭证快照。
     *
     * @param id                 凭证标识
     * @param operatorId         预览管理员标识
     * @param activationType     直接发布或设备组间接激活类型
     * @param changeType         设备组变化类型
     * @param groupVersion       设备组乐观锁版本
     * @param strategyVersionId  已保存策略版本标识
     * @param productId          产品标识
     * @param scopeType          作用域类型
     * @param scopeId            作用域标识
     * @param groupPriority      设备组优先级
     * @param candidateHash      候选策略内容哈希
     * @param resolutionHash     逐设备最终策略解析哈希
     * @param observedAt         预览统计时点
     * @param expiresAt          凭证过期时间
     * @param activatedReleaseId 已绑定发布标识
     * @param activatedAt        激活时间
     */
    record PreviewEvidence(
            UUID id,
            UUID operatorId,
            String activationType,
            String changeType,
            Long groupVersion,
            UUID strategyVersionId,
            UUID productId,
            String scopeType,
            UUID scopeId,
            Integer groupPriority,
            String candidateHash,
            String resolutionHash,
            Instant observedAt,
            Instant expiresAt,
            UUID activatedReleaseId,
            Instant activatedAt
    ) {
    }
}
