package com.bosco.mine.server.modules.mine.scene;

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
 * 三维资源和场景控制面的 MyBatis 数据访问接口。
 */
@Mapper
public interface SceneMapper {

    /** 按矿区和上传摘要查询已登记资源。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   asset_type AS assetType, original_filename AS originalFilename,
                   content_type AS contentType, content_digest AS contentDigest,
                   source_size_bytes AS sourceSizeBytes,
                   expanded_size_bytes AS expandedSizeBytes,
                   file_count AS fileCount, entry_path AS entryPath, status,
                   created_at AS createdAt, created_by AS createdBy,
                   archived_at AS archivedAt, version
            FROM mine_scene_asset
            WHERE site_id = #{siteId} AND content_digest = #{contentDigest}
            """)
    Optional<SceneAssetRecord> findAssetByDigest(
            @Param("siteId") UUID siteId,
            @Param("contentDigest") String contentDigest);

    /** 按矿区和资源标识查询资源。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   asset_type AS assetType, original_filename AS originalFilename,
                   content_type AS contentType, content_digest AS contentDigest,
                   source_size_bytes AS sourceSizeBytes,
                   expanded_size_bytes AS expandedSizeBytes,
                   file_count AS fileCount, entry_path AS entryPath, status,
                   created_at AS createdAt, created_by AS createdBy,
                   archived_at AS archivedAt, version
            FROM mine_scene_asset
            WHERE site_id = #{siteId} AND id = #{assetId}
            """)
    Optional<SceneAssetRecord> findAssetById(
            @Param("siteId") UUID siteId,
            @Param("assetId") UUID assetId);

    /** 分页查询矿区资源。 */
    @Select("""
            <script>
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   asset_type AS assetType, original_filename AS originalFilename,
                   content_type AS contentType, content_digest AS contentDigest,
                   source_size_bytes AS sourceSizeBytes,
                   expanded_size_bytes AS expandedSizeBytes,
                   file_count AS fileCount, entry_path AS entryPath, status,
                   created_at AS createdAt, created_by AS createdBy,
                   archived_at AS archivedAt, version
            FROM mine_scene_asset
            WHERE site_id = #{siteId}
            <if test="status != null">AND status = #{status}</if>
            ORDER BY created_at DESC, id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<SceneAssetRecord> findAssets(
            @Param("siteId") UUID siteId,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /** 统计矿区资源。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM mine_scene_asset
            WHERE site_id = #{siteId}
            <if test="status != null">AND status = #{status}</if>
            </script>
            """)
    long countAssets(@Param("siteId") UUID siteId, @Param("status") String status);

    /** 尝试登记隔离态资源；同矿区摘要冲突时返回零。 */
    @Insert("""
            INSERT INTO mine_scene_asset (
                id, organization_id, site_id, asset_type, original_filename,
                content_type, content_digest, source_size_bytes, expanded_size_bytes,
                file_count, entry_path, created_by
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{assetType}, #{originalFilename},
                #{contentType}, #{contentDigest}, #{sourceSizeBytes}, #{expandedSizeBytes},
                #{fileCount}, #{entryPath}, #{createdBy}
            )
            ON CONFLICT (site_id, content_digest) DO NOTHING
            """)
    int insertAsset(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("assetType") String assetType,
            @Param("originalFilename") String originalFilename,
            @Param("contentType") String contentType,
            @Param("contentDigest") String contentDigest,
            @Param("sourceSizeBytes") long sourceSizeBytes,
            @Param("expandedSizeBytes") long expandedSizeBytes,
            @Param("fileCount") int fileCount,
            @Param("entryPath") String entryPath,
            @Param("createdBy") UUID createdBy);

    /** 登记隔离态资源的一项不可变文件清单。 */
    @Insert("""
            INSERT INTO mine_scene_asset_file (
                id, organization_id, site_id, asset_id, relative_path,
                content_type, content_digest, size_bytes
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{assetId}, #{relativePath},
                #{contentType}, #{contentDigest}, #{sizeBytes}
            )
            """)
    int insertAssetFile(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("assetId") UUID assetId,
            @Param("relativePath") String relativePath,
            @Param("contentType") String contentType,
            @Param("contentDigest") String contentDigest,
            @Param("sizeBytes") long sizeBytes);

    /** 在数据库触发器复核清单完整后把资源转为可用。 */
    @Update("""
            UPDATE mine_scene_asset
            SET status = 'AVAILABLE'
            WHERE id = #{assetId} AND site_id = #{siteId} AND status = 'QUARANTINED'
            """)
    int makeAssetAvailable(@Param("siteId") UUID siteId, @Param("assetId") UUID assetId);

    /** 使用乐观锁归档可用资源。 */
    @Update("""
            UPDATE mine_scene_asset
            SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP, version = version + 1
            WHERE id = #{assetId} AND site_id = #{siteId}
              AND status = 'AVAILABLE' AND version = #{version}
            """)
    int archiveAsset(
            @Param("siteId") UUID siteId,
            @Param("assetId") UUID assetId,
            @Param("version") long version);

    /** 分页查询资源文件清单。 */
    @Select("""
            SELECT id, relative_path AS relativePath, content_type AS contentType,
                   content_digest AS contentDigest, size_bytes AS sizeBytes,
                   created_at AS createdAt
            FROM mine_scene_asset_file
            WHERE site_id = #{siteId} AND asset_id = #{assetId}
            ORDER BY relative_path, id
            OFFSET #{offset} LIMIT #{limit}
            """)
    List<SceneAssetFileRecord> findAssetFiles(
            @Param("siteId") UUID siteId,
            @Param("assetId") UUID assetId,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /** 统计资源文件清单。 */
    @Select("""
            SELECT COUNT(*) FROM mine_scene_asset_file
            WHERE site_id = #{siteId} AND asset_id = #{assetId}
            """)
    long countAssetFiles(@Param("siteId") UUID siteId, @Param("assetId") UUID assetId);

    /** 按受权矿区、资源和完整相对路径查询一个清单文件。 */
    @Select("""
            SELECT file.id, file.relative_path AS relativePath,
                   file.content_type AS contentType,
                   file.content_digest AS contentDigest,
                   file.size_bytes AS sizeBytes, file.created_at AS createdAt
            FROM mine_scene_asset_file file
            JOIN mine_scene_asset asset ON asset.id = file.asset_id
            WHERE file.site_id = #{siteId} AND file.asset_id = #{assetId}
              AND file.relative_path = #{relativePath}
              AND asset.status IN ('AVAILABLE', 'ARCHIVED')
            """)
    Optional<SceneAssetFileRecord> findAssetFile(
            @Param("siteId") UUID siteId,
            @Param("assetId") UUID assetId,
            @Param("relativePath") String relativePath);

    /** 按矿区和标识查询稳定场景。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   scene_code AS sceneCode, name, description,
                   default_scene AS defaultScene, status,
                   created_at AS createdAt, created_by AS createdBy,
                   updated_at AS updatedAt, updated_by AS updatedBy,
                   archived_at AS archivedAt, version
            FROM mine_scene
            WHERE site_id = #{siteId} AND id = #{sceneId}
            """)
    Optional<SceneRecord> findSceneById(
            @Param("siteId") UUID siteId,
            @Param("sceneId") UUID sceneId);

    /** 锁定并读取稳定场景。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   scene_code AS sceneCode, name, description,
                   default_scene AS defaultScene, status,
                   created_at AS createdAt, created_by AS createdBy,
                   updated_at AS updatedAt, updated_by AS updatedBy,
                   archived_at AS archivedAt, version
            FROM mine_scene
            WHERE site_id = #{siteId} AND id = #{sceneId}
            FOR UPDATE
            """)
    Optional<SceneRecord> lockScene(
            @Param("siteId") UUID siteId,
            @Param("sceneId") UUID sceneId);

    /** 按矿区串行化默认场景切换，避免不同目标之间发生锁顺序反转。 */
    @Select("""
            SELECT pg_advisory_xact_lock(
                hashtextextended(CAST(#{siteId} AS TEXT), 487311842024)
            )
            """)
    String lockSceneSite(@Param("siteId") UUID siteId);

    /** 分页查询稳定场景。 */
    @Select("""
            <script>
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   scene_code AS sceneCode, name, description,
                   default_scene AS defaultScene, status,
                   created_at AS createdAt, created_by AS createdBy,
                   updated_at AS updatedAt, updated_by AS updatedBy,
                   archived_at AS archivedAt, version
            FROM mine_scene
            WHERE site_id = #{siteId}
            <if test="keyword != null">
              AND (lower(scene_code) LIKE #{keyword} OR lower(name) LIKE #{keyword})
            </if>
            <if test="status != null">AND status = #{status}</if>
            ORDER BY default_scene DESC, name, id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<SceneRecord> findScenes(
            @Param("siteId") UUID siteId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /** 统计稳定场景。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM mine_scene
            WHERE site_id = #{siteId}
            <if test="keyword != null">
              AND (lower(scene_code) LIKE #{keyword} OR lower(name) LIKE #{keyword})
            </if>
            <if test="status != null">AND status = #{status}</if>
            </script>
            """)
    long countScenes(
            @Param("siteId") UUID siteId,
            @Param("keyword") String keyword,
            @Param("status") String status);

    /** 尝试创建稳定场景，矿区编码冲突时返回零。 */
    @Insert("""
            INSERT INTO mine_scene (
                id, organization_id, site_id, scene_code, name, description,
                created_by, updated_by
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{sceneCode}, #{name}, #{description},
                #{operatorId}, #{operatorId}
            )
            ON CONFLICT DO NOTHING
            """)
    int insertScene(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("sceneCode") String sceneCode,
            @Param("name") String name,
            @Param("description") String description,
            @Param("operatorId") UUID operatorId);

    /** 使用乐观锁修改活动稳定场景。 */
    @Update("""
            UPDATE mine_scene
            SET name = #{name}, description = #{description},
                updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId},
                version = version + 1
            WHERE site_id = #{siteId} AND id = #{sceneId}
              AND status = 'ACTIVE' AND version = #{version}
            """)
    int updateScene(
            @Param("siteId") UUID siteId,
            @Param("sceneId") UUID sceneId,
            @Param("name") String name,
            @Param("description") String description,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 清除矿区中除目标外的默认场景并推进其乐观版本。 */
    @Update("""
            UPDATE mine_scene
            SET default_scene = FALSE,
                updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId},
                version = version + 1
            WHERE site_id = #{siteId} AND id <> #{sceneId}
              AND status = 'ACTIVE' AND default_scene
            """)
    int clearOtherDefaultScenes(
            @Param("siteId") UUID siteId,
            @Param("sceneId") UUID sceneId,
            @Param("operatorId") UUID operatorId);

    /** 使用乐观锁把活动场景设为默认。 */
    @Update("""
            UPDATE mine_scene
            SET default_scene = TRUE,
                updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId},
                version = version + 1
            WHERE site_id = #{siteId} AND id = #{sceneId}
              AND status = 'ACTIVE' AND version = #{version}
            """)
    int setDefaultScene(
            @Param("siteId") UUID siteId,
            @Param("sceneId") UUID sceneId,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 使用乐观锁归档非默认活动场景。 */
    @Update("""
            UPDATE mine_scene
            SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId},
                version = version + 1
            WHERE site_id = #{siteId} AND id = #{sceneId}
              AND status = 'ACTIVE' AND NOT default_scene AND version = #{version}
            """)
    int archiveScene(
            @Param("siteId") UUID siteId,
            @Param("sceneId") UUID sceneId,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 按矿区、稳定场景和版本标识查询场景版本。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   scene_id AS sceneId, coordinate_system_id AS coordinateSystemId,
                   revision_number AS revisionNumber, schema_version AS schemaVersion,
                   coordinate_mode AS coordinateMode,
                   origin_x AS originX, origin_y AS originY, origin_z AS originZ,
                   heading_degrees AS headingDegrees, pitch_degrees AS pitchDegrees,
                   roll_degrees AS rollDegrees, scale,
                   default_camera::TEXT AS defaultCameraJson,
                   fallback_viewport::TEXT AS fallbackViewportJson,
                   status, content_hash AS contentHash,
                   published_at AS publishedAt, published_by AS publishedBy,
                   superseded_at AS supersededAt,
                   created_at AS createdAt, created_by AS createdBy, version
            FROM mine_scene_version
            WHERE site_id = #{siteId} AND scene_id = #{sceneId} AND id = #{versionId}
            """)
    Optional<SceneVersionRecord> findSceneVersion(
            @Param("siteId") UUID siteId,
            @Param("sceneId") UUID sceneId,
            @Param("versionId") UUID versionId);

    /** 锁定并读取一个场景版本。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   scene_id AS sceneId, coordinate_system_id AS coordinateSystemId,
                   revision_number AS revisionNumber, schema_version AS schemaVersion,
                   coordinate_mode AS coordinateMode,
                   origin_x AS originX, origin_y AS originY, origin_z AS originZ,
                   heading_degrees AS headingDegrees, pitch_degrees AS pitchDegrees,
                   roll_degrees AS rollDegrees, scale,
                   default_camera::TEXT AS defaultCameraJson,
                   fallback_viewport::TEXT AS fallbackViewportJson,
                   status, content_hash AS contentHash,
                   published_at AS publishedAt, published_by AS publishedBy,
                   superseded_at AS supersededAt,
                   created_at AS createdAt, created_by AS createdBy, version
            FROM mine_scene_version
            WHERE site_id = #{siteId} AND scene_id = #{sceneId} AND id = #{versionId}
            FOR UPDATE
            """)
    Optional<SceneVersionRecord> lockSceneVersion(
            @Param("siteId") UUID siteId,
            @Param("sceneId") UUID sceneId,
            @Param("versionId") UUID versionId);

    /** 按修订号倒序分页查询场景版本。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   scene_id AS sceneId, coordinate_system_id AS coordinateSystemId,
                   revision_number AS revisionNumber, schema_version AS schemaVersion,
                   coordinate_mode AS coordinateMode,
                   origin_x AS originX, origin_y AS originY, origin_z AS originZ,
                   heading_degrees AS headingDegrees, pitch_degrees AS pitchDegrees,
                   roll_degrees AS rollDegrees, scale,
                   default_camera::TEXT AS defaultCameraJson,
                   fallback_viewport::TEXT AS fallbackViewportJson,
                   status, content_hash AS contentHash,
                   published_at AS publishedAt, published_by AS publishedBy,
                   superseded_at AS supersededAt,
                   created_at AS createdAt, created_by AS createdBy, version
            FROM mine_scene_version
            WHERE site_id = #{siteId} AND scene_id = #{sceneId}
            ORDER BY revision_number DESC, id
            OFFSET #{offset} LIMIT #{limit}
            """)
    List<SceneVersionRecord> findSceneVersions(
            @Param("siteId") UUID siteId,
            @Param("sceneId") UUID sceneId,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /** 统计稳定场景的历史版本数量。 */
    @Select("""
            SELECT COUNT(*)
            FROM mine_scene_version
            WHERE site_id = #{siteId} AND scene_id = #{sceneId}
            """)
    long countSceneVersions(
            @Param("siteId") UUID siteId,
            @Param("sceneId") UUID sceneId);

    /** 查询稳定场景的当前发布版本。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   scene_id AS sceneId, coordinate_system_id AS coordinateSystemId,
                   revision_number AS revisionNumber, schema_version AS schemaVersion,
                   coordinate_mode AS coordinateMode,
                   origin_x AS originX, origin_y AS originY, origin_z AS originZ,
                   heading_degrees AS headingDegrees, pitch_degrees AS pitchDegrees,
                   roll_degrees AS rollDegrees, scale,
                   default_camera::TEXT AS defaultCameraJson,
                   fallback_viewport::TEXT AS fallbackViewportJson,
                   status, content_hash AS contentHash,
                   published_at AS publishedAt, published_by AS publishedBy,
                   superseded_at AS supersededAt,
                   created_at AS createdAt, created_by AS createdBy, version
            FROM mine_scene_version
            WHERE site_id = #{siteId} AND scene_id = #{sceneId} AND status = 'PUBLISHED'
            """)
    Optional<SceneVersionRecord> findCurrentPublishedVersion(
            @Param("siteId") UUID siteId,
            @Param("sceneId") UUID sceneId);

    /** 查询稳定场景的当前草稿。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   scene_id AS sceneId, coordinate_system_id AS coordinateSystemId,
                   revision_number AS revisionNumber, schema_version AS schemaVersion,
                   coordinate_mode AS coordinateMode,
                   origin_x AS originX, origin_y AS originY, origin_z AS originZ,
                   heading_degrees AS headingDegrees, pitch_degrees AS pitchDegrees,
                   roll_degrees AS rollDegrees, scale,
                   default_camera::TEXT AS defaultCameraJson,
                   fallback_viewport::TEXT AS fallbackViewportJson,
                   status, content_hash AS contentHash,
                   published_at AS publishedAt, published_by AS publishedBy,
                   superseded_at AS supersededAt,
                   created_at AS createdAt, created_by AS createdBy, version
            FROM mine_scene_version
            WHERE site_id = #{siteId} AND scene_id = #{sceneId} AND status = 'DRAFT'
            """)
    Optional<SceneVersionRecord> findDraftVersion(
            @Param("siteId") UUID siteId,
            @Param("sceneId") UUID sceneId);

    /** 查询稳定场景最大修订号。 */
    @Select("""
            SELECT COALESCE(MAX(revision_number), 0)
            FROM mine_scene_version
            WHERE site_id = #{siteId} AND scene_id = #{sceneId}
            """)
    int maxRevisionNumber(@Param("siteId") UUID siteId, @Param("sceneId") UUID sceneId);

    /** 创建一个场景草稿版本。 */
    @Insert("""
            INSERT INTO mine_scene_version (
                id, organization_id, site_id, scene_id, coordinate_system_id,
                revision_number, schema_version, coordinate_mode,
                origin_x, origin_y, origin_z,
                heading_degrees, pitch_degrees, roll_degrees, scale,
                default_camera, fallback_viewport, created_by
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{sceneId}, #{coordinateSystemId},
                #{revisionNumber}, 1, #{coordinateMode},
                #{originX}, #{originY}, #{originZ},
                #{headingDegrees}, #{pitchDegrees}, #{rollDegrees}, #{scale},
                CAST(#{defaultCameraJson} AS JSONB),
                CAST(#{fallbackViewportJson} AS JSONB), #{operatorId}
            )
            ON CONFLICT DO NOTHING
            """)
    int insertSceneVersion(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("sceneId") UUID sceneId,
            @Param("coordinateSystemId") UUID coordinateSystemId,
            @Param("revisionNumber") int revisionNumber,
            @Param("coordinateMode") String coordinateMode,
            @Param("originX") double originX,
            @Param("originY") double originY,
            @Param("originZ") double originZ,
            @Param("headingDegrees") double headingDegrees,
            @Param("pitchDegrees") double pitchDegrees,
            @Param("rollDegrees") double rollDegrees,
            @Param("scale") double scale,
            @Param("defaultCameraJson") String defaultCameraJson,
            @Param("fallbackViewportJson") String fallbackViewportJson,
            @Param("operatorId") UUID operatorId);

    /** 使用乐观锁修改场景草稿。 */
    @Update("""
            UPDATE mine_scene_version
            SET coordinate_system_id = #{coordinateSystemId},
                coordinate_mode = #{coordinateMode},
                origin_x = #{originX}, origin_y = #{originY}, origin_z = #{originZ},
                heading_degrees = #{headingDegrees}, pitch_degrees = #{pitchDegrees},
                roll_degrees = #{rollDegrees}, scale = #{scale},
                default_camera = CAST(#{defaultCameraJson} AS JSONB),
                fallback_viewport = CAST(#{fallbackViewportJson} AS JSONB),
                version = version + 1
            WHERE site_id = #{siteId} AND scene_id = #{sceneId} AND id = #{versionId}
              AND status = 'DRAFT' AND version = #{version}
            """)
    int updateSceneVersion(
            @Param("siteId") UUID siteId,
            @Param("sceneId") UUID sceneId,
            @Param("versionId") UUID versionId,
            @Param("coordinateSystemId") UUID coordinateSystemId,
            @Param("coordinateMode") String coordinateMode,
            @Param("originX") double originX,
            @Param("originY") double originY,
            @Param("originZ") double originZ,
            @Param("headingDegrees") double headingDegrees,
            @Param("pitchDegrees") double pitchDegrees,
            @Param("rollDegrees") double rollDegrees,
            @Param("scale") double scale,
            @Param("defaultCameraJson") String defaultCameraJson,
            @Param("fallbackViewportJson") String fallbackViewportJson,
            @Param("version") long version);

    /** 仅推进草稿乐观版本，用于保护图层整体替换。 */
    @Update("""
            UPDATE mine_scene_version
            SET version = version + 1
            WHERE site_id = #{siteId} AND scene_id = #{sceneId} AND id = #{versionId}
              AND status = 'DRAFT' AND version = #{version}
            """)
    int touchDraftVersion(
            @Param("siteId") UUID siteId,
            @Param("sceneId") UUID sceneId,
            @Param("versionId") UUID versionId,
            @Param("version") long version);

    /** 把当前发布版本原子转换为已替代状态。 */
    @Update("""
            UPDATE mine_scene_version
            SET status = 'SUPERSEDED', superseded_at = CURRENT_TIMESTAMP
            WHERE site_id = #{siteId} AND scene_id = #{sceneId}
              AND status = 'PUBLISHED' AND id <> #{nextVersionId}
            """)
    int supersedeCurrentPublished(
            @Param("siteId") UUID siteId,
            @Param("sceneId") UUID sceneId,
            @Param("nextVersionId") UUID nextVersionId);

    /** 使用乐观锁发布一个场景草稿。 */
    @Update("""
            UPDATE mine_scene_version
            SET status = 'PUBLISHED', content_hash = #{contentHash},
                published_at = CURRENT_TIMESTAMP, published_by = #{operatorId},
                version = version + 1
            WHERE site_id = #{siteId} AND scene_id = #{sceneId} AND id = #{versionId}
              AND status = 'DRAFT' AND version = #{version}
            """)
    int publishDraftVersion(
            @Param("siteId") UUID siteId,
            @Param("sceneId") UUID sceneId,
            @Param("versionId") UUID versionId,
            @Param("version") long version,
            @Param("contentHash") String contentHash,
            @Param("operatorId") UUID operatorId);

    /** 删除一个草稿的全部图层，为整体替换做准备。 */
    @org.apache.ibatis.annotations.Delete("""
            DELETE FROM mine_scene_layer
            WHERE site_id = #{siteId} AND scene_version_id = #{versionId}
            """)
    int deleteDraftLayers(@Param("siteId") UUID siteId, @Param("versionId") UUID versionId);

    /** 插入一个已经过领域规范化的场景图层。 */
    @Insert("""
            INSERT INTO mine_scene_layer (
                id, organization_id, site_id, scene_version_id,
                layer_code, order_number, layer_type, asset_id,
                visible, opacity, minimum_distance, maximum_distance,
                offset_x, offset_y, offset_z,
                heading_degrees, pitch_degrees, roll_degrees, scale,
                max_items, options
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{versionId},
                #{layerCode}, #{orderNumber}, #{layerType}, #{assetId},
                #{visible}, #{opacity}, #{minimumDistance}, #{maximumDistance},
                #{offsetX}, #{offsetY}, #{offsetZ},
                #{headingDegrees}, #{pitchDegrees}, #{rollDegrees}, #{scale},
                #{maxItems}, CAST(#{optionsJson} AS JSONB)
            )
            """)
    int insertSceneLayer(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("versionId") UUID versionId,
            @Param("layerCode") String layerCode,
            @Param("orderNumber") int orderNumber,
            @Param("layerType") String layerType,
            @Param("assetId") UUID assetId,
            @Param("visible") boolean visible,
            @Param("opacity") double opacity,
            @Param("minimumDistance") double minimumDistance,
            @Param("maximumDistance") double maximumDistance,
            @Param("offsetX") double offsetX,
            @Param("offsetY") double offsetY,
            @Param("offsetZ") double offsetZ,
            @Param("headingDegrees") double headingDegrees,
            @Param("pitchDegrees") double pitchDegrees,
            @Param("rollDegrees") double rollDegrees,
            @Param("scale") double scale,
            @Param("maxItems") int maxItems,
            @Param("optionsJson") String optionsJson);

    /** 按稳定顺序查询场景版本图层和静态资源状态。 */
    @Select("""
            SELECT layer.id, layer.layer_code AS layerCode,
                   layer.order_number AS orderNumber, layer.layer_type AS layerType,
                   layer.asset_id AS assetId, asset.asset_type AS assetType,
                   asset.status AS assetStatus,
                   layer.visible, layer.opacity,
                   layer.minimum_distance AS minimumDistance,
                   layer.maximum_distance AS maximumDistance,
                   layer.offset_x AS offsetX, layer.offset_y AS offsetY,
                   layer.offset_z AS offsetZ,
                   layer.heading_degrees AS headingDegrees,
                   layer.pitch_degrees AS pitchDegrees,
                   layer.roll_degrees AS rollDegrees, layer.scale,
                   layer.max_items AS maxItems, layer.options::TEXT AS optionsJson,
                   layer.created_at AS createdAt
            FROM mine_scene_layer layer
            LEFT JOIN mine_scene_asset asset ON asset.id = layer.asset_id
            WHERE layer.site_id = #{siteId} AND layer.scene_version_id = #{versionId}
            ORDER BY layer.order_number, layer.id
            """)
    List<SceneLayerRecord> findSceneLayers(
            @Param("siteId") UUID siteId,
            @Param("versionId") UUID versionId);

    /** 查询同矿区可用于场景的统一活动坐标系。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   unified, status
            FROM mine_coordinate_system
            WHERE site_id = #{siteId} AND id = #{coordinateSystemId}
            """)
    Optional<SceneCoordinateRecord> findSceneCoordinate(
            @Param("siteId") UUID siteId,
            @Param("coordinateSystemId") UUID coordinateSystemId);

    /** 原子认领一个场景用户动作幂等键。 */
    @Insert("""
            INSERT INTO mine_scene_action_idempotency (
                id, organization_id, site_id, operator_id, idempotency_key,
                action_type, aggregate_type, aggregate_id, request_hash
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{operatorId}, #{idempotencyKey},
                #{actionType}, #{aggregateType}, #{aggregateId}, #{requestHash}
            )
            ON CONFLICT (organization_id, site_id, operator_id, idempotency_key)
            DO NOTHING
            """)
    int claimSceneAction(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("operatorId") UUID operatorId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("actionType") String actionType,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") UUID aggregateId,
            @Param("requestHash") String requestHash);

    /** 查询一个用户在矿区中已认领的场景幂等动作。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   operator_id AS operatorId, idempotency_key AS idempotencyKey,
                   action_type AS actionType, aggregate_type AS aggregateType,
                   aggregate_id AS aggregateId, request_hash AS requestHash,
                   response_snapshot::TEXT AS responseSnapshotJson,
                   created_at AS createdAt, completed_at AS completedAt
            FROM mine_scene_action_idempotency
            WHERE organization_id = #{organizationId} AND site_id = #{siteId}
              AND operator_id = #{operatorId} AND idempotency_key = #{idempotencyKey}
            """)
    Optional<SceneActionRecord> findSceneAction(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("operatorId") UUID operatorId,
            @Param("idempotencyKey") String idempotencyKey);

    /** 冻结首次成功场景动作响应。 */
    @Update("""
            UPDATE mine_scene_action_idempotency
            SET response_snapshot = CAST(#{responseSnapshotJson} AS JSONB),
                completed_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND response_snapshot IS NULL AND completed_at IS NULL
            """)
    int completeSceneAction(
            @Param("id") UUID id,
            @Param("responseSnapshotJson") String responseSnapshotJson);

    /**
     * 稳定场景数据库快照。
     *
     * @param id 场景标识
     * @param organizationId 组织标识
     * @param siteId 矿区标识
     * @param sceneCode 场景编码
     * @param name 场景名称
     * @param description 场景说明
     * @param defaultScene 是否默认
     * @param status 状态
     * @param createdAt 创建时间
     * @param createdBy 创建人
     * @param updatedAt 更新时间
     * @param updatedBy 更新人
     * @param archivedAt 归档时间
     * @param version 乐观版本
     */
    record SceneRecord(
            UUID id,
            UUID organizationId,
            UUID siteId,
            String sceneCode,
            String name,
            String description,
            boolean defaultScene,
            String status,
            Instant createdAt,
            UUID createdBy,
            Instant updatedAt,
            UUID updatedBy,
            Instant archivedAt,
            long version
    ) {
    }

    /**
     * 场景版本数据库快照。
     */
    record SceneVersionRecord(
            UUID id,
            UUID organizationId,
            UUID siteId,
            UUID sceneId,
            UUID coordinateSystemId,
            int revisionNumber,
            int schemaVersion,
            String coordinateMode,
            double originX,
            double originY,
            double originZ,
            double headingDegrees,
            double pitchDegrees,
            double rollDegrees,
            double scale,
            String defaultCameraJson,
            String fallbackViewportJson,
            String status,
            String contentHash,
            Instant publishedAt,
            UUID publishedBy,
            Instant supersededAt,
            Instant createdAt,
            UUID createdBy,
            long version
    ) {
    }

    /** 场景版本图层数据库快照。 */
    record SceneLayerRecord(
            UUID id,
            String layerCode,
            int orderNumber,
            String layerType,
            UUID assetId,
            String assetType,
            String assetStatus,
            boolean visible,
            double opacity,
            double minimumDistance,
            double maximumDistance,
            double offsetX,
            double offsetY,
            double offsetZ,
            double headingDegrees,
            double pitchDegrees,
            double rollDegrees,
            double scale,
            int maxItems,
            String optionsJson,
            Instant createdAt
    ) {
    }

    /** 场景可用坐标系数据库快照。 */
    record SceneCoordinateRecord(
            UUID id,
            UUID organizationId,
            UUID siteId,
            boolean unified,
            String status
    ) {
    }

    /** 场景动作持久幂等记录。 */
    record SceneActionRecord(
            UUID id,
            UUID organizationId,
            UUID siteId,
            UUID operatorId,
            String idempotencyKey,
            String actionType,
            String aggregateType,
            UUID aggregateId,
            String requestHash,
            String responseSnapshotJson,
            Instant createdAt,
            Instant completedAt
    ) {
    }

    /**
     * 数据库中的三维资源快照。
     *
     * @param id 资源标识
     * @param organizationId 组织标识
     * @param siteId 矿区标识
     * @param assetType 资源类型
     * @param originalFilename 原始文件名
     * @param contentType 上传媒体类型
     * @param contentDigest 上传摘要
     * @param sourceSizeBytes 上传字节数
     * @param expandedSizeBytes 展开字节数
     * @param fileCount 文件数
     * @param entryPath 入口路径
     * @param status 状态
     * @param createdAt 登记时间
     * @param createdBy 上传人
     * @param archivedAt 归档时间
     * @param version 乐观锁版本
     */
    record SceneAssetRecord(
            UUID id,
            UUID organizationId,
            UUID siteId,
            String assetType,
            String originalFilename,
            String contentType,
            String contentDigest,
            long sourceSizeBytes,
            long expandedSizeBytes,
            int fileCount,
            String entryPath,
            String status,
            Instant createdAt,
            UUID createdBy,
            Instant archivedAt,
            long version
    ) {
    }

    /**
     * 数据库中的资源文件清单快照。
     *
     * @param id 文件标识
     * @param relativePath 相对路径
     * @param contentType 媒体类型
     * @param contentDigest 文件摘要
     * @param sizeBytes 文件字节数
     * @param createdAt 登记时间
     */
    record SceneAssetFileRecord(
            UUID id,
            String relativePath,
            String contentType,
            String contentDigest,
            long sizeBytes,
            Instant createdAt
    ) {
    }
}
