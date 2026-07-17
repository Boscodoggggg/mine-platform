package com.bosco.mine.server.modules.strategy.service;

import com.bosco.mine.common.error.MineException;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.strategy.repository.StrategyScopeMapper;
import com.bosco.mine.server.modules.system.service.OperationAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 校验设备组及其历史成员关系的业务约束。
 */
class DeviceGroupServiceTest {

    private StrategyScopeMapper mapper;
    private OperationAuditService auditService;
    private StrategyConflictGuard conflictGuard;
    private EffectiveStrategyCacheInvalidator cacheInvalidator;
    private StrategyRuntimeSnapshotService runtimeSnapshotService;
    private DeviceGroupService service;

    @BeforeEach
    void 创建设备组服务测试环境() {
        mapper = mock(StrategyScopeMapper.class);
        auditService = mock(OperationAuditService.class);
        conflictGuard = mock(StrategyConflictGuard.class);
        cacheInvalidator = mock(EffectiveStrategyCacheInvalidator.class);
        runtimeSnapshotService = mock(StrategyRuntimeSnapshotService.class);
        service = new DeviceGroupService(
                mapper, auditService, conflictGuard, cacheInvalidator, runtimeSnapshotService);
    }

    @Test
    void 创建设备组应规范化编码并要求产品处于启用状态() {
        UUID productId = UUID.randomUUID();
        when(mapper.isActiveProduct(productId)).thenReturn(true);
        when(mapper.findGroupById(any())).thenAnswer(invocation -> Optional.of(group(
                invocation.getArgument(0), productId, "haul-team", "运输一组", 800, "ACTIVE", 0
        )));

        DeviceGroupService.DeviceGroupView created = service.createGroup(
                new DeviceGroupService.CreateGroupCommand(
                        productId, "  HAUL-TEAM  ", " 运输一组 ", 800, " 主采区运输车辆 "
                )
        );

        assertThat(created.groupKey()).isEqualTo("haul-team");
        verify(mapper).insertGroup(
                any(), eq(productId), eq("haul-team"), eq("运输一组"), eq(800),
                eq("主采区运输车辆"), any()
        );
        verify(auditService).recordSuccess(
                eq("DEVICE_STRATEGY"), eq("CREATE_DEVICE_GROUP"), eq("device:group:edit"),
                eq("DEVICE_GROUP"), eq(created.id()), any(), any()
        );
    }

    @Test
    void 分页查询应使用正确偏移并返回标准分页对象() {
        UUID productId = UUID.randomUUID();
        StrategyScopeMapper.DeviceGroupRecord record = group(
                UUID.randomUUID(), productId, "haul-team", "运输一组", 800, "ACTIVE", 0
        );
        when(mapper.countGroups(productId, "运输", "ACTIVE")).thenReturn(21L);
        when(mapper.findGroups(productId, "运输", "ACTIVE", 20, 20)).thenReturn(List.of(record));

        PageResponse<DeviceGroupService.DeviceGroupView> result =
                service.findGroups(productId, " 运输 ", "ACTIVE", 2, 20);

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(20);
        assertThat(result.total()).isEqualTo(21);
        assertThat(result.items()).singleElement()
                .extracting(DeviceGroupService.DeviceGroupView::groupKey)
                .isEqualTo("haul-team");
    }

    @Test
    void 应能按资源地址查询单个设备组() {
        UUID productId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        when(mapper.findGroupById(groupId)).thenReturn(Optional.of(group(
                groupId, productId, "haul-team", "运输一组", 800, "ACTIVE", 0
        )));

        assertThat(service.findGroup(groupId)).satisfies(group -> {
            assertThat(group.id()).isEqualTo(groupId);
            assertThat(group.productId()).isEqualTo(productId);
        });
    }

    @Test
    void 重复设备组编码应映射为独立冲突错误码() {
        UUID productId = UUID.randomUUID();
        when(mapper.isActiveProduct(productId)).thenReturn(true);
        doThrow(new DuplicateKeyException("duplicate group key"))
                .when(mapper).insertGroup(any(), eq(productId), eq("haul-team"), eq("运输一组"),
                        eq(800), any(), any());

        assertThatThrownBy(() -> service.createGroup(new DeviceGroupService.CreateGroupCommand(
                productId, "haul-team", "运输一组", 800, null
        ))).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("STRATEGY-409-002"));
    }

    @Test
    void 非启用产品不得创建设备组() {
        UUID productId = UUID.randomUUID();
        when(mapper.isActiveProduct(productId)).thenReturn(false);

        assertThatThrownBy(() -> service.createGroup(new DeviceGroupService.CreateGroupCommand(
                productId, "haul-team", "运输一组", 800, null
        ))).isInstanceOfSatisfying(MineException.class, exception ->
                assertThat(exception.errorCode().code()).isEqualTo("STRATEGY-422-001"));

        verify(mapper, never()).insertGroup(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    void 替换成员应只结束移除项并只新增加入项() {
        UUID productId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID retainedDeviceId = UUID.randomUUID();
        UUID removedDeviceId = UUID.randomUUID();
        UUID addedDeviceId = UUID.randomUUID();
        StrategyScopeMapper.DeviceGroupRecord group = group(
                groupId, productId, "haul-team", "运输一组", 800, "ACTIVE", 2
        );
        when(mapper.findGroupById(groupId)).thenReturn(Optional.of(group));
        when(mapper.findGroupByIdForUpdate(groupId)).thenReturn(Optional.of(group));
        when(mapper.findActiveMemberIds(groupId)).thenReturn(List.of(retainedDeviceId, removedDeviceId));
        when(mapper.countEligibleDevices(productId, List.of(retainedDeviceId, addedDeviceId))).thenReturn(2L);
        when(mapper.touchGroup(groupId, 2, null)).thenReturn(1);

        DeviceGroupService.MembershipChange result = service.replaceMembers(
                groupId, List.of(retainedDeviceId, addedDeviceId), 2
        );

        assertThat(result.addedDeviceIds()).containsExactly(addedDeviceId);
        assertThat(result.removedDeviceIds()).containsExactly(removedDeviceId);
        verify(mapper).closeMemberships(eq(groupId), eq(List.of(removedDeviceId)), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StrategyScopeMapper.MembershipInsert>> insertsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(mapper).insertMemberships(eq(groupId), insertsCaptor.capture(), any());
        assertThat(insertsCaptor.getValue())
                .extracting(StrategyScopeMapper.MembershipInsert::deviceId)
                .containsExactly(addedDeviceId);
        verify(conflictGuard).verifyMembershipChange(group, List.of(addedDeviceId));
        verify(mapper).touchGroup(groupId, 2, null);
        verify(cacheInvalidator).invalidateAfterCommit(
                "ONLINE_STATUS",
                List.of(addedDeviceId, removedDeviceId),
                EffectiveStrategyCacheInvalidator.InvalidationReason.MEMBERSHIP
        );
        verify(runtimeSnapshotService).captureDelta(
                productId, "DATA_QUALITY", List.of(addedDeviceId, removedDeviceId),
                StrategyRuntimeSnapshotService.ChangeReason.MEMBERSHIP
        );
        verify(runtimeSnapshotService).captureDelta(
                productId, "ALARM", List.of(addedDeviceId, removedDeviceId),
                StrategyRuntimeSnapshotService.ChangeReason.MEMBERSHIP
        );
    }

    @Test
    void 跨产品或归档设备不得加入设备组() {
        UUID productId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID validDeviceId = UUID.randomUUID();
        UUID foreignDeviceId = UUID.randomUUID();
        StrategyScopeMapper.DeviceGroupRecord group = group(
                groupId, productId, "haul-team", "运输一组", 800, "ACTIVE", 0
        );
        when(mapper.findGroupById(groupId)).thenReturn(Optional.of(group));
        when(mapper.findGroupByIdForUpdate(groupId)).thenReturn(Optional.of(group));
        when(mapper.findActiveMemberIds(groupId)).thenReturn(List.of());
        when(mapper.countEligibleDevices(productId, List.of(validDeviceId, foreignDeviceId))).thenReturn(1L);

        assertThatThrownBy(() -> service.replaceMembers(
                groupId, List.of(validDeviceId, foreignDeviceId), 0
        )).isInstanceOfSatisfying(MineException.class, exception -> {
            assertThat(exception.errorCode().code()).isEqualTo("STRATEGY-422-002");
            assertThat(exception.details()).containsEntry("requestedCount", 2);
        });

        verify(mapper, never()).closeMemberships(any(), any(), any());
        verify(mapper, never()).insertMemberships(any(), any(), any());
    }

    @Test
    void 归档时应先结束成员关系再归档设备组() {
        UUID productId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        StrategyScopeMapper.DeviceGroupRecord group = group(
                groupId, productId, "haul-team", "运输一组", 800, "ACTIVE", 4
        );
        when(mapper.findGroupById(groupId)).thenReturn(Optional.of(group));
        when(mapper.findGroupByIdForUpdate(groupId)).thenReturn(Optional.of(group));
        UUID memberId = UUID.randomUUID();
        when(mapper.findActiveMemberIds(groupId)).thenReturn(List.of(memberId));
        when(mapper.archiveGroup(groupId, 4, null)).thenReturn(1);

        service.archiveGroup(groupId, 4);

        InOrder order = inOrder(mapper);
        order.verify(mapper).closeAllActiveMemberships(groupId, null);
        order.verify(mapper).archiveGroup(groupId, 4, null);
        verify(cacheInvalidator).invalidateAfterCommit(
                "ONLINE_STATUS",
                List.of(memberId),
                EffectiveStrategyCacheInvalidator.InvalidationReason.ARCHIVE
        );
        verify(runtimeSnapshotService).captureDelta(
                productId, "DATA_QUALITY", List.of(memberId),
                StrategyRuntimeSnapshotService.ChangeReason.GROUP_ARCHIVE
        );
        verify(runtimeSnapshotService).captureDelta(
                productId, "ALARM", List.of(memberId),
                StrategyRuntimeSnapshotService.ChangeReason.GROUP_ARCHIVE
        );
    }

    @Test
    void 更新时乐观锁冲突应返回稳定错误码() {
        UUID productId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        StrategyScopeMapper.DeviceGroupRecord group = group(
                groupId, productId, "haul-team", "运输一组", 800, "ACTIVE", 3
        );
        when(mapper.findGroupById(groupId)).thenReturn(Optional.of(group));
        when(mapper.findGroupByIdForUpdate(groupId)).thenReturn(Optional.of(group));
        when(mapper.findActiveMemberIds(groupId)).thenReturn(List.of());
        when(mapper.updateGroup(groupId, "运输二组", 900, null, 3, null)).thenReturn(0);

        assertThatThrownBy(() -> service.updateGroup(groupId,
                new DeviceGroupService.UpdateGroupCommand("运输二组", 900, null, 3)))
                .isInstanceOfSatisfying(MineException.class, exception ->
                        assertThat(exception.errorCode().code()).isEqualTo("STRATEGY-409-003"));
        verify(conflictGuard).verifyPriorityChange(group, 900, List.of());
    }

    private StrategyScopeMapper.DeviceGroupRecord group(
            UUID id,
            UUID productId,
            String groupKey,
            String name,
            int priority,
            String status,
            long version
    ) {
        Instant now = Instant.parse("2026-07-16T00:00:00Z");
        return new StrategyScopeMapper.DeviceGroupRecord(
                id, productId, groupKey, name, priority, status, null,
                null, now, now, version, 0
        );
    }
}
