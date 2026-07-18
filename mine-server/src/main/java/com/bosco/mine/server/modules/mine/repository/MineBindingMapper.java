package com.bosco.mine.server.modules.mine.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * 矿区四类时间绑定、换绑幂等操作和业务事件数据访问接口。
 */
@Mapper
public interface MineBindingMapper {

    /** 锁定并读取当前矿区状态，防止执行期间并发归档。 */
    @Select("""
            SELECT status
            FROM mine_site
            WHERE id = #{siteId} AND organization_id = #{organizationId}
            FOR UPDATE
            """)
    Optional<String> lockSite(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId
    );

    /** 锁定并读取设备状态，防止执行期间并发禁用或归档。 */
    @Select("""
            SELECT status FROM dev_device WHERE id = #{deviceId} FOR UPDATE
            """)
    Optional<String> lockDevice(@Param("deviceId") UUID deviceId);

    /** 锁定并读取矿区定位标签状态，防止执行期间并发归档。 */
    @Select("""
            SELECT status
            FROM mine_location_tag
            WHERE id = #{tagId}
              AND organization_id = #{organizationId}
              AND site_id = #{siteId}
            FOR UPDATE
            """)
    Optional<String> lockTag(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("tagId") UUID tagId
    );

    /** 锁定并读取矿区车辆状态和车型，防止执行期间并发归档或改变车型。 */
    @Select("""
            SELECT status, vehicle_type AS vehicleType
            FROM mine_vehicle
            WHERE id = #{vehicleId}
              AND organization_id = #{organizationId}
              AND site_id = #{siteId}
            FOR UPDATE
            """)
    Optional<LockedVehicleRecord> lockVehicle(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("vehicleId") UUID vehicleId
    );

    /** 按资源标识串行化同一设备、标签或人员的换绑操作。 */
    @Select("""
            SELECT pg_advisory_xact_lock(
                hashtextextended(CAST(#{resourceId} AS TEXT), 487311842021)
            )
            """)
    String lockSwitchResource(@Param("resourceId") UUID resourceId);

    /** 争用人员任职与绑定共用的事务锁，避免任职区间检查后发生并发变化。 */
    @Select("""
            SELECT pg_advisory_xact_lock(
                hashtextextended(CAST(#{personId} AS TEXT), 487311842019)
            )
            """)
    String lockPersonTimeline(@Param("personId") UUID personId);

    /** 按管理员和幂等键查询已提交的换绑结果。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   operator_id AS operatorId, idempotency_key AS idempotencyKey,
                   request_hash AS requestHash, binding_type AS bindingType,
                   resource_id AS resourceId, target_id AS targetId,
                   binding_role AS role, effective_at AS effectiveAt, reason,
                   previous_binding_type AS previousBindingType,
                   previous_binding_id AS previousBindingId,
                   new_binding_id AS newBindingId,
                   qualification_id AS qualificationId,
                   outbox_event_id AS outboxEventId,
                   response_payload::TEXT AS responsePayload,
                   created_at AS createdAt
            FROM mine_binding_switch_operation
            WHERE operator_id = #{operatorId}
              AND idempotency_key = #{idempotencyKey}
            """)
    Optional<SwitchOperationRecord> findOperation(
            @Param("operatorId") UUID operatorId,
            @Param("idempotencyKey") String idempotencyKey
    );

    /** 抢占管理员幂等键并保存拟提交的完整换绑结果标识。 */
    @Insert("""
            INSERT INTO mine_binding_switch_operation (
                id, organization_id, site_id, operator_id, idempotency_key,
                request_hash, binding_type, resource_id, target_id, binding_role,
                effective_at, reason, previous_binding_type, previous_binding_id,
                new_binding_id, qualification_id, outbox_event_id, response_payload
            ) VALUES (
                #{operationId}, #{organizationId}, #{siteId}, #{operatorId},
                #{idempotencyKey}, #{requestHash}, #{bindingType}, #{resourceId},
                #{targetId}, #{role}, #{effectiveAt}, #{reason},
                #{previousBindingType}, #{previousBindingId}, #{newBindingId},
                #{qualificationId}, #{outboxEventId}, CAST(#{responsePayload} AS JSONB)
            )
            ON CONFLICT (operator_id, idempotency_key) DO NOTHING
            """)
    int insertOperation(
            @Param("operationId") UUID operationId,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("operatorId") UUID operatorId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("bindingType") String bindingType,
            @Param("resourceId") UUID resourceId,
            @Param("targetId") UUID targetId,
            @Param("role") String role,
            @Param("effectiveAt") Instant effectiveAt,
            @Param("reason") String reason,
            @Param("previousBindingType") String previousBindingType,
            @Param("previousBindingId") UUID previousBindingId,
            @Param("newBindingId") UUID newBindingId,
            @Param("qualificationId") UUID qualificationId,
            @Param("outboxEventId") UUID outboxEventId,
            @Param("responsePayload") String responsePayload
    );

    /** 查询标签在指定发生时刻属于车辆还是人员。 */
    @Select("""
            SELECT binding_id AS bindingId, binding_type AS bindingType,
                   organization_id AS organizationId, site_id AS siteId,
                   resource_id AS resourceId, target_id AS targetId, role,
                   valid_from AS validFrom, valid_to AS validTo
            FROM (
                SELECT id AS binding_id, 'VEHICLE_TAG' AS binding_type,
                       organization_id, site_id, tag_id AS resource_id,
                       vehicle_id AS target_id, NULL::VARCHAR AS role,
                       valid_from, valid_to
                FROM mine_vehicle_tag_binding
                WHERE tag_id = #{tagId} AND valid_period @> #{occurredAt}::TIMESTAMPTZ
                UNION ALL
                SELECT id, 'PERSON_TAG', organization_id, site_id, tag_id,
                       person_id, NULL::VARCHAR, valid_from, valid_to
                FROM mine_person_tag_binding
                WHERE tag_id = #{tagId} AND valid_period @> #{occurredAt}::TIMESTAMPTZ
            ) resolved
            ORDER BY valid_from DESC, binding_id
            LIMIT 1
            """)
    Optional<BindingRecord> findTagBindingAt(
            @Param("tagId") UUID tagId,
            @Param("occurredAt") Instant occurredAt
    );

    /** 查询设备在指定发生时刻所属车辆。 */
    @Select("""
            SELECT id AS bindingId, 'VEHICLE_DEVICE' AS bindingType,
                   organization_id AS organizationId, site_id AS siteId,
                   device_id AS resourceId, vehicle_id AS targetId,
                   binding_role AS role, valid_from AS validFrom, valid_to AS validTo
            FROM mine_vehicle_device_binding
            WHERE device_id = #{deviceId}
              AND valid_period @> #{occurredAt}::TIMESTAMPTZ
            ORDER BY valid_from DESC, id
            LIMIT 1
            """)
    Optional<BindingRecord> findDeviceBindingAt(
            @Param("deviceId") UUID deviceId,
            @Param("occurredAt") Instant occurredAt
    );

    /** 查询人员在指定发生时刻驾驶的车辆。 */
    @Select("""
            SELECT id AS bindingId, 'PERSON_VEHICLE' AS bindingType,
                   organization_id AS organizationId, site_id AS siteId,
                   person_id AS resourceId, vehicle_id AS targetId,
                   assignment_role AS role, valid_from AS validFrom, valid_to AS validTo
            FROM mine_person_vehicle_binding
            WHERE person_id = #{personId}
              AND valid_period @> #{occurredAt}::TIMESTAMPTZ
            ORDER BY valid_from DESC, id
            LIMIT 1
            """)
    Optional<BindingRecord> findPersonVehicleBindingAt(
            @Param("personId") UUID personId,
            @Param("occurredAt") Instant occurredAt
    );

    /** 查询目标车辆与拟建区间重叠的主设备关系。 */
    @Select("""
            SELECT id AS bindingId, 'VEHICLE_DEVICE' AS bindingType,
                   organization_id AS organizationId, site_id AS siteId,
                   device_id AS resourceId, vehicle_id AS targetId,
                   binding_role AS role, valid_from AS validFrom, valid_to AS validTo
            FROM mine_vehicle_device_binding
            WHERE vehicle_id = #{vehicleId}
              AND binding_role = 'PRIMARY'
              AND valid_period && tstzrange(
                    CAST(#{validFrom} AS TIMESTAMPTZ),
                    CAST(#{validTo} AS TIMESTAMPTZ), '[)')
              AND (CAST(#{excludedBindingId} AS UUID) IS NULL
                   OR id <> CAST(#{excludedBindingId} AS UUID))
            ORDER BY valid_from DESC, id
            LIMIT 1
            """)
    Optional<BindingRecord> findPrimaryDeviceOverlapAtVehicle(
            @Param("vehicleId") UUID vehicleId,
            @Param("validFrom") Instant validFrom,
            @Param("validTo") Instant validTo,
            @Param("excludedBindingId") UUID excludedBindingId
    );

    /** 查询目标车辆与拟建区间重叠的定位标签关系。 */
    @Select("""
            SELECT id AS bindingId, 'VEHICLE_TAG' AS bindingType,
                   organization_id AS organizationId, site_id AS siteId,
                   tag_id AS resourceId, vehicle_id AS targetId,
                   NULL::VARCHAR AS role, valid_from AS validFrom, valid_to AS validTo
            FROM mine_vehicle_tag_binding
            WHERE vehicle_id = #{vehicleId}
              AND valid_period && tstzrange(
                    CAST(#{validFrom} AS TIMESTAMPTZ),
                    CAST(#{validTo} AS TIMESTAMPTZ), '[)')
              AND (CAST(#{excludedBindingId} AS UUID) IS NULL
                   OR id <> CAST(#{excludedBindingId} AS UUID))
            ORDER BY valid_from DESC, id
            LIMIT 1
            """)
    Optional<BindingRecord> findTagOverlapAtVehicle(
            @Param("vehicleId") UUID vehicleId,
            @Param("validFrom") Instant validFrom,
            @Param("validTo") Instant validTo,
            @Param("excludedBindingId") UUID excludedBindingId
    );

    /** 查询目标人员与拟建区间重叠的定位标签关系。 */
    @Select("""
            SELECT id AS bindingId, 'PERSON_TAG' AS bindingType,
                   organization_id AS organizationId, site_id AS siteId,
                   tag_id AS resourceId, person_id AS targetId,
                   NULL::VARCHAR AS role, valid_from AS validFrom, valid_to AS validTo
            FROM mine_person_tag_binding
            WHERE person_id = #{personId}
              AND valid_period && tstzrange(
                    CAST(#{validFrom} AS TIMESTAMPTZ),
                    CAST(#{validTo} AS TIMESTAMPTZ), '[)')
              AND (CAST(#{excludedBindingId} AS UUID) IS NULL
                   OR id <> CAST(#{excludedBindingId} AS UUID))
            ORDER BY valid_from DESC, id
            LIMIT 1
            """)
    Optional<BindingRecord> findTagOverlapAtPerson(
            @Param("personId") UUID personId,
            @Param("validFrom") Instant validFrom,
            @Param("validTo") Instant validTo,
            @Param("excludedBindingId") UUID excludedBindingId
    );

    /** 查询目标车辆与拟建区间重叠的主司机关系。 */
    @Select("""
            SELECT id AS bindingId, 'PERSON_VEHICLE' AS bindingType,
                   organization_id AS organizationId, site_id AS siteId,
                   person_id AS resourceId, vehicle_id AS targetId,
                   assignment_role AS role, valid_from AS validFrom, valid_to AS validTo
            FROM mine_person_vehicle_binding
            WHERE vehicle_id = #{vehicleId}
              AND assignment_role = 'PRIMARY_DRIVER'
              AND valid_period && tstzrange(
                    CAST(#{validFrom} AS TIMESTAMPTZ),
                    CAST(#{validTo} AS TIMESTAMPTZ), '[)')
              AND (CAST(#{excludedBindingId} AS UUID) IS NULL
                   OR id <> CAST(#{excludedBindingId} AS UUID))
            ORDER BY valid_from DESC, id
            LIMIT 1
            """)
    Optional<BindingRecord> findPrimaryDriverOverlapAtVehicle(
            @Param("vehicleId") UUID vehicleId,
            @Param("validFrom") Instant validFrom,
            @Param("validTo") Instant validTo,
            @Param("excludedBindingId") UUID excludedBindingId
    );

    /** 判断设备除待关闭关系外是否已有与拟建区间重叠的关系。 */
    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM mine_vehicle_device_binding
                WHERE device_id = #{deviceId}
                  AND valid_period && tstzrange(
                        CAST(#{validFrom} AS TIMESTAMPTZ),
                        CAST(#{validTo} AS TIMESTAMPTZ), '[)')
                  AND (CAST(#{excludedBindingId} AS UUID) IS NULL
                       OR id <> CAST(#{excludedBindingId} AS UUID))
            )
            """)
    boolean hasDeviceBindingOverlap(
            @Param("deviceId") UUID deviceId,
            @Param("validFrom") Instant validFrom,
            @Param("validTo") Instant validTo,
            @Param("excludedBindingId") UUID excludedBindingId
    );

    /** 判断标签除待关闭关系外是否已有与拟建区间重叠的跨类型关系。 */
    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM mine_location_tag_binding_claim
                WHERE tag_id = #{tagId}
                  AND valid_period && tstzrange(
                        CAST(#{validFrom} AS TIMESTAMPTZ),
                        CAST(#{validTo} AS TIMESTAMPTZ), '[)')
                  AND (CAST(#{excludedBindingId} AS UUID) IS NULL
                       OR binding_id <> CAST(#{excludedBindingId} AS UUID))
            )
            """)
    boolean hasTagBindingOverlap(
            @Param("tagId") UUID tagId,
            @Param("validFrom") Instant validFrom,
            @Param("validTo") Instant validTo,
            @Param("excludedBindingId") UUID excludedBindingId
    );

    /** 判断人员除待关闭关系外是否已有与拟建区间重叠的车辆关系。 */
    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM mine_person_vehicle_binding
                WHERE person_id = #{personId}
                  AND valid_period && tstzrange(
                        CAST(#{validFrom} AS TIMESTAMPTZ),
                        CAST(#{validTo} AS TIMESTAMPTZ), '[)')
                  AND (CAST(#{excludedBindingId} AS UUID) IS NULL
                       OR id <> CAST(#{excludedBindingId} AS UUID))
            )
            """)
    boolean hasPersonVehicleBindingOverlap(
            @Param("personId") UUID personId,
            @Param("validFrom") Instant validFrom,
            @Param("validTo") Instant validTo,
            @Param("excludedBindingId") UUID excludedBindingId
    );

    /** 按明确类型和标识查询历史绑定，用于重放原幂等结果。 */
    @Select("""
            SELECT binding_id AS bindingId, binding_type AS bindingType,
                   organization_id AS organizationId, site_id AS siteId,
                   resource_id AS resourceId, target_id AS targetId, role,
                   valid_from AS validFrom, valid_to AS validTo
            FROM (
                SELECT id AS binding_id, 'VEHICLE_DEVICE' AS binding_type,
                       organization_id, site_id, device_id AS resource_id,
                       vehicle_id AS target_id, binding_role AS role, valid_from, valid_to
                FROM mine_vehicle_device_binding
                UNION ALL
                SELECT id, 'VEHICLE_TAG', organization_id, site_id, tag_id,
                       vehicle_id, NULL::VARCHAR, valid_from, valid_to
                FROM mine_vehicle_tag_binding
                UNION ALL
                SELECT id, 'PERSON_TAG', organization_id, site_id, tag_id,
                       person_id, NULL::VARCHAR, valid_from, valid_to
                FROM mine_person_tag_binding
                UNION ALL
                SELECT id, 'PERSON_VEHICLE', organization_id, site_id, person_id,
                       vehicle_id, assignment_role, valid_from, valid_to
                FROM mine_person_vehicle_binding
            ) binding
            WHERE binding_type = #{bindingType}
              AND binding_id = #{bindingId}
            """)
    Optional<BindingRecord> findBindingById(
            @Param("bindingType") String bindingType,
            @Param("bindingId") UUID bindingId
    );

    /** 查询人员在指定发生时刻的当前矿区有效任职，且要求人员档案活动。 */
    @Select("""
            SELECT assignment.id, assignment.valid_from AS validFrom,
                   assignment.valid_to AS validTo
            FROM mine_person person
            JOIN mine_person_site_assignment assignment
              ON assignment.organization_id = person.organization_id
             AND assignment.person_id = person.id
             AND assignment.site_id = #{siteId}
             AND assignment.valid_period @> #{effectiveAt}::TIMESTAMPTZ
            WHERE person.organization_id = #{organizationId}
              AND person.id = #{personId}
              AND person.status = 'ACTIVE'
            ORDER BY assignment.valid_from DESC, assignment.id
            LIMIT 1
            """)
    Optional<PersonAssignmentRecord> findActivePersonAssignment(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("personId") UUID personId,
            @Param("effectiveAt") Instant effectiveAt
    );

    /** 查询覆盖车型和生效日期的活动司机资质及其包含式结束日期。 */
    @Select("""
            SELECT id, valid_to AS validTo
            FROM mine_driver_qualification
            WHERE organization_id = #{organizationId}
              AND person_id = #{personId}
              AND status = 'ACTIVE'
              AND valid_from <= #{effectiveDate}
              AND valid_to >= #{effectiveDate}
              AND allowed_vehicle_types @> jsonb_build_array(CAST(#{vehicleType} AS TEXT))
            ORDER BY valid_to DESC, id
            LIMIT 1
            """)
    Optional<QualificationRecord> findValidQualification(
            @Param("organizationId") UUID organizationId,
            @Param("personId") UUID personId,
            @Param("vehicleType") String vehicleType,
            @Param("effectiveDate") LocalDate effectiveDate
    );

    /** 在切换时刻关闭车辆设备旧关系。 */
    @Update("""
            UPDATE mine_vehicle_device_binding
            SET valid_to = #{effectiveAt}, updated_at = CURRENT_TIMESTAMP,
                updated_by = #{operatorId}, version = version + 1
            WHERE id = #{bindingId}
              AND valid_from < #{effectiveAt}
              AND (valid_to IS NULL OR valid_to > #{effectiveAt})
            """)
    int closeVehicleDevice(
            @Param("bindingId") UUID bindingId,
            @Param("effectiveAt") Instant effectiveAt,
            @Param("operatorId") UUID operatorId
    );

    /** 在切换时刻关闭标签共享占用记录。 */
    @Update("""
            UPDATE mine_location_tag_binding_claim
            SET valid_to = #{effectiveAt}
            WHERE binding_id = #{bindingId}
              AND valid_from < #{effectiveAt}
              AND (valid_to IS NULL OR valid_to > #{effectiveAt})
            """)
    int closeTagClaim(
            @Param("bindingId") UUID bindingId,
            @Param("effectiveAt") Instant effectiveAt
    );

    /** 在切换时刻关闭车辆标签旧关系。 */
    @Update("""
            UPDATE mine_vehicle_tag_binding
            SET valid_to = #{effectiveAt}, updated_at = CURRENT_TIMESTAMP,
                updated_by = #{operatorId}, version = version + 1
            WHERE id = #{bindingId}
              AND valid_from < #{effectiveAt}
              AND (valid_to IS NULL OR valid_to > #{effectiveAt})
            """)
    int closeVehicleTag(
            @Param("bindingId") UUID bindingId,
            @Param("effectiveAt") Instant effectiveAt,
            @Param("operatorId") UUID operatorId
    );

    /** 在切换时刻关闭人员标签旧关系。 */
    @Update("""
            UPDATE mine_person_tag_binding
            SET valid_to = #{effectiveAt}, updated_at = CURRENT_TIMESTAMP,
                updated_by = #{operatorId}, version = version + 1
            WHERE id = #{bindingId}
              AND valid_from < #{effectiveAt}
              AND (valid_to IS NULL OR valid_to > #{effectiveAt})
            """)
    int closePersonTag(
            @Param("bindingId") UUID bindingId,
            @Param("effectiveAt") Instant effectiveAt,
            @Param("operatorId") UUID operatorId
    );

    /** 在切换时刻关闭人员车辆旧关系。 */
    @Update("""
            UPDATE mine_person_vehicle_binding
            SET valid_to = #{effectiveAt}, updated_at = CURRENT_TIMESTAMP,
                updated_by = #{operatorId}, version = version + 1
            WHERE id = #{bindingId}
              AND valid_from < #{effectiveAt}
              AND (valid_to IS NULL OR valid_to > #{effectiveAt})
            """)
    int closePersonVehicle(
            @Param("bindingId") UUID bindingId,
            @Param("effectiveAt") Instant effectiveAt,
            @Param("operatorId") UUID operatorId
    );

    /** 建立新的车辆设备关系。 */
    @Insert("""
            INSERT INTO mine_vehicle_device_binding (
                id, organization_id, site_id, vehicle_id, device_id,
                binding_role, valid_from, reason, created_by, updated_by
            ) VALUES (
                #{bindingId}, #{organizationId}, #{siteId}, #{vehicleId}, #{deviceId},
                #{role}, #{effectiveAt}, #{reason}, #{operatorId}, #{operatorId}
            )
            """)
    int insertVehicleDevice(
            @Param("bindingId") UUID bindingId,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("vehicleId") UUID vehicleId,
            @Param("deviceId") UUID deviceId,
            @Param("role") String role,
            @Param("effectiveAt") Instant effectiveAt,
            @Param("reason") String reason,
            @Param("operatorId") UUID operatorId
    );

    /** 建立标签共享占用记录。 */
    @Insert("""
            INSERT INTO mine_location_tag_binding_claim (
                binding_id, organization_id, site_id, binding_type,
                tag_id, valid_from, valid_to
            ) VALUES (
                #{bindingId}, #{organizationId}, #{siteId}, #{claimType},
                #{tagId}, #{effectiveAt}, #{validTo}
            )
            """)
    int insertTagClaim(
            @Param("bindingId") UUID bindingId,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("claimType") String claimType,
            @Param("tagId") UUID tagId,
            @Param("effectiveAt") Instant effectiveAt,
            @Param("validTo") Instant validTo
    );

    /** 建立新的车辆标签关系。 */
    @Insert("""
            INSERT INTO mine_vehicle_tag_binding (
                id, organization_id, site_id, vehicle_id, tag_id,
                valid_from, reason, created_by, updated_by
            ) VALUES (
                #{bindingId}, #{organizationId}, #{siteId}, #{vehicleId}, #{tagId},
                #{effectiveAt}, #{reason}, #{operatorId}, #{operatorId}
            )
            """)
    int insertVehicleTag(
            @Param("bindingId") UUID bindingId,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("vehicleId") UUID vehicleId,
            @Param("tagId") UUID tagId,
            @Param("effectiveAt") Instant effectiveAt,
            @Param("reason") String reason,
            @Param("operatorId") UUID operatorId
    );

    /** 建立新的人员标签关系。 */
    @Insert("""
            INSERT INTO mine_person_tag_binding (
                id, organization_id, site_id, person_id, tag_id,
                valid_from, valid_to, reason, created_by, updated_by
            ) VALUES (
                #{bindingId}, #{organizationId}, #{siteId}, #{personId}, #{tagId},
                #{effectiveAt}, #{validTo}, #{reason}, #{operatorId}, #{operatorId}
            )
            """)
    int insertPersonTag(
            @Param("bindingId") UUID bindingId,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("personId") UUID personId,
            @Param("tagId") UUID tagId,
            @Param("effectiveAt") Instant effectiveAt,
            @Param("validTo") Instant validTo,
            @Param("reason") String reason,
            @Param("operatorId") UUID operatorId
    );

    /** 建立新的人员车辆关系。 */
    @Insert("""
            INSERT INTO mine_person_vehicle_binding (
                id, organization_id, site_id, person_id, vehicle_id,
                assignment_role, valid_from, valid_to, reason, created_by, updated_by
            ) VALUES (
                #{bindingId}, #{organizationId}, #{siteId}, #{personId}, #{vehicleId},
                #{role}, #{effectiveAt}, #{validTo}, #{reason}, #{operatorId}, #{operatorId}
            )
            """)
    int insertPersonVehicle(
            @Param("bindingId") UUID bindingId,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("personId") UUID personId,
            @Param("vehicleId") UUID vehicleId,
            @Param("role") String role,
            @Param("effectiveAt") Instant effectiveAt,
            @Param("validTo") Instant validTo,
            @Param("reason") String reason,
            @Param("operatorId") UUID operatorId
    );

    /** 在换绑业务事务内写入待可靠发布事件。 */
    @Insert("""
            INSERT INTO sys_outbox_event (
                id, aggregate_type, aggregate_id, event_type,
                schema_version, payload, headers
            ) VALUES (
                #{eventId}, 'MINE_BINDING', #{operationId}, 'mine.binding.switched',
                1, CAST(#{payloadJson} AS JSONB), '{}'::JSONB
            )
            """)
    int insertOutbox(
            @Param("eventId") UUID eventId,
            @Param("operationId") UUID operationId,
            @Param("payloadJson") String payloadJson
    );

    /** 统一的四类绑定投影。 */
    record BindingRecord(
            UUID bindingId,
            String bindingType,
            UUID organizationId,
            UUID siteId,
            UUID resourceId,
            UUID targetId,
            String role,
            Instant validFrom,
            Instant validTo
    ) {
    }

    /** 已提交换绑幂等操作投影。 */
    record SwitchOperationRecord(
            UUID id,
            UUID organizationId,
            UUID siteId,
            UUID operatorId,
            String idempotencyKey,
            String requestHash,
            String bindingType,
            UUID resourceId,
            UUID targetId,
            String role,
            Instant effectiveAt,
            String reason,
            String previousBindingType,
            UUID previousBindingId,
            UUID newBindingId,
            UUID qualificationId,
            UUID outboxEventId,
            String responsePayload,
            Instant createdAt
    ) {
    }

    /** 执行阶段持锁读取的车辆关键状态。 */
    record LockedVehicleRecord(String status, String vehicleType) {
    }

    /** 命中的司机资质标识和包含式有效结束日期。 */
    record QualificationRecord(UUID id, LocalDate validTo) {
    }

    /** 人员在矿区的发生时任职区间投影。 */
    record PersonAssignmentRecord(UUID id, Instant validFrom, Instant validTo) {
    }
}
