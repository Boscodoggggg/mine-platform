import { beforeEach, describe, expect, it, vi } from 'vitest';

const client = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@/api/client', () => client);

import {
  createNotificationChannel,
  getNotificationChannel,
  getNotificationChannels,
  getNotificationProviders,
  revealNotificationChannelSecrets,
  updateNotificationChannel
} from './notification';

describe('notification api', () => {
  beforeEach(() => client.request.mockReset());

  it('应读取 Provider 目录、渠道详情和受控敏感配置', () => {
    getNotificationProviders();
    getNotificationChannels();
    getNotificationChannel('channel/1');
    revealNotificationChannelSecrets('channel/1');

    expect(client.request).toHaveBeenNthCalledWith(1, '/notifications/providers');
    expect(client.request).toHaveBeenNthCalledWith(2, '/notifications/channels');
    expect(client.request).toHaveBeenNthCalledWith(3, '/notifications/channels/channel%2F1');
    expect(client.request).toHaveBeenNthCalledWith(4, '/notifications/channels/channel%2F1/secrets');
  });

  it('应使用 JSON 创建并按渠道标识更新配置', () => {
    const request = {
      channelCode: 'critical-email',
      name: '严重告警邮件',
      channelType: 'EMAIL' as const,
      provider: 'smtp' as const,
      status: 'DISABLED' as const,
      configuration: { host: 'mail.mine.local' },
      secrets: { password: 'mail-secret' },
      connectTimeoutMs: 3000,
      readTimeoutMs: 5000,
      version: 0
    };

    createNotificationChannel(request);
    updateNotificationChannel('channel/1', request);

    expect(client.request).toHaveBeenNthCalledWith(1, '/notifications/channels', {
      method: 'POST',
      body: JSON.stringify(request)
    });
    expect(client.request).toHaveBeenNthCalledWith(2, '/notifications/channels/channel%2F1', {
      method: 'PUT',
      body: JSON.stringify(request)
    });
  });
});
