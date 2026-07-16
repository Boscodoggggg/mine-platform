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

function json(method: string, body: unknown): RequestInit {
  return { method, body: JSON.stringify(body) };
}

function toModelFieldInput(field: ModelField): components['schemas']['DeviceModelFieldInput'] {
  const { id: _id, modelVersionId: _modelVersionId, version: _version, ...input } = field;
  return input;
}

function toProtocolFieldInput(field: ProtocolField): components['schemas']['ProtocolMappingFieldInput'] {
  const { id: _id, mappingVersionId: _mappingVersionId, version: _version, ...input } = field;
  return input;
}
