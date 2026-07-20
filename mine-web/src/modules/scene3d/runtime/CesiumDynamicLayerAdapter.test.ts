import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { LocationTrajectory, MineLocationItem, MineSpatialEvent, MineSpatialRegion } from '@/modules/mine';
import type { SceneRuntimeLayer, SceneRuntimeSnapshot } from '../api/scene';
import type { CesiumModule } from './load-cesium';
import { CesiumDynamicLayerAdapter } from './CesiumDynamicLayerAdapter';

const transforms = vi.hoisted(() => ({
  createLayerTransform: vi.fn((_runtime, _root, layer: SceneRuntimeLayer) => `transform:${layer.layerCode}`),
  transformScenePosition: vi.fn((_runtime, transform, position) => ({ transform, position }))
}));

vi.mock('./scene-transform', () => transforms);

describe('CesiumDynamicLayerAdapter', () => {
  beforeEach(() => transforms.transformScenePosition.mockClear());

  it('动画帧内合并高频位置，同一对象一百次更新只创建一个 PointPrimitive', () => {
    const harness = createHarness();
    const adapter = createAdapter(harness, [dynamicLayer('DYNAMIC_LOCATIONS', { maxItems: 2 })]);

    for (let sequence = 1; sequence <= 100; sequence += 1) {
      adapter.update({ ...emptyData(), locations: [location({ ingestSequence: sequence, localX: sequence })] });
    }
    expect(harness.pointAdd).not.toHaveBeenCalled();
    harness.flushFrame();

    expect(harness.pointAdd).toHaveBeenCalledOnce();
    expect(harness.pointAdd.mock.results[0]!.value.position).toEqual(
      expect.objectContaining({ position: [100, 20, 5] })
    );

    adapter.update({ ...emptyData(), locations: [location({ ingestSequence: 100, localX: 100 })] });
    harness.flushFrame();
    expect(transforms.transformScenePosition).toHaveBeenCalledOnce();

    adapter.update({ ...emptyData(), locations: [location({ ingestSequence: 101, localX: 101 })] });
    harness.flushFrame();
    expect(harness.pointAdd).toHaveBeenCalledOnce();
  });

  it('按图层 maxItems 截断对象，低质量和陈旧位置仍以降级样式可见', () => {
    const harness = createHarness();
    const adapter = createAdapter(harness, [
      dynamicLayer('DYNAMIC_LOCATIONS', { maxItems: 3, options: { staleAfterSeconds: 60 } })
    ]);
    adapter.update({
      ...emptyData(),
      nowMillis: Date.parse('2026-07-20T08:10:00Z'),
      locations: [
        location({ tagId: 'tag-good', ingestSequence: 4 }),
        location({
          tagId: 'tag-degraded',
          ingestSequence: 3,
          qualityStatus: 'DEGRADED',
          occurredAt: '2026-07-20T08:09:50Z'
        }),
        location({ tagId: 'tag-stale', ingestSequence: 2, occurredAt: '2026-07-20T07:00:00Z' }),
        location({ tagId: 'tag-overflow', ingestSequence: 1 })
      ]
    });
    harness.flushFrame();

    expect(harness.pointAdd).toHaveBeenCalledTimes(3);
    expect(harness.pointAdd.mock.calls[1]![0]).toMatchObject({ show: true, outlineWidth: 3 });
    expect(harness.pointAdd.mock.calls[2]![0]).toMatchObject({ show: true, outlineWidth: 3 });
  });

  it('发布的最小与最大视距相等时不渲染动态对象', () => {
    const harness = createHarness();
    const adapter = createAdapter(harness, [
      dynamicLayer('DYNAMIC_LOCATIONS', { minimumDistance: 100, maximumDistance: 100 })
    ]);

    adapter.update({ ...emptyData(), locations: [location()] });
    harness.flushFrame();

    expect(harness.pointAdd.mock.calls[0]![0]).toMatchObject({
      show: false,
      distanceDisplayCondition: { near: 0, far: Number.MAX_VALUE }
    });
  });

  it('对象身份持续轮换时淘汰旧点，池大小始终受 maxItems 限制', () => {
    const harness = createHarness();
    const adapter = createAdapter(harness, [dynamicLayer('DYNAMIC_LOCATIONS', { maxItems: 2 })]);

    adapter.update({
      ...emptyData(),
      locations: [location({ tagId: 'tag-1', ingestSequence: 2 }), location({ tagId: 'tag-2', ingestSequence: 1 })]
    });
    harness.flushFrame();
    adapter.update({
      ...emptyData(),
      locations: [location({ tagId: 'tag-3', ingestSequence: 4 }), location({ tagId: 'tag-4', ingestSequence: 3 })]
    });
    harness.flushFrame();

    expect(harness.pointAdd).toHaveBeenCalledTimes(4);
    expect(harness.pointRemove).toHaveBeenCalledTimes(2);
  });

  it('无新位置消息时到达陈旧阈值也会按需重绘降级状态', () => {
    const harness = createHarness();
    const adapter = createAdapter(harness, [
      dynamicLayer('DYNAMIC_LOCATIONS', { maxItems: 2, options: { staleAfterSeconds: 60 } })
    ]);
    adapter.update({
      ...emptyData(),
      nowMillis: Date.parse('2026-07-20T08:00:00Z'),
      locations: [location({ occurredAt: '2026-07-20T07:59:30Z' })]
    });
    harness.flushFrame();
    const point = harness.pointAdd.mock.results[0]!.value;
    expect(point.outlineWidth).toBe(1);

    harness.flushTimer(Date.parse('2026-07-20T08:00:31Z'));
    harness.flushFrame();

    expect(harness.pointAdd).toHaveBeenCalledOnce();
    expect(point.outlineWidth).toBe(3);
  });

  it('空间事件只为带坐标证据的有界事件创建标记', () => {
    const harness = createHarness();
    const adapter = createAdapter(harness, [
      dynamicLayer('DYNAMIC_REGIONS', { maxItems: 5, options: { maxEvents: 1 } })
    ]);

    adapter.update({
      ...emptyData(),
      events: [spatialEvent('event-1', true), spatialEvent('event-2', true), spatialEvent('event-3', false)]
    });
    harness.flushFrame();

    expect(harness.pointAdd).toHaveBeenCalledOnce();
    expect(harness.pointAdd.mock.calls[0]![0].id).toEqual({ type: 'EVENT', eventId: 'event-1' });
  });

  it('区域版本不变不重建折线，版本变化才替换对应 Primitive', () => {
    const harness = createHarness();
    const adapter = createAdapter(harness, [dynamicLayer('DYNAMIC_REGIONS', { maxItems: 10 })]);
    const first = polygonRegion({ zoneVersionId: 'version-1' });

    adapter.update({ ...emptyData(), regions: [first] });
    harness.flushFrame();
    adapter.update({ ...emptyData(), regions: [{ ...first, name: '仅名称变化' }] });
    harness.flushFrame();
    expect(harness.polylineAdd).toHaveBeenCalledOnce();
    expect(harness.polylineRemove).not.toHaveBeenCalled();

    adapter.update({ ...emptyData(), regions: [{ ...first, zoneVersionId: 'version-2' }] });
    harness.flushFrame();
    expect(harness.polylineRemove).toHaveBeenCalledOnce();
    expect(harness.polylineAdd).toHaveBeenCalledTimes(2);
  });

  it('只为选中对象创建有界轨迹并在销毁时释放集合与待执行帧', () => {
    const harness = createHarness();
    const adapter = createAdapter(harness, [dynamicLayer('DYNAMIC_TRAJECTORY', { maxItems: 2 })]);
    const points = [trajectoryPoint(1), trajectoryPoint(2), trajectoryPoint(3)];

    adapter.update({ ...emptyData(), trajectory: points, selectedTagId: null });
    harness.flushFrame();
    expect(harness.polylineAdd).not.toHaveBeenCalled();

    adapter.update({ ...emptyData(), trajectory: points, selectedTagId: 'tag-1' });
    harness.flushFrame();
    expect(harness.polylineAdd).toHaveBeenCalledOnce();
    expect(harness.polylineAdd.mock.calls[0]![0].positions).toHaveLength(2);

    adapter.update({ ...emptyData(), trajectory: points, selectedTagId: 'tag-2' });
    harness.flushFrame();
    expect(harness.polylineAdd.mock.results[0]!.value.id).toEqual({ type: 'TRAJECTORY', tagId: 'tag-2' });

    adapter.update({ ...emptyData(), locations: [location()] });
    adapter.destroy();
    expect(harness.cancelFrame).toHaveBeenCalledOnce();
    expect(harness.primitiveRemove).toHaveBeenCalledOnce();
  });

  it('区域折线集合创建失败时轨迹仍独立可用并正确归因', () => {
    const harness = createHarness();
    const onLayerFailure = vi.fn();
    harness.primitiveAdd
      .mockImplementationOnce(value => value)
      .mockImplementationOnce(() => {
        throw new Error('region collection unavailable');
      })
      .mockImplementation(value => value);
    const regionLayer = dynamicLayer('DYNAMIC_REGIONS', { maxItems: 10 });
    const trajectoryLayer = dynamicLayer('DYNAMIC_TRAJECTORY', { maxItems: 10 });
    const adapter = createAdapter(harness, [regionLayer, trajectoryLayer], onLayerFailure);

    adapter.update({
      ...emptyData(),
      regions: [polygonRegion()],
      trajectory: [trajectoryPoint(1), trajectoryPoint(2)],
      selectedTagId: 'tag-1'
    });
    harness.flushFrame();

    expect(onLayerFailure).toHaveBeenCalledWith(regionLayer, expect.any(Error));
    expect(onLayerFailure).not.toHaveBeenCalledWith(trajectoryLayer, expect.anything());
    expect(harness.polylineAdd).toHaveBeenCalledOnce();
    expect(harness.polylineAdd.mock.calls[0]![0].id).toEqual({ type: 'TRAJECTORY', tagId: 'tag-1' });
  });
});

function createAdapter(
  harness: ReturnType<typeof createHarness>,
  layers: SceneRuntimeLayer[],
  onLayerFailure?: (layer: SceneRuntimeLayer, error: unknown) => void
) {
  return new CesiumDynamicLayerAdapter(
    harness.runtime,
    harness.viewer as never,
    'scene-root' as never,
    snapshot(layers),
    {
      scheduleFrame: harness.scheduleFrame,
      cancelFrame: harness.cancelFrame,
      scheduleTimer: harness.scheduleTimer,
      cancelTimer: harness.cancelTimer,
      now: harness.now,
      onLayerFailure
    }
  );
}

function createHarness() {
  let nextFrame: FrameRequestCallback | null = null;
  let nextTimer: (() => void) | null = null;
  let nowMillis = Date.parse('2026-07-20T08:00:00Z');
  const pointAdd = vi.fn((options: Record<string, unknown>) => ({ ...options }));
  const pointRemove = vi.fn();
  const polylineAdd = vi.fn((options: Record<string, unknown>) => ({ ...options }));
  const polylineRemove = vi.fn();
  const primitiveAdd = vi.fn((value: unknown) => value);
  const primitiveRemove = vi.fn(() => true);
  const requestRender = vi.fn();
  const scheduleFrame = vi.fn((callback: FrameRequestCallback) => {
    nextFrame = callback;
    return 1;
  });
  const cancelFrame = vi.fn(() => {
    nextFrame = null;
  });
  const scheduleTimer = vi.fn((callback: () => void) => {
    nextTimer = callback;
    return 1 as unknown as ReturnType<typeof setTimeout>;
  });
  const cancelTimer = vi.fn(() => {
    nextTimer = null;
  });
  const runtime = {
    PointPrimitiveCollection: vi.fn(function PointCollectionMock() {
      return { add: pointAdd, remove: pointRemove, isDestroyed: () => false, destroy: vi.fn() };
    }),
    PolylineCollection: vi.fn(function PolylineCollectionMock() {
      return { add: polylineAdd, remove: polylineRemove, isDestroyed: () => false, destroy: vi.fn() };
    }),
    DistanceDisplayCondition: vi.fn(function DistanceMock(near: number, far: number) {
      return { near, far };
    }),
    Material: { fromType: vi.fn((_type: string, options: unknown) => ({ options })) },
    Color: {
      WHITE: color('white'),
      YELLOW: color('yellow'),
      GRAY: color('gray'),
      LIME: color('lime'),
      ORANGE: color('orange'),
      RED: color('red'),
      CYAN: color('cyan')
    }
  } as unknown as CesiumModule;
  const viewer = { scene: { primitives: { add: primitiveAdd, remove: primitiveRemove }, requestRender } };
  return {
    runtime,
    viewer,
    pointAdd,
    pointRemove,
    polylineAdd,
    polylineRemove,
    primitiveAdd,
    primitiveRemove,
    requestRender,
    scheduleFrame,
    cancelFrame,
    scheduleTimer,
    cancelTimer,
    now: () => nowMillis,
    flushFrame() {
      const callback = nextFrame;
      nextFrame = null;
      callback?.(0);
    },
    flushTimer(nextNowMillis: number) {
      nowMillis = nextNowMillis;
      const callback = nextTimer;
      nextTimer = null;
      callback?.();
    }
  };
}

function color(name: string) {
  return { name, withAlpha: (alpha: number) => ({ name, alpha }) };
}

function emptyData() {
  return {
    locations: [] as MineLocationItem[],
    regions: [] as MineSpatialRegion[],
    events: [] as MineSpatialEvent[],
    trajectory: [] as LocationTrajectory['items'],
    selectedTagId: null as string | null,
    nowMillis: Date.parse('2026-07-20T08:00:00Z')
  };
}

function location(overrides: Partial<MineLocationItem> = {}): MineLocationItem {
  return {
    eventId: 'event-1',
    organizationId: 'organization-1',
    siteId: 'site-1',
    sourceDeviceId: 'device-1',
    tagId: 'tag-1',
    tagCode: 'TAG-001',
    coordinateSystemId: 'coordinate-1',
    vehicleId: 'vehicle-1',
    personId: null,
    ingestSequence: 1,
    occurredAt: '2026-07-20T07:59:30Z',
    receivedAt: '2026-07-20T07:59:31Z',
    localX: 10,
    localY: 20,
    localZ: 5,
    speedMetersPerSecond: 3,
    headingDegrees: 90,
    accuracyMeters: 1,
    qualityStatus: 'GOOD',
    qualityReason: null,
    ...overrides
  };
}

function polygonRegion(overrides: Partial<MineSpatialRegion> = {}): MineSpatialRegion {
  return {
    zoneType: 'GEOFENCE',
    zoneId: 'zone-1',
    zoneVersionId: 'version-1',
    coordinateSystemId: 'coordinate-1',
    name: '北区围栏',
    contentHash: `sha256:${'a'.repeat(64)}`,
    shape: 'POLYGON',
    vertices: [
      { x: 0, y: 0 },
      { x: 10, y: 0 },
      { x: 10, y: 10 }
    ],
    ...overrides
  } as MineSpatialRegion;
}

function trajectoryPoint(sequence: number): LocationTrajectory['items'][number] {
  const current = location({ ingestSequence: sequence, localX: sequence });
  const { tagCode: _tagCode, ...point } = current;
  return point;
}

function spatialEvent(eventId: string, withEvidence: boolean): MineSpatialEvent {
  return {
    eventId,
    eventSequence: null,
    organizationId: 'organization-1',
    siteId: 'site-1',
    zoneType: 'GEOFENCE',
    zoneId: 'zone-1',
    zoneVersionId: 'zone-version-1',
    tagId: 'tag-1',
    vehicleId: 'vehicle-1',
    personId: null,
    eventType: 'ENTER',
    previousState: 'OUTSIDE',
    currentState: 'INSIDE',
    confirmationPoints: 3,
    ingestSequence: 11,
    runtimeRevision: 1,
    occurredAt: '2026-07-20T08:00:00Z',
    evidence: withEvidence
      ? {
          localX: 10,
          localY: 20,
          localZ: null,
          classification: 'INSIDE',
          qualityStatus: 'GOOD',
          qualityReason: null,
          zoneContentHash: `sha256:${'a'.repeat(64)}`
        }
      : undefined
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
