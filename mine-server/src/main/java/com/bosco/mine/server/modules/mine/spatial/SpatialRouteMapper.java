package com.bosco.mine.server.modules.mine.spatial;

import com.bosco.mine.server.modules.mine.spatial.model.GeofenceVersionRecord;
import com.bosco.mine.server.modules.mine.spatial.model.GeofenceVertexRecord;
import com.bosco.mine.server.modules.mine.spatial.model.RouteVersionRecord;
import com.bosco.mine.server.modules.mine.spatial.model.RouteWaypointRecord;
import com.bosco.mine.server.modules.mine.spatial.model.SpatialObjectRecord;
import com.bosco.mine.server.modules.mine.spatial.model.WaypointVersionRecord;
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
 * 途径点、路线和电子围栏控制面数据访问接口。
 */
@Mapper
public interface SpatialRouteMapper {

    /** 判断坐标系是否为当前矿区的活动统一坐标系。 */
    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM mine_coordinate_system
                WHERE id = #{coordinateSystemId}
                  AND organization_id = #{organizationId}
                  AND site_id = #{siteId}
                  AND unified = TRUE
                  AND status = 'ACTIVE'
            )
            """)
    boolean isActiveUnifiedCoordinateSystem(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("coordinateSystemId") UUID coordinateSystemId);

    /** 查询当前矿区活动统一坐标系的高程边界。 */
    @Select("""
            SELECT id AS coordinateSystemId,
                   min_x AS minX, max_x AS maxX,
                   min_y AS minY, max_y AS maxY,
                   min_z AS minZ, max_z AS maxZ
            FROM mine_coordinate_system
            WHERE id = #{coordinateSystemId}
              AND organization_id = #{organizationId}
              AND site_id = #{siteId}
              AND unified = TRUE
              AND status = 'ACTIVE'
            """)
    Optional<SpatialCoordinateBounds> findActiveUnifiedCoordinateBounds(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("coordinateSystemId") UUID coordinateSystemId);

    /** 新增稳定途径点。 */
    @Insert("""
            INSERT INTO mine_waypoint (
                id, organization_id, site_id, waypoint_code, created_by, updated_by
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{code}, #{operatorId}, #{operatorId}
            )
            """)
    int insertWaypoint(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("code") String code,
            @Param("operatorId") UUID operatorId);

    /** 按矿区查询稳定途径点。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   waypoint_code AS code, status, created_at AS createdAt,
                   updated_at AS updatedAt, archived_at AS archivedAt, version
            FROM mine_waypoint
            WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
            """)
    Optional<SpatialObjectRecord> findWaypointById(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id);

    /** 分页查询稳定途径点。 */
    @Select("""
            <script>
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   waypoint_code AS code, status, created_at AS createdAt,
                   updated_at AS updatedAt, archived_at AS archivedAt, version
            FROM mine_waypoint
            WHERE organization_id = #{organizationId} AND site_id = #{siteId}
              <if test="keyword != null and keyword != ''">
                AND waypoint_code ILIKE CONCAT('%', #{keyword}, '%')
              </if>
              <if test="status != null and status != ''">AND status = #{status}</if>
            ORDER BY waypoint_code, id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<SpatialObjectRecord> findWaypoints(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /** 统计稳定途径点。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM mine_waypoint
            WHERE organization_id = #{organizationId} AND site_id = #{siteId}
              <if test="keyword != null and keyword != ''">
                AND waypoint_code ILIKE CONCAT('%', #{keyword}, '%')
              </if>
              <if test="status != null and status != ''">AND status = #{status}</if>
            </script>
            """)
    long countWaypoints(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("keyword") String keyword,
            @Param("status") String status);

    /** 使用乐观锁归档稳定途径点。 */
    @Update("""
            UPDATE mine_waypoint
               SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP,
                   updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId}, version = version + 1
             WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status = 'ACTIVE' AND version = #{version}
            """)
    int archiveWaypoint(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 统计仍在活动已发布路线中引用该稳定途径点的版本。 */
    @Select("""
            SELECT COUNT(*)
            FROM mine_route_version_waypoint route_waypoint
            JOIN mine_route_version route_version
              ON route_version.id = route_waypoint.route_version_id
             AND route_version.organization_id = route_waypoint.organization_id
             AND route_version.site_id = route_waypoint.site_id
            JOIN mine_route route
              ON route.id = route_version.route_id
             AND route.organization_id = route_version.organization_id
             AND route.site_id = route_version.site_id
            JOIN mine_waypoint_version waypoint_version
              ON waypoint_version.id = route_waypoint.waypoint_version_id
             AND waypoint_version.organization_id = route_waypoint.organization_id
             AND waypoint_version.site_id = route_waypoint.site_id
            WHERE waypoint_version.waypoint_id = #{waypointId}
              AND route_waypoint.organization_id = #{organizationId}
              AND route_waypoint.site_id = #{siteId}
              AND route_version.status = 'PUBLISHED'
              AND route.status = 'ACTIVE'
            """)
    long countActivePublishedRouteReferences(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("waypointId") UUID waypointId);

    /** 新增途径点草稿版本。 */
    @Insert("""
            INSERT INTO mine_waypoint_version (
                id, waypoint_id, organization_id, site_id, coordinate_system_id,
                version_number, name, waypoint_type, x, y, z, radius_meters, min_z, max_z,
                boundary_tolerance_meters, enter_confirm_points, exit_confirm_points,
                dwell_seconds, allowed_lateness_seconds, idle_flush_seconds,
                valid_from, valid_to, created_by, updated_by
            ) VALUES (
                #{item.id}, #{item.parentId}, #{item.organizationId}, #{item.siteId},
                #{item.coordinateSystemId}, #{item.versionNumber}, #{item.name}, #{item.type},
                #{item.x}, #{item.y}, #{item.z}, #{item.radiusMeters}, #{item.minZ}, #{item.maxZ},
                #{item.boundaryToleranceMeters}, #{item.enterConfirmPoints},
                #{item.exitConfirmPoints}, #{item.dwellSeconds}, #{item.allowedLatenessSeconds},
                #{item.idleFlushSeconds}, #{item.validFrom}, #{item.validTo},
                #{item.operatorId}, #{item.operatorId}
            )
            """)
    int insertWaypointVersion(@Param("item") WaypointVersionWrite item);

    /** 使用乐观锁修改途径点草稿版本。 */
    @Update("""
            UPDATE mine_waypoint_version
               SET coordinate_system_id = #{item.coordinateSystemId}, name = #{item.name},
                   waypoint_type = #{item.type}, x = #{item.x}, y = #{item.y}, z = #{item.z},
                   radius_meters = #{item.radiusMeters}, min_z = #{item.minZ}, max_z = #{item.maxZ},
                   boundary_tolerance_meters = #{item.boundaryToleranceMeters},
                   enter_confirm_points = #{item.enterConfirmPoints},
                   exit_confirm_points = #{item.exitConfirmPoints}, dwell_seconds = #{item.dwellSeconds},
                   allowed_lateness_seconds = #{item.allowedLatenessSeconds},
                   idle_flush_seconds = #{item.idleFlushSeconds}, valid_from = #{item.validFrom},
                   valid_to = #{item.validTo}, updated_at = CURRENT_TIMESTAMP,
                   updated_by = #{item.operatorId}, version = version + 1
             WHERE id = #{item.id} AND waypoint_id = #{item.parentId}
               AND organization_id = #{item.organizationId} AND site_id = #{item.siteId}
               AND status = 'DRAFT' AND version = #{item.lockVersion}
            """)
    int updateWaypointVersion(@Param("item") WaypointVersionWrite item);

    /** 发布途径点版本。 */
    @Update("""
            UPDATE mine_waypoint_version
               SET status = 'PUBLISHED', content_hash = #{contentHash},
                   published_at = CURRENT_TIMESTAMP, published_by = #{operatorId},
                   updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId}, version = version + 1
             WHERE id = #{id} AND waypoint_id = #{parentId}
               AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status = 'DRAFT' AND version = #{version}
            """)
    int publishWaypointVersion(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("parentId") UUID parentId,
            @Param("id") UUID id,
            @Param("contentHash") String contentHash,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 按矿区和稳定对象查询途径点版本。 */
    @Select("""
            SELECT id, waypoint_id AS waypointId, organization_id AS organizationId,
                   site_id AS siteId, coordinate_system_id AS coordinateSystemId,
                   version_number AS versionNumber, name, waypoint_type AS waypointType,
                   x, y, z, radius_meters AS radiusMeters, min_z AS minZ, max_z AS maxZ,
                   boundary_tolerance_meters AS boundaryToleranceMeters,
                   enter_confirm_points AS enterConfirmPoints,
                   exit_confirm_points AS exitConfirmPoints, dwell_seconds AS dwellSeconds,
                   allowed_lateness_seconds AS allowedLatenessSeconds,
                   idle_flush_seconds AS idleFlushSeconds, valid_from AS validFrom,
                   valid_to AS validTo, status, content_hash AS contentHash,
                   published_at AS publishedAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
            FROM mine_waypoint_version
            WHERE waypoint_id = #{parentId} AND organization_id = #{organizationId}
              AND site_id = #{siteId}
            ORDER BY version_number, id
            """)
    List<WaypointVersionRecord> findWaypointVersions(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("parentId") UUID parentId);

    /** 统计可被路线有效引用的已发布途径点版本。 */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM mine_waypoint_version waypoint_version
            JOIN mine_waypoint waypoint
              ON waypoint.id = waypoint_version.waypoint_id
             AND waypoint.organization_id = waypoint_version.organization_id
             AND waypoint.site_id = waypoint_version.site_id
            WHERE waypoint_version.organization_id = #{organizationId}
              AND waypoint_version.site_id = #{siteId}
              AND waypoint_version.coordinate_system_id = #{coordinateSystemId}
              AND waypoint_version.status = 'PUBLISHED'
              AND waypoint.status = 'ACTIVE'
              AND waypoint_version.valid_from &lt;= #{validFrom}
              <choose>
                <when test="validTo != null">
                  AND (waypoint_version.valid_to IS NULL
                       OR waypoint_version.valid_to &gt;= #{validTo})
                </when>
                <otherwise>
                  AND waypoint_version.valid_to IS NULL
                </otherwise>
              </choose>
              AND waypoint_version.id IN
              <foreach collection="versionIds" item="versionId" open="(" separator="," close=")">
                #{versionId}
              </foreach>
            </script>
            """)
    long countCompatibleWaypointVersions(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("coordinateSystemId") UUID coordinateSystemId,
            @Param("validFrom") Instant validFrom,
            @Param("validTo") Instant validTo,
            @Param("versionIds") List<UUID> versionIds);

    /** 按矿区和稳定对象查询指定途径点版本。 */
    @Select("""
            SELECT id, waypoint_id AS waypointId, organization_id AS organizationId,
                   site_id AS siteId, coordinate_system_id AS coordinateSystemId,
                   version_number AS versionNumber, name, waypoint_type AS waypointType,
                   x, y, z, radius_meters AS radiusMeters, min_z AS minZ, max_z AS maxZ,
                   boundary_tolerance_meters AS boundaryToleranceMeters,
                   enter_confirm_points AS enterConfirmPoints,
                   exit_confirm_points AS exitConfirmPoints, dwell_seconds AS dwellSeconds,
                   allowed_lateness_seconds AS allowedLatenessSeconds,
                   idle_flush_seconds AS idleFlushSeconds, valid_from AS validFrom,
                   valid_to AS validTo, status, content_hash AS contentHash,
                   published_at AS publishedAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
            FROM mine_waypoint_version
            WHERE id = #{id} AND waypoint_id = #{parentId}
              AND organization_id = #{organizationId} AND site_id = #{siteId}
            """)
    Optional<WaypointVersionRecord> findWaypointVersionById(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("parentId") UUID parentId,
            @Param("id") UUID id);

    /** 新增稳定路线。 */
    @Insert("""
            INSERT INTO mine_route (
                id, organization_id, site_id, route_code, created_by, updated_by
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{code}, #{operatorId}, #{operatorId}
            )
            """)
    int insertRoute(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("code") String code,
            @Param("operatorId") UUID operatorId);

    /** 按矿区查询稳定路线。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   route_code AS code, status, created_at AS createdAt,
                   updated_at AS updatedAt, archived_at AS archivedAt, version
            FROM mine_route
            WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
            """)
    Optional<SpatialObjectRecord> findRouteById(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id);

    /** 分页查询稳定路线。 */
    @Select("""
            <script>
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   route_code AS code, status, created_at AS createdAt,
                   updated_at AS updatedAt, archived_at AS archivedAt, version
            FROM mine_route
            WHERE organization_id = #{organizationId} AND site_id = #{siteId}
              <if test="keyword != null and keyword != ''">
                AND route_code ILIKE CONCAT('%', #{keyword}, '%')
              </if>
              <if test="status != null and status != ''">AND status = #{status}</if>
            ORDER BY route_code, id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<SpatialObjectRecord> findRoutes(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /** 统计稳定路线。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM mine_route
            WHERE organization_id = #{organizationId} AND site_id = #{siteId}
              <if test="keyword != null and keyword != ''">
                AND route_code ILIKE CONCAT('%', #{keyword}, '%')
              </if>
              <if test="status != null and status != ''">AND status = #{status}</if>
            </script>
            """)
    long countRoutes(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("keyword") String keyword,
            @Param("status") String status);

    /** 使用乐观锁归档稳定路线。 */
    @Update("""
            UPDATE mine_route
               SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP,
                   updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId}, version = version + 1
             WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status = 'ACTIVE' AND version = #{version}
            """)
    int archiveRoute(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 新增路线草稿版本。 */
    @Insert("""
            INSERT INTO mine_route_version (
                id, route_id, organization_id, site_id, coordinate_system_id,
                version_number, name, direction, applicable_vehicle_types,
                valid_from, valid_to, created_by, updated_by
            ) VALUES (
                #{item.id}, #{item.parentId}, #{item.organizationId}, #{item.siteId},
                #{item.coordinateSystemId}, #{item.versionNumber}, #{item.name}, #{item.type},
                CAST(#{item.applicableVehicleTypesJson} AS JSONB), #{item.validFrom}, #{item.validTo},
                #{item.operatorId}, #{item.operatorId}
            )
            """)
    int insertRouteVersion(@Param("item") RouteVersionWrite item);

    /** 使用乐观锁修改路线草稿版本。 */
    @Update("""
            UPDATE mine_route_version
               SET coordinate_system_id = #{item.coordinateSystemId}, name = #{item.name},
                   direction = #{item.type},
                   applicable_vehicle_types = CAST(#{item.applicableVehicleTypesJson} AS JSONB),
                   valid_from = #{item.validFrom}, valid_to = #{item.validTo},
                   updated_at = CURRENT_TIMESTAMP, updated_by = #{item.operatorId}, version = version + 1
             WHERE id = #{item.id} AND route_id = #{item.parentId}
               AND organization_id = #{item.organizationId} AND site_id = #{item.siteId}
               AND status = 'DRAFT' AND version = #{item.lockVersion}
            """)
    int updateRouteVersion(@Param("item") RouteVersionWrite item);

    /** 发布路线版本。 */
    @Update("""
            UPDATE mine_route_version
               SET status = 'PUBLISHED', content_hash = #{contentHash},
                   published_at = CURRENT_TIMESTAMP, published_by = #{operatorId},
                   updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId}, version = version + 1
             WHERE id = #{id} AND route_id = #{parentId}
               AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status = 'DRAFT' AND version = #{version}
            """)
    int publishRouteVersion(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("parentId") UUID parentId,
            @Param("id") UUID id,
            @Param("contentHash") String contentHash,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 删除路线草稿的旧途径点顺序。 */
    @Delete("DELETE FROM mine_route_version_waypoint WHERE route_version_id = #{versionId}")
    int deleteRouteWaypoints(@Param("versionId") UUID versionId);

    /** 批量写入路线途径点顺序。 */
    @Insert("""
            <script>
            INSERT INTO mine_route_version_waypoint (
                route_version_id, waypoint_version_id, organization_id, site_id,
                sequence_number, stop_required, expected_dwell_seconds
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (#{item.versionId}, #{item.referenceId}, #{item.organizationId}, #{item.siteId},
                 #{item.sequenceNumber}, #{item.stopRequired}, #{item.expectedDwellSeconds})
            </foreach>
            </script>
            """)
    int insertRouteWaypoints(@Param("items") List<RouteWaypointWrite> items);

    /** 查询路线版本途径点顺序。 */
    @Select("""
            SELECT route_version_id AS routeVersionId, waypoint_version_id AS waypointVersionId,
                   sequence_number AS sequenceNumber, stop_required AS stopRequired,
                   expected_dwell_seconds AS expectedDwellSeconds
            FROM mine_route_version_waypoint
            WHERE route_version_id = #{versionId}
              AND organization_id = #{organizationId} AND site_id = #{siteId}
            ORDER BY sequence_number
            """)
    List<RouteWaypointRecord> findRouteWaypoints(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("versionId") UUID versionId);

    /** 按稳定路线查询所有版本。 */
    @Select("""
            SELECT id, route_id AS routeId, organization_id AS organizationId,
                   site_id AS siteId, coordinate_system_id AS coordinateSystemId,
                   version_number AS versionNumber, name, direction,
                   applicable_vehicle_types::TEXT AS applicableVehicleTypesJson,
                   valid_from AS validFrom, valid_to AS validTo, status,
                   content_hash AS contentHash, published_at AS publishedAt,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM mine_route_version
            WHERE route_id = #{parentId} AND organization_id = #{organizationId}
              AND site_id = #{siteId}
            ORDER BY version_number, id
            """)
    List<RouteVersionRecord> findRouteVersions(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("parentId") UUID parentId);

    /** 查询指定路线版本。 */
    @Select("""
            SELECT id, route_id AS routeId, organization_id AS organizationId,
                   site_id AS siteId, coordinate_system_id AS coordinateSystemId,
                   version_number AS versionNumber, name, direction,
                   applicable_vehicle_types::TEXT AS applicableVehicleTypesJson,
                   valid_from AS validFrom, valid_to AS validTo, status,
                   content_hash AS contentHash, published_at AS publishedAt,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM mine_route_version
            WHERE id = #{id} AND route_id = #{parentId}
              AND organization_id = #{organizationId} AND site_id = #{siteId}
            """)
    Optional<RouteVersionRecord> findRouteVersionById(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("parentId") UUID parentId,
            @Param("id") UUID id);

    /** 新增稳定电子围栏。 */
    @Insert("""
            INSERT INTO mine_geofence (
                id, organization_id, site_id, geofence_code, created_by, updated_by
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{code}, #{operatorId}, #{operatorId}
            )
            """)
    int insertGeofence(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("code") String code,
            @Param("operatorId") UUID operatorId);

    /** 按矿区查询稳定电子围栏。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   geofence_code AS code, status, created_at AS createdAt,
                   updated_at AS updatedAt, archived_at AS archivedAt, version
            FROM mine_geofence
            WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
            """)
    Optional<SpatialObjectRecord> findGeofenceById(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id);

    /** 分页查询稳定电子围栏。 */
    @Select("""
            <script>
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   geofence_code AS code, status, created_at AS createdAt,
                   updated_at AS updatedAt, archived_at AS archivedAt, version
            FROM mine_geofence
            WHERE organization_id = #{organizationId} AND site_id = #{siteId}
              <if test="keyword != null and keyword != ''">
                AND geofence_code ILIKE CONCAT('%', #{keyword}, '%')
              </if>
              <if test="status != null and status != ''">AND status = #{status}</if>
            ORDER BY geofence_code, id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<SpatialObjectRecord> findGeofences(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /** 统计稳定电子围栏。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM mine_geofence
            WHERE organization_id = #{organizationId} AND site_id = #{siteId}
              <if test="keyword != null and keyword != ''">
                AND geofence_code ILIKE CONCAT('%', #{keyword}, '%')
              </if>
              <if test="status != null and status != ''">AND status = #{status}</if>
            </script>
            """)
    long countGeofences(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("keyword") String keyword,
            @Param("status") String status);

    /** 使用乐观锁归档稳定电子围栏。 */
    @Update("""
            UPDATE mine_geofence
               SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP,
                   updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId}, version = version + 1
             WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status = 'ACTIVE' AND version = #{version}
            """)
    int archiveGeofence(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 新增电子围栏草稿版本。 */
    @Insert("""
            INSERT INTO mine_geofence_version (
                id, geofence_id, organization_id, site_id, coordinate_system_id,
                version_number, name, geofence_type, min_z, max_z,
                boundary_tolerance_meters, enter_confirm_points, exit_confirm_points,
                dwell_seconds, allowed_lateness_seconds, idle_flush_seconds,
                valid_from, valid_to, created_by, updated_by
            ) VALUES (
                #{item.id}, #{item.parentId}, #{item.organizationId}, #{item.siteId},
                #{item.coordinateSystemId}, #{item.versionNumber}, #{item.name}, #{item.type},
                #{item.minZ}, #{item.maxZ}, #{item.boundaryToleranceMeters},
                #{item.enterConfirmPoints}, #{item.exitConfirmPoints}, #{item.dwellSeconds},
                #{item.allowedLatenessSeconds}, #{item.idleFlushSeconds},
                #{item.validFrom}, #{item.validTo}, #{item.operatorId}, #{item.operatorId}
            )
            """)
    int insertGeofenceVersion(@Param("item") GeofenceVersionWrite item);

    /** 使用乐观锁修改电子围栏草稿版本。 */
    @Update("""
            UPDATE mine_geofence_version
               SET coordinate_system_id = #{item.coordinateSystemId}, name = #{item.name},
                   geofence_type = #{item.type}, min_z = #{item.minZ}, max_z = #{item.maxZ},
                   boundary_tolerance_meters = #{item.boundaryToleranceMeters},
                   enter_confirm_points = #{item.enterConfirmPoints},
                   exit_confirm_points = #{item.exitConfirmPoints}, dwell_seconds = #{item.dwellSeconds},
                   allowed_lateness_seconds = #{item.allowedLatenessSeconds},
                   idle_flush_seconds = #{item.idleFlushSeconds}, valid_from = #{item.validFrom},
                   valid_to = #{item.validTo}, updated_at = CURRENT_TIMESTAMP,
                   updated_by = #{item.operatorId}, version = version + 1
             WHERE id = #{item.id} AND geofence_id = #{item.parentId}
               AND organization_id = #{item.organizationId} AND site_id = #{item.siteId}
               AND status = 'DRAFT' AND version = #{item.lockVersion}
            """)
    int updateGeofenceVersion(@Param("item") GeofenceVersionWrite item);

    /** 发布电子围栏版本。 */
    @Update("""
            UPDATE mine_geofence_version
               SET status = 'PUBLISHED', content_hash = #{contentHash},
                   published_at = CURRENT_TIMESTAMP, published_by = #{operatorId},
                   updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId}, version = version + 1
             WHERE id = #{id} AND geofence_id = #{parentId}
               AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status = 'DRAFT' AND version = #{version}
            """)
    int publishGeofenceVersion(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("parentId") UUID parentId,
            @Param("id") UUID id,
            @Param("contentHash") String contentHash,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 删除电子围栏草稿的旧顶点。 */
    @Delete("DELETE FROM mine_geofence_vertex WHERE geofence_version_id = #{versionId}")
    int deleteGeofenceVertices(@Param("versionId") UUID versionId);

    /** 批量写入电子围栏顶点。 */
    @Insert("""
            <script>
            INSERT INTO mine_geofence_vertex (
                geofence_version_id, organization_id, site_id, sequence_number, x, y
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (#{item.versionId}, #{item.organizationId}, #{item.siteId},
                 #{item.sequenceNumber}, #{item.x}, #{item.y})
            </foreach>
            </script>
            """)
    int insertGeofenceVertices(@Param("items") List<GeofenceVertexWrite> items);

    /** 查询电子围栏版本顶点。 */
    @Select("""
            SELECT geofence_version_id AS geofenceVersionId,
                   sequence_number AS sequenceNumber, x, y
            FROM mine_geofence_vertex
            WHERE geofence_version_id = #{versionId}
              AND organization_id = #{organizationId} AND site_id = #{siteId}
            ORDER BY sequence_number
            """)
    List<GeofenceVertexRecord> findGeofenceVertices(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("versionId") UUID versionId);

    /** 按稳定电子围栏查询所有版本。 */
    @Select("""
            SELECT id, geofence_id AS geofenceId, organization_id AS organizationId,
                   site_id AS siteId, coordinate_system_id AS coordinateSystemId,
                   version_number AS versionNumber, name, geofence_type AS geofenceType,
                   min_z AS minZ, max_z AS maxZ,
                   boundary_tolerance_meters AS boundaryToleranceMeters,
                   enter_confirm_points AS enterConfirmPoints,
                   exit_confirm_points AS exitConfirmPoints, dwell_seconds AS dwellSeconds,
                   allowed_lateness_seconds AS allowedLatenessSeconds,
                   idle_flush_seconds AS idleFlushSeconds, valid_from AS validFrom,
                   valid_to AS validTo, status, content_hash AS contentHash,
                   published_at AS publishedAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
            FROM mine_geofence_version
            WHERE geofence_id = #{parentId} AND organization_id = #{organizationId}
              AND site_id = #{siteId}
            ORDER BY version_number, id
            """)
    List<GeofenceVersionRecord> findGeofenceVersions(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("parentId") UUID parentId);

    /** 查询指定电子围栏版本。 */
    @Select("""
            SELECT id, geofence_id AS geofenceId, organization_id AS organizationId,
                   site_id AS siteId, coordinate_system_id AS coordinateSystemId,
                   version_number AS versionNumber, name, geofence_type AS geofenceType,
                   min_z AS minZ, max_z AS maxZ,
                   boundary_tolerance_meters AS boundaryToleranceMeters,
                   enter_confirm_points AS enterConfirmPoints,
                   exit_confirm_points AS exitConfirmPoints, dwell_seconds AS dwellSeconds,
                   allowed_lateness_seconds AS allowedLatenessSeconds,
                   idle_flush_seconds AS idleFlushSeconds, valid_from AS validFrom,
                   valid_to AS validTo, status, content_hash AS contentHash,
                   published_at AS publishedAt, created_at AS createdAt,
                   updated_at AS updatedAt, version
            FROM mine_geofence_version
            WHERE id = #{id} AND geofence_id = #{parentId}
              AND organization_id = #{organizationId} AND site_id = #{siteId}
            """)
    Optional<GeofenceVersionRecord> findGeofenceVersionById(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("parentId") UUID parentId,
            @Param("id") UUID id);

    /** 一次性查询指定时刻处于有效期内的已发布途径点和电子围栏。 */
    @Select("""
            SELECT 'WAYPOINT' AS zoneType,
                   waypoint.id AS zoneId,
                   version.id AS zoneVersionId,
                   version.coordinate_system_id AS coordinateSystemId,
                   version.name,
                   version.content_hash AS contentHash,
                   version.valid_from AS validFrom,
                   version.valid_to AS validTo,
                   'CIRCLE' AS shape,
                   version.x,
                   version.y,
                   version.radius_meters AS radiusMeters,
                   '[]' AS verticesJson
            FROM mine_waypoint_version version
            JOIN mine_waypoint waypoint
              ON waypoint.id = version.waypoint_id
             AND waypoint.organization_id = version.organization_id
             AND waypoint.site_id = version.site_id
            JOIN mine_coordinate_system coordinate
              ON coordinate.id = version.coordinate_system_id
             AND coordinate.organization_id = version.organization_id
             AND coordinate.site_id = version.site_id
            WHERE version.organization_id = #{organizationId}
              AND version.site_id = #{siteId}
              AND version.status = 'PUBLISHED'
              AND waypoint.status = 'ACTIVE'
              AND coordinate.status = 'ACTIVE'
              AND coordinate.unified = TRUE
              AND version.valid_from <= #{evaluatedAt}
              AND (version.valid_to IS NULL OR version.valid_to > #{evaluatedAt})
            UNION ALL
            SELECT 'GEOFENCE' AS zoneType,
                   geofence.id AS zoneId,
                   version.id AS zoneVersionId,
                   version.coordinate_system_id AS coordinateSystemId,
                   version.name,
                   version.content_hash AS contentHash,
                   version.valid_from AS validFrom,
                   version.valid_to AS validTo,
                   'POLYGON' AS shape,
                   NULL::double precision AS x,
                   NULL::double precision AS y,
                   NULL::double precision AS radiusMeters,
                   COALESCE(
                       jsonb_agg(
                           jsonb_build_object(
                               'sequenceNumber', vertex.sequence_number,
                               'x', vertex.x,
                               'y', vertex.y
                           ) ORDER BY vertex.sequence_number
                       ) FILTER (WHERE vertex.sequence_number IS NOT NULL),
                       '[]'::jsonb
                   )::text AS verticesJson
            FROM mine_geofence_version version
            JOIN mine_geofence geofence
              ON geofence.id = version.geofence_id
             AND geofence.organization_id = version.organization_id
             AND geofence.site_id = version.site_id
            JOIN mine_coordinate_system coordinate
              ON coordinate.id = version.coordinate_system_id
             AND coordinate.organization_id = version.organization_id
             AND coordinate.site_id = version.site_id
            LEFT JOIN mine_geofence_vertex vertex
              ON vertex.geofence_version_id = version.id
             AND vertex.organization_id = version.organization_id
             AND vertex.site_id = version.site_id
            WHERE version.organization_id = #{organizationId}
              AND version.site_id = #{siteId}
              AND version.status = 'PUBLISHED'
              AND geofence.status = 'ACTIVE'
              AND coordinate.status = 'ACTIVE'
              AND coordinate.unified = TRUE
              AND version.valid_from <= #{evaluatedAt}
              AND (version.valid_to IS NULL OR version.valid_to > #{evaluatedAt})
            GROUP BY geofence.id, version.id
            ORDER BY zoneType, name, zoneVersionId
            """)
    List<PublishedSpatialRegionRecord> findCurrentSpatialRegions(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("evaluatedAt") Instant evaluatedAt);

    /** 查询已发布空间配置晚于评估时刻的最近有效期边界。 */
    @Select("""
            SELECT MIN(change_at)
            FROM (
                SELECT version.valid_from AS change_at
                FROM mine_waypoint_version version
                JOIN mine_waypoint waypoint
                  ON waypoint.id = version.waypoint_id
                 AND waypoint.organization_id = version.organization_id
                 AND waypoint.site_id = version.site_id
                JOIN mine_coordinate_system coordinate
                  ON coordinate.id = version.coordinate_system_id
                 AND coordinate.organization_id = version.organization_id
                 AND coordinate.site_id = version.site_id
                WHERE version.organization_id = #{organizationId}
                  AND version.site_id = #{siteId}
                  AND version.status = 'PUBLISHED'
                  AND waypoint.status = 'ACTIVE'
                  AND coordinate.status = 'ACTIVE'
                  AND coordinate.unified = TRUE
                  AND version.valid_from > #{evaluatedAt}
                UNION ALL
                SELECT version.valid_to
                FROM mine_waypoint_version version
                JOIN mine_waypoint waypoint
                  ON waypoint.id = version.waypoint_id
                 AND waypoint.organization_id = version.organization_id
                 AND waypoint.site_id = version.site_id
                JOIN mine_coordinate_system coordinate
                  ON coordinate.id = version.coordinate_system_id
                 AND coordinate.organization_id = version.organization_id
                 AND coordinate.site_id = version.site_id
                WHERE version.organization_id = #{organizationId}
                  AND version.site_id = #{siteId}
                  AND version.status = 'PUBLISHED'
                  AND waypoint.status = 'ACTIVE'
                  AND coordinate.status = 'ACTIVE'
                  AND coordinate.unified = TRUE
                  AND version.valid_to > #{evaluatedAt}
                UNION ALL
                SELECT version.valid_from
                FROM mine_geofence_version version
                JOIN mine_geofence geofence
                  ON geofence.id = version.geofence_id
                 AND geofence.organization_id = version.organization_id
                 AND geofence.site_id = version.site_id
                JOIN mine_coordinate_system coordinate
                  ON coordinate.id = version.coordinate_system_id
                 AND coordinate.organization_id = version.organization_id
                 AND coordinate.site_id = version.site_id
                WHERE version.organization_id = #{organizationId}
                  AND version.site_id = #{siteId}
                  AND version.status = 'PUBLISHED'
                  AND geofence.status = 'ACTIVE'
                  AND coordinate.status = 'ACTIVE'
                  AND coordinate.unified = TRUE
                  AND version.valid_from > #{evaluatedAt}
                UNION ALL
                SELECT version.valid_to
                FROM mine_geofence_version version
                JOIN mine_geofence geofence
                  ON geofence.id = version.geofence_id
                 AND geofence.organization_id = version.organization_id
                 AND geofence.site_id = version.site_id
                JOIN mine_coordinate_system coordinate
                  ON coordinate.id = version.coordinate_system_id
                 AND coordinate.organization_id = version.organization_id
                 AND coordinate.site_id = version.site_id
                WHERE version.organization_id = #{organizationId}
                  AND version.site_id = #{siteId}
                  AND version.status = 'PUBLISHED'
                  AND geofence.status = 'ACTIVE'
                  AND coordinate.status = 'ACTIVE'
                  AND coordinate.unified = TRUE
                  AND version.valid_to > #{evaluatedAt}
            ) boundaries
            """)
    Instant findNextSpatialRegionChangeAt(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("evaluatedAt") Instant evaluatedAt);

    /** 运行空间配置所使用统一坐标系的平面和可选高程边界。 */
    record SpatialCoordinateBounds(
            UUID coordinateSystemId,
            double minX,
            double maxX,
            double minY,
            double maxY,
            Double minZ,
            Double maxZ
    ) {
    }

    /** 当前生效空间区域聚合查询记录。 */
    record PublishedSpatialRegionRecord(
            String zoneType,
            UUID zoneId,
            UUID zoneVersionId,
            UUID coordinateSystemId,
            String name,
            String contentHash,
            Instant validFrom,
            Instant validTo,
            String shape,
            Double x,
            Double y,
            Double radiusMeters,
            String verticesJson
    ) {
    }

    /** 途径点版本写入参数。 */
    record WaypointVersionWrite(
            UUID id,
            UUID parentId,
            UUID organizationId,
            UUID siteId,
            UUID coordinateSystemId,
            int versionNumber,
            String name,
            String type,
            double x,
            double y,
            Double z,
            double radiusMeters,
            Double minZ,
            Double maxZ,
            double boundaryToleranceMeters,
            int enterConfirmPoints,
            int exitConfirmPoints,
            int dwellSeconds,
            int allowedLatenessSeconds,
            int idleFlushSeconds,
            Instant validFrom,
            Instant validTo,
            UUID operatorId,
            long lockVersion
    ) {
    }

    /** 路线版本写入参数。 */
    record RouteVersionWrite(
            UUID id,
            UUID parentId,
            UUID organizationId,
            UUID siteId,
            UUID coordinateSystemId,
            int versionNumber,
            String name,
            String type,
            String applicableVehicleTypesJson,
            Instant validFrom,
            Instant validTo,
            UUID operatorId,
            long lockVersion
    ) {
    }

    /** 路线途径点写入参数。 */
    record RouteWaypointWrite(
            UUID versionId,
            UUID referenceId,
            UUID organizationId,
            UUID siteId,
            int sequenceNumber,
            boolean stopRequired,
            Integer expectedDwellSeconds
    ) {
    }

    /** 电子围栏版本写入参数。 */
    record GeofenceVersionWrite(
            UUID id,
            UUID parentId,
            UUID organizationId,
            UUID siteId,
            UUID coordinateSystemId,
            int versionNumber,
            String name,
            String type,
            Double minZ,
            Double maxZ,
            double boundaryToleranceMeters,
            int enterConfirmPoints,
            int exitConfirmPoints,
            int dwellSeconds,
            int allowedLatenessSeconds,
            int idleFlushSeconds,
            Instant validFrom,
            Instant validTo,
            UUID operatorId,
            long lockVersion
    ) {
    }

    /** 电子围栏顶点写入参数。 */
    record GeofenceVertexWrite(
            UUID versionId,
            UUID organizationId,
            UUID siteId,
            int sequenceNumber,
            double x,
            double y
    ) {
    }
}
