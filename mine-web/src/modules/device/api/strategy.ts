import { request } from '@/api/client';
import type { components } from '@/generated/mine-api';

export type DeviceGroup = components['schemas']['DeviceGroup'];
export type DeviceGroupPage = components['schemas']['DeviceGroupPage'];
export type DeviceGroupCreateRequest = components['schemas']['DeviceGroupCreateRequest'];
export type DeviceGroupUpdateRequest = components['schemas']['DeviceGroupUpdateRequest'];
export type DeviceGroupMember = components['schemas']['DeviceGroupMember'];
export type DeviceGroupMembersRequest = components['schemas']['DeviceGroupMembersRequest'];
export type DeviceGroupMembershipChange = components['schemas']['DeviceGroupMembershipChange'];
export type StrategyType = components['schemas']['StrategyType'];
export type StrategyScopeType = components['schemas']['StrategyScopeType'];
export type StrategyVersionStatus = components['schemas']['StrategyVersionStatus'];
export type StrategyVersion = components['schemas']['StrategyVersion'];
export type StrategyDraftCreateRequest = components['schemas']['StrategyDraftCreateRequest'];
export type StrategyDraftUpdateRequest = components['schemas']['StrategyDraftUpdateRequest'];
export type StrategyPublishRequest = components['schemas']['StrategyPublishRequest'];
export type StrategyRollbackRequest = components['schemas']['StrategyRollbackRequest'];
export type StrategyRelease = components['schemas']['StrategyRelease'];
export type EffectiveStrategy = components['schemas']['EffectiveStrategy'];
export type AlarmNumericRule = components['schemas']['AlarmNumericRule'];
export type AlarmQualityRule = components['schemas']['AlarmQualityRule'];
export type AlarmRule = AlarmNumericRule | AlarmQualityRule;
export type AlarmStrategyValues = components['schemas']['AlarmStrategyValues'];
export type RetentionClass = components['schemas']['RetentionClass'];
export type RetentionPolicy = components['schemas']['RetentionPolicy'];
export type RetentionPreviewRequest = components['schemas']['RetentionPreviewRequest'];
export type RetentionPreview = components['schemas']['RetentionPreview'];
export type RetentionGroupChangePreviewRequest = components['schemas']['RetentionGroupChangePreviewRequest'];
export type RetentionGroupChangePreview = components['schemas']['RetentionGroupChangePreview'];
export type RetentionExecution = components['schemas']['RetentionExecution'];

export interface DeviceGroupQuery {
  productId: string;
  keyword?: string;
  status?: components['schemas']['DeviceGroupStatus'];
  page?: number;
  size?: number;
}

export interface StrategyVersionQuery {
  productId: string;
  strategyType: StrategyType;
  status?: StrategyVersionStatus;
  limit?: number;
}

export interface StrategyReleaseQuery {
  productId: string;
  strategyType: StrategyType;
  limit?: number;
}

/** 分页查询产品内设备组。 */
export function listDeviceGroups(query: DeviceGroupQuery) {
  return request<DeviceGroupPage>(`/device-groups?${queryString(query)}`);
}

/** 创建设备组。 */
export function createDeviceGroup(body: DeviceGroupCreateRequest) {
  return request<DeviceGroup>('/device-groups', { method: 'POST', body: JSON.stringify(body) });
}

/** 使用乐观锁修改设备组。 */
export function updateDeviceGroup(groupId: string, body: DeviceGroupUpdateRequest) {
  return request<DeviceGroup>(`/device-groups/${encodeURIComponent(groupId)}`, {
    method: 'PUT',
    body: JSON.stringify(body)
  });
}

/** 查询设备组当前成员。 */
export function listDeviceGroupMembers(groupId: string) {
  return request<DeviceGroupMember[]>(`/device-groups/${encodeURIComponent(groupId)}/members`);
}

/** 差异化替换设备组当前成员。 */
export function replaceDeviceGroupMembers(groupId: string, body: DeviceGroupMembersRequest) {
  return request<DeviceGroupMembershipChange>(`/device-groups/${encodeURIComponent(groupId)}/members`, {
    method: 'PUT',
    body: JSON.stringify(body)
  });
}

/** 归档设备组并结束成员关系。 */
export function archiveDeviceGroup(groupId: string, version: number, retentionPreviewToken?: string) {
  return request<void>(`/device-groups/${encodeURIComponent(groupId)}/archive`, {
    method: 'POST',
    body: JSON.stringify({ version, ...(retentionPreviewToken ? { retentionPreviewToken } : {}) })
  });
}

/** 查询产品的策略版本。 */
export function listStrategyVersions(query: StrategyVersionQuery) {
  return request<StrategyVersion[]>(`/strategy-versions?${queryString(query)}`);
}

/** 创建指定类型的策略草稿。 */
export function createStrategyDraft(body: StrategyDraftCreateRequest) {
  return request<StrategyVersion>('/strategy-versions', { method: 'POST', body: JSON.stringify(body) });
}

/** 使用乐观锁修改策略草稿。 */
export function updateStrategyDraft(strategyVersionId: string, body: StrategyDraftUpdateRequest) {
  return request<StrategyVersion>(`/strategy-versions/${encodeURIComponent(strategyVersionId)}`, {
    method: 'PUT',
    body: JSON.stringify(body)
  });
}

/** 查询不可变策略发布历史。 */
export function listStrategyReleases(query: StrategyReleaseQuery) {
  return request<StrategyRelease[]>(`/strategy-releases?${queryString(query)}`);
}

/** 使用稳定幂等键发布策略。 */
export function publishStrategy(body: StrategyPublishRequest, idempotencyKey: string) {
  return request<StrategyRelease>('/strategy-releases', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body)
  });
}

/** 基于历史发布创建不可变回滚发布。 */
export function rollbackStrategyRelease(
  strategyReleaseId: string,
  body: StrategyRollbackRequest,
  idempotencyKey: string
) {
  return request<StrategyRelease>(`/strategy-releases/${encodeURIComponent(strategyReleaseId)}/rollback`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body)
  });
}

/** 查询设备指定类型的有效策略和逐字段来源。 */
export function getEffectiveStrategy(deviceId: string, strategyType: StrategyType) {
  return request<EffectiveStrategy>(
    `/devices/${encodeURIComponent(deviceId)}/effective-strategies/${encodeURIComponent(strategyType)}`
  );
}

/** 基于数据库一致性统计时点预览保留策略的影响范围。 */
export function previewRetentionImpact(body: RetentionPreviewRequest) {
  return request<RetentionPreview>('/retention-previews', {
    method: 'POST',
    body: JSON.stringify(body)
  });
}

/** 预览设备组变化间接激活当前保留策略后的逐设备删除影响。 */
export function previewRetentionGroupChange(body: RetentionGroupChangePreviewRequest) {
  return request<RetentionGroupChangePreview>('/retention-group-change-previews', {
    method: 'POST',
    body: JSON.stringify(body)
  });
}

/** 查询遥测运行面最近的数据保留执行事实。 */
export function listRetentionExecutions(limit = 20) {
  return request<RetentionExecution[]>(`/retention-executions?${queryString({ limit })}`);
}

function queryString(query: object) {
  const parameters = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') parameters.set(key, String(value));
  });
  return parameters.toString();
}
