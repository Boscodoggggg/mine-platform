package com.bosco.mine.server.modules.strategy.service;

import cn.dev33.satoken.stp.StpUtil;
import com.bosco.mine.common.error.ErrorCode;
import com.bosco.mine.common.error.MineException;
import com.bosco.mine.common.id.IdGenerator;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.strategy.repository.StrategyScopeMapper;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 管理产品内设备组及可追溯的成员生效区间。
 */
@Service
public class DeviceGroupService {

    private static final ErrorCode GROUP_NOT_FOUND =
            new ErrorCode("STRATEGY-404-001", "设备组不存在");
    private static final ErrorCode GROUP_KEY_CONFLICT =
            new ErrorCode("STRATEGY-409-002", "产品下已存在相同设备组编码");
    private static final ErrorCode VERSION_CONFLICT =
            new ErrorCode("STRATEGY-409-003", "设备组已被其他管理员修改，请刷新后重试");
    private static final ErrorCode GROUP_INACTIVE =
            new ErrorCode("STRATEGY-409-004", "归档设备组不能继续修改");
    private static final ErrorCode PRODUCT_NOT_ACTIVE =
            new ErrorCode("STRATEGY-422-001", "只有启用产品可以维护设备组");
    private static final ErrorCode MEMBER_INVALID =
            new ErrorCode("STRATEGY-422-002", "成员设备必须属于同一产品且未归档");
    private static final ErrorCode MEMBER_DUPLICATED =
            new ErrorCode("STRATEGY-422-003", "成员设备标识不能重复");

    private final StrategyScopeMapper mapper;
    private final OperationAuditService auditService;
    private final StrategyConflictGuard conflictGuard;
    private final EffectiveStrategyCacheInvalidator cacheInvalidator;
    private final StrategyRuntimeSnapshotService runtimeSnapshotService;
    private final IdGenerator idGenerator = IdGenerator.timeOrdered();

    /**
     * 创建设备组服务。
     *
     * @param mapper       策略作用域数据接口
     * @param auditService  操作审计服务
     * @param conflictGuard 策略冲突门禁
     * @param cacheInvalidator 有效策略缓存失效器
     * @param runtimeSnapshotService 运行策略增量快照服务
     */
    public DeviceGroupService(
            StrategyScopeMapper mapper,
            OperationAuditService auditService,
            StrategyConflictGuard conflictGuard,
            EffectiveStrategyCacheInvalidator cacheInvalidator,
            StrategyRuntimeSnapshotService runtimeSnapshotService
    ) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.conflictGuard = conflictGuard;
        this.cacheInvalidator = cacheInvalidator;
        this.runtimeSnapshotService = runtimeSnapshotService;
    }

    /**
     * 分页查询指定产品下的设备组。
     *
     * @param productId 产品标识
     * @param keyword   编码或名称关键词
     * @param status    设备组状态
     * @param page      页码
     * @param size      每页数量
     * @return 标准分页结果
     */
    @Transactional(readOnly = true)
    public PageResponse<DeviceGroupView> findGroups(
            UUID productId,
            String keyword,
            String status,
            int page,
            int size
    ) {
        String normalizedKeyword = normalize(keyword);
        String normalizedStatus = normalize(status);
        long total = mapper.countGroups(productId, normalizedKeyword, normalizedStatus);
        List<DeviceGroupView> items = total == 0
                ? List.of()
                : mapper.findGroups(productId, normalizedKeyword, normalizedStatus, (page - 1) * size, size)
                .stream().map(DeviceGroupService::toView).toList();
        return new PageResponse<>(items, page, size, total);
    }

    /**
     * 按资源标识查询设备组详情。
     *
     * @param groupId 设备组标识
     * @return 设备组详情
     */
    @Transactional(readOnly = true)
    public DeviceGroupView findGroup(UUID groupId) {
        return toView(requireGroup(groupId));
    }

    /**
     * 查询设备组当前成员详情。
     *
     * @param groupId 设备组标识
     * @return 当前成员列表
     */
    @Transactional(readOnly = true)
    public List<DeviceGroupMemberView> findMembers(UUID groupId) {
        requireGroup(groupId);
        return mapper.findActiveMembers(groupId).stream()
                .map(member -> new DeviceGroupMemberView(
                        member.id(), member.deviceCode(), member.name(), member.status(), member.effectiveFrom()
                ))
                .toList();
    }

    /**
     * 在启用产品下创建活动设备组。
     *
     * @param command 创建命令
     * @return 新建设备组快照
     */
    @Transactional
    public DeviceGroupView createGroup(CreateGroupCommand command) {
        if (!mapper.isActiveProduct(command.productId())) {
            throw new MineException(PRODUCT_NOT_ACTIVE, Map.of("productId", command.productId()));
        }
        String groupKey = command.groupKey().strip().toLowerCase(Locale.ROOT);
        UUID groupId = idGenerator.next();
        try {
            mapper.insertGroup(
                    groupId,
                    command.productId(),
                    groupKey,
                    command.name().strip(),
                    command.priority(),
                    normalize(command.description()),
                    currentOperatorId()
            );
        } catch (DuplicateKeyException exception) {
            throw new MineException(GROUP_KEY_CONFLICT, Map.of(
                    "productId", command.productId(), "groupKey", groupKey
            ));
        }
        DeviceGroupView created = toView(requireGroup(groupId));
        auditService.recordSuccess(
                "DEVICE_STRATEGY", "CREATE_DEVICE_GROUP", "device:group:edit",
                "DEVICE_GROUP", groupId, Map.of(), summary(created)
        );
        return created;
    }

    /**
     * 使用乐观锁修改设备组名称、优先级和说明。
     *
     * @param groupId 设备组标识
     * @param command 修改命令
     * @return 修改后的设备组快照
     */
    @Transactional
    public DeviceGroupView updateGroup(UUID groupId, UpdateGroupCommand command) {
        StrategyScopeMapper.DeviceGroupRecord initial = requireActiveGroup(groupId);
        mapper.lockProduct(initial.productId());
        StrategyScopeMapper.DeviceGroupRecord locked = requireActiveGroupForUpdate(groupId);
        DeviceGroupView before = toView(locked);
        List<UUID> affectedDeviceIds = List.of();
        if (locked.priority() != command.priority()) {
            affectedDeviceIds = mapper.findActiveMemberIds(groupId);
            conflictGuard.verifyPriorityChange(locked, command.priority(), affectedDeviceIds);
        }
        int affected = mapper.updateGroup(
                groupId,
                command.name().strip(),
                command.priority(),
                normalize(command.description()),
                command.version(),
                currentOperatorId()
        );
        if (affected == 0) {
            throw versionConflict(groupId);
        }
        DeviceGroupView updated = toView(requireGroup(groupId));
        auditService.recordSuccess(
                "DEVICE_STRATEGY", "UPDATE_DEVICE_GROUP", "device:group:edit",
                "DEVICE_GROUP", groupId, summary(before), summary(updated)
        );
        if (locked.priority() != command.priority()) {
            runtimeSnapshotService.captureDelta(
                    locked.productId(), "DATA_QUALITY", affectedDeviceIds,
                    StrategyRuntimeSnapshotService.ChangeReason.PRIORITY
            );
            cacheInvalidator.invalidateAfterCommit(
                    "ONLINE_STATUS", affectedDeviceIds,
                    EffectiveStrategyCacheInvalidator.InvalidationReason.PRIORITY
            );
        }
        return updated;
    }

    /**
     * 以差异方式替换设备组当前成员，并保留已移除关系的历史区间。
     *
     * @param groupId  设备组标识
     * @param deviceIds 目标成员设备标识
     * @param version   设备组期望版本号
     * @return 成员变化摘要
     */
    @Transactional
    public MembershipChange replaceMembers(UUID groupId, List<UUID> deviceIds, long version) {
        StrategyScopeMapper.DeviceGroupRecord initial = requireActiveGroup(groupId);
        mapper.lockProduct(initial.productId());
        StrategyScopeMapper.DeviceGroupRecord group = requireActiveGroupForUpdate(groupId);
        if (group.version() != version) {
            throw versionConflict(groupId);
        }
        LinkedHashSet<UUID> desired = new LinkedHashSet<>(deviceIds);
        if (desired.size() != deviceIds.size()) {
            throw new MineException(MEMBER_DUPLICATED, Map.of("groupId", groupId));
        }
        List<UUID> desiredList = List.copyOf(desired);
        if (!desiredList.isEmpty()
                && mapper.countEligibleDevices(group.productId(), desiredList) != desiredList.size()) {
            throw new MineException(MEMBER_INVALID, Map.of(
                    "groupId", groupId,
                    "productId", group.productId(),
                    "requestedCount", desiredList.size()
            ));
        }

        List<UUID> current = mapper.findActiveMemberIds(groupId);
        Set<UUID> currentSet = Set.copyOf(current);
        List<UUID> added = desiredList.stream().filter(id -> !currentSet.contains(id)).toList();
        List<UUID> removed = current.stream().filter(id -> !desired.contains(id)).toList();
        if (added.isEmpty() && removed.isEmpty()) {
            return new MembershipChange(groupId, List.of(), List.of(), current.size(), version);
        }

        conflictGuard.verifyMembershipChange(group, added);

        UUID operatorId = currentOperatorId();
        if (!removed.isEmpty()) {
            mapper.closeMemberships(groupId, removed, operatorId);
        }
        if (!added.isEmpty()) {
            List<StrategyScopeMapper.MembershipInsert> inserts = added.stream()
                    .map(deviceId -> new StrategyScopeMapper.MembershipInsert(idGenerator.next(), deviceId))
                    .toList();
            mapper.insertMemberships(groupId, inserts, operatorId);
        }
        if (mapper.touchGroup(groupId, version, operatorId) == 0) {
            throw versionConflict(groupId);
        }
        MembershipChange change = new MembershipChange(
                groupId, added, removed, desiredList.size(), version + 1
        );
        auditService.recordSuccess(
                "DEVICE_STRATEGY", "REPLACE_DEVICE_GROUP_MEMBERS", "device:group:edit",
                "DEVICE_GROUP", groupId,
                Map.of("memberCount", current.size()),
                Map.of(
                        "memberCount", desiredList.size(),
                        "addedCount", added.size(),
                        "removedCount", removed.size()
                )
        );
        List<UUID> affectedDeviceIds = new ArrayList<>(added.size() + removed.size());
        affectedDeviceIds.addAll(added);
        affectedDeviceIds.addAll(removed);
        runtimeSnapshotService.captureDelta(
                group.productId(), "DATA_QUALITY", affectedDeviceIds,
                StrategyRuntimeSnapshotService.ChangeReason.MEMBERSHIP
        );
        cacheInvalidator.invalidateAfterCommit(
                "ONLINE_STATUS", affectedDeviceIds,
                EffectiveStrategyCacheInvalidator.InvalidationReason.MEMBERSHIP
        );
        return change;
    }

    /**
     * 先结束全部当前成员关系，再使用乐观锁归档设备组。
     *
     * @param groupId 设备组标识
     * @param version 设备组期望版本号
     */
    @Transactional
    public void archiveGroup(UUID groupId, long version) {
        StrategyScopeMapper.DeviceGroupRecord initial = requireActiveGroup(groupId);
        mapper.lockProduct(initial.productId());
        DeviceGroupView before = toView(requireActiveGroupForUpdate(groupId));
        if (before.version() != version) {
            throw versionConflict(groupId);
        }
        List<UUID> affectedDeviceIds = mapper.findActiveMemberIds(groupId);
        UUID operatorId = currentOperatorId();
        mapper.closeAllActiveMemberships(groupId, operatorId);
        if (mapper.archiveGroup(groupId, version, operatorId) == 0) {
            throw versionConflict(groupId);
        }
        auditService.recordSuccess(
                "DEVICE_STRATEGY", "ARCHIVE_DEVICE_GROUP", "device:group:edit",
                "DEVICE_GROUP", groupId, summary(before), Map.of("status", "ARCHIVED")
        );
        runtimeSnapshotService.captureDelta(
                initial.productId(), "DATA_QUALITY", affectedDeviceIds,
                StrategyRuntimeSnapshotService.ChangeReason.GROUP_ARCHIVE
        );
        cacheInvalidator.invalidateAfterCommit(
                "ONLINE_STATUS", affectedDeviceIds,
                EffectiveStrategyCacheInvalidator.InvalidationReason.ARCHIVE
        );
    }

    private StrategyScopeMapper.DeviceGroupRecord requireGroup(UUID groupId) {
        return mapper.findGroupById(groupId)
                .orElseThrow(() -> new MineException(GROUP_NOT_FOUND, Map.of("groupId", groupId)));
    }

    private StrategyScopeMapper.DeviceGroupRecord requireActiveGroup(UUID groupId) {
        StrategyScopeMapper.DeviceGroupRecord group = requireGroup(groupId);
        if (!"ACTIVE".equals(group.status())) {
            throw new MineException(GROUP_INACTIVE, Map.of("groupId", groupId));
        }
        return group;
    }

    private StrategyScopeMapper.DeviceGroupRecord requireActiveGroupForUpdate(UUID groupId) {
        StrategyScopeMapper.DeviceGroupRecord group = mapper.findGroupByIdForUpdate(groupId)
                .orElseThrow(() -> new MineException(GROUP_NOT_FOUND, Map.of("groupId", groupId)));
        if (!"ACTIVE".equals(group.status())) {
            throw new MineException(GROUP_INACTIVE, Map.of("groupId", groupId));
        }
        return group;
    }

    private MineException versionConflict(UUID groupId) {
        return new MineException(VERSION_CONFLICT, Map.of("groupId", groupId));
    }

    private static DeviceGroupView toView(StrategyScopeMapper.DeviceGroupRecord group) {
        return new DeviceGroupView(
                group.id(), group.productId(), group.groupKey(), group.name(), group.priority(),
                group.status(), group.description(), group.archivedAt(), group.createdAt(),
                group.updatedAt(), group.version(), group.memberCount()
        );
    }

    private Map<String, ?> summary(DeviceGroupView group) {
        return Map.of(
                "productId", group.productId(),
                "groupKey", group.groupKey(),
                "name", group.name(),
                "priority", group.priority(),
                "status", group.status(),
                "memberCount", group.memberCount()
        );
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private UUID currentOperatorId() {
        try {
            return UUID.fromString(StpUtil.getLoginIdAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * 设备组创建命令。
     *
     * @param productId   所属产品标识
     * @param groupKey    稳定设备组编码
     * @param name        设备组名称
     * @param priority    策略优先级
     * @param description 用途说明
     */
    public record CreateGroupCommand(
            UUID productId,
            String groupKey,
            String name,
            int priority,
            String description
    ) {
    }

    /**
     * 设备组修改命令。
     *
     * @param name        设备组名称
     * @param priority    策略优先级
     * @param description 用途说明
     * @param version     乐观锁版本号
     */
    public record UpdateGroupCommand(String name, int priority, String description, long version) {
    }

    /**
     * 设备组对外快照。
     *
     * @param id          设备组标识
     * @param productId   所属产品标识
     * @param groupKey    稳定设备组编码
     * @param name        设备组名称
     * @param priority    策略优先级
     * @param status      设备组状态
     * @param description 用途说明
     * @param archivedAt  归档时间
     * @param createdAt   创建时间
     * @param updatedAt   更新时间
     * @param version     乐观锁版本号
     * @param memberCount 当前成员数量
     */
    public record DeviceGroupView(
            UUID id,
            UUID productId,
            String groupKey,
            String name,
            int priority,
            String status,
            String description,
            Instant archivedAt,
            Instant createdAt,
            Instant updatedAt,
            long version,
            long memberCount
    ) {
    }

    /**
     * 设备组当前成员视图。
     *
     * @param id            设备标识
     * @param deviceCode    设备编码
     * @param name          设备名称
     * @param status        设备状态
     * @param effectiveFrom 加入设备组时间
     */
    public record DeviceGroupMemberView(
            UUID id,
            String deviceCode,
            String name,
            String status,
            Instant effectiveFrom
    ) {
    }

    /**
     * 一次设备组成员替换的差异摘要。
     *
     * @param groupId          设备组标识
     * @param addedDeviceIds   新增设备标识
     * @param removedDeviceIds 移除设备标识
     * @param memberCount      替换后的成员数量
     * @param version          替换后的乐观锁版本号
     */
    public record MembershipChange(
            UUID groupId,
            List<UUID> addedDeviceIds,
            List<UUID> removedDeviceIds,
            int memberCount,
            long version
    ) {
        /**
         * 固化不可变的成员差异集合。
         */
        public MembershipChange {
            addedDeviceIds = List.copyOf(addedDeviceIds);
            removedDeviceIds = List.copyOf(removedDeviceIds);
        }
    }
}
