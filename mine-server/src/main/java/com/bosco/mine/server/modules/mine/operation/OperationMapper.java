package com.bosco.mine.server.modules.mine.operation;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 班次、作业单、调度分配和运营事实控制面的统一数据访问接口。
 */
@Mapper
public interface OperationMapper {

    /** 新增矿区班次模板。 */
    @Insert("""
            INSERT INTO mine_shift_definition (
                id, organization_id, site_id, definition_code, name,
                start_local, end_local, crosses_midnight, valid_from, valid_to,
                created_by, updated_by
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{definitionCode}, #{name},
                #{startLocal}, #{endLocal}, #{crossesMidnight}, #{validFrom}, #{validTo},
                #{operatorId}, #{operatorId}
            )
            """)
    int insertShiftDefinition(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("definitionCode") String definitionCode,
            @Param("name") String name,
            @Param("startLocal") LocalTime startLocal,
            @Param("endLocal") LocalTime endLocal,
            @Param("crossesMidnight") boolean crossesMidnight,
            @Param("validFrom") LocalDate validFrom,
            @Param("validTo") LocalDate validTo,
            @Param("operatorId") UUID operatorId);

    /** 按矿区作用域查询班次模板。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   definition_code AS definitionCode, name,
                   start_local AS startLocal, end_local AS endLocal,
                   crosses_midnight AS crossesMidnight,
                   valid_from AS validFrom, valid_to AS validTo, status,
                   created_at AS createdAt, updated_at AS updatedAt,
                   archived_at AS archivedAt, version
            FROM mine_shift_definition
            WHERE id = #{id}
              AND organization_id = #{organizationId}
              AND site_id = #{siteId}
            """)
    Optional<ShiftDefinitionRow> findShiftDefinition(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id);

    /** 锁定并查询当前矿区班次模板。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   definition_code AS definitionCode, name,
                   start_local AS startLocal, end_local AS endLocal,
                   crosses_midnight AS crossesMidnight,
                   valid_from AS validFrom, valid_to AS validTo, status,
                   created_at AS createdAt, updated_at AS updatedAt,
                   archived_at AS archivedAt, version
            FROM mine_shift_definition
            WHERE id = #{id}
              AND organization_id = #{organizationId}
              AND site_id = #{siteId}
            FOR UPDATE
            """)
    Optional<ShiftDefinitionRow> lockShiftDefinition(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id);

    /** 分页查询当前矿区班次模板。 */
    @Select("""
            <script>
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   definition_code AS definitionCode, name,
                   start_local AS startLocal, end_local AS endLocal,
                   crosses_midnight AS crossesMidnight,
                   valid_from AS validFrom, valid_to AS validTo, status,
                   created_at AS createdAt, updated_at AS updatedAt,
                   archived_at AS archivedAt, version
            FROM mine_shift_definition
            WHERE organization_id = #{organizationId} AND site_id = #{siteId}
              <if test="status != null and status != ''">AND status = #{status}</if>
            ORDER BY definition_code, id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<ShiftDefinitionRow> findShiftDefinitions(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /** 使用乐观锁修改活动班次模板。 */
    @Update("""
            UPDATE mine_shift_definition
               SET definition_code = #{definitionCode}, name = #{name},
                   start_local = #{startLocal}, end_local = #{endLocal},
                   crosses_midnight = #{crossesMidnight},
                   valid_from = #{validFrom}, valid_to = #{validTo},
                   updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId},
                   version = version + 1
             WHERE id = #{id}
               AND organization_id = #{organizationId}
               AND site_id = #{siteId}
               AND status = 'ACTIVE' AND version = #{version}
            """)
    int updateShiftDefinition(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("definitionCode") String definitionCode,
            @Param("name") String name,
            @Param("startLocal") LocalTime startLocal,
            @Param("endLocal") LocalTime endLocal,
            @Param("crossesMidnight") boolean crossesMidnight,
            @Param("validFrom") LocalDate validFrom,
            @Param("validTo") LocalDate validTo,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 使用乐观锁归档活动班次模板。 */
    @Update("""
            UPDATE mine_shift_definition
               SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP,
                   updated_at = CURRENT_TIMESTAMP, updated_by = #{operatorId},
                   version = version + 1
             WHERE id = #{id}
               AND organization_id = #{organizationId}
               AND site_id = #{siteId}
               AND status = 'ACTIVE' AND version = #{version}
            """)
    int archiveShiftDefinition(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 新增由服务端计算 UTC 边界的班次实例。 */
    @Insert("""
            INSERT INTO mine_shift_instance (
                id, organization_id, site_id, shift_definition_id,
                business_date, planned_start_at, planned_end_at,
                supervisor_person_id, definition_snapshot, created_by, updated_by
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{definitionId},
                #{businessDate}, #{plannedStartAt}, #{plannedEndAt},
                #{supervisorPersonId}, CAST(#{definitionSnapshotJson} AS JSONB),
                #{operatorId}, #{operatorId}
            )
            """)
    int insertShiftInstance(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("definitionId") UUID definitionId,
            @Param("businessDate") LocalDate businessDate,
            @Param("plannedStartAt") Instant plannedStartAt,
            @Param("plannedEndAt") Instant plannedEndAt,
            @Param("supervisorPersonId") UUID supervisorPersonId,
            @Param("definitionSnapshotJson") String definitionSnapshotJson,
            @Param("operatorId") UUID operatorId);

    /** 按矿区作用域查询班次实例。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   shift_definition_id AS shiftDefinitionId,
                   business_date AS businessDate,
                   planned_start_at AS plannedStartAt,
                   planned_end_at AS plannedEndAt,
                   actual_start_at AS actualStartAt,
                   actual_end_at AS actualEndAt,
                   supervisor_person_id AS supervisorPersonId,
                   definition_snapshot::TEXT AS definitionSnapshotJson,
                   status, opened_at AS openedAt, closed_at AS closedAt,
                   cancelled_at AS cancelledAt,
                   cancellation_reason AS cancellationReason,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM mine_shift_instance
            WHERE id = #{id}
              AND organization_id = #{organizationId}
              AND site_id = #{siteId}
            """)
    Optional<ShiftInstanceRow> findShiftInstance(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id);

    /** 锁定并查询当前矿区班次实例。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   shift_definition_id AS shiftDefinitionId,
                   business_date AS businessDate,
                   planned_start_at AS plannedStartAt,
                   planned_end_at AS plannedEndAt,
                   actual_start_at AS actualStartAt,
                   actual_end_at AS actualEndAt,
                   supervisor_person_id AS supervisorPersonId,
                   definition_snapshot::TEXT AS definitionSnapshotJson,
                   status, opened_at AS openedAt, closed_at AS closedAt,
                   cancelled_at AS cancelledAt,
                   cancellation_reason AS cancellationReason,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM mine_shift_instance
            WHERE id = #{id}
              AND organization_id = #{organizationId}
              AND site_id = #{siteId}
            FOR UPDATE
            """)
    Optional<ShiftInstanceRow> lockShiftInstance(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id);

    /** 分页查询矿区指定营业日期范围内的班次实例。 */
    @Select("""
            <script>
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   shift_definition_id AS shiftDefinitionId,
                   business_date AS businessDate,
                   planned_start_at AS plannedStartAt,
                   planned_end_at AS plannedEndAt,
                   actual_start_at AS actualStartAt,
                   actual_end_at AS actualEndAt,
                   supervisor_person_id AS supervisorPersonId,
                   definition_snapshot::TEXT AS definitionSnapshotJson,
                   status, opened_at AS openedAt, closed_at AS closedAt,
                   cancelled_at AS cancelledAt,
                   cancellation_reason AS cancellationReason,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM mine_shift_instance
            WHERE organization_id = #{organizationId} AND site_id = #{siteId}
              AND business_date BETWEEN #{fromDate} AND #{toDate}
              <if test="status != null and status != ''">AND status = #{status}</if>
            ORDER BY business_date DESC, planned_start_at, id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<ShiftInstanceRow> findShiftInstances(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /** 开启计划班次。 */
    @Update("""
            UPDATE mine_shift_instance
               SET status = 'OPEN', actual_start_at = #{actionAt},
                   opened_at = #{actionAt}, opened_by = #{operatorId},
                   updated_at = #{actionAt}, updated_by = #{operatorId},
                   version = version + 1
             WHERE id = #{id}
               AND organization_id = #{organizationId}
               AND site_id = #{siteId} AND status = 'PLANNED'
               AND version = #{version}
            """)
    int openShiftInstance(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("actionAt") Instant actionAt,
            @Param("operatorId") UUID operatorId);

    /** 关闭已开启班次。 */
    @Update("""
            UPDATE mine_shift_instance
               SET status = 'CLOSED', actual_end_at = #{actionAt},
                   closed_at = #{actionAt}, closed_by = #{operatorId},
                   updated_at = #{actionAt}, updated_by = #{operatorId},
                   version = version + 1
             WHERE id = #{id}
               AND organization_id = #{organizationId}
               AND site_id = #{siteId} AND status = 'OPEN'
               AND version = #{version}
            """)
    int closeShiftInstance(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("actionAt") Instant actionAt,
            @Param("operatorId") UUID operatorId);

    /** 取消尚未开启的班次。 */
    @Update("""
            UPDATE mine_shift_instance
               SET status = 'CANCELLED', cancelled_at = #{actionAt},
                   cancelled_by = #{operatorId}, cancellation_reason = #{reason},
                   updated_at = #{actionAt}, updated_by = #{operatorId},
                   version = version + 1
             WHERE id = #{id}
               AND organization_id = #{organizationId}
               AND site_id = #{siteId} AND status = 'PLANNED'
               AND version = #{version}
            """)
    int cancelShiftInstance(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("actionAt") Instant actionAt,
            @Param("reason") String reason,
            @Param("operatorId") UUID operatorId);

    /** 统计阻止班次关账的运行作业和循环状态。 */
    @Select("""
            SELECT
                (SELECT COUNT(*) FROM mine_work_order work_order
                 WHERE work_order.shift_instance_id = #{shiftId}
                   AND work_order.organization_id = #{organizationId}
                   AND work_order.site_id = #{siteId}
                   AND work_order.status IN ('RUNNING', 'PAUSED'))
                +
                (SELECT COUNT(*)
                 FROM mine_operation_runtime_state runtime_state
                 JOIN mine_work_order work_order
                   ON work_order.id = runtime_state.work_order_id
                  AND work_order.organization_id = runtime_state.organization_id
                  AND work_order.site_id = runtime_state.site_id
                 WHERE work_order.shift_instance_id = #{shiftId}
                   AND runtime_state.organization_id = #{organizationId}
                   AND runtime_state.site_id = #{siteId})
            """)
    long countShiftCloseBlockers(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("shiftId") UUID shiftId);

    /** 新增运输作业单草稿。 */
    @Insert("""
            INSERT INTO mine_work_order (
                id, organization_id, site_id, shift_instance_id,
                order_number, title, work_type, planned_start_at, planned_end_at,
                planned_cycles, planned_tons, priority, created_by, updated_by
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{shiftInstanceId},
                #{orderNumber}, #{title}, #{workType}, #{plannedStartAt}, #{plannedEndAt},
                #{plannedCycles}, #{plannedTons}, #{priority}, #{operatorId}, #{operatorId}
            )
            """)
    int insertWorkOrder(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("shiftInstanceId") UUID shiftInstanceId,
            @Param("orderNumber") String orderNumber,
            @Param("title") String title,
            @Param("workType") String workType,
            @Param("plannedStartAt") Instant plannedStartAt,
            @Param("plannedEndAt") Instant plannedEndAt,
            @Param("plannedCycles") Integer plannedCycles,
            @Param("plannedTons") BigDecimal plannedTons,
            @Param("priority") int priority,
            @Param("operatorId") UUID operatorId);

    /** 按矿区作用域查询运输作业单。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   shift_instance_id AS shiftInstanceId, order_number AS orderNumber,
                   title, work_type AS workType, planned_start_at AS plannedStartAt,
                   planned_end_at AS plannedEndAt, planned_cycles AS plannedCycles,
                   planned_tons AS plannedTons, priority,
                   route_version_id AS routeVersionId,
                   load_geofence_version_id AS loadGeofenceVersionId,
                   unload_geofence_version_id AS unloadGeofenceVersionId,
                   release_snapshot::TEXT AS releaseSnapshotJson, status,
                   released_at AS releasedAt, started_at AS startedAt,
                   paused_at AS pausedAt, completed_at AS completedAt,
                   cancelled_at AS cancelledAt, aborted_at AS abortedAt,
                   terminal_reason AS terminalReason,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM mine_work_order
            WHERE id = #{id}
              AND organization_id = #{organizationId}
              AND site_id = #{siteId}
            """)
    Optional<WorkOrderRow> findWorkOrder(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id);

    /** 锁定并查询运输作业单聚合。 */
    @Select("""
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   shift_instance_id AS shiftInstanceId, order_number AS orderNumber,
                   title, work_type AS workType, planned_start_at AS plannedStartAt,
                   planned_end_at AS plannedEndAt, planned_cycles AS plannedCycles,
                   planned_tons AS plannedTons, priority,
                   route_version_id AS routeVersionId,
                   load_geofence_version_id AS loadGeofenceVersionId,
                   unload_geofence_version_id AS unloadGeofenceVersionId,
                   release_snapshot::TEXT AS releaseSnapshotJson, status,
                   released_at AS releasedAt, started_at AS startedAt,
                   paused_at AS pausedAt, completed_at AS completedAt,
                   cancelled_at AS cancelledAt, aborted_at AS abortedAt,
                   terminal_reason AS terminalReason,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM mine_work_order
            WHERE id = #{id}
              AND organization_id = #{organizationId}
              AND site_id = #{siteId}
            FOR UPDATE
            """)
    Optional<WorkOrderRow> lockWorkOrder(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id);

    /** 分页查询矿区作业单。 */
    @Select("""
            <script>
            SELECT id, organization_id AS organizationId, site_id AS siteId,
                   shift_instance_id AS shiftInstanceId, order_number AS orderNumber,
                   title, work_type AS workType, planned_start_at AS plannedStartAt,
                   planned_end_at AS plannedEndAt, planned_cycles AS plannedCycles,
                   planned_tons AS plannedTons, priority,
                   route_version_id AS routeVersionId,
                   load_geofence_version_id AS loadGeofenceVersionId,
                   unload_geofence_version_id AS unloadGeofenceVersionId,
                   release_snapshot::TEXT AS releaseSnapshotJson, status,
                   released_at AS releasedAt, started_at AS startedAt,
                   paused_at AS pausedAt, completed_at AS completedAt,
                   cancelled_at AS cancelledAt, aborted_at AS abortedAt,
                   terminal_reason AS terminalReason,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM mine_work_order
            WHERE organization_id = #{organizationId} AND site_id = #{siteId}
              <if test="shiftInstanceId != null">
                AND shift_instance_id = #{shiftInstanceId}
              </if>
              <if test="status != null and status != ''">AND status = #{status}</if>
            ORDER BY planned_start_at DESC, priority DESC, id
            OFFSET #{offset} LIMIT #{limit}
            </script>
            """)
    List<WorkOrderRow> findWorkOrders(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("shiftInstanceId") UUID shiftInstanceId,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /** 使用乐观锁修改作业单草稿。 */
    @Update("""
            UPDATE mine_work_order
               SET shift_instance_id = #{shiftInstanceId}, order_number = #{orderNumber},
                   title = #{title}, work_type = #{workType},
                   planned_start_at = #{plannedStartAt}, planned_end_at = #{plannedEndAt},
                   planned_cycles = #{plannedCycles}, planned_tons = #{plannedTons},
                   priority = #{priority}, updated_at = CURRENT_TIMESTAMP,
                   updated_by = #{operatorId}, version = version + 1
             WHERE id = #{id}
               AND organization_id = #{organizationId}
               AND site_id = #{siteId}
               AND status = 'DRAFT' AND version = #{version}
            """)
    int updateWorkOrder(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("shiftInstanceId") UUID shiftInstanceId,
            @Param("orderNumber") String orderNumber,
            @Param("title") String title,
            @Param("workType") String workType,
            @Param("plannedStartAt") Instant plannedStartAt,
            @Param("plannedEndAt") Instant plannedEndAt,
            @Param("plannedCycles") Integer plannedCycles,
            @Param("plannedTons") BigDecimal plannedTons,
            @Param("priority") int priority,
            @Param("version") long version,
            @Param("operatorId") UUID operatorId);

    /** 查询作业单全部调度分配事实及显示字段。 */
    @Select("""
            SELECT assignment.id, assignment.organization_id AS organizationId,
                   assignment.site_id AS siteId,
                   assignment.work_order_id AS workOrderId,
                   assignment.vehicle_id AS vehicleId,
                   vehicle.vehicle_code AS vehicleCode, vehicle.name AS vehicleName,
                   vehicle.vehicle_type AS vehicleType,
                   assignment.driver_person_id AS driverPersonId,
                   person.name AS driverName,
                   assignment.valid_from AS validFrom, assignment.valid_to AS validTo,
                   assignment.status, assignment.assignment_reason AS assignmentReason,
                   assignment.created_at AS createdAt, assignment.version
            FROM mine_work_order_assignment assignment
            JOIN mine_vehicle vehicle
              ON vehicle.id = assignment.vehicle_id
             AND vehicle.organization_id = assignment.organization_id
             AND vehicle.site_id = assignment.site_id
            LEFT JOIN mine_person person
              ON person.id = assignment.driver_person_id
             AND person.organization_id = assignment.organization_id
            WHERE assignment.work_order_id = #{workOrderId}
              AND assignment.organization_id = #{organizationId}
              AND assignment.site_id = #{siteId}
            ORDER BY assignment.valid_from, assignment.id
            """)
    List<WorkOrderAssignmentRow> findWorkOrderAssignments(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("workOrderId") UUID workOrderId);

    /** 按矿区作用域查询可调度车辆。 */
    @Select("""
            SELECT id, vehicle_code AS vehicleCode, name,
                   vehicle_type AS vehicleType, status
            FROM mine_vehicle
            WHERE id = #{vehicleId}
              AND organization_id = #{organizationId}
              AND site_id = #{siteId}
            FOR UPDATE
            """)
    Optional<DispatchVehicleRow> lockDispatchVehicle(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("vehicleId") UUID vehicleId);

    /** 判断司机在完整分配区间内具备矿区任职和对应车型资质。 */
    @Select("""
            SELECT EXISTS (
                SELECT 1
                FROM mine_person person
                JOIN mine_person_site_assignment site_assignment
                  ON site_assignment.person_id = person.id
                 AND site_assignment.organization_id = person.organization_id
                 AND site_assignment.site_id = #{siteId}
                 AND site_assignment.valid_period @>
                     tstzrange(#{validFrom}, #{validTo}, '[)')
                JOIN mine_driver_qualification qualification
                  ON qualification.person_id = person.id
                 AND qualification.organization_id = person.organization_id
                 AND qualification.status = 'ACTIVE'
                 AND qualification.valid_from <= #{fromDate}
                 AND qualification.valid_to >= #{toDate}
                 AND jsonb_exists(qualification.allowed_vehicle_types, #{vehicleType})
                WHERE person.id = #{personId}
                  AND person.organization_id = #{organizationId}
                  AND person.status = 'ACTIVE'
                  AND person.person_type = 'DRIVER'
            )
            """)
    boolean isDriverEligible(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("personId") UUID personId,
            @Param("vehicleType") String vehicleType,
            @Param("validFrom") Instant validFrom,
            @Param("validTo") Instant validTo,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /** 追加一条调度分配事实。 */
    @Insert("""
            INSERT INTO mine_work_order_assignment (
                id, organization_id, site_id, work_order_id,
                vehicle_id, driver_person_id, valid_from, valid_to,
                assignment_reason, created_by, updated_by
            ) VALUES (
                #{id}, #{organizationId}, #{siteId}, #{workOrderId},
                #{vehicleId}, #{driverPersonId}, #{validFrom}, #{validTo},
                #{assignmentReason}, #{operatorId}, #{operatorId}
            )
            """)
    int insertWorkOrderAssignment(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("workOrderId") UUID workOrderId,
            @Param("vehicleId") UUID vehicleId,
            @Param("driverPersonId") UUID driverPersonId,
            @Param("validFrom") Instant validFrom,
            @Param("validTo") Instant validTo,
            @Param("assignmentReason") String assignmentReason,
            @Param("operatorId") UUID operatorId);

    /** 查询发布快照使用的活动已发布路线版本。 */
    @Select("""
            SELECT version.id, route.route_code AS objectCode, version.name,
                   version.status, route.status AS parentStatus,
                   version.valid_from AS validFrom, version.valid_to AS validTo,
                   version.content_hash AS contentHash,
                   jsonb_exists(version.applicable_vehicle_types, 'HAUL_TRUCK') AS vehicleCompatible,
                   CAST(NULL AS VARCHAR) AS geofenceType
            FROM mine_route_version version
            JOIN mine_route route
              ON route.id = version.route_id
             AND route.organization_id = version.organization_id
             AND route.site_id = version.site_id
            WHERE version.id = #{versionId}
              AND version.organization_id = #{organizationId}
              AND version.site_id = #{siteId}
            """)
    Optional<SpatialReleaseRow> findReleaseRoute(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("versionId") UUID versionId);

    /** 查询发布快照使用的活动已发布电子围栏版本。 */
    @Select("""
            SELECT version.id, geofence.geofence_code AS objectCode, version.name,
                   version.status, geofence.status AS parentStatus,
                   version.valid_from AS validFrom, version.valid_to AS validTo,
                   version.content_hash AS contentHash,
                   TRUE AS vehicleCompatible,
                   version.geofence_type AS geofenceType
            FROM mine_geofence_version version
            JOIN mine_geofence geofence
              ON geofence.id = version.geofence_id
             AND geofence.organization_id = version.organization_id
             AND geofence.site_id = version.site_id
            WHERE version.id = #{versionId}
              AND version.organization_id = #{organizationId}
              AND version.site_id = #{siteId}
            """)
    Optional<SpatialReleaseRow> findReleaseGeofence(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("versionId") UUID versionId);

    /** 发布并冻结作业单计划、空间版本和调度摘要。 */
    @Update("""
            UPDATE mine_work_order
               SET route_version_id = #{routeVersionId},
                   load_geofence_version_id = #{loadGeofenceVersionId},
                   unload_geofence_version_id = #{unloadGeofenceVersionId},
                   release_snapshot = CAST(#{releaseSnapshotJson} AS JSONB),
                   status = 'RELEASED', released_at = #{actionAt},
                   released_by = #{operatorId}, updated_at = #{actionAt},
                   updated_by = #{operatorId}, version = version + 1
             WHERE id = #{id}
               AND organization_id = #{organizationId}
               AND site_id = #{siteId} AND status = 'DRAFT'
               AND version = #{version}
            """)
    int releaseWorkOrder(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("routeVersionId") UUID routeVersionId,
            @Param("loadGeofenceVersionId") UUID loadGeofenceVersionId,
            @Param("unloadGeofenceVersionId") UUID unloadGeofenceVersionId,
            @Param("releaseSnapshotJson") String releaseSnapshotJson,
            @Param("actionAt") Instant actionAt,
            @Param("operatorId") UUID operatorId);

    /** 启动已发布作业单。 */
    @Update("""
            UPDATE mine_work_order
               SET status = 'RUNNING', started_at = #{actionAt}, started_by = #{operatorId},
                   updated_at = #{actionAt}, updated_by = #{operatorId}, version = version + 1
             WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status = 'RELEASED' AND version = #{version}
            """)
    int startWorkOrder(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("actionAt") Instant actionAt,
            @Param("operatorId") UUID operatorId);

    /** 暂停运行作业单。 */
    @Update("""
            UPDATE mine_work_order
               SET status = 'PAUSED', paused_at = #{actionAt}, paused_by = #{operatorId},
                   updated_at = #{actionAt}, updated_by = #{operatorId}, version = version + 1
             WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status = 'RUNNING' AND version = #{version}
            """)
    int pauseWorkOrder(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("actionAt") Instant actionAt,
            @Param("operatorId") UUID operatorId);

    /** 恢复暂停作业单。 */
    @Update("""
            UPDATE mine_work_order
               SET status = 'RUNNING', updated_at = #{actionAt},
                   updated_by = #{operatorId}, version = version + 1
             WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status = 'PAUSED' AND version = #{version}
            """)
    int resumeWorkOrder(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("actionAt") Instant actionAt,
            @Param("operatorId") UUID operatorId);

    /** 完成运行或暂停作业单。 */
    @Update("""
            UPDATE mine_work_order
               SET status = 'COMPLETED', completed_at = #{actionAt},
                   completed_by = #{operatorId}, updated_at = #{actionAt},
                   updated_by = #{operatorId}, version = version + 1
             WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status IN ('RUNNING', 'PAUSED') AND version = #{version}
            """)
    int completeWorkOrder(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("actionAt") Instant actionAt,
            @Param("operatorId") UUID operatorId);

    /** 取消未启动作业单。 */
    @Update("""
            UPDATE mine_work_order
               SET status = 'CANCELLED', cancelled_at = #{actionAt},
                   cancelled_by = #{operatorId}, terminal_reason = #{reason},
                   updated_at = #{actionAt}, updated_by = #{operatorId}, version = version + 1
             WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status IN ('DRAFT', 'RELEASED') AND version = #{version}
            """)
    int cancelWorkOrder(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("actionAt") Instant actionAt,
            @Param("reason") String reason,
            @Param("operatorId") UUID operatorId);

    /** 异常终止运行或暂停作业单。 */
    @Update("""
            UPDATE mine_work_order
               SET status = 'ABORTED', aborted_at = #{actionAt}, aborted_by = #{operatorId},
                   terminal_reason = #{reason}, updated_at = #{actionAt},
                   updated_by = #{operatorId}, version = version + 1
             WHERE id = #{id} AND organization_id = #{organizationId} AND site_id = #{siteId}
               AND status IN ('RUNNING', 'PAUSED') AND version = #{version}
            """)
    int abortWorkOrder(
            @Param("organizationId") UUID organizationId,
            @Param("siteId") UUID siteId,
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("actionAt") Instant actionAt,
            @Param("reason") String reason,
            @Param("operatorId") UUID operatorId);

    /** 运输作业单持久化投影。 */
    record WorkOrderRow(
            UUID id,
            UUID organizationId,
            UUID siteId,
            UUID shiftInstanceId,
            String orderNumber,
            String title,
            String workType,
            Instant plannedStartAt,
            Instant plannedEndAt,
            Integer plannedCycles,
            BigDecimal plannedTons,
            int priority,
            UUID routeVersionId,
            UUID loadGeofenceVersionId,
            UUID unloadGeofenceVersionId,
            String releaseSnapshotJson,
            String status,
            Instant releasedAt,
            Instant startedAt,
            Instant pausedAt,
            Instant completedAt,
            Instant cancelledAt,
            Instant abortedAt,
            String terminalReason,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
    }

    /** 作业单调度分配持久化投影。 */
    record WorkOrderAssignmentRow(
            UUID id,
            UUID organizationId,
            UUID siteId,
            UUID workOrderId,
            UUID vehicleId,
            String vehicleCode,
            String vehicleName,
            String vehicleType,
            UUID driverPersonId,
            String driverName,
            Instant validFrom,
            Instant validTo,
            String status,
            String assignmentReason,
            Instant createdAt,
            long version
    ) {
    }

    /** 调度车辆身份和车型投影。 */
    record DispatchVehicleRow(
            UUID id,
            String vehicleCode,
            String name,
            String vehicleType,
            String status
    ) {
    }

    /** 发布时空间版本的规范化显示和有效期投影。 */
    record SpatialReleaseRow(
            UUID id,
            String objectCode,
            String name,
            String status,
            String parentStatus,
            Instant validFrom,
            Instant validTo,
            String contentHash,
            boolean vehicleCompatible,
            String geofenceType
    ) {
    }

    /** 班次模板持久化投影。 */
    record ShiftDefinitionRow(
            UUID id,
            UUID organizationId,
            UUID siteId,
            String definitionCode,
            String name,
            LocalTime startLocal,
            LocalTime endLocal,
            boolean crossesMidnight,
            LocalDate validFrom,
            LocalDate validTo,
            String status,
            Instant createdAt,
            Instant updatedAt,
            Instant archivedAt,
            long version
    ) {
    }

    /** 班次实例持久化投影。 */
    record ShiftInstanceRow(
            UUID id,
            UUID organizationId,
            UUID siteId,
            UUID shiftDefinitionId,
            LocalDate businessDate,
            Instant plannedStartAt,
            Instant plannedEndAt,
            Instant actualStartAt,
            Instant actualEndAt,
            UUID supervisorPersonId,
            String definitionSnapshotJson,
            String status,
            Instant openedAt,
            Instant closedAt,
            Instant cancelledAt,
            String cancellationReason,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {
    }
}
