import type { LocationTrajectory, MineLocationItem, MineSpatialEvent, MineSpatialRegion } from '@/modules/mine';
import type { SceneRuntimeLayer, SceneRuntimeSnapshot } from '../api/scene';
import type { SceneAlarmMarker, SceneOperationMarker } from '../composables/useSceneBusinessLayers';
import type { CesiumModule } from './load-cesium';
import { createLayerTransform, transformScenePosition } from './scene-transform';
import type { SceneMatrix } from './scene-transform';

export interface SceneDynamicData {
  locations: MineLocationItem[];
  regions: MineSpatialRegion[];
  events: MineSpatialEvent[];
  trajectory: LocationTrajectory['items'];
  alarms: SceneAlarmMarker[];
  operations: SceneOperationMarker[];
  selectedTagId: string | null;
  nowMillis?: number;
}

export interface CesiumDynamicLayerAdapterOptions {
  scheduleFrame?: (callback: FrameRequestCallback) => number;
  cancelFrame?: (handle: number) => void;
  scheduleTimer?: (callback: () => void, delayMillis: number) => ReturnType<typeof setTimeout>;
  cancelTimer?: (handle: ReturnType<typeof setTimeout>) => void;
  now?: () => number;
  onLayerFailure?: (layer: SceneRuntimeLayer, error: unknown) => void;
}

type ViewerInstance = InstanceType<CesiumModule['Viewer']>;
type PointCollectionInstance = InstanceType<CesiumModule['PointPrimitiveCollection']>;
type PolylineCollectionInstance = InstanceType<CesiumModule['PolylineCollection']>;
type OwnedCollection = PointCollectionInstance | PolylineCollectionInstance;

interface DynamicPoint {
  position: unknown;
  show: boolean;
  pixelSize: number;
  color: unknown;
  outlineColor: unknown;
  outlineWidth: number;
  distanceDisplayCondition: unknown;
}

interface DynamicPolyline {
  positions: unknown[];
  show: boolean;
  width: number;
  material: unknown;
  distanceDisplayCondition: unknown;
}

interface RegionPrimitive {
  versionId: string;
  primitive: DynamicPolyline;
}

interface LocationPrimitive {
  ingestSequence: number;
  styleKey: string;
  primitive: DynamicPoint;
}

const EMPTY_DATA: SceneDynamicData = Object.freeze({
  locations: [],
  regions: [],
  events: [],
  trajectory: [],
  alarms: [],
  operations: [],
  selectedTagId: null
});

/** 使用有界对象池和动画帧合并更新 Cesium 动态位置、区域、事件与轨迹图层。 */
export class CesiumDynamicLayerAdapter {
  private readonly scheduleFrame: (callback: FrameRequestCallback) => number;
  private readonly cancelFrame: (handle: number) => void;
  private readonly scheduleTimer: (
    callback: () => void,
    delayMillis: number
  ) => ReturnType<typeof setTimeout>;
  private readonly cancelTimer: (handle: ReturnType<typeof setTimeout>) => void;
  private readonly now: () => number;
  private readonly locationLayer: SceneRuntimeLayer | null;
  private readonly regionLayer: SceneRuntimeLayer | null;
  private readonly trajectoryLayer: SceneRuntimeLayer | null;
  private readonly alarmLayer: SceneRuntimeLayer | null;
  private readonly operationLayer: SceneRuntimeLayer | null;
  private readonly locationTransform: SceneMatrix | null;
  private readonly regionTransform: SceneMatrix | null;
  private readonly trajectoryTransform: SceneMatrix | null;
  private readonly alarmTransform: SceneMatrix | null;
  private readonly operationTransform: SceneMatrix | null;
  private locationPoints: PointCollectionInstance | null = null;
  private eventPoints: PointCollectionInstance | null = null;
  private regionLines: PolylineCollectionInstance | null = null;
  private trajectoryLines: PolylineCollectionInstance | null = null;
  private alarmPoints: PointCollectionInstance | null = null;
  private operationPoints: PointCollectionInstance | null = null;
  private readonly ownedCollections: OwnedCollection[] = [];
  private readonly locationPool = new Map<string, LocationPrimitive>();
  private readonly eventPool = new Map<string, DynamicPoint>();
  private readonly regionPool = new Map<string, RegionPrimitive>();
  private readonly alarmPool = new Map<string, DynamicPoint>();
  private readonly operationPool = new Map<string, DynamicPoint>();
  private trajectoryPrimitive: DynamicPolyline | null = null;
  private pendingData: SceneDynamicData = EMPTY_DATA;
  private frameHandle: number | null = null;
  private staleTimerHandle: ReturnType<typeof setTimeout> | null = null;
  private disposed = false;

  constructor(
    private readonly runtime: CesiumModule,
    private readonly viewer: ViewerInstance,
    sceneRoot: SceneMatrix,
    snapshot: SceneRuntimeSnapshot,
    private readonly options: CesiumDynamicLayerAdapterOptions = {}
  ) {
    this.scheduleFrame = options.scheduleFrame ?? (callback => globalThis.requestAnimationFrame(callback));
    this.cancelFrame = options.cancelFrame ?? (handle => globalThis.cancelAnimationFrame(handle));
    this.scheduleTimer = options.scheduleTimer ?? ((callback, delay) => globalThis.setTimeout(callback, delay));
    this.cancelTimer = options.cancelTimer ?? (handle => globalThis.clearTimeout(handle));
    this.now = options.now ?? (() => Date.now());
    this.locationLayer = dynamicLayer(snapshot, 'DYNAMIC_LOCATIONS');
    this.regionLayer = dynamicLayer(snapshot, 'DYNAMIC_REGIONS');
    this.trajectoryLayer = dynamicLayer(snapshot, 'DYNAMIC_TRAJECTORY');
    this.alarmLayer = dynamicLayer(snapshot, 'DYNAMIC_ALARMS');
    this.operationLayer = dynamicLayer(snapshot, 'DYNAMIC_OPERATIONS');
    this.locationTransform = transformFor(this.runtime, sceneRoot, this.locationLayer);
    this.regionTransform = transformFor(this.runtime, sceneRoot, this.regionLayer);
    this.trajectoryTransform = transformFor(this.runtime, sceneRoot, this.trajectoryLayer);
    this.alarmTransform = transformFor(this.runtime, sceneRoot, this.alarmLayer);
    this.operationTransform = transformFor(this.runtime, sceneRoot, this.operationLayer);
    this.createCollections();
  }

  update(data: SceneDynamicData) {
    if (this.disposed) return;
    this.pendingData = data;
    this.queueFrame();
  }

  private queueFrame() {
    if (this.frameHandle !== null) return;
    this.frameHandle = this.scheduleFrame(() => {
      this.frameHandle = null;
      if (!this.disposed) this.applyPendingData();
    });
  }

  destroy() {
    if (this.disposed) return;
    this.disposed = true;
    if (this.frameHandle !== null) this.cancelFrame(this.frameHandle);
    if (this.staleTimerHandle !== null) this.cancelTimer(this.staleTimerHandle);
    this.frameHandle = null;
    this.staleTimerHandle = null;
    this.ownedCollections.splice(0).forEach(collection => {
      this.viewer.scene.primitives.remove(collection);
    });
    this.locationPool.clear();
    this.eventPool.clear();
    this.regionPool.clear();
    this.alarmPool.clear();
    this.operationPool.clear();
    this.trajectoryPrimitive = null;
  }

  private createCollections() {
    if (this.locationLayer) {
      this.locationPoints = this.addCollection(this.locationLayer, () => new this.runtime.PointPrimitiveCollection());
    }
    if (this.regionLayer) {
      this.eventPoints = this.addCollection(this.regionLayer, () => new this.runtime.PointPrimitiveCollection());
    }
    if (this.regionLayer) {
      this.regionLines = this.addCollection(this.regionLayer, () => new this.runtime.PolylineCollection());
    }
    if (this.trajectoryLayer) {
      this.trajectoryLines = this.addCollection(this.trajectoryLayer, () => new this.runtime.PolylineCollection());
    }
    if (this.alarmLayer) {
      this.alarmPoints = this.addCollection(this.alarmLayer, () => new this.runtime.PointPrimitiveCollection());
    }
    if (this.operationLayer) {
      this.operationPoints = this.addCollection(this.operationLayer, () => new this.runtime.PointPrimitiveCollection());
    }
  }

  private addCollection<T extends OwnedCollection>(layer: SceneRuntimeLayer, factory: () => T): T | null {
    let collection: T | null = null;
    try {
      collection = factory();
      this.viewer.scene.primitives.add(collection);
      this.ownedCollections.push(collection);
      return collection;
    } catch (error) {
      if (collection && !collection.isDestroyed()) collection.destroy();
      this.options.onLayerFailure?.(layer, error);
      return null;
    }
  }

  private applyPendingData() {
    const data = this.pendingData;
    this.applyLayer(this.locationLayer, () => this.applyLocations(data));
    this.applyLayer(this.regionLayer, () => {
      this.applyRegions(data.regions);
      this.applyEvents(data.events);
    });
    this.applyLayer(this.trajectoryLayer, () => this.applyTrajectory(data));
    this.applyLayer(this.alarmLayer, () => this.applyAlarms(data.alarms));
    this.applyLayer(this.operationLayer, () => this.applyOperations(data.operations));
    this.viewer.scene.requestRender();
  }

  private applyLayer(layer: SceneRuntimeLayer | null, apply: () => void) {
    if (!layer) return;
    try {
      apply();
    } catch (error) {
      this.options.onLayerFailure?.(layer, error);
    }
  }

  private applyLocations(data: SceneDynamicData) {
    const layer = this.locationLayer;
    const collection = this.locationPoints;
    const transform = this.locationTransform;
    if (!layer || !collection || !transform) return;
    this.locationPool.forEach(record => {
      record.primitive.show = false;
    });
    const maximum = boundedItems(layer.maxItems);
    const items = [...data.locations]
      .filter(validLocation)
      .sort((left, right) => right.ingestSequence - left.ingestSequence)
      .slice(0, maximum);
    const distance = displayDistance(this.runtime, layer);
    for (const item of items) {
      const degraded = item.qualityStatus !== 'GOOD';
      const stale = (data.nowMillis ?? Date.now()) - Date.parse(item.occurredAt) > staleAfterMillis(layer);
      const selected = data.selectedTagId === item.tagId;
      const styleKey = `${selected}:${degraded}:${stale}`;
      const style = stale
        ? { color: this.runtime.Color.GRAY, outline: this.runtime.Color.WHITE }
        : degraded
          ? { color: this.runtime.Color.YELLOW, outline: this.runtime.Color.ORANGE }
          : { color: this.runtime.Color.WHITE, outline: this.runtime.Color.CYAN };
      const visual = {
        show: distance.renderable,
        pixelSize: optionNumber(layer.options.pointSize, 4, 32, selected ? 14 : 10),
        color: style.color.withAlpha(layer.opacity),
        outlineColor: style.outline.withAlpha(layer.opacity),
        outlineWidth: selected || degraded || stale ? 3 : 1
      };
      const existing = this.locationPool.get(item.tagId);
      if (existing) {
        existing.primitive.show = distance.renderable;
        if (existing.ingestSequence !== item.ingestSequence) {
          existing.primitive.position = transformScenePosition(this.runtime, transform, [
            item.localX,
            item.localY,
            item.localZ ?? 0
          ]);
          existing.ingestSequence = item.ingestSequence;
        }
        if (existing.styleKey !== styleKey) {
          Object.assign(existing.primitive, visual);
          existing.styleKey = styleKey;
        }
      } else {
        const primitive = collection.add({
          ...visual,
          position: transformScenePosition(this.runtime, transform, [item.localX, item.localY, item.localZ ?? 0]),
          distanceDisplayCondition: distance.condition,
          id: { type: 'LOCATION', tagId: item.tagId }
        }) as DynamicPoint;
        this.locationPool.set(item.tagId, { ingestSequence: item.ingestSequence, styleKey, primitive });
      }
    }
    const activeIds = new Set(items.map(item => item.tagId));
    this.locationPool.forEach((record, tagId) => {
      if (activeIds.has(tagId)) return;
      collection.remove(record.primitive as never);
      this.locationPool.delete(tagId);
    });
    this.scheduleStaleRefresh(items, layer, data.nowMillis ?? this.now());
  }

  private scheduleStaleRefresh(items: MineLocationItem[], layer: SceneRuntimeLayer, nowMillis: number) {
    if (this.staleTimerHandle !== null) this.cancelTimer(this.staleTimerHandle);
    this.staleTimerHandle = null;
    const staleMillis = staleAfterMillis(layer);
    const nextDeadline = items.reduce((earliest, item) => {
      const occurredAt = Date.parse(item.occurredAt);
      const deadline = occurredAt + staleMillis;
      return Number.isFinite(deadline) && deadline > nowMillis ? Math.min(earliest, deadline) : earliest;
    }, Number.POSITIVE_INFINITY);
    if (!Number.isFinite(nextDeadline)) return;
    this.staleTimerHandle = this.scheduleTimer(() => {
      this.staleTimerHandle = null;
      if (this.disposed) return;
      this.pendingData = { ...this.pendingData, nowMillis: this.now() };
      this.queueFrame();
    }, Math.max(16, nextDeadline - nowMillis + 1));
  }

  private applyRegions(regions: MineSpatialRegion[]) {
    const layer = this.regionLayer;
    const collection = this.regionLines;
    const transform = this.regionTransform;
    if (!layer || !collection || !transform) return;
    const visibleIds = new Set<string>();
    const distance = displayDistance(this.runtime, layer);
    regions.slice(0, boundedItems(layer.maxItems)).forEach(region => {
      visibleIds.add(region.zoneId);
      const existing = this.regionPool.get(region.zoneId);
      if (existing?.versionId === region.zoneVersionId) {
        existing.primitive.show = distance.renderable;
        return;
      }
      if (existing) collection.remove(existing.primitive as never);
      const primitive = collection.add({
        positions: regionPositions(this.runtime, transform, region, layer),
        show: distance.renderable,
        width: optionNumber(layer.options.lineWidth, 1, 12, 3),
        material: this.runtime.Material.fromType('Color', {
          color: this.runtime.Color.LIME.withAlpha(layer.opacity)
        }),
        distanceDisplayCondition: distance.condition,
        id: { type: 'REGION', zoneId: region.zoneId, zoneVersionId: region.zoneVersionId }
      }) as DynamicPolyline;
      this.regionPool.set(region.zoneId, { versionId: region.zoneVersionId, primitive });
    });
    this.regionPool.forEach((record, zoneId) => {
      if (visibleIds.has(zoneId)) return;
      collection.remove(record.primitive as never);
      this.regionPool.delete(zoneId);
    });
  }

  private applyEvents(events: MineSpatialEvent[]) {
    const layer = this.regionLayer;
    const collection = this.eventPoints;
    const transform = this.regionTransform;
    if (!layer || !collection || !transform) return;
    this.eventPool.forEach(point => {
      point.show = false;
    });
    const maximum = Math.min(boundedItems(layer.maxItems), optionNumber(layer.options.maxEvents, 1, 1000, 100));
    const visibleIds = new Set<string>();
    const distance = displayDistance(this.runtime, layer);
    events
      .filter(event => validEvidence(event.evidence))
      .slice(0, maximum)
      .forEach(event => {
        visibleIds.add(event.eventId);
        const evidence = event.evidence!;
        const color = event.eventType === 'EXIT' ? this.runtime.Color.ORANGE : this.runtime.Color.RED;
        const values = {
          position: transformScenePosition(this.runtime, transform, [
            evidence.localX,
            evidence.localY,
            evidence.localZ ?? 0
          ]),
          show: distance.renderable,
          pixelSize: optionNumber(layer.options.eventPointSize, 4, 32, 12),
          color: color.withAlpha(layer.opacity),
          outlineColor: this.runtime.Color.WHITE.withAlpha(layer.opacity),
          outlineWidth: 2,
          distanceDisplayCondition: distance.condition
        };
        const existing = this.eventPool.get(event.eventId);
        if (existing) Object.assign(existing, values);
        else this.eventPool.set(event.eventId, collection.add({ ...values, id: { type: 'EVENT', eventId: event.eventId } }) as DynamicPoint);
      });
    this.eventPool.forEach((point, eventId) => {
      if (visibleIds.has(eventId)) return;
      collection.remove(point as never);
      this.eventPool.delete(eventId);
    });
  }

  private applyTrajectory(data: SceneDynamicData) {
    const layer = this.trajectoryLayer;
    const collection = this.trajectoryLines;
    const transform = this.trajectoryTransform;
    if (!layer || !collection || !transform) return;
    const points = data.selectedTagId
      ? [...data.trajectory]
          .filter(validTrajectoryPoint)
          .sort((left, right) => left.ingestSequence - right.ingestSequence)
          .slice(-boundedItems(layer.maxItems))
      : [];
    if (points.length < 2) {
      if (this.trajectoryPrimitive) this.trajectoryPrimitive.show = false;
      return;
    }
    const values = {
      positions: points.map(point =>
        transformScenePosition(this.runtime, transform, [point.localX, point.localY, point.localZ ?? 0])
      ),
      show: displayDistance(this.runtime, layer).renderable,
      width: optionNumber(layer.options.lineWidth, 1, 12, 4),
      material: this.runtime.Material.fromType('Color', {
        color: this.runtime.Color.CYAN.withAlpha(layer.opacity)
      }),
      distanceDisplayCondition: displayDistance(this.runtime, layer).condition
    };
    if (this.trajectoryPrimitive) {
      Object.assign(this.trajectoryPrimitive, values);
      (this.trajectoryPrimitive as DynamicPolyline & { id?: unknown }).id = {
        type: 'TRAJECTORY',
        tagId: data.selectedTagId
      };
    }
    else this.trajectoryPrimitive = collection.add({ ...values, id: { type: 'TRAJECTORY', tagId: data.selectedTagId } }) as DynamicPolyline;
  }

  private applyAlarms(markers: SceneAlarmMarker[]) {
    const layer = this.alarmLayer;
    const collection = this.alarmPoints;
    const transform = this.alarmTransform;
    if (!layer || !collection || !transform) return;
    const distance = displayDistance(this.runtime, layer);
    const visibleIds = new Set<string>();
    markers.slice(0, boundedItems(layer.maxItems)).forEach(marker => {
      visibleIds.add(marker.alarm.id);
      const color =
        marker.alarm.severity === 'CRITICAL'
          ? this.runtime.Color.RED
          : marker.alarm.severity === 'WARNING'
            ? this.runtime.Color.ORANGE
            : this.runtime.Color.YELLOW;
      const values = {
        position: transformScenePosition(this.runtime, transform, [
          marker.location.localX,
          marker.location.localY,
          marker.location.localZ ?? 0
        ]),
        show: distance.renderable,
        pixelSize: optionNumber(layer.options.pointSize, 6, 40, 18),
        color: color.withAlpha(layer.opacity),
        outlineColor: this.runtime.Color.WHITE.withAlpha(layer.opacity),
        outlineWidth: 3,
        distanceDisplayCondition: distance.condition
      };
      const existing = this.alarmPool.get(marker.alarm.id);
      if (existing) Object.assign(existing, values);
      else {
        this.alarmPool.set(
          marker.alarm.id,
          collection.add({ ...values, id: { type: 'ALARM', alarmId: marker.alarm.id } }) as DynamicPoint
        );
      }
    });
    removeMissingPoints(collection, this.alarmPool, visibleIds);
  }

  private applyOperations(markers: SceneOperationMarker[]) {
    const layer = this.operationLayer;
    const collection = this.operationPoints;
    const transform = this.operationTransform;
    if (!layer || !collection || !transform) return;
    const distance = displayDistance(this.runtime, layer);
    const visibleIds = new Set<string>();
    markers.slice(0, boundedItems(layer.maxItems)).forEach(marker => {
      visibleIds.add(marker.markerId);
      const values = {
        position: transformScenePosition(this.runtime, transform, [
          marker.location.localX,
          marker.location.localY,
          marker.location.localZ ?? 0
        ]),
        show: distance.renderable,
        pixelSize: optionNumber(layer.options.pointSize, 6, 40, 16),
        color: this.runtime.Color.LIME.withAlpha(layer.opacity),
        outlineColor: this.runtime.Color.CYAN.withAlpha(layer.opacity),
        outlineWidth: 3,
        distanceDisplayCondition: distance.condition
      };
      const existing = this.operationPool.get(marker.markerId);
      if (existing) Object.assign(existing, values);
      else {
        this.operationPool.set(
          marker.markerId,
          collection.add({ ...values, id: { type: 'OPERATION', workOrderId: marker.workOrder.id } }) as DynamicPoint
        );
      }
    });
    removeMissingPoints(collection, this.operationPool, visibleIds);
  }
}

function dynamicLayer(snapshot: SceneRuntimeSnapshot, type: SceneRuntimeLayer['layerType']) {
  return snapshot.layers.find(layer => layer.layerType === type && layer.visible) ?? null;
}

function transformFor(runtime: CesiumModule, sceneRoot: SceneMatrix, layer: SceneRuntimeLayer | null) {
  return layer ? createLayerTransform(runtime, sceneRoot, layer) : null;
}

function boundedItems(value: number) {
  return Number.isInteger(value) && value > 0 ? Math.min(value, 100_000) : 0;
}

function staleAfterMillis(layer: SceneRuntimeLayer) {
  return optionNumber(layer.options.staleAfterSeconds, 10, 86_400, 120) * 1000;
}

function displayDistance(runtime: CesiumModule, layer: SceneRuntimeLayer) {
  const near = Math.max(0, layer.minimumDistance);
  const renderable = layer.maximumDistance > near;
  const far = renderable ? layer.maximumDistance : Number.MAX_VALUE;
  return { renderable, condition: new runtime.DistanceDisplayCondition(renderable ? near : 0, far) };
}

function optionNumber(value: unknown, minimum: number, maximum: number, fallback: number) {
  return typeof value === 'number' && Number.isFinite(value) && value >= minimum && value <= maximum ? value : fallback;
}

function validLocation(item: MineLocationItem) {
  return Number.isFinite(item.localX) && Number.isFinite(item.localY) && item.ingestSequence > 0;
}

function validTrajectoryPoint(item: LocationTrajectory['items'][number]) {
  return Number.isFinite(item.localX) && Number.isFinite(item.localY) && item.ingestSequence > 0;
}

function validEvidence(evidence: MineSpatialEvent['evidence']): evidence is NonNullable<MineSpatialEvent['evidence']> {
  return Boolean(evidence && Number.isFinite(evidence.localX) && Number.isFinite(evidence.localY));
}

function regionPositions(
  runtime: CesiumModule,
  transform: SceneMatrix,
  region: MineSpatialRegion,
  layer: SceneRuntimeLayer
) {
  const height = optionNumber(layer.options.height, -100_000, 100_000, 0);
  const coordinates =
    region.shape === 'CIRCLE'
      ? circleCoordinates(region.x, region.y, region.radiusMeters, optionNumber(layer.options.circleSegments, 12, 128, 48))
      : [...region.vertices, region.vertices[0]!].map(vertex => [vertex.x, vertex.y] as const);
  return coordinates.map(([x, y]) => transformScenePosition(runtime, transform, [x, y, height]));
}

function circleCoordinates(centerX: number, centerY: number, radius: number, segments: number) {
  return Array.from({ length: segments + 1 }, (_, index) => {
    const radians = (index / segments) * Math.PI * 2;
    return [centerX + Math.cos(radians) * radius, centerY + Math.sin(radians) * radius] as const;
  });
}

function removeMissingPoints(
  collection: PointCollectionInstance,
  pool: Map<string, DynamicPoint>,
  visibleIds: Set<string>
) {
  pool.forEach((point, id) => {
    if (visibleIds.has(id)) return;
    collection.remove(point as never);
    pool.delete(id);
  });
}
