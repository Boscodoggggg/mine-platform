import { request } from '@/api/client';
import type { components } from '@/generated/mine-api';

export type SystemOperationsOverview = components['schemas']['SystemOperationsOverview'];
export type SystemOperationItem = components['schemas']['SystemOperationItem'];
export type SystemOperationCategory = components['schemas']['SystemOperationCategory'];
export type OperationsObservabilityRange = components['schemas']['OperationsObservabilityRange'];
export type OperationsObservabilityOverview = components['schemas']['OperationsObservabilityOverview'];
export type OperationsObservabilityItem = components['schemas']['OperationsObservabilityItem'];
export type OperationsObservabilitySeries = components['schemas']['OperationsObservabilitySeries'];

/** 按需检查平台服务、消息和存储运行状态。 */
export function getSystemOperations() {
  return request<SystemOperationsOverview>('/system/operations');
}

/** 独立执行一个已注册检查项，供界面并发渐进展示结果。 */
export function checkSystemOperation(key: string) {
  return request<SystemOperationItem>(`/system/operations/items/${encodeURIComponent(key)}/check`);
}

/** 查询固定时间范围内的历史运行结论。 */
export function getSystemOperationsOverview(range: OperationsObservabilityRange) {
  return request<OperationsObservabilityOverview>(`/system/operations/overview?range=${encodeURIComponent(range)}`);
}

/** 按需查询单个已注册检查项的真实趋势。 */
export function getSystemOperationSeries(key: string, range: OperationsObservabilityRange) {
  return request<OperationsObservabilitySeries>(
    `/system/operations/items/${encodeURIComponent(key)}/series?range=${encodeURIComponent(range)}`
  );
}
