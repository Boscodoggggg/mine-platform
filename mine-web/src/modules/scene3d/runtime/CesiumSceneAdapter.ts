import { getApiAuthHeaders } from '@/api/client';
import type { SceneRuntimeLayer, SceneRuntimeSnapshot } from '../api/scene';
import type { CesiumModule } from './load-cesium';
import {
  createGridTransform,
  createLayerTransform,
  createSceneRootTransform,
  resolveCameraView,
  transformCameraOrientation,
  transformScenePosition
} from './scene-transform';
import type { SceneMatrix } from './scene-transform';
import type { SceneViewportAdapter } from './types';
import { CesiumDynamicLayerAdapter } from './CesiumDynamicLayerAdapter';
import type { SceneDynamicData } from './CesiumDynamicLayerAdapter';

export interface SceneLayerFailure {
  layerCode: string;
  layerType: SceneRuntimeLayer['layerType'];
  message: string;
}

export interface CesiumSceneAdapterOptions {
  onLayerFailure?: (failure: SceneLayerFailure) => void;
  onRuntimeFailure?: (error: unknown) => void;
}

type ViewerInstance = InstanceType<CesiumModule['Viewer']>;
type Destroyable = { destroy: () => void; isDestroyed?: () => boolean };

/** 本地离线 Cesium 场景适配器；静态图层失败与宿主级初始化失败严格隔离。 */
export class CesiumSceneAdapter implements SceneViewportAdapter {
  private viewer: ViewerInstance | null = null;
  private dynamicLayers: CesiumDynamicLayerAdapter | null = null;
  private pendingDynamicData: SceneDynamicData | null = null;
  private disposed = false;
  private readonly removeListeners: Array<() => void> = [];
  private readonly failedLayers = new Set<string>();
  private runtimeFailureReported = false;

  constructor(
    private readonly runtime: CesiumModule,
    private readonly options: CesiumSceneAdapterOptions = {}
  ) {}

  async mount(container: HTMLElement, snapshot: SceneRuntimeSnapshot): Promise<void> {
    if (this.disposed || this.viewer) throw new Error('Cesium scene adapter can only be mounted once');

    try {
      const viewer = new this.runtime.Viewer(container, offlineViewerOptions(this.runtime));
      this.viewer = viewer;
      this.observeRuntimeFailures(viewer);
      viewer.scene.globe.show = snapshot.coordinateMode === 'WGS84_ANCHOR';
      const sceneRoot = createSceneRootTransform(this.runtime, snapshot);
      this.dynamicLayers = new CesiumDynamicLayerAdapter(this.runtime, viewer, sceneRoot, snapshot, {
        onLayerFailure: (layer, error) => this.reportLayerFailure(layer, error)
      });
      if (this.pendingDynamicData) this.dynamicLayers.update(this.pendingDynamicData);
      const layers = [...snapshot.layers].sort((left, right) => left.orderNumber - right.orderNumber);
      for (const layer of layers) {
        if (this.disposed) return;
        await this.loadLayer(viewer, sceneRoot, layer);
      }
      if (this.disposed) return;
      this.applyCamera(viewer, sceneRoot, snapshot);
      viewer.scene.requestRender();
    } catch (error) {
      this.destroy();
      throw error;
    }
  }

  destroy(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.removeListeners.splice(0).forEach(remove => remove());
    this.dynamicLayers?.destroy();
    this.dynamicLayers = null;
    const viewer = this.viewer;
    this.viewer = null;
    if (viewer && !isDestroyed(viewer)) viewer.destroy();
  }

  updateDynamicData(data: SceneDynamicData): void {
    this.pendingDynamicData = data;
    this.dynamicLayers?.update(data);
  }

  private async loadLayer(viewer: ViewerInstance, sceneRoot: SceneMatrix, layer: SceneRuntimeLayer) {
    if (!isStaticLayer(layer)) return;
    try {
      const transform = createLayerTransform(this.runtime, sceneRoot, layer);
      if (layer.layerType === 'GRID') {
        this.loadGrid(viewer, transform, layer);
      } else if (layer.layerType === 'STATIC_GLB') {
        await this.loadGlb(viewer, transform, layer);
      } else {
        await this.loadTileset(viewer, transform, layer);
      }
    } catch (error) {
      if (!this.disposed) this.reportLayerFailure(layer, error);
    }
  }

  private loadGrid(viewer: ViewerInstance, transform: SceneMatrix, layer: SceneRuntimeLayer) {
    const width = optionNumber(layer.options.width, 10, 1_000_000, 1000);
    const height = optionNumber(layer.options.height, 10, 1_000_000, 1000);
    const cellsX = optionNumber(layer.options.cellsX, 1, 200, 20);
    const cellsY = optionNumber(layer.options.cellsY, 1, 200, 20);
    const lineWidth = optionNumber(layer.options.lineWidth, 1, 8, 1);
    const distance = displayDistance(layer);
    const color = this.runtime.Color.WHITE.withAlpha(layer.opacity);
    const material = this.runtime.Material.fromType('Grid', {
      color,
      cellAlpha: Math.min(0.5, Math.max(0.02, layer.opacity * 0.15)),
      lineCount: new this.runtime.Cartesian2(cellsX, cellsY),
      lineThickness: new this.runtime.Cartesian2(lineWidth, lineWidth)
    });
    const geometry = new this.runtime.PlaneGeometry({
      vertexFormat: this.runtime.MaterialAppearance.MaterialSupport.TEXTURED.vertexFormat
    });
    const instance = new this.runtime.GeometryInstance({
      geometry,
      attributes: {
        distanceDisplayCondition: new this.runtime.DistanceDisplayConditionGeometryInstanceAttribute(
          distance.near,
          distance.far
        )
      }
    });
    const grid = new this.runtime.Primitive({
      geometryInstances: instance,
      appearance: new this.runtime.MaterialAppearance({ material, translucent: layer.opacity < 1 }),
      modelMatrix: createGridTransform(this.runtime, transform, width, height),
      show: layer.visible && distance.renderable,
      allowPicking: false,
      asynchronous: true
    });
    addOwnedPrimitive(viewer, grid);
  }

  private async loadGlb(viewer: ViewerInstance, transform: SceneMatrix, layer: SceneRuntimeLayer) {
    const resource = this.resourceFor(layer);
    const distance = displayDistance(layer);
    const model = await this.runtime.Model.fromGltfAsync({
      url: resource,
      show: layer.visible && distance.renderable,
      modelMatrix: transform,
      distanceDisplayCondition: new this.runtime.DistanceDisplayCondition(distance.near, distance.far),
      color: this.runtime.Color.WHITE.withAlpha(layer.opacity),
      minimumPixelSize: optionNumber(layer.options.minimumPixelSize, 0, 256, 0),
      maximumScale: optionOptionalNumber(layer.options.maximumScale, 0.000001, 1_000_000),
      allowPicking: true,
      incrementallyLoadTextures: true,
      releaseGltfJson: true
    });
    if (this.disposed) {
      destroyResource(model);
      return;
    }
    addOwnedPrimitive(viewer, model);
  }

  private async loadTileset(viewer: ViewerInstance, transform: SceneMatrix, layer: SceneRuntimeLayer) {
    const resource = this.resourceFor(layer);
    const distanceRange = displayDistance(layer);
    const memoryMegabytes = optionNumber(layer.options.maximumMemoryMegabytes, 32, 1024, 256);
    const tileset = await this.runtime.Cesium3DTileset.fromUrl(resource, {
      show: layer.visible && distanceRange.renderable,
      modelMatrix: transform,
      maximumScreenSpaceError: optionNumber(layer.options.maximumScreenSpaceError, 1, 64, 16),
      cacheBytes: memoryMegabytes * 1024 * 1024,
      maximumCacheOverflowBytes: Math.min(memoryMegabytes, 256) * 1024 * 1024,
      cullRequestsWhileMoving: true,
      dynamicScreenSpaceError: true,
      preloadWhenHidden: false,
      scene: viewer.scene
    });
    if (this.disposed) {
      destroyResource(tileset);
      return;
    }
    tileset.style = new this.runtime.Cesium3DTileStyle({
      color: `color("white", ${layer.opacity})`
    });
    addOwnedPrimitive(viewer, tileset);

    const updateVisibility = () => {
      const distance = this.runtime.Cartesian3.distance(viewer.camera.positionWC, tileset.boundingSphere.center);
      tileset.show =
        layer.visible &&
        distanceRange.renderable &&
        distance >= distanceRange.near &&
        distance <= distanceRange.far;
    };
    this.removeListeners.push(viewer.scene.preRender.addEventListener(updateVisibility));
    this.removeListeners.push(tileset.tileFailed.addEventListener(error => this.reportLayerFailure(layer, error)));
    updateVisibility();
  }

  private resourceFor(layer: SceneRuntimeLayer) {
    if (!layer.asset) throw new Error('Static scene layer is missing its published asset');
    const url = sameOriginPath(layer.asset.entryUrl);
    const resource = new this.runtime.Resource({ url, headers: getApiAuthHeaders() });
    return hardenAssetResource(resource, assetPackagePrefix(url));
  }

  private applyCamera(viewer: ViewerInstance, sceneRoot: SceneMatrix, snapshot: SceneRuntimeSnapshot) {
    const camera = resolveCameraView(snapshot);
    viewer.camera.setView({
      destination: transformScenePosition(this.runtime, sceneRoot, camera.position),
      orientation: transformCameraOrientation(this.runtime, sceneRoot, camera)
    });
  }

  private observeRuntimeFailures(viewer: ViewerInstance) {
    this.removeListeners.push(
      viewer.scene.renderError.addEventListener((_scene, error) => this.reportRuntimeFailure(error))
    );
    const onContextLost = (event: Event) => {
      event.preventDefault();
      this.reportRuntimeFailure(new Error('WebGL context was lost'));
    };
    viewer.scene.canvas.addEventListener('webglcontextlost', onContextLost);
    this.removeListeners.push(() => viewer.scene.canvas.removeEventListener('webglcontextlost', onContextLost));
  }

  private reportRuntimeFailure(error: unknown) {
    if (this.disposed || this.runtimeFailureReported) return;
    this.runtimeFailureReported = true;
    this.options.onRuntimeFailure?.(error);
  }

  private reportLayerFailure(layer: SceneRuntimeLayer, error: unknown) {
    if (this.failedLayers.has(layer.layerCode)) return;
    this.failedLayers.add(layer.layerCode);
    this.options.onLayerFailure?.({
      layerCode: layer.layerCode,
      layerType: layer.layerType,
      message: error instanceof Error ? error.message : 'Scene layer could not be loaded'
    });
  }
}

function offlineViewerOptions(runtime: CesiumModule) {
  return {
    animation: false,
    baseLayerPicker: false,
    fullscreenButton: false,
    vrButton: false,
    geocoder: false,
    homeButton: false,
    infoBox: false,
    sceneModePicker: false,
    selectionIndicator: false,
    timeline: false,
    navigationHelpButton: false,
    projectionPicker: false,
    scene3DOnly: true,
    shouldAnimate: false,
    automaticallyTrackDataSourceClocks: false,
    baseLayer: false as const,
    terrainProvider: new runtime.EllipsoidTerrainProvider(),
    skyBox: false as const,
    skyAtmosphere: false as const,
    requestRenderMode: true,
    maximumRenderTimeChange: Number.POSITIVE_INFINITY,
    showRenderLoopErrors: false,
    shadows: false
  };
}

function isStaticLayer(layer: SceneRuntimeLayer) {
  return layer.layerType === 'GRID' || layer.layerType === 'STATIC_GLB' || layer.layerType === 'STATIC_3D_TILES';
}

function sameOriginPath(value: string) {
  if (!value.startsWith('/') || value.startsWith('//')) throw new Error('Scene asset URL must be a same-origin path');
  const origin = globalThis.location?.origin;
  if (!origin) throw new Error('Scene asset URL requires a browser origin');
  const parsed = new URL(value, origin);
  if (parsed.origin !== origin || parsed.protocol !== globalThis.location.protocol || parsed.search || parsed.hash) {
    throw new Error('Scene asset URL must be a same-origin path without query or fragment');
  }
  return `${parsed.pathname}`;
}

function assetPackagePrefix(entryPath: string) {
  const marker = '/files/';
  const markerIndex = entryPath.lastIndexOf(marker);
  if (markerIndex < 0 || markerIndex + marker.length === entryPath.length) {
    throw new Error('Scene asset URL must identify a file inside an asset package');
  }
  return entryPath.slice(0, markerIndex + marker.length);
}

type CesiumResource = InstanceType<CesiumModule['Resource']>;
const hardenedAssetResources = new WeakMap<object, string>();

function hardenAssetResource(resource: CesiumResource, allowedPathPrefix: string): CesiumResource {
  assertAssetResourceUrl(resource.url, allowedPathPrefix);
  const existingBoundary = hardenedAssetResources.get(resource);
  if (existingBoundary) {
    if (existingBoundary !== allowedPathPrefix) {
      throw new Error('Scene asset resource cannot change its package boundary');
    }
    return resource;
  }
  hardenedAssetResources.set(resource, allowedPathPrefix);
  const getDerivedResource = resource.getDerivedResource.bind(resource);
  const clone = resource.clone.bind(resource);
  resource.getDerivedResource = options =>
    hardenAssetResource(getDerivedResource(options), allowedPathPrefix);
  resource.clone = result => hardenAssetResource(clone(result), allowedPathPrefix);
  return resource;
}

function assertAssetResourceUrl(value: string, allowedPathPrefix: string) {
  const origin = globalThis.location?.origin;
  if (!origin) throw new Error('Scene asset URL requires a browser origin');
  const parsed = new URL(value, origin);
  if (
    parsed.origin !== origin ||
    parsed.protocol !== globalThis.location.protocol ||
    parsed.username ||
    parsed.password ||
    !parsed.pathname.startsWith(allowedPathPrefix) ||
    parsed.pathname.length === allowedPathPrefix.length
  ) {
    throw new Error('Scene asset derived URL must stay inside the same asset package');
  }
}

function optionNumber(value: unknown, minimum: number, maximum: number, fallback: number) {
  return typeof value === 'number' && Number.isFinite(value) && value >= minimum && value <= maximum ? value : fallback;
}

function optionOptionalNumber(value: unknown, minimum: number, maximum: number) {
  return typeof value === 'number' && Number.isFinite(value) && value >= minimum && value <= maximum
    ? value
    : undefined;
}

function displayDistance(layer: SceneRuntimeLayer) {
  return layer.maximumDistance > layer.minimumDistance
    ? { near: layer.minimumDistance, far: layer.maximumDistance, renderable: true }
    : { near: 0, far: Number.MAX_VALUE, renderable: false };
}

function isDestroyed(value: Destroyable) {
  return value.isDestroyed?.() ?? false;
}

function destroyResource(value: Destroyable) {
  if (!isDestroyed(value)) value.destroy();
}

function addOwnedPrimitive(viewer: ViewerInstance, primitive: Destroyable) {
  try {
    viewer.scene.primitives.add(primitive);
  } catch (error) {
    destroyResource(primitive);
    throw error;
  }
}
