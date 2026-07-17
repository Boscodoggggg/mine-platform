import { beforeEach, describe, expect, it, vi } from 'vitest';

const client = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@/api/client', () => client);

import {
  archiveDeviceGroup,
  createStrategyDraft,
  getEffectiveStrategy,
  listDeviceGroups,
  listRetentionExecutions,
  listStrategyReleases,
  previewRetentionImpact,
  publishStrategy,
  replaceDeviceGroupMembers,
  rollbackStrategyRelease
} from './strategy';

describe('strategy api', () => {
  beforeEach(() => client.request.mockReset());

  it('应编码设备组查询条件与成员版本', () => {
    listDeviceGroups({ productId: 'product/1', keyword: '运输 组', status: 'ACTIVE', page: 2, size: 50 });
    replaceDeviceGroupMembers('group/1', { deviceIds: ['device-1'], version: 3 });
    archiveDeviceGroup('group/1', 4);

    expect(client.request).toHaveBeenNthCalledWith(
      1,
      '/device-groups?productId=product%2F1&keyword=%E8%BF%90%E8%BE%93+%E7%BB%84&status=ACTIVE&page=2&size=50'
    );
    expect(client.request).toHaveBeenNthCalledWith(2, '/device-groups/group%2F1/members', {
      method: 'PUT',
      body: JSON.stringify({ deviceIds: ['device-1'], version: 3 })
    });
    expect(client.request).toHaveBeenNthCalledWith(3, '/device-groups/group%2F1/archive', {
      method: 'POST',
      body: JSON.stringify({ version: 4 })
    });
  });

  it('应使用调用方稳定幂等键发布和回滚', () => {
    const publishBody = {
      strategyVersionId: 'version-1',
      scopeType: 'GROUP' as const,
      scopeId: 'group-1',
      reason: '调整离线判定窗口'
    };
    publishStrategy(publishBody, 'publish-key');
    rollbackStrategyRelease('release/1', { reason: '恢复上一稳定版本' }, 'rollback-key');

    expect(client.request).toHaveBeenNthCalledWith(1, '/strategy-releases', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'publish-key' },
      body: JSON.stringify(publishBody)
    });
    expect(client.request).toHaveBeenNthCalledWith(2, '/strategy-releases/release%2F1/rollback', {
      method: 'POST',
      headers: { 'Idempotency-Key': 'rollback-key' },
      body: JSON.stringify({ reason: '恢复上一稳定版本' })
    });
  });

  it('应查询草稿、发布历史与设备有效策略', () => {
    createStrategyDraft({
      productId: 'product-1',
      strategyType: 'ONLINE_STATUS',
      values: { offlineThresholdSeconds: 180 },
      description: '运输设备基线'
    });
    listStrategyReleases({ productId: 'product-1', strategyType: 'ONLINE_STATUS', limit: 30 });
    getEffectiveStrategy('device/1', 'ONLINE_STATUS');

    expect(client.request).toHaveBeenNthCalledWith(
      1,
      '/strategy-versions',
      expect.objectContaining({ method: 'POST' })
    );
    expect(client.request).toHaveBeenNthCalledWith(
      2,
      '/strategy-releases?productId=product-1&strategyType=ONLINE_STATUS&limit=30'
    );
    expect(client.request).toHaveBeenNthCalledWith(3, '/devices/device%2F1/effective-strategies/ONLINE_STATUS');
  });

  it('应原样提交保留影响预览并限制最近执行事实数量', () => {
    const body = {
      productId: 'product-1',
      scopeType: 'GROUP' as const,
      scopeId: 'group-1',
      policy: {
        enabled: true,
        retentionClass: 'STANDARD' as const,
        rawTelemetryDays: 90,
        qualityEvidenceDays: 180
      }
    };

    previewRetentionImpact(body);
    listRetentionExecutions(20);

    expect(client.request).toHaveBeenNthCalledWith(1, '/retention-previews', {
      method: 'POST',
      body: JSON.stringify(body)
    });
    expect(client.request).toHaveBeenNthCalledWith(2, '/retention-executions?limit=20');
  });
});
