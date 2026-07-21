import { beforeEach, describe, expect, it, vi } from 'vitest';

const client = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@/api/client', () => client);

import {
  createAggregationRecomputation,
  listAggregationExecutions,
  listAggregationRecomputations
} from './aggregation';
import type { AggregationRecomputeRequest } from './aggregation';

describe('aggregation api', () => {
  beforeEach(() => client.request.mockReset());

  it('应使用稳定幂等键提交重算并有界查询两类运行事实', () => {
    const body: AggregationRecomputeRequest = {
      deviceId: 'device-1',
      propertyId: 'engine.rpm',
      bucket: 'PT5M',
      from: '2026-07-01T00:00:00.000Z',
      to: '2026-07-02T00:00:00.000Z',
      reason: '补算弱网恢复前历史'
    };

    createAggregationRecomputation(body, 'aggregation-recompute-1');
    listAggregationExecutions(20);
    listAggregationRecomputations(20);

    expect(client.request).toHaveBeenNthCalledWith(1, '/aggregation-recomputations', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'aggregation-recompute-1' },
      body: JSON.stringify(body)
    });
    expect(client.request).toHaveBeenNthCalledWith(2, '/aggregation-executions?limit=20');
    expect(client.request).toHaveBeenNthCalledWith(3, '/aggregation-recomputations?limit=20');
  });
});
