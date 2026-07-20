import { effectScope } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import type { CurrentLocation, LocationTrajectory, SpatialEventPage } from '@/modules/mine/api/mine-api';
import type { RealtimeLocationBatch, RealtimeSpatialNotification } from '@/realtime';
import type { SceneRuntimeLayer, SceneRuntimeSnapshot } from '../api/scene';
import { useSceneRealtime } from './useSceneRealtime';

describe('useSceneRealtime', () => {
  it('复用位置和空间实时链路，并按摄取序号合并当前位置', async () => {
    let locationListener!: (batch: RealtimeLocationBatch) => void;
    let spatialListener!: (notification: RealtimeSpatialNotification) => void;
    const locationRelease = vi.fn();
    const spatialRelease = vi.fn();
    const scope = effectScope();
    const state = scope.run(() =>
      useSceneRealtime({
        location: {
          loadCurrent: vi.fn(async (siteId, limit) => currentSnapshot(siteId, limit ?? 0)),
          subscribe: (_siteId, listener) => {
            locationListener = listener;
            return locationRelease;
          },
          subscribeConnectionState: listener => {
            listener('CONNECTED');
            return vi.fn();
          }
        },
        spatial: {
          loadRegions: vi.fn(async siteId => regionSnapshot(siteId)),
          loadEvents: vi.fn(async siteId => eventPage(siteId)),
          subscribe: (_siteId, listener) => {
            spatialListener = listener;
            return spatialRelease;
          }
        }
      })
    )!;

    await state.selectSite(
      'site-1',
      snapshot([
        dynamicLayer('DYNAMIC_LOCATIONS', { maxItems: 2 }),
        dynamicLayer('DYNAMIC_REGIONS', { maxItems: 20 })
      ])
    );
    locationListener({ siteId: 'site-1', mode: 'UPDATE', items: [realtimeLocation(9, 1)] });
    locationListener({ siteId: 'site-1', mode: 'UPDATE', items: [realtimeLocation(11, 222)] });
    spatialListener({ mode: 'SUBSCRIBED', siteId: 'site-1' });

    expect(state.locations.value).toEqual([expect.objectContaining({ localX: 222, ingestSequence: 11 })]);
    expect(state.regions.value).toHaveLength(1);
    expect(state.locationStatus.value).toBe('LIVE');
    await vi.waitFor(() => expect(state.eventStatus.value).toBe('LIVE'));
    expect(state.eventStatus.value).toBe('LIVE');

    await state.selectSite(null, null);
    expect(locationRelease).toHaveBeenCalledOnce();
    expect(spatialRelease).toHaveBeenCalledOnce();
    scope.stop();
  });

  it('只有选中当前标签后才加载有界轨迹，矿区切换会忽略迟到结果', async () => {
    let resolveTrajectory!: (value: LocationTrajectory) => void;
    const pending = new Promise<LocationTrajectory>(resolve => {
      resolveTrajectory = resolve;
    });
    const loadTrajectory = vi.fn().mockReturnValueOnce(pending);
    const scope = effectScope();
    const state = scope.run(() =>
      useSceneRealtime({
        location: {
          loadCurrent: vi.fn(async siteId => currentSnapshot(siteId, 10)),
          subscribe: () => vi.fn(),
          subscribeConnectionState: () => vi.fn()
        },
        spatial: {
          loadRegions: vi.fn(async siteId => regionSnapshot(siteId)),
          loadEvents: vi.fn(async siteId => eventPage(siteId)),
          subscribe: () => vi.fn()
        },
        loadTrajectory,
        now: () => new Date('2026-07-20T08:00:00Z')
      })
    )!;
    const scene = snapshot([
      dynamicLayer('DYNAMIC_LOCATIONS', { maxItems: 10 }),
      dynamicLayer('DYNAMIC_TRAJECTORY', { maxItems: 50, options: { windowMinutes: 30 } })
    ]);
    await state.selectSite('site-1', scene);

    expect(loadTrajectory).not.toHaveBeenCalled();
    const loading = state.selectTag('tag-1');
    expect(loadTrajectory).toHaveBeenCalledWith(
      'site-1',
      expect.objectContaining({
        subjectType: 'TAG',
        subjectId: 'tag-1',
        from: '2026-07-20T07:30:00.000Z',
        to: '2026-07-20T08:00:00.000Z',
        maxPoints: 50
      }),
      expect.any(AbortSignal)
    );

    await state.selectSite('site-2', scene);
    resolveTrajectory(trajectory('site-1'));
    await loading;

    expect(state.selectedTagId.value).toBeNull();
    expect(state.trajectory.value).toEqual([]);
    scope.stop();
  });

  it('场景未发布对应动态层时不建立无权限实时订阅', async () => {
    const loadCurrent = vi.fn();
    const subscribeLocation = vi.fn();
    const loadRegions = vi.fn();
    const subscribeSpatial = vi.fn();
    const state = effectScope().run(() =>
      useSceneRealtime({
        location: {
          loadCurrent,
          subscribe: subscribeLocation,
          subscribeConnectionState: () => vi.fn()
        },
        spatial: {
          loadRegions,
          loadEvents: vi.fn(),
          subscribe: subscribeSpatial
        }
      })
    )!;

    await state.selectSite('site-1', snapshot([]));

    expect(loadCurrent).not.toHaveBeenCalled();
    expect(subscribeLocation).not.toHaveBeenCalled();
    expect(loadRegions).not.toHaveBeenCalled();
    expect(subscribeSpatial).not.toHaveBeenCalled();
    expect(state.locationStatus.value).toBe('IDLE');
    expect(state.regionStatus.value).toBe('DISABLED');
  });

  it('业务图层只在具备定位和对应业务权限时读取映射位置', async () => {
    const loadCurrent = vi.fn(async siteId => currentSnapshot(siteId, 10));
    const state = effectScope().run(() =>
      useSceneRealtime({
        location: { loadCurrent, subscribe: () => vi.fn(), subscribeConnectionState: () => vi.fn() },
        spatial: {
          loadRegions: vi.fn(async siteId => regionSnapshot(siteId)),
          loadEvents: vi.fn(async siteId => eventPage(siteId)),
          subscribe: () => vi.fn()
        }
      })
    )!;
    const alarmScene = snapshot([dynamicLayer('DYNAMIC_ALARMS', { maxItems: 20 })]);

    await state.selectSite('site-1', alarmScene, {
      locations: true,
      regions: false,
      events: false,
      alarms: true,
      operations: false
    });
    expect(loadCurrent).toHaveBeenCalledOnce();
    expect(loadCurrent).toHaveBeenCalledWith('site-1', 10_000);
    expect(state.locations.value).toEqual([]);
    expect(state.selectableLocations.value).toHaveLength(1);
    expect(state.locationMappingStatus.value).toBe('READY');

    await state.selectSite('site-2', alarmScene, {
      locations: false,
      regions: false,
      events: false,
      alarms: true,
      operations: false
    });
    expect(loadCurrent).toHaveBeenCalledOnce();
    expect(state.selectableLocations.value).toEqual([]);
    expect(state.locationMappingStatus.value).toBe('UNAUTHORIZED');
  });

  it('业务位置快照截断时显式暴露不完整状态', async () => {
    const state = effectScope().run(() =>
      useSceneRealtime({
        location: {
          loadCurrent: vi.fn(async siteId => ({ ...currentSnapshot(siteId, 10), truncated: true })),
          subscribe: () => vi.fn(),
          subscribeConnectionState: () => vi.fn()
        },
        spatial: {
          loadRegions: vi.fn(async siteId => regionSnapshot(siteId)),
          loadEvents: vi.fn(async siteId => eventPage(siteId)),
          subscribe: () => vi.fn()
        }
      })
    )!;

    await state.selectSite('site-1', snapshot([dynamicLayer('DYNAMIC_ALARMS')]), {
      locations: true,
      regions: false,
      events: false,
      alarms: true,
      operations: false
    });

    expect(state.locationTruncated.value).toBe(true);
    expect(state.locationMappingStatus.value).toBe('TRUNCATED');
  });

  it('二维与三维共用发布可见性和上限规则，选择数据不冒充位置图层', async () => {
    let spatialListener!: (notification: RealtimeSpatialNotification) => void;
    const loadCurrent = vi.fn(async (siteId: string) => {
      const result = currentSnapshot(siteId, 10);
      result.items = [
        { ...result.items[0]!, tagId: 'tag-2', tagCode: 'TAG-002', ingestSequence: 11 },
        result.items[0]!
      ];
      return result;
    });
    const state = effectScope().run(() =>
      useSceneRealtime({
        location: {
          loadCurrent,
          subscribe: () => vi.fn(),
          subscribeConnectionState: () => vi.fn()
        },
        spatial: {
          loadRegions: vi.fn(async siteId => {
            const result = regionSnapshot(siteId);
            result.regions = [
              result.regions[0]!,
              { ...result.regions[0]!, zoneId: 'zone-2', zoneVersionId: 'zone-version-2' }
            ];
            return result;
          }),
          loadEvents: vi.fn(async siteId => eventPage(siteId)),
          subscribe: (_siteId, listener) => {
            spatialListener = listener;
            return vi.fn();
          }
        }
      })
    )!;

    await state.selectSite('site-1', snapshot([dynamicLayer('DYNAMIC_TRAJECTORY', { maxItems: 50 })]));
    expect(state.locations.value).toEqual([]);
    expect(state.selectableLocations.value).toHaveLength(2);

    await state.selectSite(
      'site-1',
      snapshot([
        dynamicLayer('DYNAMIC_LOCATIONS', { maxItems: 1 }),
        dynamicLayer('DYNAMIC_REGIONS', { maxItems: 1, options: { maxEvents: 1 } })
      ])
    );
    spatialListener({ mode: 'UPDATE', siteId: 'site-1', item: realtimeSpatialEvent('event-1', '2026-07-20T08:00:00Z') });
    spatialListener({ mode: 'UPDATE', siteId: 'site-1', item: realtimeSpatialEvent('event-2', '2026-07-20T08:00:01Z') });
    expect(state.locations.value).toEqual([expect.objectContaining({ tagId: 'tag-2' })]);
    expect(state.selectableLocations.value).toHaveLength(2);
    expect(state.regions.value).toHaveLength(1);
    expect(state.events.value).toEqual([expect.objectContaining({ eventId: 'event-2' })]);
  });

  it('同一标签轨迹刷新失败时保留最后正确轨迹并独立降级', async () => {
    const firstTrajectory = trajectory('site-1');
    firstTrajectory.items = [trajectoryPoint()];
    const loadTrajectory = vi
      .fn()
      .mockResolvedValueOnce(firstTrajectory)
      .mockRejectedValueOnce(new Error('trajectory unavailable'));
    const state = effectScope().run(() =>
      useSceneRealtime({
        location: {
          loadCurrent: vi.fn(async siteId => currentSnapshot(siteId, 10)),
          subscribe: () => vi.fn(),
          subscribeConnectionState: () => vi.fn()
        },
        spatial: {
          loadRegions: vi.fn(async siteId => regionSnapshot(siteId)),
          loadEvents: vi.fn(async siteId => eventPage(siteId)),
          subscribe: () => vi.fn()
        },
        loadTrajectory
      })
    )!;
    await state.selectSite(
      'site-1',
      snapshot([
        dynamicLayer('DYNAMIC_LOCATIONS', { maxItems: 10 }),
        dynamicLayer('DYNAMIC_TRAJECTORY', { maxItems: 50 })
      ])
    );
    await state.selectTag('tag-1');
    await state.selectTag('tag-1');

    expect(state.trajectory.value).toHaveLength(1);
    expect(state.trajectoryStatus.value).toBe('DEGRADED');
    expect(state.trajectoryUpdatedAt.value).toBe('2026-07-20T08:00:00Z');
    expect(state.locationStatus.value).not.toBe('ERROR');
  });

  it('同矿区重载各层失败时保留最后正确数据、选择和更新时间', async () => {
    const scene = snapshot([
      dynamicLayer('DYNAMIC_LOCATIONS', { maxItems: 10 }),
      dynamicLayer('DYNAMIC_REGIONS', { maxItems: 10 }),
      dynamicLayer('DYNAMIC_TRAJECTORY', { maxItems: 50 })
    ]);
    const loadCurrent = vi
      .fn()
      .mockResolvedValueOnce(currentSnapshot('site-1', 10))
      .mockRejectedValueOnce(new Error('location unavailable'));
    const loadRegions = vi
      .fn()
      .mockResolvedValueOnce(regionSnapshot('site-1'))
      .mockRejectedValueOnce(new Error('region unavailable'));
    const loadEvents = vi
      .fn()
      .mockResolvedValueOnce(eventPage('site-1'))
      .mockRejectedValueOnce(new Error('event unavailable'));
    const loadTrajectory = vi
      .fn()
      .mockResolvedValueOnce(trajectory('site-1'))
      .mockRejectedValueOnce(new Error('trajectory unavailable'));
    const firstTrajectory = trajectory('site-1');
    firstTrajectory.items = [trajectoryPoint()];
    loadTrajectory.mockReset().mockResolvedValueOnce(firstTrajectory).mockRejectedValueOnce(new Error('trajectory unavailable'));
    const state = effectScope().run(() =>
      useSceneRealtime({
        location: { loadCurrent, subscribe: () => vi.fn(), subscribeConnectionState: () => vi.fn() },
        spatial: { loadRegions, loadEvents, subscribe: () => vi.fn() },
        loadTrajectory
      })
    )!;

    await state.selectSite('site-1', scene);
    await state.selectTag('tag-1');
    await state.selectSite('site-1', scene);

    expect(state.locations.value).toHaveLength(1);
    expect(state.regions.value).toHaveLength(1);
    expect(state.selectedTagId.value).toBe('tag-1');
    expect(state.trajectory.value).toHaveLength(1);
    expect(state.locationUpdatedAt.value).toBe('2026-07-20T07:59:01Z');
    expect(state.regionUpdatedAt.value).toBe('2026-07-20T08:00:00Z');
    expect(state.eventUpdatedAt.value).toBe('2026-07-20T08:00:00Z');
    expect(state.trajectoryUpdatedAt.value).toBe('2026-07-20T08:00:00Z');
  });

  it('仅位置图层也可选中高亮对象且不请求轨迹', async () => {
    const loadTrajectory = vi.fn();
    const state = effectScope().run(() =>
      useSceneRealtime({
        location: {
          loadCurrent: vi.fn(async siteId => currentSnapshot(siteId, 10)),
          subscribe: () => vi.fn(),
          subscribeConnectionState: () => vi.fn()
        },
        spatial: {
          loadRegions: vi.fn(async siteId => regionSnapshot(siteId)),
          loadEvents: vi.fn(async siteId => eventPage(siteId)),
          subscribe: () => vi.fn()
        },
        loadTrajectory
      })
    )!;
    await state.selectSite('site-1', snapshot([dynamicLayer('DYNAMIC_LOCATIONS', { maxItems: 10 })]));

    await state.selectTag('tag-1');

    expect(state.selectedTagId.value).toBe('tag-1');
    expect(state.trajectoryStatus.value).toBe('DISABLED');
    expect(loadTrajectory).not.toHaveBeenCalled();
  });

  it('同矿区刷新迟到时不得回滚用户期间的新选择', async () => {
    let resolveRefresh!: (value: CurrentLocation) => void;
    const refresh = new Promise<CurrentLocation>(resolve => {
      resolveRefresh = resolve;
    });
    const initial = currentSnapshot('site-1', 10);
    initial.items = [initial.items[0]!, { ...initial.items[0]!, tagId: 'tag-2', tagCode: 'TAG-002' }];
    const loadCurrent = vi.fn().mockResolvedValueOnce(initial).mockReturnValueOnce(refresh);
    const loadTrajectory = vi.fn(async (_siteId: string, query: { subjectId: string }) =>
      trajectory('site-1', query.subjectId)
    );
    const scene = snapshot([
      dynamicLayer('DYNAMIC_LOCATIONS', { maxItems: 10 }),
      dynamicLayer('DYNAMIC_TRAJECTORY', { maxItems: 50 })
    ]);
    const state = effectScope().run(() =>
      useSceneRealtime({
        location: { loadCurrent, subscribe: () => vi.fn(), subscribeConnectionState: () => vi.fn() },
        spatial: {
          loadRegions: vi.fn(async siteId => regionSnapshot(siteId)),
          loadEvents: vi.fn(async siteId => eventPage(siteId)),
          subscribe: () => vi.fn()
        },
        loadTrajectory
      })
    )!;
    await state.selectSite('site-1', scene);
    await state.selectTag('tag-1');

    const reloading = state.selectSite('site-1', scene);
    await state.selectTag('tag-2');
    resolveRefresh(initial);
    await reloading;

    expect(state.selectedTagId.value).toBe('tag-2');
    expect(loadTrajectory.mock.calls.map(call => call[1].subjectId)).toEqual(['tag-1', 'tag-2']);
  });
});

function currentSnapshot(siteId: string, limit: number): CurrentLocation {
  return {
    siteId,
    watermark: 10,
    source: 'REDIS',
    truncated: limit < 1,
    items: [
      {
        eventId: 'event-1',
        organizationId: 'organization-1',
        siteId,
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
        localZ: null,
        speedMetersPerSecond: 3,
        headingDegrees: 90,
        accuracyMeters: 1,
        qualityStatus: 'GOOD',
        qualityReason: null
      }
    ]
  };
}

function realtimeLocation(sequence: number, localX: number) {
  const item = currentSnapshot('site-1', 10).items[0]!;
  return { ...item, sourceSequence: sequence, localX };
}

function realtimeSpatialEvent(eventId: string, occurredAt: string) {
  return {
    eventId,
    organizationId: 'organization-1',
    siteId: 'site-1',
    zoneType: 'GEOFENCE' as const,
    zoneId: 'zone-1',
    zoneVersionId: 'zone-version-1',
    tagId: 'tag-1',
    vehicleId: 'vehicle-1',
    personId: null,
    eventType: 'ENTER' as const,
    previousState: 'OUTSIDE' as const,
    currentState: 'INSIDE' as const,
    confirmationPoints: 3,
    ingestSequence: 11,
    runtimeRevision: 5,
    occurredAt,
    anchorLocationEventId: `location-${eventId}`,
    evidence: {
      localX: 100,
      localY: 200,
      localZ: null,
      classification: 'INSIDE' as const,
      qualityStatus: 'GOOD' as const,
      qualityReason: null,
      zoneContentHash: `sha256:${'c'.repeat(64)}`
    }
  };
}

function regionSnapshot(siteId: string) {
  return {
    siteId,
    evaluatedAt: '2026-07-20T08:00:00Z',
    nextChangeAt: null,
    regions: [
      {
        zoneType: 'GEOFENCE' as const,
        zoneId: 'zone-1',
        zoneVersionId: 'zone-version-1',
        coordinateSystemId: 'coordinate-1',
        name: '北区围栏',
        contentHash: `sha256:${'a'.repeat(64)}`,
        shape: 'POLYGON' as const,
        vertices: [
          { x: 0, y: 0 },
          { x: 10, y: 0 },
          { x: 10, y: 10 }
        ]
      }
    ]
  };
}

function eventPage(siteId: string): SpatialEventPage {
  return {
    siteId,
    from: '2026-07-20T07:00:00Z',
    to: '2026-07-20T08:00:00Z',
    watermark: 0,
    nextCursor: null,
    items: []
  };
}

function trajectory(siteId: string, subjectId = 'tag-1'): LocationTrajectory {
  return {
    siteId,
    subjectType: 'TAG',
    subjectId,
    from: '2026-07-20T07:30:00Z',
    to: '2026-07-20T08:00:00Z',
    watermark: 10,
    nextCursor: null,
    sampled: false,
    sourcePointCount: 0,
    items: []
  };
}

function trajectoryPoint(): LocationTrajectory['items'][number] {
  const current = currentSnapshot('site-1', 10).items[0]!;
  const { tagCode: _tagCode, ...point } = current;
  return point;
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
    sceneName: 'North pit',
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
