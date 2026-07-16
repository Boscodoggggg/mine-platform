import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import OperationsTrendChart from './OperationsTrendChart.vue';

const chart = vi.hoisted(() => ({ updateOptions: vi.fn() }));

vi.mock('@/hooks/common/echarts', () => ({
  useEcharts: () => ({ domRef: { value: null }, updateOptions: chart.updateOptions })
}));

describe('OperationsTrendChart', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    chart.updateOptions.mockReset();
  });

  it('没有历史采样点时应展示明确空状态', () => {
    const wrapper = mount(OperationsTrendChart, {
      props: {
        loading: false,
        series: {
          key: 'redis',
          category: 'STORAGE',
          dataStatus: 'AVAILABLE',
          range: '24h',
          generatedAt: '2026-07-16T03:00:00Z',
          points: []
        }
      },
      global: { plugins: [i18n] }
    });

    expect(wrapper.text()).toContain('所选范围没有可展示的历史样本');
  });

  it('有真实采样点时应生成可访问趋势图', async () => {
    const wrapper = mount(OperationsTrendChart, {
      props: {
        loading: false,
        series: {
          key: 'redis',
          category: 'STORAGE',
          dataStatus: 'AVAILABLE',
          range: '24h',
          generatedAt: '2026-07-16T03:00:00Z',
          points: [
            { time: '2026-07-16T02:59:00Z', status: 'UP', latencyMillis: 3 },
            { time: '2026-07-16T03:00:00Z', status: 'DOWN', latencyMillis: 20 }
          ]
        }
      },
      global: { plugins: [i18n] }
    });
    await flushPromises();

    expect(wrapper.find('[role="img"]').attributes('aria-label')).toContain('redis');
    expect(chart.updateOptions).toHaveBeenCalled();
  });
});
