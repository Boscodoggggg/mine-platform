import { beforeEach, describe, expect, it, vi } from 'vitest';

const client = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@/api/client', () => client);

import {
  archiveNotificationPolicy,
  createNotificationChannel,
  createNotificationPolicy,
  createNotificationPolicyDraft,
  createNotificationRecipient,
  createNotificationRecipientGroup,
  createNotificationTemplate,
  getNotificationChannel,
  getNotificationChannels,
  getNotificationPolicies,
  getNotificationProviders,
  getNotificationRecipientGroups,
  getNotificationRecipients,
  getNotificationTemplates,
  getNotificationTemplateVariables,
  publishNotificationPolicy,
  revealNotificationChannelSecrets,
  revealNotificationRecipientTarget,
  updateNotificationPolicyDraft,
  updateNotificationRecipient,
  updateNotificationRecipientGroup,
  updateNotificationTemplate,
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

  it('应覆盖接收对象、模板和策略控制面全部端点并编码路径标识', () => {
    const group = { groupCode: 'dispatch', name: '调度组', status: 'ENABLED' as const, version: 0 };
    const recipient = {
      recipientType: 'EXTERNAL_CONTACT' as const,
      displayName: '值班手机',
      channelType: 'SMS' as const,
      target: '+8613800000000',
      status: 'ENABLED' as const,
      version: 0
    };
    const template = {
      templateCode: 'alarm-raised-sms',
      name: '告警短信',
      channelType: 'SMS' as const,
      eventType: 'ALARM_RAISED' as const,
      locale: 'zh-CN' as const,
      bodyTemplate: '{{alarmTitle}}',
      allowedVariables: ['alarmTitle'],
      status: 'ENABLED' as const,
      version: 0
    };
    const policy = {
      policyCode: 'critical',
      name: '严重告警',
      priority: 500,
      matchConditions: { mineIds: [], ruleKeys: [] },
      severityFilter: ['CRITICAL' as const],
      stopOnAck: true,
      notifyOnRecovery: false,
      steps: [],
      version: 0
    };

    getNotificationRecipientGroups();
    createNotificationRecipientGroup(group);
    updateNotificationRecipientGroup('group/1', group);
    getNotificationRecipients('group/1');
    createNotificationRecipient('group/1', recipient);
    updateNotificationRecipient('group/1', 'recipient/1', recipient);
    revealNotificationRecipientTarget('group/1', 'recipient/1');
    getNotificationTemplates();
    getNotificationTemplateVariables();
    createNotificationTemplate(template);
    updateNotificationTemplate('template/1', template);
    getNotificationPolicies();
    createNotificationPolicy(policy);
    updateNotificationPolicyDraft('policy/1', policy);
    createNotificationPolicyDraft('policy/1', { version: 2 });
    publishNotificationPolicy('policy/1', { versionId: 'version-1', publicationReason: '上线', version: 2 });
    archiveNotificationPolicy('policy/1', { version: 3 });

    expect(client.request.mock.calls.map(call => call[0])).toEqual([
      '/notifications/recipient-groups',
      '/notifications/recipient-groups',
      '/notifications/recipient-groups/group%2F1',
      '/notifications/recipient-groups/group%2F1/recipients',
      '/notifications/recipient-groups/group%2F1/recipients',
      '/notifications/recipient-groups/group%2F1/recipients/recipient%2F1',
      '/notifications/recipient-groups/group%2F1/recipients/recipient%2F1/target',
      '/notifications/templates',
      '/notifications/templates/variables',
      '/notifications/templates',
      '/notifications/templates/template%2F1',
      '/notifications/policies',
      '/notifications/policies',
      '/notifications/policies/policy%2F1/draft',
      '/notifications/policies/policy%2F1/drafts',
      '/notifications/policies/policy%2F1/publish',
      '/notifications/policies/policy%2F1/archive'
    ]);
  });
});
