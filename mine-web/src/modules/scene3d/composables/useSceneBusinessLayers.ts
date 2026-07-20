import { computed, onScopeDispose, ref, shallowRef } from 'vue';
import type { Ref } from 'vue';
import { getMineSiteAlarmsUpTo } from '@/modules/alarm/api/alarm';
import type { Alarm, AlarmPage } from '@/modules/alarm/api/alarm';
import type { MineLocationItem } from '@/modules/mine';
import { getShiftInstances, getWorkOrders } from '@/modules/mine/api/operation';
import type { ShiftInstance, WorkOrder } from '@/modules/mine/api/operation';
import { realtimeHub } from '@/realtime';
import type { RealtimeOperationListener, RealtimeOperationNotification } from '@/realtime';
import type { SceneRuntimeLayer, SceneRuntimeSnapshot } from '../api/scene';
import type { SceneLocationMappingStatus } from './useSceneRealtime';

export type SceneBusinessLayerStatus = 'DISABLED' | 'IDLE' | 'LOADING' | 'READY' | 'LIVE' | 'RESYNCING' | 'DEGRADED';
const MAX_BUSINESS_LAYER_ITEMS = 10_000;

export interface SceneBusinessAccess {
  alarms: boolean;
  operations: boolean;
}

export interface SceneAlarmMarker {
  alarm: Alarm;
  location: MineLocationItem;
}

export interface SceneOperationMarker {
  markerId: string;
  workOrder: WorkOrder;
  assignment: WorkOrder['assignments'][number];
  location: MineLocationItem;
}

interface SceneBusinessDependencies {
  loadAlarms: (siteId: string, limit: number, signal: AbortSignal) => Promise<AlarmPage>;
  loadShifts: (
    siteId: string,
    fromDate: string,
    toDate: string,
    signal: AbortSignal
  ) => Promise<ShiftInstance[]>;
  loadWorkOrders: (siteId: string, limit: number, signal: AbortSignal) => Promise<WorkOrder[]>;
  subscribeOperation: (siteId: string, listener: RealtimeOperationListener) => () => void;
  now: () => Date;
  locationMappingStatus: Readonly<Ref<SceneLocationMappingStatus>>;
}

/**
 * 将受权告警和生产运营快照投影到现有实时位置，不读取无权限设备全集。
 * 各业务层独立保留最后正确数据，运营增量按业务序号去重并在缺口后回查快照。
 */
export function useSceneBusinessLayers(
  locations: Readonly<Ref<readonly MineLocationItem[]>>,
  dependencies: Partial<SceneBusinessDependencies> = {}
) {
  const runtime: SceneBusinessDependencies = {
    loadAlarms:
      dependencies.loadAlarms ??
      ((siteId, limit, signal) =>
        getMineSiteAlarmsUpTo(siteId, { closed: false }, limit, signal)),
    loadShifts:
      dependencies.loadShifts ??
      ((siteId, fromDate, toDate, signal) =>
        getShiftInstances(
          siteId,
          { fromDate, toDate, status: 'OPEN', limit: 100 },
          signal
        )),
    loadWorkOrders:
      dependencies.loadWorkOrders ??
      ((siteId, limit, signal) =>
        getWorkOrders(siteId, { status: 'RUNNING', limit: Math.min(limit, 100) }, signal)),
    subscribeOperation:
      dependencies.subscribeOperation ??
      ((siteId, listener) => realtimeHub.subscribeMineOperation(siteId, listener)),
    now: dependencies.now ?? (() => new Date()),
    locationMappingStatus: dependencies.locationMappingStatus ?? ref<SceneLocationMappingStatus>('READY')
  };
  const selectedSiteId = ref<string | null>(null);
  const selectedTimezone = ref<string | null>(null);
  const alarmLayer = shallowRef<SceneRuntimeLayer | null>(null);
  const operationLayer = shallowRef<SceneRuntimeLayer | null>(null);
  const alarms = shallowRef<Alarm[]>([]);
  const shifts = shallowRef<ShiftInstance[]>([]);
  const workOrders = shallowRef<WorkOrder[]>([]);
  const alarmStatus = ref<SceneBusinessLayerStatus>('IDLE');
  const operationStatus = ref<SceneBusinessLayerStatus>('IDLE');
  const alarmError = shallowRef<unknown | null>(null);
  const operationError = shallowRef<unknown | null>(null);
  const alarmUpdatedAt = ref<string | null>(null);
  const operationUpdatedAt = ref<string | null>(null);
  const operationBusinessSequence = ref<number | null>(null);
  const pendingOperationBusinessSequence = ref<number | null>(null);
  let generation = 0;
  let controller: AbortController | null = null;
  let releaseOperation: (() => void) | null = null;
  let operationSubscribed = false;
  let operationSubscriptionFailureCode: string | null = null;
  let operationSnapshotFailure: unknown | null = null;
  let operationResyncVersion = 0;
  let confirmedOperationResyncVersion = 0;
  let operationRefresh: { generation: number; requested: boolean; promise: Promise<void> } | null = null;

  const activeAlarms = computed(() => {
    const layer = alarmLayer.value;
    if (!layer) return [];
    return alarms.value
      .filter(alarm => alarm.status !== 'CLOSED' && !alarm.closedAt)
      .slice(0, boundedItems(layer.maxItems));
  });
  const alarmMarkers = computed<SceneAlarmMarker[]>(() => {
    const locationsByDevice = latestLocationsBy(locations.value, item => item.sourceDeviceId);
    return activeAlarms.value.flatMap(alarm => {
      const location = locationsByDevice.get(alarm.deviceId);
      return location ? [{ alarm, location }] : [];
    });
  });
  const unlocatedAlarms = computed(() => {
    const locatedIds = new Set(alarmMarkers.value.map(marker => marker.alarm.id));
    return runtime.locationMappingStatus.value === 'READY'
      ? activeAlarms.value.filter(alarm => !locatedIds.has(alarm.id))
      : [];
  });
  const unresolvedAlarmLocations = computed(() => {
    if (runtime.locationMappingStatus.value === 'READY') return [];
    const locatedIds = new Set(alarmMarkers.value.map(marker => marker.alarm.id));
    return activeAlarms.value.filter(alarm => !locatedIds.has(alarm.id));
  });
  const currentShifts = computed(() => {
    const layer = operationLayer.value;
    return layer ? shifts.value.filter(shift => shift.status === 'OPEN').slice(0, boundedItems(layer.maxItems)) : [];
  });
  const runningWorkOrders = computed(() => {
    const layer = operationLayer.value;
    return layer
      ? workOrders.value.filter(order => order.status === 'RUNNING').slice(0, boundedItems(layer.maxItems))
      : [];
  });
  const operationMarkers = computed<SceneOperationMarker[]>(() => {
    const layer = operationLayer.value;
    if (!layer) return [];
    const locationsByVehicle = latestLocationsBy(
      locations.value.filter(item => item.vehicleId),
      item => item.vehicleId!
    );
    return runningWorkOrders.value
      .flatMap(workOrder =>
        workOrder.assignments
          .filter(assignment => assignment.status === 'ACTIVE')
          .flatMap(assignment => {
            const location = locationsByVehicle.get(assignment.vehicleId);
            return location
              ? [{ markerId: `${workOrder.id}:${assignment.id}`, workOrder, assignment, location }]
              : [];
          })
      )
      .slice(0, boundedItems(layer.maxItems));
  });
  const loading = computed(
    () => alarmStatus.value === 'LOADING' || operationStatus.value === 'LOADING' || operationStatus.value === 'RESYNCING'
  );

  async function selectSite(
    siteId: string | null,
    snapshot: SceneRuntimeSnapshot | null,
    access: SceneBusinessAccess,
    timezone?: string | null
  ) {
    const normalizedSiteId = siteId?.trim() || null;
    const sameSite = normalizedSiteId !== null && normalizedSiteId === selectedSiteId.value;
    const currentGeneration = ++generation;
    controller?.abort();
    controller = null;
    releaseOperationSubscription();
    selectedSiteId.value = normalizedSiteId;
    selectedTimezone.value = timezone?.trim() || null;
    alarmLayer.value = access.alarms ? visibleLayer(snapshot, 'DYNAMIC_ALARMS') : null;
    operationLayer.value = access.operations ? visibleLayer(snapshot, 'DYNAMIC_OPERATIONS') : null;
    if (!sameSite || !alarmLayer.value) {
      alarms.value = [];
      alarmUpdatedAt.value = null;
    }
    if (!sameSite || !operationLayer.value) {
      shifts.value = [];
      workOrders.value = [];
      operationUpdatedAt.value = null;
      operationBusinessSequence.value = null;
      pendingOperationBusinessSequence.value = null;
    }
    alarmError.value = null;
    operationError.value = null;
    operationSnapshotFailure = null;
    alarmStatus.value = alarmLayer.value ? (normalizedSiteId ? 'LOADING' : 'IDLE') : 'DISABLED';
    operationStatus.value = operationLayer.value ? (normalizedSiteId ? 'LOADING' : 'IDLE') : 'DISABLED';
    if (!normalizedSiteId) return;

    const nextController = new AbortController();
    controller = nextController;
    const jobs: Promise<void>[] = [];
    if (alarmLayer.value) jobs.push(loadAlarmSnapshot(normalizedSiteId, currentGeneration, nextController.signal));
    if (operationLayer.value) {
      operationBusinessSequence.value = null;
      pendingOperationBusinessSequence.value = null;
      operationResyncVersion = 0;
      confirmedOperationResyncVersion = 0;
      try {
        releaseOperation = runtime.subscribeOperation(normalizedSiteId, notification => {
          handleOperationNotification(notification, normalizedSiteId, currentGeneration);
        });
      } catch (cause) {
        operationSubscriptionFailureCode = 'REALTIME-CLIENT-SUBSCRIPTION-FAILED';
        operationError.value = cause;
      }
      jobs.push(requestOperationRefresh(currentGeneration));
    }
    await Promise.all(jobs);
  }

  async function loadAlarmSnapshot(siteId: string, currentGeneration: number, signal: AbortSignal) {
    const layer = alarmLayer.value;
    if (!layer) return;
    try {
      const response = await runtime.loadAlarms(siteId, boundedItems(layer.maxItems), signal);
      if (!isCurrent(siteId, currentGeneration, signal)) return;
      alarms.value = response.items.filter(alarm => alarm.status !== 'CLOSED' && !alarm.closedAt);
      alarmUpdatedAt.value = runtime.now().toISOString();
      alarmError.value = null;
      alarmStatus.value = 'READY';
    } catch (cause) {
      if (!isCurrent(siteId, currentGeneration, signal)) return;
      alarmError.value = cause;
      alarmStatus.value = 'DEGRADED';
    }
  }

  function handleOperationNotification(
    notification: RealtimeOperationNotification,
    siteId: string,
    currentGeneration: number
  ) {
    if (notification.siteId !== siteId || currentGeneration !== generation || selectedSiteId.value !== siteId) return;
    if (notification.mode === 'SUBSCRIPTION_FAILED') {
      operationSubscribed = false;
      operationSubscriptionFailureCode = notification.code;
      operationError.value = new Error(`运营实时订阅失败: ${notification.code}`);
      operationStatus.value = 'DEGRADED';
      return;
    }
    if (notification.mode === 'SUBSCRIBED') {
      operationSubscribed = true;
      operationSubscriptionFailureCode = null;
      operationError.value = operationSnapshotFailure;
      if (operationSnapshotFailure) operationStatus.value = 'DEGRADED';
      else if (operationUpdatedAt.value) operationStatus.value = 'LIVE';
      return;
    }
    if (notification.mode === 'RESYNC_REQUIRED') {
      const receivedSequence = positiveSequence(notification.receivedBusinessSequence);
      if (receivedSequence !== null) {
        pendingOperationBusinessSequence.value = Math.max(
          pendingOperationBusinessSequence.value ?? 0,
          receivedSequence
        );
      } else {
        operationResyncVersion += 1;
      }
      operationStatus.value = 'RESYNCING';
      void requestOperationRefresh(currentGeneration);
      return;
    }
    const sequence = notification.item.businessSequence;
    const baseline = pendingOperationBusinessSequence.value ?? operationBusinessSequence.value;
    if (baseline !== null && sequence <= baseline) return;
    if (
      pendingOperationBusinessSequence.value !== null ||
      operationResyncVersion > confirmedOperationResyncVersion ||
      (baseline !== null && sequence !== baseline + 1)
    ) {
      pendingOperationBusinessSequence.value = sequence;
      operationStatus.value = 'RESYNCING';
    } else {
      operationBusinessSequence.value = sequence;
    }
    void requestOperationRefresh(currentGeneration);
  }

  function requestOperationRefresh(currentGeneration: number) {
    if (operationRefresh?.generation === currentGeneration) {
      operationRefresh.requested = true;
      return operationRefresh.promise;
    }
    const state = {
      generation: currentGeneration,
      requested: true,
      promise: Promise.resolve()
    };
    const refresh = (async () => {
      while (state.requested) {
        state.requested = false;
        const siteId = selectedSiteId.value;
        const signal = controller?.signal;
        if (!siteId || !signal || currentGeneration !== generation || signal.aborted) break;
        await loadOperationSnapshot(siteId, currentGeneration, signal);
      }
    })();
    state.promise = refresh;
    operationRefresh = state;
    void refresh.finally(() => {
      if (operationRefresh === state) operationRefresh = null;
    });
    return refresh;
  }

  async function loadOperationSnapshot(
    siteId: string,
    currentGeneration: number,
    signal: AbortSignal
  ) {
    const layer = operationLayer.value;
    if (!layer) return;
    const capturedPendingSequence = pendingOperationBusinessSequence.value;
    const capturedResyncVersion = operationResyncVersion;
    const toDate = resolveBusinessDate(runtime.now(), selectedTimezone.value);
    const fromDate = previousBusinessDate(toDate);
    try {
      const [nextShifts, nextWorkOrders] = await Promise.all([
        runtime.loadShifts(siteId, fromDate, toDate, signal),
        runtime.loadWorkOrders(siteId, boundedItems(layer.maxItems), signal)
      ]);
      if (!isCurrent(siteId, currentGeneration, signal)) return;
      if (nextShifts.some(shift => shift.siteId !== siteId) || nextWorkOrders.some(order => order.siteId !== siteId)) {
        throw new Error('生产运营快照矿区不匹配');
      }
      shifts.value = nextShifts.filter(shift => shift.status === 'OPEN');
      workOrders.value = nextWorkOrders.filter(order => order.status === 'RUNNING');
      operationUpdatedAt.value = runtime.now().toISOString();
      if (capturedPendingSequence !== null) {
        operationBusinessSequence.value = capturedPendingSequence;
        if (pendingOperationBusinessSequence.value === capturedPendingSequence) {
          pendingOperationBusinessSequence.value = null;
        }
      } else if (capturedResyncVersion > confirmedOperationResyncVersion) {
        operationBusinessSequence.value = null;
      }
      confirmedOperationResyncVersion = Math.max(
        confirmedOperationResyncVersion,
        capturedResyncVersion
      );
      operationSnapshotFailure = null;
      operationError.value = null;
      operationStatus.value =
        pendingOperationBusinessSequence.value !== null || operationResyncVersion > confirmedOperationResyncVersion
          ? 'RESYNCING'
          : operationSubscriptionFailureCode
            ? 'DEGRADED'
            : operationSubscribed
              ? 'LIVE'
              : 'READY';
    } catch (cause) {
      if (!isCurrent(siteId, currentGeneration, signal)) return;
      operationError.value = cause;
      operationSnapshotFailure = cause;
      operationStatus.value = 'DEGRADED';
    }
  }

  function isCurrent(siteId: string, currentGeneration: number, signal: AbortSignal) {
    return currentGeneration === generation && selectedSiteId.value === siteId && !signal.aborted;
  }

  function releaseOperationSubscription() {
    releaseOperation?.();
    releaseOperation = null;
    operationSubscribed = false;
    operationSubscriptionFailureCode = null;
    operationSnapshotFailure = null;
    pendingOperationBusinessSequence.value = null;
    operationResyncVersion = 0;
    confirmedOperationResyncVersion = 0;
    operationRefresh = null;
  }

  function stop() {
    generation += 1;
    controller?.abort();
    controller = null;
    releaseOperationSubscription();
  }

  onScopeDispose(stop);

  return {
    selectedSiteId,
    activeAlarms,
    alarmMarkers,
    unlocatedAlarms,
    unresolvedAlarmLocations,
    locationMappingStatus: runtime.locationMappingStatus,
    currentShifts,
    runningWorkOrders,
    operationMarkers,
    alarmStatus,
    operationStatus,
    alarmError,
    operationError,
    alarmUpdatedAt,
    operationUpdatedAt,
    operationBusinessSequence,
    pendingOperationBusinessSequence,
    loading,
    selectSite,
    stop
  };
}

function visibleLayer(snapshot: SceneRuntimeSnapshot | null, layerType: SceneRuntimeLayer['layerType']) {
  return snapshot?.layers.find(layer => layer.layerType === layerType && layer.visible) ?? null;
}

function boundedItems(value: number) {
  return Number.isInteger(value) && value > 0 ? Math.min(value, MAX_BUSINESS_LAYER_ITEMS) : 0;
}

function latestLocationsBy(
  locations: readonly MineLocationItem[],
  key: (location: MineLocationItem) => string
) {
  return locations.reduce((latest, location) => {
    const value = key(location);
    const existing = latest.get(value);
    if (!existing || location.ingestSequence > existing.ingestSequence) latest.set(value, location);
    return latest;
  }, new Map<string, MineLocationItem>());
}

function resolveBusinessDate(now: Date, timezone?: string | null) {
  if (!timezone) return now.toISOString().slice(0, 10);
  try {
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone: timezone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit'
    }).formatToParts(now);
    const values = Object.fromEntries(parts.map(part => [part.type, part.value]));
    return `${values.year}-${values.month}-${values.day}`;
  } catch {
    return now.toISOString().slice(0, 10);
  }
}

function previousBusinessDate(date: string) {
  const midnight = Date.parse(`${date}T00:00:00Z`);
  return Number.isFinite(midnight) ? new Date(midnight - 86_400_000).toISOString().slice(0, 10) : date;
}

function positiveSequence(value: number | undefined) {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0 ? value : null;
}
