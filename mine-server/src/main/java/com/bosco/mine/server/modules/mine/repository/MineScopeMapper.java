package com.bosco.mine.server.modules.mine.repository;

import com.bosco.mine.server.modules.mine.model.MineOrganization;
import com.bosco.mine.server.modules.mine.model.MineSite;
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
 * 组织、矿区和用户数据授权的数据访问接口。
 */
@Mapper
public interface MineScopeMapper {

    /** 查询用户是否存在且启用。 */
    @Select("SELECT EXISTS (SELECT 1 FROM sys_user WHERE id = #{userId} AND status = 'ENABLED')")
    boolean isEnabledUser(@Param("userId") UUID userId);

    /** 按标识查询组织。 */
    @Select("""
            SELECT id, organization_code AS organizationCode, name, status,
                   created_at AS createdAt, created_by AS createdBy,
                   updated_at AS updatedAt, updated_by AS updatedBy,
                   archived_at AS archivedAt, version
            FROM mine_organization
            WHERE id = #{organizationId}
            """)
    Optional<MineOrganization> findOrganizationById(
            @Param("organizationId") UUID organizationId);

    /** 按标识查询矿区。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_code AS siteCode,
                   name, timezone, description, status,
                   created_at AS createdAt, created_by AS createdBy,
                   updated_at AS updatedAt, updated_by AS updatedBy,
                   archived_at AS archivedAt, version
            FROM mine_site
            WHERE id = #{siteId}
            """)
    Optional<MineSite> findSiteById(@Param("siteId") UUID siteId);

    /** 判断用户是否拥有组织或组织下任一矿区授权。 */
    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM mine_user_scope_grant
                WHERE user_id = #{userId}
                  AND organization_id = #{organizationId}
            )
            """)
    boolean hasOrganizationAccess(
            @Param("userId") UUID userId,
            @Param("organizationId") UUID organizationId);

    /** 判断用户是否拥有组织级授权。 */
    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM mine_user_scope_grant
                WHERE user_id = #{userId}
                  AND organization_id = #{organizationId}
                  AND site_id IS NULL
            )
            """)
    boolean hasOrganizationWideGrant(
            @Param("userId") UUID userId,
            @Param("organizationId") UUID organizationId);

    /** 判断用户是否拥有指定矿区或所属组织的授权。 */
    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM mine_site site
                JOIN mine_user_scope_grant grant_record
                  ON grant_record.organization_id = site.organization_id
                 AND (grant_record.site_id IS NULL OR grant_record.site_id = site.id)
                WHERE site.id = #{siteId}
                  AND grant_record.user_id = #{userId}
            )
            """)
    boolean hasSiteAccess(@Param("userId") UUID userId, @Param("siteId") UUID siteId);

    /** 分页查询当前用户可见组织。 */
    @Select("""
            <script>
            SELECT organization.id,
                   organization.organization_code AS organizationCode,
                   organization.name,
                   organization.status,
                   organization.created_at AS createdAt,
                   organization.created_by AS createdBy,
                   organization.updated_at AS updatedAt,
                   organization.updated_by AS updatedBy,
                   organization.archived_at AS archivedAt,
                   organization.version
            FROM mine_organization organization
            WHERE 1 = 1
              <if test="!allAccess">
                AND EXISTS (
                    SELECT 1 FROM mine_user_scope_grant grant_record
                    WHERE grant_record.user_id = #{userId}
                      AND grant_record.organization_id = organization.id
                )
              </if>
              <if test="keyword != null and keyword != ''">
                AND (organization.organization_code ILIKE CONCAT('%', #{keyword}, '%')
                     OR organization.name ILIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">
                AND organization.status = #{status}
              </if>
            ORDER BY organization.name, organization.id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<MineOrganization> findVisibleOrganizations(
            @Param("userId") UUID userId,
            @Param("allAccess") boolean allAccess,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /** 统计当前用户可见组织。 */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM mine_organization organization
            WHERE 1 = 1
              <if test="!allAccess">
                AND EXISTS (
                    SELECT 1 FROM mine_user_scope_grant grant_record
                    WHERE grant_record.user_id = #{userId}
                      AND grant_record.organization_id = organization.id
                )
              </if>
              <if test="keyword != null and keyword != ''">
                AND (organization.organization_code ILIKE CONCAT('%', #{keyword}, '%')
                     OR organization.name ILIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">
                AND organization.status = #{status}
              </if>
            </script>
            """)
    long countVisibleOrganizations(
            @Param("userId") UUID userId,
            @Param("allAccess") boolean allAccess,
            @Param("keyword") String keyword,
            @Param("status") String status);

    /** 分页查询当前用户可见矿区。 */
    @Select("""
            <script>
            SELECT site.id, site.organization_id AS organizationId,
                   site.site_code AS siteCode, site.name, site.timezone,
                   site.description, site.status,
                   site.created_at AS createdAt, site.created_by AS createdBy,
                   site.updated_at AS updatedAt, site.updated_by AS updatedBy,
                   site.archived_at AS archivedAt, site.version
            FROM mine_site site
            WHERE site.organization_id = #{organizationId}
              <if test="!allAccess">
                AND EXISTS (
                    SELECT 1 FROM mine_user_scope_grant grant_record
                    WHERE grant_record.user_id = #{userId}
                      AND grant_record.organization_id = site.organization_id
                      AND (grant_record.site_id IS NULL OR grant_record.site_id = site.id)
                )
              </if>
              <if test="keyword != null and keyword != ''">
                AND (site.site_code ILIKE CONCAT('%', #{keyword}, '%')
                     OR site.name ILIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">
                AND site.status = #{status}
              </if>
            ORDER BY site.name, site.id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<MineSite> findVisibleSites(
            @Param("userId") UUID userId,
            @Param("allAccess") boolean allAccess,
            @Param("organizationId") UUID organizationId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /** 统计当前用户可见矿区。 */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM mine_site site
            WHERE site.organization_id = #{organizationId}
              <if test="!allAccess">
                AND EXISTS (
                    SELECT 1 FROM mine_user_scope_grant grant_record
                    WHERE grant_record.user_id = #{userId}
                      AND grant_record.organization_id = site.organization_id
                      AND (grant_record.site_id IS NULL OR grant_record.site_id = site.id)
                )
              </if>
              <if test="keyword != null and keyword != ''">
                AND (site.site_code ILIKE CONCAT('%', #{keyword}, '%')
                     OR site.name ILIKE CONCAT('%', #{keyword}, '%'))
              </if>
              <if test="status != null and status != ''">
                AND site.status = #{status}
              </if>
            </script>
            """)
    long countVisibleSites(
            @Param("userId") UUID userId,
            @Param("allAccess") boolean allAccess,
            @Param("organizationId") UUID organizationId,
            @Param("keyword") String keyword,
            @Param("status") String status);

    /** 新增组织。 */
    @Insert("""
            INSERT INTO mine_organization (
                id, organization_code, name, created_by, updated_by
            ) VALUES (#{id}, #{code}, #{name}, #{operatorId}, #{operatorId})
            """)
    int insertOrganization(
            @Param("id") UUID id,
            @Param("code") String code,
            @Param("name") String name,
            @Param("operatorId") UUID operatorId);

    /** 使用乐观锁更新组织。 */
    @Update("""
            UPDATE mine_organization
            SET name = #{name}, updated_at = CURRENT_TIMESTAMP,
                updated_by = #{operatorId}, version = version + 1
            WHERE id = #{id} AND status = 'ACTIVE' AND version = #{version}
            """)
    int updateOrganization(
            @Param("id") UUID id,
            @Param("name") String name,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 归档没有活动矿区的组织。 */
    @Update("""
            UPDATE mine_organization
            SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId},
                version = version + 1
            WHERE id = #{id} AND status = 'ACTIVE' AND version = #{version}
            """)
    int archiveOrganization(
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 统计组织下活动矿区。 */
    @Select("SELECT COUNT(*) FROM mine_site WHERE organization_id = #{organizationId} AND status = 'ACTIVE'")
    long countActiveSites(@Param("organizationId") UUID organizationId);

    /** 新增矿区。 */
    @Insert("""
            INSERT INTO mine_site (
                id, organization_id, site_code, name, timezone, description,
                created_by, updated_by
            ) VALUES (
                #{id}, #{organizationId}, #{siteCode}, #{name}, #{timezone},
                #{description}, #{operatorId}, #{operatorId}
            )
            """)
    int insertSite(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteCode") String siteCode,
            @Param("name") String name,
            @Param("timezone") String timezone,
            @Param("description") String description,
            @Param("operatorId") UUID operatorId);

    /** 使用乐观锁更新矿区。 */
    @Update("""
            UPDATE mine_site
            SET name = #{name}, timezone = #{timezone}, description = #{description},
                updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId},
                version = version + 1
            WHERE id = #{id} AND status = 'ACTIVE' AND version = #{version}
            """)
    int updateSite(
            @Param("id") UUID id,
            @Param("name") String name,
            @Param("timezone") String timezone,
            @Param("description") String description,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 使用乐观锁归档矿区。 */
    @Update("""
            UPDATE mine_site
            SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId},
                version = version + 1
            WHERE id = #{id} AND status = 'ACTIVE' AND version = #{version}
            """)
    int archiveSite(
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 新增用户数据范围授权。 */
    @Insert("""
            INSERT INTO mine_user_scope_grant (
                id, user_id, organization_id, site_id, granted_by
            ) VALUES (#{id}, #{userId}, #{organizationId}, #{siteId}, #{grantedBy})
            """)
    int insertScopeGrant(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("grantedBy") UUID grantedBy);

    /** 删除一条精确数据范围授权。 */
    @Delete("""
            DELETE FROM mine_user_scope_grant
            WHERE user_id = #{userId}
              AND organization_id = #{organizationId}
              AND site_id IS NOT DISTINCT FROM #{siteId}
            """)
    int deleteScopeGrant(
            @Param("userId") UUID userId,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId);

    /** 查询组织下全部授权。 */
    @Select("""
            SELECT id, user_id AS userId, organization_id AS organizationId,
                   site_id AS siteId, granted_at AS grantedAt, granted_by AS grantedBy
            FROM mine_user_scope_grant
            WHERE organization_id = #{organizationId}
            ORDER BY granted_at, id
            """)
    List<ScopeGrantRecord> findScopeGrants(@Param("organizationId") UUID organizationId);

    /**
     * 数据范围授权持久化快照。
     *
     * @param id 授权标识
     * @param userId 用户标识
     * @param organizationId 组织标识
     * @param siteId 矿区标识
     * @param grantedAt 授权时间
     * @param grantedBy 授权人
     */
    record ScopeGrantRecord(
            UUID id,
            UUID userId,
            UUID organizationId,
            UUID siteId,
            Instant grantedAt,
            UUID grantedBy
    ) {
    }
}
