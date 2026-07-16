import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import SystemOperationsPage from './SystemOperationsPage.vue';

const api = vi.hoisted(() => ({
  getSystemOperations: vi.fn(),
  checkSystemOperation: vi.fn(),
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
    api.checkSystemOperation.mockImplementation(async (key: string) => ({
      key,
      category: key === 'rabbitmq' ? 'MESSAGE' : key === 'mine-server' ? 'SERVICE' : 'STORAGE',
      status: 'UP',
      latencyMillis: 4,
      summary: key === 'rabbitmq' ? 'rabbitUp' : key === 'mine-server' ? 'serviceUp' : 'connectionUp'
    }));
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

    expect(api.checkSystemOperation).toHaveBeenCalledTimes(7);
    expect(wrapper.find('[role="region"][aria-label="即时检查结果"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('7/7 项已完成');
  });

  it('应并发发起即时检查并按完成顺序逐项展示结果', async () => {
    const rabbitMq = deferred<SystemOperationResult>();
    const redis = deferred<SystemOperationResult>();
    api.checkSystemOperation.mockImplementation((key: string) =>
      key === 'rabbitmq' ? rabbitMq.promise : redis.promise
    );
    const wrapper = mountPage();
    await flushPromises();

    const checkButton = wrapper.findAll('button').find(button => button.text().includes('立即检查'));
    await checkButton!.trigger('click');
    await wrapper.vm.$nextTick();

    expect(api.checkSystemOperation).toHaveBeenCalledTimes(2);
    expect(api.checkSystemOperation).toHaveBeenCalledWith('rabbitmq');
    expect(api.checkSystemOperation).toHaveBeenCalledWith('mine-server');
    expect(wrapper.text()).toContain('0/2 项已完成');

    rabbitMq.resolve({
      key: 'rabbitmq', category: 'MESSAGE', status: 'DOWN', latencyMillis: 50, summary: 'healthFailed'
    });
    await flushPromises();

    expect(wrapper.text()).toContain('1/2 项已完成');
    expect(wrapper.text()).toContain('50 ms');
    expect(wrapper.text()).toContain('检查中');

    redis.resolve({
      key: 'mine-server', category: 'SERVICE', status: 'UP', latencyMillis: 3, summary: 'serviceUp'
    });
    await flushPromises();

    expect(wrapper.text()).toContain('2/2 项已完成');
  });

  it('新一轮检查不应被上一轮迟到结果覆盖', async () => {
    const firstRabbitMq = deferred<SystemOperationResult>();
    const firstMineServer = deferred<SystemOperationResult>();
    const secondRabbitMq = deferred<SystemOperationResult>();
    const secondMineServer = deferred<SystemOperationResult>();
    api.checkSystemOperation
      .mockImplementationOnce(() => firstRabbitMq.promise)
      .mockImplementationOnce(() => firstMineServer.promise)
      .mockImplementationOnce(() => secondRabbitMq.promise)
      .mockImplementationOnce(() => secondMineServer.promise);
    const wrapper = mountPage();
    await flushPromises();

    const exposed = wrapper.vm as unknown as { runImmediateCheck: () => Promise<void> };
    const firstRun = exposed.runImmediateCheck();
    const secondRun = exposed.runImmediateCheck();
    secondRabbitMq.resolve({
      key: 'rabbitmq', category: 'MESSAGE', status: 'UP', latencyMillis: 2, summary: 'rabbitUp'
    });
    secondMineServer.resolve({
      key: 'mine-server', category: 'SERVICE', status: 'UP', latencyMillis: 3, summary: 'serviceUp'
    });
    await secondRun;
    await flushPromises();

    expect(wrapper.text()).toContain('2 ms');
    expect(wrapper.text()).toContain('3 ms');

    firstRabbitMq.resolve({
      key: 'rabbitmq', category: 'MESSAGE', status: 'DOWN', latencyMillis: 90, summary: 'healthFailed'
    });
    firstMineServer.resolve({
      key: 'mine-server', category: 'SERVICE', status: 'DOWN', latencyMillis: 91, summary: 'healthFailed'
    });
    await firstRun;
    await flushPromises();

    expect(wrapper.text()).not.toContain('90 ms');
    expect(wrapper.text()).not.toContain('91 ms');
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

type SystemOperationResult = {
  key: string;
  category: 'SERVICE' | 'MESSAGE' | 'STORAGE';
  status: 'UP' | 'DEGRADED' | 'DOWN' | 'UNKNOWN';
  latencyMillis: number;
  summary: string;
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(resolvePromise => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}
