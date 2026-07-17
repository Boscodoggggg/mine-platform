import { request } from '@/api/client';
import type { components } from '@/generated/mine-api';

export type AggregationExecution = components['schemas']['AggregationExecution'];
export type AggregationRecompute = components['schemas']['AggregationRecompute'];
export type AggregationRecomputeRequest = components['schemas']['AggregationRecomputeRequest'];

/** 登记有界、幂等且可审计的显式聚合重算请求。 */
export function createAggregationRecomputation(body: AggregationRecomputeRequest, idempotencyKey: string) {
  return request<AggregationRecompute>('/aggregation-recomputations', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body)
  });
}

/** 查询 Telemetry 最近的数值聚合执行事实。 */
export function listAggregationExecutions(limit = 20) {
  return request<AggregationExecution[]>(`/aggregation-executions?${queryString({ limit })}`);
}

/** 查询最近登记的显式聚合重算请求。 */
export function listAggregationRecomputations(limit = 20) {
  return request<AggregationRecompute[]>(`/aggregation-recomputations?${queryString({ limit })}`);
}

function queryString(query: object) {
  const parameters = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') parameters.set(key, String(value));
  });
  return parameters.toString();
}
