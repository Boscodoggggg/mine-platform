import { beforeEach, describe, expect, it, vi } from 'vitest';

const client = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@/api/client', () => client);

import {
  assignWorkOrder,
  closeShiftInstance,
  createShiftInstance,
  createWorkOrder,
  getOperationRecords,
  getOperationSummary,
  getShiftInstances,
  releaseWorkOrder
} from './operation';

describe('production operation api', () => {
  beforeEach(() => {
    client.request.mockReset().mockResolvedValue({});
    vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue('00000000-0000-4000-8000-000000000001');
  });

  it('应编码矿区作用域、营业日期和固定水位查询', () => {
    getShiftInstances('site/1', { fromDate: '2026-07-20', toDate: '2026-07-21', status: 'OPEN' });
    getOperationRecords('site/1', {
      fromDate: '2026-07-20',
      toDate: '2026-07-21',
      watermark: 18,
      cursor: 'sequence/17',
      pageSize: 100
    });
    getOperationSummary('site/1', { fromDate: '2026-07-20', toDate: '2026-07-21' });

    expect(client.request).toHaveBeenNthCalledWith(
      1,
      '/mine/sites/site%2F1/shift-instances?fromDate=2026-07-20&toDate=2026-07-21&status=OPEN'
    );
    expect(client.request).toHaveBeenNthCalledWith(
      2,
      '/mine/sites/site%2F1/operation-records?fromDate=2026-07-20&toDate=2026-07-21&watermark=18&cursor=sequence%2F17&pageSize=100'
    );
    expect(client.request).toHaveBeenNthCalledWith(
      3,
      '/mine/sites/site%2F1/operation-summary?fromDate=2026-07-20&toDate=2026-07-21'
    );
  });

  it('应按后端契约创建班次、作业单和调度分配', () => {
    const shift = { shiftDefinitionId: 'definition-1', businessDate: '2026-07-20', supervisorPersonId: null };
    const workOrder = {
      shiftInstanceId: 'shift-1',
      orderNumber: 'HAUL-01',
      title: '北区运输',
      workType: 'HAULAGE' as const,
      plannedStartAt: '2026-07-20T12:00:00.000Z',
      plannedEndAt: '2026-07-21T00:00:00.000Z',
      plannedCycles: 10,
      plannedTons: 200,
      priority: 50,
      version: 0
    };
    const assignment = {
      vehicleId: 'vehicle-1',
      driverPersonId: 'driver-1',
      validFrom: '2026-07-20T12:00:00.000Z',
      validTo: '2026-07-21T00:00:00.000Z',
      assignmentReason: '当日运输计划',
      version: 7
    };

    createShiftInstance('site-1', shift);
    createWorkOrder('site-1', workOrder);
    assignWorkOrder('site-1', 'work/order-1', assignment);

    expect(client.request).toHaveBeenNthCalledWith(1, '/mine/sites/site-1/shift-instances', {
      method: 'POST',
      body: JSON.stringify(shift)
    });
    expect(client.request).toHaveBeenNthCalledWith(2, '/mine/sites/site-1/work-orders', {
      method: 'POST',
      body: JSON.stringify(workOrder)
    });
    expect(client.request).toHaveBeenNthCalledWith(
      3,
      '/mine/sites/site-1/work-orders/work%2Forder-1/assignments',
      { method: 'POST', body: JSON.stringify(assignment) }
    );
  });

  it('高风险状态动作应自动携带唯一幂等键', () => {
    closeShiftInstance('site-1', 'shift/1', 3);
    releaseWorkOrder('site-1', 'work/1', {
      version: 7,
      routeVersionId: 'route-1',
      loadGeofenceVersionId: 'load-1',
      unloadGeofenceVersionId: 'unload-1'
    });

    expect(client.request).toHaveBeenNthCalledWith(1, '/mine/sites/site-1/shift-instances/shift%2F1/close', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'web-close-shift-shift/1-00000000-0000-4000-8000-000000000001' },
      body: JSON.stringify({ version: 3 })
    });
    expect(client.request).toHaveBeenNthCalledWith(2, '/mine/sites/site-1/work-orders/work%2F1/release', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'web-release-work-order-work/1-00000000-0000-4000-8000-000000000001' },
      body: JSON.stringify({
        version: 7,
        routeVersionId: 'route-1',
        loadGeofenceVersionId: 'load-1',
        unloadGeofenceVersionId: 'unload-1'
      })
    });
  });
});
