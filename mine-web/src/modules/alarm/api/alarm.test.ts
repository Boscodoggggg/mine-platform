import { beforeEach, describe, expect, it, vi } from 'vitest';

const client = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@/api/client', () => client);

import { acknowledgeAlarm, closeAlarm, getAlarm, getAlarms } from './alarm';

describe('alarm api', () => {
  beforeEach(() => client.request.mockReset());

  it('应编码待处置告警组合筛选', () => {
    getAlarms({
      active: true,
      closed: false,
      status: 'ACTIVE_UNACKNOWLEDGED',
      severity: 'CRITICAL',
      deviceId: 'device/1',
      deviceKeyword: '北区 01',
      ruleKey: 'engine temp',
      from: '2026-07-17T00:00:00.000Z',
      to: '2026-07-18T00:00:00.000Z',
      page: 2,
      size: 50
    });

    expect(client.request).toHaveBeenCalledWith(
      '/alarms?active=true&closed=false&status=ACTIVE_UNACKNOWLEDGED&severity=CRITICAL&deviceId=device%2F1&deviceKeyword=%E5%8C%97%E5%8C%BA+01&ruleKey=engine+temp&from=2026-07-17T00%3A00%3A00.000Z&to=2026-07-18T00%3A00%3A00.000Z&page=2&size=50'
    );
  });

  it('应使用稳定幂等键确认和关闭告警', () => {
    acknowledgeAlarm('alarm/1', { note: '调度员已通知现场', version: 3 }, 'ack-key');
    closeAlarm('alarm/1', { note: '现场复核已恢复', version: 4 }, 'close-key');
    getAlarm('alarm/1');

    expect(client.request).toHaveBeenNthCalledWith(1, '/alarms/alarm%2F1/acknowledgements', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'ack-key' },
      body: JSON.stringify({ note: '调度员已通知现场', version: 3 })
    });
    expect(client.request).toHaveBeenNthCalledWith(2, '/alarms/alarm%2F1/close', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'close-key' },
      body: JSON.stringify({ note: '现场复核已恢复', version: 4 })
    });
    expect(client.request).toHaveBeenNthCalledWith(3, '/alarms/alarm%2F1');
  });
});
