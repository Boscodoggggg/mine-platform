package com.bosco.mine.server.modules.device.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 设备物模型版本和协议字段映射版本的数据访问接口。
 */
@Mapper
public interface DeviceModelManagementMapper {

    /** 判断设备产品是否存在。 */
    @Select("SELECT EXISTS (SELECT 1 FROM dev_product WHERE id = #{productId} AND status <> 'ARCHIVED')")
    boolean productExists(@Param("productId") UUID productId);

    /** 查询产品全部物模型版本。 */
    @Select("""
            SELECT id, product_id AS productId, version_number AS versionNumber,
                   status, description, version
            FROM dev_model_version WHERE product_id = #{productId}
            ORDER BY version_number DESC
            """)
    List<ModelVersionRecord> findModelVersions(@Param("productId") UUID productId);

    /** 查询物模型版本。 */
    @Select("""
            SELECT id, product_id AS productId, version_number AS versionNumber,
                   status, description, version
            FROM dev_model_version WHERE id = #{id}
            """)
    Optional<ModelVersionRecord> findModelVersion(@Param("id") UUID id);

    /** 计算产品的下一个物模型版本号。 */
    @Select("SELECT COALESCE(MAX(version_number), 0) + 1 FROM dev_model_version WHERE product_id = #{productId}")
    int nextModelVersionNumber(@Param("productId") UUID productId);

    /** 新增物模型草稿。 */
    @Insert("""
            INSERT INTO dev_model_version (
                id, product_id, version_number, description, created_by, updated_by
            ) VALUES (
                #{id}, #{productId}, #{versionNumber}, #{description}, #{operatorId}, #{operatorId}
            )
            """)
    int insertModelVersion(
            @Param("id") UUID id,
            @Param("productId") UUID productId,
            @Param("versionNumber") int versionNumber,
            @Param("description") String description,
            @Param("operatorId") UUID operatorId
    );

    /** 查询物模型字段。 */
    @Select("""
            SELECT id, model_version_id AS modelVersionId, identifier,
                   name_zh AS nameZh, name_en AS nameEn, data_type AS dataType,
                   unit, access_mode AS accessMode, required_field AS requiredField,
                   minimum_value AS minimumValue, maximum_value AS maximumValue,
                   decimal_places AS decimalPlaces, sort_order AS sortOrder,
                   description, version
            FROM dev_model_field WHERE model_version_id = #{versionId}
            ORDER BY sort_order, id
            """)
    List<ModelFieldRecord> findModelFields(@Param("versionId") UUID versionId);

    /** 统计物模型字段数量。 */
    @Select("SELECT COUNT(*) FROM dev_model_field WHERE model_version_id = #{versionId}")
    long countModelFields(@Param("versionId") UUID versionId);

    /** 删除物模型草稿下的全部字段。 */
    @Delete("DELETE FROM dev_model_field WHERE model_version_id = #{versionId}")
    int deleteModelFields(@Param("versionId") UUID versionId);

    /** 新增物模型字段。 */
    @Insert("""
            INSERT INTO dev_model_field (
                id, model_version_id, identifier, name_zh, name_en, data_type,
                unit, access_mode, required_field, minimum_value, maximum_value,
                decimal_places, sort_order, description, created_by, updated_by
            ) VALUES (
                #{id}, #{modelVersionId}, #{identifier}, #{nameZh}, #{nameEn}, #{dataType},
                #{unit}, #{accessMode}, #{requiredField}, #{minimumValue}, #{maximumValue},
                #{decimalPlaces}, #{sortOrder}, #{description}, #{operatorId}, #{operatorId}
            )
            """)
    int insertModelField(
            @Param("id") UUID id,
            @Param("modelVersionId") UUID modelVersionId,
            @Param("identifier") String identifier,
            @Param("nameZh") String nameZh,
            @Param("nameEn") String nameEn,
            @Param("dataType") String dataType,
            @Param("unit") String unit,
            @Param("accessMode") String accessMode,
            @Param("requiredField") boolean requiredField,
            @Param("minimumValue") BigDecimal minimumValue,
            @Param("maximumValue") BigDecimal maximumValue,
            @Param("decimalPlaces") Integer decimalPlaces,
            @Param("sortOrder") int sortOrder,
            @Param("description") String description,
            @Param("operatorId") UUID operatorId
    );

    /** 将产品当前已发布物模型退役。 */
    @Update("""
            UPDATE dev_model_version
            SET status = 'RETIRED', updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId}, version = version + 1
            WHERE product_id = #{productId} AND status = 'PUBLISHED'
            """)
    int retirePublishedModel(@Param("productId") UUID productId, @Param("operatorId") UUID operatorId);

    /** 使用乐观锁发布物模型草稿。 */
    @Update("""
            UPDATE dev_model_version
            SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP, published_by = #{operatorId},
                updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId}, version = version + 1
            WHERE id = #{id} AND version = #{version} AND status = 'DRAFT'
            """)
    int publishModelVersion(
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId
    );

    /** 查询产品全部协议映射版本。 */
    @Select("""
            SELECT id, product_id AS productId, profile_key AS profileKey, name,
                   protocol_type AS protocolType, message_type AS messageType,
                   version_number AS versionNumber, status,
                   topic_or_frame AS topicOrFrame, description, version
            FROM dev_protocol_mapping_version WHERE product_id = #{productId}
            ORDER BY profile_key, version_number DESC
            """)
    List<ProtocolVersionRecord> findProtocolVersions(@Param("productId") UUID productId);

    /** 查询协议映射版本。 */
    @Select("""
            SELECT id, product_id AS productId, profile_key AS profileKey, name,
                   protocol_type AS protocolType, message_type AS messageType,
                   version_number AS versionNumber, status,
                   topic_or_frame AS topicOrFrame, description, version
            FROM dev_protocol_mapping_version WHERE id = #{id}
            """)
    Optional<ProtocolVersionRecord> findProtocolVersion(@Param("id") UUID id);

    /** 计算协议配置的下一个版本号。 */
    @Select("""
            SELECT COALESCE(MAX(version_number), 0) + 1
            FROM dev_protocol_mapping_version
            WHERE product_id = #{productId} AND profile_key = #{profileKey}
            """)
    int nextProtocolVersionNumber(@Param("productId") UUID productId, @Param("profileKey") String profileKey);

    /** 新增协议映射草稿。 */
    @Insert("""
            INSERT INTO dev_protocol_mapping_version (
                id, product_id, profile_key, name, protocol_type, message_type,
                version_number, topic_or_frame, description, created_by, updated_by
            ) VALUES (
                #{id}, #{productId}, #{profileKey}, #{name}, #{protocolType}, #{messageType},
                #{versionNumber}, #{topicOrFrame}, #{description}, #{operatorId}, #{operatorId}
            )
            """)
    int insertProtocolVersion(
            @Param("id") UUID id,
            @Param("productId") UUID productId,
            @Param("profileKey") String profileKey,
            @Param("name") String name,
            @Param("protocolType") String protocolType,
            @Param("messageType") String messageType,
            @Param("versionNumber") int versionNumber,
            @Param("topicOrFrame") String topicOrFrame,
            @Param("description") String description,
            @Param("operatorId") UUID operatorId
    );

    /** 查询协议字段映射。 */
    @Select("""
            SELECT id, mapping_version_id AS mappingVersionId, source_path AS sourcePath,
                   source_data_type AS sourceDataType, target_identifier AS targetIdentifier,
                   transform_type AS transformType, scale_factor AS scaleFactor,
                   offset_value AS offsetValue, expression, required_field AS requiredField,
                   sort_order AS sortOrder, version
            FROM dev_protocol_field_mapping WHERE mapping_version_id = #{versionId}
            ORDER BY sort_order, id
            """)
    List<ProtocolFieldRecord> findProtocolFields(@Param("versionId") UUID versionId);

    /** 删除协议草稿下的全部字段映射。 */
    @Delete("DELETE FROM dev_protocol_field_mapping WHERE mapping_version_id = #{versionId}")
    int deleteProtocolFields(@Param("versionId") UUID versionId);

    /** 新增协议字段映射。 */
    @Insert("""
            INSERT INTO dev_protocol_field_mapping (
                id, mapping_version_id, source_path, source_data_type, target_identifier,
                transform_type, scale_factor, offset_value, expression,
                required_field, sort_order, created_by, updated_by
            ) VALUES (
                #{id}, #{mappingVersionId}, #{sourcePath}, #{sourceDataType}, #{targetIdentifier},
                #{transformType}, #{scaleFactor}, #{offsetValue}, #{expression},
                #{requiredField}, #{sortOrder}, #{operatorId}, #{operatorId}
            )
            """)
    int insertProtocolField(
            @Param("id") UUID id,
            @Param("mappingVersionId") UUID mappingVersionId,
            @Param("sourcePath") String sourcePath,
            @Param("sourceDataType") String sourceDataType,
            @Param("targetIdentifier") String targetIdentifier,
            @Param("transformType") String transformType,
            @Param("scaleFactor") BigDecimal scaleFactor,
            @Param("offsetValue") BigDecimal offsetValue,
            @Param("expression") String expression,
            @Param("requiredField") boolean requiredField,
            @Param("sortOrder") int sortOrder,
            @Param("operatorId") UUID operatorId
    );

    /** 统计发布物模型中存在的目标字段数量。 */
    @Select("""
            <script>
            SELECT COUNT(DISTINCT field.identifier)
            FROM dev_model_field field
            JOIN dev_model_version model ON model.id = field.model_version_id
            WHERE model.product_id = #{productId} AND model.status = 'PUBLISHED'
              AND field.identifier IN
              <foreach collection="identifiers" item="identifier" open="(" separator="," close=")">
                  #{identifier}
              </foreach>
            </script>
            """)
    long countPublishedModelIdentifiers(
            @Param("productId") UUID productId,
            @Param("identifiers") List<String> identifiers
    );

    /** 将同协议配置当前已发布版本退役。 */
    @Update("""
            UPDATE dev_protocol_mapping_version
            SET status = 'RETIRED', updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId}, version = version + 1
            WHERE product_id = #{productId} AND profile_key = #{profileKey} AND status = 'PUBLISHED'
            """)
    int retirePublishedProtocol(
            @Param("productId") UUID productId,
            @Param("profileKey") String profileKey,
            @Param("operatorId") UUID operatorId
    );

    /** 使用乐观锁发布协议映射草稿。 */
    @Update("""
            UPDATE dev_protocol_mapping_version
            SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP, published_by = #{operatorId},
                updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId}, version = version + 1
            WHERE id = #{id} AND version = #{version} AND status = 'DRAFT'
            """)
    int publishProtocolVersion(
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId
    );

    /** 物模型版本投影。 */
    record ModelVersionRecord(UUID id, UUID productId, int versionNumber, String status, String description, long version) {
    }

    /** 物模型字段投影。 */
    record ModelFieldRecord(
            UUID id, UUID modelVersionId, String identifier, String nameZh, String nameEn,
            String dataType, String unit, String accessMode, boolean requiredField,
            BigDecimal minimumValue, BigDecimal maximumValue, Integer decimalPlaces,
            int sortOrder, String description, long version
    ) {
    }

    /** 协议映射版本投影。 */
    record ProtocolVersionRecord(
            UUID id, UUID productId, String profileKey, String name, String protocolType,
            String messageType, int versionNumber, String status, String topicOrFrame,
            String description, long version
    ) {
    }

    /** 协议字段映射投影。 */
    record ProtocolFieldRecord(
            UUID id, UUID mappingVersionId, String sourcePath, String sourceDataType,
            String targetIdentifier, String transformType, BigDecimal scaleFactor,
            BigDecimal offsetValue, String expression, boolean requiredField,
            int sortOrder, long version
    ) {
    }
}
