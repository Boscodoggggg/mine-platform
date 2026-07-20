package com.bosco.mine.server.modules.mine.repository;

import com.bosco.mine.server.modules.mine.model.MineCoordinateSystem;
import com.bosco.mine.server.modules.mine.model.MineCoordinateTransform;
import com.bosco.mine.server.modules.mine.model.MineLocationTag;
import com.bosco.mine.server.modules.mine.model.MineVehicle;
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
 * 矿区车辆、定位标签和双坐标主数据访问接口。
 */
@Mapper
public interface MineAssetMapper {

    /** 按矿区作用域查询车辆。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   vehicle_code AS vehicleCode, name, vehicle_type AS vehicleType,
                   mine_number AS mineNumber, license_plate AS licensePlate,
                   brand, model, rated_load_tons AS ratedLoadTons, status,
                   created_at AS createdAt, updated_at AS updatedAt,
                   archived_at AS archivedAt, version
            FROM mine_vehicle
            WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
            """)
    Optional<MineVehicle> findVehicleById(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id);

    /** 分页查询矿区车辆。 */
    List<MineVehicle> findVehicles(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /** 统计矿区车辆。 */
    long countVehicles(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("keyword") String keyword,
            @Param("status") String status);

    /** 新增矿区车辆。 */
    @Insert("""
            INSERT INTO mine_vehicle (
                id, organization_id, site_id, vehicle_code, name, vehicle_type,
                mine_number, license_plate, brand, model, rated_load_tons,
                created_by, updated_by
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{vehicleCode}, #{name}, #{vehicleType},
                #{mineNumber}, #{licensePlate}, #{brand}, #{model}, #{ratedLoadTons},
                #{operatorId}, #{operatorId}
            )
            """)
    int insertVehicle(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("vehicleCode") String vehicleCode,
            @Param("name") String name,
            @Param("vehicleType") String vehicleType,
            @Param("mineNumber") String mineNumber,
            @Param("licensePlate") String licensePlate,
            @Param("brand") String brand,
            @Param("model") String model,
            @Param("ratedLoadTons") BigDecimal ratedLoadTons,
            @Param("operatorId") UUID operatorId);

    /** 使用乐观锁修改活动车辆。 */
    @Update("""
            UPDATE mine_vehicle
               SET name = #{name}, vehicle_type = #{vehicleType}, mine_number = #{mineNumber},
                   license_plate = #{licensePlate}, brand = #{brand}, model = #{model},
                   rated_load_tons = #{ratedLoadTons}, updated_at = CURRENT_TIMESTAMP,
                   updated_by = #{operatorId}, version = version + 1
             WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status <> 'ARCHIVED' AND version = #{version}
            """)
    int updateVehicle(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("name") String name,
            @Param("vehicleType") String vehicleType,
            @Param("mineNumber") String mineNumber,
            @Param("licensePlate") String licensePlate,
            @Param("brand") String brand,
            @Param("model") String model,
            @Param("ratedLoadTons") BigDecimal ratedLoadTons,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 使用乐观锁归档车辆。 */
    @Update("""
            UPDATE mine_vehicle
               SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP,
                   updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId},
                   version = version + 1
             WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status <> 'ARCHIVED' AND version = #{version}
            """)
    int archiveVehicle(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 按矿区作用域查询定位标签。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   tag_code AS tagCode, vendor, model, battery_percent AS batteryPercent,
                   battery_observed_at AS batteryObservedAt, status,
                   created_at AS createdAt, updated_at AS updatedAt,
                   archived_at AS archivedAt, version
            FROM mine_location_tag
            WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
            """)
    Optional<MineLocationTag> findLocationTagById(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id);

    /** 分页查询定位标签。 */
    List<MineLocationTag> findLocationTags(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /** 统计定位标签。 */
    long countLocationTags(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("keyword") String keyword,
            @Param("status") String status);

    /** 新增定位标签。 */
    @Insert("""
            INSERT INTO mine_location_tag (
                id, organization_id, site_id, tag_code, vendor, model,
                battery_percent, battery_observed_at, created_by, updated_by
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{tagCode}, #{vendor}, #{model},
                #{batteryPercent}, #{batteryObservedAt}, #{operatorId}, #{operatorId}
            )
            """)
    int insertLocationTag(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("tagCode") String tagCode,
            @Param("vendor") String vendor,
            @Param("model") String model,
            @Param("batteryPercent") Integer batteryPercent,
            @Param("batteryObservedAt") Instant batteryObservedAt,
            @Param("operatorId") UUID operatorId);

    /** 使用乐观锁修改定位标签。 */
    @Update("""
            UPDATE mine_location_tag
               SET vendor = #{vendor}, model = #{model}, battery_percent = #{batteryPercent},
                   battery_observed_at = #{batteryObservedAt}, updated_at = CURRENT_TIMESTAMP,
                   updated_by = #{operatorId}, version = version + 1
             WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status <> 'ARCHIVED' AND version = #{version}
            """)
    int updateLocationTag(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("vendor") String vendor,
            @Param("model") String model,
            @Param("batteryPercent") Integer batteryPercent,
            @Param("batteryObservedAt") Instant batteryObservedAt,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 使用乐观锁归档定位标签。 */
    @Update("""
            UPDATE mine_location_tag
               SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP,
                   updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId},
                   version = version + 1
             WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status <> 'ARCHIVED' AND version = #{version}
            """)
    int archiveLocationTag(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 按矿区作用域查询坐标系。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   system_code AS systemCode, name, coordinate_type AS coordinateType,
                   unit, epsg_code AS epsgCode, unified,
                   min_x AS minX, max_x AS maxX, min_y AS minY, max_y AS maxY,
                   min_z AS minZ, max_z AS maxZ, status,
                   created_at AS createdAt, updated_at AS updatedAt,
                   archived_at AS archivedAt, version
            FROM mine_coordinate_system
            WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
            """)
    Optional<MineCoordinateSystem> findCoordinateSystemById(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id);

    /** 分页查询坐标系。 */
    List<MineCoordinateSystem> findCoordinateSystems(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /** 统计坐标系。 */
    long countCoordinateSystems(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("keyword") String keyword,
            @Param("status") String status);

    /** 新增坐标系。 */
    @Insert("""
            INSERT INTO mine_coordinate_system (
                id, organization_id, site_id, system_code, name, coordinate_type,
                unit, epsg_code, unified, min_x, max_x, min_y, max_y, min_z, max_z,
                created_by, updated_by
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{systemCode}, #{name}, #{coordinateType},
                #{unit}, #{epsgCode}, #{unified}, #{minX}, #{maxX}, #{minY}, #{maxY},
                #{minZ}, #{maxZ}, #{operatorId}, #{operatorId}
            )
            """)
    int insertCoordinateSystem(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("systemCode") String systemCode,
            @Param("name") String name,
            @Param("coordinateType") String coordinateType,
            @Param("unit") String unit,
            @Param("epsgCode") Integer epsgCode,
            @Param("unified") boolean unified,
            @Param("minX") double minX,
            @Param("maxX") double maxX,
            @Param("minY") double minY,
            @Param("maxY") double maxY,
            @Param("minZ") Double minZ,
            @Param("maxZ") Double maxZ,
            @Param("operatorId") UUID operatorId);

    /** 使用乐观锁修改活动坐标系。 */
    @Update("""
            UPDATE mine_coordinate_system
               SET name = #{name}, coordinate_type = #{coordinateType}, unit = #{unit},
                   epsg_code = #{epsgCode}, unified = #{unified},
                   min_x = #{minX}, max_x = #{maxX}, min_y = #{minY}, max_y = #{maxY},
                   min_z = #{minZ}, max_z = #{maxZ}, updated_at = CURRENT_TIMESTAMP,
                   updated_by = #{operatorId}, version = version + 1
             WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status = 'ACTIVE' AND version = #{version}
            """)
    int updateCoordinateSystem(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("name") String name,
            @Param("coordinateType") String coordinateType,
            @Param("unit") String unit,
            @Param("epsgCode") Integer epsgCode,
            @Param("unified") boolean unified,
            @Param("minX") double minX,
            @Param("maxX") double maxX,
            @Param("minY") double minY,
            @Param("maxY") double maxY,
            @Param("minZ") Double minZ,
            @Param("maxZ") Double maxZ,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 使用乐观锁归档坐标系。 */
    @Update("""
            UPDATE mine_coordinate_system
               SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP,
                   updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId},
                   version = version + 1
             WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status = 'ACTIVE' AND version = #{version}
            """)
    int archiveCoordinateSystem(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 按矿区作用域查询坐标转换版本。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   source_coordinate_system_id AS sourceCoordinateSystemId,
                   target_coordinate_system_id AS targetCoordinateSystemId,
                   version_number AS versionNumber, algorithm, parameters::text AS parametersJson,
                   valid_from AS validFrom, valid_to AS validTo, status,
                   content_hash AS contentHash, published_at AS publishedAt,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM mine_coordinate_transform_version
            WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
            """)
    Optional<MineCoordinateTransform> findCoordinateTransformById(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id);

    /** 分页查询坐标转换版本。 */
    List<MineCoordinateTransform> findCoordinateTransforms(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /** 统计坐标转换版本。 */
    long countCoordinateTransforms(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("status") String status);

    /** 新增坐标转换草稿。 */
    @Insert("""
            INSERT INTO mine_coordinate_transform_version (
                id, organization_id, site_id,
                source_coordinate_system_id, target_coordinate_system_id,
                version_number, algorithm, parameters, valid_from, valid_to,
                created_by, updated_by
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{sourceId}, #{targetId},
                #{versionNumber}, #{algorithm}, CAST(#{parametersJson} AS JSONB),
                #{validFrom}, #{validTo}, #{operatorId}, #{operatorId}
            )
            """)
    int insertCoordinateTransform(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("sourceId") UUID sourceId,
            @Param("targetId") UUID targetId,
            @Param("versionNumber") int versionNumber,
            @Param("algorithm") String algorithm,
            @Param("parametersJson") String parametersJson,
            @Param("validFrom") Instant validFrom,
            @Param("validTo") Instant validTo,
            @Param("operatorId") UUID operatorId);

    /** 使用乐观锁修改坐标转换草稿。 */
    @Update("""
            UPDATE mine_coordinate_transform_version
               SET algorithm = #{algorithm}, parameters = CAST(#{parametersJson} AS JSONB),
                   valid_from = #{validFrom}, valid_to = #{validTo},
                   updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId},
                   version = version + 1
             WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status = 'DRAFT' AND version = #{version}
            """)
    int updateCoordinateTransform(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("algorithm") String algorithm,
            @Param("parametersJson") String parametersJson,
            @Param("validFrom") Instant validFrom,
            @Param("validTo") Instant validTo,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 使用乐观锁发布坐标转换草稿。 */
    @Update("""
            UPDATE mine_coordinate_transform_version
               SET status = 'PUBLISHED', content_hash = #{contentHash},
                   published_at = CURRENT_TIMESTAMP, published_by = #{operatorId},
                   updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId},
                   version = version + 1
             WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status = 'DRAFT' AND version = #{version}
            """)
    int publishCoordinateTransform(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("contentHash") String contentHash,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);
}
