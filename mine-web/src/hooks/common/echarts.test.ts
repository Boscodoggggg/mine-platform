import { defineComponent, h, nextTick, ref } from 'vue';
import type { Ref } from 'vue';
import { mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const chartHarness = vi.hoisted(() => ({
  init: vi.fn(),
  use: vi.fn(),
  width: null as Ref<number> | null,
  height: null as Ref<number> | null
}));

vi.mock('echarts/core', () => ({
  init: chartHarness.init,
  use: chartHarness.use
}));

vi.mock('@vueuse/core', async importOriginal => {
  const [actual, vue] = await Promise.all([
    importOriginal<typeof import('@vueuse/core')>(),
    vi.importActual<typeof import('vue')>('vue')
  ]);
  chartHarness.width = vue.ref(0);
  chartHarness.height = vue.ref(0);
  return {
    ...actual,
    useElementSize: () => ({ width: chartHarness.width, height: chartHarness.height })
  };
});

vi.mock('@/store/modules/theme', () => ({
  useThemeStore: () => ({ darkMode: false, themeColor: 'test-primary' })
}));

import { useEcharts } from './echarts';

describe('useEcharts', () => {
  beforeEach(() => {
    chartHarness.init.mockReset().mockImplementation(() => ({
      clear: vi.fn(),
      dispose: vi.fn(),
      hideLoading: vi.fn(),
      resize: vi.fn(),
      setOption: vi.fn(),
      showLoading: vi.fn()
    }));
    chartHarness.width!.value = 0;
    chartHarness.height!.value = 0;
  });

  it('画布引用释放后不得使用空 DOM，重新挂载时必须迁移到新节点', async () => {
    let domRef: ReturnType<typeof useEcharts>['domRef'];
    const visible = ref(true);
    const renderKey = ref(1);
    const Harness = defineComponent({
      setup() {
        const chart = useEcharts(() => ({ series: [] }));
        domRef = chart.domRef;
        return () => visible.value
          ? h('div', { key: renderKey.value, ref: chart.domRef })
          : h('span');
      }
    });
    const wrapper = mount(Harness);
    chartHarness.width!.value = 320;
    chartHarness.height!.value = 180;
    await nextTick();

    expect(chartHarness.init).toHaveBeenCalledOnce();
    expect(chartHarness.init.mock.calls[0]![0]).toBeInstanceOf(HTMLElement);
    const firstChart = chartHarness.init.mock.results[0]!.value;

    visible.value = false;
    await nextTick();
    chartHarness.width!.value = 0;
    await nextTick();

    expect(chartHarness.init).toHaveBeenCalledOnce();

    visible.value = true;
    renderKey.value += 1;
    await nextTick();
    const replacementElement = domRef!.value;
    chartHarness.width!.value = 640;
    chartHarness.height!.value = 360;
    await nextTick();

    await vi.waitFor(() => expect(chartHarness.init).toHaveBeenCalledTimes(2));
    expect(chartHarness.init.mock.calls[1]![0]).toBe(replacementElement);
    expect(firstChart.dispose).toHaveBeenCalledOnce();
    wrapper.unmount();
  });
});
