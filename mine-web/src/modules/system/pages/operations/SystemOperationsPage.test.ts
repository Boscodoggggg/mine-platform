import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import SystemOperationsPage from './SystemOperationsPage.vue';

const api = vi.hoisted(() => ({
  getSystemOperations: vi.fn(),
  getSystemOperationsOverview: vi.fn(),
  getSystemOperationSeries: vi.fn()
}));

vi.mock('@/modules/system/api/operations', () => api);

const historicalOverview = {
  dataStatus: 'AVAILABLE' as const,
  range: '24h' as const,
  generatedAt: '2026-07-16T03:00:00Z',
  status: 'DEGRADED' as const,
  healthy: 5,
  degraded: 0,
  down: 1,
  unknown: 1,
  items: [
    {
      key: 'rabbitmq',
      category: 'MESSAGE' as const,
      status: 'DOWN' as const,
      availabilityPercent: 98.5,
      averageLatencyMillis: null,
      p95LatencyMillis: null,
      lastChangedAt: '2026-07-16T02:58:00Z',
      sampleCount: 288
    },
    {
      key: 'mine-server',
      category: 'SERVICE' as const,
      status: 'UP' as const,
      availabilityPercent: 100,
      averageLatencyMillis: null,
      p95LatencyMillis: null,
      lastChangedAt: '2026-07-15T03:00:00Z',
      sampleCount: 288
    }
  ]
};

describe('SystemOperationsPage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    vi.clearAllMocks();
    api.getSystemOperationsOverview.mockResolvedValue(historicalOverview);
    api.getSystemOperations.mockResolvedValue({
      status: 'DEGRADED',
      checkedAt: '2026-07-16T03:01:00Z',
      items: [
        { key: 'rabbitmq', category: 'MESSAGE', status: 'DOWN', latencyMillis: 50, summary: 'healthFailed' }
      ]
    });
    api.getSystemOperationSeries.mockResolvedValue({
      key: 'rabbitmq',
      category: 'MESSAGE',
      dataStatus: 'AVAILABLE',
      range: '24h',
      generatedAt: '2026-07-16T03:00:00Z',
      points: [
        { time: '2026-07-16T02:59:00Z', status: 'UP', latencyMillis: null },
        { time: '2026-07-16T03:00:00Z', status: 'DOWN', latencyMillis: null }
      ]
    });
  });

  it('应以异常优先表格展示历史可用率和运行结论', async () => {
    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.text()).toContain('运行监控');
    expect(wrapper.text()).toContain('部分异常');
    expect(wrapper.text()).toContain('5 项正常');
    expect(wrapper.text()).toContain('历史可用率');
    expect(wrapper.text()).toContain('98.5%');
    expect(wrapper.text().indexOf('RabbitMQ')).toBeLessThan(wrapper.text().indexOf('管理服务'));
    expect(api.getSystemOperationsOverview).toHaveBeenCalledWith('24h');
    expect(api.getSystemOperations).not.toHaveBeenCalled();
  });

  it('Prometheus不可用时应明确降级并允许立即检查', async () => {
    api.getSystemOperationsOverview.mockResolvedValue({
      ...historicalOverview,
      dataStatus: 'UNAVAILABLE',
      status: 'UNKNOWN',
      healthy: 0,
      down: 0,
      unknown: 7,
      items: []
    });
    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.text()).toContain('历史监控暂不可用');
    const checkButton = wrapper.findAll('button').find(button => button.text().includes('立即检查'));
    expect(checkButton).toBeDefined();
    await checkButton!.trigger('click');
    await flushPromises();

    expect(api.getSystemOperations).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain('50 ms');
  });

  it('应在用户请求时按需加载单项趋势', async () => {
    const wrapper = mountPage();
    await flushPromises();

    const trendButton = wrapper.findAll('button').find(button => button.text().includes('查看趋势'));
    expect(trendButton).toBeDefined();
    await trendButton!.trigger('click');
    await flushPromises();

    expect(api.getSystemOperationSeries).toHaveBeenCalledWith('rabbitmq', '24h');
    expect(wrapper.find('[data-testid="trend-drawer"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('RabbitMQ · 24 小时趋势');

    await wrapper.find('[data-testid="close-trend-drawer"]').trigger('click');
    expect(wrapper.find('[data-testid="trend-drawer"]').exists()).toBe(false);
  });
});

function mountPage() {
  return mount(SystemOperationsPage, {
    global: {
      plugins: [i18n],
      stubs: {
        Drawer: {
          props: ['show'],
          emits: ['update:show'],
          template:
            '<aside v-if="show" data-testid="trend-drawer"><button data-testid="close-trend-drawer" @click="$emit(\'update:show\', false)">关闭</button><slot /></aside>'
        },
        DrawerContent: { template: '<section><slot name="header" /><slot /></section>' },
        OperationsTrendChart: { template: '<div data-testid="operations-trend-chart"></div>' }
      }
    }
  });
}
