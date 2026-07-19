import { ref } from 'vue';
import { mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';

const chart = vi.hoisted(() => ({
  options: null as Record<string, unknown> | null,
  updateOptions: vi.fn()
}));

vi.mock('@/hooks/common/echarts', () => ({
  useEcharts: (factory: () => Record<string, unknown>) => {
    chart.options = factory();
    chart.updateOptions.mockImplementation(
      (callback: (options: Record<string, unknown>) => Record<string, unknown>) => {
        chart.options = callback(chart.options!);
      }
    );
    return { domRef: ref(null), updateOptions: chart.updateOptions };
  }
}));

vi.mock('naive-ui', async importOriginal => {
  const actual = await importOriginal<typeof import('naive-ui')>();
  return {
    ...actual,
    useThemeVars: () =>
      ref({
        textColor3: 'token-text',
        dividerColor: 'token-divider',
        primaryColor: 'token-primary',
        primaryColorHover: 'token-primary-hover',
        warningColor: 'token-warning',
        errorColor: 'token-error'
      })
  };
});

async function loadEditor() {
  return import('./SpatialGeometryEditor.vue')
    .then(module => module.default)
    .catch(() => null);
}

describe('SpatialGeometryEditor', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    chart.options = null;
    chart.updateOptions.mockReset();
  });

  it('应使用主题令牌绘制有序多边形并提供无障碍说明', async () => {
    const component = await loadEditor();
    expect(component).not.toBeNull();
    if (!component) return;
    const wrapper = mount(component, {
      props: {
        modelValue: points([0, 0], [100, 0], [100, 100]),
        geometryType: 'POLYGON',
        readonly: false,
        loading: false
      },
      global: { plugins: [i18n] }
    });
    await Promise.resolve();

    const series = chart.options!.series as Array<{
      type: string;
      lineStyle?: { color: string };
      areaStyle?: { color: string };
      data: number[][];
    }>;
    expect(series[0]).toMatchObject({
      type: 'line',
      lineStyle: { color: 'token-primary' },
      areaStyle: { color: 'token-primary-hover' }
    });
    expect(series[0]!.data).toEqual([
      [0, 0],
      [100, 0],
      [100, 100],
      [0, 0]
    ]);
    expect(wrapper.get('[role="img"]').attributes('aria-label')).toContain('电子围栏几何编辑画布');
  });

  it('应拒绝顶点不足、重复、面积过小和自相交多边形', async () => {
    const component = await loadEditor();
    expect(component).not.toBeNull();
    if (!component) return;
    const wrapper = mount(component, {
      props: {
        modelValue: points([0, 0], [100, 100], [0, 100], [100, 0]),
        geometryType: 'POLYGON',
        readonly: false,
        loading: false
      },
      global: { plugins: [i18n] }
    });

    expect(wrapper.text()).toContain('多边形边界不能自相交');
    expect(wrapper.emitted('validityChange')?.at(-1)).toEqual([false]);

    await wrapper.setProps({ modelValue: points([0, 0], [0, 0], [1, 1]) });
    expect(wrapper.text()).toContain('不能包含重复坐标点');
    await wrapper.setProps({ modelValue: points([0, 0], [0.01, 0], [0, 0.01]) });
    expect(wrapper.text()).toContain('围栏面积必须大于 0.01 平方米');
    await wrapper.setProps({ modelValue: points([0, 0], [1, 1]) });
    expect(wrapper.text()).toContain('多边形至少需要 3 个顶点');
  });

  it('历史版本应只读，草稿编辑应重新生成连续序号', async () => {
    const component = await loadEditor();
    expect(component).not.toBeNull();
    if (!component) return;
    const readonlyWrapper = mount(component, {
      props: {
        modelValue: points([0, 0], [10, 0], [10, 10]),
        geometryType: 'POLYGON',
        readonly: true,
        loading: false
      },
      global: { plugins: [i18n] }
    });
    expect(readonlyWrapper.text()).toContain('已发布版本永久只读');
    expect(readonlyWrapper.find('[data-action="add-point"]').exists()).toBe(false);

    const editableWrapper = mount(component, {
      props: {
        modelValue: points([0, 0], [10, 0]),
        geometryType: 'POLYLINE',
        readonly: false,
        loading: false
      },
      global: { plugins: [i18n] }
    });
    const editor = editableWrapper.vm as unknown as {
      addPoint: () => void;
      movePoint: (index: number, offset: number) => void;
    };
    editor.addPoint();
    editor.movePoint(2, -1);
    const emitted = editableWrapper.emitted('update:modelValue')?.at(-1)?.[0] as Array<{
      sequenceNumber: number;
    }>;
    expect(emitted.map(item => item.sequenceNumber)).toEqual([1, 2, 3]);
  });
});

function points(...coordinates: Array<[number, number]>) {
  return coordinates.map(([x, y], index) => ({ sequenceNumber: index + 1, x, y }));
}
