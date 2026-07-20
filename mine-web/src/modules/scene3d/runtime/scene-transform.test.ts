import * as Cesium from 'cesium';
import { describe, expect, it } from 'vitest';
import type { SceneRuntimeLayer, SceneRuntimeSnapshot } from '../api/scene';
import {
  createLayerTransform,
  createSceneRootTransform,
  resolveCameraView,
  transformCameraOrientation,
  transformScenePosition
} from './scene-transform';

describe('scene-transform', () => {
  it('LOCAL_ENU 将矿区统一坐标原点平移到稳定本地 ENU 锚点', () => {
    const snapshot = sceneSnapshot({
      coordinateMode: 'LOCAL_ENU',
      originX: 100,
      originY: 200,
      originZ: 30,
      scale: 2
    });

    const root = createSceneRootTransform(Cesium, snapshot);
    const worldOrigin = transformScenePosition(Cesium, root, [100, 200, 30]);
    const fixedAnchor = Cesium.Cartesian3.fromDegrees(0, 0, 0);

    expect(Cesium.Cartesian3.distance(worldOrigin, fixedAnchor)).toBeLessThan(0.001);
  });

  it('WGS84_ANCHOR 将局部零点绑定到发布快照经纬高', () => {
    const snapshot = sceneSnapshot({
      coordinateMode: 'WGS84_ANCHOR',
      originX: 116.4,
      originY: 39.9,
      originZ: 80,
      headingDegrees: 15
    });

    const root = createSceneRootTransform(Cesium, snapshot);
    const worldOrigin = transformScenePosition(Cesium, root, [0, 0, 0]);
    const expected = Cesium.Cartesian3.fromDegrees(116.4, 39.9, 80);

    expect(Cesium.Cartesian3.distance(worldOrigin, expected)).toBeLessThan(0.001);
  });

  it('图层变换按场景根、偏移、旋转和缩放顺序组合', () => {
    const snapshot = sceneSnapshot();
    const root = createSceneRootTransform(Cesium, snapshot);
    const layer = sceneLayer({ offsetX: 10, offsetY: 20, offsetZ: 30, scale: 3 });

    const layerTransform = createLayerTransform(Cesium, root, layer);
    const layerOrigin = transformScenePosition(Cesium, layerTransform, [0, 0, 0]);
    const expected = transformScenePosition(Cesium, root, [10, 20, 30]);

    expect(Cesium.Cartesian3.distance(layerOrigin, expected)).toBeLessThan(0.001);
  });

  it('优先读取有界默认相机，非法配置退回有界二维视口', () => {
    const configured = resolveCameraView(
      sceneSnapshot({
        defaultCamera: {
          position: [100, 200, 300],
          headingDegrees: 20,
          pitchDegrees: -30,
          rollDegrees: 5
        }
      })
    );
    expect(configured).toEqual({
      position: [100, 200, 300],
      headingDegrees: 20,
      pitchDegrees: -30,
      rollDegrees: 5,
      source: 'DEFAULT_CAMERA'
    });

    const fallback = resolveCameraView(
      sceneSnapshot({
        defaultCamera: { position: [Number.POSITIVE_INFINITY, 0, 0] },
        fallbackViewport: { minX: 0, maxX: 1000, minY: 100, maxY: 900, minZ: -20, maxZ: 80 }
      })
    );
    expect(fallback.source).toBe('FALLBACK_VIEWPORT');
    expect(fallback.position.every(Number.isFinite)).toBe(true);
    expect(Math.max(...fallback.position.map(Math.abs))).toBeLessThanOrEqual(10_000_000);
  });

  it('场景非零旋转会同步旋转相机方向并保持正交单位向量', () => {
    const camera = resolveCameraView(
      sceneSnapshot({
        defaultCamera: { position: [100, 200, 300], headingDegrees: 15, pitchDegrees: -35, rollDegrees: 5 }
      })
    );
    const plainRoot = createSceneRootTransform(Cesium, sceneSnapshot());
    const rotatedRoot = createSceneRootTransform(
      Cesium,
      sceneSnapshot({ headingDegrees: 90, pitchDegrees: 12, rollDegrees: -8 })
    );

    const plain = transformCameraOrientation(Cesium, plainRoot, camera);
    const rotated = transformCameraOrientation(Cesium, rotatedRoot, camera);

    expect(Cesium.Cartesian3.distance(plain.direction, rotated.direction)).toBeGreaterThan(0.1);
    expect(Cesium.Cartesian3.magnitude(rotated.direction)).toBeCloseTo(1, 10);
    expect(Cesium.Cartesian3.magnitude(rotated.up)).toBeCloseTo(1, 10);
    expect(Cesium.Cartesian3.dot(rotated.direction, rotated.up)).toBeCloseTo(0, 10);
  });
});

function sceneSnapshot(overrides: Partial<SceneRuntimeSnapshot> = {}): SceneRuntimeSnapshot {
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
    defaultCamera: {},
    fallbackViewport: {},
    publishedAt: '2026-07-20T08:00:00Z',
    layerCount: 0,
    layers: [],
    ...overrides
  };
}

function sceneLayer(overrides: Partial<SceneRuntimeLayer> = {}): SceneRuntimeLayer {
  return {
    id: crypto.randomUUID(),
    layerCode: 'pit-model',
    orderNumber: 1,
    layerType: 'STATIC_GLB',
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
    asset: {
      assetId: crypto.randomUUID(),
      assetType: 'GLB',
      contentDigest: `sha256:${'b'.repeat(64)}`,
      fileCount: 1,
      entryUrl: `/api/v1/mine/sites/${crypto.randomUUID()}/scene-assets/${crypto.randomUUID()}/files/model.glb`
    },
    ...overrides
  };
}
