import { onScopeDispose, ref, shallowRef } from 'vue';
import { getMineCurrentSpatialRegions, getMineSpatialEvents } from '../api/mine-api';
import type { CurrentSpatialRegions, SpatialEvent, SpatialEventPage } from '../api/mine-api';
import { realtimeHub } from '@/realtime';
import type {
  RealtimeSpatialEventItem,
  RealtimeSpatialListener,
  RealtimeSpatialNotification
} from '@/realtime';

export type MineSpatialLayerStatus = 'DISABLED' | 'IDLE' | 'LOADING' | 'READY' | 'LIVE' | 'RESYNCING' | 'DEGRADED';

interface MineSpatialRegionBase {
  zoneType: 'WAYPOINT' | 'GEOFENCE';
  zoneId: string;
  zoneVersionId: string;
  coordinateSystemId: string;
  name: string;
  contentHash: string | null;
}

export interface MineSpatialCircleRegion extends MineSpatialRegionBase {
  zoneType: 'WAYPOINT';
  shape: 'CIRCLE';
  x: number;
  y: number;
  radiusMeters: number;
}

export interface MineSpatialPolygonRegion extends MineSpatialRegionBase {
  zoneType: 'GEOFENCE';
  shape: 'POLYGON';
  vertices: Array<{ x: number; y: number }>;
}

export type MineSpatialRegion = MineSpatialCircleRegion | MineSpatialPolygonRegion;

export interface MineSpatialRegionSnapshot {
  siteId: string;
  evaluatedAt: string;
  nextChangeAt: string | null;
  regions: MineSpatialRegion[];
}

export type MineSpatialEvent = Omit<SpatialEvent, 'eventSequence'> & {
  eventSequence: number | null;
  anchorLocationEventId?: string;
  evidence?: RealtimeSpatialEventItem['evidence'];
};

export interface MineSpatialCapabilities {
  regions: boolean;
  events: boolean;
}

interface MineSpatialDependencies {
  loadRegions: (siteId: string) => Promise<MineSpatialRegionSnapshot>;
  loadEvents: (siteId: string) => Promise<SpatialEventPage>;
  subscribe: (siteId: string, listener: RealtimeSpatialListener) => () => void;
}

const REGION_SAFETY_REFRESH_MILLIS = 60_000;
const REGION_RETRY_MILLIS = 30_000;
const REGION_BOUNDARY_SKEW_MILLIS = 100;

/**
 * 管理矿区空间区域和事件图层，实时流先订阅并与固定水位快照合并。
 * 连接序号断档时仅回查空间事件，区域或事件失败不会影响当前位置主链路。
 */
export function useMineSpatialEvents(dependencies: Partial<MineSpatialDependencies> = {}) {
  const runtime: MineSpatialDependencies = {
    loadRegions: dependencies.loadRegions ?? loadCurrentRegions,
    loadEvents: dependencies.loadEvents ?? (siteId => getMineSpatialEvents(siteId, { pageSize: 100 })),
    subscribe:
      dependencies.subscribe ??
      ((siteId, listener) => realtimeHub.subscribeMineSpatialEvent(siteId, listener))
  };
  const selectedSiteId = ref<string | null>(null);
  const regions = shallowRef<MineSpatialRegion[]>([]);
  const events = shallowRef<MineSpatialEvent[]>([]);
  const lastEvent = shallowRef<MineSpatialEvent | null>(null);
  const regionStatus = ref<MineSpatialLayerStatus>('IDLE');
  const eventStatus = ref<MineSpatialLayerStatus>('IDLE');
  const regionError = shallowRef<unknown | null>(null);
  const eventError = shallowRef<unknown | null>(null);
  const eventWatermark = ref<number | null>(null);
  let selectionGeneration = 0;
  let eventSnapshotGeneration = 0;
  let releaseEvents: (() => void) | null = null;
  let resyncPromise: Promise<void> | null = null;
  let regionRefreshTimer: ReturnType<typeof setTimeout> | null = null;
  let eventSubscriptionInitializing = false;
  let eventSubscriptionReady = false;
  let eventSubscriptionFailureCode: string | null = null;

  async function selectSite(
    siteId: string | null,
    capabilities: MineSpatialCapabilities = { regions: true, events: true }
  ) {
    selectionGeneration += 1;
    eventSnapshotGeneration += 1;
    const generation = selectionGeneration;
    clearRegionRefreshTimer();
    releaseSubscription();
    selectedSiteId.value = siteId?.trim() || null;
    regions.value = [];
    events.value = [];
    lastEvent.value = null;
    eventWatermark.value = null;
    regionError.value = null;
    eventError.value = null;
    regionStatus.value = capabilities.regions ? 'IDLE' : 'DISABLED';
    eventStatus.value = capabilities.events ? 'IDLE' : 'DISABLED';
    if (!selectedSiteId.value) return;

    const requestedSiteId = selectedSiteId.value;
    const jobs: Promise<void>[] = [];
    if (capabilities.events) {
      eventStatus.value = 'LOADING';
      try {
        eventSubscriptionInitializing = true;
        releaseEvents = runtime.subscribe(requestedSiteId, handleRealtimeNotification);
        eventSubscriptionInitializing = false;
        const initialSnapshot = loadEventSnapshot(requestedSiteId, generation, false);
        jobs.push(initialSnapshot);
        if (eventSubscriptionReady) jobs.push(initialSnapshot.then(() => resyncEvents()));
      } catch (cause) {
        eventSubscriptionInitializing = false;
        eventError.value = cause;
        eventStatus.value = 'DEGRADED';
      }
    }
    if (capabilities.regions) {
      regionStatus.value = 'LOADING';
      jobs.push(loadRegionSnapshot(requestedSiteId, generation));
    }
    await Promise.all(jobs);
  }

  async function loadRegionSnapshot(siteId: string, generation: number) {
    try {
      const snapshot = await runtime.loadRegions(siteId);
      if (generation !== selectionGeneration || selectedSiteId.value !== siteId) return;
      if (snapshot.siteId !== siteId) throw new Error('空间区域快照矿区不匹配');
      regions.value = snapshot.regions;
      regionError.value = null;
      regionStatus.value = 'READY';
      scheduleRegionRefresh(siteId, generation, snapshot.evaluatedAt, snapshot.nextChangeAt);
    } catch (cause) {
      if (generation !== selectionGeneration || selectedSiteId.value !== siteId) return;
      regionError.value = cause;
      regionStatus.value = 'DEGRADED';
      scheduleRegionRefresh(siteId, generation, null, null, REGION_RETRY_MILLIS);
    }
  }

  async function loadEventSnapshot(siteId: string, generation: number, resync: boolean) {
    const snapshotGeneration = ++eventSnapshotGeneration;
    try {
      const snapshot = await runtime.loadEvents(siteId);
      if (
        generation !== selectionGeneration ||
        selectedSiteId.value !== siteId ||
        snapshotGeneration !== eventSnapshotGeneration
      ) return;
      if (snapshot.siteId !== siteId) throw new Error('空间事件快照矿区不匹配');
      const snapshotEvents = snapshot.items.map(normalizeSnapshotEvent);
      const snapshotIds = new Set(snapshotEvents.map(item => item.eventId));
      const retainedEvidence = events.value.filter(
        item => item.eventSequence === null || snapshotIds.has(item.eventId)
      );
      events.value = mergeEvents(snapshotEvents, retainedEvidence);
      eventWatermark.value = snapshot.watermark;
      if (!eventSubscriptionFailureCode) {
        eventError.value = null;
        eventStatus.value = eventSubscriptionReady ? 'LIVE' : 'READY';
      }
    } catch (cause) {
      if (
        generation !== selectionGeneration ||
        selectedSiteId.value !== siteId ||
        snapshotGeneration !== eventSnapshotGeneration
      ) return;
      eventError.value = cause;
      eventStatus.value = 'DEGRADED';
    } finally {
      if (resync && snapshotGeneration === eventSnapshotGeneration) resyncPromise = null;
    }
  }

  function handleRealtimeNotification(notification: RealtimeSpatialNotification) {
    if (notification.siteId !== selectedSiteId.value) return;
    if (notification.mode === 'SUBSCRIBED') {
      eventSubscriptionReady = true;
      eventSubscriptionFailureCode = null;
      if (!eventSubscriptionInitializing) void resyncEvents();
      return;
    }
    if (notification.mode === 'SUBSCRIPTION_FAILED') {
      eventSubscriptionReady = false;
      eventSubscriptionFailureCode = notification.code;
      eventSnapshotGeneration += 1;
      resyncPromise = null;
      eventError.value = new Error(`空间事件订阅失败: ${notification.code}`);
      eventStatus.value = 'DEGRADED';
      return;
    }
    if (notification.mode === 'RESYNC_REQUIRED') {
      void resyncEvents();
      return;
    }
    const event = normalizeRealtimeEvent(notification.item);
    events.value = mergeEvents([event], events.value).slice(0, 100);
    lastEvent.value = event;
    if (eventSubscriptionReady && eventStatus.value !== 'RESYNCING' && !eventError.value) {
      eventStatus.value = 'LIVE';
    }
  }

  function resyncEvents() {
    const siteId = selectedSiteId.value;
    if (!siteId || resyncPromise) return resyncPromise ?? Promise.resolve();
    const generation = selectionGeneration;
    eventStatus.value = 'RESYNCING';
    resyncPromise = loadEventSnapshot(siteId, generation, true);
    return resyncPromise;
  }

  function releaseSubscription() {
    releaseEvents?.();
    releaseEvents = null;
    resyncPromise = null;
    eventSubscriptionInitializing = false;
    eventSubscriptionReady = false;
    eventSubscriptionFailureCode = null;
  }

  function scheduleRegionRefresh(
    siteId: string,
    generation: number,
    evaluatedAt: string | null,
    nextChangeAt: string | null,
    maximumDelay = REGION_SAFETY_REFRESH_MILLIS
  ) {
    clearRegionRefreshTimer();
    const evaluatedAtMillis = evaluatedAt ? Date.parse(evaluatedAt) : Number.NaN;
    const boundaryAt = nextChangeAt ? Date.parse(nextChangeAt) : Number.NaN;
    const boundaryDelay = Number.isFinite(evaluatedAtMillis) && Number.isFinite(boundaryAt)
      ? Math.max(250, boundaryAt - evaluatedAtMillis + REGION_BOUNDARY_SKEW_MILLIS)
      : maximumDelay;
    const delay = Math.min(maximumDelay, boundaryDelay);
    regionRefreshTimer = setTimeout(() => {
      regionRefreshTimer = null;
      if (generation !== selectionGeneration || selectedSiteId.value !== siteId) return;
      void loadRegionSnapshot(siteId, generation);
    }, delay);
  }

  function clearRegionRefreshTimer() {
    if (!regionRefreshTimer) return;
    clearTimeout(regionRefreshTimer);
    regionRefreshTimer = null;
  }

  function stop() {
    selectionGeneration += 1;
    eventSnapshotGeneration += 1;
    clearRegionRefreshTimer();
    releaseSubscription();
  }

  onScopeDispose(stop);

  return {
    selectedSiteId,
    regions,
    events,
    lastEvent,
    regionStatus,
    eventStatus,
    regionError,
    eventError,
    eventWatermark,
    selectSite,
    resyncEvents,
    stop
  };
}

function normalizeSnapshotEvent(event: SpatialEvent): MineSpatialEvent {
  return { ...event, eventSequence: event.eventSequence };
}

function normalizeRealtimeEvent(event: RealtimeSpatialEventItem): MineSpatialEvent {
  return { ...event, eventSequence: null };
}

function mergeEvents(primary: MineSpatialEvent[], secondary: MineSpatialEvent[]) {
  const unique = new Map<string, MineSpatialEvent>();
  [...primary, ...secondary].forEach(event => {
    const existing = unique.get(event.eventId);
    if (!existing) {
      unique.set(event.eventId, event);
      return;
    }
    const persisted = existing.eventSequence !== null ? existing : event;
    unique.set(event.eventId, {
      ...event,
      ...existing,
      eventSequence: persisted.eventSequence,
      anchorLocationEventId: existing.anchorLocationEventId ?? event.anchorLocationEventId,
      evidence: existing.evidence ?? event.evidence
    });
  });
  return [...unique.values()]
    .sort((left, right) => Date.parse(right.occurredAt) - Date.parse(left.occurredAt))
    .slice(0, 100);
}

async function loadCurrentRegions(siteId: string): Promise<MineSpatialRegionSnapshot> {
  const snapshot = await getMineCurrentSpatialRegions(siteId);
  return {
    siteId: snapshot.siteId,
    evaluatedAt: snapshot.evaluatedAt,
    nextChangeAt: snapshot.nextChangeAt,
    regions: snapshot.items.map(normalizeRegion)
  };
}

function normalizeRegion(item: CurrentSpatialRegions['items'][number]): MineSpatialRegion {
  const base = {
    zoneType: item.zoneType,
    zoneId: item.zoneId,
    zoneVersionId: item.zoneVersionId,
    coordinateSystemId: item.coordinateSystemId,
    name: item.name,
    contentHash: item.contentHash
  };
  if (
    item.zoneType === 'WAYPOINT' &&
    item.shape === 'CIRCLE' &&
    item.x !== null &&
    item.y !== null &&
    item.radiusMeters !== null
  ) {
    return { ...base, zoneType: 'WAYPOINT', shape: 'CIRCLE', x: item.x, y: item.y, radiusMeters: item.radiusMeters };
  }
  if (item.zoneType === 'GEOFENCE' && item.shape === 'POLYGON' && item.vertices.length >= 3) {
    return {
      ...base,
      zoneType: 'GEOFENCE',
      shape: 'POLYGON',
      vertices: item.vertices
        .slice()
        .sort((left, right) => left.sequenceNumber - right.sequenceNumber)
        .map(vertex => ({ x: vertex.x, y: vertex.y }))
    };
  }
  throw new Error(`空间区域几何不合法: ${item.zoneVersionId}`);
}
