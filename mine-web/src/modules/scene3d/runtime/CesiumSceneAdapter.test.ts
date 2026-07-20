import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Resource as CesiumResource } from 'cesium';
import { localStg } from '@/utils/storage';
import type { SceneRuntimeLayer, SceneRuntimeSnapshot } from '../api/scene';
import type { CesiumModule } from './load-cesium';
import { CesiumSceneAdapter } from './CesiumSceneAdapter';

const transforms = vi.hoisted(() => ({
  createSceneRootTransform: vi.fn(() => 'scene-root'),
  createLayerTransform: vi.fn((_runtime, _root, layer: SceneRuntimeLayer) => `layer:${layer.layerCode}`),
  createGridTransform: vi.fn((_runtime, layerTransform) => `grid:${layerTransform}`),
  resolveCameraView: vi.fn(() => ({
    position: [100, 200, 300] as [number, number, number],
    headingDegrees: 0,
    pitchDegrees: -45,
    rollDegrees: 0,
    source: 'DEFAULT_CAMERA' as const
  })),
  transformCameraOrientation: vi.fn(() => ({ direction: 'camera-direction', up: 'camera-up' })),
  transformScenePosition: vi.fn(() => 'camera-destination')
}));
const dynamicLayers = vi.hoisted(() => ({
  constructor: vi.fn(),
  update: vi.fn(),
  destroy: vi.fn()
}));

vi.mock('./scene-transform', () => transforms);
vi.mock('./CesiumDynamicLayerAdapter', () => ({
  CesiumDynamicLayerAdapter: dynamicLayers.constructor
}));

describe('CesiumSceneAdapter', () => {
  beforeEach(() => {
    localStg.set('tokenName', 'X-Mine-Token');
    localStg.set('token', 'scene-token');
    dynamicLayers.update.mockReset();
    dynamicLayers.destroy.mockReset();
    dynamicLayers.constructor.mockReset().mockImplementation(function DynamicLayerMock() {
      return { update: dynamicLayers.update, destroy: dynamicLayers.destroy };
    });
  });

  it('使用离线 Viewer 配置并按发布顺序加载 GRID、GLB 和 3D Tiles', async () => {
    const harness = createCesiumHarness();
    const adapter = new CesiumSceneAdapter(harness.runtime);
    const snapshot = sceneSnapshot([
      staticTilesLayer({ orderNumber: 3 }),
      gridLayer({ orderNumber: 1, opacity: 0.4, minimumDistance: 10, maximumDistance: 5000 }),
      staticGlbLayer({ orderNumber: 2, opacity: 0.65, minimumDistance: 20, maximumDistance: 8000 })
    ]);

    await adapter.mount(document.createElement('div'), snapshot);

    expect(harness.viewerOptions).toMatchObject({
      animation: false,
      baseLayer: false,
      baseLayerPicker: false,
      geocoder: false,
      terrainProvider: harness.terrainProvider,
      timeline: false,
      requestRenderMode: true,
      maximumRenderTimeChange: Number.POSITIVE_INFINITY,
      skyBox: false,
      skyAtmosphere: false
    });
    expect(harness.viewer.scene.globe.show).toBe(false);
    expect(harness.loadOrder).toEqual(['grid:coordinate-grid', 'glb:pit-model', 'tiles:pit-tiles']);
    expect(harness.resources.map(item => item.url)).toEqual([
      snapshot.layers[2]!.asset!.entryUrl,
      snapshot.layers[0]!.asset!.entryUrl
    ]);
    expect(harness.resources.every(item => item.headers['x-mine-token'] === 'scene-token')).toBe(true);
    expect(harness.modelOptions).toMatchObject({
      show: true,
      modelMatrix: 'layer:pit-model',
      distanceDisplayCondition: { near: 20, far: 8000 }
    });
    expect(harness.modelOptions.color).toEqual({ alpha: 0.65 });
    expect(harness.tilesetOptions).toMatchObject({ show: true, modelMatrix: 'layer:pit-tiles' });
    expect(harness.tileset.style).toEqual({ style: { color: 'color("white", 1)' } });
    expect(harness.camera.setView).toHaveBeenCalledWith({
      destination: 'camera-destination',
      orientation: { direction: 'camera-direction', up: 'camera-up' }
    });
    expect(harness.requestRender).toHaveBeenCalled();
  });

  it('单个静态资源失败只降级对应图层并继续加载后续图层', async () => {
    const harness = createCesiumHarness();
    harness.modelFromGltf.mockImplementationOnce(async () => {
      harness.loadOrder.push('glb:pit-model');
      throw new Error('broken glb');
    });
    const failures = vi.fn();
    const adapter = new CesiumSceneAdapter(harness.runtime, { onLayerFailure: failures });

    await expect(
      adapter.mount(
        document.createElement('div'),
        sceneSnapshot([staticGlbLayer({ orderNumber: 1 }), staticTilesLayer({ orderNumber: 2 })])
      )
    ).resolves.toBeUndefined();

    expect(failures).toHaveBeenCalledOnce();
    expect(failures).toHaveBeenCalledWith(expect.objectContaining({ layerCode: 'pit-model' }));
    expect(harness.loadOrder).toEqual(['glb:pit-model', 'tiles:pit-tiles']);
    expect(harness.primitiveAdd).toHaveBeenCalledWith(harness.tileset);
    expect(harness.camera.setView).toHaveBeenCalledOnce();
  });

  it('零宽显示距离按隐藏图层处理且不会触发 Cesium 距离属性异常', async () => {
    const harness = createCesiumHarness();
    const failures = vi.fn();
    const adapter = new CesiumSceneAdapter(harness.runtime, { onLayerFailure: failures });

    await adapter.mount(
      document.createElement('div'),
      sceneSnapshot([gridLayer({ minimumDistance: 0, maximumDistance: 0 })])
    );

    expect(failures).not.toHaveBeenCalled();
    expect(harness.distanceAttribute).toHaveBeenCalledWith(0, Number.MAX_VALUE);
    expect(harness.Primitive.mock.calls[0]![0]).toMatchObject({ show: false });
  });

  it('拒绝跨源资源入口且不影响同场景其他静态图层', async () => {
    const harness = createCesiumHarness();
    const failures = vi.fn();
    const adapter = new CesiumSceneAdapter(harness.runtime, { onLayerFailure: failures });
    const external = staticGlbLayer({
      orderNumber: 1,
      asset: { ...staticGlbLayer().asset!, entryUrl: 'https://outside.example/model.glb' }
    });

    await adapter.mount(document.createElement('div'), sceneSnapshot([external, staticTilesLayer({ orderNumber: 2 })]));

    expect(harness.modelFromGltf).not.toHaveBeenCalled();
    expect(harness.tilesetFromUrl).toHaveBeenCalledOnce();
    expect(failures).toHaveBeenCalledWith(expect.objectContaining({ layerCode: 'pit-model' }));
  });

  it('递归拒绝 Cesium 派生资源越出当前资源包且认证头不离开同源', async () => {
    const harness = createCesiumHarness();
    const adapter = new CesiumSceneAdapter(harness.runtime);
    await adapter.mount(document.createElement('div'), sceneSnapshot([staticTilesLayer()]));

    const root = harness.resources[0]!;
    const nested = root.getDerivedResource({ url: 'subtrees/0.subtree' });

    expect(nested.headers['x-mine-token']).toBe('scene-token');
    expect(() => root.getDerivedResource({ url: 'https://attacker.example/tile.glb' })).toThrow(
      'same asset package'
    );
    expect(() => nested.getDerivedResource({ url: 'https://attacker.example/schema.json' })).toThrow(
      'same asset package'
    );
  });

  it('真实 Cesium Resource 派生和克隆后仍保留资源包安全边界', async () => {
    const harness = createCesiumHarness();
    Object.assign(harness.runtime, { Resource: CesiumResource });
    const adapter = new CesiumSceneAdapter(harness.runtime);
    await adapter.mount(document.createElement('div'), sceneSnapshot([staticTilesLayer()]));

    const root = harness.tilesetFromUrl.mock.calls[0]![0] as CesiumResource;
    const nested = root.getDerivedResource({ url: 'models/tile.glb' });
    const cloned = nested.clone();

    expect(new URL(nested.url).pathname).toContain('/files/models/tile.glb');
    expect(() => cloned.getDerivedResource({ url: 'https://attacker.example/steal.bin' })).toThrow(
      'same asset package'
    );
  });

  it('真实 Cesium Resource 连续深层派生保持线性且不会重复包装', async () => {
    const harness = createCesiumHarness();
    Object.assign(harness.runtime, { Resource: CesiumResource });
    const adapter = new CesiumSceneAdapter(harness.runtime);
    await adapter.mount(document.createElement('div'), sceneSnapshot([staticTilesLayer()]));

    let current = harness.tilesetFromUrl.mock.calls[0]![0] as CesiumResource;
    for (let depth = 0; depth < 32; depth += 1) {
      current = current.getDerivedResource({ url: `level-${depth}/tile.json` });
    }

    expect(new URL(current.url).pathname).toContain('/files/level-0/level-1/');
    expect(() => current.getDerivedResource({ url: 'https://attacker.example/deep.bin' })).toThrow(
      'same asset package'
    );
  });

  it('挂载后的渲染错误只通知宿主一次并在销毁时释放监听', async () => {
    const harness = createCesiumHarness();
    const runtimeFailure = vi.fn();
    const adapter = new CesiumSceneAdapter(harness.runtime, { onRuntimeFailure: runtimeFailure });
    await adapter.mount(document.createElement('div'), sceneSnapshot([gridLayer()]));

    harness.raiseRenderError(new Error('worker failed'));
    harness.raiseContextLost();

    expect(runtimeFailure).toHaveBeenCalledOnce();
    adapter.destroy();
    expect(harness.removeRenderError).toHaveBeenCalledOnce();
    expect(harness.removeContextLost).toHaveBeenCalledOnce();
  });

  it('缓存挂载前动态快照并在销毁 Viewer 前释放动态对象池', async () => {
    const harness = createCesiumHarness();
    const adapter = new CesiumSceneAdapter(harness.runtime);
    const data = { locations: [], regions: [], events: [], trajectory: [], selectedTagId: null };

    adapter.updateDynamicData(data);
    await adapter.mount(document.createElement('div'), sceneSnapshot([gridLayer()]));
    adapter.destroy();

    expect(dynamicLayers.constructor).toHaveBeenCalledOnce();
    expect(dynamicLayers.update).toHaveBeenCalledWith(data);
    expect(dynamicLayers.destroy).toHaveBeenCalledOnce();
    expect(dynamicLayers.destroy.mock.invocationCallOrder[0]).toBeLessThan(harness.viewerDestroy.mock.invocationCallOrder[0]!);
  });

  it('关键相机初始化失败会释放 Viewer 并通知宿主执行二维降级', async () => {
    const harness = createCesiumHarness();
    harness.camera.setView.mockImplementationOnce(() => {
      throw new Error('camera failed');
    });
    const adapter = new CesiumSceneAdapter(harness.runtime);

    await expect(adapter.mount(document.createElement('div'), sceneSnapshot([gridLayer()]))).rejects.toThrow(
      'camera failed'
    );
    expect(harness.viewerDestroy).toHaveBeenCalledOnce();
    expect(harness.removePreRender).toHaveBeenCalledTimes(0);
  });

  it('destroy 幂等释放帧监听、全部 Cesium 资源和 Viewer', async () => {
    const harness = createCesiumHarness();
    const adapter = new CesiumSceneAdapter(harness.runtime);
    await adapter.mount(document.createElement('div'), sceneSnapshot([staticTilesLayer()]));

    adapter.destroy();
    adapter.destroy();

    expect(harness.removePreRender).toHaveBeenCalledOnce();
    expect(harness.removeTileFailed).toHaveBeenCalledOnce();
    expect(harness.viewerDestroy).toHaveBeenCalledOnce();
  });

  it('挂载中销毁会释放晚到的模型且不再加入已销毁 Viewer', async () => {
    const harness = createCesiumHarness();
    const pendingModel = deferred<typeof harness.model>();
    harness.modelFromGltf.mockImplementationOnce(async () => {
      harness.loadOrder.push('glb:pit-model');
      return pendingModel.promise;
    });
    const adapter = new CesiumSceneAdapter(harness.runtime);
    const mounting = adapter.mount(document.createElement('div'), sceneSnapshot([staticGlbLayer()]));
    await vi.waitFor(() => expect(harness.modelFromGltf).toHaveBeenCalledOnce());

    adapter.destroy();
    pendingModel.resolve(harness.model);
    await mounting;

    expect(harness.model.destroy).toHaveBeenCalledOnce();
    expect(harness.primitiveAdd).not.toHaveBeenCalledWith(harness.model);
    expect(harness.viewerDestroy).toHaveBeenCalledOnce();
  });
});

function createCesiumHarness() {
  type MockResource = {
    url: string;
    headers: Record<string, string>;
    getDerivedResource: (options: { url: string }) => MockResource;
    clone: () => MockResource;
  };
  const loadOrder: string[] = [];
  const resources: MockResource[] = [];
  const terrainProvider = {};
  let viewerOptions: Record<string, unknown> = {};
  const primitiveAdd = vi.fn((primitive: unknown) => primitive);
  const requestRender = vi.fn();
  const removePreRender = vi.fn();
  const removeTileFailed = vi.fn();
  const removeRenderError = vi.fn();
  const removeContextLost = vi.fn();
  const viewerDestroy = vi.fn();
  let renderErrorListener: ((scene: unknown, error: unknown) => void) | undefined;
  let contextLostListener: ((event: Event) => void) | undefined;
  const canvas = {
    addEventListener: vi.fn((_name: string, listener: (event: Event) => void) => {
      contextLostListener = listener;
    }),
    removeEventListener: vi.fn((_name: string, listener: (event: Event) => void) => {
      if (listener === contextLostListener) removeContextLost();
    })
  };
  const camera = { positionWC: 'camera-position', setView: vi.fn() };
  const viewer = {
    scene: {
      primitives: { add: primitiveAdd },
      preRender: { addEventListener: vi.fn(() => removePreRender) },
      renderError: {
        addEventListener: vi.fn((listener: (scene: unknown, error: unknown) => void) => {
          renderErrorListener = listener;
          return removeRenderError;
        })
      },
      canvas,
      globe: { show: true },
      requestRender
    },
    camera,
    destroy: viewerDestroy,
    isDestroyed: vi.fn(() => viewerDestroy.mock.calls.length > 0)
  };
  const Viewer = vi.fn(function ViewerMock(_container: Element, options: Record<string, unknown>) {
    viewerOptions = options;
    return viewer;
  });
  const Resource = vi.fn(function ResourceMock(options: { url: string; headers: Record<string, string> }) {
    const headers = Object.fromEntries(
      Object.entries(options.headers).map(([name, value]) => [name.toLowerCase(), value])
    );
    const resource: MockResource = {
      url: options.url,
      headers,
      getDerivedResource: ({ url }) => {
        const derivedUrl = new URL(url, new URL(resource.url, globalThis.location.origin)).href;
        return new Resource({ url: derivedUrl, headers: resource.headers }) as unknown as MockResource;
      },
      clone: () => new Resource({ url: resource.url, headers: resource.headers }) as unknown as MockResource
    };
    resources.push(resource);
    return resource;
  });
  const model = { destroy: vi.fn(), isDestroyed: vi.fn(() => false) };
  const modelOptions: Record<string, unknown> = {};
  const modelFromGltf = vi.fn(async (options: Record<string, unknown>) => {
    Object.assign(modelOptions, options);
    loadOrder.push('glb:pit-model');
    return model;
  });
  const tileset = {
    show: true,
    style: undefined as unknown,
    boundingSphere: { center: 'tiles-center' },
    tileFailed: { addEventListener: vi.fn(() => removeTileFailed) },
    destroy: vi.fn(),
    isDestroyed: vi.fn(() => false)
  };
  const tilesetOptions: Record<string, unknown> = {};
  const tilesetFromUrl = vi.fn(async (_resource: unknown, options: Record<string, unknown>) => {
    Object.assign(tilesetOptions, options);
    loadOrder.push('tiles:pit-tiles');
    return tileset;
  });
  const Primitive = vi.fn(function PrimitiveMock(options: Record<string, unknown>) {
    loadOrder.push('grid:coordinate-grid');
    return { kind: 'grid', options };
  });
  const distanceAttribute = vi.fn(function DistanceAttributeMock(near: number, far: number) {
    return { near, far };
  });
  const runtime = {
    Viewer,
    EllipsoidTerrainProvider: vi.fn(function EllipsoidTerrainProviderMock() {
      return terrainProvider;
    }),
    Resource,
    Model: { fromGltfAsync: modelFromGltf },
    Cesium3DTileset: { fromUrl: tilesetFromUrl },
    Cesium3DTileStyle: vi.fn(function StyleMock(style: unknown) {
      return { style };
    }),
    PlaneGeometry: vi.fn(function PlaneGeometryMock(options: unknown) {
      return { options };
    }),
    GeometryInstance: vi.fn(function GeometryInstanceMock(options: unknown) {
      return { options };
    }),
    Primitive,
    Material: { fromType: vi.fn((_type: string, options: unknown) => ({ options })) },
    MaterialAppearance: Object.assign(
      vi.fn(function MaterialAppearanceMock(options: unknown) {
        return { options };
      }),
      { MaterialSupport: { TEXTURED: { vertexFormat: 'textured' } } }
    ),
    DistanceDisplayCondition: vi.fn(function DistanceMock(near: number, far: number) {
      return { near, far };
    }),
    DistanceDisplayConditionGeometryInstanceAttribute: distanceAttribute,
    Cartesian2: vi.fn(function Cartesian2Mock(x: number, y: number) {
      return { x, y };
    }),
    Cartesian3: { distance: vi.fn(() => 100) },
    Color: { WHITE: { withAlpha: vi.fn((alpha: number) => ({ alpha })) } },
    Math: { toRadians: (degrees: number) => degrees }
  } as unknown as CesiumModule;

  return {
    runtime,
    viewer,
    Primitive,
    distanceAttribute,
    get viewerOptions() {
      return viewerOptions;
    },
    terrainProvider,
    loadOrder,
    resources,
    primitiveAdd,
    requestRender,
    removePreRender,
    removeTileFailed,
    removeRenderError,
    removeContextLost,
    raiseRenderError: (error: unknown) => renderErrorListener?.(viewer.scene, error),
    raiseContextLost: () => contextLostListener?.(new Event('webglcontextlost', { cancelable: true })),
    viewerDestroy,
    camera,
    model,
    modelOptions,
    modelFromGltf,
    tileset,
    tilesetOptions,
    tilesetFromUrl
  };
}

function sceneSnapshot(layers: SceneRuntimeLayer[]): SceneRuntimeSnapshot {
  return {
    sceneId: crypto.randomUUID(),
    sceneCode: 'north-pit',
    sceneName: 'North pit',
    versionId: crypto.randomUUID(),
    revisionNumber: 1,
    schemaVersion: 1,
    contentHash: `sha256:${'a'.repeat(64)}`,
    coordinateSystemId: crypto.randomUUID(),
    coordinateMode: 'LOCAL_ENU',
    originX: 0,
    originY: 0,
    originZ: 0,
    headingDegrees: 0,
    pitchDegrees: 0,
    rollDegrees: 0,
    scale: 1,
    defaultCamera: { position: [100, 200, 300] },
    fallbackViewport: { minX: 0, maxX: 1000, minY: 0, maxY: 800 },
    publishedAt: '2026-07-20T08:00:00Z',
    layerCount: layers.length,
    layers
  };
}

function gridLayer(overrides: Partial<SceneRuntimeLayer> = {}): SceneRuntimeLayer {
  return baseLayer({
    layerCode: 'coordinate-grid',
    layerType: 'GRID',
    asset: null,
    options: { width: 1200, height: 900, cellsX: 12, cellsY: 9 },
    ...overrides
  });
}

function staticGlbLayer(overrides: Partial<SceneRuntimeLayer> = {}): SceneRuntimeLayer {
  const siteId = crypto.randomUUID();
  const assetId = crypto.randomUUID();
  return baseLayer({
    layerCode: 'pit-model',
    layerType: 'STATIC_GLB',
    asset: {
      assetId,
      assetType: 'GLB',
      contentDigest: `sha256:${'b'.repeat(64)}`,
      fileCount: 1,
      entryUrl: `/api/v1/mine/sites/${siteId}/scene-assets/${assetId}/files/model.glb`
    },
    ...overrides
  });
}

function staticTilesLayer(overrides: Partial<SceneRuntimeLayer> = {}): SceneRuntimeLayer {
  const siteId = crypto.randomUUID();
  const assetId = crypto.randomUUID();
  return baseLayer({
    layerCode: 'pit-tiles',
    layerType: 'STATIC_3D_TILES',
    asset: {
      assetId,
      assetType: 'TILES_3D',
      contentDigest: `sha256:${'c'.repeat(64)}`,
      fileCount: 2,
      entryUrl: `/api/v1/mine/sites/${siteId}/scene-assets/${assetId}/files/tileset.json`
    },
    ...overrides
  });
}

function baseLayer(overrides: Partial<SceneRuntimeLayer>): SceneRuntimeLayer {
  return {
    id: crypto.randomUUID(),
    layerCode: 'layer',
    orderNumber: 1,
    layerType: 'GRID',
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
    maxItems: 0,
    options: {},
    asset: null,
    ...overrides
  };
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  const promise = new Promise<T>(resolvePromise => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}
