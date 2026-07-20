import type { SceneRuntimeLayer, SceneRuntimeSnapshot } from '../api/scene';
import type { CesiumModule } from './load-cesium';

export type SceneMatrix = InstanceType<CesiumModule['Matrix4']>;
export type SceneCartesian = InstanceType<CesiumModule['Cartesian3']>;

export interface SceneCameraView {
  position: [number, number, number];
  headingDegrees: number;
  pitchDegrees: number;
  rollDegrees: number;
  source: 'DEFAULT_CAMERA' | 'FALLBACK_VIEWPORT';
}

export interface SceneCameraOrientation {
  direction: SceneCartesian;
  up: SceneCartesian;
}

const MAX_COORDINATE = 1_000_000_000;
const MAX_CAMERA_COORDINATE = 10_000_000;
const MAX_CAMERA_DISTANCE = 2_000_000;
const DEFAULT_VIEWPORT = Object.freeze({
  minX: -500,
  maxX: 500,
  minY: -500,
  maxY: 500,
  minZ: 0,
  maxZ: 100
});

/** 创建把矿区统一坐标投影到 Cesium 固定世界坐标的场景根矩阵。 */
export function createSceneRootTransform(runtime: CesiumModule, snapshot: SceneRuntimeSnapshot): SceneMatrix {
  validateSnapshotTransform(snapshot);
  const anchor =
    snapshot.coordinateMode === 'WGS84_ANCHOR'
      ? runtime.Cartesian3.fromDegrees(snapshot.originX, snapshot.originY, snapshot.originZ)
      : runtime.Cartesian3.fromDegrees(0, 0, 0);
  const localFrame = runtime.Transforms.eastNorthUpToFixedFrame(anchor);
  const rotation = runtime.Matrix4.fromRotationTranslation(
    runtime.Matrix3.fromHeadingPitchRoll(
      runtime.HeadingPitchRoll.fromDegrees(snapshot.headingDegrees, snapshot.pitchDegrees, snapshot.rollDegrees)
    )
  );
  const scale = runtime.Matrix4.fromUniformScale(snapshot.scale);
  const rotatedAndScaled = multiply(runtime, rotation, scale);

  if (snapshot.coordinateMode === 'WGS84_ANCHOR') {
    return multiply(runtime, localFrame, rotatedAndScaled);
  }

  const subtractMineOrigin = runtime.Matrix4.fromTranslation(
    new runtime.Cartesian3(-snapshot.originX, -snapshot.originY, -snapshot.originZ)
  );
  return multiply(runtime, multiply(runtime, localFrame, rotatedAndScaled), subtractMineOrigin);
}

/** 在场景根矩阵之上组合单个发布图层的局部变换。 */
export function createLayerTransform(
  runtime: CesiumModule,
  sceneRoot: SceneMatrix,
  layer: SceneRuntimeLayer
): SceneMatrix {
  const translation = runtime.Matrix4.fromTranslation(
    new runtime.Cartesian3(layer.offsetX, layer.offsetY, layer.offsetZ)
  );
  const rotation = runtime.Matrix4.fromRotationTranslation(
    runtime.Matrix3.fromHeadingPitchRoll(
      runtime.HeadingPitchRoll.fromDegrees(layer.headingDegrees, layer.pitchDegrees, layer.rollDegrees)
    )
  );
  const scale = runtime.Matrix4.fromUniformScale(layer.scale);
  return multiply(runtime, multiply(runtime, multiply(runtime, sceneRoot, translation), rotation), scale);
}

/** 为单位平面追加有界网格宽高，不改变已发布图层变换。 */
export function createGridTransform(
  runtime: CesiumModule,
  layerTransform: SceneMatrix,
  width: number,
  height: number
): SceneMatrix {
  return multiply(runtime, layerTransform, runtime.Matrix4.fromScale(new runtime.Cartesian3(width, height, 1)));
}

/** 将矿区统一坐标点应用到已组合的场景矩阵。 */
export function transformScenePosition(
  runtime: CesiumModule,
  transform: SceneMatrix,
  position: readonly [number, number, number]
): SceneCartesian {
  return runtime.Matrix4.multiplyByPoint(
    transform,
    new runtime.Cartesian3(position[0], position[1], position[2]),
    new runtime.Cartesian3()
  );
}

/** 把场景局部相机姿态与场景根旋转组合为 Cesium 世界坐标方向。 */
export function transformCameraOrientation(
  runtime: CesiumModule,
  transform: SceneMatrix,
  camera: SceneCameraView
): SceneCameraOrientation {
  const localHpr = new runtime.HeadingPitchRoll(
    runtime.Math.toRadians(camera.headingDegrees) - runtime.Math.PI_OVER_TWO,
    runtime.Math.toRadians(camera.pitchDegrees),
    runtime.Math.toRadians(camera.rollDegrees)
  );
  const localRotation = runtime.Matrix3.fromQuaternion(runtime.Quaternion.fromHeadingPitchRoll(localHpr));
  const localDirection = runtime.Matrix3.getColumn(localRotation, 0, new runtime.Cartesian3());
  const localUp = runtime.Matrix3.getColumn(localRotation, 2, new runtime.Cartesian3());
  const direction = runtime.Matrix4.multiplyByPointAsVector(transform, localDirection, new runtime.Cartesian3());
  const up = runtime.Matrix4.multiplyByPointAsVector(transform, localUp, new runtime.Cartesian3());
  return {
    direction: runtime.Cartesian3.normalize(direction, direction),
    up: runtime.Cartesian3.normalize(up, up)
  };
}

/** 只接受白名单字段和有限数值；无效相机配置自动回退到有界视口。 */
export function resolveCameraView(snapshot: SceneRuntimeSnapshot): SceneCameraView {
  const configuredPosition = tuple(snapshot.defaultCamera.position, MAX_CAMERA_COORDINATE);
  if (configuredPosition) {
    return {
      position: configuredPosition,
      headingDegrees: boundedNumber(snapshot.defaultCamera.headingDegrees, -360, 360, 0),
      pitchDegrees: boundedNumber(snapshot.defaultCamera.pitchDegrees, -90, 90, -45),
      rollDegrees: boundedNumber(snapshot.defaultCamera.rollDegrees, -360, 360, 0),
      source: 'DEFAULT_CAMERA'
    };
  }

  const viewport = resolveViewport(snapshot.fallbackViewport);
  const span = Math.max(viewport.maxX - viewport.minX, viewport.maxY - viewport.minY, 10);
  const distance = clamp(span * 1.5, 100, MAX_CAMERA_DISTANCE);
  return {
    position: [
      clamp((viewport.minX + viewport.maxX) / 2, -MAX_CAMERA_COORDINATE, MAX_CAMERA_COORDINATE),
      clamp((viewport.minY + viewport.maxY) / 2, -MAX_CAMERA_COORDINATE, MAX_CAMERA_COORDINATE),
      clamp(viewport.maxZ + distance, -MAX_CAMERA_COORDINATE, MAX_CAMERA_COORDINATE)
    ],
    headingDegrees: 0,
    pitchDegrees: -90,
    rollDegrees: 0,
    source: 'FALLBACK_VIEWPORT'
  };
}

function resolveViewport(value: Record<string, unknown>) {
  const minX = optionalFinite(value.minX, -MAX_CAMERA_COORDINATE, MAX_CAMERA_COORDINATE);
  const maxX = optionalFinite(value.maxX, -MAX_CAMERA_COORDINATE, MAX_CAMERA_COORDINATE);
  const minY = optionalFinite(value.minY, -MAX_CAMERA_COORDINATE, MAX_CAMERA_COORDINATE);
  const maxY = optionalFinite(value.maxY, -MAX_CAMERA_COORDINATE, MAX_CAMERA_COORDINATE);
  const minZ = optionalFinite(value.minZ, -MAX_CAMERA_COORDINATE, MAX_CAMERA_COORDINATE) ?? DEFAULT_VIEWPORT.minZ;
  const maxZ = optionalFinite(value.maxZ, -MAX_CAMERA_COORDINATE, MAX_CAMERA_COORDINATE) ?? DEFAULT_VIEWPORT.maxZ;
  if (minX === undefined || maxX === undefined || minY === undefined || maxY === undefined) {
    return DEFAULT_VIEWPORT;
  }
  if (minX >= maxX || minY >= maxY || minZ > maxZ) return DEFAULT_VIEWPORT;
  return { minX, maxX, minY, maxY, minZ, maxZ };
}

function validateSnapshotTransform(snapshot: SceneRuntimeSnapshot) {
  finiteBounded(snapshot.originX, MAX_COORDINATE, 'originX');
  finiteBounded(snapshot.originY, MAX_COORDINATE, 'originY');
  finiteBounded(snapshot.originZ, MAX_COORDINATE, 'originZ');
  finiteBounded(snapshot.headingDegrees, 360, 'headingDegrees');
  finiteBounded(snapshot.pitchDegrees, 360, 'pitchDegrees');
  finiteBounded(snapshot.rollDegrees, 360, 'rollDegrees');
  if (!Number.isFinite(snapshot.scale) || snapshot.scale <= 0 || snapshot.scale > 1_000_000) {
    throw new Error('Scene scale is outside the supported range');
  }
  if (
    snapshot.coordinateMode === 'WGS84_ANCHOR' &&
    (snapshot.originX < -180 || snapshot.originX > 180 || snapshot.originY < -90 || snapshot.originY > 90)
  ) {
    throw new Error('WGS84 scene anchor is outside longitude or latitude bounds');
  }
}

function tuple(value: unknown, bound: number): [number, number, number] | null {
  if (!Array.isArray(value) || value.length !== 3) return null;
  const numbers = value.map(item => optionalFinite(item, -bound, bound));
  if (numbers.some(item => item === undefined)) return null;
  return numbers as [number, number, number];
}

function boundedNumber(value: unknown, minimum: number, maximum: number, fallback: number): number {
  return optionalFinite(value, minimum, maximum) ?? fallback;
}

function optionalFinite(value: unknown, minimum: number, maximum: number): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) && value >= minimum && value <= maximum
    ? value
    : undefined;
}

function finiteBounded(value: number, bound: number, field: string) {
  if (!Number.isFinite(value) || Math.abs(value) > bound) {
    throw new Error(`Scene transform field ${field} is outside the supported range`);
  }
}

function multiply(runtime: CesiumModule, left: SceneMatrix, right: SceneMatrix): SceneMatrix {
  return runtime.Matrix4.multiply(left, right, new runtime.Matrix4());
}

function clamp(value: number, minimum: number, maximum: number) {
  return Math.min(maximum, Math.max(minimum, value));
}
