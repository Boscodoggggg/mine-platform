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
vi.mock('@/components/custom/svg-icon.vue', () => ({ default: { template: '<span aria-hidden="true"></span>' } }));
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

  it('需要关注设备应使用规整的三列表格展示', async () => {
    const wrapper = mount(HomePage, { global: { plugins: [i18n] } });
    await flushPromises();

    const table = wrapper.get('[data-testid="attention-device-table"]');
    const headers = table.findAll('th').map(header => header.text());

    expect(headers).toEqual(['设备信息', '当前状态', '最后上报']);
    expect(table.text()).toContain('九号矿卡');
    expect(table.text()).toContain('truck-009');
    expect(table.text()).toContain('从未上报');
    expect(table.text()).toContain('尚未收到遥测数据');
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
    expect(wrapper.text()).toContain('异常状态暂不可确认');
    expect(wrapper.text()).not.toContain('当前没有异常设备');
    const unavailableChart = wrapper.get('[data-testid="home-telemetry-chart"]');
    expect(unavailableChart.get('svg[role="img"]').attributes('aria-label')).toContain('遥测数据暂不可用');
    expect(unavailableChart.get('svg[role="img"]').attributes('aria-label')).not.toContain('总计 0 条');
    expect(wrapper.text()).not.toContain('正常上报0');
    expect(wrapper.text()).not.toContain('今日接收0');
  });

  it('刷新时遥测不可用应保留最后成功快照并明确标记陈旧', async () => {
    api.getOperationsDashboard.mockResolvedValueOnce(availableOverview()).mockResolvedValueOnce({
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

  it('无异常设备时应给出可执行的排查入口并使用趋势图呈现时序数据', async () => {
    api.getOperationsDashboard.mockResolvedValue(emptyOverview());

    const wrapper = mount(HomePage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.text()).toContain('当前没有异常设备');
    expect(wrapper.text()).toContain('如发现未上报的设备，可前往设备台账进行排查');
    expect(wrapper.text()).toContain('最近 24 小时未收到遥测报文');
    expect(wrapper.text()).not.toContain('暂无数据');
    const chart = wrapper.get('[data-testid="home-telemetry-chart"]');
    expect(chart.get('svg[role="img"]').attributes('aria-label')).toContain('总计 0 条，峰值每小时 0 条');

    const registryAction = wrapper.find('[data-testid="attention-device-registry"]');
    expect(registryAction.text()).toContain('查看设备台账');
    await registryAction.trigger('click');
    expect(router.push).toHaveBeenCalledWith('/device/devices');
  });

  it('常用操作应突出实时监控并补充操作说明', async () => {
    const wrapper = mount(HomePage, { global: { plugins: [i18n] } });
    await flushPromises();

    const actions = wrapper.findAll('[data-testid="quick-action"]');
    expect(actions).toHaveLength(3);
    expect(actions[0].text()).toContain('进入实时监控');
    expect(actions[0].text()).toContain('查看实时数据与设备状态');
    expect(actions[1].text()).toContain('管理设备与接入配置');
    expect(actions[2].text()).toContain('下发与查看命令记录');
    expect(actions[0].attributes('data-action-priority')).toBe('primary');
    expect(actions[1].attributes('data-action-priority')).toBe('secondary');

    await actions[0].trigger('click');
    await actions[1].trigger('click');
    await actions[2].trigger('click');
    expect(router.push).toHaveBeenNthCalledWith(1, '/device/monitor');
    expect(router.push).toHaveBeenNthCalledWith(2, '/device/devices');
    expect(router.push).toHaveBeenNthCalledWith(3, '/command/center');
  });

  it('低频遥测趋势应显示不重复的可读刻度', async () => {
    api.getOperationsDashboard.mockResolvedValue({
      ...availableOverview(),
      telemetry: {
        todayMessages: 1,
        latestReceivedAt: '2026-07-16T02:29:58Z',
        hourly: [{ hour: '2026-07-16T02:00:00Z', count: 1 }]
      }
    });

    const wrapper = mount(HomePage, { global: { plugins: [i18n] } });
    await flushPromises();

    const ticks = wrapper.findAll('[data-testid="telemetry-y-tick"]').map(tick => tick.text());
    expect(ticks).toEqual(['1', '0.5', '0']);
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

function emptyOverview() {
  return {
    generatedAt: '2026-07-16T02:30:00Z',
    dataStatus: 'AVAILABLE' as const,
    freshnessSeconds: 300,
    devices: { enabled: 0, reporting: 0, stale: 0, neverReported: 0 },
    telemetry: {
      todayMessages: 0,
      latestReceivedAt: null,
      hourly: Array.from({ length: 24 }, (_, index) => ({
        hour: new Date(Date.UTC(2026, 6, 15, index + 3)).toISOString(),
        count: 0
      }))
    },
    attentionDevices: []
  };
}
