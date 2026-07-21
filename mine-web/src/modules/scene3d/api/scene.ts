import type { components } from '@/generated/mine-api';
import type { UploadOptions } from '@/api/client';
import { request, upload } from '@/api/client';

export type SceneAsset = components['schemas']['SceneAssetResponse'];
export type SceneAssetPage = components['schemas']['SceneAssetPage'];
export type Scene = components['schemas']['SceneResponse'];
export type ScenePage = components['schemas']['ScenePage'];
export type SceneRequest = components['schemas']['SceneRequest'];
export type SceneVersion = components['schemas']['SceneVersionResponse'];
export type SceneVersionPage = components['schemas']['SceneVersionPage'];
export type SceneVersionRequest = components['schemas']['SceneVersionRequest'];
export type SceneLayerRequest = components['schemas']['SceneLayerRequest'];
export type SceneRuntime = components['schemas']['SceneRuntimeResponse'];
export type SceneRuntimeSnapshot = components['schemas']['SceneRuntimeSnapshot'];
export type SceneRuntimeLayer = components['schemas']['SceneRuntimeLayer'];
export type SceneRuntimeAsset = components['schemas']['SceneRuntimeAsset'];
export type SceneRuntimeReason = SceneRuntime['reason'];

export interface SceneAssetQuery {
  status?: SceneAsset['status'] | null;
  page?: number;
  size?: number;
}

export interface SceneAssetDetailQuery {
  filePage?: number;
  fileSize?: number;
}

export interface SceneQuery {
  keyword?: string;
  status?: Scene['status'] | null;
  page?: number;
  size?: number;
}

export interface SceneVersionQuery {
  page?: number;
  size?: number;
}

/** 场景运行响应不满足受信契约。 */
export class SceneRuntimeContractError extends Error {
  readonly code = 'SCENE_RUNTIME_CONTRACT_INVALID';
  readonly field: string;

  constructor(field: string) {
    super(`Scene runtime contract is invalid at ${field}`);
    this.name = 'SceneRuntimeContractError';
    this.field = field;
  }
}

/** 分页读取当前矿区三维资源。 */
export function listSceneAssets(siteId: string, query: SceneAssetQuery = {}, signal?: AbortSignal) {
  return requestWithSignal<SceneAssetPage>(withQuery(sitePath(siteId, 'scene-assets'), query), signal);
}

/** 上传由服务端同步校验的 GLB 或 3D Tiles ZIP。 */
export function uploadSceneAsset(siteId: string, file: File, options: UploadOptions = {}) {
  const formData = new FormData();
  formData.set('file', file);
  return upload<SceneAsset>(sitePath(siteId, 'scene-assets'), formData, options);
}

/** 读取资源和一页有界文件清单。 */
export function getSceneAsset(siteId: string, assetId: string, query: SceneAssetDetailQuery = {}) {
  return request<SceneAsset>(withQuery(`${sitePath(siteId, 'scene-assets')}/${segment(assetId)}`, query));
}

/** 使用调用方稳定幂等键归档资源。 */
export function archiveSceneAsset(siteId: string, assetId: string, version: number, idempotencyKey: string) {
  return request<SceneAsset>(
    `${sitePath(siteId, 'scene-assets')}/${segment(assetId)}/archive`,
    mutation({ version }, idempotencyKey)
  );
}

/** 分页读取稳定场景。 */
export function listScenes(siteId: string, query: SceneQuery = {}, signal?: AbortSignal) {
  return requestWithSignal<ScenePage>(withQuery(sitePath(siteId, 'scenes'), query), signal);
}

/** 创建稳定场景。 */
export function createScene(siteId: string, body: SceneRequest) {
  return request<Scene>(sitePath(siteId, 'scenes'), json('POST', body));
}

/** 读取稳定场景详情。 */
export function getScene(siteId: string, sceneId: string) {
  return request<Scene>(scenePath(siteId, sceneId));
}

/** 使用乐观版本更新稳定场景。 */
export function updateScene(siteId: string, sceneId: string, body: SceneRequest) {
  return request<Scene>(scenePath(siteId, sceneId), json('PATCH', body));
}

/** 使用调用方稳定幂等键归档非默认场景。 */
export function archiveScene(siteId: string, sceneId: string, version: number, idempotencyKey: string) {
  return request<Scene>(`${scenePath(siteId, sceneId)}/archive`, mutation({ version }, idempotencyKey));
}

/** 使用调用方稳定幂等键切换默认场景。 */
export function makeDefaultScene(siteId: string, sceneId: string, version: number, idempotencyKey: string) {
  return request<Scene>(`${scenePath(siteId, sceneId)}/make-default`, mutation({ version }, idempotencyKey));
}

/** 分页读取场景版本和有序图层。 */
export function listSceneVersions(
  siteId: string,
  sceneId: string,
  query: SceneVersionQuery = {},
  signal?: AbortSignal
) {
  return requestWithSignal<SceneVersionPage>(withQuery(versionPath(siteId, sceneId), query), signal);
}

/** 创建场景首个草稿。 */
export function createSceneDraft(siteId: string, sceneId: string, body: SceneVersionRequest) {
  return request<SceneVersion>(versionPath(siteId, sceneId), json('POST', body));
}

/** 从当前发布版本克隆下一草稿。 */
export function cloneNextSceneDraft(siteId: string, sceneId: string) {
  return request<SceneVersion>(`${versionPath(siteId, sceneId)}/next-draft`, { method: 'POST' });
}

/** 读取一个场景版本。 */
export function getSceneVersion(siteId: string, sceneId: string, versionId: string) {
  return request<SceneVersion>(`${versionPath(siteId, sceneId)}/${segment(versionId)}`);
}

/** 使用乐观版本修改草稿。 */
export function updateSceneDraft(siteId: string, sceneId: string, versionId: string, body: SceneVersionRequest) {
  return request<SceneVersion>(`${versionPath(siteId, sceneId)}/${segment(versionId)}`, json('PATCH', body));
}

/** 使用乐观版本原子替换全部图层。 */
export function replaceSceneLayers(
  siteId: string,
  sceneId: string,
  versionId: string,
  version: number,
  layers: SceneLayerRequest[]
) {
  return request<SceneVersion>(
    withQuery(`${versionPath(siteId, sceneId)}/${segment(versionId)}/layers`, { version }),
    json('PUT', layers)
  );
}

/** 使用调用方稳定幂等键发布并锁定场景版本。 */
export function publishSceneVersion(
  siteId: string,
  sceneId: string,
  versionId: string,
  version: number,
  idempotencyKey: string
) {
  return request<SceneVersion>(
    `${versionPath(siteId, sceneId)}/${segment(versionId)}/publish`,
    mutation({ version }, idempotencyKey)
  );
}

/** 读取并运行时校验默认场景最后正确快照。 */
export async function getSceneRuntime(siteId: string, signal?: AbortSignal): Promise<SceneRuntime> {
  const runtime = parseSceneRuntimeResponse(
    await requestWithSignal<unknown>(sitePath(siteId, 'scene-runtime'), signal)
  );
  if (runtime.siteId !== siteId) invalid('siteId');
  return runtime;
}

/** 将未知 JSON 收窄为完整场景运行响应，拒绝半包和外部资源入口。 */
export function parseSceneRuntimeResponse(value: unknown): SceneRuntime {
  const response = objectValue(value, '$');
  const siteId = uuidValue(response.siteId, 'siteId');
  const status = enumValue(response.status, ['READY', 'DEGRADED'] as const, 'status');
  const reason = enumValue(response.reason, RUNTIME_REASONS, 'reason');

  if (status === 'DEGRADED') {
    if (reason === 'NONE' || response.snapshot !== null) invalid('snapshot');
  } else {
    if (reason !== 'NONE' || response.snapshot === null) invalid('snapshot');
    validateSnapshot(response.snapshot, siteId);
  }
  return value as SceneRuntime;
}

const RUNTIME_REASONS = [
  'NONE',
  'NO_DEFAULT_SCENE',
  'DEFAULT_SCENE_UNPUBLISHED',
  'UNSUPPORTED_SCHEMA_VERSION',
  'INVALID_PUBLISHED_SNAPSHOT',
  'SCENE_STORAGE_UNAVAILABLE'
] as const;

const LAYER_TYPES = [
  'GRID',
  'STATIC_GLB',
  'STATIC_3D_TILES',
  'DYNAMIC_LOCATIONS',
  'DYNAMIC_REGIONS',
  'DYNAMIC_TRAJECTORY',
  'DYNAMIC_ALARMS',
  'DYNAMIC_OPERATIONS'
] as const;

function validateSnapshot(value: unknown, siteId: string) {
  const snapshot = objectValue(value, 'snapshot');
  uuidValue(snapshot.sceneId, 'snapshot.sceneId');
  nonBlankString(snapshot.sceneCode, 'snapshot.sceneCode');
  nonBlankString(snapshot.sceneName, 'snapshot.sceneName');
  uuidValue(snapshot.versionId, 'snapshot.versionId');
  integerValue(snapshot.revisionNumber, 'snapshot.revisionNumber', 1);
  if (integerValue(snapshot.schemaVersion, 'snapshot.schemaVersion', 1) !== 1) {
    invalid('snapshot.schemaVersion');
  }
  digestValue(snapshot.contentHash, 'snapshot.contentHash');
  uuidValue(snapshot.coordinateSystemId, 'snapshot.coordinateSystemId');
  enumValue(snapshot.coordinateMode, ['LOCAL_ENU', 'WGS84_ANCHOR'] as const, 'snapshot.coordinateMode');
  finiteValue(snapshot.originX, 'snapshot.originX');
  finiteValue(snapshot.originY, 'snapshot.originY');
  finiteValue(snapshot.originZ, 'snapshot.originZ');
  finiteValue(snapshot.headingDegrees, 'snapshot.headingDegrees');
  finiteValue(snapshot.pitchDegrees, 'snapshot.pitchDegrees');
  finiteValue(snapshot.rollDegrees, 'snapshot.rollDegrees');
  positiveValue(snapshot.scale, 'snapshot.scale');
  objectValue(snapshot.defaultCamera, 'snapshot.defaultCamera');
  objectValue(snapshot.fallbackViewport, 'snapshot.fallbackViewport');
  dateTimeValue(snapshot.publishedAt, 'snapshot.publishedAt');
  const layerCount = integerValue(snapshot.layerCount, 'snapshot.layerCount', 1, 256);
  const layers = arrayValue(snapshot.layers, 'snapshot.layers');
  if (layers.length !== layerCount) invalid('snapshot.layers');

  let previousOrder = 0;
  layers.forEach((layer, index) => {
    const order = validateLayer(layer, index, siteId);
    if (order <= previousOrder) invalid(`snapshot.layers[${index}].orderNumber`);
    previousOrder = order;
  });
}

function validateLayer(value: unknown, index: number, siteId: string): number {
  const path = `snapshot.layers[${index}]`;
  const layer = objectValue(value, path);
  uuidValue(layer.id, `${path}.id`);
  nonBlankString(layer.layerCode, `${path}.layerCode`);
  const orderNumber = integerValue(layer.orderNumber, `${path}.orderNumber`, 1, 10000);
  const layerType = enumValue(layer.layerType, LAYER_TYPES, `${path}.layerType`);
  booleanValue(layer.visible, `${path}.visible`);
  rangeValue(layer.opacity, `${path}.opacity`, 0, 1);
  const minimumDistance = rangeValue(layer.minimumDistance, `${path}.minimumDistance`, 0);
  const maximumDistance = rangeValue(layer.maximumDistance, `${path}.maximumDistance`, 0);
  if (maximumDistance < minimumDistance) invalid(`${path}.maximumDistance`);
  finiteValue(layer.offsetX, `${path}.offsetX`);
  finiteValue(layer.offsetY, `${path}.offsetY`);
  finiteValue(layer.offsetZ, `${path}.offsetZ`);
  finiteValue(layer.headingDegrees, `${path}.headingDegrees`);
  finiteValue(layer.pitchDegrees, `${path}.pitchDegrees`);
  finiteValue(layer.rollDegrees, `${path}.rollDegrees`);
  positiveValue(layer.scale, `${path}.scale`);
  integerValue(layer.maxItems, `${path}.maxItems`, 0, 100000);
  objectValue(layer.options, `${path}.options`);

  const staticLayer = layerType === 'STATIC_GLB' || layerType === 'STATIC_3D_TILES';
  if (staticLayer) {
    validateRuntimeAsset(layer.asset, path, layerType, siteId);
  } else if (layer.asset !== null) {
    invalid(`${path}.asset`);
  }
  return orderNumber;
}

function validateRuntimeAsset(
  value: unknown,
  layerPath: string,
  layerType: 'STATIC_GLB' | 'STATIC_3D_TILES',
  siteId: string
) {
  const path = `${layerPath}.asset`;
  const asset = objectValue(value, path);
  const assetId = uuidValue(asset.assetId, `${path}.assetId`);
  const assetType = enumValue(asset.assetType, ['GLB', 'TILES_3D'] as const, `${path}.assetType`);
  if (
    (layerType === 'STATIC_GLB' && assetType !== 'GLB') ||
    (layerType === 'STATIC_3D_TILES' && assetType !== 'TILES_3D')
  ) {
    invalid(`${path}.assetType`);
  }
  digestValue(asset.contentDigest, `${path}.contentDigest`);
  integerValue(asset.fileCount, `${path}.fileCount`, 1, 10000);
  const entryUrl = nonBlankString(asset.entryUrl, `${path}.entryUrl`);
  validateAssetEntryUrl(entryUrl, siteId, assetId, `${path}.entryUrl`);
}

function validateAssetEntryUrl(entryUrl: string, siteId: string, assetId: string, field: string) {
  if (!entryUrl.startsWith('/') || entryUrl.startsWith('//')) invalid(field);

  let url: URL;
  try {
    url = new URL(entryUrl, 'https://scene-runtime.invalid');
  } catch {
    invalid(field);
  }
  if (url.origin !== 'https://scene-runtime.invalid' || url.search || url.hash) invalid(field);

  const segments = entryUrl.split('/');
  if (segments.length < 10 || segments[0] !== '') invalid(field);
  const decoded = segments.slice(1).map(segmentValue => decodeRuntimePathSegment(segmentValue, field));
  if (
    decoded[0] !== 'api' ||
    decoded[1] !== 'v1' ||
    decoded[2] !== 'mine' ||
    decoded[3] !== 'sites' ||
    decoded[4] !== siteId ||
    decoded[5] !== 'scene-assets' ||
    decoded[6] !== assetId ||
    decoded[7] !== 'files' ||
    decoded.length < 9
  ) {
    invalid(field);
  }
}

function decodeRuntimePathSegment(value: string, field: string): string {
  if (!value) invalid(field);
  let decoded = value;
  try {
    for (let index = 0; index < 3; index += 1) {
      const next = decodeURIComponent(decoded);
      if (next === decoded) break;
      decoded = next;
    }
  } catch {
    invalid(field);
  }
  if (
    !decoded ||
    decoded === '.' ||
    decoded === '..' ||
    decoded.includes('/') ||
    decoded.includes('\\') ||
    decoded.includes('\0')
  ) {
    invalid(field);
  }
  return decoded;
}

function json(method: 'POST' | 'PATCH' | 'PUT', body: unknown): RequestInit {
  return { method, body: JSON.stringify(body) };
}

function mutation(body: object, idempotencyKey: string): RequestInit {
  return {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body)
  };
}

function sitePath(siteId: string, resource: string) {
  return `/mine/sites/${segment(siteId)}/${resource}`;
}

function scenePath(siteId: string, sceneId: string) {
  return `${sitePath(siteId, 'scenes')}/${segment(sceneId)}`;
}

function versionPath(siteId: string, sceneId: string) {
  return `${scenePath(siteId, sceneId)}/versions`;
}

function segment(value: string) {
  return encodeURIComponent(value);
}

function withQuery(path: string, query: object) {
  const parameters = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') parameters.set(key, String(value));
  });
  const queryString = parameters.toString();
  return queryString ? `${path}?${queryString}` : path;
}

function requestWithSignal<T>(path: string, signal?: AbortSignal) {
  return signal ? request<T>(path, { signal }) : request<T>(path);
}

function objectValue(value: unknown, field: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid(field);
  return value as Record<string, unknown>;
}

function arrayValue(value: unknown, field: string): unknown[] {
  if (!Array.isArray(value)) invalid(field);
  return value;
}

function nonBlankString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.trim() === '') invalid(field);
  return value;
}

function uuidValue(value: unknown, field: string): string {
  const text = nonBlankString(value, field);
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(text)) {
    invalid(field);
  }
  return text;
}

function digestValue(value: unknown, field: string): string {
  const text = nonBlankString(value, field);
  if (!/^sha256:[0-9a-f]{64}$/.test(text)) invalid(field);
  return text;
}

function dateTimeValue(value: unknown, field: string): string {
  const text = nonBlankString(value, field);
  if (Number.isNaN(Date.parse(text))) invalid(field);
  return text;
}

function finiteValue(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) invalid(field);
  return value;
}

function positiveValue(value: unknown, field: string): number {
  const number = finiteValue(value, field);
  if (number <= 0) invalid(field);
  return number;
}

function rangeValue(value: unknown, field: string, minimum: number, maximum = Number.POSITIVE_INFINITY) {
  const number = finiteValue(value, field);
  if (number < minimum || number > maximum) invalid(field);
  return number;
}

function integerValue(value: unknown, field: string, minimum: number, maximum = Number.MAX_SAFE_INTEGER) {
  const number = finiteValue(value, field);
  if (!Number.isInteger(number) || number < minimum || number > maximum) invalid(field);
  return number;
}

function booleanValue(value: unknown, field: string): boolean {
  if (typeof value !== 'boolean') invalid(field);
  return value;
}

function enumValue<const T extends readonly string[]>(value: unknown, values: T, field: string): T[number] {
  if (typeof value !== 'string' || !values.includes(value)) invalid(field);
  return value as T[number];
}

function invalid(field: string): never {
  throw new SceneRuntimeContractError(field);
}
