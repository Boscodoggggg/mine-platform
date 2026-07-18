package com.bosco.mine.server.modules.mine.service;

import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.common.time.MineClock;
import com.bosco.mine.server.modules.device.model.Device;
import com.bosco.mine.server.modules.device.repository.DeviceMapper;
import com.bosco.mine.server.modules.mine.dto.BindingPreviewResponse;
import com.bosco.mine.server.modules.mine.dto.BindingSwitchRequest;
import com.bosco.mine.server.modules.mine.location.LocationSnapshotService;
import com.bosco.mine.server.modules.mine.model.MineLocationTag;
import com.bosco.mine.server.modules.mine.model.MineSite;
import com.bosco.mine.server.modules.mine.model.MineVehicle;
import com.bosco.mine.server.modules.mine.repository.MineAssetMapper;
import com.bosco.mine.server.modules.mine.repository.MineBindingMapper;
import com.bosco.mine.server.modules.mine.repository.MineBindingMapper.BindingRecord;
import com.bosco.mine.server.modules.mine.repository.MineBindingMapper.LockedVehicleRecord;
import com.bosco.mine.server.modules.mine.repository.MineBindingMapper.PersonAssignmentRecord;
import com.bosco.mine.server.modules.mine.repository.MineBindingMapper.QualificationRecord;
import com.bosco.mine.server.modules.mine.repository.MineBindingMapper.SwitchOperationRecord;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 编排四类矿区资产绑定的影响预览、原子换绑、幂等和发生时解析。
 */
@Service
public class MineBindingService {

    private static final ErrorCode TARGET_NOT_FOUND =
            new ErrorCode("MINE-BINDING-404-001", "换绑目标不存在、不活动或不属于当前矿区");
    private static final ErrorCode RESOURCE_NOT_FOUND =
            new ErrorCode("MINE-BINDING-404-002", "被换绑资源不存在、不活动或不属于当前矿区");
    private static final ErrorCode EFFECTIVE_TIME_INVALID =
            new ErrorCode("MINE-BINDING-422-001", "换绑生效时间必须在当前时刻至未来 366 天内");
    private static final ErrorCode REQUEST_INVALID =
            new ErrorCode("MINE-BINDING-422-002", "换绑类型、角色、原因或标识不合法");
    private static final ErrorCode QUALIFICATION_INVALID =
            new ErrorCode("MINE-BINDING-422-003", "司机车型资质或有效期不满足换绑要求");
    private static final ErrorCode SCOPE_CONFLICT =
            new ErrorCode("MINE-BINDING-422-004", "已有关系属于其他矿区，禁止跨矿直接换绑");
    private static final ErrorCode IDEMPOTENCY_CONFLICT =
            new ErrorCode("MINE-BINDING-409-001", "幂等键已绑定其他换绑请求");
    private static final ErrorCode BINDING_CONFLICT =
            new ErrorCode("MINE-BINDING-409-002", "换绑关系已变化或与其他有效关系冲突");

    private static final Set<String> DEVICE_ROLES = Set.of("PRIMARY", "SECONDARY");
    private static final Set<String> DRIVER_ROLES = Set.of("PRIMARY_DRIVER", "RELIEF_DRIVER");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Duration MAX_SCHEDULING_HORIZON = Duration.ofDays(366);

    private final MineBindingMapper mapper;
    private final MineAssetMapper assetMapper;
    private final DeviceMapper deviceMapper;
    private final MineScopeAuthorizer authorizer;
    private final OperationAuditService auditService;
    private final LocationSnapshotService locationSnapshotService;
    private final ObjectMapper objectMapper;
    private final MineClock clock;
    private final IdGenerator idGenerator;

    /**
     * 创建生产环境使用系统 UTC 时钟和时间有序标识的换绑服务。
     *
     * @param mapper 绑定数据访问接口
     * @param assetMapper 车辆和标签数据访问接口
     * @param deviceMapper 设备台账数据访问接口
     * @param authorizer 矿区作用域授权器
     * @param auditService 操作审计服务
     * @param locationSnapshotService 定位运行快照服务
     * @param objectMapper JSON 映射器
     */
    @Autowired
    public MineBindingService(
            MineBindingMapper mapper,
            MineAssetMapper assetMapper,
            DeviceMapper deviceMapper,
            MineScopeAuthorizer authorizer,
            OperationAuditService auditService,
            LocationSnapshotService locationSnapshotService,
            ObjectMapper objectMapper
    ) {
        this(
                mapper, assetMapper, deviceMapper, authorizer, auditService,
                locationSnapshotService, objectMapper,
                MineClock.systemUtc(), IdGenerator.timeOrdered());
    }

    /**
     * 创建可注入业务时钟和标识生成器的换绑服务。
     */
    MineBindingService(
            MineBindingMapper mapper,
            MineAssetMapper assetMapper,
            DeviceMapper deviceMapper,
            MineScopeAuthorizer authorizer,
            OperationAuditService auditService,
            LocationSnapshotService locationSnapshotService,
            ObjectMapper objectMapper,
            MineClock clock,
            IdGenerator idGenerator
    ) {
        this.mapper = Objects.requireNonNull(mapper, "绑定数据访问接口不能为空");
        this.assetMapper = Objects.requireNonNull(assetMapper, "资产数据访问接口不能为空");
        this.deviceMapper = Objects.requireNonNull(deviceMapper, "设备数据访问接口不能为空");
        this.authorizer = Objects.requireNonNull(authorizer, "矿区授权器不能为空");
        this.auditService = Objects.requireNonNull(auditService, "操作审计服务不能为空");
        this.locationSnapshotService = Objects.requireNonNull(
                locationSnapshotService, "定位快照服务不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON 映射器不能为空");
        this.clock = Objects.requireNonNull(clock, "业务时钟不能为空");
        this.idGenerator = Objects.requireNonNull(idGenerator, "标识生成器不能为空");
    }

    /**
     * 预览换绑时的旧关系、目标关系、司机资质和受影响对象，不写入任何业务状态。
     *
     * @param siteId 当前矿区标识
     * @param request 换绑请求
     * @return 影响预览
     */
    @Transactional(readOnly = true)
    public BindingPreviewResponse preview(UUID siteId, BindingSwitchRequest request) {
        NormalizedCommand command = normalize(request);
        validateEffectiveTime(command.effectiveAt());
        MineSite site = authorizer.requireSite(siteId);
        SwitchContext context = inspect(site, command);
        return response(null, false, command, context, null, null);
    }

    /**
     * 以管理员幂等键原子关闭旧关系、建立新关系并写审计和 Outbox。
     *
     * @param siteId 当前矿区标识
     * @param request 换绑请求
     * @param idempotencyKey 管理员幂等键
     * @return 首次提交或精确重试的换绑结果
     */
    @Transactional
    public BindingPreviewResponse switchBinding(
            UUID siteId,
            BindingSwitchRequest request,
            String idempotencyKey
    ) {
        NormalizedCommand command = normalize(request);
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        MineSite site = authorizer.requireSite(siteId);
        UUID operatorId = authorizer.currentUserId();
        String requestHash = requestHash(site.id(), command);
        Optional<SwitchOperationRecord> existing = mapper.findOperation(operatorId, normalizedKey);
        if (existing.isPresent()) {
            return replay(existing.orElseThrow(), requestHash, site);
        }
        validateEffectiveTime(command.effectiveAt());
        mapper.lockSwitchResource(command.resourceId());
        existing = mapper.findOperation(operatorId, normalizedKey);
        if (existing.isPresent()) {
            return replay(existing.orElseThrow(), requestHash, site);
        }
        lockSwitchObjects(site, command);
        BindingRecord lockPlanningPrevious = findPrevious(command);
        lockPersonTimelines(command, lockPlanningPrevious);
        validateEffectiveTime(command.effectiveAt());

        SwitchContext context = inspect(site, command);
        if (!context.blockers().isEmpty()) {
            if (context.blockers().contains("DRIVER_QUALIFICATION_INVALID")) {
                throw new MineException(QUALIFICATION_INVALID, Map.of());
            }
            throw new MineException(BINDING_CONFLICT, Map.of());
        }
        UUID operationId = idGenerator.next();
        UUID newBindingId = idGenerator.next();
        UUID outboxEventId = idGenerator.next();
        BindingRecord previous = context.previous();
        BindingRecord proposed = new BindingRecord(
                newBindingId, command.type().name(), site.organizationId(), site.id(),
                command.resourceId(), command.targetId(), command.role(),
                command.effectiveAt(), context.proposedValidTo());
        BindingPreviewResponse committedResponse = response(
                operationId, true, command, context, proposed, outboxEventId);
        int inserted = mapper.insertOperation(
                operationId, site.organizationId(), site.id(), operatorId,
                normalizedKey, requestHash, command.type().name(), command.resourceId(),
                command.targetId(), command.role(), command.effectiveAt(), command.reason(),
                previous == null ? null : previous.bindingType(),
                previous == null ? null : previous.bindingId(),
                newBindingId, context.qualificationId(), outboxEventId,
                writeResponse(committedResponse));
        if (inserted == 0) {
            SwitchOperationRecord concurrent = mapper.findOperation(operatorId, normalizedKey)
                    .orElseThrow(() -> new MineException(IDEMPOTENCY_CONFLICT, Map.of()));
            return replay(concurrent, requestHash, site);
        }

        try {
            closePrevious(previous, command.effectiveAt(), operatorId);
            insertNew(site, command, context.proposedValidTo(), newBindingId, operatorId);
            locationSnapshotService.captureSite(
                            site.organizationId(), site.id(),
                            LocationSnapshotService.ChangeReason.BINDING_SWITCH,
                            outboxEventId)
                    .orElseThrow(() -> new IllegalStateException("换绑后定位运行快照未发生变化"));
            audit(operationId, previous, newBindingId, site, command, outboxEventId);
        } catch (DataIntegrityViolationException exception) {
            throw new MineException(BINDING_CONFLICT, Map.of());
        }
        return committedResponse;
    }

    /**
     * 按定位标签和发生时间解析当时的车辆或人员归属。
     *
     * @param tagId 定位标签标识
     * @param occurredAt 发生时间
     * @return 当时关系，无绑定时为空
     */
    @Transactional(readOnly = true)
    public Optional<BindingResolution> resolveTag(UUID tagId, Instant occurredAt) {
        requireResolutionArguments(tagId, occurredAt);
        return mapper.findTagBindingAt(tagId, occurredAt).map(MineBindingService::resolution);
    }

    /**
     * 按设备和发生时间解析当时所属车辆。
     *
     * @param deviceId 设备标识
     * @param occurredAt 发生时间
     * @return 当时关系，无绑定时为空
     */
    @Transactional(readOnly = true)
    public Optional<BindingResolution> resolveDevice(UUID deviceId, Instant occurredAt) {
        requireResolutionArguments(deviceId, occurredAt);
        return mapper.findDeviceBindingAt(deviceId, occurredAt).map(MineBindingService::resolution);
    }

    /**
     * 按人员和发生时间解析当时驾驶车辆。
     *
     * @param personId 人员标识
     * @param occurredAt 发生时间
     * @return 当时关系，无绑定时为空
     */
    @Transactional(readOnly = true)
    public Optional<BindingResolution> resolvePersonVehicle(UUID personId, Instant occurredAt) {
        requireResolutionArguments(personId, occurredAt);
        return mapper.findPersonVehicleBindingAt(personId, occurredAt)
                .map(MineBindingService::resolution);
    }

    private SwitchContext inspect(MineSite site, NormalizedCommand command) {
        BindingRecord previous;
        BindingRecord targetOccupancy;
        boolean resourceIntervalOccupied;
        UUID qualificationId = null;
        Instant proposedValidTo = null;
        switch (command.type()) {
            case VEHICLE_DEVICE -> {
                requireEnabledDevice(command.resourceId());
                requireActiveVehicle(site, command.targetId());
                previous = mapper.findDeviceBindingAt(
                        command.resourceId(), command.effectiveAt()).orElse(null);
                targetOccupancy = "PRIMARY".equals(command.role())
                        ? mapper.findPrimaryDeviceOverlapAtVehicle(
                                command.targetId(), command.effectiveAt(), proposedValidTo,
                                bindingId(previous)).orElse(null)
                        : null;
                resourceIntervalOccupied = mapper.hasDeviceBindingOverlap(
                        command.resourceId(), command.effectiveAt(), proposedValidTo,
                        bindingId(previous));
            }
            case VEHICLE_TAG -> {
                requireActiveTag(site, command.resourceId());
                requireActiveVehicle(site, command.targetId());
                previous = mapper.findTagBindingAt(
                        command.resourceId(), command.effectiveAt()).orElse(null);
                targetOccupancy = mapper.findTagOverlapAtVehicle(
                        command.targetId(), command.effectiveAt(), proposedValidTo,
                        bindingId(previous)).orElse(null);
                resourceIntervalOccupied = mapper.hasTagBindingOverlap(
                        command.resourceId(), command.effectiveAt(), proposedValidTo,
                        bindingId(previous));
            }
            case PERSON_TAG -> {
                requireActiveTag(site, command.resourceId());
                proposedValidTo = requireActivePerson(
                        site, command.targetId(), command.effectiveAt(), true).validTo();
                previous = mapper.findTagBindingAt(
                        command.resourceId(), command.effectiveAt()).orElse(null);
                targetOccupancy = mapper.findTagOverlapAtPerson(
                        command.targetId(), command.effectiveAt(), proposedValidTo,
                        bindingId(previous)).orElse(null);
                resourceIntervalOccupied = mapper.hasTagBindingOverlap(
                        command.resourceId(), command.effectiveAt(), proposedValidTo,
                        bindingId(previous));
            }
            case PERSON_VEHICLE -> {
                proposedValidTo = requireActivePerson(
                        site, command.resourceId(), command.effectiveAt(), false).validTo();
                MineVehicle vehicle = requireActiveVehicle(site, command.targetId());
                previous = mapper.findPersonVehicleBindingAt(
                        command.resourceId(), command.effectiveAt()).orElse(null);
                QualificationRecord qualification = mapper.findValidQualification(
                                site.organizationId(), command.resourceId(), vehicle.vehicleType(),
                                effectiveDate(site, command.effectiveAt()))
                        .orElse(null);
                qualificationId = qualification == null ? null : qualification.id();
                if (qualification != null) {
                    proposedValidTo = earlierUpperBound(
                            proposedValidTo,
                            qualification.validTo().plusDays(1)
                                    .atStartOfDay(ZoneId.of(site.timezone()))
                                    .toInstant());
                }
                targetOccupancy = "PRIMARY_DRIVER".equals(command.role())
                        ? mapper.findPrimaryDriverOverlapAtVehicle(
                                command.targetId(), command.effectiveAt(), proposedValidTo,
                                bindingId(previous)).orElse(null)
                        : null;
                resourceIntervalOccupied = mapper.hasPersonVehicleBindingOverlap(
                        command.resourceId(), command.effectiveAt(), proposedValidTo,
                        bindingId(previous));
            }
            default -> throw new MineException(REQUEST_INVALID, Map.of());
        }
        validatePreviousScope(site, previous);
        List<String> blockers = new ArrayList<>();
        if (command.type() == BindingType.PERSON_VEHICLE && qualificationId == null) {
            blockers.add("DRIVER_QUALIFICATION_INVALID");
        }
        if (previous != null
                && previous.targetId().equals(command.targetId())
                && previous.bindingType().equals(command.type().name())
                && Objects.equals(previous.role(), command.role())) {
            blockers.add("ALREADY_BOUND_TO_TARGET");
        }
        if (targetOccupancy != null
                && (previous == null
                || !targetOccupancy.bindingId().equals(previous.bindingId()))) {
            blockers.add("TARGET_ROLE_OCCUPIED");
        }
        if (previous != null && !previous.validFrom().isBefore(command.effectiveAt())) {
            blockers.add("BINDING_START_CONFLICT");
        }
        if (resourceIntervalOccupied && !blockers.contains("BINDING_START_CONFLICT")) {
            blockers.add("BINDING_START_CONFLICT");
        }
        return new SwitchContext(
                previous, qualificationId, proposedValidTo, List.copyOf(blockers));
    }

    private static UUID bindingId(BindingRecord binding) {
        return binding == null ? null : binding.bindingId();
    }

    private BindingRecord findPrevious(NormalizedCommand command) {
        return switch (command.type()) {
            case VEHICLE_DEVICE -> mapper.findDeviceBindingAt(
                    command.resourceId(), command.effectiveAt()).orElse(null);
            case VEHICLE_TAG, PERSON_TAG -> mapper.findTagBindingAt(
                    command.resourceId(), command.effectiveAt()).orElse(null);
            case PERSON_VEHICLE -> mapper.findPersonVehicleBindingAt(
                    command.resourceId(), command.effectiveAt()).orElse(null);
        };
    }

    private void lockPersonTimelines(NormalizedCommand command, BindingRecord previous) {
        TreeSet<UUID> personIds = new TreeSet<>();
        if (command.type() == BindingType.PERSON_TAG) {
            personIds.add(command.targetId());
        } else if (command.type() == BindingType.PERSON_VEHICLE) {
            personIds.add(command.resourceId());
        }
        if (previous != null && "PERSON_TAG".equals(previous.bindingType())) {
            personIds.add(previous.targetId());
        } else if (previous != null && "PERSON_VEHICLE".equals(previous.bindingType())) {
            personIds.add(previous.resourceId());
        }
        personIds.forEach(mapper::lockPersonTimeline);
    }

    private void lockSwitchObjects(MineSite site, NormalizedCommand command) {
        String siteStatus = mapper.lockSite(site.organizationId(), site.id())
                .orElseGet(() -> authorizer.requireSite(site.id()).status());
        if (!"ACTIVE".equals(siteStatus)) {
            authorizer.requireSite(site.id());
            throw new MineException(BINDING_CONFLICT, Map.of());
        }
        if (command.type() == BindingType.VEHICLE_DEVICE) {
            String deviceStatus = mapper.lockDevice(command.resourceId())
                    .orElseThrow(() -> new MineException(RESOURCE_NOT_FOUND, Map.of()));
            if (!"ENABLED".equals(deviceStatus)) {
                throw new MineException(RESOURCE_NOT_FOUND, Map.of());
            }
        } else if (command.type() == BindingType.VEHICLE_TAG
                || command.type() == BindingType.PERSON_TAG) {
            String tagStatus = mapper.lockTag(
                            site.organizationId(), site.id(), command.resourceId())
                    .orElseThrow(() -> new MineException(RESOURCE_NOT_FOUND, Map.of()));
            if (!"ACTIVE".equals(tagStatus)) {
                throw new MineException(RESOURCE_NOT_FOUND, Map.of());
            }
        }
        if (command.type() == BindingType.VEHICLE_DEVICE
                || command.type() == BindingType.VEHICLE_TAG
                || command.type() == BindingType.PERSON_VEHICLE) {
            LockedVehicleRecord vehicle = mapper.lockVehicle(
                            site.organizationId(), site.id(), command.targetId())
                    .orElseThrow(() -> new MineException(TARGET_NOT_FOUND, Map.of()));
            if (!"ACTIVE".equals(vehicle.status())) {
                throw new MineException(TARGET_NOT_FOUND, Map.of());
            }
        }
    }

    private void requireEnabledDevice(UUID deviceId) {
        Device device = deviceMapper.findDeviceById(deviceId)
                .orElseThrow(() -> new MineException(RESOURCE_NOT_FOUND, Map.of()));
        if (!"ENABLED".equals(device.status())) {
            throw new MineException(RESOURCE_NOT_FOUND, Map.of());
        }
    }

    private MineVehicle requireActiveVehicle(MineSite site, UUID vehicleId) {
        MineVehicle vehicle = assetMapper.findVehicleById(
                        site.organizationId(), site.id(), vehicleId)
                .orElseThrow(() -> new MineException(TARGET_NOT_FOUND, Map.of()));
        if (!"ACTIVE".equals(vehicle.status())) {
            throw new MineException(TARGET_NOT_FOUND, Map.of());
        }
        return vehicle;
    }

    private void requireActiveTag(MineSite site, UUID tagId) {
        MineLocationTag tag = assetMapper.findLocationTagById(
                        site.organizationId(), site.id(), tagId)
                .orElseThrow(() -> new MineException(RESOURCE_NOT_FOUND, Map.of()));
        if (!"ACTIVE".equals(tag.status())) {
            throw new MineException(RESOURCE_NOT_FOUND, Map.of());
        }
    }

    private PersonAssignmentRecord requireActivePerson(
            MineSite site,
            UUID personId,
            Instant effectiveAt,
            boolean target
    ) {
        return mapper.findActivePersonAssignment(
                        site.organizationId(), site.id(), personId, effectiveAt)
                .orElseThrow(() -> new MineException(
                        target ? TARGET_NOT_FOUND : RESOURCE_NOT_FOUND, Map.of()));
    }

    private void validatePreviousScope(MineSite site, BindingRecord previous) {
        if (previous != null
                && (!site.organizationId().equals(previous.organizationId())
                || !site.id().equals(previous.siteId()))) {
            throw new MineException(SCOPE_CONFLICT, Map.of());
        }
    }

    private void closePrevious(BindingRecord previous, Instant effectiveAt, UUID operatorId) {
        if (previous == null) {
            return;
        }
        int changed = switch (BindingType.valueOf(previous.bindingType())) {
            case VEHICLE_DEVICE -> mapper.closeVehicleDevice(
                    previous.bindingId(), effectiveAt, operatorId);
            case VEHICLE_TAG -> closeVehicleTag(previous.bindingId(), effectiveAt, operatorId);
            case PERSON_TAG -> closePersonTag(previous.bindingId(), effectiveAt, operatorId);
            case PERSON_VEHICLE -> mapper.closePersonVehicle(
                    previous.bindingId(), effectiveAt, operatorId);
        };
        if (changed != 1) {
            throw new MineException(BINDING_CONFLICT, Map.of());
        }
    }

    private int closeVehicleTag(UUID bindingId, Instant effectiveAt, UUID operatorId) {
        int claim = mapper.closeTagClaim(bindingId, effectiveAt);
        int typed = mapper.closeVehicleTag(bindingId, effectiveAt, operatorId);
        return claim == 1 && typed == 1 ? 1 : 0;
    }

    private int closePersonTag(UUID bindingId, Instant effectiveAt, UUID operatorId) {
        int claim = mapper.closeTagClaim(bindingId, effectiveAt);
        int typed = mapper.closePersonTag(bindingId, effectiveAt, operatorId);
        return claim == 1 && typed == 1 ? 1 : 0;
    }

    private void insertNew(
            MineSite site,
            NormalizedCommand command,
            Instant validTo,
            UUID bindingId,
            UUID operatorId
    ) {
        int changed = switch (command.type()) {
            case VEHICLE_DEVICE -> mapper.insertVehicleDevice(
                    bindingId, site.organizationId(), site.id(), command.targetId(),
                    command.resourceId(), command.role(), command.effectiveAt(),
                    command.reason(), operatorId);
            case VEHICLE_TAG -> insertVehicleTag(site, command, bindingId, operatorId);
            case PERSON_TAG -> insertPersonTag(
                    site, command, validTo, bindingId, operatorId);
            case PERSON_VEHICLE -> mapper.insertPersonVehicle(
                    bindingId, site.organizationId(), site.id(), command.resourceId(),
                    command.targetId(), command.role(), command.effectiveAt(), validTo,
                    command.reason(), operatorId);
        };
        if (changed != 1) {
            throw new MineException(BINDING_CONFLICT, Map.of());
        }
    }

    private int insertVehicleTag(
            MineSite site,
            NormalizedCommand command,
            UUID bindingId,
            UUID operatorId
    ) {
        int claim = mapper.insertTagClaim(
                bindingId, site.organizationId(), site.id(), "VEHICLE",
                command.resourceId(), command.effectiveAt(), null);
        int typed = mapper.insertVehicleTag(
                bindingId, site.organizationId(), site.id(), command.targetId(),
                command.resourceId(), command.effectiveAt(), command.reason(), operatorId);
        return claim == 1 && typed == 1 ? 1 : 0;
    }

    private int insertPersonTag(
            MineSite site,
            NormalizedCommand command,
            Instant validTo,
            UUID bindingId,
            UUID operatorId
    ) {
        int claim = mapper.insertTagClaim(
                bindingId, site.organizationId(), site.id(), "PERSON",
                command.resourceId(), command.effectiveAt(), validTo);
        int typed = mapper.insertPersonTag(
                bindingId, site.organizationId(), site.id(), command.targetId(),
                command.resourceId(), command.effectiveAt(), validTo,
                command.reason(), operatorId);
        return claim == 1 && typed == 1 ? 1 : 0;
    }

    private BindingPreviewResponse replay(
            SwitchOperationRecord operation,
            String requestHash,
            MineSite site
    ) {
        if (!operation.requestHash().equals(requestHash)) {
            throw new MineException(IDEMPOTENCY_CONFLICT, Map.of());
        }
        if (!site.organizationId().equals(operation.organizationId())
                || !site.id().equals(operation.siteId())) {
            throw new MineException(IDEMPOTENCY_CONFLICT, Map.of());
        }
        try {
            return objectMapper.readValue(
                    operation.responsePayload(), BindingPreviewResponse.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("换绑幂等响应快照无法解析", exception);
        }
    }

    private BindingPreviewResponse response(
            UUID operationId,
            boolean executed,
            NormalizedCommand command,
            SwitchContext context,
            BindingRecord created,
            UUID outboxEventId
    ) {
        BindingPreviewResponse.BindingRelation previous = relation(context.previous());
        BindingPreviewResponse.BindingRelation proposed = created == null
                ? new BindingPreviewResponse.BindingRelation(
                        null, command.type().name(), command.resourceId(), command.targetId(),
                        command.role(), command.effectiveAt(), context.proposedValidTo())
                : relation(created);
        boolean qualificationRequired = command.type() == BindingType.PERSON_VEHICLE;
        BindingPreviewResponse.QualificationAssessment qualification =
                new BindingPreviewResponse.QualificationAssessment(
                        qualificationRequired,
                        !qualificationRequired || context.qualificationId() != null,
                        context.qualificationId());
        return new BindingPreviewResponse(
                operationId, executed, context.blockers().isEmpty(), command.type().name(),
                previous, proposed, qualification, affectedObjects(command, context.previous()),
                context.blockers(), outboxEventId);
    }

    private List<BindingPreviewResponse.AffectedObject> affectedObjects(
            NormalizedCommand command,
            BindingRecord previous
    ) {
        List<BindingPreviewResponse.AffectedObject> affected = new ArrayList<>();
        affected.add(new BindingPreviewResponse.AffectedObject(
                resourceType(command.type()), command.resourceId(), "RESOURCE"));
        if (previous != null) {
            affected.add(new BindingPreviewResponse.AffectedObject(
                    targetType(BindingType.valueOf(previous.bindingType())),
                    previous.targetId(), "PREVIOUS_TARGET"));
        }
        affected.add(new BindingPreviewResponse.AffectedObject(
                targetType(command.type()), command.targetId(), "NEW_TARGET"));
        return List.copyOf(affected);
    }

    private void audit(
            UUID operationId,
            BindingRecord previous,
            UUID newBindingId,
            MineSite site,
            NormalizedCommand command,
            UUID outboxEventId
    ) {
        Map<String, Object> before = new LinkedHashMap<>();
        if (previous != null) {
            before.put("bindingId", previous.bindingId());
            before.put("bindingType", previous.bindingType());
            before.put("targetId", previous.targetId());
            before.put("validFrom", previous.validFrom());
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("siteId", site.id());
        after.put("bindingId", newBindingId);
        after.put("bindingType", command.type().name());
        after.put("resourceId", command.resourceId());
        after.put("targetId", command.targetId());
        after.put("effectiveAt", command.effectiveAt());
        after.put("reason", command.reason());
        after.put("outboxEventId", outboxEventId);
        auditService.recordSuccess(
                "MINE_MANAGEMENT", "SWITCH_BINDING", "mine:binding:switch",
                "MINE_BINDING_SWITCH", operationId, before, after);
    }

    private String writeResponse(BindingPreviewResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JacksonException exception) {
            throw new IllegalStateException("换绑幂等响应快照序列化失败", exception);
        }
    }

    private NormalizedCommand normalize(BindingSwitchRequest request) {
        if (request == null || request.bindingType() == null
                || request.resourceId() == null || request.targetId() == null
                || request.effectiveAt() == null || request.reason() == null
                || request.reason().isBlank() || request.reason().strip().length() > 500) {
            throw new MineException(REQUEST_INVALID, Map.of());
        }
        BindingType type;
        try {
            type = BindingType.valueOf(request.bindingType().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new MineException(REQUEST_INVALID, Map.of());
        }
        String role = request.role() == null || request.role().isBlank()
                ? null : request.role().strip().toUpperCase(Locale.ROOT);
        if ((type == BindingType.VEHICLE_DEVICE && !DEVICE_ROLES.contains(role))
                || (type == BindingType.PERSON_VEHICLE && !DRIVER_ROLES.contains(role))
                || ((type == BindingType.VEHICLE_TAG || type == BindingType.PERSON_TAG)
                && role != null)) {
            throw new MineException(REQUEST_INVALID, Map.of());
        }
        return new NormalizedCommand(
                type, request.resourceId(), request.targetId(), role,
                request.effectiveAt(), request.reason().strip());
    }

    private String normalizeIdempotencyKey(String value) {
        String normalized = value == null ? null : value.strip();
        if (normalized == null || !IDEMPOTENCY_KEY.matcher(normalized).matches()) {
            throw new MineException(REQUEST_INVALID, Map.of());
        }
        return normalized;
    }

    private void validateEffectiveTime(Instant effectiveAt) {
        Instant now = clock.instant();
        if (effectiveAt.isBefore(now)
                || effectiveAt.isAfter(now.plus(MAX_SCHEDULING_HORIZON))) {
            throw new MineException(EFFECTIVE_TIME_INVALID, Map.of());
        }
    }

    private String requestHash(UUID siteId, NormalizedCommand command) {
        String canonical = String.join("|",
                siteId.toString(), command.type().name(), command.resourceId().toString(),
                command.targetId().toString(), Objects.toString(command.role(), ""),
                command.effectiveAt().toString(), command.reason());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    private LocalDate effectiveDate(MineSite site, Instant effectiveAt) {
        return effectiveAt.atZone(ZoneId.of(site.timezone())).toLocalDate();
    }

    private Instant earlierUpperBound(Instant first, Instant second) {
        if (first == null) {
            return second;
        }
        return first.isBefore(second) ? first : second;
    }

    private static BindingPreviewResponse.BindingRelation relation(BindingRecord binding) {
        if (binding == null) {
            return null;
        }
        return new BindingPreviewResponse.BindingRelation(
                binding.bindingId(), binding.bindingType(), binding.resourceId(),
                binding.targetId(), binding.role(), binding.validFrom(), binding.validTo());
    }

    private static BindingResolution resolution(BindingRecord binding) {
        return new BindingResolution(
                binding.bindingId(), binding.bindingType(), binding.organizationId(),
                binding.siteId(), binding.resourceId(), binding.targetId(), binding.role(),
                binding.validFrom(), binding.validTo());
    }

    private static void requireResolutionArguments(UUID resourceId, Instant occurredAt) {
        Objects.requireNonNull(resourceId, "发生时解析资源标识不能为空");
        Objects.requireNonNull(occurredAt, "发生时解析时间不能为空");
    }

    private static String resourceType(BindingType type) {
        return switch (type) {
            case VEHICLE_DEVICE -> "DEVICE";
            case VEHICLE_TAG, PERSON_TAG -> "LOCATION_TAG";
            case PERSON_VEHICLE -> "PERSON";
        };
    }

    private static String targetType(BindingType type) {
        return switch (type) {
            case VEHICLE_DEVICE, VEHICLE_TAG, PERSON_VEHICLE -> "VEHICLE";
            case PERSON_TAG -> "PERSON";
        };
    }

    /**
     * 发生时解析得到的稳定历史关系。
     *
     * @param bindingId 关系标识
     * @param bindingType 关系类型
     * @param organizationId 所属组织标识
     * @param siteId 所属矿区标识
     * @param resourceId 设备、标签或人员标识
     * @param targetId 车辆或人员目标标识
     * @param role 关系角色
     * @param validFrom 生效时刻
     * @param validTo 失效时刻
     */
    public record BindingResolution(
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

    private enum BindingType {
        VEHICLE_DEVICE,
        VEHICLE_TAG,
        PERSON_TAG,
        PERSON_VEHICLE
    }

    private record NormalizedCommand(
            BindingType type,
            UUID resourceId,
            UUID targetId,
            String role,
            Instant effectiveAt,
            String reason
    ) {
    }

    private record SwitchContext(
            BindingRecord previous,
            UUID qualificationId,
            Instant proposedValidTo,
            List<String> blockers
    ) {
    }
}
