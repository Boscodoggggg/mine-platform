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
