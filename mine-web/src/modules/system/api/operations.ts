import { request } from '@/api/client';
import type { components } from '@/generated/mine-api';

export type SystemOperationsOverview = components['schemas']['SystemOperationsOverview'];
export type SystemOperationItem = components['schemas']['SystemOperationItem'];
export type SystemOperationCategory = components['schemas']['SystemOperationCategory'];

/** 按需检查平台服务、消息和存储运行状态。 */
export function getSystemOperations() {
  return request<SystemOperationsOverview>('/system/operations');
}
