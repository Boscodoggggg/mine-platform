package com.bosco.mine.server.modules.configuration.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 协议黄金样本、不可变配置包和发布事实的数据访问接口。
 */
@Mapper
public interface ProtocolConfigurationMapper {

    /** 读取协议映射及所属产品信息。 */
    @Select("""
            SELECT mapping.id AS mappingVersionId, mapping.product_id AS productId,
                   product.product_key AS productKey, mapping.profile_key AS profileKey,
                   mapping.version_number AS versionNumber, mapping.status,
                   mapping.protocol_type AS protocolType,
                   mapping.metadata_paths::TEXT AS metadataPathsJson, mapping.version
            FROM dev_protocol_mapping_version mapping
            JOIN dev_product product ON product.id = mapping.product_id
            WHERE mapping.id = #{mappingVersionId}
            """)
    Optional<ProtocolDefinitionRecord> findDefinition(
            @Param("mappingVersionId") UUID mappingVersionId
    );

    /** 使用排他锁读取协议映射及所属产品信息。 */
    @Select("""
            SELECT mapping.id AS mappingVersionId, mapping.product_id AS productId,
                   product.product_key AS productKey, mapping.profile_key AS profileKey,
                   mapping.version_number AS versionNumber, mapping.status,
                   mapping.protocol_type AS protocolType,
                   mapping.metadata_paths::TEXT AS metadataPathsJson, mapping.version
            FROM dev_protocol_mapping_version mapping
            JOIN dev_product product ON product.id = mapping.product_id
            WHERE mapping.id = #{mappingVersionId}
            FOR UPDATE OF mapping
            """)
    Optional<ProtocolDefinitionRecord> findDefinitionForUpdate(
            @Param("mappingVersionId") UUID mappingVersionId
    );

    /** 锁定产品行，串行化同一产品的配置包和发布序号分配。 */
    @Select("SELECT id FROM dev_product WHERE id = #{productId} FOR UPDATE")
    UUID lockProduct(@Param("productId") UUID productId);

    /** 查询产品当前已发布物模型。 */
    @Select("""
            SELECT id, product_id AS productId, version_number AS versionNumber
            FROM dev_model_version
            WHERE product_id = #{productId} AND status = 'PUBLISHED'
            """)
    Optional<ModelVersionRecord> findPublishedModel(@Param("productId") UUID productId);

    /** 查询物模型属性类型。 */
    @Select("""
            SELECT identifier, data_type AS dataType
            FROM dev_model_field
            WHERE model_version_id = #{modelVersionId}
            ORDER BY sort_order, id
            """)
    List<ModelFieldRecord> findModelFields(@Param("modelVersionId") UUID modelVersionId);

    /** 查询协议字段和受控转换参数。 */
    @Select("""
            SELECT source_path AS sourcePath, source_data_type AS sourceDataType,
                   target_identifier AS targetIdentifier, transform_type AS transformType,
                   scale_factor AS scaleFactor, offset_value AS offsetValue,
                   enum_mapping::TEXT AS enumMappingJson, required_field AS requiredField
            FROM dev_protocol_field_mapping
            WHERE mapping_version_id = #{mappingVersionId}
            ORDER BY sort_order, id
            """)
    List<ProtocolFieldRecord> findProtocolFields(@Param("mappingVersionId") UUID mappingVersionId);

    /** 查询协议版本的全部黄金样本。 */
    @Select("""
            SELECT id, mapping_version_id AS mappingVersionId, name,
                   raw_payload::TEXT AS rawPayloadJson, expected_success AS expectedSuccess,
                   expected_properties::TEXT AS expectedPropertiesJson,
                   expected_error_code AS expectedErrorCode, sort_order AS sortOrder, version
            FROM dev_protocol_sample
            WHERE mapping_version_id = #{mappingVersionId}
            ORDER BY sort_order, id
            """)
    List<SampleRecord> findSamples(@Param("mappingVersionId") UUID mappingVersionId);

    /** 使用乐观锁标记协议草稿已修改。 */
    @Update("""
            UPDATE dev_protocol_mapping_version
            SET updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId}, version = version + 1
            WHERE id = #{mappingVersionId} AND status = 'DRAFT' AND version = #{version}
            """)
    int touchDraft(
            @Param("mappingVersionId") UUID mappingVersionId,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId
    );

    /** 删除草稿下的旧样本。 */
    @Delete("DELETE FROM dev_protocol_sample WHERE mapping_version_id = #{mappingVersionId}")
    int deleteSamples(@Param("mappingVersionId") UUID mappingVersionId);

    /** 插入一条黄金样本。 */
    @Insert("""
            INSERT INTO dev_protocol_sample (
                id, mapping_version_id, name, raw_payload, expected_success,
                expected_properties, expected_error_code, sort_order, created_by, updated_by
            ) VALUES (
                #{id}, #{mappingVersionId}, #{name}, CAST(#{rawPayloadJson} AS JSONB),
                #{expectedSuccess}, CAST(#{expectedPropertiesJson} AS JSONB),
                #{expectedErrorCode}, #{sortOrder}, #{operatorId}, #{operatorId}
            )
            """)
    int insertSample(
            @Param("id") UUID id,
            @Param("mappingVersionId") UUID mappingVersionId,
            @Param("name") String name,
            @Param("rawPayloadJson") String rawPayloadJson,
            @Param("expectedSuccess") boolean expectedSuccess,
            @Param("expectedPropertiesJson") String expectedPropertiesJson,
            @Param("expectedErrorCode") String expectedErrorCode,
            @Param("sortOrder") int sortOrder,
            @Param("operatorId") UUID operatorId
    );

    /** 保存一次不可变黄金样本测试结果。 */
    @Insert("""
            INSERT INTO dev_protocol_test_run (
                id, mapping_version_id, definition_hash, status, total_samples,
                passed_samples, failed_samples, result_json, started_at, completed_at, triggered_by
            ) VALUES (
                #{id}, #{mappingVersionId}, #{definitionHash}, #{status}, #{totalSamples},
                #{passedSamples}, #{failedSamples}, CAST(#{resultJson} AS JSONB),
                #{startedAt}, #{completedAt}, #{operatorId}
            )
            """)
    int insertTestRun(
            @Param("id") UUID id,
            @Param("mappingVersionId") UUID mappingVersionId,
            @Param("definitionHash") String definitionHash,
            @Param("status") String status,
            @Param("totalSamples") int totalSamples,
            @Param("passedSamples") int passedSamples,
            @Param("failedSamples") int failedSamples,
            @Param("resultJson") String resultJson,
            @Param("startedAt") Instant startedAt,
            @Param("completedAt") Instant completedAt,
            @Param("operatorId") UUID operatorId
    );

    /** 计算产品的下一个配置包序号。 */
    @Select("SELECT COALESCE(MAX(package_sequence), 0) + 1 FROM dev_config_package WHERE product_id = #{productId}")
    long nextPackageSequence(@Param("productId") UUID productId);

    /** 按内容哈希查找已存在的不可变配置包。 */
    @Select("""
            SELECT id, product_id AS productId, package_sequence AS packageSequence,
                   model_version_id AS modelVersionId,
                   protocol_mapping_version_id AS protocolMappingVersionId,
                   content_hash AS contentHash, snapshot::TEXT AS snapshotJson
            FROM dev_config_package
            WHERE product_id = #{productId} AND content_hash = #{contentHash}
            """)
    Optional<PackageRecord> findPackageByHash(
            @Param("productId") UUID productId,
            @Param("contentHash") String contentHash
    );

    /** 插入不可变配置包。 */
    @Insert("""
            INSERT INTO dev_config_package (
                id, product_id, package_sequence, model_version_id,
                protocol_mapping_version_id, content_hash, snapshot, change_reason, created_by
            ) VALUES (
                #{id}, #{productId}, #{packageSequence}, #{modelVersionId},
                #{protocolMappingVersionId}, #{contentHash}, CAST(#{snapshotJson} AS JSONB),
                #{changeReason}, #{operatorId}
            )
            """)
    int insertPackage(
            @Param("id") UUID id,
            @Param("productId") UUID productId,
            @Param("packageSequence") long packageSequence,
            @Param("modelVersionId") UUID modelVersionId,
            @Param("protocolMappingVersionId") UUID protocolMappingVersionId,
            @Param("contentHash") String contentHash,
            @Param("snapshotJson") String snapshotJson,
            @Param("changeReason") String changeReason,
            @Param("operatorId") UUID operatorId
    );

    /** 计算产品的下一个发布序号。 */
    @Select("SELECT COALESCE(MAX(release_sequence), 0) + 1 FROM dev_config_release WHERE product_id = #{productId}")
    long nextReleaseSequence(@Param("productId") UUID productId);

    /** 按管理员和幂等键查询原发布。 */
    @Select("""
            SELECT release.id, release.product_id AS productId, release.package_id AS packageId,
                   release.release_sequence AS releaseSequence, release.action,
                   release.rollback_of_release_id AS rollbackOfReleaseId,
                   package.content_hash AS contentHash, package.snapshot::TEXT AS snapshotJson
            FROM dev_config_release release
            JOIN dev_config_package package ON package.id = release.package_id
            WHERE release.created_by IS NOT DISTINCT FROM #{operatorId}
              AND release.idempotency_key = #{idempotencyKey}
            ORDER BY release.created_at DESC LIMIT 1
            """)
    Optional<ReleaseRecord> findReleaseByIdempotency(
            @Param("operatorId") UUID operatorId,
            @Param("idempotencyKey") String idempotencyKey
    );

    /** 按发布标识查询发布及完整快照。 */
    @Select("""
            SELECT release.id, release.product_id AS productId, release.package_id AS packageId,
                   release.release_sequence AS releaseSequence, release.action,
                   release.rollback_of_release_id AS rollbackOfReleaseId,
                   package.content_hash AS contentHash, package.snapshot::TEXT AS snapshotJson
            FROM dev_config_release release
            JOIN dev_config_package package ON package.id = release.package_id
            WHERE release.id = #{releaseId}
            """)
    Optional<ReleaseRecord> findRelease(@Param("releaseId") UUID releaseId);

    /** 按产品倒序查询最近的配置发布摘要。 */
    @Select("""
            SELECT release.id, release.product_id AS productId, release.package_id AS packageId,
                   release.release_sequence AS releaseSequence, release.action,
                   release.rollback_of_release_id AS rollbackOfReleaseId,
                   package.content_hash AS contentHash, release.reason, release.created_at AS createdAt
            FROM dev_config_release release
            JOIN dev_config_package package ON package.id = release.package_id
            WHERE release.product_id = #{productId}
            ORDER BY release.release_sequence DESC
            LIMIT #{limit}
            """)
    List<ReleaseSummaryRecord> findReleaseSummaries(
            @Param("productId") UUID productId,
            @Param("limit") int limit
    );

    /** 按发布标识查询不含完整快照的管理摘要。 */
    @Select("""
            SELECT release.id, release.product_id AS productId, release.package_id AS packageId,
                   release.release_sequence AS releaseSequence, release.action,
                   release.rollback_of_release_id AS rollbackOfReleaseId,
                   package.content_hash AS contentHash, release.reason, release.created_at AS createdAt
            FROM dev_config_release release
            JOIN dev_config_package package ON package.id = release.package_id
            WHERE release.id = #{releaseId}
            """)
    Optional<ReleaseSummaryRecord> findReleaseSummary(@Param("releaseId") UUID releaseId);

    /** 查询指定发布的全部运行节点加载状态。 */
    @Select("""
            SELECT node_id AS nodeId, node_type AS nodeType, status, actual_hash AS actualHash,
                   message, loaded_at AS loadedAt, updated_at AS updatedAt
            FROM dev_config_node_status
            WHERE release_id = #{releaseId}
            ORDER BY node_type, node_id
            """)
    List<NodeStatusRecord> findNodeStatuses(@Param("releaseId") UUID releaseId);

    /** 插入不可变配置发布。 */
    @Insert("""
            INSERT INTO dev_config_release (
                id, product_id, package_id, release_sequence, action,
                rollback_of_release_id, reason, idempotency_key, created_by
            ) VALUES (
                #{id}, #{productId}, #{packageId}, #{releaseSequence}, #{action},
                #{rollbackOfReleaseId}, #{reason}, #{idempotencyKey}, #{operatorId}
            )
            """)
    int insertRelease(
            @Param("id") UUID id,
            @Param("productId") UUID productId,
            @Param("packageId") UUID packageId,
            @Param("releaseSequence") long releaseSequence,
            @Param("action") String action,
            @Param("rollbackOfReleaseId") UUID rollbackOfReleaseId,
            @Param("reason") String reason,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("operatorId") UUID operatorId
    );

    /** 插入配置发布作用目标。 */
    @Insert("""
            INSERT INTO dev_config_release_target (id, release_id, target_type, target_id)
            VALUES (#{id}, #{releaseId}, #{targetType}, #{targetId})
            """)
    int insertReleaseTarget(
            @Param("id") UUID id,
            @Param("releaseId") UUID releaseId,
            @Param("targetType") String targetType,
            @Param("targetId") UUID targetId
    );

    /** 在同一事务写入只携带发布标识和哈希的 Outbox 事件。 */
    @Insert("""
            INSERT INTO sys_outbox_event (
                id, aggregate_type, aggregate_id, event_type, schema_version, payload, headers
            ) VALUES (
                #{eventId}, 'CONFIG_RELEASE', #{releaseId}, 'config.release.created', 1,
                CAST(#{payloadJson} AS JSONB), CAST(#{headersJson} AS JSONB)
            )
            """)
    int insertReleaseOutbox(
            @Param("eventId") UUID eventId,
            @Param("releaseId") UUID releaseId,
            @Param("payloadJson") String payloadJson,
            @Param("headersJson") String headersJson
    );

    /** 新增或刷新运行节点对一次配置发布的加载状态。 */
    @Insert("""
            INSERT INTO dev_config_node_status (
                release_id, node_id, node_type, status, actual_hash, message, loaded_at, updated_at
            ) VALUES (
                #{releaseId}, #{nodeId}, #{nodeType}, #{status}, #{actualHash}, #{message},
                CASE WHEN #{status} = 'LOADED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                CURRENT_TIMESTAMP
            )
            ON CONFLICT (release_id, node_id) DO UPDATE SET
                node_type = EXCLUDED.node_type,
                status = EXCLUDED.status,
                actual_hash = EXCLUDED.actual_hash,
                message = EXCLUDED.message,
                loaded_at = EXCLUDED.loaded_at,
                updated_at = CURRENT_TIMESTAMP
            """)
    int upsertNodeStatus(
            @Param("releaseId") UUID releaseId,
            @Param("nodeId") String nodeId,
            @Param("nodeType") String nodeType,
            @Param("status") String status,
            @Param("actualHash") String actualHash,
            @Param("message") String message
    );

    /** 协议映射发布信息。 */
    record ProtocolDefinitionRecord(
            UUID mappingVersionId,
            UUID productId,
            String productKey,
            String profileKey,
            int versionNumber,
            String status,
            String protocolType,
            String metadataPathsJson,
            long version
    ) {
    }

    /** 已发布物模型版本。 */
    record ModelVersionRecord(UUID id, UUID productId, int versionNumber) {
    }

    /** 物模型字段类型。 */
    record ModelFieldRecord(String identifier, String dataType) {
    }

    /** 协议字段映射与转换参数。 */
    record ProtocolFieldRecord(
            String sourcePath,
            String sourceDataType,
            String targetIdentifier,
            String transformType,
            BigDecimal scaleFactor,
            BigDecimal offsetValue,
            String enumMappingJson,
            boolean requiredField
    ) {
    }

    /** 协议黄金样本。 */
    record SampleRecord(
            UUID id,
            UUID mappingVersionId,
            String name,
            String rawPayloadJson,
            boolean expectedSuccess,
            String expectedPropertiesJson,
            String expectedErrorCode,
            int sortOrder,
            long version
    ) {
    }

    /** 不可变配置包。 */
    record PackageRecord(
            UUID id,
            UUID productId,
            long packageSequence,
            UUID modelVersionId,
            UUID protocolMappingVersionId,
            String contentHash,
            String snapshotJson
    ) {
    }

    /** 配置发布和快照投影。 */
    record ReleaseRecord(
            UUID id,
            UUID productId,
            UUID packageId,
            long releaseSequence,
            String action,
            UUID rollbackOfReleaseId,
            String contentHash,
            String snapshotJson
    ) {
    }

    /** 配置发布管理摘要，不包含运行节点使用的完整快照。 */
    record ReleaseSummaryRecord(
            UUID id,
            UUID productId,
            UUID packageId,
            long releaseSequence,
            String action,
            UUID rollbackOfReleaseId,
            String contentHash,
            String reason,
            Instant createdAt
    ) {
    }

    /** 运行节点加载状态投影。 */
    record NodeStatusRecord(
            String nodeId,
            String nodeType,
            String status,
            String actualHash,
            String message,
            Instant loadedAt,
            Instant updatedAt
    ) {
    }
}
