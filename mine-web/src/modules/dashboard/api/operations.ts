import { request } from '@/api/client';
import type { components } from '@/generated/mine-api';

export type OperationsOverview = components['schemas']['OperationsOverview'];
export type DashboardAttentionDevice = components['schemas']['DashboardAttentionDevice'];

/** 查询矿区调度运行总览。 */
export function getOperationsDashboard() {
  return request<OperationsOverview>('/dashboard/operations');
}
