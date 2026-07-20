package com.bosco.mine.server.modules.mine.operation;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.operation.domain.ShiftLifecycle;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftDefinitionRequest;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftDefinitionResponse;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftInstanceRequest;
import com.bosco.mine.server.modules.mine.operation.dto.ShiftInstanceResponse;
import com.bosco.mine.server.modules.mine.service.MineScopeAuthorizer;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 管理班次模板和由矿区时区计算的班次实例生命周期。
 */
@Service
public class ShiftCommandService {

    private static final Pattern DEFINITION_CODE = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private static final ErrorCode DEFINITION_NOT_FOUND =
            new ErrorCode("MINE-SHIFT-404-001", "班次模板不存在");
    private static final ErrorCode INSTANCE_NOT_FOUND =
            new ErrorCode("MINE-SHIFT-404-002", "班次实例不存在");
    private static final ErrorCode DEFINITION_CONFLICT =
            new ErrorCode("MINE-SHIFT-409-001", "班次模板编码冲突或已被其他管理员修改");
    private static final ErrorCode INSTANCE_CONFLICT =
            new ErrorCode("MINE-SHIFT-409-002", "同一模板和营业日期的班次实例已存在");
    private static final ErrorCode CLOSE_BLOCKED =
            new ErrorCode("MINE-SHIFT-409-003", "班次仍有未终结运输作业或循环运行态");
    private static final ErrorCode LIFECYCLE_CONFLICT =
            new ErrorCode("MINE-SHIFT-409-004", "当前班次状态不允许执行该动作");
    private static final ErrorCode INVALID =
            new ErrorCode("MINE-SHIFT-422-001", "班次模板、时间或动作参数不合法");
    private static final ErrorCode DEFINITION_INACTIVE =
            new ErrorCode("MINE-SHIFT-422-002", "班次模板未启用或营业日期超出有效期");
    private static final ErrorCode SUPERVISOR_INVALID =
            new ErrorCode("MINE-SHIFT-422-003", "班次负责人必须在完整班次期间于当前矿区在职");

    private final OperationMapper mapper;
    private final ShiftQueryService queryService;
    private final MineScopeAuthorizer authorizer;
    private final OperationAuditService auditService;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建班次命令服务。
     *
     * @param mapper 运营控制面数据访问接口
     * @param queryService 班次查询服务
     * @param authorizer 矿区作用域授权器
     * @param auditService 操作审计服务
     * @param objectMapper JSON 映射器
     */
    public ShiftCommandService(
            OperationMapper mapper,
            ShiftQueryService queryService,
            MineScopeAuthorizer authorizer,
            OperationAuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.queryService = queryService;
        this.authorizer = authorizer;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建矿区班次模板。
     *
     * @param siteId 矿区标识
     * @param request 模板请求
     * @return 新建模板
     */
    @Transactional
    public ShiftDefinitionResponse createDefinition(
            UUID siteId, ShiftDefinitionRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        ShiftDefinitionRequest normalized = normalize(request);
        UUID definitionId = idGenerator.next();
        UUID operatorId = authorizer.currentUserId();
        try {
            mapper.insertShiftDefinition(
                    definitionId, site.organizationId(), site.id(),
                    normalized.definitionCode(), normalized.name(),
                    normalized.startLocal(), normalized.endLocal(),
                    normalized.crossesMidnight(), normalized.validFrom(), normalized.validTo(),
                    operatorId);
        } catch (DuplicateKeyException exception) {
            throw definitionConflict(definitionId);
        } catch (DataIntegrityViolationException exception) {
            throw invalid(exception);
        }
        ShiftDefinitionResponse created = queryService.findDefinition(site.id(), definitionId);
        audit("CREATE_SHIFT_DEFINITION", "mine:shift:edit", "MINE_SHIFT_DEFINITION",
                definitionId, Map.of(), definitionSummary(created));
        return created;
    }

    /**
     * 使用乐观锁修改活动班次模板。
     *
     * @param siteId 矿区标识
     * @param definitionId 模板标识
     * @param request 模板请求和当前版本
     * @return 修改后的模板
     */
    @Transactional
    public ShiftDefinitionResponse updateDefinition(
            UUID siteId, UUID definitionId, ShiftDefinitionRequest request
    ) {
        MineSite site = authorizer.requireSite(siteId);
        ShiftDefinitionRequest normalized = normalize(request);
        OperationMapper.ShiftDefinitionRow before = requireDefinition(site, definitionId, false);
        try {
            if (mapper.updateShiftDefinition(
                    definitionId, site.organizationId(), site.id(),
                    normalized.definitionCode(), normalized.name(),
                    normalized.startLocal(), normalized.endLocal(),
                    normalized.crossesMidnight(), normalized.validFrom(), normalized.validTo(),
                    normalized.version(), authorizer.currentUserId()) == 0) {
                throw definitionConflict(definitionId);
            }
        } catch (DuplicateKeyException exception) {
            throw definitionConflict(definitionId);
        } catch (DataIntegrityViolationException exception) {
            throw invalid(exception);
        }
        ShiftDefinitionResponse updated = queryService.findDefinition(site.id(), definitionId);
        audit("UPDATE_SHIFT_DEFINITION", "mine:shift:edit", "MINE_SHIFT_DEFINITION",
                definitionId, definitionSummary(ShiftQueryService.toResponse(before)),
                definitionSummary(updated));
        return updated;
    }

    /**
     * 使用乐观锁归档活动班次模板。
     *
     * @param siteId 矿区标识
     * @param definitionId 模板标识
     * @param version 当前版本
     * @return 归档后的模板
     */
    @Transactional
    public ShiftDefinitionResponse archiveDefinition(
            UUID siteId, UUID definitionId, long version
    ) {
        MineSite site = authorizer.requireSite(siteId);
        OperationMapper.ShiftDefinitionRow before = requireDefinition(site, definitionId, false);
        if (mapper.archiveShiftDefinition(
                site.organizationId(), site.id(), definitionId, version,
                authorizer.currentUserId()) == 0) {
            throw definitionConflict(definitionId);
        }
        ShiftDefinitionResponse archived = queryService.findDefinition(site.id(), definitionId);
        audit("ARCHIVE_SHIFT_DEFINITION", "mine:shift:edit", "MINE_SHIFT_DEFINITION",
                definitionId, definitionSummary(ShiftQueryService.toResponse(before)),
                definitionSummary(archived));
        return archived;
    }

    /**
     * 使用矿区时区和营业日期创建计划班次实例。
     *
     * @param siteId 矿区标识
     * @param request 实例请求
     * @return 新建班次实例
     */
    @Transactional
    public ShiftInstanceResponse createInstance(UUID siteId, ShiftInstanceRequest request) {
        MineSite site = authorizer.requireSite(siteId);
        if (request == null || request.shiftDefinitionId() == null
                || request.businessDate() == null) {
            throw invalid();
        }
        OperationMapper.ShiftDefinitionRow definition = requireDefinition(
                site, request.shiftDefinitionId(), true);
        ensureEffective(definition, request.businessDate());
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(site.timezone());
        } catch (RuntimeException exception) {
            throw invalid(exception);
        }
        Instant plannedStartAt = request.businessDate()
                .atTime(definition.startLocal()).atZone(zoneId).toInstant();
        LocalDate endDate = definition.crossesMidnight()
                ? request.businessDate().plusDays(1)
                : request.businessDate();
        Instant plannedEndAt = endDate
                .atTime(definition.endLocal()).atZone(zoneId).toInstant();
        Duration duration = Duration.between(plannedStartAt, plannedEndAt);
        if (duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofHours(24)) > 0) {
            throw invalid();
        }
        if (request.supervisorPersonId() != null
                && !mapper.isShiftSupervisorEligible(
                site.organizationId(), site.id(), request.supervisorPersonId(),
                plannedStartAt, plannedEndAt)) {
            throw new MineException(SUPERVISOR_INVALID, Map.of(
                    "supervisorPersonId", request.supervisorPersonId()));
        }
        UUID shiftId = idGenerator.next();
        UUID operatorId = authorizer.currentUserId();
        Map<String, Object> snapshot = definitionSnapshot(
                definition, site.timezone(), plannedStartAt, plannedEndAt);
        try {
            mapper.insertShiftInstance(
                    shiftId, site.organizationId(), site.id(), definition.id(),
                    request.businessDate(), plannedStartAt, plannedEndAt,
                    request.supervisorPersonId(), toJson(snapshot), operatorId);
        } catch (DuplicateKeyException exception) {
            throw new MineException(INSTANCE_CONFLICT, Map.of(
                    "definitionId", definition.id(), "businessDate", request.businessDate()));
        } catch (DataIntegrityViolationException exception) {
            throw invalid(exception);
        }
        ShiftInstanceResponse created = queryService.findInstance(site.id(), shiftId);
        audit("CREATE_SHIFT_INSTANCE", "mine:shift:edit", "MINE_SHIFT_INSTANCE",
                shiftId, Map.of(), instanceSummary(created));
        return created;
    }

    /**
     * 幂等开启一个计划班次。
     *
     * @param siteId 矿区标识
     * @param shiftId 班次实例标识
     * @return 开启后的班次实例
     */
    @Transactional
    public ShiftInstanceResponse openInstance(UUID siteId, UUID shiftId) {
        return openInstance(siteId, shiftId, null);
    }

    /**
     * 使用客户端乐观版本幂等开启一个计划班次。
     *
     * @param siteId 矿区标识
     * @param shiftId 班次实例标识
     * @param expectedVersion 客户端看到的当前版本
     * @return 开启后的班次实例
     */
    @Transactional
    public ShiftInstanceResponse openInstance(
            UUID siteId, UUID shiftId, long expectedVersion
    ) {
        return openInstance(siteId, shiftId, Long.valueOf(expectedVersion));
    }

    private ShiftInstanceResponse openInstance(
            UUID siteId, UUID shiftId, Long expectedVersion
    ) {
        MineSite site = authorizer.requireSite(siteId);
        OperationMapper.ShiftInstanceRow before = lockInstance(site, shiftId);
        requireActionVersion(before, expectedVersion);
        if ("OPEN".equals(before.status())) {
            return queryService.toResponse(before);
        }
        requireTransition(before.status(), ShiftLifecycle.Action.OPEN);
        Instant actionAt = Instant.now();
        if (mapper.openShiftInstance(
                site.organizationId(), site.id(), shiftId, before.version(), actionAt,
                authorizer.currentUserId()) == 0) {
            throw lifecycleConflict(shiftId);
        }
        ShiftInstanceResponse opened = queryService.findInstance(site.id(), shiftId);
        audit("OPEN_SHIFT", "mine:shift:edit", "MINE_SHIFT_INSTANCE", shiftId,
                instanceSummary(queryService.toResponse(before)), instanceSummary(opened));
        return opened;
    }

    /**
     * 幂等关闭没有运行作业或循环的已开启班次。
     *
     * @param siteId 矿区标识
     * @param shiftId 班次实例标识
     * @return 关账后的班次实例
     */
    @Transactional
    public ShiftInstanceResponse closeInstance(UUID siteId, UUID shiftId) {
        return closeInstance(siteId, shiftId, null);
    }

    /**
     * 使用客户端乐观版本关闭没有运行作业或循环的已开启班次。
     *
     * @param siteId 矿区标识
     * @param shiftId 班次实例标识
     * @param expectedVersion 客户端看到的当前版本
     * @return 关账后的班次实例
     */
    @Transactional
    public ShiftInstanceResponse closeInstance(
            UUID siteId, UUID shiftId, long expectedVersion
    ) {
        return closeInstance(siteId, shiftId, Long.valueOf(expectedVersion));
    }

    private ShiftInstanceResponse closeInstance(
            UUID siteId, UUID shiftId, Long expectedVersion
    ) {
        MineSite site = authorizer.requireSite(siteId);
        OperationMapper.ShiftInstanceRow before = lockInstance(site, shiftId);
        requireActionVersion(before, expectedVersion);
        if ("CLOSED".equals(before.status())) {
            return queryService.toResponse(before);
        }
        requireTransition(before.status(), ShiftLifecycle.Action.CLOSE);
        boolean hasUnfinishedWork = !mapper.lockShiftBlockingWorkOrders(
                site.organizationId(), site.id(), shiftId).isEmpty();
        boolean hasRuntimeState = !mapper.lockShiftRuntimeStates(
                site.organizationId(), site.id(), shiftId).isEmpty();
        if (hasUnfinishedWork || hasRuntimeState) {
            throw new MineException(CLOSE_BLOCKED, Map.of("shiftId", shiftId));
        }
        Instant actionAt = Instant.now();
        if (mapper.closeShiftInstance(
                site.organizationId(), site.id(), shiftId, before.version(), actionAt,
                authorizer.currentUserId()) == 0) {
            throw lifecycleConflict(shiftId);
        }
        ShiftInstanceResponse closed = queryService.findInstance(site.id(), shiftId);
        audit("CLOSE_SHIFT", "mine:shift:close", "MINE_SHIFT_INSTANCE", shiftId,
                instanceSummary(queryService.toResponse(before)), instanceSummary(closed));
        return closed;
    }

    /**
     * 幂等取消一个尚未开启的计划班次。
     *
     * @param siteId 矿区标识
     * @param shiftId 班次实例标识
     * @param reason 取消原因
     * @return 取消后的班次实例
     */
    @Transactional
    public ShiftInstanceResponse cancelInstance(UUID siteId, UUID shiftId, String reason) {
        return cancelInstance(siteId, shiftId, null, reason);
    }

    /**
     * 使用客户端乐观版本取消一个尚未开启的计划班次。
     *
     * @param siteId 矿区标识
     * @param shiftId 班次实例标识
     * @param expectedVersion 客户端看到的当前版本
     * @param reason 取消原因
     * @return 取消后的班次实例
     */
    @Transactional
    public ShiftInstanceResponse cancelInstance(
            UUID siteId, UUID shiftId, long expectedVersion, String reason
    ) {
        return cancelInstance(siteId, shiftId, Long.valueOf(expectedVersion), reason);
    }

    private ShiftInstanceResponse cancelInstance(
            UUID siteId, UUID shiftId, Long expectedVersion, String reason
    ) {
        MineSite site = authorizer.requireSite(siteId);
        String normalizedReason = required(reason, 500);
        OperationMapper.ShiftInstanceRow before = lockInstance(site, shiftId);
        requireActionVersion(before, expectedVersion);
        if ("CANCELLED".equals(before.status())) {
            if (normalizedReason.equals(before.cancellationReason())) {
                return queryService.toResponse(before);
            }
            throw lifecycleConflict(shiftId);
        }
        requireTransition(before.status(), ShiftLifecycle.Action.CANCEL);
        if (!mapper.lockShiftBlockingWorkOrders(
                site.organizationId(), site.id(), shiftId).isEmpty()) {
            throw new MineException(CLOSE_BLOCKED, Map.of("shiftId", shiftId));
        }
        Instant actionAt = Instant.now();
        if (mapper.cancelShiftInstance(
                site.organizationId(), site.id(), shiftId, before.version(), actionAt,
                normalizedReason, authorizer.currentUserId()) == 0) {
            throw lifecycleConflict(shiftId);
        }
        ShiftInstanceResponse cancelled = queryService.findInstance(site.id(), shiftId);
        audit("CANCEL_SHIFT", "mine:shift:edit", "MINE_SHIFT_INSTANCE", shiftId,
                instanceSummary(queryService.toResponse(before)), instanceSummary(cancelled));
        return cancelled;
    }

    private void requireActionVersion(
            OperationMapper.ShiftInstanceRow instance, Long expectedVersion
    ) {
        if (expectedVersion != null && instance.version() != expectedVersion) {
            throw new MineException(LIFECYCLE_CONFLICT, Map.of(
                    "shiftId", instance.id(),
                    "expectedVersion", expectedVersion,
                    "actualVersion", instance.version()));
        }
    }

    private OperationMapper.ShiftDefinitionRow requireDefinition(
            MineSite site, UUID definitionId, boolean lock
    ) {
        return (lock
                ? mapper.lockShiftDefinition(site.organizationId(), site.id(), definitionId)
                : mapper.findShiftDefinition(site.organizationId(), site.id(), definitionId))
                .orElseThrow(() -> new MineException(
                        DEFINITION_NOT_FOUND, Map.of("definitionId", definitionId)));
    }

    private OperationMapper.ShiftInstanceRow lockInstance(MineSite site, UUID shiftId) {
        return mapper.lockShiftInstance(site.organizationId(), site.id(), shiftId)
                .orElseThrow(() -> new MineException(
                        INSTANCE_NOT_FOUND, Map.of("shiftId", shiftId)));
    }

    private void ensureEffective(
            OperationMapper.ShiftDefinitionRow definition, LocalDate businessDate
    ) {
        if (!"ACTIVE".equals(definition.status())
                || businessDate.isBefore(definition.validFrom())
                || (definition.validTo() != null && businessDate.isAfter(definition.validTo()))) {
            throw new MineException(DEFINITION_INACTIVE, Map.of(
                    "definitionId", definition.id(), "businessDate", businessDate));
        }
    }

    private ShiftDefinitionRequest normalize(ShiftDefinitionRequest request) {
        if (request == null || request.startLocal() == null || request.endLocal() == null
                || request.validFrom() == null || request.version() == null) {
            throw invalid();
        }
        String code = required(request.definitionCode(), 64).toLowerCase(Locale.ROOT);
        String name = required(request.name(), 128);
        if (!DEFINITION_CODE.matcher(code).matches()
                || (request.validTo() != null && request.validTo().isBefore(request.validFrom()))) {
            throw invalid();
        }
        boolean validWindow = request.crossesMidnight()
                ? request.endLocal().isBefore(request.startLocal())
                : request.endLocal().isAfter(request.startLocal());
        if (!validWindow) {
            throw invalid();
        }
        return new ShiftDefinitionRequest(
                code, name, request.startLocal(), request.endLocal(), request.crossesMidnight(),
                request.validFrom(), request.validTo(), request.version());
    }

    private void requireTransition(String status, ShiftLifecycle.Action action) {
        try {
            ShiftLifecycle.transition(ShiftLifecycle.Status.valueOf(status), action);
        } catch (RuntimeException exception) {
            throw new MineException(LIFECYCLE_CONFLICT, Map.of(
                    "status", status, "action", action.name()));
        }
    }

    private Map<String, Object> definitionSnapshot(
            OperationMapper.ShiftDefinitionRow definition,
            String timezone,
            Instant plannedStartAt,
            Instant plannedEndAt
    ) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("definitionId", definition.id().toString());
        snapshot.put("definitionCode", definition.definitionCode());
        snapshot.put("name", definition.name());
        snapshot.put("startLocal", definition.startLocal().toString());
        snapshot.put("endLocal", definition.endLocal().toString());
        snapshot.put("crossesMidnight", definition.crossesMidnight());
        snapshot.put("validFrom", definition.validFrom().toString());
        if (definition.validTo() != null) {
            snapshot.put("validTo", definition.validTo().toString());
        }
        snapshot.put("timezone", timezone);
        snapshot.put("plannedStartAt", plannedStartAt.toString());
        snapshot.put("plannedEndAt", plannedEndAt.toString());
        snapshot.put("definitionVersion", definition.version());
        return Map.copyOf(snapshot);
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("班次冻结模板快照序列化失败", exception);
        }
    }

    private Map<String, Object> definitionSummary(ShiftDefinitionResponse response) {
        return Map.of(
                "definitionCode", response.definitionCode(),
                "name", response.name(),
                "status", response.status(),
                "version", response.version());
    }

    private Map<String, Object> instanceSummary(ShiftInstanceResponse response) {
        return Map.of(
                "businessDate", response.businessDate().toString(),
                "status", response.status(),
                "definitionId", response.shiftDefinitionId().toString(),
                "version", response.version());
    }

    private void audit(
            String action,
            String permission,
            String resourceType,
            UUID resourceId,
            Map<String, ?> before,
            Map<String, ?> after
    ) {
        auditService.recordSuccess(
                "MINE_OPERATION", action, permission, resourceType, resourceId, before, after);
    }

    private String required(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw invalid();
        }
        return normalized;
    }

    private MineException definitionConflict(UUID definitionId) {
        return new MineException(DEFINITION_CONFLICT, Map.of("definitionId", definitionId));
    }

    private MineException lifecycleConflict(UUID shiftId) {
        return new MineException(LIFECYCLE_CONFLICT, Map.of("shiftId", shiftId));
    }

    private MineException invalid() {
        return new MineException(INVALID, Map.of());
    }

    private MineException invalid(RuntimeException exception) {
        MineException invalid = invalid();
        invalid.addSuppressed(exception);
        return invalid;
    }
}
