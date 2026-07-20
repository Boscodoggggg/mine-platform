import { effectScope, ref, shallowRef } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import type { Alarm, AlarmPage } from '@/modules/alarm/api/alarm';
import type { MineLocationItem } from '@/modules/mine';
import type { ShiftInstance, WorkOrder } from '@/modules/mine/api/operation';
import type { RealtimeOperationNotification } from '@/realtime';
import type { SceneRuntimeLayer, SceneRuntimeSnapshot } from '../api/scene';
import { useSceneBusinessLayers } from './useSceneBusinessLayers';

describe('useSceneBusinessLayers', () => {
  it('只用实时位置映射未关闭告警和运行作业，无位置告警保留在侧栏', async () => {
    const locations = shallowRef([
      location({ tagId: 'tag-1', sourceDeviceId: 'device-1', vehicleId: 'vehicle-1' }),
      location({ tagId: 'tag-2', sourceDeviceId: 'device-2', vehicleId: 'vehicle-2' })
    ]);
    const loadAlarms = vi.fn(async () => alarmPage([alarm('alarm-1', 'device-1'), alarm('alarm-2', 'device-9')]));
    const loadShifts = vi.fn(async () => [shift()]);
    const loadWorkOrders = vi.fn(async () => [workOrder()]);
    const state = effectScope().run(() =>
      useSceneBusinessLayers(locations, {
        loadAlarms,
        loadShifts,
        loadWorkOrders,
        subscribeOperation: () => vi.fn(),
        now: () => new Date('2026-07-20T08:00:00Z')
      })
    )!;

    await state.selectSite(
      'site-1',
      snapshot([
        dynamicLayer('DYNAMIC_ALARMS', { maxItems: 10 }),
        dynamicLayer('DYNAMIC_OPERATIONS', { maxItems: 10 })
      ]),
      { alarms: true, operations: true },
      'Asia/Shanghai'
    );

    expect(loadAlarms).toHaveBeenCalledWith('site-1', 10, expect.any(AbortSignal));
    expect(loadShifts).toHaveBeenCalledWith(
      'site-1',
      '2026-07-19',
      '2026-07-20',
      expect.any(AbortSignal)
    );
    expect(state.alarmMarkers.value).toEqual([
      expect.objectContaining({ alarm: expect.objectContaining({ id: 'alarm-1' }), location: expect.objectContaining({ tagId: 'tag-1' }) })
    ]);
    expect(state.unlocatedAlarms.value).toEqual([expect.objectContaining({ id: 'alarm-2' })]);
    expect(state.operationMarkers.value).toEqual([
      expect.objectContaining({ workOrder: expect.objectContaining({ id: 'work-order-1' }), location: expect.objectContaining({ vehicleId: 'vehicle-1' }) })
    ]);
    expect(state.currentShifts.value).toHaveLength(1);
    expect(state.runningWorkOrders.value).toHaveLength(1);
    expect(state.alarmUpdatedAt.value).toBe('2026-07-20T08:00:00.000Z');
    expect(state.operationUpdatedAt.value).toBe('2026-07-20T08:00:00.000Z');
  });

  it('未发布图层或无对应权限时不调用受权业务接口', async () => {
    const loadAlarms = vi.fn();
    const loadShifts = vi.fn();
    const loadWorkOrders = vi.fn();
    const subscribeOperation = vi.fn();
    const state = effectScope().run(() =>
      useSceneBusinessLayers(shallowRef([]), { loadAlarms, loadShifts, loadWorkOrders, subscribeOperation })
    )!;

    await state.selectSite(
      'site-1',
      snapshot([dynamicLayer('DYNAMIC_ALARMS'), dynamicLayer('DYNAMIC_OPERATIONS')]),
      { alarms: false, operations: false },
      'Asia/Shanghai'
    );

    expect(loadAlarms).not.toHaveBeenCalled();
    expect(loadShifts).not.toHaveBeenCalled();
    expect(loadWorkOrders).not.toHaveBeenCalled();
    expect(subscribeOperation).not.toHaveBeenCalled();
    expect(state.alarmStatus.value).toBe('DISABLED');
    expect(state.operationStatus.value).toBe('DISABLED');
  });

  it('业务图层上限应收紧到一万个对象避免无界分页', async () => {
    const loadAlarms = vi.fn(async () => alarmPage([]));
    const state = effectScope().run(() =>
      useSceneBusinessLayers(shallowRef([]), {
        loadAlarms,
        loadShifts: vi.fn(),
        loadWorkOrders: vi.fn(),
        subscribeOperation: vi.fn()
      })
    )!;

    await state.selectSite(
      'site-1',
      snapshot([dynamicLayer('DYNAMIC_ALARMS', { maxItems: 100_000 })]),
      { alarms: true, operations: false },
      'Asia/Shanghai'
    );

    expect(loadAlarms).toHaveBeenCalledWith('site-1', 10_000, expect.any(AbortSignal));
  });

  it('告警快照失败只降级告警层，运营快照和定位不受影响', async () => {
    const locations = shallowRef([location()]);
    const state = effectScope().run(() =>
      useSceneBusinessLayers(locations, {
        loadAlarms: vi.fn().mockRejectedValue(new Error('alarm unavailable')),
        loadShifts: vi.fn(async () => [shift()]),
        loadWorkOrders: vi.fn(async () => [workOrder()]),
        subscribeOperation: () => vi.fn()
      })
    )!;

    await state.selectSite(
      'site-1',
      snapshot([dynamicLayer('DYNAMIC_ALARMS'), dynamicLayer('DYNAMIC_OPERATIONS')]),
      { alarms: true, operations: true },
      'Asia/Shanghai'
    );

    expect(state.alarmStatus.value).toBe('DEGRADED');
    expect(state.operationStatus.value).toBe('READY');
    expect(state.runningWorkOrders.value).toHaveLength(1);
    expect(locations.value).toHaveLength(1);
  });

  it('运营增量按 businessSequence 去重，缺口会显式回查快照', async () => {
    let listener!: (notification: RealtimeOperationNotification) => void;
    const loadShifts = vi.fn(async () => [shift()]);
    const loadWorkOrders = vi.fn(async () => [workOrder()]);
    const state = effectScope().run(() =>
      useSceneBusinessLayers(shallowRef([location()]), {
        loadAlarms: vi.fn(async () => alarmPage([])),
        loadShifts,
        loadWorkOrders,
        subscribeOperation: (_siteId, callback) => {
          listener = callback;
          return vi.fn();
        }
      })
    )!;
    await state.selectSite(
      'site-1',
      snapshot([dynamicLayer('DYNAMIC_OPERATIONS')]),
      { alarms: false, operations: true },
      'Asia/Shanghai'
    );
    listener({ mode: 'SUBSCRIBED', siteId: 'site-1' });
    listener({ mode: 'UPDATE', siteId: 'site-1', item: operationEvent(10) });
    await vi.waitFor(() => expect(loadWorkOrders).toHaveBeenCalledTimes(2));

    listener({ mode: 'UPDATE', siteId: 'site-1', item: operationEvent(10) });
    await Promise.resolve();
    expect(loadWorkOrders).toHaveBeenCalledTimes(2);

    listener({
      mode: 'RESYNC_REQUIRED',
      siteId: 'site-1',
      expectedBusinessSequence: 11,
      receivedBusinessSequence: 12
    });
    expect(state.operationStatus.value).toBe('RESYNCING');
    await vi.waitFor(() => expect(loadWorkOrders).toHaveBeenCalledTimes(3));
    expect(state.operationBusinessSequence.value).toBe(12);
    expect(state.operationStatus.value).toBe('LIVE');

    listener({ mode: 'UPDATE', siteId: 'site-1', item: operationEvent(13) });
    expect(state.operationStatus.value).toBe('LIVE');
    await vi.waitFor(() => expect(loadWorkOrders).toHaveBeenCalledTimes(4));
    expect(state.operationBusinessSequence.value).toBe(13);
  });

  it('回查期间又到达的新序列不得被旧快照错误确认', async () => {
    let listener!: (notification: RealtimeOperationNotification) => void;
    const gapSnapshot = deferred<WorkOrder[]>();
    const followUpSnapshot = deferred<WorkOrder[]>();
    const loadWorkOrders = vi
      .fn()
      .mockResolvedValueOnce([workOrder()])
      .mockResolvedValueOnce([workOrder()])
      .mockImplementationOnce(() => gapSnapshot.promise)
      .mockImplementationOnce(() => followUpSnapshot.promise);
    const state = effectScope().run(() =>
      useSceneBusinessLayers(shallowRef([location()]), {
        loadAlarms: vi.fn(async () => alarmPage([])),
        loadShifts: vi.fn(async () => [shift()]),
        loadWorkOrders,
        subscribeOperation: (_siteId, callback) => {
          listener = callback;
          return vi.fn();
        }
      })
    )!;
    await state.selectSite(
      'site-1',
      snapshot([dynamicLayer('DYNAMIC_OPERATIONS')]),
      { alarms: false, operations: true },
      'Asia/Shanghai'
    );
    listener({ mode: 'SUBSCRIBED', siteId: 'site-1' });
    listener({ mode: 'UPDATE', siteId: 'site-1', item: operationEvent(10) });
    await vi.waitFor(() => expect(loadWorkOrders).toHaveBeenCalledTimes(2));

    listener({
      mode: 'RESYNC_REQUIRED',
      siteId: 'site-1',
      expectedBusinessSequence: 11,
      receivedBusinessSequence: 12
    });
    await vi.waitFor(() => expect(loadWorkOrders).toHaveBeenCalledTimes(3));
    listener({ mode: 'UPDATE', siteId: 'site-1', item: operationEvent(13) });
    gapSnapshot.resolve([workOrder()]);

    await vi.waitFor(() => expect(loadWorkOrders).toHaveBeenCalledTimes(4));
    expect(state.operationStatus.value).toBe('RESYNCING');
    followUpSnapshot.reject(new Error('follow-up unavailable'));
    await vi.waitFor(() => expect(state.operationStatus.value).toBe('DEGRADED'));
    expect(state.operationBusinessSequence.value).toBe(12);
    expect(state.pendingOperationBusinessSequence.value).toBe(13);
  });

  it('重连触发的无水位回查期间应冻结新增序列直到后续快照确认', async () => {
    let listener!: (notification: RealtimeOperationNotification) => void;
    const reconnectSnapshot = deferred<WorkOrder[]>();
    const followUpSnapshot = deferred<WorkOrder[]>();
    const loadWorkOrders = vi
      .fn()
      .mockResolvedValueOnce([workOrder()])
      .mockResolvedValueOnce([workOrder()])
      .mockImplementationOnce(() => reconnectSnapshot.promise)
      .mockImplementationOnce(() => followUpSnapshot.promise);
    const state = effectScope().run(() =>
      useSceneBusinessLayers(shallowRef([location()]), {
        loadAlarms: vi.fn(async () => alarmPage([])),
        loadShifts: vi.fn(async () => [shift()]),
        loadWorkOrders,
        subscribeOperation: (_siteId, callback) => {
          listener = callback;
          return vi.fn();
        }
      })
    )!;
    await state.selectSite(
      'site-1',
      snapshot([dynamicLayer('DYNAMIC_OPERATIONS')]),
      { alarms: false, operations: true },
      'Asia/Shanghai'
    );
    listener({ mode: 'SUBSCRIBED', siteId: 'site-1' });
    listener({ mode: 'UPDATE', siteId: 'site-1', item: operationEvent(10) });
    await vi.waitFor(() => expect(loadWorkOrders).toHaveBeenCalledTimes(2));

    listener({ mode: 'RESYNC_REQUIRED', siteId: 'site-1' });
    await vi.waitFor(() => expect(loadWorkOrders).toHaveBeenCalledTimes(3));
    listener({ mode: 'UPDATE', siteId: 'site-1', item: operationEvent(11) });
    listener({ mode: 'UPDATE', siteId: 'site-1', item: operationEvent(12) });
    reconnectSnapshot.resolve([workOrder()]);

    await vi.waitFor(() => expect(loadWorkOrders).toHaveBeenCalledTimes(4));
    expect(state.operationStatus.value).toBe('RESYNCING');
    expect(state.pendingOperationBusinessSequence.value).toBe(12);
    followUpSnapshot.resolve([workOrder()]);
    await vi.waitFor(() => expect(state.operationStatus.value).toBe('LIVE'));
    expect(state.operationBusinessSequence.value).toBe(12);
    expect(state.pendingOperationBusinessSequence.value).toBeNull();
  });

  it('运营实时订阅失败只降级运营层并保留快照', async () => {
    let listener!: (notification: RealtimeOperationNotification) => void;
    const state = effectScope().run(() =>
      useSceneBusinessLayers(shallowRef([location()]), {
        loadAlarms: vi.fn(async () => alarmPage([alarm('alarm-1', 'device-1')])),
        loadShifts: vi.fn(async () => [shift()]),
        loadWorkOrders: vi.fn(async () => [workOrder()]),
        subscribeOperation: (_siteId, callback) => {
          listener = callback;
          return vi.fn();
        }
      })
    )!;
    await state.selectSite(
      'site-1',
      snapshot([dynamicLayer('DYNAMIC_ALARMS'), dynamicLayer('DYNAMIC_OPERATIONS')]),
      { alarms: true, operations: true },
      'Asia/Shanghai'
    );

    listener({ mode: 'SUBSCRIPTION_FAILED', siteId: 'site-1', code: 'REALTIME-LIMIT-EXCEEDED' });

    expect(state.operationStatus.value).toBe('DEGRADED');
    expect(state.runningWorkOrders.value).toHaveLength(1);
    expect(state.alarmStatus.value).toBe('READY');
    expect(state.alarmMarkers.value).toHaveLength(1);
  });

  it('同矿区业务刷新失败保留各层最后正确数据和时间', async () => {
    const loadAlarms = vi
      .fn()
      .mockResolvedValueOnce(alarmPage([alarm('alarm-1', 'device-1')]))
      .mockRejectedValueOnce(new Error('alarm unavailable'));
    const loadShifts = vi.fn().mockResolvedValueOnce([shift()]).mockRejectedValueOnce(new Error('shift unavailable'));
    const loadWorkOrders = vi.fn().mockResolvedValueOnce([workOrder()]).mockRejectedValueOnce(new Error('order unavailable'));
    const state = effectScope().run(() =>
      useSceneBusinessLayers(shallowRef([location()]), {
        loadAlarms,
        loadShifts,
        loadWorkOrders,
        subscribeOperation: () => vi.fn(),
        now: () => new Date('2026-07-20T08:00:00Z')
      })
    )!;
    const scene = snapshot([dynamicLayer('DYNAMIC_ALARMS'), dynamicLayer('DYNAMIC_OPERATIONS')]);
    await state.selectSite('site-1', scene, { alarms: true, operations: true }, 'Asia/Shanghai');
    await state.selectSite('site-1', scene, { alarms: true, operations: true }, 'Asia/Shanghai');

    expect(state.alarmStatus.value).toBe('DEGRADED');
    expect(state.operationStatus.value).toBe('DEGRADED');
    expect(state.activeAlarms.value).toHaveLength(1);
    expect(state.currentShifts.value).toHaveLength(1);
    expect(state.runningWorkOrders.value).toHaveLength(1);
    expect(state.alarmUpdatedAt.value).toBe('2026-07-20T08:00:00.000Z');
    expect(state.operationUpdatedAt.value).toBe('2026-07-20T08:00:00.000Z');
  });

  it('快速切换矿区时丢弃旧矿区迟到快照并释放旧订阅', async () => {
    const oldAlarms = deferred<AlarmPage>();
    const oldShifts = deferred<ShiftInstance[]>();
    const oldOrders = deferred<WorkOrder[]>();
    const releaseSiteOne = vi.fn();
    const locations = shallowRef([location({ siteId: 'site-1' })]);
    const loadAlarms = vi
      .fn()
      .mockImplementationOnce(() => oldAlarms.promise)
      .mockResolvedValueOnce(alarmPage([alarm('alarm-site-2', 'device-2')]));
    const loadShifts = vi.fn((siteId: string) =>
      siteId === 'site-1' ? oldShifts.promise : Promise.resolve([{ ...shift(), id: 'shift-2', siteId: 'site-2' }])
    );
    const loadWorkOrders = vi.fn((siteId: string) =>
      siteId === 'site-1'
        ? oldOrders.promise
        : Promise.resolve([{ ...workOrder(), id: 'work-order-2', siteId: 'site-2' }])
    );
    const state = effectScope().run(() =>
      useSceneBusinessLayers(locations, {
        loadAlarms,
        loadShifts,
        loadWorkOrders,
        subscribeOperation: siteId => (siteId === 'site-1' ? releaseSiteOne : vi.fn())
      })
    )!;
    const scene = snapshot([dynamicLayer('DYNAMIC_ALARMS'), dynamicLayer('DYNAMIC_OPERATIONS')]);

    const oldSelection = state.selectSite(
      'site-1',
      scene,
      { alarms: true, operations: true },
      'Asia/Shanghai'
    );
    locations.value = [
      location({
        eventId: 'location-2',
        siteId: 'site-2',
        sourceDeviceId: 'device-2',
        tagId: 'tag-2',
        vehicleId: 'vehicle-2'
      })
    ];
    await state.selectSite('site-2', scene, { alarms: true, operations: true }, 'Asia/Shanghai');

    oldAlarms.resolve(alarmPage([alarm('alarm-site-1', 'device-1')]));
    oldShifts.resolve([shift()]);
    oldOrders.resolve([workOrder()]);
    await oldSelection;

    expect(releaseSiteOne).toHaveBeenCalledOnce();
    expect(state.selectedSiteId.value).toBe('site-2');
    expect(state.activeAlarms.value).toEqual([expect.objectContaining({ id: 'alarm-site-2' })]);
    expect(state.currentShifts.value).toEqual([expect.objectContaining({ id: 'shift-2', siteId: 'site-2' })]);
    expect(state.runningWorkOrders.value).toEqual([
      expect.objectContaining({ id: 'work-order-2', siteId: 'site-2' })
    ]);
  });

  it('未选择矿区时已发布业务层保持空闲而不是持续加载', async () => {
    const state = effectScope().run(() => useSceneBusinessLayers(shallowRef([])))!;

    await state.selectSite(
      null,
      snapshot([dynamicLayer('DYNAMIC_ALARMS'), dynamicLayer('DYNAMIC_OPERATIONS')]),
      { alarms: true, operations: true },
      'Asia/Shanghai'
    );

    expect(state.alarmStatus.value).toBe('IDLE');
    expect(state.operationStatus.value).toBe('IDLE');
    expect(state.loading.value).toBe(false);
  });

  it('位置映射未就绪或快照截断时不得把未匹配告警断言为无位置', async () => {
    const mappingStatus = ref<'TRUNCATED' | 'READY'>('TRUNCATED');
    const state = effectScope().run(() =>
      useSceneBusinessLayers(shallowRef([location()]), {
        loadAlarms: vi.fn(async () => alarmPage([alarm('alarm-1', 'device-1'), alarm('alarm-2', 'device-2')])),
        loadShifts: vi.fn(),
        loadWorkOrders: vi.fn(),
        subscribeOperation: vi.fn(),
        locationMappingStatus: mappingStatus
      })
    )!;

    await state.selectSite(
      'site-1',
      snapshot([dynamicLayer('DYNAMIC_ALARMS')]),
      { alarms: true, operations: false },
      'Asia/Shanghai'
    );

    expect(state.alarmMarkers.value).toHaveLength(1);
    expect(state.unlocatedAlarms.value).toEqual([]);
    expect(state.unresolvedAlarmLocations.value).toEqual([expect.objectContaining({ id: 'alarm-2' })]);

    mappingStatus.value = 'READY';
    expect(state.unresolvedAlarmLocations.value).toEqual([]);
    expect(state.unlocatedAlarms.value).toEqual([expect.objectContaining({ id: 'alarm-2' })]);
  });

  it('运营回查跨矿区本地午夜时重新计算前一日到当日班次窗口', async () => {
    let listener!: (notification: RealtimeOperationNotification) => void;
    let currentTime = new Date('2026-07-20T15:59:00Z');
    const loadShifts = vi.fn(async () => [shift()]);
    const state = effectScope().run(() =>
      useSceneBusinessLayers(shallowRef([location()]), {
        loadAlarms: vi.fn(),
        loadShifts,
        loadWorkOrders: vi.fn(async () => [workOrder()]),
        subscribeOperation: (_siteId, callback) => {
          listener = callback;
          return vi.fn();
        },
        now: () => currentTime
      })
    )!;
    await state.selectSite(
      'site-1',
      snapshot([dynamicLayer('DYNAMIC_OPERATIONS')]),
      { alarms: false, operations: true },
      'Asia/Shanghai'
    );
    expect(loadShifts).toHaveBeenLastCalledWith(
      'site-1',
      '2026-07-19',
      '2026-07-20',
      expect.any(AbortSignal)
    );

    currentTime = new Date('2026-07-20T16:01:00Z');
    listener({ mode: 'UPDATE', siteId: 'site-1', item: operationEvent(1) });
    await vi.waitFor(() => expect(loadShifts).toHaveBeenCalledTimes(2));

    expect(loadShifts).toHaveBeenLastCalledWith(
      'site-1',
      '2026-07-20',
      '2026-07-21',
      expect.any(AbortSignal)
    );
  });
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
}

function alarmPage(items: Alarm[]): AlarmPage {
  return { items, page: 1, size: 200, total: items.length };
}

function alarm(id: string, deviceId: string): Alarm {
  return {
    id,
    deviceId,
    deviceCode: deviceId.toUpperCase(),
    deviceName: deviceId,
    productKey: 'haul-truck',
    ruleKey: 'overspeed',
    ruleName: '超速告警',
    sourceType: 'NUMERIC',
    severity: 'CRITICAL',
    propertyId: 'speed',
    status: 'ACTIVE_UNACKNOWLEDGED',
    policyRuntimeRevision: 1,
    policySnapshotId: 'policy-1',
    policyContentHash: `sha256:${'a'.repeat(64)}`,
    raisedAt: '2026-07-20T07:30:00Z',
    raisedEvidence: {},
    recoveredAt: null,
    recoveredEvidence: {},
    recoveryReason: null,
    acknowledgedAt: null,
    acknowledgedBy: null,
    acknowledgementNote: null,
    closedAt: null,
    closedBy: null,
    closeNote: null,
    version: 0
  };
}

function shift(): ShiftInstance {
  return {
    id: 'shift-1',
    organizationId: 'organization-1',
    siteId: 'site-1',
    shiftDefinitionId: 'definition-1',
    businessDate: '2026-07-20',
    plannedStartAt: '2026-07-20T00:00:00Z',
    plannedEndAt: '2026-07-20T08:00:00Z',
    actualStartAt: '2026-07-20T00:00:00Z',
    actualEndAt: null,
    supervisorPersonId: null,
    definitionSnapshot: { name: '早班' },
    status: 'OPEN',
    openedAt: '2026-07-20T00:00:00Z',
    closedAt: null,
    cancelledAt: null,
    cancellationReason: null,
    createdAt: '2026-07-19T23:00:00Z',
    updatedAt: '2026-07-20T00:00:00Z',
    version: 1
  };
}

function workOrder(): WorkOrder {
  return {
    id: 'work-order-1',
    organizationId: 'organization-1',
    siteId: 'site-1',
    shiftInstanceId: 'shift-1',
    orderNumber: 'WO-001',
    title: '北区运输',
    workType: 'HAULAGE',
    plannedStartAt: '2026-07-20T00:00:00Z',
    plannedEndAt: '2026-07-20T08:00:00Z',
    plannedCycles: 20,
    plannedTons: 600,
    priority: 50,
    routeVersionId: null,
    loadGeofenceVersionId: null,
    unloadGeofenceVersionId: null,
    releaseSnapshot: {},
    status: 'RUNNING',
    releasedAt: '2026-07-19T23:30:00Z',
    startedAt: '2026-07-20T00:00:00Z',
    pausedAt: null,
    completedAt: null,
    cancelledAt: null,
    abortedAt: null,
    terminalReason: null,
    createdAt: '2026-07-19T23:00:00Z',
    updatedAt: '2026-07-20T00:00:00Z',
    version: 2,
    assignments: [
      {
        id: 'assignment-1',
        vehicleId: 'vehicle-1',
        vehicleCode: 'TRUCK-001',
        vehicleName: '矿卡 1',
        vehicleType: 'HAUL_TRUCK',
        driverPersonId: null,
        driverName: null,
        validFrom: '2026-07-20T00:00:00Z',
        validTo: '2026-07-20T08:00:00Z',
        status: 'ACTIVE',
        assignmentReason: '计划分配',
        createdAt: '2026-07-19T23:30:00Z',
        version: 0
      }
    ]
  };
}

function location(overrides: Partial<MineLocationItem> = {}): MineLocationItem {
  return {
    eventId: 'location-1',
    organizationId: 'organization-1',
    siteId: 'site-1',
    sourceDeviceId: 'device-1',
    tagId: 'tag-1',
    tagCode: 'TAG-001',
    coordinateSystemId: 'coordinate-1',
    vehicleId: 'vehicle-1',
    personId: null,
    ingestSequence: 10,
    occurredAt: '2026-07-20T07:59:00Z',
    receivedAt: '2026-07-20T07:59:01Z',
    localX: 100,
    localY: 200,
    localZ: 5,
    speedMetersPerSecond: 3,
    headingDegrees: 90,
    accuracyMeters: 1,
    qualityStatus: 'GOOD',
    qualityReason: null,
    ...overrides
  };
}

function operationEvent(businessSequence: number) {
  return {
    eventId: `operation-${businessSequence}`,
    organizationId: 'organization-1',
    siteId: 'site-1',
    aggregateType: 'WORK_ORDER' as const,
    aggregateId: 'work-order-1',
    updateType: 'RUNTIME_TRANSITIONED' as const,
    businessSequence,
    occurredAt: `2026-07-20T07:59:${String(businessSequence).padStart(2, '0')}Z`,
    summary: { status: 'RUNNING' }
  };
}

function dynamicLayer(
  layerType: SceneRuntimeLayer['layerType'],
  overrides: Partial<SceneRuntimeLayer> = {}
): SceneRuntimeLayer {
  return {
    id: crypto.randomUUID(),
    layerCode: layerType.toLowerCase(),
    orderNumber: 1,
    layerType,
    visible: true,
    opacity: 1,
    minimumDistance: 0,
    maximumDistance: 100_000,
    offsetX: 0,
    offsetY: 0,
    offsetZ: 0,
    headingDegrees: 0,
    pitchDegrees: 0,
    rollDegrees: 0,
    scale: 1,
    maxItems: 100,
    options: {},
    asset: null,
    ...overrides
  };
}

function snapshot(layers: SceneRuntimeLayer[]): SceneRuntimeSnapshot {
  return {
    sceneId: crypto.randomUUID(),
    sceneCode: 'north-pit',
    sceneName: '北采区',
    versionId: crypto.randomUUID(),
    revisionNumber: 1,
    schemaVersion: 1,
    contentHash: `sha256:${'b'.repeat(64)}`,
    coordinateSystemId: crypto.randomUUID(),
    coordinateMode: 'LOCAL_ENU',
    originX: 0,
    originY: 0,
    originZ: 0,
    headingDegrees: 0,
    pitchDegrees: 0,
    rollDegrees: 0,
    scale: 1,
    defaultCamera: {},
    fallbackViewport: {},
    publishedAt: '2026-07-20T08:00:00Z',
    layerCount: layers.length,
    layers
  };
}
