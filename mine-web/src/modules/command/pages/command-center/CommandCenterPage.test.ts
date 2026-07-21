import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NButton } from 'naive-ui';
import { ApiError } from '@/api/client';
import { i18n, setLocale } from '@/locales';
import CommandCenterPage from './CommandCenterPage.vue';

const api = vi.hoisted(() => ({
  cancelCommand: vi.fn(),
  createCommand: vi.fn(),
  createRawHexCommand: vi.fn(),
  getCommand: vi.fn(),
  getCommandTargets: vi.fn(),
  getCommandTemplates: vi.fn(),
  getCommands: vi.fn()
}));
const registryApi = vi.hoisted(() => ({
  getDevices: vi.fn(),
  getProducts: vi.fn()
}));
const authState = vi.hoisted(() => ({
  userInfo: {
    buttons: [
      'command:instance:create',
      'command:template:view',
      'command:raw:execute',
      'command:instance:cancel'
    ] as string[]
  }
}));

vi.mock('@/modules/command/api/command', () => api);
vi.mock('@/modules/device/api/device', () => registryApi);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

describe('CommandCenterPage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    Object.values(api).forEach(mock => mock.mockReset());
    Object.values(registryApi).forEach(mock => mock.mockReset());
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
    api.createRawHexCommand.mockResolvedValue(command('QUEUED'));
    api.getCommand.mockResolvedValue(command('SUCCEEDED'));
    registryApi.getProducts.mockResolvedValue({
      items: [product()],
      page: 1,
      size: 100,
      total: 1
    });
    registryApi.getDevices.mockResolvedValue({
      items: [device()],
      page: 1,
      size: 100,
      total: 1
    });
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
    window.$dialog = { warning: vi.fn() } as never;
  });

  it('应展示命令状态和已落库的时间线', async () => {
    const wrapper = mount(CommandCenterPage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.text()).toContain('命令列表');
    expect(wrapper.text()).toContain('状态查询');
    expect(wrapper.text()).toContain('执行成功');
    expect(api.getCommandTargets).toHaveBeenCalled();
  });

  it('历史命令应展示产品名称和设备名称而不是设备标识', async () => {
    api.getCommandTargets.mockResolvedValue({ items: [], page: 1, size: 100, total: 0 });
    api.getCommands.mockResolvedValue({ items: [command('EXPIRED')], page: 1, size: 20, total: 1 });
    const wrapper = mount(CommandCenterPage, { global: { plugins: [i18n] } });
    await flushPromises();

    const list = wrapper.get('[data-list-card]');
    expect(list.text()).toContain('矿用运输车');
    expect(list.text()).toContain('一号运输车');
    expect(list.text()).toContain('truck-001');
    expect(list.text()).not.toContain('device-1');
  });

  it('桌面筛选条件和紧凑操作按钮应保持在同一行容器', async () => {
    const wrapper = mount(CommandCenterPage, { global: { plugins: [i18n] } });
    await flushPromises();
    await wrapper.get('[data-list-search-panel] .n-collapse-item__header-main').trigger('click');
    await flushPromises();
    const filterRow = wrapper.find('[data-command-filter-row]');
    const filterButtons = wrapper.findAllComponents(NButton).filter(button => ['查询', '重置'].includes(button.text()));

    expect(filterRow.exists()).toBe(true);
    expect(filterRow.text()).toContain('查询');
    expect(filterRow.text()).toContain('重置');
    expect(wrapper.find('[data-list-search-panel]').exists()).toBe(true);
    expect(wrapper.find('[data-list-card]').exists()).toBe(true);
    const listToolbar = wrapper.get('[data-list-toolbar]');
    expect(listToolbar.text()).toContain('下发命令');
    expect(listToolbar.text()).toContain('刷新');
    expect(filterButtons).toHaveLength(2);
    expect(filterButtons.every(button => button.props('block') !== true)).toBe(true);
    expect(filterButtons.every(button => button.props('size') === 'small')).toBe(true);
    expect(filterButtons.every(button => button.find('.n-button__icon').exists())).toBe(true);
  });

  it('临时十六进制应合并到统一的下发命令入口', async () => {
    const wrapper = mount(CommandCenterPage, { global: { plugins: [i18n] } });
    await flushPromises();

    const toolbar = wrapper.get('[data-list-toolbar]');
    expect(toolbar.text()).toContain('下发命令');
    expect(toolbar.text()).not.toContain('临时十六进制');

    const page = wrapper.vm as unknown as { openCreate: () => void };
    page.openCreate();
    await flushPromises();
    expect(document.body.textContent).toContain('下发方式');
    expect(document.body.textContent).toContain('临时十六进制');
  });

  it('切换下发方式时应始终使用同一个目标设备', async () => {
    api.getCommandTargets.mockResolvedValue({
      items: [target(), { id: 'device-2', deviceCode: 'truck-002', name: '二号运输车' }],
      page: 1,
      size: 100,
      total: 2
    });
    const wrapper = mount(CommandCenterPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      targetDeviceId: string | null;
      rawForm: Record<string, string | number>;
      dispatchMode: 'template' | 'rawHex';
      openCreate: () => void;
      submitRawCommand: () => Promise<void>;
    };

    page.openCreate();
    page.targetDeviceId = 'device-2';
    page.dispatchMode = 'rawHex';
    Object.assign(page.rawForm, {
      rawPayloadHex: 'AA BB 01',
      reason: '现场协议联调',
      ttlSeconds: 300
    });
    await page.submitRawCommand();

    expect(api.createRawHexCommand).toHaveBeenCalledWith(
      expect.objectContaining({ deviceId: 'device-2' }),
      expect.any(String)
    );
  });

  it('只有后端判定可取消的排队命令才应显示取消入口', async () => {
    api.getCommands.mockResolvedValue({
      items: [{ ...command('QUEUED'), cancelable: false }],
      page: 1,
      size: 20,
      total: 1
    });
    const wrapper = mount(CommandCenterPage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.get('[data-list-card]').text()).not.toContain('取消');

    api.getCommands.mockResolvedValue({
      items: [{ ...command('QUEUED'), cancelable: true }],
      page: 1,
      size: 20,
      total: 1
    });
    await (wrapper.vm as unknown as { loadCommands: () => Promise<void> }).loadCommands();
    await flushPromises();

    expect(wrapper.get('[data-list-card]').text()).toContain('取消');
  });

  it('下发前应显示二次确认且复用同一个幂等键提交', async () => {
    const wrapper = mount(CommandCenterPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      commandForm: Record<string, string | number>;
      targetDeviceId: string | null;
      openCreate: () => void;
      requestSubmit: () => void;
      submitCommand: () => Promise<void>;
      currentIdempotencyKey: string;
    };
    page.openCreate();
    page.targetDeviceId = 'device-1';
    Object.assign(page.commandForm, { templateKey: 'QUERY_STATUS' });
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

  it('临时十六进制命令应显示Broker语义确认并复用幂等键', async () => {
    const wrapper = mount(CommandCenterPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      rawForm: Record<string, string | number>;
      targetDeviceId: string | null;
      dispatchMode: 'template' | 'rawHex';
      openCreate: () => void;
      requestRawSubmit: () => void;
      submitRawCommand: () => Promise<void>;
      rawIdempotencyKey: string;
    };
    page.openCreate();
    page.dispatchMode = 'rawHex';
    page.targetDeviceId = 'device-1';
    Object.assign(page.rawForm, {
      rawPayloadHex: '0xAA BB 01',
      reason: '现场协议联调',
      ttlSeconds: 300
    });

    page.requestRawSubmit();

    expect(window.$dialog?.warning).toHaveBeenCalledWith(
      expect.objectContaining({ title: '确认发布原始字节', content: expect.stringContaining('AABB01') })
    );
    const key = page.rawIdempotencyKey;
    await page.submitRawCommand();
    await page.submitRawCommand();
    expect(api.createRawHexCommand).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ rawPayloadHex: '0xAA BB 01', reason: '现场协议联调' }),
      key
    );
    expect(api.createRawHexCommand).toHaveBeenNthCalledWith(2, expect.any(Object), key);
  });

  it('接口返回业务错误时应显示真实原因和追踪号', async () => {
    api.createCommand.mockRejectedValue(
      new ApiError({ message: '命令已被中继领取，无法取消', traceId: 'trace-command-conflict' }, 409)
    );
    const wrapper = mount(CommandCenterPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      targetDeviceId: string | null;
      commandForm: Record<string, string | number>;
      openCreate: () => void;
      submitCommand: () => Promise<void>;
    };
    page.openCreate();
    page.targetDeviceId = 'device-1';
    page.commandForm.templateKey = 'QUERY_STATUS';

    await page.submitCommand();

    expect(window.$message?.error).toHaveBeenCalledWith(
      expect.stringContaining('命令已被中继领取，无法取消')
    );
    expect(window.$message?.error).toHaveBeenCalledWith(expect.stringContaining('trace-command-conflict'));
  });
});

function target() {
  return { id: 'device-1', deviceCode: 'truck-001', name: '一号运输车' };
}

function product() {
  return {
    id: 'product-1',
    productKey: 'haul-truck',
    name: '矿用运输车',
    deviceType: 'VEHICLE',
    status: 'ACTIVE',
    archivedAt: null,
    createdAt: '2026-07-15T08:00:00Z',
    updatedAt: '2026-07-15T08:00:00Z',
    version: 0
  };
}

function device() {
  return {
    id: 'device-1',
    productId: 'product-1',
    deviceCode: 'truck-001',
    name: '一号运输车',
    status: 'ARCHIVED',
    archivedAt: '2026-07-16T08:00:00Z',
    createdAt: '2026-07-15T08:00:00Z',
    updatedAt: '2026-07-16T08:00:00Z',
    version: 1
  };
}

function command(status: string) {
  return {
    id: 'command-1',
    deviceId: 'device-1',
    templateKey: 'QUERY_STATUS',
    templateVersion: 1,
    parameters: {},
    dispatchSource: 'BUILTIN_TEMPLATE',
    payloadFormat: 'MQTT_JSON',
    replyExpected: true,
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
    cancelable: false,
    version: 4
  };
}
