package com.bosco.mine.server.modules.mine.location;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 定位运行快照源数据、不可变条目和可靠事件数据访问接口。
 */
@Mapper
public interface LocationSnapshotMapper {

    /** 锁定矿区修订时间线并校验组织作用域。 */
    @Select("""
            SELECT id AS siteId, organization_id AS organizationId, status
            FROM mine_site
            WHERE id = #{siteId} AND organization_id = #{organizationId}
            FOR UPDATE
            """)
    Optional<SiteLockRecord> lockSite(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId
    );

    /** 按稳定条目键读取定位热路径所需的全部非敏感源数据。 */
    @Select("""
            SELECT item_key AS itemKey, item_type AS itemType, content::TEXT AS contentJson
            FROM (
                SELECT 'SITE:' || site.id AS item_key,
                       'SITE' AS item_type,
                       jsonb_build_object(
                           'organizationId', site.organization_id,
                           'siteCode', site.site_code,
                           'siteId', site.id,
                           'status', site.status,
                           'timezone', site.timezone
                       ) AS content
                FROM mine_site site
                WHERE site.id = #{siteId} AND site.organization_id = #{organizationId}

                UNION ALL

                SELECT 'VEHICLE:' || vehicle.id,
                       'VEHICLE',
                       jsonb_build_object(
                           'status', vehicle.status,
                           'vehicleId', vehicle.id,
                           'vehicleType', vehicle.vehicle_type
                       )
                FROM mine_vehicle vehicle
                WHERE vehicle.site_id = #{siteId}
                  AND vehicle.organization_id = #{organizationId}

                UNION ALL

                SELECT 'PERSON:' || person.id,
                       'PERSON',
                       jsonb_build_object(
                           'personId', person.id,
                           'status', person.status
                       )
                FROM mine_person person
                WHERE person.organization_id = #{organizationId}
                  AND EXISTS (
                      SELECT 1
                      FROM mine_person_tag_binding tag_binding
                      WHERE tag_binding.site_id = #{siteId}
                        AND tag_binding.organization_id = #{organizationId}
                        AND tag_binding.person_id = person.id
                      UNION ALL
                      SELECT 1
                      FROM mine_person_vehicle_binding vehicle_binding
                      WHERE vehicle_binding.site_id = #{siteId}
                        AND vehicle_binding.organization_id = #{organizationId}
                        AND vehicle_binding.person_id = person.id
                  )

                UNION ALL

                SELECT 'LOCATION_TAG:' || tag.id,
                       'LOCATION_TAG',
                       jsonb_build_object(
                           'status', tag.status,
                           'tagCode', tag.tag_code,
                           'tagId', tag.id
                       )
                FROM mine_location_tag tag
                WHERE tag.site_id = #{siteId}
                  AND tag.organization_id = #{organizationId}

                UNION ALL

                SELECT 'SOURCE_DEVICE:' || device.id,
                       'SOURCE_DEVICE',
                       jsonb_build_object(
                           'deviceId', device.id,
                           'status', device.status
                       )
                FROM dev_device device
                WHERE EXISTS (
                    SELECT 1
                    FROM mine_vehicle_device_binding binding
                    WHERE binding.site_id = #{siteId}
                      AND binding.organization_id = #{organizationId}
                      AND binding.device_id = device.id
                )

                UNION ALL

                SELECT 'COORDINATE_SYSTEM:' || coordinate.id,
                       'COORDINATE_SYSTEM',
                       jsonb_build_object(
                           'coordinateSystemId', coordinate.id,
                           'coordinateType', coordinate.coordinate_type,
                           'epsgCode', coordinate.epsg_code,
                           'maxX', coordinate.max_x,
                           'maxY', coordinate.max_y,
                           'maxZ', coordinate.max_z,
                           'minX', coordinate.min_x,
                           'minY', coordinate.min_y,
                           'minZ', coordinate.min_z,
                           'status', coordinate.status,
                           'systemCode', coordinate.system_code,
                           'unified', coordinate.unified,
                           'unit', coordinate.unit
                       )
                FROM mine_coordinate_system coordinate
                WHERE coordinate.site_id = #{siteId}
                  AND coordinate.organization_id = #{organizationId}

                UNION ALL

                SELECT 'COORDINATE_TRANSFORM:' || transform.id,
                       'COORDINATE_TRANSFORM',
                       jsonb_build_object(
                           'algorithm', transform.algorithm,
                           'contentHash', transform.content_hash,
                           'coordinateTransformId', transform.id,
                           'parameters', transform.parameters,
                           'sourceCoordinateSystemId', transform.source_coordinate_system_id,
                           'targetCoordinateSystemId', transform.target_coordinate_system_id,
                           'validFrom', transform.valid_from,
                           'validTo', transform.valid_to,
                           'versionNumber', transform.version_number
                       )
                FROM mine_coordinate_transform_version transform
                WHERE transform.site_id = #{siteId}
                  AND transform.organization_id = #{organizationId}
                  AND transform.status = 'PUBLISHED'

                UNION ALL

                SELECT 'VEHICLE_DEVICE_BINDING:' || binding.id,
                       'VEHICLE_DEVICE_BINDING',
                       jsonb_build_object(
                           'bindingId', binding.id,
                           'deviceId', binding.device_id,
                           'role', binding.binding_role,
                           'validFrom', binding.valid_from,
                           'validTo', binding.valid_to,
                           'vehicleId', binding.vehicle_id
                       )
                FROM mine_vehicle_device_binding binding
                WHERE binding.site_id = #{siteId}
                  AND binding.organization_id = #{organizationId}

                UNION ALL

                SELECT 'VEHICLE_TAG_BINDING:' || binding.id,
                       'VEHICLE_TAG_BINDING',
                       jsonb_build_object(
                           'bindingId', binding.id,
                           'tagId', binding.tag_id,
                           'validFrom', binding.valid_from,
                           'validTo', binding.valid_to,
                           'vehicleId', binding.vehicle_id
                       )
                FROM mine_vehicle_tag_binding binding
                WHERE binding.site_id = #{siteId}
                  AND binding.organization_id = #{organizationId}

                UNION ALL

                SELECT 'PERSON_TAG_BINDING:' || binding.id,
                       'PERSON_TAG_BINDING',
                       jsonb_build_object(
                           'bindingId', binding.id,
                           'personId', binding.person_id,
                           'tagId', binding.tag_id,
                           'validFrom', binding.valid_from,
                           'validTo', binding.valid_to
                       )
                FROM mine_person_tag_binding binding
                WHERE binding.site_id = #{siteId}
                  AND binding.organization_id = #{organizationId}

                UNION ALL

                SELECT 'PERSON_VEHICLE_BINDING:' || binding.id,
                       'PERSON_VEHICLE_BINDING',
                       jsonb_build_object(
                           'bindingId', binding.id,
                           'personId', binding.person_id,
                           'role', binding.assignment_role,
                           'validFrom', binding.valid_from,
                           'validTo', binding.valid_to,
                           'vehicleId', binding.vehicle_id
                       )
                FROM mine_person_vehicle_binding binding
                WHERE binding.site_id = #{siteId}
                  AND binding.organization_id = #{organizationId}
            ) source
            ORDER BY item_key
            """)
    List<SourceItemRecord> findSourceItems(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId
    );

    /** 读取矿区已发布的最新快照。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   revision, change_reason AS changeReason, item_count AS itemCount,
                   content_hash AS contentHash, created_at AS createdAt
            FROM mine_location_runtime_snapshot
            WHERE site_id = #{siteId}
            ORDER BY revision DESC
            LIMIT 1
            """)
    Optional<SnapshotRecord> findLatest(@Param("siteId") UUID siteId);

    /** 计算已锁定矿区的下一个单调修订号。 */
    @Select("""
            SELECT COALESCE(MAX(revision), 0) + 1
            FROM mine_location_runtime_snapshot
            WHERE site_id = #{siteId}
            """)
    long nextRevision(@Param("siteId") UUID siteId);

    /** 写入不可变定位运行快照头。 */
    @Insert("""
            INSERT INTO mine_location_runtime_snapshot (
                id, organization_id, site_id, revision,
                change_reason, item_count, content_hash
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{revision},
                #{changeReason}, #{itemCount}, #{contentHash}
            )
            """)
    int insertSnapshot(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("revision") long revision,
            @Param("changeReason") String changeReason,
            @Param("itemCount") int itemCount,
            @Param("contentHash") String contentHash
    );

    /** 批量写入已按键排序的规范化快照条目。 */
    @Insert("""
            <script>
            INSERT INTO mine_location_runtime_snapshot_item (
                snapshot_id, item_key, item_type, content, content_hash
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (#{item.snapshotId}, #{item.itemKey}, #{item.itemType},
                 CAST(#{item.contentJson} AS JSONB), #{item.contentHash})
            </foreach>
            </script>
            """)
    int insertItems(@Param("items") List<SnapshotItemWrite> items);

    /** 在快照事务中写入只携带定位、修订和摘要的 Outbox 事件。 */
    @Insert("""
            INSERT INTO sys_outbox_event (
                id, aggregate_type, aggregate_id, event_type,
                schema_version, payload, headers
            ) VALUES (
                #{eventId}, 'LOCATION_RUNTIME_SNAPSHOT', #{snapshotId},
                'location.runtime.changed', 1,
                CAST(#{payloadJson} AS JSONB), CAST(#{headersJson} AS JSONB)
            )
            """)
    int insertOutbox(
            @Param("eventId") UUID eventId,
            @Param("snapshotId") UUID snapshotId,
            @Param("payloadJson") String payloadJson,
            @Param("headersJson") String headersJson
    );

    /** 按标识读取不可变定位运行快照头。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   revision, change_reason AS changeReason, item_count AS itemCount,
                   content_hash AS contentHash, created_at AS createdAt
            FROM mine_location_runtime_snapshot
            WHERE id = #{snapshotId}
            """)
    Optional<SnapshotRecord> findSnapshot(@Param("snapshotId") UUID snapshotId);

    /** 使用稳定条目键游标分页读取指定快照。 */
    @Select("""
            <script>
            SELECT snapshot_id AS snapshotId, item_key AS itemKey,
                   item_type AS itemType, content::TEXT AS contentJson,
                   content_hash AS contentHash
            FROM mine_location_runtime_snapshot_item
            WHERE snapshot_id = #{snapshotId}
            <if test="afterItemKey != null and afterItemKey != ''">
              AND item_key &gt; #{afterItemKey}
            </if>
            ORDER BY item_key
            LIMIT #{limit}
            </script>
            """)
    List<SnapshotItemRecord> findItems(
            @Param("snapshotId") UUID snapshotId,
            @Param("afterItemKey") String afterItemKey,
            @Param("limit") int limit
    );

    /** 已锁定矿区的组织和生命周期状态。 */
    record SiteLockRecord(UUID siteId, UUID organizationId, String status) {
    }

    /** 待规范化的定位运行源条目。 */
    record SourceItemRecord(String itemKey, String itemType, String contentJson) {
    }

    /** 待批量写入的定位运行快照条目。 */
    record SnapshotItemWrite(
            UUID snapshotId,
            String itemKey,
            String itemType,
            String contentJson,
            String contentHash
    ) {
    }

    /** 数据库中的不可变定位运行快照头。 */
    record SnapshotRecord(
            UUID id,
            UUID organizationId,
            UUID siteId,
            long revision,
            String changeReason,
            int itemCount,
            String contentHash,
            Instant createdAt
    ) {
    }

    /** 数据库中的单个定位运行快照条目。 */
    record SnapshotItemRecord(
            UUID snapshotId,
            String itemKey,
            String itemType,
            String contentJson,
            String contentHash
    ) {
    }
}
