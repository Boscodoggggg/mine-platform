import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NButton } from 'naive-ui';
import { i18n, setLocale } from '@/locales';
import CommandCenterPage from './CommandCenterPage.vue';

const api = vi.hoisted(() => ({
  createCommand: vi.fn(),
  getCommand: vi.fn(),
  getCommandTargets: vi.fn(),
  getCommandTemplates: vi.fn(),
  getCommands: vi.fn()
}));
const authState = vi.hoisted(() => ({
  userInfo: { buttons: ['command:instance:create', 'command:template:view'] as string[] }
}));

vi.mock('@/modules/command/api/command', () => api);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

describe('CommandCenterPage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    Object.values(api).forEach(mock => mock.mockReset());
    api.getCommandTargets.mockResolvedValue({ items: [target()], page: 1, size: 100, total: 1 });
    api.getCommandTemplates.mockResolvedValue([
      {
        key: 'QUERY_STATUS',
        version: 1,
        riskLevel: 'LOW',
        defaultTtlSeconds: 300,
        defaultTimeoutSeconds: 30,
        parameterSchema: { type: 'object', additionalProperties: false, properties: {} }
      }
    ]);
    api.getCommands.mockResolvedValue({ items: [command('SUCCEEDED')], page: 1, size: 20, total: 1 });
    api.createCommand.mockResolvedValue(command('QUEUED'));
    api.getCommand.mockResolvedValue(command('SUCCEEDED'));
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
    window.$dialog = { warning: vi.fn() } as never;
  });

  it('应展示命令状态和已落库的时间线', async () => {
    const wrapper = mount(CommandCenterPage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.text()).toContain('命令中心');
    expect(wrapper.text()).toContain('状态查询');
    expect(wrapper.text()).toContain('执行成功');
    expect(api.getCommandTargets).toHaveBeenCalled();
  });

  it('桌面筛选条件和紧凑操作按钮应保持在同一行容器', async () => {
    const wrapper = mount(CommandCenterPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const filterRow = wrapper.find('[data-command-filter-row]');
    const filterButtons = wrapper.findAllComponents(NButton).filter(button => ['查询', '重置'].includes(button.text()));

    expect(filterRow.exists()).toBe(true);
    expect(filterRow.text()).toContain('查询');
    expect(filterRow.text()).toContain('重置');
    expect(filterButtons).toHaveLength(2);
    expect(filterButtons.every(button => button.props('block') !== true)).toBe(true);
    expect(filterButtons.every(button => button.props('size') === 'small')).toBe(true);
    expect(filterButtons.every(button => button.find('.n-button__icon').exists())).toBe(true);
  });

  it('下发前应显示二次确认且复用同一个幂等键提交', async () => {
    const wrapper = mount(CommandCenterPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      commandForm: Record<string, string | number>;
      openCreate: () => void;
      requestSubmit: () => void;
      submitCommand: () => Promise<void>;
      currentIdempotencyKey: string;
    };
    page.openCreate();
    Object.assign(page.commandForm, { deviceId: 'device-1', templateKey: 'QUERY_STATUS' });
    page.requestSubmit();
    expect(window.$dialog?.warning).toHaveBeenCalled();
    const key = page.currentIdempotencyKey;
    await page.submitCommand();
    await page.submitCommand();

    expect(api.createCommand).toHaveBeenCalledTimes(2);
    expect(api.createCommand).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ deviceId: 'device-1', templateKey: 'QUERY_STATUS' }),
      key
    );
    expect(api.createCommand).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ deviceId: 'device-1', templateKey: 'QUERY_STATUS' }),
      key
    );
  });

  it('详情应展示参数、超时配置、回复和错误码', async () => {
    const timeout = {
      ...command('TIMEOUT'),
      lastErrorCode: 'DEVICE_ACK_TIMEOUT',
      reply: { online: false }
    };
    api.getCommand.mockResolvedValue(timeout);
    const wrapper = mount(CommandCenterPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as { showDetails: (command: unknown) => Promise<void> };

    await page.showDetails(timeout);
    await flushPromises();

    expect(document.body.textContent).toContain('DEVICE_ACK_TIMEOUT');
    expect(document.body.textContent).toContain('online');
    expect(document.body.textContent).toContain('300');
  });
});

function target() {
  return { id: 'device-1', deviceCode: 'truck-001', name: '一号运输车' };
}

function command(status: string) {
  return {
    id: 'command-1',
    deviceId: 'device-1',
    templateKey: 'QUERY_STATUS',
    templateVersion: 1,
    parameters: {},
    riskLevel: 'LOW',
    status,
    ttlSeconds: 300,
    timeoutSeconds: 30,
    idempotencyKey: 'request-1',
    createdAt: '2026-07-15T08:00:00Z',
    updatedAt: '2026-07-15T08:00:03Z',
    queuedAt: '2026-07-15T08:00:00Z',
    sentAt: '2026-07-15T08:00:01Z',
    deviceReceivedAt: '2026-07-15T08:00:02Z',
    completedAt: '2026-07-15T08:00:03Z',
    expiresAt: '2026-07-15T08:05:00Z',
    lastErrorCode: null,
    reply: { online: true },
    version: 4
  };
}
