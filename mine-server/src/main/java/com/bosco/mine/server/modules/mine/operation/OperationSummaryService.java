package com.bosco.mine.server.modules.mine.operation;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.operation.dto.OperationRecordPageResponse;
import com.bosco.mine.server.modules.mine.operation.dto.OperationSummaryResponse;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 按矿区营业日期提供有界运营汇总和固定水位执行记录分页。
 */
@Service
public class OperationSummaryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationSummaryService.class);
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DETAIL_LIMIT = 500;
    private static final Set<String> RECORD_STATUSES = Set.of("COMPLETED", "ABORTED");
    private static final ErrorCode RANGE_INVALID = new ErrorCode(
            "MINE-OPERATION-422-003", "运营查询营业日期范围不合法，最多包含三十一天");
    private static final ErrorCode PAGE_INVALID = new ErrorCode(
            "MINE-OPERATION-422-004", "运营记录分页水位、游标或筛选条件不合法");
    private static final ErrorCode QUERY_UNAVAILABLE = new ErrorCode(
            "MINE-OPERATION-503-001", "运营统计查询暂时不可用");

    private final JdbcTemplate jdbcTemplate;
    private final MineScopeAuthorizer authorizer;
    private final ObjectMapper objectMapper;

    /**
     * 创建运营汇总查询服务。
     *
     * @param jdbcTemplate 业务数据库访问模板
     * @param authorizer 矿区作用域授权器
     * @param objectMapper 游标 JSON 映射器
     */
    public OperationSummaryService(
            JdbcTemplate jdbcTemplate,
            MineScopeAuthorizer authorizer,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JDBC 模板不能为空");
        this.authorizer = Objects.requireNonNull(authorizer, "矿区授权器不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 映射器不能为空");
    }

    /**
     * 查询明确营业日期窗口的计划、有效产量、阶段时长和人员车辆维度汇总。
     *
     * @param siteId 矿区标识
     * @param fromDate 起始营业日期，包含
     * @param toDate 结束营业日期，包含
     * @return 运营汇总
     */
    @Transactional(readOnly = true)
    public OperationSummaryResponse summarize(
            UUID siteId,
            LocalDate fromDate,
            LocalDate toDate
    ) {
        MineSite site = authorizer.requireSite(siteId);
        validateDateRange(fromDate, toDate);
        try {
            PlanAggregate plan = queryPlan(site, fromDate, toDate);
            RecordAggregate records = queryRecordAggregate(site, fromDate, toDate);
            List<OperationSummaryResponse.WorkOrderProgress> workOrderRows =
                    queryWorkOrders(site, fromDate, toDate);
            List<OperationSummaryResponse.VehicleUtilization> vehicleRows =
                    queryVehicles(site, fromDate, toDate);
            List<OperationSummaryResponse.DriverWorkload> driverRows =
                    queryDrivers(site, fromDate, toDate);
            boolean truncated = workOrderRows.size() > DETAIL_LIMIT
                    || vehicleRows.size() > DETAIL_LIMIT
                    || driverRows.size() > DETAIL_LIMIT;
            return new OperationSummaryResponse(
                    site.id(), fromDate, toDate, plan.workOrderCount(), plan.plannedCycles(),
                    records.completedCycles(), records.anomalyCycles(),
                    plan.allCyclesPlanned()
                            ? percentage(records.completedCycles(), plan.plannedCycles()) : null,
                    plan.plannedTons(), records.actualTons(),
                    records.knownTonsCycles(), records.unknownTonsCycles(),
                    records.distanceMeters(), records.totalCycleSeconds(),
                    records.averageCycleSeconds(), records.averageLoadingSeconds(),
                    records.averageHaulingSeconds(), records.averageUnloadingSeconds(),
                    truncated, trim(workOrderRows), trim(vehicleRows), trim(driverRows));
        } catch (MineException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.error("运营汇总查询失败: siteId={}, fromDate={}, toDate={}",
                    siteId, fromDate, toDate, exception);
            throw new MineException(QUERY_UNAVAILABLE, Map.of("siteId", siteId));
        }
    }

    /**
     * 使用矿区业务序号固定水位查询最新有效执行记录投影。
     *
     * @param siteId 矿区标识
     * @param fromDate 起始营业日期，包含
     * @param toDate 结束营业日期，包含
     * @param watermark 首页返回的固定矿区业务水位
     * @param cursor 下一页不透明游标
     * @param shiftInstanceId 可选班次实例标识
     * @param workOrderId 可选作业单标识
     * @param vehicleId 可选车辆标识
     * @param driverPersonId 可选司机人员标识
     * @param recordStatus 可选执行结果状态
     * @param pageSize 每页数量，最大二百
     * @return 固定水位记录页
     */
    @Transactional(readOnly = true)
    public OperationRecordPageResponse findRecords(
            UUID siteId,
            LocalDate fromDate,
            LocalDate toDate,
            Long watermark,
            String cursor,
            UUID shiftInstanceId,
            UUID workOrderId,
            UUID vehicleId,
            UUID driverPersonId,
            String recordStatus,
            int pageSize
    ) {
        MineSite site = authorizer.requireSite(siteId);
        validateDateRange(fromDate, toDate);
        String normalizedStatus = normalizeRecordStatus(recordStatus);
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE
                || (cursor != null && (cursor.isBlank() || watermark == null))) {
            throw pageInvalid("PAGE_ARGUMENT_INVALID");
        }
        try {
            long currentWatermark = currentWatermark(site);
            long resolvedWatermark = watermark == null ? currentWatermark : watermark;
            if (resolvedWatermark < 0L || resolvedWatermark > currentWatermark) {
                throw pageInvalid("WATERMARK_INVALID");
            }
            QueryBinding binding = new QueryBinding(
                    site.organizationId(), site.id(), fromDate, toDate,
                    resolvedWatermark, shiftInstanceId, workOrderId,
                    vehicleId, driverPersonId, normalizedStatus);
            CursorState state = cursor == null ? null : decodeCursor(cursor);
            if (state != null && !state.matches(binding)) {
                throw pageInvalid("CURSOR_QUERY_MISMATCH");
            }
            List<OperationRecordPageResponse.Item> rows = queryRecordPage(
                    binding, state, pageSize + 1);
            boolean hasMore = rows.size() > pageSize;
            List<OperationRecordPageResponse.Item> items = hasMore
                    ? List.copyOf(rows.subList(0, pageSize)) : List.copyOf(rows);
            String nextCursor = hasMore
                    ? encodeCursor(binding, items.getLast()) : null;
            return new OperationRecordPageResponse(
                    site.id(), fromDate, toDate, resolvedWatermark, nextCursor, items);
        } catch (MineException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.error("运营记录分页查询失败: siteId={}, fromDate={}, toDate={}",
                    siteId, fromDate, toDate, exception);
            throw new MineException(QUERY_UNAVAILABLE, Map.of("siteId", siteId));
        }
    }

    private PlanAggregate queryPlan(MineSite site, LocalDate fromDate, LocalDate toDate) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS work_order_count,
                       SUM(work_order.planned_cycles) AS planned_cycles,
                       SUM(work_order.planned_tons) AS planned_tons,
                       COUNT(work_order.planned_cycles) = COUNT(*) AS all_cycles_planned
                FROM mine_work_order work_order
                JOIN mine_shift_instance shift_instance
                  ON shift_instance.id = work_order.shift_instance_id
                 AND shift_instance.organization_id = work_order.organization_id
                 AND shift_instance.site_id = work_order.site_id
                WHERE work_order.organization_id = ? AND work_order.site_id = ?
                  AND shift_instance.business_date BETWEEN ? AND ?
                  AND work_order.status <> 'CANCELLED'
                """, (resultSet, rowNumber) -> new PlanAggregate(
                Math.toIntExact(resultSet.getLong("work_order_count")),
                nullableLong(resultSet, "planned_cycles"),
                resultSet.getBigDecimal("planned_tons"),
                resultSet.getBoolean("all_cycles_planned")),
                site.organizationId(), site.id(), fromDate, toDate);
    }

    private RecordAggregate queryRecordAggregate(
            MineSite site,
            LocalDate fromDate,
            LocalDate toDate
    ) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FILTER (
                           WHERE effective.record_status = 'COMPLETED'
                       ) AS completed_cycles,
                       COUNT(*) FILTER (
                           WHERE effective.record_status = 'ABORTED'
                       ) AS anomaly_cycles,
                       SUM(effective.actual_tons) FILTER (
                           WHERE effective.record_status = 'COMPLETED'
                       ) AS actual_tons,
                       COUNT(*) FILTER (
                           WHERE effective.record_status = 'COMPLETED'
                             AND effective.actual_tons IS NOT NULL
                       ) AS known_tons_cycles,
                       COUNT(*) FILTER (
                           WHERE effective.record_status = 'COMPLETED'
                             AND effective.actual_tons IS NULL
                       ) AS unknown_tons_cycles,
                       SUM(effective.distance_meters) FILTER (
                           WHERE effective.record_status = 'COMPLETED'
                       ) AS distance_meters,
                       COALESCE(SUM(EXTRACT(EPOCH FROM (
                           effective.completed_at - effective.started_at
                       ))) FILTER (
                           WHERE effective.record_status = 'COMPLETED'
                       ), 0) AS total_cycle_seconds,
                       ROUND(AVG(EXTRACT(EPOCH FROM (
                           effective.completed_at - effective.started_at
                       ))) FILTER (
                           WHERE effective.record_status = 'COMPLETED'
                       )::NUMERIC, 3) AS average_cycle_seconds,
                       ROUND(AVG(EXTRACT(EPOCH FROM (
                           base.load_completed_at - base.started_at
                       ))) FILTER (
                           WHERE effective.record_status = 'COMPLETED'
                             AND base.load_completed_at IS NOT NULL
                       )::NUMERIC, 3) AS average_loading_seconds,
                       ROUND(AVG(EXTRACT(EPOCH FROM (
                           base.unloading_started_at - base.load_completed_at
                       ))) FILTER (
                           WHERE effective.record_status = 'COMPLETED'
                             AND base.load_completed_at IS NOT NULL
                             AND base.unloading_started_at IS NOT NULL
                       )::NUMERIC, 3) AS average_hauling_seconds,
                       ROUND(AVG(EXTRACT(EPOCH FROM (
                           base.completed_at - base.unloading_started_at
                       ))) FILTER (
                           WHERE effective.record_status = 'COMPLETED'
                             AND base.unloading_started_at IS NOT NULL
                       )::NUMERIC, 3) AS average_unloading_seconds
                FROM mine_operation_effective_record effective
                JOIN mine_operation_record base
                  ON base.id = effective.operation_record_id
                 AND base.organization_id = effective.organization_id
                 AND base.site_id = effective.site_id
                JOIN mine_shift_instance shift_instance
                  ON shift_instance.id = effective.shift_instance_id
                 AND shift_instance.organization_id = effective.organization_id
                 AND shift_instance.site_id = effective.site_id
                WHERE effective.organization_id = ? AND effective.site_id = ?
                  AND shift_instance.business_date BETWEEN ? AND ?
                  AND effective.effective
                """, (resultSet, rowNumber) -> new RecordAggregate(
                resultSet.getLong("completed_cycles"),
                resultSet.getLong("anomaly_cycles"),
                resultSet.getBigDecimal("actual_tons"),
                resultSet.getLong("known_tons_cycles"),
                resultSet.getLong("unknown_tons_cycles"),
                resultSet.getBigDecimal("distance_meters"),
                decimalLong(resultSet.getBigDecimal("total_cycle_seconds")),
                resultSet.getBigDecimal("average_cycle_seconds"),
                resultSet.getBigDecimal("average_loading_seconds"),
                resultSet.getBigDecimal("average_hauling_seconds"),
                resultSet.getBigDecimal("average_unloading_seconds")),
                site.organizationId(), site.id(), fromDate, toDate);
    }

    private List<OperationSummaryResponse.WorkOrderProgress> queryWorkOrders(
            MineSite site,
            LocalDate fromDate,
            LocalDate toDate
    ) {
        return jdbcTemplate.query("""
                SELECT work_order.id, work_order.order_number, work_order.title,
                       work_order.planned_cycles, work_order.planned_tons,
                       COUNT(effective.operation_record_id) FILTER (
                           WHERE effective.record_status = 'COMPLETED'
                       ) AS completed_cycles,
                       COUNT(effective.operation_record_id) FILTER (
                           WHERE effective.record_status = 'ABORTED'
                       ) AS anomaly_cycles,
                       SUM(effective.actual_tons) FILTER (
                           WHERE effective.record_status = 'COMPLETED'
                       ) AS actual_tons,
                       COUNT(effective.operation_record_id) FILTER (
                           WHERE effective.record_status = 'COMPLETED'
                             AND effective.actual_tons IS NOT NULL
                       ) AS known_tons_cycles,
                       COUNT(effective.operation_record_id) FILTER (
                           WHERE effective.record_status = 'COMPLETED'
                             AND effective.actual_tons IS NULL
                       ) AS unknown_tons_cycles
                FROM mine_work_order work_order
                JOIN mine_shift_instance shift_instance
                  ON shift_instance.id = work_order.shift_instance_id
                 AND shift_instance.organization_id = work_order.organization_id
                 AND shift_instance.site_id = work_order.site_id
                LEFT JOIN mine_operation_effective_record effective
                  ON effective.work_order_id = work_order.id
                 AND effective.organization_id = work_order.organization_id
                 AND effective.site_id = work_order.site_id
                 AND effective.effective
                WHERE work_order.organization_id = ? AND work_order.site_id = ?
                  AND shift_instance.business_date BETWEEN ? AND ?
                  AND work_order.status <> 'CANCELLED'
                GROUP BY work_order.id, work_order.order_number, work_order.title,
                         work_order.planned_cycles, work_order.planned_tons,
                         shift_instance.business_date, work_order.priority
                ORDER BY shift_instance.business_date DESC,
                         work_order.priority DESC, work_order.id
                LIMIT ?
                """, (resultSet, rowNumber) -> {
                    Integer plannedCycles = resultSet.getObject("planned_cycles", Integer.class);
                    long completedCycles = resultSet.getLong("completed_cycles");
                    return new OperationSummaryResponse.WorkOrderProgress(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getString("order_number"),
                            resultSet.getString("title"), plannedCycles,
                            resultSet.getBigDecimal("planned_tons"), completedCycles,
                            resultSet.getLong("anomaly_cycles"),
                            percentage(completedCycles, plannedCycles),
                            resultSet.getBigDecimal("actual_tons"),
                            resultSet.getLong("known_tons_cycles"),
                            resultSet.getLong("unknown_tons_cycles"));
                }, site.organizationId(), site.id(), fromDate, toDate, DETAIL_LIMIT + 1);
    }

    private List<OperationSummaryResponse.VehicleUtilization> queryVehicles(
            MineSite site,
            LocalDate fromDate,
            LocalDate toDate
    ) {
        return jdbcTemplate.query("""
                WITH assigned_shift AS (
                    SELECT DISTINCT assignment.vehicle_id,
                           shift_instance.id AS shift_instance_id,
                           shift_instance.actual_start_at,
                           COALESCE(shift_instance.actual_end_at, CURRENT_TIMESTAMP)
                               AS effective_end_at
                    FROM mine_work_order_assignment assignment
                    JOIN mine_work_order work_order
                      ON work_order.id = assignment.work_order_id
                     AND work_order.organization_id = assignment.organization_id
                     AND work_order.site_id = assignment.site_id
                    JOIN mine_shift_instance shift_instance
                      ON shift_instance.id = work_order.shift_instance_id
                     AND shift_instance.organization_id = work_order.organization_id
                     AND shift_instance.site_id = work_order.site_id
                    WHERE assignment.organization_id = ? AND assignment.site_id = ?
                      AND shift_instance.business_date BETWEEN ? AND ?
                      AND work_order.status <> 'CANCELLED'
                      AND assignment.status <> 'CANCELLED'
                      AND shift_instance.actual_start_at IS NOT NULL
                ), assigned AS (
                    SELECT vehicle_id,
                           SUM(GREATEST(EXTRACT(EPOCH FROM (
                               effective_end_at - actual_start_at
                           )), 0)) AS shift_open_seconds
                    FROM assigned_shift
                    GROUP BY vehicle_id
                ), actual AS (
                    SELECT effective.vehicle_id,
                           COUNT(*) AS effective_cycles,
                           COUNT(*) FILTER (
                               WHERE effective.record_status = 'COMPLETED'
                           ) AS completed_cycles,
                           COUNT(*) FILTER (
                               WHERE effective.record_status = 'ABORTED'
                           ) AS anomaly_cycles,
                           SUM(EXTRACT(EPOCH FROM (
                               effective.completed_at - effective.started_at
                           ))) AS active_seconds,
                           SUM(effective.actual_tons) AS actual_tons,
                           COUNT(*) FILTER (
                               WHERE effective.actual_tons IS NULL
                           ) AS unknown_tons_cycles
                    FROM mine_operation_effective_record effective
                    JOIN mine_shift_instance shift_instance
                      ON shift_instance.id = effective.shift_instance_id
                     AND shift_instance.organization_id = effective.organization_id
                     AND shift_instance.site_id = effective.site_id
                    WHERE effective.organization_id = ? AND effective.site_id = ?
                      AND shift_instance.business_date BETWEEN ? AND ?
                      AND effective.effective
                    GROUP BY effective.vehicle_id
                )
                SELECT vehicle.id, vehicle.vehicle_code, vehicle.name,
                       assigned.shift_open_seconds,
                       COALESCE(actual.active_seconds, 0) AS active_seconds,
                       COALESCE(actual.effective_cycles, 0) AS effective_cycles,
                       COALESCE(actual.completed_cycles, 0) AS completed_cycles,
                       COALESCE(actual.anomaly_cycles, 0) AS anomaly_cycles,
                       actual.actual_tons,
                       COALESCE(actual.unknown_tons_cycles, 0) AS unknown_tons_cycles
                FROM assigned
                JOIN mine_vehicle vehicle ON vehicle.id = assigned.vehicle_id
                LEFT JOIN actual ON actual.vehicle_id = assigned.vehicle_id
                ORDER BY active_seconds DESC, vehicle.vehicle_code, vehicle.id
                LIMIT ?
                """, (resultSet, rowNumber) -> {
                    long shiftOpenSeconds = decimalLong(
                            resultSet.getBigDecimal("shift_open_seconds"));
                    long activeSeconds = decimalLong(
                            resultSet.getBigDecimal("active_seconds"));
                    return new OperationSummaryResponse.VehicleUtilization(
                            resultSet.getObject("id", UUID.class),
                            resultSet.getString("vehicle_code"),
                            resultSet.getString("name"), shiftOpenSeconds, activeSeconds,
                            percentage(activeSeconds, shiftOpenSeconds),
                            resultSet.getLong("effective_cycles"),
                            resultSet.getLong("completed_cycles"),
                            resultSet.getLong("anomaly_cycles"),
                            resultSet.getBigDecimal("actual_tons"),
                            resultSet.getLong("unknown_tons_cycles"));
                }, site.organizationId(), site.id(), fromDate, toDate,
                site.organizationId(), site.id(), fromDate, toDate, DETAIL_LIMIT + 1);
    }

    private List<OperationSummaryResponse.DriverWorkload> queryDrivers(
            MineSite site,
            LocalDate fromDate,
            LocalDate toDate
    ) {
        return jdbcTemplate.query("""
                SELECT effective.driver_person_id, person.name,
                       COUNT(*) AS effective_cycles,
                       COUNT(*) FILTER (
                           WHERE effective.record_status = 'COMPLETED'
                       ) AS completed_cycles,
                       COUNT(*) FILTER (
                           WHERE effective.record_status = 'ABORTED'
                       ) AS anomaly_cycles,
                       SUM(EXTRACT(EPOCH FROM (
                           effective.completed_at - effective.started_at
                       ))) AS active_seconds,
                       SUM(effective.actual_tons) AS actual_tons,
                       COUNT(*) FILTER (
                           WHERE effective.actual_tons IS NULL
                       ) AS unknown_tons_cycles
                FROM mine_operation_effective_record effective
                JOIN mine_shift_instance shift_instance
                  ON shift_instance.id = effective.shift_instance_id
                 AND shift_instance.organization_id = effective.organization_id
                 AND shift_instance.site_id = effective.site_id
                LEFT JOIN mine_person person
                  ON person.id = effective.driver_person_id
                 AND person.organization_id = effective.organization_id
                WHERE effective.organization_id = ? AND effective.site_id = ?
                  AND shift_instance.business_date BETWEEN ? AND ?
                  AND effective.effective
                GROUP BY effective.driver_person_id, person.name
                ORDER BY effective_cycles DESC, person.name NULLS LAST,
                         effective.driver_person_id NULLS LAST
                LIMIT ?
                """, (resultSet, rowNumber) ->
                        new OperationSummaryResponse.DriverWorkload(
                                resultSet.getObject("driver_person_id", UUID.class),
                                resultSet.getString("name"),
                                resultSet.getLong("effective_cycles"),
                                resultSet.getLong("completed_cycles"),
                                resultSet.getLong("anomaly_cycles"),
                                decimalLong(resultSet.getBigDecimal("active_seconds")),
                                resultSet.getBigDecimal("actual_tons"),
                                resultSet.getLong("unknown_tons_cycles")),
                site.organizationId(), site.id(), fromDate, toDate, DETAIL_LIMIT + 1);
    }

    private long currentWatermark(MineSite site) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(business_sequence), 0)
                FROM mine_operation_outbox
                WHERE organization_id = ? AND site_id = ?
                """, Long.class, site.organizationId(), site.id());
        return value == null ? 0L : value;
    }

    private List<OperationRecordPageResponse.Item> queryRecordPage(
            QueryBinding binding,
            CursorState cursor,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                WITH candidate_records AS MATERIALIZED (
                    SELECT effective.operation_record_id,
                       effective.shift_instance_id, shift_instance.business_date,
                       effective.work_order_id, work_order.order_number,
                       work_order.title AS order_title, effective.assignment_id,
                       effective.cycle_number, effective.record_status,
                       effective.vehicle_id, vehicle.vehicle_code,
                       vehicle.name AS vehicle_name,
                       effective.driver_person_id, person.name AS driver_name,
                       effective.started_at, base.load_completed_at,
                       base.unloading_started_at, effective.completed_at,
                       EXTRACT(EPOCH FROM (
                           effective.completed_at - effective.started_at
                       ))::BIGINT AS total_duration_seconds,
                       CASE WHEN base.load_completed_at IS NULL THEN NULL ELSE
                           EXTRACT(EPOCH FROM (
                               base.load_completed_at - base.started_at
                           ))::BIGINT END AS loading_seconds,
                       CASE WHEN base.load_completed_at IS NULL
                                  OR base.unloading_started_at IS NULL THEN NULL ELSE
                           EXTRACT(EPOCH FROM (
                               base.unloading_started_at - base.load_completed_at
                           ))::BIGINT END AS hauling_seconds,
                       CASE WHEN base.unloading_started_at IS NULL THEN NULL ELSE
                           EXTRACT(EPOCH FROM (
                               base.completed_at - base.unloading_started_at
                           ))::BIGINT END AS unloading_seconds,
                       effective.distance_meters, effective.actual_tons,
                       effective.tons_source, effective.correction_version,
                       effective.projected_at
                FROM mine_operation_effective_record effective
                JOIN mine_operation_record base
                  ON base.id = effective.operation_record_id
                 AND base.organization_id = effective.organization_id
                 AND base.site_id = effective.site_id
                JOIN mine_shift_instance shift_instance
                  ON shift_instance.id = effective.shift_instance_id
                 AND shift_instance.organization_id = effective.organization_id
                 AND shift_instance.site_id = effective.site_id
                JOIN mine_work_order work_order
                  ON work_order.id = effective.work_order_id
                 AND work_order.organization_id = effective.organization_id
                 AND work_order.site_id = effective.site_id
                JOIN mine_vehicle vehicle
                  ON vehicle.id = effective.vehicle_id
                 AND vehicle.organization_id = effective.organization_id
                 AND vehicle.site_id = effective.site_id
                LEFT JOIN mine_person person
                  ON person.id = effective.driver_person_id
                 AND person.organization_id = effective.organization_id
                WHERE effective.organization_id = ? AND effective.site_id = ?
                  AND shift_instance.business_date BETWEEN ? AND ?
                  AND effective.effective
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(binding.organizationId());
        arguments.add(binding.siteId());
        arguments.add(binding.fromDate());
        arguments.add(binding.toDate());
        appendFilter(sql, arguments, "effective.shift_instance_id", binding.shiftInstanceId());
        appendFilter(sql, arguments, "effective.work_order_id", binding.workOrderId());
        appendFilter(sql, arguments, "effective.vehicle_id", binding.vehicleId());
        appendFilter(sql, arguments, "effective.driver_person_id", binding.driverPersonId());
        appendFilter(sql, arguments, "effective.record_status", binding.recordStatus());
        sql.append("""
                )
                SELECT candidate.operation_record_id, sequence.record_sequence,
                       candidate.shift_instance_id, candidate.business_date,
                       candidate.work_order_id, candidate.order_number,
                       candidate.order_title, candidate.assignment_id,
                       candidate.cycle_number, candidate.record_status,
                       candidate.vehicle_id, candidate.vehicle_code,
                       candidate.vehicle_name, candidate.driver_person_id,
                       candidate.driver_name, candidate.started_at,
                       candidate.load_completed_at, candidate.unloading_started_at,
                       candidate.completed_at, candidate.total_duration_seconds,
                       candidate.loading_seconds, candidate.hauling_seconds,
                       candidate.unloading_seconds, candidate.distance_meters,
                       candidate.actual_tons, candidate.tons_source,
                       candidate.correction_version, candidate.projected_at
                FROM candidate_records candidate
                JOIN LATERAL (
                    SELECT MIN(outbox.business_sequence) AS record_sequence
                    FROM mine_operation_outbox outbox
                    WHERE outbox.organization_id = ? AND outbox.site_id = ?
                      AND outbox.aggregate_type = 'OPERATION_RECORD'
                      AND outbox.aggregate_id = candidate.operation_record_id
                      AND outbox.business_sequence <= ?
                ) sequence ON sequence.record_sequence IS NOT NULL
                """);
        arguments.add(binding.organizationId());
        arguments.add(binding.siteId());
        arguments.add(binding.watermark());
        if (cursor != null) {
            sql.append(" WHERE (candidate.completed_at, sequence.record_sequence, ")
                    .append("candidate.operation_record_id) < (?, ?, ?)");
            arguments.add(Timestamp.from(cursor.completedAt()));
            arguments.add(cursor.recordSequence());
            arguments.add(cursor.recordId());
        }
        sql.append(" ORDER BY candidate.completed_at DESC, ")
                .append("sequence.record_sequence DESC, candidate.operation_record_id DESC LIMIT ?");
        arguments.add(limit);
        return jdbcTemplate.query(sql.toString(), this::mapRecordItem, arguments.toArray());
    }

    private void appendFilter(
            StringBuilder sql,
            List<Object> arguments,
            String column,
            Object value
    ) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = ?");
            arguments.add(value);
        }
    }

    private OperationRecordPageResponse.Item mapRecordItem(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        BigDecimal actualTons = resultSet.getBigDecimal("actual_tons");
        return new OperationRecordPageResponse.Item(
                resultSet.getObject("operation_record_id", UUID.class),
                resultSet.getLong("record_sequence"),
                resultSet.getObject("shift_instance_id", UUID.class),
                resultSet.getObject("business_date", LocalDate.class),
                resultSet.getObject("work_order_id", UUID.class),
                resultSet.getString("order_number"),
                resultSet.getString("order_title"),
                resultSet.getObject("assignment_id", UUID.class),
                resultSet.getLong("cycle_number"),
                resultSet.getString("record_status"),
                resultSet.getObject("vehicle_id", UUID.class),
                resultSet.getString("vehicle_code"),
                resultSet.getString("vehicle_name"),
                resultSet.getObject("driver_person_id", UUID.class),
                resultSet.getString("driver_name"),
                instant(resultSet, "started_at"),
                instant(resultSet, "load_completed_at"),
                instant(resultSet, "unloading_started_at"),
                instant(resultSet, "completed_at"),
                resultSet.getLong("total_duration_seconds"),
                resultSet.getObject("loading_seconds", Long.class),
                resultSet.getObject("hauling_seconds", Long.class),
                resultSet.getObject("unloading_seconds", Long.class),
                resultSet.getBigDecimal("distance_meters"), actualTons,
                actualTons != null, resultSet.getString("tons_source"),
                resultSet.getInt("correction_version"),
                instant(resultSet, "projected_at"));
    }

    private String encodeCursor(
            QueryBinding binding,
            OperationRecordPageResponse.Item last
    ) {
        CursorState state = CursorState.from(binding, last);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    objectMapper.writeValueAsBytes(state));
        } catch (JacksonException exception) {
            throw new IllegalStateException("运营记录游标编码失败", exception);
        }
    }

    private CursorState decodeCursor(String cursor) {
        try {
            byte[] json = Base64.getUrlDecoder().decode(
                    cursor.getBytes(StandardCharsets.US_ASCII));
            CursorState state = objectMapper.readValue(json, CursorState.class);
            if (state.organizationId() == null || state.siteId() == null
                    || state.fromDate() == null || state.toDate() == null
                    || state.watermark() < 0L || state.completedAt() == null
                    || state.recordSequence() < 1L || state.recordId() == null) {
                throw new IllegalArgumentException("运营记录游标字段不合法");
            }
            return state;
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            throw pageInvalid("CURSOR_INVALID");
        }
    }

    private void validateDateRange(LocalDate fromDate, LocalDate toDate) {
        if (fromDate == null || toDate == null || toDate.isBefore(fromDate)
                || ChronoUnit.DAYS.between(fromDate, toDate) > 30L) {
            throw new MineException(RANGE_INVALID, Map.of("maximumDays", 31));
        }
    }

    private String normalizeRecordStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!RECORD_STATUSES.contains(normalized)) {
            throw pageInvalid("RECORD_STATUS_INVALID");
        }
        return normalized;
    }

    private BigDecimal percentage(long numerator, Number denominator) {
        if (denominator == null || new BigDecimal(denominator.toString()).signum() <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(new BigDecimal(denominator.toString()), 2, RoundingMode.HALF_UP);
    }

    private long decimalLong(BigDecimal value) {
        return value == null ? 0L : value.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        BigDecimal value = resultSet.getBigDecimal(column);
        return value == null ? null : value.longValueExact();
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private <T> List<T> trim(List<T> values) {
        return values.size() > DETAIL_LIMIT
                ? List.copyOf(values.subList(0, DETAIL_LIMIT)) : List.copyOf(values);
    }

    private MineException pageInvalid(String reason) {
        return new MineException(PAGE_INVALID, Map.of("reason", reason));
    }

    /** 计划作业合计。 */
    private record PlanAggregate(
            int workOrderCount,
            Long plannedCycles,
            BigDecimal plannedTons,
            boolean allCyclesPlanned
    ) {
    }

    /** 当前有效执行投影合计。 */
    private record RecordAggregate(
            long completedCycles,
            long anomalyCycles,
            BigDecimal actualTons,
            long knownTonsCycles,
            long unknownTonsCycles,
            BigDecimal distanceMeters,
            long totalCycleSeconds,
            BigDecimal averageCycleSeconds,
            BigDecimal averageLoadingSeconds,
            BigDecimal averageHaulingSeconds,
            BigDecimal averageUnloadingSeconds
    ) {
    }

    /** 固定水位分页查询条件。 */
    private record QueryBinding(
            UUID organizationId,
            UUID siteId,
            LocalDate fromDate,
            LocalDate toDate,
            long watermark,
            UUID shiftInstanceId,
            UUID workOrderId,
            UUID vehicleId,
            UUID driverPersonId,
            String recordStatus
    ) {
    }

    /** 与完整查询条件绑定的下一页游标。 */
    private record CursorState(
            UUID organizationId,
            UUID siteId,
            LocalDate fromDate,
            LocalDate toDate,
            long watermark,
            UUID shiftInstanceId,
            UUID workOrderId,
            UUID vehicleId,
            UUID driverPersonId,
            String recordStatus,
            Instant completedAt,
            long recordSequence,
            UUID recordId
    ) {

        /** 从页末记录生成完整游标状态。 */
        private static CursorState from(
                QueryBinding binding,
                OperationRecordPageResponse.Item last
        ) {
            return new CursorState(
                    binding.organizationId(), binding.siteId(),
                    binding.fromDate(), binding.toDate(), binding.watermark(),
                    binding.shiftInstanceId(), binding.workOrderId(),
                    binding.vehicleId(), binding.driverPersonId(), binding.recordStatus(),
                    last.completedAt(), last.recordSequence(), last.recordId());
        }

        /** 判断游标是否属于完全相同的查询。 */
        private boolean matches(QueryBinding binding) {
            return organizationId.equals(binding.organizationId())
                    && siteId.equals(binding.siteId())
                    && fromDate.equals(binding.fromDate())
                    && toDate.equals(binding.toDate())
                    && watermark == binding.watermark()
                    && Objects.equals(shiftInstanceId, binding.shiftInstanceId())
                    && Objects.equals(workOrderId, binding.workOrderId())
                    && Objects.equals(vehicleId, binding.vehicleId())
                    && Objects.equals(driverPersonId, binding.driverPersonId())
                    && Objects.equals(recordStatus, binding.recordStatus());
        }
    }
}
