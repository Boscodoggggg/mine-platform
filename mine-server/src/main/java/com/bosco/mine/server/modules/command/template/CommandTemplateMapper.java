package com.bosco.mine.server.modules.command.template;

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
 * 命令模板、不可变版本和产品绑定的数据访问接口。
 */
@Mapper
public interface CommandTemplateMapper {

    /** 查询全部模板版本。 */
    List<TemplateRecord> findAll();

    /** 按模板编码查询最新版本。 */
    Optional<TemplateRecord> findByTemplateKey(@Param("templateKey") String templateKey);

    /** 按版本标识查询模板。 */
    Optional<TemplateRecord> findByVersionId(@Param("versionId") UUID versionId);

    /** 查询设备所属产品已发布的自定义模板。 */
    List<TemplateRecord> findPublishedByDeviceId(@Param("deviceId") UUID deviceId);

    /** 查询设备可用的指定已发布模板。 */
    Optional<TemplateRecord> findPublishedByDeviceAndKey(
            @Param("deviceId") UUID deviceId,
            @Param("templateKey") String templateKey
    );

    /** 查询模板版本绑定产品。 */
    @Select("""
            SELECT product_id
            FROM cmd_template_binding
            WHERE template_version_id = #{versionId}
            ORDER BY product_id
            """)
    List<UUID> findProductIds(@Param("versionId") UUID versionId);

    /** 判断稳定模板是否已经存在草稿版本。 */
    @Select("SELECT COUNT(*) > 0 FROM cmd_template_version WHERE template_id = #{templateId} AND status = 'DRAFT'")
    boolean draftExists(@Param("templateId") UUID templateId);

    /** 计算稳定模板的下一个单调版本号。 */
    @Select("SELECT COALESCE(MAX(version_number), 0) + 1 FROM cmd_template_version WHERE template_id = #{templateId}")
    int nextVersionNumber(@Param("templateId") UUID templateId);

    /** 查询允许绑定命令模板的活动产品。 */
    @Select("""
            SELECT id, product_key AS productKey, name
            FROM dev_product
            WHERE status = 'ACTIVE'
            ORDER BY name, product_key, id
            """)
    List<CommandTemplateProductResponse> findBindableProducts();

    /** 判断产品是否存在且未归档。 */
    @Select("SELECT COUNT(*) > 0 FROM dev_product WHERE id = #{productId} AND status <> 'ARCHIVED'")
    boolean productExists(@Param("productId") UUID productId);

    /** 插入稳定模板标识。 */
    @Insert("""
            INSERT INTO cmd_template (
                id, template_key, created_by, updated_by
            ) VALUES (
                #{templateId}, #{templateKey}, #{operatorId}, #{operatorId}
            )
            """)
    int insertTemplate(
            @Param("templateId") UUID templateId,
            @Param("templateKey") String templateKey,
            @Param("operatorId") UUID operatorId
    );

    /** 插入模板草稿版本。 */
    @Insert("""
            INSERT INTO cmd_template_version (
                id, template_id, version_number, name_zh, name_en, description,
                payload_format, parameter_schema, raw_payload, raw_payload_hex,
                raw_payload_sha256, default_ttl_seconds, default_timeout_seconds,
                requires_reason, reply_expected, created_by, updated_by
            ) VALUES (
                #{draft.versionId}, #{draft.templateId}, #{draft.versionNumber},
                #{draft.nameZh}, #{draft.nameEn}, #{draft.description},
                #{draft.payloadFormat}, CAST(#{draft.parameterSchemaJson} AS JSONB),
                #{draft.rawPayload}, #{draft.rawPayloadHex}, #{draft.rawPayloadSha256},
                #{draft.defaultTtlSeconds}, #{draft.defaultTimeoutSeconds},
                #{draft.requiresReason}, #{draft.replyExpected},
                #{draft.operatorId}, #{draft.operatorId}
            )
            """)
    int insertVersion(@Param("draft") TemplateDraft draft);

    /** 使用乐观锁替换草稿内容。 */
    @Update("""
            UPDATE cmd_template_version
            SET name_zh = #{draft.nameZh},
                name_en = #{draft.nameEn},
                description = #{draft.description},
                payload_format = #{draft.payloadFormat},
                parameter_schema = CAST(#{draft.parameterSchemaJson} AS JSONB),
                raw_payload = #{draft.rawPayload},
                raw_payload_hex = #{draft.rawPayloadHex},
                raw_payload_sha256 = #{draft.rawPayloadSha256},
                default_ttl_seconds = #{draft.defaultTtlSeconds},
                default_timeout_seconds = #{draft.defaultTimeoutSeconds},
                requires_reason = #{draft.requiresReason},
                reply_expected = #{draft.replyExpected},
                updated_by = #{draft.operatorId},
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = #{draft.versionId}
              AND status = 'DRAFT'
              AND version = #{expectedVersion}
            """)
    int updateDraft(
            @Param("draft") TemplateDraft draft,
            @Param("expectedVersion") long expectedVersion
    );

    /** 删除草稿的旧产品绑定。 */
    @Delete("DELETE FROM cmd_template_binding WHERE template_version_id = #{versionId}")
    int deleteBindings(@Param("versionId") UUID versionId);

    /** 插入模板产品绑定。 */
    @Insert("""
            INSERT INTO cmd_template_binding (
                id, template_version_id, product_id, created_by
            ) VALUES (
                #{bindingId}, #{versionId}, #{productId}, #{operatorId}
            )
            """)
    int insertBinding(
            @Param("bindingId") UUID bindingId,
            @Param("versionId") UUID versionId,
            @Param("productId") UUID productId,
            @Param("operatorId") UUID operatorId
    );

    /** 退役同一模板的旧发布版本。 */
    @Update("""
            UPDATE cmd_template_version
            SET status = 'RETIRED', updated_by = #{operatorId}, updated_at = #{occurredAt},
                version = version + 1
            WHERE template_id = #{templateId}
              AND id <> #{currentVersionId}
              AND status = 'PUBLISHED'
            """)
    int retirePublished(
            @Param("templateId") UUID templateId,
            @Param("currentVersionId") UUID currentVersionId,
            @Param("operatorId") UUID operatorId,
            @Param("occurredAt") Instant occurredAt
    );

    /** 使用乐观锁发布草稿。 */
    @Update("""
            UPDATE cmd_template_version
            SET status = 'PUBLISHED', published_by = #{operatorId}, published_at = #{occurredAt},
                updated_by = #{operatorId}, updated_at = #{occurredAt}, version = version + 1
            WHERE id = #{versionId} AND status = 'DRAFT' AND version = #{expectedVersion}
            """)
    int publishVersion(
            @Param("versionId") UUID versionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("operatorId") UUID operatorId,
            @Param("occurredAt") Instant occurredAt
    );

    /**
     * 模板版本数据库投影。
     */
    record TemplateRecord(
            UUID templateId,
            String templateKey,
            String templateStatus,
            long templateVersion,
            UUID versionId,
            int versionNumber,
            String versionStatus,
            String nameZh,
            String nameEn,
            String description,
            String riskLevel,
            String payloadFormat,
            String parameterSchemaJson,
            byte[] rawPayload,
            String rawPayloadHex,
            String rawPayloadSha256,
            int defaultTtlSeconds,
            int defaultTimeoutSeconds,
            boolean requiresReason,
            boolean replyExpected,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            long version
    ) {
    }

    /**
     * 经过服务层校验的模板草稿写入参数。
     */
    record TemplateDraft(
            UUID templateId,
            UUID versionId,
            int versionNumber,
            String nameZh,
            String nameEn,
            String description,
            String payloadFormat,
            String parameterSchemaJson,
            byte[] rawPayload,
            String rawPayloadHex,
            String rawPayloadSha256,
            int defaultTtlSeconds,
            int defaultTimeoutSeconds,
            boolean requiresReason,
            boolean replyExpected,
            UUID operatorId
    ) {
        /** 创建时复制原始载荷，防止调用方修改写入参数。 */
        public TemplateDraft {
            rawPayload = rawPayload == null ? null : rawPayload.clone();
        }

        /** @return 原始载荷副本 */
        @Override
        public byte[] rawPayload() {
            return rawPayload == null ? null : rawPayload.clone();
        }
    }
}
