import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import HomePage from './index.vue';

const api = vi.hoisted(() => ({ getOperationsDashboard: vi.fn() }));
const router = vi.hoisted(() => ({ push: vi.fn() }));
const authState = vi.hoisted(() => ({
  userInfo: {
    displayName: '矿区值班员',
    userName: 'dispatcher',
    buttons: ['device:telemetry:view', 'device:device:view', 'command:instance:view'] as string[]
  }
}));

vi.mock('@/modules/dashboard/api/operations', () => api);
vi.mock('vue-router', async importOriginal => {
  const original = await importOriginal<typeof import('vue-router')>();
  return { ...original, useRouter: () => router };
});
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

describe('HomePage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    api.getOperationsDashboard.mockReset();
    router.push.mockReset();
    api.getOperationsDashboard.mockResolvedValue(availableOverview());
  });

  it('应优先展示真实运行指标和需要关注的设备', async () => {
    const wrapper = mount(HomePage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.text()).toContain('矿区运行态势');
    expect(wrapper.text()).toContain('启用设备');
    expect(wrapper.text()).toContain('正常上报');
    expect(wrapper.text()).toContain('数据中断');
    expect(wrapper.text()).toContain('今日接收');
    expect(wrapper.text()).toContain('12,500');
    expect(wrapper.text()).toContain('九号矿卡');
    expect(wrapper.text()).toContain('从未上报');
    expect(wrapper.text()).not.toContain('Phase 0');
    expect(wrapper.text()).not.toContain('待建设');
  });

  it('遥测不可用时应明确显示未知而不是伪造零值', async () => {
    api.getOperationsDashboard.mockResolvedValue({
      ...availableOverview(),
      dataStatus: 'UNAVAILABLE',
      devices: { enabled: 12, reporting: null, stale: null, neverReported: null },
      telemetry: { todayMessages: null, latestReceivedAt: null, hourly: [] },
      attentionDevices: []
    });

    const wrapper = mount(HomePage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.text()).toContain('运行数据暂不可用');
    expect(wrapper.text()).toContain('暂不可用');
    expect(wrapper.text()).not.toContain('正常上报0');
    expect(wrapper.text()).not.toContain('今日接收0');
  });

  it('刷新时遥测不可用应保留最后成功快照并明确标记陈旧', async () => {
    api.getOperationsDashboard
      .mockResolvedValueOnce(availableOverview())
      .mockResolvedValueOnce({
        ...availableOverview(),
        dataStatus: 'UNAVAILABLE',
        devices: { enabled: 12, reporting: null, stale: null, neverReported: null },
        telemetry: { todayMessages: null, latestReceivedAt: null, hourly: [] },
        attentionDevices: []
      });
    const wrapper = mount(HomePage, { global: { plugins: [i18n] } });
    await flushPromises();

    const refresh = wrapper.findAll('button').find(button => button.text().includes('刷新'));
    await refresh?.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('运行数据暂不可用');
    expect(wrapper.text()).toContain('12,500');
    expect(wrapper.text()).toContain('九号矿卡');
  });
});

function availableOverview() {
  return {
    generatedAt: '2026-07-16T02:30:00Z',
    dataStatus: 'AVAILABLE' as const,
    freshnessSeconds: 300,
    devices: { enabled: 12, reporting: 8, stale: 3, neverReported: 1 },
    telemetry: {
      todayMessages: 12500,
      latestReceivedAt: '2026-07-16T02:29:58Z',
      hourly: [{ hour: '2026-07-16T02:00:00Z', count: 730 }]
    },
    attentionDevices: [
      {
        deviceId: 'device-9',
        deviceCode: 'truck-009',
        name: '九号矿卡',
        state: 'NEVER_REPORTED' as const,
        lastSeenAt: null
      }
    ]
  };
}
