import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import DeviceListPage from './DeviceListPage.vue';

const api = vi.hoisted(() => ({
  archiveDevice: vi.fn(),
  createDevice: vi.fn(),
  getDeviceAccessStatuses: vi.fn(),
  getDeviceConnectionProfile: vi.fn(),
  getDevices: vi.fn(),
  getProducts: vi.fn(),
  saveDeviceConnectionProfile: vi.fn(),
  testDeviceAccessStatus: vi.fn(),
  testDeviceConnectionProfile: vi.fn(),
  updateDevice: vi.fn()
}));
const authState = vi.hoisted(() => ({ userInfo: { buttons: [] as string[] } }));

vi.mock('@/modules/device/api/device', () => api);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

const product = {
  id: 'product-1',
  productKey: 'haul-truck',
  name: '矿用运输车',
  deviceType: 'VEHICLE',
  status: 'ACTIVE' as const,
  archivedAt: null,
  createdAt: '2026-07-15T00:00:00Z',
  updatedAt: '2026-07-15T00:00:00Z',
  version: 0
};
const device = {
  id: 'device-1',
  productId: 'product-1',
  deviceCode: 'truck-001',
  name: '一号运输车',
  status: 'ENABLED' as const,
  archivedAt: null,
  createdAt: '2026-07-15T00:00:00Z',
  updatedAt: '2026-07-15T00:00:00Z',
  version: 0
};
const connectionProfile = {
  id: 'connection-1',
  deviceId: 'device-1',
  connectionMode: 'DEVICE_TO_PLATFORM_MQTT' as const,
  host: 'mqtt.mine.local',
  port: 8883,
  tlsEnabled: true,
  tlsServerName: 'mqtt.mine.local',
  connectTimeoutMillis: 5000,
  keepAliveSeconds: 60,
  offlineThresholdSeconds: 180,
  lastTestStatus: 'NOT_TESTED' as const,
  lastTestAt: null,
  lastTestDurationMillis: null,
  lastTestMessage: null,
  lastTestStages: [],
  createdAt: '2026-07-16T00:00:00Z',
  updatedAt: '2026-07-16T00:00:00Z',
  version: 0
};

describe('DeviceListPage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    Object.values(api).forEach(mock => mock.mockReset());
    api.getProducts.mockResolvedValue({ items: [product], page: 1, size: 100, total: 1 });
    api.getDevices.mockResolvedValue({ items: [device], page: 1, size: 20, total: 1 });
    api.getDeviceAccessStatuses.mockResolvedValue([
      {
        deviceId: 'device-1',
        status: 'ONLINE',
        firstSeenAt: '2026-07-16T00:50:00Z',
        lastReceivedAt: '2026-07-16T01:00:00Z',
        offlineThresholdSeconds: 180,
        message: 'ACCESS_ONLINE'
      }
    ]);
    api.createDevice.mockResolvedValue(device);
    api.getDeviceConnectionProfile.mockResolvedValue(connectionProfile);
    api.saveDeviceConnectionProfile.mockResolvedValue({ ...connectionProfile, version: 1 });
    api.testDeviceConnectionProfile.mockResolvedValue({
      deviceId: 'device-1',
      status: 'PARTIAL',
      startedAt: '2026-07-16T01:00:00Z',
      durationMillis: 12,
      message: 'TEST_PARTIAL',
      stages: [
        { stage: 'CONFIG', status: 'PASSED', message: 'CONFIG_OK', durationMillis: 0 },
        { stage: 'DEVICE_CONFIRMATION', status: 'SKIPPED', message: 'FIRST_MESSAGE_REQUIRED', durationMillis: 0 }
      ]
    });
    api.testDeviceAccessStatus.mockResolvedValue({
      deviceId: 'device-1',
      status: 'ONLINE',
      checkedAt: '2026-07-16T01:00:01Z',
      durationMillis: 18,
      message: 'ACCESS_ONLINE',
      stages: [
        { layer: 'ENDPOINT', status: 'PASSED', message: 'TEST_PARTIAL', observedAt: null, durationMillis: 12 },
        {
          layer: 'TRANSPORT',
          status: 'PASSED',
          message: 'TRANSPORT_CONNECTED',
          observedAt: '2026-07-16T00:59:58Z',
          durationMillis: 0
        },
        {
          layer: 'ACTIVITY',
          status: 'PASSED',
          message: 'FIRST_FRAME_CONFIRMED',
          observedAt: '2026-07-16T00:50:00Z',
          durationMillis: 0
        },
        {
          layer: 'DATA',
          status: 'PASSED',
          message: 'DATA_FRESH',
          observedAt: '2026-07-16T01:00:00Z',
          durationMillis: 0
        }
      ]
    });
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
  });

  it('应显示设备与所属产品并按权限显示创建入口', async () => {
    const wrapper = mountPage(['device:device:create']);
    await flushPromises();

    expect(wrapper.text()).toContain('设备台账');
    expect(wrapper.text()).toContain('一号运输车');
    expect(wrapper.text()).toContain('矿用运输车');
    expect(wrapper.text()).toContain('在线');
    expect(wrapper.text()).toContain('最后上报');
    expect(wrapper.find('[data-action="create-device"]').exists()).toBe(true);
    expect(wrapper.find('[data-action="connection-profile"]').exists()).toBe(false);
  });

  it('创建设备时应提交产品标识和矿区内设备编码', async () => {
    const wrapper = mountPage(['device:device:create']);
    await flushPromises();
    const page = wrapper.vm as unknown as {
      deviceForm: Record<string, string>;
      openCreate: () => void;
      saveDevice: () => Promise<void>;
    };

    page.openCreate();
    Object.assign(page.deviceForm, {
      productId: 'product-1',
      deviceCode: 'truck-002',
      name: '二号运输车',
      status: 'ENABLED'
    });
    await page.saveDevice();

    expect(api.createDevice).toHaveBeenCalledWith({
      productId: 'product-1',
      deviceCode: 'truck-002',
      name: '二号运输车',
      status: 'ENABLED'
    });
  });

  it('创建设备时只能选择已发布产品', async () => {
    api.getProducts.mockResolvedValue({
      items: [{ ...product, status: 'DRAFT', id: 'product-draft', productKey: 'draft-truck' }, product],
      page: 1,
      size: 100,
      total: 2
    });
    const wrapper = mountPage(['device:device:create']);
    await flushPromises();

    const page = wrapper.vm as unknown as {
      creatableProductOptions: Array<{ value: string }>;
    };
    expect(page.creatableProductOptions.map(option => option.value)).toEqual(['product-1']);
  });

  it('应按权限加载保存接入配置并执行分层诊断', async () => {
    const wrapper = mountPage(['device:connection:view', 'device:connection:update', 'device:connection:test']);
    await flushPromises();

    expect(wrapper.find('[data-action="connection-profile"]').exists()).toBe(true);
    const page = wrapper.vm as unknown as {
      connectionForm: Record<string, string | number | boolean>;
      openConnectionConfig: (target: typeof device) => Promise<void>;
      saveConnectionProfile: () => Promise<void>;
      runConnectionTest: () => Promise<void>;
    };
    await page.openConnectionConfig(device);
    expect(api.getDeviceConnectionProfile).toHaveBeenCalledWith('device-1');

    page.connectionForm.host = 'edge.mine.local';
    page.connectionForm.port = 1883;
    await page.runConnectionTest();
    expect(api.testDeviceConnectionProfile).not.toHaveBeenCalled();

    await page.saveConnectionProfile();
    expect(api.saveDeviceConnectionProfile).toHaveBeenCalledWith(
      'device-1',
      expect.objectContaining({ host: 'edge.mine.local', port: 1883, version: 0 })
    );

    await page.runConnectionTest();
    expect(api.testDeviceConnectionProfile).toHaveBeenCalledWith('device-1');
    expect(window.$message?.warning).toHaveBeenCalledWith('网络端点检查通过，仍需设备首帧确认');
  });

  it('应支持手动检查端点传输活动和数据四层状态', async () => {
    const wrapper = mountPage(['device:connection:view', 'device:connection:test']);
    await flushPromises();
    const page = wrapper.vm as unknown as {
      accessTest: { stages: Array<{ layer: string }> } | null;
      openConnectionConfig: (target: typeof device) => Promise<void>;
      runAccessStatusTest: () => Promise<void>;
    };

    await page.openConnectionConfig(device);
    await flushPromises();

    await page.runAccessStatusTest();
    await flushPromises();

    expect(api.testDeviceAccessStatus).toHaveBeenCalledWith('device-1');
    expect(page.accessTest?.stages.map(stage => stage.layer)).toEqual(['ENDPOINT', 'TRANSPORT', 'ACTIVITY', 'DATA']);
  });

  it('切换接入方式时应更换字段语义和默认值并保留独立草稿', async () => {
    const wrapper = mountPage(['device:connection:view', 'device:connection:update']);
    await flushPromises();
    const page = wrapper.vm as unknown as {
      connectionForm: {
        connectionMode: string;
        host: string;
        port: number;
        tlsEnabled: boolean;
        keepAliveSeconds: number;
      };
      connectionPresentation: {
        description: string;
        hostLabel: string;
        hostPlaceholder: string;
        keepAliveLabel: string;
      };
      openConnectionConfig: (target: typeof device) => Promise<void>;
      switchConnectionMode: (mode: string) => void;
    };
    await page.openConnectionConfig(device);
    page.connectionForm.host = 'custom-mqtt.mine.local';

    page.switchConnectionMode('DEVICE_TO_PLATFORM_TCP');

    expect(page.connectionForm).toMatchObject({
      connectionMode: 'DEVICE_TO_PLATFORM_TCP',
      host: '',
      port: 9000,
      tlsEnabled: false,
      keepAliveSeconds: 60
    });
    expect(page.connectionPresentation.hostLabel).toBe('平台 TCP 监听地址');
    expect(page.connectionPresentation.hostPlaceholder).toBe('例如 tcp.mine.local');
    expect(page.connectionPresentation.keepAliveLabel).toBe('应用心跳间隔（秒）');
    expect(page.connectionPresentation.description).toContain('设备将主动连接');

    page.connectionForm.host = 'tcp.mine.local';
    page.switchConnectionMode('PLATFORM_TO_DEVICE_TCP');

    expect(page.connectionForm).toMatchObject({
      connectionMode: 'PLATFORM_TO_DEVICE_TCP',
      host: '',
      port: 9000,
      tlsEnabled: false,
      keepAliveSeconds: 60
    });
    expect(page.connectionPresentation.hostLabel).toBe('设备 IP 或域名');
    expect(page.connectionPresentation.description).toContain('平台将主动连接');

    page.switchConnectionMode('DEVICE_TO_PLATFORM_MQTT');

    expect(page.connectionForm.connectionMode).toBe('DEVICE_TO_PLATFORM_MQTT');
    expect(page.connectionForm.host).toBe('custom-mqtt.mine.local');
    expect(page.connectionForm.port).toBe(8883);
    expect(page.connectionForm.tlsEnabled).toBe(true);
    expect(page.connectionPresentation.hostLabel).toBe('Broker 地址');
    expect(page.connectionPresentation.keepAliveLabel).toBe('MQTT KeepAlive（秒）');
  });

  it('切换接入方式时应清除上一模式的诊断结果', async () => {
    const wrapper = mountPage(['device:connection:view', 'device:connection:test']);
    await flushPromises();
    const page = wrapper.vm as unknown as {
      connectionTest: unknown;
      accessTest: unknown;
      openConnectionConfig: (target: typeof device) => Promise<void>;
      runConnectionTest: () => Promise<void>;
      runAccessStatusTest: () => Promise<void>;
      switchConnectionMode: (mode: string) => void;
    };
    await page.openConnectionConfig(device);
    await page.runConnectionTest();
    expect(page.connectionTest).not.toBeNull();

    page.switchConnectionMode('DEVICE_TO_PLATFORM_TCP');

    expect(page.connectionTest).toBeNull();
    expect(page.accessTest).toBeNull();

    page.switchConnectionMode('DEVICE_TO_PLATFORM_MQTT');
    await page.runAccessStatusTest();
    expect(page.accessTest).not.toBeNull();

    page.switchConnectionMode('PLATFORM_TO_DEVICE_TCP');

    expect(page.connectionTest).toBeNull();
    expect(page.accessTest).toBeNull();
  });

  it('四层检查后应刷新配置版本再允许切换保存', async () => {
    api.getDeviceConnectionProfile
      .mockResolvedValueOnce(connectionProfile)
      .mockResolvedValueOnce({ ...connectionProfile, version: 1 });
    const wrapper = mountPage(['device:connection:view', 'device:connection:update', 'device:connection:test']);
    await flushPromises();
    const page = wrapper.vm as unknown as {
      connectionForm: { host: string; version: number };
      openConnectionConfig: (target: typeof device) => Promise<void>;
      runAccessStatusTest: () => Promise<void>;
      saveConnectionProfile: () => Promise<void>;
      switchConnectionMode: (mode: string) => void;
    };
    await page.openConnectionConfig(device);

    await page.runAccessStatusTest();

    expect(api.getDeviceConnectionProfile).toHaveBeenCalledTimes(2);
    expect(page.connectionForm.version).toBe(1);
    page.switchConnectionMode('PLATFORM_TO_DEVICE_TCP');
    page.connectionForm.host = 'edge.mine.local';
    await page.saveConnectionProfile();
    expect(api.saveDeviceConnectionProfile).toHaveBeenCalledWith(
      'device-1',
      expect.objectContaining({ connectionMode: 'PLATFORM_TO_DEVICE_TCP', version: 1 })
    );
  });
});

function mountPage(permissions: string[]) {
  authState.userInfo.buttons = permissions;
  return mount(DeviceListPage, { global: { plugins: [i18n] } });
}
