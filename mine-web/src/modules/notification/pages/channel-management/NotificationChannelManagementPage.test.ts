import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import NotificationChannelManagementPage from './NotificationChannelManagementPage.vue';

const api = vi.hoisted(() => ({
  createNotificationChannel: vi.fn(),
  getNotificationChannel: vi.fn(),
  getNotificationChannels: vi.fn(),
  getNotificationProviders: vi.fn(),
  revealNotificationChannelSecrets: vi.fn(),
  updateNotificationChannel: vi.fn()
}));
const authState = vi.hoisted(() => ({ userInfo: { buttons: [] as string[] } }));

vi.mock('@/modules/notification/api/notification', () => api);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

const smtpProvider = {
  key: { channelType: 'EMAIL', provider: 'smtp' },
  displayNameKey: 'mine.notification.provider.smtp',
  supportsDeliveryReceipt: false,
  configurationFields: [
    { key: 'host', type: 'STRING', required: true, maxLength: 253, defaultValue: null, options: [] },
    { key: 'port', type: 'INTEGER', required: true, maxLength: null, defaultValue: 587, options: [] }
  ],
  secretFields: [
    { key: 'username', type: 'SECRET', required: false, maxLength: 512, defaultValue: null, options: [] },
    { key: 'password', type: 'SECRET', required: false, maxLength: 4096, defaultValue: null, options: [] }
  ]
};
const channel = {
  id: 'channel-1',
  channelCode: 'critical-email',
  name: '严重告警邮件',
  channelType: 'EMAIL',
  provider: 'smtp',
  status: 'DISABLED',
  configuration: { host: 'mail.mine.local', port: 587 },
  secretConfigured: true,
  connectTimeoutMs: 3000,
  readTimeoutMs: 5000,
  lastVerifiedAt: null,
  createdAt: '2026-07-21T00:00:00Z',
  updatedAt: '2026-07-21T00:00:00Z',
  version: 2
};

describe('NotificationChannelManagementPage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    Object.values(api).forEach(mock => mock.mockReset());
    api.getNotificationProviders.mockResolvedValue([smtpProvider]);
    api.getNotificationChannels.mockResolvedValue([channel]);
    api.getNotificationChannel.mockResolvedValue(channel);
    api.revealNotificationChannelSecrets.mockResolvedValue({
      channelId: 'channel-1',
      provider: 'smtp',
      secrets: { username: 'mine-admin', password: 'mail-secret' },
      version: 2,
      revealedAt: '2026-07-21T00:00:00Z'
    });
    api.updateNotificationChannel.mockResolvedValue({ ...channel, version: 3 });
    api.createNotificationChannel.mockResolvedValue(channel);
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
  });

  it('授权管理员编辑渠道时应回显真实凭据并原值提交', async () => {
    authState.userInfo.buttons = [
      'notification:channel:view',
      'notification:channel:edit',
      'notification:channel:secret:view'
    ];
    const wrapper = mount(NotificationChannelManagementPage, {
      global: { plugins: [i18n], stubs: { teleport: true } }
    });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      openEdit: (value: typeof channel) => Promise<void>;
      saveChannel: () => Promise<void>;
    };

    await page.openEdit(channel);
    await flushPromises();

    expect(api.revealNotificationChannelSecrets).toHaveBeenCalledWith('channel-1');
    const password = wrapper.find('[data-field="secret-password"] input');
    expect((password.element as HTMLInputElement).value).toBe('mail-secret');

    await page.saveChannel();
    expect(api.updateNotificationChannel).toHaveBeenCalledWith(
      'channel-1',
      expect.objectContaining({
        channelCode: 'critical-email',
        configuration: { host: 'mail.mine.local', port: 587 },
        secrets: { username: 'mine-admin', password: 'mail-secret' },
        version: 2
      })
    );
    wrapper.unmount();
  });

  it('无敏感查看权限时不得读取凭据且更新必须保留既有密文', async () => {
    authState.userInfo.buttons = ['notification:channel:view', 'notification:channel:edit'];
    const wrapper = mount(NotificationChannelManagementPage, {
      global: { plugins: [i18n], stubs: { teleport: true } }
    });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      openEdit: (value: typeof channel) => Promise<void>;
      saveChannel: () => Promise<void>;
    };

    await page.openEdit(channel);
    await flushPromises();
    expect(api.revealNotificationChannelSecrets).not.toHaveBeenCalled();

    await page.saveChannel();
    expect(api.updateNotificationChannel).toHaveBeenCalledWith(
      'channel-1',
      expect.objectContaining({ secrets: {} })
    );
    wrapper.unmount();
  });
});
