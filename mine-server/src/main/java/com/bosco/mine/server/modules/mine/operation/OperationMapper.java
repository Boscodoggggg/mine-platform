package com.bosco.mine.server.modules.mine.operation;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
