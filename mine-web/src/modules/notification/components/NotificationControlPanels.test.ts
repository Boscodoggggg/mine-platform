import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import NotificationPoliciesPanel from './NotificationPoliciesPanel.vue';
import NotificationRecipientsPanel from './NotificationRecipientsPanel.vue';
import NotificationTemplatesPanel from './NotificationTemplatesPanel.vue';

const api = vi.hoisted(() => ({
  archiveNotificationPolicy: vi.fn(),
  createNotificationPolicy: vi.fn(),
  createNotificationPolicyDraft: vi.fn(),
  createNotificationRecipient: vi.fn(),
  createNotificationRecipientGroup: vi.fn(),
  createNotificationTemplate: vi.fn(),
  getNotificationChannels: vi.fn(),
  getNotificationPolicies: vi.fn(),
  getNotificationRecipientGroups: vi.fn(),
  getNotificationRecipients: vi.fn(),
  getNotificationTemplates: vi.fn(),
  getNotificationTemplateVariables: vi.fn(),
  publishNotificationPolicy: vi.fn(),
  revealNotificationRecipientTarget: vi.fn(),
  updateNotificationPolicyDraft: vi.fn(),
  updateNotificationRecipient: vi.fn(),
  updateNotificationRecipientGroup: vi.fn(),
  updateNotificationTemplate: vi.fn()
}));
const authState = vi.hoisted(() => ({ userInfo: { buttons: ['*'] as string[] } }));

vi.mock('@/modules/notification/api/notification', () => api);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

const group = {
  id: 'group-1',
  groupCode: 'dispatch',
  name: '调度值班组',
  description: '二十四小时调度',
  status: 'ENABLED',
  createdAt: '2026-07-21T00:00:00Z',
  updatedAt: '2026-07-21T00:00:00Z',
  version: 1
};
const recipient = {
  id: 'recipient-1',
  groupId: 'group-1',
  recipientType: 'EXTERNAL_CONTACT',
  userId: null,
  displayName: '值班手机',
  channelType: 'SMS',
  targetMasked: '+86****0000',
  targetConfigured: true,
  status: 'ENABLED',
  createdAt: '2026-07-21T00:00:00Z',
  updatedAt: '2026-07-21T00:00:00Z',
  version: 1
};
const template = {
  id: 'template-1',
  templateCode: 'alarm-raised-sms',
  name: '告警短信',
  channelType: 'SMS',
  eventType: 'ALARM_RAISED',
  locale: 'zh-CN',
  subjectTemplate: null,
  bodyTemplate: '{{alarmTitle}}',
  providerTemplateCode: 'SMS_001',
  allowedVariables: ['alarmTitle'],
  status: 'ENABLED',
  version: 1,
  createdAt: '2026-07-21T00:00:00Z',
  updatedAt: '2026-07-21T00:00:00Z'
};
const policy = {
  id: 'policy-1',
  policyCode: 'critical',
  name: '严重告警升级',
  description: null,
  priority: 500,
  status: 'ACTIVE',
  draft: null,
  published: {
    id: 'version-1',
    versionNumber: 1,
    state: 'PUBLISHED',
    matchConditions: { mineIds: [], ruleKeys: [] },
    severityFilter: ['CRITICAL'],
    stopOnAck: true,
    notifyOnRecovery: false,
    publicationReason: '首版上线',
    publishedAt: '2026-07-21T00:00:00Z',
    steps: []
  },
  createdAt: '2026-07-21T00:00:00Z',
  updatedAt: '2026-07-21T00:00:00Z',
  archivedAt: null,
  version: 2
};

describe('Notification control panels', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    Object.values(api).forEach(mock => mock.mockReset());
    authState.userInfo.buttons = ['*'];
    api.getNotificationRecipientGroups.mockResolvedValue([group]);
    api.getNotificationRecipients.mockResolvedValue([recipient]);
    api.revealNotificationRecipientTarget.mockResolvedValue({
      recipientId: 'recipient-1',
      target: '+8613800000000',
      version: 1,
      revealedAt: '2026-07-21T00:00:00Z'
    });
    api.getNotificationTemplates.mockResolvedValue([template]);
    api.getNotificationTemplateVariables.mockResolvedValue(['alarmTitle', 'severity']);
    api.getNotificationPolicies.mockResolvedValue([policy]);
    api.getNotificationChannels.mockResolvedValue([]);
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
  });

  it('接收对象普通列表只显示脱敏目标且敏感权限动作可按需读取真实目标', async () => {
    const wrapper = mount(NotificationRecipientsPanel, {
      global: { plugins: [i18n], stubs: { teleport: true } }
    });
    await flushPromises();

    expect(wrapper.text()).toContain('+86****0000');
    expect(wrapper.text()).not.toContain('+8613800000000');
    await wrapper.get('[data-action="reveal-recipient-target"]').trigger('click');
    await flushPromises();
    expect(api.revealNotificationRecipientTarget).toHaveBeenCalledWith('group-1', 'recipient-1');
    expect(wrapper.text()).toContain('+8613800000000');
  });

  it('接收组创建抽屉应使用接收组领域文案', async () => {
    const wrapper = mount(NotificationRecipientsPanel, {
      global: { plugins: [i18n], stubs: { teleport: true } }
    });
    await flushPromises();

    await wrapper.get('[data-action="create-recipient-group"]').trigger('click');

    expect(wrapper.text()).toContain('接收组名称');
    expect(wrapper.text()).toContain('保存接收组');
    expect(wrapper.text()).not.toContain('渠道名称');
    expect(wrapper.text()).not.toContain('保存渠道');
  });

  it('模板面板应加载变量目录并展示模板事件和状态', async () => {
    const wrapper = mount(NotificationTemplatesPanel, {
      global: { plugins: [i18n], stubs: { teleport: true } }
    });
    await flushPromises();

    expect(api.getNotificationTemplateVariables).toHaveBeenCalledOnce();
    expect(wrapper.text()).toContain('告警短信');
    expect(wrapper.text()).toContain('告警触发');
  });

  it('模板创建抽屉应使用模板领域文案', async () => {
    const wrapper = mount(NotificationTemplatesPanel, {
      global: { plugins: [i18n], stubs: { teleport: true } }
    });
    await flushPromises();

    await wrapper.get('[data-action="create-template"]').trigger('click');

    expect(wrapper.text()).toContain('模板名称');
    expect(wrapper.text()).toContain('保存模板');
    expect(wrapper.text()).not.toContain('渠道名称');
    expect(wrapper.text()).not.toContain('保存渠道');
  });

  it('策略面板应区分不可变发布版本并允许派生新草稿', async () => {
    api.createNotificationPolicyDraft.mockResolvedValue({ ...policy, version: 3 });
    const wrapper = mount(NotificationPoliciesPanel, {
      global: { plugins: [i18n], stubs: { teleport: true } }
    });
    await flushPromises();

    expect(wrapper.text()).toContain('v1');
    expect(wrapper.text()).toContain('已发布');
    await wrapper.get('[data-action="derive-policy-draft"]').trigger('click');
    await flushPromises();
    expect(api.createNotificationPolicyDraft).toHaveBeenCalledWith('policy-1', { version: 2 });
  });

  it('策略创建抽屉应使用策略领域文案', async () => {
    const wrapper = mount(NotificationPoliciesPanel, {
      global: { plugins: [i18n], stubs: { teleport: true } }
    });
    await flushPromises();

    await wrapper.get('[data-action="create-policy"]').trigger('click');

    expect(wrapper.text()).toContain('策略名称');
    expect(wrapper.text()).toContain('保存草稿');
    expect(wrapper.text()).not.toContain('渠道名称');
    expect(wrapper.text()).not.toContain('保存渠道');
  });
});
