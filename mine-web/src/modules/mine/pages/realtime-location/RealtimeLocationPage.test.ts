import { ref } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import RealtimeLocationPage from './RealtimeLocationPage.vue';

const api = vi.hoisted(() => ({
  getMineLocationTrajectory: vi.fn(),
  getMineSpatialEvent: vi.fn(),
  getMinePeople: vi.fn(),
  getMineVehicles: vi.fn()
}));
const contextState = vi.hoisted(() => ({
  selectedSiteId: 'site-1' as string | null,
  sites: [{ id: 'site-1', name: 'A矿', siteCode: 'PIT-A' }, { id: 'site-2', name: 'B矿', siteCode: 'PIT-B' }],
  loading: false,
  refreshContext: vi.fn(),
  selectSite: vi.fn()
}));
const realtimeState = vi.hoisted(() => ({ runtime: null as Record<string, unknown> | null }));
const spatialState = vi.hoisted(() => ({ runtime: null as Record<string, unknown> | null }));
const authState = vi.hoisted(() => ({ buttons: null as ReturnType<typeof ref<string[]>> | null }));

vi.mock('@/modules/mine/api/mine-api', () => api);
vi.mock('@/modules/mine/stores/mine-context', () => ({ useMineContextStore: () => contextState }));
vi.mock('@/modules/mine/composables/useMineLocationRealtime', () => ({
  useMineLocationRealtime: () => realtimeState.runtime
}));
vi.mock('@/modules/mine/composables/useMineSpatialEvents', () => ({
  useMineSpatialEvents: () => spatialState.runtime
}));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({
    userInfo: {
      get buttons() {
        return authState.buttons!.value;
      }
    }
  })
}));
vi.mock('@/modules/mine/components/MineCoordinateCanvas.vue', () => ({
  default: {
    props: ['items', 'trajectory', 'regions', 'events', 'showRegions', 'showEvents', 'selectedTagId', 'loading'],
    template: '<div data-coordinate-canvas :data-regions-visible="showRegions" :data-events-visible="showEvents">{{ items.length }} / {{ trajectory.length }} / {{ regions.length }} / {{ events.length }}</div>'
  }
}));

describe('RealtimeLocationPage', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-07-18T08:00:00Z'));
    setLocale('zh-CN');
    Object.values(api).forEach(mock => mock.mockReset());
    contextState.selectedSiteId = 'site-1';
    contextState.refreshContext.mockReset().mockResolvedValue(undefined);
    contextState.selectSite.mockReset();
    realtimeState.runtime = runtime();
    spatialState.runtime = spatialRuntime();
    authState.buttons = ref(['mine:location:view', 'mine:spatial:view', 'mine:spatial:event:view']);
    api.getMineVehicles.mockResolvedValue({ items: [{ id: 'vehicle-1', vehicleCode: 'TRUCK-01', name: '1号矿卡' }], page: 1, size: 100, total: 1 });
    api.getMinePeople.mockResolvedValue({ items: [{ id: 'person-1', personCode: 'DRIVER-01', name: '张师傅' }], page: 1, size: 100, total: 1 });
    api.getMineLocationTrajectory.mockResolvedValue(trajectory());
    api.getMineSpatialEvent.mockResolvedValue(spatialEventDetail());
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('应展示质量、新鲜度、业务对象与窄屏堆叠', async () => {
    const wrapper = mount(RealtimeLocationPage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(contextState.refreshContext).toHaveBeenCalled();
    expect((realtimeState.runtime!.selectSite as ReturnType<typeof vi.fn>)).toHaveBeenCalledWith('site-1');
    expect(wrapper.text()).toContain('2');
    expect(wrapper.text()).toContain('1号矿卡');
    expect(wrapper.text()).toContain('质量降级');
    expect(wrapper.text()).toContain('离线');
    expect(wrapper.find('[data-responsive-stack]').classes()).toContain('flex-col');
    expect((spatialState.runtime!.selectSite as ReturnType<typeof vi.fn>)).toHaveBeenCalledWith('site-1', {
      regions: true,
      events: true
    });
    expect(wrapper.get('[data-coordinate-canvas]').text()).toContain('2 / 0 / 1 / 1');
    expect(wrapper.text()).toContain('车辆 · 1号矿卡 · TRUCK-01 进入 北区禁入围栏');

    const page = wrapper.vm as unknown as { filter: { subject: 'ALL' | 'VEHICLE' | 'PERSON' | 'TAG' } };
    page.filter.subject = 'PERSON';
    await flushPromises();
    expect(wrapper.get('[data-coordinate-canvas]').text()).toContain('1 / 0');
    wrapper.unmount();
  });

  it('弱网重连和快照截断必须持续可见', async () => {
    const runtimeState = realtimeState.runtime!;
    (runtimeState.status as ReturnType<typeof ref>).value = 'RECONNECTING';
    (runtimeState.truncated as ReturnType<typeof ref>).value = true;
    const wrapper = mount(RealtimeLocationPage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.text()).toContain('网络波动，正在自动重连');
    expect(wrapper.text()).toContain('已显示前 5,000 个点位');
    wrapper.unmount();
  });

  it('空间层失败应明确降级但保持当前位置，并允许独立关闭图层', async () => {
    (spatialState.runtime!.regionStatus as ReturnType<typeof ref>).value = 'DEGRADED';
    (spatialState.runtime!.regionError as ReturnType<typeof ref>).value = new Error('unavailable');
    const wrapper = mount(RealtimeLocationPage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.text()).toContain('空间图层暂不可用，当前位置、轨迹和资产功能不受影响');
    expect(wrapper.get('[data-coordinate-canvas]').text()).toContain('2 / 0');
    const page = wrapper.vm as unknown as { layers: { regions: boolean; events: boolean } };
    page.layers.regions = false;
    page.layers.events = false;
    await flushPromises();
    expect(wrapper.get('[data-coordinate-canvas]').attributes('data-regions-visible')).toBe('false');
    expect(wrapper.get('[data-coordinate-canvas]').attributes('data-events-visible')).toBe('false');
    wrapper.unmount();
  });

  it('实时空间事件应可查看持久化判定证据', async () => {
    const wrapper = mount(RealtimeLocationPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      openSpatialEventDetails: (event: ReturnType<typeof spatialEvent>) => Promise<void>;
    };
    await page.openSpatialEventDetails(spatialEvent());
    await flushPromises();

    expect(api.getMineSpatialEvent).toHaveBeenCalledWith('site-1', 'spatial-event-1');
    expect(document.body.textContent).toContain('判定坐标');
    expect(document.body.textContent).toContain('(120, 88)');
    expect(document.body.textContent).toContain('sha256:');
    wrapper.unmount();
  });

  it('缺少空间权限时不得请求空间区域或事件但定位仍可使用', async () => {
    authState.buttons!.value = ['mine:location:view'];
    const wrapper = mount(RealtimeLocationPage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect((spatialState.runtime!.selectSite as ReturnType<typeof vi.fn>)).toHaveBeenCalledWith('site-1', {
      regions: false,
      events: false
    });
    expect(wrapper.get('[data-coordinate-canvas]').text()).toContain('2 / 0');
    wrapper.unmount();
  });

  it('会话中空间权限被回收时应立即释放图层订阅并清空页面入口', async () => {
    const wrapper = mount(RealtimeLocationPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const selectSite = spatialState.runtime!.selectSite as ReturnType<typeof vi.fn>;
    selectSite.mockClear();

    authState.buttons!.value = ['mine:location:view'];
    await flushPromises();

    expect(selectSite).toHaveBeenCalledWith('site-1', { regions: false, events: false });
    expect(wrapper.get('[data-coordinate-canvas]').attributes('data-regions-visible')).toBe('false');
    expect(wrapper.get('[data-coordinate-canvas]').attributes('data-events-visible')).toBe('false');
    expect(wrapper.text()).not.toContain('最近空间事件');
    wrapper.unmount();
  });

  it('详情应按业务对象查询最多24小时和5000点的服务端轨迹', async () => {
    const wrapper = mount(RealtimeLocationPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      trajectoryRange: '1h' | '6h' | '24h';
      openDetails: (item: ReturnType<typeof currentItem>) => Promise<void>;
      changeSite: (siteId: string) => Promise<void>;
    };
    page.trajectoryRange = '24h';
    await page.openDetails(currentItem());
    await flushPromises();

    expect(api.getMineLocationTrajectory).toHaveBeenCalledWith(
      'site-1',
      expect.objectContaining({ subjectType: 'VEHICLE', subjectId: 'vehicle-1', maxPoints: 5000 })
    );
    const query = api.getMineLocationTrajectory.mock.calls[0][1];
    expect(Date.parse(query.to) - Date.parse(query.from)).toBe(24 * 60 * 60 * 1000);
    expect(document.body.textContent).toContain('原始 8600 个点已由服务端抽稀后展示');

    await page.changeSite('site-2');
    expect(contextState.selectSite).toHaveBeenCalledWith('site-2');
    expect((realtimeState.runtime!.selectSite as ReturnType<typeof vi.fn>)).toHaveBeenLastCalledWith('site-2');
    wrapper.unmount();
  });
});

function runtime() {
  return {
    locations: ref([
      currentItem(),
      currentItem({ tagId: 'tag-2', tagCode: 'tag-002', vehicleId: null, personId: 'person-1', ingestSequence: 11, qualityStatus: 'DEGRADED', qualityReason: 'LOW_ACCURACY', occurredAt: '2026-07-18T07:50:00Z' })
    ]),
    status: ref('LIVE'), error: ref(null), watermark: ref(10), source: ref('REDIS'), truncated: ref(false),
    connectionState: ref('CONNECTED'),
    selectSite: vi.fn().mockResolvedValue(undefined), stop: vi.fn()
  };
}

function spatialRuntime() {
  return {
    regions: ref([spatialRegion()]),
    events: ref([spatialEvent()]),
    lastEvent: ref(spatialEvent()),
    regionStatus: ref('READY'),
    eventStatus: ref('LIVE'),
    regionError: ref(null),
    eventError: ref(null),
    eventWatermark: ref(41),
    selectSite: vi.fn().mockResolvedValue(undefined),
    resyncEvents: vi.fn().mockResolvedValue(undefined),
    stop: vi.fn()
  };
}

function currentItem(overrides: Record<string, unknown> = {}) {
  return {
    eventId: 'event-1', organizationId: 'organization-1', siteId: 'site-1', sourceDeviceId: 'device-1',
    tagId: 'tag-1', tagCode: 'tag-001', coordinateSystemId: 'coordinate-1', vehicleId: 'vehicle-1', personId: null,
    ingestSequence: 10, occurredAt: '2026-07-18T07:59:50Z', receivedAt: '2026-07-18T07:59:51Z',
    localX: 120, localY: 88, localZ: null, speedMetersPerSecond: 4.5, headingDegrees: 90,
    accuracyMeters: 0.5, qualityStatus: 'GOOD', qualityReason: null, ...overrides
  };
}

function trajectory() {
  const { tagCode: _tagCode, ...point } = currentItem();
  return { siteId: 'site-1', subjectType: 'VEHICLE', subjectId: 'vehicle-1', from: '2026-07-17T08:00:00Z', to: '2026-07-18T08:00:00Z', watermark: 10, nextCursor: null, sampled: true, sourcePointCount: 8600, items: [point] };
}

function spatialRegion() {
  return {
    zoneType: 'GEOFENCE', zoneId: 'zone-1', zoneVersionId: 'zone-version-1',
    coordinateSystemId: 'coordinate-1', name: '北区禁入围栏', contentHash: `sha256:${'a'.repeat(64)}`,
    shape: 'POLYGON', vertices: [{ x: 100, y: 60 }, { x: 160, y: 60 }, { x: 160, y: 120 }]
  };
}

function spatialEvent() {
  return {
    eventId: 'spatial-event-1', eventSequence: null, organizationId: 'organization-1', siteId: 'site-1',
    zoneType: 'GEOFENCE', zoneId: 'zone-1', zoneVersionId: 'zone-version-1', tagId: 'tag-1',
    vehicleId: 'vehicle-1', personId: null, eventType: 'ENTER', previousState: 'OUTSIDE', currentState: 'INSIDE',
    confirmationPoints: 3, ingestSequence: 11, runtimeRevision: 5, occurredAt: '2026-07-18T07:59:55Z',
    anchorLocationEventId: 'location-event-1',
    evidence: { localX: 120, localY: 88, localZ: null, classification: 'INSIDE', qualityStatus: 'GOOD', qualityReason: null, zoneContentHash: `sha256:${'a'.repeat(64)}` }
  };
}

function spatialEventDetail() {
  return {
    ...spatialEvent(),
    eventSequence: 41,
    createdAt: '2026-07-18T08:00:00Z'
  };
}
