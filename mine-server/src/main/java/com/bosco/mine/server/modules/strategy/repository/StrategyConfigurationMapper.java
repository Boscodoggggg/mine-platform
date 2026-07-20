package com.bosco.mine.server.modules.strategy.repository;

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
 * 策略草稿、不可变发布和有效值片段的数据访问接口。
 */
@Mapper
public interface StrategyConfigurationMapper {

    /**
     * 使用事务级咨询锁串行化同一管理员和幂等键，避免并发请求重复创建发布。
     *
     * @param operatorId     操作人标识，内部调用未登录时允许为空
     * @param idempotencyKey 规范化幂等键
     * @return PostgreSQL void 类型的驱动占位值，调用方无需使用
     */
    @Select("""
            SELECT pg_advisory_xact_lock(
                hashtextextended(
                    COALESCE(CAST(#{operatorId} AS TEXT), 'anonymous')
                    || ':' || #{idempotencyKey},
                    0
                )
            )
            """)
    String lockIdempotencyKey(
            @Param("operatorId") UUID operatorId,
            @Param("idempotencyKey") String idempotencyKey
    );

    /**
     * 锁定产品行，串行化同产品策略版本、发布和冲突预检。
     *
     * @param productId 产品标识
     * @return 被锁定的产品标识
     */
    @Select("SELECT id FROM dev_product WHERE id = #{productId} FOR UPDATE")
    UUID lockProduct(@Param("productId") UUID productId);

    /**
     * 计算产品和策略类型内的下一个版本号。
     *
     * @param productId   产品标识
     * @param strategyType 策略类型
     * @return 下一个版本号
     */
    @Select("""
            SELECT COALESCE(MAX(version_number), 0) + 1
              FROM dev_strategy_version
             WHERE product_id = #{productId}
               AND strategy_type = #{strategyType}
            """)
    long nextVersionNumber(
            @Param("productId") UUID productId,
            @Param("strategyType") String strategyType
    );

    /**
     * 新增经过类型校验的策略草稿。
     *
     * @param id            策略版本标识
     * @param productId     产品标识
     * @param strategyType  策略类型
     * @param versionNumber 版本号
     * @param valuesJson    规范化局部字段 JSON
     * @param description   版本说明
     * @param operatorId    操作人标识
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO dev_strategy_version (
                id, product_id, strategy_type, version_number, status, values_json,
                description, created_by, updated_by
            ) VALUES (
                #{id}, #{productId}, #{strategyType}, #{versionNumber}, 'DRAFT',
                CAST(#{valuesJson} AS JSONB), #{description}, #{operatorId}, #{operatorId}
            )
            """)
    int insertVersion(
            @Param("id") UUID id,
            @Param("productId") UUID productId,
            @Param("strategyType") String strategyType,
            @Param("versionNumber") long versionNumber,
            @Param("valuesJson") String valuesJson,
            @Param("description") String description,
            @Param("operatorId") UUID operatorId
    );

    /**
     * 按标识查询策略版本。
     *
     * @param versionId 策略版本标识
     * @return 策略版本快照
     */
    @Select("""
            SELECT id, product_id AS productId, strategy_type AS strategyType,
                   version_number AS versionNumber, status, values_json::TEXT AS valuesJson,
                   content_hash AS contentHash, description,
                   published_at AS publishedAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
              FROM dev_strategy_version
             WHERE id = #{versionId}
            """)
    Optional<StrategyVersionRecord> findVersionById(@Param("versionId") UUID versionId);

    /**
     * 使用排他锁查询待发布策略版本。
     *
     * @param versionId 策略版本标识
     * @return 被锁定的策略版本快照
     */
    @Select("""
            SELECT id, product_id AS productId, strategy_type AS strategyType,
                   version_number AS versionNumber, status, values_json::TEXT AS valuesJson,
                   content_hash AS contentHash, description,
                   published_at AS publishedAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
              FROM dev_strategy_version
             WHERE id = #{versionId}
             FOR UPDATE
            """)
    Optional<StrategyVersionRecord> findVersionForUpdate(@Param("versionId") UUID versionId);

    /**
     * 查询产品下最近的策略版本。
     *
     * @param productId    产品标识
     * @param strategyType 策略类型
     * @param status       可选版本状态
     * @param limit        最大返回数量
     * @return 策略版本列表
     */
    List<StrategyVersionRecord> findVersions(
            @Param("productId") UUID productId,
            @Param("strategyType") String strategyType,
            @Param("status") String status,
            @Param("limit") int limit
    );

    /**
     * 使用乐观锁修改策略草稿。
     *
     * @param versionId      策略版本标识
     * @param valuesJson     规范化局部字段 JSON
     * @param description    版本说明
     * @param expectedVersion 期望乐观锁版本号
     * @param operatorId     操作人标识
     * @return 影响行数
     */
    @Update("""
            UPDATE dev_strategy_version
               SET values_json = CAST(#{valuesJson} AS JSONB),
                   description = #{description},
                   updated_at = CURRENT_TIMESTAMP,
                   updated_by = #{operatorId},
                   version = version + 1
             WHERE id = #{versionId}
               AND status = 'DRAFT'
               AND version = #{expectedVersion}
            """)
    int updateDraft(
            @Param("versionId") UUID versionId,
            @Param("valuesJson") String valuesJson,
            @Param("description") String description,
            @Param("expectedVersion") long expectedVersion,
            @Param("operatorId") UUID operatorId
    );

    /**
     * 使用乐观锁把草稿固化为不可变已发布版本。
     *
     * @param versionId       策略版本标识
     * @param contentHash     规范化字段内容哈希
     * @param operatorId      操作人标识
     * @param expectedVersion 期望乐观锁版本号
     * @return 影响行数
     */
    @Update("""
            UPDATE dev_strategy_version
               SET status = 'PUBLISHED',
                   content_hash = #{contentHash},
                   published_at = CURRENT_TIMESTAMP,
                   published_by = #{operatorId},
                   updated_at = CURRENT_TIMESTAMP,
                   updated_by = #{operatorId},
                   version = version + 1
             WHERE id = #{versionId}
               AND status = 'DRAFT'
               AND version = #{expectedVersion}
            """)
    int publishVersion(
            @Param("versionId") UUID versionId,
            @Param("contentHash") String contentHash,
            @Param("expectedVersion") long expectedVersion,
            @Param("operatorId") UUID operatorId
    );

    /**
     * 计算产品内下一个策略发布序号。
     *
     * @param productId 产品标识
     * @return 下一个发布序号
     */
    @Select("""
            SELECT COALESCE(MAX(release_sequence), 0) + 1
              FROM dev_strategy_release
             WHERE product_id = #{productId}
            """)
    long nextReleaseSequence(@Param("productId") UUID productId);

    /**
     * 按管理员和幂等键查询已有策略发布。
     *
     * @param operatorId     操作人标识
     * @param idempotencyKey 幂等键
     * @return 已有策略发布
     */
    @Select("""
            SELECT release.id, release.product_id AS productId,
                   release.strategy_version_id AS strategyVersionId,
                   release.strategy_type AS strategyType,
                   release.release_sequence AS releaseSequence,
                   release.scope_type AS scopeType, release.scope_id AS scopeId,
                   release.group_priority AS groupPriority, release.action,
                   release.rollback_of_release_id AS rollbackOfReleaseId,
                   release.reason, version.content_hash AS contentHash,
                   version.values_json::TEXT AS valuesJson,
                   release.created_at AS createdAt
              FROM dev_strategy_release release
              JOIN dev_strategy_version version ON version.id = release.strategy_version_id
             WHERE release.created_by IS NOT DISTINCT FROM #{operatorId}
               AND release.idempotency_key = #{idempotencyKey}
             ORDER BY release.created_at DESC
             LIMIT 1
            """)
    Optional<StrategyReleaseRecord> findReleaseByIdempotency(
            @Param("operatorId") UUID operatorId,
            @Param("idempotencyKey") String idempotencyKey
    );

    /**
     * 按标识查询策略发布及其不可变字段。
     *
     * @param releaseId 策略发布标识
     * @return 策略发布快照
     */
    @Select("""
            SELECT release.id, release.product_id AS productId,
                   release.strategy_version_id AS strategyVersionId,
                   release.strategy_type AS strategyType,
                   release.release_sequence AS releaseSequence,
                   release.scope_type AS scopeType, release.scope_id AS scopeId,
                   release.group_priority AS groupPriority, release.action,
                   release.rollback_of_release_id AS rollbackOfReleaseId,
                   release.reason, version.content_hash AS contentHash,
                   version.values_json::TEXT AS valuesJson,
                   release.created_at AS createdAt
              FROM dev_strategy_release release
              JOIN dev_strategy_version version ON version.id = release.strategy_version_id
             WHERE release.id = #{releaseId}
            """)
    Optional<StrategyReleaseRecord> findRelease(@Param("releaseId") UUID releaseId);

    /**
     * 查询产品和策略类型最近的发布历史。
     *
     * @param productId    产品标识
     * @param strategyType 策略类型
     * @param limit        最大返回数量
     * @return 策略发布列表
     */
    @Select("""
            SELECT release.id, release.product_id AS productId,
                   release.strategy_version_id AS strategyVersionId,
                   release.strategy_type AS strategyType,
                   release.release_sequence AS releaseSequence,
                   release.scope_type AS scopeType, release.scope_id AS scopeId,
                   release.group_priority AS groupPriority, release.action,
                   release.rollback_of_release_id AS rollbackOfReleaseId,
                   release.reason, version.content_hash AS contentHash,
                   version.values_json::TEXT AS valuesJson,
                   release.created_at AS createdAt
              FROM dev_strategy_release release
              JOIN dev_strategy_version version ON version.id = release.strategy_version_id
             WHERE release.product_id = #{productId}
               AND release.strategy_type = #{strategyType}
             ORDER BY release.release_sequence DESC
             LIMIT #{limit}
            """)
    List<StrategyReleaseRecord> findReleases(
            @Param("productId") UUID productId,
            @Param("strategyType") String strategyType,
            @Param("limit") int limit
    );

    /**
     * 查询设备组按策略类型划分的当前发布，用于成员和优先级变更预检。
     *
     * @param groupId 设备组标识
     * @return 各策略类型当前发布
     */
    @Select("""
            SELECT DISTINCT ON (release.strategy_type)
                   release.id, release.product_id AS productId,
                   release.strategy_version_id AS strategyVersionId,
                   release.strategy_type AS strategyType,
                   release.release_sequence AS releaseSequence,
                   release.scope_type AS scopeType, release.scope_id AS scopeId,
                   release.group_priority AS groupPriority, release.action,
                   release.rollback_of_release_id AS rollbackOfReleaseId,
                   release.reason, version.content_hash AS contentHash,
                   version.values_json::TEXT AS valuesJson,
                   release.created_at AS createdAt
              FROM dev_strategy_release release
              JOIN dev_strategy_version version ON version.id = release.strategy_version_id
             WHERE release.scope_type = 'GROUP'
               AND release.scope_id = #{groupId}
             ORDER BY release.strategy_type, release.release_sequence DESC, release.id DESC
            """)
    List<StrategyReleaseRecord> findCurrentGroupReleases(@Param("groupId") UUID groupId);

    /**
     * 插入不可变策略发布事实。
     *
     * @param id                  发布标识
     * @param productId           产品标识
     * @param strategyVersionId   策略版本标识
     * @param strategyType        策略类型
     * @param releaseSequence     产品内发布序号
     * @param scopeType           作用范围类型
     * @param scopeId             作用范围标识
     * @param groupPriority       设备组优先级快照
     * @param action              发布动作
     * @param rollbackOfReleaseId 回滚来源发布标识
     * @param reason              发布原因
     * @param idempotencyKey      幂等键
     * @param operatorId          操作人标识
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO dev_strategy_release (
                id, product_id, strategy_version_id, strategy_type, release_sequence,
                scope_type, scope_id, group_priority, action, rollback_of_release_id,
                reason, idempotency_key, created_by
            ) VALUES (
                #{id}, #{productId}, #{strategyVersionId}, #{strategyType}, #{releaseSequence},
                #{scopeType}, #{scopeId}, #{groupPriority}, #{action}, #{rollbackOfReleaseId},
                #{reason}, #{idempotencyKey}, #{operatorId}
            )
            """)
    int insertRelease(
            @Param("id") UUID id,
            @Param("productId") UUID productId,
            @Param("strategyVersionId") UUID strategyVersionId,
            @Param("strategyType") String strategyType,
            @Param("releaseSequence") long releaseSequence,
            @Param("scopeType") String scopeType,
            @Param("scopeId") UUID scopeId,
            @Param("groupPriority") Integer groupPriority,
            @Param("action") String action,
            @Param("rollbackOfReleaseId") UUID rollbackOfReleaseId,
            @Param("reason") String reason,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("operatorId") UUID operatorId
    );

    /**
     * 查询设备所属产品和生命周期状态。
     *
     * @param deviceId 设备标识
     * @return 设备上下文
     */
    @Select("""
            SELECT id, product_id AS productId, status
              FROM dev_device
             WHERE id = #{deviceId}
            """)
    Optional<DeviceContextRecord> findDeviceContext(@Param("deviceId") UUID deviceId);

    /**
     * 批量查询设备所属产品和生命周期状态。
     *
     * @param deviceIds 设备标识集合
     * @return 已存在设备上下文
     */
    List<DeviceContextRecord> findDeviceContexts(@Param("deviceIds") List<UUID> deviceIds);

    /**
     * 锁定并查询单设备策略作用目标，防止发布期间设备被并发归档。
     *
     * @param deviceId 设备标识
     * @return 被锁定的设备上下文
     */
    @Select("""
            SELECT id, product_id AS productId, status
              FROM dev_device
             WHERE id = #{deviceId}
             FOR UPDATE
            """)
    Optional<DeviceContextRecord> findDeviceContextForUpdate(@Param("deviceId") UUID deviceId);

    /**
     * 查询产品下全部未归档设备标识。
     *
     * @param productId 产品标识
     * @return 稳定排序的设备标识
     */
    @Select("""
            SELECT id
              FROM dev_device
             WHERE product_id = #{productId}
               AND status <> 'ARCHIVED'
             ORDER BY id
            """)
    List<UUID> findUnarchivedDeviceIds(@Param("productId") UUID productId);

    /**
     * 批量查询每台设备在产品、当前设备组和单设备范围的最新发布片段。
     *
     * @param productId    产品标识
     * @param strategyType 策略类型
     * @param deviceIds    目标设备标识
     * @return 按设备组织前的当前发布片段
     */
    List<StrategyFragmentRecord> findCurrentFragmentsForDevices(
            @Param("productId") UUID productId,
            @Param("strategyType") String strategyType,
            @Param("deviceIds") List<UUID> deviceIds
    );

    /**
     * 策略版本持久化快照。
     *
     * @param id            策略版本标识
     * @param productId     产品标识
     * @param strategyType  策略类型
     * @param versionNumber 版本号
     * @param status        草稿或已发布状态
     * @param valuesJson    局部字段 JSON
     * @param contentHash   已发布内容哈希
     * @param description   版本说明
     * @param publishedAt   发布时间
     * @param createdAt     创建时间
     * @param updatedAt     更新时间
     * @param version       乐观锁版本号
     */
    record StrategyVersionRecord(
            UUID id,
            UUID productId,
            String strategyType,
            long versionNumber,
            String status,
            String valuesJson,
            String contentHash,
            String description,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
    }

    /**
     * 策略发布和不可变版本字段快照。
     *
     * @param id                  发布标识
     * @param productId           产品标识
     * @param strategyVersionId   策略版本标识
     * @param strategyType        策略类型
     * @param releaseSequence     产品内发布序号
     * @param scopeType           作用范围类型
     * @param scopeId             作用范围标识
     * @param groupPriority       设备组优先级快照
     * @param action              发布或回滚动作
     * @param rollbackOfReleaseId 回滚来源发布标识
     * @param reason              发布原因
     * @param contentHash         版本内容哈希
     * @param valuesJson          局部字段 JSON
     * @param createdAt           发布时间
     */
    record StrategyReleaseRecord(
            UUID id,
            UUID productId,
            UUID strategyVersionId,
            String strategyType,
            long releaseSequence,
            String scopeType,
            UUID scopeId,
            Integer groupPriority,
            String action,
            UUID rollbackOfReleaseId,
            String reason,
            String contentHash,
            String valuesJson,
            Instant createdAt
    ) {
    }

    /**
     * 一台设备可消费的当前策略片段。
     *
     * @param deviceId      设备标识
     * @param releaseId     当前发布标识
     * @param scopeType     作用范围类型
     * @param scopeId       作用范围标识
     * @param groupPriority 设备组优先级快照
     * @param valuesJson    局部字段 JSON
     */
    record StrategyFragmentRecord(
            UUID deviceId,
            UUID releaseId,
            String scopeType,
            UUID scopeId,
            Integer groupPriority,
            String valuesJson
    ) {
    }

    /**
     * 设备产品归属和状态快照。
     *
     * @param id        设备标识
     * @param productId 所属产品标识
     * @param status    生命周期状态
     */
    record DeviceContextRecord(UUID id, UUID productId, String status) {
    }
}
