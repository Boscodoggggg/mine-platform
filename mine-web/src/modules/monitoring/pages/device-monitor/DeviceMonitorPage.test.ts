import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import DeviceMonitorPage from './DeviceMonitorPage.vue';

const api = vi.hoisted(() => ({
  getDeviceLatest: vi.fn(),
  getDevices: vi.fn()
}));
const realtime = vi.hoisted(() => ({
  listener: null as null | ((batch: unknown) => void),
  release: vi.fn(),
  subscribe: vi.fn((_deviceId: string, _properties: string[], listener: (batch: unknown) => void) => {
    realtime.listener = listener;
    return realtime.release;
  })
}));

vi.mock('@/modules/device/api/device', () => api);
vi.mock('@/realtime', () => ({ realtimeHub: { subscribe: realtime.subscribe } }));

describe('DeviceMonitorPage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    Object.values(api).forEach(mock => mock.mockReset());
    realtime.subscribe.mockClear();
    realtime.release.mockClear();
    realtime.listener = null;
    api.getDevices.mockResolvedValue({
      items: [
        {
          id: 'device-1',
          productId: 'product-1',
          deviceCode: 'simulator-001',
          name: '模拟运输车',
          status: 'ENABLED',
          archivedAt: null,
          createdAt: '2026-07-15T00:00:00Z',
          updatedAt: '2026-07-15T00:00:00Z',
          version: 0
        }
      ],
      page: 1,
      size: 100,
      total: 1
    });
    api.getDeviceLatest.mockResolvedValue({
      deviceId: 'device-1',
      items: [
        {
          propertyId: 'engine.rpm',
          value: 1280,
          occurredAt: '2026-07-15T00:00:00Z',
          receivedAt: '2026-07-15T00:00:01Z',
          quality: 'GOOD',
          eventId: 'event-1',
          bootId: 'boot-1',
          sequence: 1,
          source: 'REDIS'
        }
      ],
      missingPropertyIds: ['coolant.temperature', 'vehicle.speed', 'fuel.level']
    });
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
  });

  it('选择设备后应先查询快照再建立实时订阅', async () => {
    const wrapper = mount(DeviceMonitorPage, { global: { plugins: [i18n] } });
    await flushPromises();

    const page = wrapper.vm as unknown as { selectedDeviceId: string; selectDevice: (value: string) => Promise<void> };
    page.selectedDeviceId = 'device-1';
    await page.selectDevice('device-1');
    await flushPromises();

    expect(api.getDeviceLatest).toHaveBeenCalledWith('device-1', [
      'engine.rpm',
      'coolant.temperature',
      'vehicle.speed',
      'fuel.level'
    ]);
    expect(realtime.subscribe).toHaveBeenCalledWith('device-1', expect.any(Array), expect.any(Function));
    expect(wrapper.text()).toContain('1280');
    expect(wrapper.text()).toContain('等待设备上报');
  });

  it('实时批次到达后应更新对应动态字段卡片', async () => {
    const wrapper = mount(DeviceMonitorPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as { selectDevice: (value: string) => Promise<void> };
    await page.selectDevice('device-1');
    await flushPromises();

    realtime.listener?.({
      values: [
        {
          deviceId: 'device-1',
          propertyId: 'vehicle.speed',
          value: 18.6,
          quality: 'GOOD',
          receivedAt: '2026-07-15T00:00:02Z'
        }
      ]
    });
    await flushPromises();

    expect(wrapper.text()).toContain('18.6');
  });
});
