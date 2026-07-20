import { computed, onScopeDispose, ref, shallowRef } from 'vue';
import { getMineLocationTrajectory } from '@/modules/mine/api/mine-api';
import type { LocationTrajectory } from '@/modules/mine/api/mine-api';
import { useMineLocationRealtime, useMineSpatialEvents } from '@/modules/mine';
import type { SceneRuntimeLayer, SceneRuntimeSnapshot } from '../api/scene';

type LocationDependencies = NonNullable<Parameters<typeof useMineLocationRealtime>[0]>;
type SpatialDependencies = NonNullable<Parameters<typeof useMineSpatialEvents>[0]>;

interface SceneRealtimeDependencies {
  location?: LocationDependencies;
  spatial?: SpatialDependencies;
  loadTrajectory?: typeof getMineLocationTrajectory;
  now?: () => Date;
}

export type SceneTrajectoryStatus = 'DISABLED' | 'IDLE' | 'LOADING' | 'READY' | 'DEGRADED';

/**
 * 组合矿区现有位置与空间实时能力，为二维和三维视口提供同一份不可变数据。
 * 轨迹只在选中标签后按发布图层上限读取，切换矿区会取消并隔离迟到响应。
 */
export function useSceneRealtime(dependencies: SceneRealtimeDependencies = {}) {
  const location = useMineLocationRealtime(dependencies.location);
  const spatial = useMineSpatialEvents(dependencies.spatial);
  const loadTrajectory = dependencies.loadTrajectory ?? getMineLocationTrajectory;
  const now = dependencies.now ?? (() => new Date());
  const selectedSiteId = ref<string | null>(null);
  const selectedTagId = ref<string | null>(null);
  const trajectory = shallowRef<LocationTrajectory['items']>([]);
  const trajectoryUpdatedAt = ref<string | null>(null);
  const trajectoryStatus = ref<SceneTrajectoryStatus>('DISABLED');
  const trajectoryError = shallowRef<unknown | null>(null);
  const locationLayer = shallowRef<SceneRuntimeLayer | null>(null);
  const regionLayer = shallowRef<SceneRuntimeLayer | null>(null);
  const trajectoryLayer = shallowRef<SceneRuntimeLayer | null>(null);
  let generation = 0;
  let trajectoryGeneration = 0;
  let trajectoryController: AbortController | null = null;

  const loading = computed(
    () =>
      location.status.value === 'LOADING' ||
      spatial.regionStatus.value === 'LOADING' ||
      spatial.eventStatus.value === 'LOADING' ||
      trajectoryStatus.value === 'LOADING'
  );
  const locations = computed(() => {
    const layer = locationLayer.value;
    if (!layer) return [];
    return [...location.locations.value]
      .filter(item => Number.isFinite(item.localX) && Number.isFinite(item.localY) && item.ingestSequence > 0)
      .sort((left, right) => right.ingestSequence - left.ingestSequence)
      .slice(0, boundedItems(layer.maxItems));
  });
  const selectableLocations = computed(() => location.locations.value);
  const regions = computed(() => {
    const layer = regionLayer.value;
    return layer ? spatial.regions.value.slice(0, boundedItems(layer.maxItems)) : [];
  });
  const events = computed(() => {
    const layer = regionLayer.value;
    if (!layer) return [];
    const maximum = Math.min(boundedItems(layer.maxItems), optionNumber(layer.options.maxEvents, 1, 1000, 100));
    return spatial.events.value
      .filter(event => event.evidence && Number.isFinite(event.evidence.localX) && Number.isFinite(event.evidence.localY))
      .slice(0, maximum);
  });
  const visibleTrajectory = computed(() => {
    const layer = trajectoryLayer.value;
    return layer && selectedTagId.value ? trajectory.value.slice(-boundedItems(layer.maxItems)) : [];
  });
  const locationStaleAfterSeconds = computed(() =>
    optionNumber(locationLayer.value?.options.staleAfterSeconds, 10, 86_400, 120)
  );

  async function selectSite(siteId: string | null, snapshot: SceneRuntimeSnapshot | null) {
    const normalizedSiteId = siteId?.trim() || null;
    const sameSite = normalizedSiteId !== null && normalizedSiteId === selectedSiteId.value;
    const retainedTagId = sameSite ? selectedTagId.value : null;
    const currentGeneration = ++generation;
    cancelTrajectory();
    selectedSiteId.value = normalizedSiteId;
    if (!sameSite) {
      selectedTagId.value = null;
      trajectory.value = [];
      trajectoryUpdatedAt.value = null;
    }
    trajectoryError.value = null;

    locationLayer.value = visibleLayer(snapshot, 'DYNAMIC_LOCATIONS');
    trajectoryLayer.value = visibleLayer(snapshot, 'DYNAMIC_TRAJECTORY');
    regionLayer.value = visibleLayer(snapshot, 'DYNAMIC_REGIONS');
    if (!trajectoryLayer.value || trajectoryLayer.value.maxItems < 2) {
      trajectory.value = [];
      trajectoryUpdatedAt.value = null;
      trajectoryStatus.value = 'DISABLED';
      if (!sameSite || !locationLayer.value) selectedTagId.value = null;
    } else if (!sameSite || trajectoryStatus.value === 'DISABLED') {
      trajectoryStatus.value = 'IDLE';
    }

    if (!selectedSiteId.value) {
      await Promise.all([
        location.selectSite(null),
        spatial.selectSite(null, { regions: false, events: false })
      ]);
      return;
    }

    const requestedSiteId = selectedSiteId.value;
    const needsLocations = Boolean(locationLayer.value || trajectoryLayer.value);
    await Promise.all([
      needsLocations
        ? location.selectSite(requestedSiteId, locationLimit(locationLayer.value, trajectoryLayer.value))
        : location.selectSite(null),
      spatial.selectSite(requestedSiteId, {
        regions: Boolean(regionLayer.value),
        events: Boolean(regionLayer.value)
      })
    ]);
    if (currentGeneration !== generation || requestedSiteId !== selectedSiteId.value) return;
    if (retainedTagId && selectedTagId.value === retainedTagId) await selectTag(retainedTagId);
  }

  async function selectTag(tagId: string | null) {
    cancelTrajectory();
    trajectoryError.value = null;
    const normalizedTagId = tagId?.trim() || null;
    const layer = trajectoryLayer.value;
    const siteId = selectedSiteId.value;
    if (!normalizedTagId || !siteId || !location.locations.value.some(item => item.tagId === normalizedTagId)) {
      selectedTagId.value = null;
      trajectory.value = [];
      trajectoryUpdatedAt.value = null;
      trajectoryStatus.value = layer && layer.maxItems >= 2 ? 'IDLE' : 'DISABLED';
      return;
    }

    if (!layer || layer.maxItems < 2) {
      selectedTagId.value = normalizedTagId;
      trajectory.value = [];
      trajectoryUpdatedAt.value = null;
      trajectoryStatus.value = 'DISABLED';
      return;
    }

    if (selectedTagId.value !== normalizedTagId) {
      trajectory.value = [];
      trajectoryUpdatedAt.value = null;
    }
    selectedTagId.value = normalizedTagId;
    const currentGeneration = ++trajectoryGeneration;
    const controller = new AbortController();
    trajectoryController = controller;
    trajectoryStatus.value = 'LOADING';
    const to = now();
    const from = new Date(to.getTime() - trajectoryWindowMinutes(layer) * 60_000);
    try {
      const response = await loadTrajectory(
        siteId,
        {
          subjectType: 'TAG',
          subjectId: normalizedTagId,
          from: from.toISOString(),
          to: to.toISOString(),
          watermark: location.watermark.value,
          maxPoints: Math.min(layer.maxItems, 5000)
        },
        controller.signal
      );
      if (
        currentGeneration !== trajectoryGeneration ||
        controller.signal.aborted ||
        selectedSiteId.value !== siteId ||
        selectedTagId.value !== normalizedTagId
      ) return;
      if (response.siteId !== siteId || response.subjectType !== 'TAG' || response.subjectId !== normalizedTagId) {
        throw new Error('场景轨迹响应作用域不匹配');
      }
      trajectory.value = response.items.slice(-Math.min(layer.maxItems, 5000));
      trajectoryUpdatedAt.value = response.to;
      trajectoryStatus.value = 'READY';
    } catch (cause) {
      if (currentGeneration !== trajectoryGeneration || controller.signal.aborted) return;
      trajectoryError.value = cause;
      trajectoryStatus.value = 'DEGRADED';
    } finally {
      if (trajectoryController === controller) trajectoryController = null;
    }
  }

  function cancelTrajectory() {
    trajectoryGeneration += 1;
    trajectoryController?.abort();
    trajectoryController = null;
  }

  onScopeDispose(cancelTrajectory);

  return {
    selectedSiteId,
    selectedTagId,
    locations,
    selectableLocations,
    locationStaleAfterSeconds,
    locationStatus: location.status,
    locationError: location.error,
    locationUpdatedAt: location.updatedAt,
    regions,
    events,
    regionStatus: spatial.regionStatus,
    eventStatus: spatial.eventStatus,
    regionError: spatial.regionError,
    eventError: spatial.eventError,
    regionUpdatedAt: spatial.regionUpdatedAt,
    eventUpdatedAt: spatial.eventUpdatedAt,
    trajectory: visibleTrajectory,
    trajectoryStatus,
    trajectoryError,
    trajectoryUpdatedAt,
    loading,
    selectSite,
    selectTag
  };
}

function visibleLayer(snapshot: SceneRuntimeSnapshot | null, layerType: SceneRuntimeLayer['layerType']) {
  return snapshot?.layers.find(layer => layer.layerType === layerType && layer.visible) ?? null;
}

function locationLimit(...layers: Array<SceneRuntimeLayer | null>) {
  const configured = layers.reduce((maximum, layer) => Math.max(maximum, layer?.maxItems ?? 0), 0);
  return Math.min(5000, Math.max(1, configured || 5000));
}

function trajectoryWindowMinutes(layer: SceneRuntimeLayer) {
  const value = layer.options.windowMinutes;
  return typeof value === 'number' && Number.isInteger(value) && value >= 1 && value <= 1440 ? value : 60;
}

function boundedItems(value: number) {
  return Number.isInteger(value) && value > 0 ? Math.min(value, 100_000) : 0;
}

function optionNumber(value: unknown, minimum: number, maximum: number, fallback: number) {
  return typeof value === 'number' && Number.isFinite(value) && value >= minimum && value <= maximum ? value : fallback;
}
