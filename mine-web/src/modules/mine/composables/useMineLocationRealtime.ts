import { computed, onScopeDispose, ref, shallowRef } from 'vue';
import { getMineCurrentLocations } from '../api/mine-api';
import type { CurrentLocation } from '../api/mine-api';
import { realtimeHub } from '@/realtime';
import type {
  RealtimeConnectionState,
  RealtimeLocationBatch,
  RealtimeLocationItem,
  RealtimeLocationListener
} from '@/realtime';

export type MineLocationItem = CurrentLocation['items'][number];
export type MineLocationRuntimeStatus = 'IDLE' | 'LOADING' | 'CONNECTING' | 'LIVE' | 'RECONNECTING' | 'ERROR';

interface MineLocationRealtimeDependencies {
  loadCurrent: (siteId: string, limit?: number) => Promise<CurrentLocation>;
  subscribe: (siteId: string, listener: RealtimeLocationListener) => () => void;
  subscribeConnectionState: (listener: (state: RealtimeConnectionState) => void) => () => void;
  now: () => Date;
}

/**
 * 管理矿区当前位置的“REST 快照优先、WebSocket 增量跟随”生命周期。
 * 矿区切换通过世代号隔离迟到响应，摄取序号只允许当前位置前进。
 */
export function useMineLocationRealtime(
  dependencies: Partial<MineLocationRealtimeDependencies> = {}
) {
  const runtime = {
    loadCurrent: dependencies.loadCurrent ?? getMineCurrentLocations,
    subscribe: dependencies.subscribe ?? ((siteId, listener) => realtimeHub.subscribeMineLocation(siteId, listener)),
    subscribeConnectionState:
      dependencies.subscribeConnectionState ?? (listener => realtimeHub.subscribeConnectionState(listener)),
    now: dependencies.now ?? (() => new Date())
  };
  const selectedSiteId = ref<string | null>(null);
  const locations = shallowRef<MineLocationItem[]>([]);
  const status = ref<MineLocationRuntimeStatus>('IDLE');
  const error = shallowRef<unknown | null>(null);
  const watermark = ref<number | null>(null);
  const source = ref<CurrentLocation['source'] | null>(null);
  const truncated = ref(false);
  const updatedAt = ref<string | null>(null);
  const connectionState = ref<RealtimeConnectionState>('DISCONNECTED');
  let selectionGeneration = 0;
  let releaseLocation: (() => void) | null = null;

  const releaseConnectionState = runtime.subscribeConnectionState(state => {
    connectionState.value = state;
    if (!selectedSiteId.value || status.value === 'LOADING' || status.value === 'ERROR') return;
    status.value = runtimeStatus(state);
  });

  const hasLocations = computed(() => locations.value.length > 0);

  async function selectSite(siteId: string | null, limit = 5000) {
    const normalizedSiteId = siteId?.trim() || null;
    const sameSite = normalizedSiteId !== null && normalizedSiteId === selectedSiteId.value;
    selectionGeneration += 1;
    const generation = selectionGeneration;
    clearLocationSubscription();
    selectedSiteId.value = normalizedSiteId;
    if (!sameSite) {
      locations.value = [];
      watermark.value = null;
      source.value = null;
      truncated.value = false;
      updatedAt.value = null;
    }
    error.value = null;
    if (!selectedSiteId.value) {
      status.value = 'IDLE';
      return;
    }

    const requestedSiteId = selectedSiteId.value;
    status.value = 'LOADING';
    try {
      const snapshot = await runtime.loadCurrent(requestedSiteId, boundedLimit(limit));
      if (generation !== selectionGeneration || selectedSiteId.value !== requestedSiteId) return;
      if (snapshot.siteId !== requestedSiteId) throw new Error('当前位置快照矿区不匹配');
      applySnapshot(snapshot);
      releaseLocation = runtime.subscribe(requestedSiteId, applyRealtimeBatch);
      status.value = runtimeStatus(connectionState.value);
    } catch (cause) {
      if (generation !== selectionGeneration) return;
      error.value = cause;
      status.value = 'ERROR';
    }
  }

  function applySnapshot(snapshot: CurrentLocation) {
    const latest = new Map<string, MineLocationItem>();
    snapshot.items.forEach(item => upsertLatest(latest, item));
    locations.value = [...latest.values()];
    watermark.value = snapshot.watermark;
    source.value = snapshot.source;
    truncated.value = snapshot.truncated;
    updatedAt.value = latestTimestamp(snapshot.items.map(item => item.receivedAt)) ?? runtime.now().toISOString();
  }

  function applyRealtimeBatch(batch: RealtimeLocationBatch) {
    if (batch.siteId !== selectedSiteId.value) return;
    const appliedTimestamps: string[] = [];
    const latest =
      batch.mode === 'SNAPSHOT'
        ? new Map<string, MineLocationItem>()
        : new Map(locations.value.map(item => [item.tagId, item]));
    batch.items.forEach(item => {
      const normalized = normalizeRealtimeItem(item);
      if (normalized && upsertLatest(latest, normalized)) appliedTimestamps.push(normalized.receivedAt);
    });
    locations.value = [...latest.values()];
    if (batch.watermark !== undefined) watermark.value = batch.watermark;
    if (batch.source !== undefined) source.value = batch.source;
    if (batch.truncated !== undefined) truncated.value = batch.truncated;
    if (batch.mode === 'SNAPSHOT') {
      updatedAt.value = latestTimestamp(appliedTimestamps) ?? runtime.now().toISOString();
    } else if (appliedTimestamps.length > 0) {
      updatedAt.value = latestTimestamp([updatedAt.value, ...appliedTimestamps].filter(isTimestamp));
    }
  }

  function clearLocationSubscription() {
    releaseLocation?.();
    releaseLocation = null;
  }

  function stop() {
    selectionGeneration += 1;
    clearLocationSubscription();
    releaseConnectionState();
  }

  onScopeDispose(stop);

  return {
    selectedSiteId,
    locations,
    hasLocations,
    status,
    error,
    watermark,
    source,
    truncated,
    updatedAt,
    connectionState,
    selectSite,
    stop
  };
}

function latestTimestamp(values: string[]) {
  return values.reduce<string | null>((latest, value) => {
    const timestamp = Date.parse(value);
    if (!Number.isFinite(timestamp)) return latest;
    return !latest || timestamp > Date.parse(latest) ? value : latest;
  }, null);
}

function runtimeStatus(state: RealtimeConnectionState): MineLocationRuntimeStatus {
  if (state === 'CONNECTED') return 'LIVE';
  if (state === 'RECONNECTING') return 'RECONNECTING';
  return 'CONNECTING';
}

function boundedLimit(value: number) {
  return Number.isInteger(value) && value >= 1 && value <= 10_000 ? value : 5000;
}

function upsertLatest(latest: Map<string, MineLocationItem>, item: MineLocationItem) {
  const existing = latest.get(item.tagId);
  if (existing && item.ingestSequence <= existing.ingestSequence) return false;
  latest.set(item.tagId, item);
  return true;
}

function isTimestamp(value: string | null): value is string {
  return value !== null && Number.isFinite(Date.parse(value));
}

function normalizeRealtimeItem(item: RealtimeLocationItem): MineLocationItem | null {
  const ingestSequence = item.sourceSequence ?? item.ingestSequence ?? 0;
  if (ingestSequence < 1 || !Number.isFinite(item.localX) || !Number.isFinite(item.localY)) return null;
  return {
    eventId: item.eventId,
    organizationId: item.organizationId,
    siteId: item.siteId,
    sourceDeviceId: item.sourceDeviceId,
    tagId: item.tagId,
    tagCode: item.tagCode,
    coordinateSystemId: item.coordinateSystemId,
    vehicleId: item.vehicleId,
    personId: item.personId,
    ingestSequence,
    occurredAt: item.occurredAt,
    receivedAt: item.receivedAt,
    localX: item.localX,
    localY: item.localY,
    localZ: item.localZ,
    speedMetersPerSecond: item.speedMetersPerSecond,
    headingDegrees: item.headingDegrees,
    accuracyMeters: item.accuracyMeters,
    qualityStatus: item.qualityStatus,
    qualityReason: item.qualityReason
  };
}
