import { ref } from 'vue';
import { mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import type { LocationTrajectory } from '../api/mine-api';
import type { MineLocationItem } from '../composables/useMineLocationRealtime';
import MineCoordinateCanvas from './MineCoordinateCanvas.vue';

const chart = vi.hoisted(() => ({
  options: null as Record<string, unknown> | null,
  updateOptions: vi.fn()
}));

vi.mock('@/hooks/common/echarts', () => ({
  useEcharts: (factory: () => Record<string, unknown>) => {
    chart.options = factory();
    chart.updateOptions.mockImplementation((callback: (options: Record<string, unknown>) => Record<string, unknown>) => {
      chart.options = callback(chart.options!);
    });
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
        successColor: 'token-success',
        warningColor: 'token-warning'
      })
  };
});

describe('MineCoordinateCanvas', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    chart.options = null;
    chart.updateOptions.mockReset();
  });

  it('应使用主题令牌绘制质量分层点位和轨迹', async () => {
    const wrapper = mount(MineCoordinateCanvas, {
      props: {
        items: [location({ tagId: 'tag-1', qualityStatus: 'GOOD' }), location({ tagId: 'tag-2', qualityStatus: 'DEGRADED', localX: 130 })],
        trajectory: [trajectoryPoint({ localX: 100 }), trajectoryPoint({ localX: 120 })],
        selectedTagId: 'tag-1',
        loading: false
      },
      global: { plugins: [i18n] }
    });
    await Promise.resolve();

    const series = chart.options!.series as Array<{ type: string; data: Array<{ value: number[]; itemStyle?: { color: string } } | number[]> }>;
    expect(series.map(item => item.type)).toEqual(['line', 'scatter']);
    expect((series[1]!.data[0] as { itemStyle: { color: string } }).itemStyle.color).toBe('token-primary');
    expect((series[1]!.data[1] as { itemStyle: { color: string } }).itemStyle.color).toBe('token-warning');
    expect(wrapper.get('[role="img"]').attributes('aria-label')).toContain('矿区二维定位画布');
  });

  it('无点位和轨迹时应展示明确空状态', () => {
    const wrapper = mount(MineCoordinateCanvas, {
      props: { items: [], trajectory: [], selectedTagId: null, loading: false },
      global: { plugins: [i18n] }
    });

    expect(wrapper.text()).toContain('当前矿区暂无可展示的位置数据');
    expect(wrapper.find('[role="img"]').exists()).toBe(false);
  });
});

function location(overrides: Partial<MineLocationItem> = {}): MineLocationItem {
  return {
    eventId: 'event-1', organizationId: 'organization-1', siteId: 'site-1', sourceDeviceId: 'device-1',
    tagId: 'tag-1', tagCode: 'tag-001', coordinateSystemId: 'coordinate-1', vehicleId: 'vehicle-1', personId: null,
    ingestSequence: 10, occurredAt: '2026-07-18T00:00:00Z', receivedAt: '2026-07-18T00:00:01Z',
    localX: 120, localY: 88, localZ: null, speedMetersPerSecond: 4.5, headingDegrees: 90,
    accuracyMeters: 0.5, qualityStatus: 'GOOD', qualityReason: null, ...overrides
  };
}

function trajectoryPoint(
  overrides: Partial<LocationTrajectory['items'][number]> = {}
): LocationTrajectory['items'][number] {
  const { tagCode: _tagCode, ...point } = location({ ingestSequence: 9 });
  return { ...point, ...overrides };
}
