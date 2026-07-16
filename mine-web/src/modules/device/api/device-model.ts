import { request } from '@/api/client';
import type { components } from '@/generated/mine-api';

export type ConfigStatus = components['schemas']['ConfigurationVersionStatus'];
export type ModelDataType = components['schemas']['DeviceModelFieldInput']['dataType'];
export type ProtocolType = components['schemas']['ProtocolMappingVersion']['protocolType'];
export type ModelVersion = components['schemas']['DeviceModelVersion'];
export type ModelField = components['schemas']['DeviceModelFieldInput'] &
  Partial<Pick<components['schemas']['DeviceModelField'], 'id' | 'modelVersionId' | 'version'>>;
export type ProtocolVersion = components['schemas']['ProtocolMappingVersion'];
export type ProtocolField = components['schemas']['ProtocolMappingFieldInput'] &
  Partial<Pick<components['schemas']['ProtocolMappingField'], 'id' | 'mappingVersionId' | 'version'>>;
export type ProductConfiguration = components['schemas']['ProductDeviceConfiguration'];
export type ProtocolSample = components['schemas']['ProtocolSample'];
export type ProtocolSampleInput = components['schemas']['ProtocolSampleInput'];
export type ProtocolTestRun = components['schemas']['ProtocolTestRun'];
export type ConfigRelease = components['schemas']['ConfigRelease'];
export type ConfigReleaseSummary = components['schemas']['ConfigReleaseSummary'];
export type ConfigReleaseDetail = components['schemas']['ConfigReleaseDetail'];

/** 查询产品的全部物模型与协议映射版本。 */
export function getProductConfiguration(productId: string) {
  return request<ProductConfiguration>(`/device-models/products/${productId}/configuration`);
}

/** 新建空物模型草稿。 */
export function createModelDraft(productId: string, description?: string) {
  return request<ModelVersion>(`/device-models/products/${productId}/model-drafts`, json('POST', { description }));
}

/** 原子保存物模型草稿字段。 */
export function saveModelFields(versionId: string, fields: ModelField[]) {
  const payload: components['schemas']['DeviceModelFieldsRequest'] = {
    fields: fields.map(toModelFieldInput)
  };
  return request<components['schemas']['DeviceModelField'][]>(
    `/device-models/model-versions/${versionId}/fields`,
    json('PUT', payload)
  );
}

/** 发布物模型草稿。 */
export function publishModel(versionId: string, version: number) {
  return request<void>(`/device-models/model-versions/${versionId}/publish`, json('POST', { version }));
}

/** 新建协议字段映射草稿。 */
export function createProtocolDraft(productId: string, payload: components['schemas']['ProtocolMappingDraftRequest']) {
  return request<ProtocolVersion>(`/device-models/products/${productId}/protocol-drafts`, json('POST', payload));
}

/** 原子保存协议字段映射草稿。 */
export function saveProtocolFields(versionId: string, fields: ProtocolField[]) {
  const payload: components['schemas']['ProtocolMappingFieldsRequest'] = {
    fields: fields.map(toProtocolFieldInput)
  };
  return request<components['schemas']['ProtocolMappingField'][]>(
    `/device-models/protocol-versions/${versionId}/fields`,
    json('PUT', payload)
  );
}

/** 发布协议字段映射草稿。 */
export function publishProtocol(versionId: string, version: number) {
  return request<void>(`/device-models/protocol-versions/${versionId}/publish`, json('POST', { version }));
}

/** 查询协议版本的全部黄金样本。 */
export function getProtocolSamples(versionId: string) {
  return request<ProtocolSample[]>(`/protocol-mapping-versions/${versionId}/samples`);
}

/** 原子保存协议草稿的全部黄金样本。 */
export function saveProtocolSamples(versionId: string, version: number, samples: ProtocolSampleInput[]) {
  return request<ProtocolSample[]>(
    `/protocol-mapping-versions/${versionId}/samples`,
    json('PUT', { version, samples })
  );
}

/** 使用 Gateway 同源引擎回放全部黄金样本。 */
export function testProtocolSamples(versionId: string) {
  return request<ProtocolTestRun>(`/protocol-mapping-versions/${versionId}/test`, { method: 'POST' });
}

/** 查询产品最近的配置发布历史。 */
export function getConfigReleases(productId: string, limit = 50) {
  const query = new URLSearchParams({ productId, limit: String(limit) });
  return request<ConfigReleaseSummary[]>(`/config-releases?${query}`);
}

/** 查询配置发布和运行节点加载状态。 */
export function getConfigRelease(releaseId: string) {
  return request<ConfigReleaseDetail>(`/config-releases/${releaseId}`);
}

/** 通过黄金样本门禁后创建配置发布。 */
export function publishConfigRelease(
  payload: components['schemas']['ConfigPublishRequest'],
  idempotencyKey: string
) {
  return request<ConfigRelease>(
    '/config-releases',
    json('POST', payload, { 'Idempotency-Key': idempotencyKey })
  );
}

/** 复用历史配置包创建更高序号的回滚发布。 */
export function rollbackConfigRelease(releaseId: string, reason: string, idempotencyKey: string) {
  return request<ConfigRelease>(
    `/config-releases/${releaseId}/rollback`,
    json('POST', { reason }, { 'Idempotency-Key': idempotencyKey })
  );
}

function json(method: string, body: unknown, headers?: HeadersInit): RequestInit {
  return { method, body: JSON.stringify(body), headers };
}

function toModelFieldInput(field: ModelField): components['schemas']['DeviceModelFieldInput'] {
  const { id: _id, modelVersionId: _modelVersionId, version: _version, ...input } = field;
  return input;
}

function toProtocolFieldInput(field: ProtocolField): components['schemas']['ProtocolMappingFieldInput'] {
  const { id: _id, mappingVersionId: _mappingVersionId, version: _version, ...input } = field;
  return input;
}
