import { ref } from 'vue';
import { mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import type { LocationTrajectory } from '../api/mine-api';
import type { MineLocationItem } from '../composables/useMineLocationRealtime';
import type { MineSpatialEvent, MineSpatialRegion } from '../composables/useMineSpatialEvents';
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
        warningColor: 'token-warning',
        infoColor: 'token-info'
      })
  };
});

describe('MineCoordinateCanvas', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    chart.options = null;
    chart.updateOptions.mockReset();
  });

  it('应使用主题令牌绘制区域、实时事件、质量分层点位和轨迹', async () => {
    const wrapper = mount(MineCoordinateCanvas, {
      props: {
        items: [
          location({ tagId: 'tag-1', qualityStatus: 'GOOD' }),
          location({ tagId: 'tag-2', qualityStatus: 'DEGRADED', localX: 130 })
        ],
        trajectory: [trajectoryPoint({ localX: 100 }), trajectoryPoint({ localX: 120 })],
        regions: [geofenceRegion(), { ...geofenceRegion(), zoneId: 'zone-2', zoneVersionId: 'zone-version-2' }],
        events: [spatialEvent()],
        showRegions: true,
        showEvents: true,
        selectedTagId: 'tag-1',
        loading: false
      },
      global: { plugins: [i18n] }
    });
    await Promise.resolve();

    const series = chart.options!.series as Array<{
      id: string;
      type: string;
      data: Array<{ value: number[]; itemStyle?: { color: string } } | number[]>;
    }>;
    expect(series.map(item => item.id)).toEqual([
      'trajectory',
      'geofence-regions',
      'spatial-events',
      'current-locations'
    ]);
    expect(series.find(item => item.id === 'geofence-regions')?.data).toHaveLength(9);
    expect(series.find(item => item.id === 'spatial-events')?.type).toBe('scatter');
    expect(
      (series.find(item => item.id === 'spatial-events')!.data[0] as { itemStyle: { color: string } }).itemStyle.color
    ).toBe('token-success');
    const current = series.find(item => item.id === 'current-locations')!;
    expect((current.data[0] as { itemStyle: { color: string } }).itemStyle.color).toBe('token-primary');
    expect((current.data[1] as { itemStyle: { color: string } }).itemStyle.color).toBe('token-warning');
    expect(wrapper.get('[role="img"]').attributes('aria-label')).toContain('矿区二维定位画布');
  });

  it('图层开关应独立隐藏区域和事件但保留当前位置', async () => {
    const wrapper = mount(MineCoordinateCanvas, {
      props: {
        items: [location()],
        trajectory: [],
        regions: [geofenceRegion()],
        events: [spatialEvent()],
        showRegions: false,
        showEvents: false,
        selectedTagId: null,
        loading: false
      },
      global: { plugins: [i18n] }
    });
    await Promise.resolve();

    expect((chart.options!.series as Array<{ id: string }>).map(item => item.id)).toEqual([
      'trajectory',
      'current-locations'
    ]);

    await wrapper.setProps({ showRegions: true, showEvents: true });
    const visibleIds = (chart.options!.series as Array<{ id: string }>).map(item => item.id);
    expect(visibleIds).toContain('geofence-regions');
    expect(visibleIds).toContain('spatial-events');
  });

  it('无可见点位、轨迹、区域和事件时应展示明确空状态', () => {
    const wrapper = mount(MineCoordinateCanvas, {
      props: {
        items: [],
        trajectory: [],
        regions: [],
        events: [],
        showRegions: true,
        showEvents: true,
        selectedTagId: null,
        loading: false
      },
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

function geofenceRegion(): MineSpatialRegion {
  return {
    zoneType: 'GEOFENCE',
    zoneId: 'zone-1',
    zoneVersionId: 'zone-version-1',
    coordinateSystemId: 'coordinate-1',
    name: '北区禁入围栏',
    contentHash: `sha256:${'a'.repeat(64)}`,
    shape: 'POLYGON',
    vertices: [
      { x: 100, y: 60 },
      { x: 160, y: 60 },
      { x: 160, y: 120 }
    ]
  };
}

function spatialEvent(): MineSpatialEvent {
  return {
    eventId: 'spatial-event-1',
    eventSequence: null,
    organizationId: 'organization-1',
    siteId: 'site-1',
    zoneType: 'GEOFENCE',
    zoneId: 'zone-1',
    zoneVersionId: 'zone-version-1',
    tagId: 'tag-1',
    vehicleId: 'vehicle-1',
    personId: null,
    eventType: 'ENTER',
    previousState: 'OUTSIDE',
    currentState: 'INSIDE',
    confirmationPoints: 3,
    ingestSequence: 11,
    runtimeRevision: 5,
    occurredAt: '2026-07-18T00:00:00Z',
    anchorLocationEventId: 'location-event-1',
    evidence: {
      localX: 120,
      localY: 88,
      localZ: null,
      classification: 'INSIDE',
      qualityStatus: 'GOOD',
      qualityReason: null,
      zoneContentHash: `sha256:${'a'.repeat(64)}`
    }
  };
}
