import { flushPromises, mount } from '@vue/test-utils';
import { NInput, NSpin } from 'naive-ui';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { i18n, setLocale } from '@/locales';
import SpatialManagementPage from './SpatialManagementPage.vue';

const api = vi.hoisted(() => ({
  archiveMineGeofence: vi.fn(),
  archiveMineRoute: vi.fn(),
  archiveMineWaypoint: vi.fn(),
  createMineGeofence: vi.fn(),
  createMineGeofenceVersion: vi.fn(),
  createMineRoute: vi.fn(),
  createMineRouteVersion: vi.fn(),
  createMineSpatialReplay: vi.fn(),
  createMineWaypoint: vi.fn(),
  createMineWaypointVersion: vi.fn(),
  getMineCoordinateSystems: vi.fn(),
  getMineGeofenceVersions: vi.fn(),
  getMineGeofences: vi.fn(),
  getMineRouteVersions: vi.fn(),
  getMineRoutes: vi.fn(),
  getMineSpatialEvent: vi.fn(),
  getMineSpatialEvents: vi.fn(),
  getMineWaypointVersions: vi.fn(),
  getMineWaypoints: vi.fn(),
  publishMineGeofenceVersion: vi.fn(),
  publishMineRouteVersion: vi.fn(),
  publishMineWaypointVersion: vi.fn(),
  updateMineGeofenceVersion: vi.fn(),
  updateMineRouteVersion: vi.fn(),
  updateMineWaypointVersion: vi.fn()
}));
const authState = vi.hoisted(() => ({ userInfo: { buttons: [] as string[] } }));
const contextState = vi.hoisted(() => ({
  selectedSiteId: 'site-1' as string | null,
  sites: [
    { id: 'site-1', name: '北区矿', siteCode: 'NORTH' },
    { id: 'site-2', name: '南区矿', siteCode: 'SOUTH' }
  ],
  loading: false,
  refreshContext: vi.fn(),
  selectSite: vi.fn((siteId: string) => {
    contextState.selectedSiteId = siteId;
    return true;
  })
}));

vi.mock('@/modules/mine/api/mine-api', () => api);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));
vi.mock('@/modules/mine/stores/mine-context', () => ({ useMineContextStore: () => contextState }));
vi.mock('@/modules/mine/components/MineSiteSelector.vue', () => ({
  default: { template: '<div data-site-selector></div>' }
}));
vi.mock('@/modules/mine/components/SpatialGeometryEditor.vue', () => ({
  default: {
    name: 'SpatialGeometryEditor',
    props: ['modelValue', 'geometryType', 'readonly', 'loading'],
    template: '<div data-geometry-editor :data-readonly="readonly">geometry</div>'
  }
}));

const object = {
  id: 'route-1',
  organizationId: 'organization-1',
  siteId: 'site-1',
  code: 'HAUL-R01',
  status: 'ACTIVE',
  archivedAt: null,
  createdAt: '2026-07-18T00:00:00Z',
  updatedAt: '2026-07-18T00:00:00Z',
  version: 1
};
const coordinate = {
  id: 'coordinate-1',
  systemCode: 'LOCAL-NORTH',
  name: '北区本地坐标',
  status: 'ACTIVE',
  unified: true
};
const draftRoute = {
  id: 'route-version-2',
  routeId: object.id,
  organizationId: 'organization-1',
  siteId: 'site-1',
  coordinateSystemId: coordinate.id,
  versionNumber: 2,
  name: '北区主运输路线',
  direction: 'BIDIRECTIONAL',
  applicableVehicleTypes: ['HAUL_TRUCK'],
  validFrom: '2026-07-18T00:00:00Z',
  validTo: null,
  status: 'DRAFT',
  contentHash: null,
  publishedAt: null,
  waypoints: [
    { sequenceNumber: 1, waypointVersionId: 'waypoint-version-1', stopRequired: false, expectedDwellSeconds: null },
    { sequenceNumber: 2, waypointVersionId: 'waypoint-version-2', stopRequired: true, expectedDwellSeconds: 30 }
  ],
  createdAt: '2026-07-18T00:00:00Z',
  updatedAt: '2026-07-18T00:00:00Z',
  version: 3
};
const publishedRoute = {
  ...draftRoute,
  id: 'route-version-1',
  versionNumber: 1,
  status: 'PUBLISHED',
  contentHash: 'sha256:route',
  publishedAt: '2026-07-18T01:00:00Z'
};
const event = {
  eventId: 'event-1',
  eventSequence: 41,
  organizationId: 'organization-1',
  siteId: 'site-1',
  zoneType: 'GEOFENCE',
  zoneId: 'geofence-1',
  zoneVersionId: 'geofence-version-1',
  tagId: 'tag-1',
  vehicleId: 'vehicle-1',
  personId: null,
  eventType: 'ENTER',
  previousState: 'OUTSIDE',
  currentState: 'INSIDE',
  confirmationPoints: 2,
  ingestSequence: 99,
  runtimeRevision: 7,
  occurredAt: '2026-07-18T02:00:00Z'
};

describe('SpatialManagementPage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    Object.values(api).forEach(mock => mock.mockReset());
    contextState.selectedSiteId = 'site-1';
    contextState.refreshContext.mockReset().mockResolvedValue(undefined);
    contextState.selectSite.mockClear();
    authState.userInfo.buttons = [];
    api.getMineCoordinateSystems.mockResolvedValue({ items: [coordinate], page: 1, size: 100, total: 1 });
    api.getMineRoutes.mockResolvedValue({ items: [object], page: 1, size: 100, total: 1 });
    api.getMineWaypoints.mockResolvedValue({ items: [{ ...object, id: 'waypoint-1', code: 'WP-01' }], page: 1, size: 100, total: 1 });
    api.getMineGeofences.mockResolvedValue({ items: [{ ...object, id: 'geofence-1', code: 'GEO-01' }], page: 1, size: 100, total: 1 });
    api.getMineRouteVersions.mockResolvedValue([draftRoute, publishedRoute]);
    api.getMineWaypointVersions.mockResolvedValue([]);
    api.getMineGeofenceVersions.mockResolvedValue([]);
    api.getMineSpatialEvents.mockResolvedValue({
      siteId: 'site-1',
      from: '2026-07-18T00:00:00Z',
      to: '2026-07-19T00:00:00Z',
      watermark: 100,
      nextCursor: 'cursor-2',
      items: [event]
    });
    api.archiveMineRoute.mockResolvedValue(undefined);
    api.archiveMineWaypoint.mockResolvedValue(undefined);
    api.archiveMineGeofence.mockResolvedValue(undefined);
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
    window.$dialog = { warning: vi.fn() } as never;
  });

  it('应提供四页签工作台、矿区隔离、响应式布局和权限入口', async () => {
    authState.userInfo.buttons = ['mine:spatial:edit', 'mine:spatial:publish', 'mine:spatial:event:view', 'mine:spatial:replay'];
    const wrapper = mount(SpatialManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.text()).toContain('路线');
    expect(wrapper.text()).toContain('途径点');
    expect(wrapper.text()).toContain('电子围栏');
    expect(wrapper.text()).toContain('空间事件');
    expect(wrapper.text()).toContain('HAUL-R01');
    expect(api.getMineRoutes).toHaveBeenCalledWith('site-1', expect.objectContaining({ page: 1, size: 100 }));
    expect(wrapper.find('[data-responsive-workbench]').classes()).toContain('flex-col');
    expect(wrapper.find('[data-action="create-object"]').exists()).toBe(true);

    const page = wrapper.vm as unknown as {
      activateTab: (tab: string) => Promise<void>;
      confirmArchiveObject: (value: typeof object) => void;
      archiveObject: (value: typeof object) => Promise<void>;
    };
    page.confirmArchiveObject(object);
    const archiveOptions = vi.mocked(window.$dialog!.warning).mock.calls.at(-1)![0] as {
      content: string;
      onPositiveClick: () => Promise<void>;
    };
    expect(archiveOptions.content).toContain('历史引用会永久保留');
    await page.archiveObject(object);
    expect(api.archiveMineRoute).toHaveBeenCalledWith('site-1', object.id, object.version);

    await page.activateTab('WAYPOINTS');
    await page.activateTab('GEOFENCES');
    await page.activateTab('EVENTS');
    await flushPromises();
    expect(api.getMineWaypoints).toHaveBeenCalledWith('site-1', expect.any(Object));
    expect(api.getMineGeofences).toHaveBeenCalledWith('site-1', expect.any(Object));
    expect(api.getMineSpatialEvents).toHaveBeenCalledWith('site-1', expect.any(Object));
    expect(wrapper.find('[data-action="replay"]').exists()).toBe(true);
  });

  it('切换矿区应清空旧选择并只接受最新矿区响应', async () => {
    const wrapper = mount(SpatialManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      changeSite: (siteId: string) => Promise<void>;
      selectObject: (value: typeof object) => Promise<void>;
      selectedObject: { id: string } | null;
    };

    await page.selectObject(object);
    expect(wrapper.find('[data-editor-readonly="true"]').exists()).toBe(true);
    await page.changeSite('site-2');
    await flushPromises();
    expect(contextState.selectSite).toHaveBeenCalledWith('site-2');
    expect(api.getMineRoutes).toHaveBeenLastCalledWith('site-2', expect.any(Object));
    expect(page.selectedObject).toBeNull();
  });

  it('加载、空数据和失败状态应保持明确反馈', async () => {
    let resolveRoutes!: (value: { items: never[]; page: number; size: number; total: number }) => void;
    api.getMineRoutes.mockReturnValueOnce(new Promise(resolve => { resolveRoutes = resolve; }));
    const wrapper = mount(SpatialManagementPage, { global: { plugins: [i18n] } });
    await Promise.resolve();
    await Promise.resolve();
    expect(wrapper.findAllComponents(NSpin).some(spin => spin.props('show') === true)).toBe(true);

    resolveRoutes({ items: [], page: 1, size: 100, total: 0 });
    await flushPromises();
    expect(wrapper.text()).toContain('当前矿区暂无对象');

    vi.mocked(window.$message!.error).mockClear();
    api.getMineRoutes.mockRejectedValueOnce(new Error('network unavailable'));
    mount(SpatialManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();
    expect(window.$message?.error).toHaveBeenCalledWith('操作失败，请稍后重试');
  });

  it('历史发布版本必须只读，草稿冲突应刷新最新版本', async () => {
    authState.userInfo.buttons = ['mine:spatial:edit'];
    api.updateMineRouteVersion.mockRejectedValueOnce(
      new ApiError({ code: 'MINE-SPATIAL-409-002', message: '版本冲突' }, 409)
    );
    const wrapper = mount(SpatialManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      selectObject: (value: typeof object) => Promise<void>;
      selectVersion: (value: typeof draftRoute) => void;
      saveSelectedVersion: () => Promise<void>;
    };

    await page.selectObject(object);
    page.selectVersion(publishedRoute as unknown as typeof draftRoute);
    await flushPromises();
    expect(wrapper.find('[data-history-readonly]').exists()).toBe(true);
    expect(wrapper.find('[data-action="save-version"]').exists()).toBe(false);

    page.selectVersion(draftRoute);
    await page.saveSelectedVersion();
    expect(api.getMineRouteVersions).toHaveBeenCalledTimes(2);
    expect(window.$message?.warning).toHaveBeenCalledWith('草稿已被其他用户修改，已刷新到最新版本');
  });

  it('发布必须展示运行态影响并经二次确认', async () => {
    authState.userInfo.buttons = ['mine:spatial:publish'];
    api.publishMineRouteVersion.mockResolvedValue(publishedRoute);
    const wrapper = mount(SpatialManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      selectObject: (value: typeof object) => Promise<void>;
      selectVersion: (value: typeof draftRoute) => void;
      confirmPublish: () => void;
    };
    await page.selectObject(object);
    page.selectVersion(draftRoute);
    page.confirmPublish();

    const options = vi.mocked(window.$dialog!.warning).mock.calls[0]![0] as {
      content: string;
      onPositiveClick: () => Promise<void>;
    };
    expect(options.content).toContain('运行时快照');
    expect(options.content).toContain('不可修改');
    await options.onPositiveClick();
    expect(api.publishMineRouteVersion).toHaveBeenCalledWith('site-1', object.id, draftRoute.id, draftRoute.version);
  });

  it('存在未保存修改时必须阻止发布旧草稿', async () => {
    authState.userInfo.buttons = ['mine:spatial:edit', 'mine:spatial:publish'];
    const wrapper = mount(SpatialManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      selectObject: (value: typeof object) => Promise<void>;
      confirmPublish: () => void;
    };
    await page.selectObject(object);
    const nameInput = wrapper.findAllComponents(NInput).find(input => input.props('placeholder') === '请输入版本名称');
    nameInput!.vm.$emit('update:value', '尚未保存的新名称');
    page.confirmPublish();

    expect(window.$dialog?.warning).not.toHaveBeenCalled();
    expect(window.$message?.warning).toHaveBeenCalledWith('当前表单包含未保存修改，请先保存草稿再发布');
  });

  it('归档对象必须完全只读且不显示写入入口', async () => {
    authState.userInfo.buttons = ['mine:spatial:edit', 'mine:spatial:publish'];
    const archivedObject = { ...object, status: 'ARCHIVED' };
    const wrapper = mount(SpatialManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as { selectObject: (value: typeof archivedObject) => Promise<void> };
    await page.selectObject(archivedObject);
    await flushPromises();

    expect(wrapper.find('[data-object-archived]').exists()).toBe(true);
    expect(wrapper.find('[data-editor-readonly="true"]').exists()).toBe(true);
    expect(wrapper.find('[data-action="save-version"]').exists()).toBe(false);
    expect(wrapper.find('[data-action="publish-version"]').exists()).toBe(false);
    expect(wrapper.find('[data-action="new-draft"]').exists()).toBe(false);
  });

  it('路线候选应分页加载全部活动途径点且允许环形序列', async () => {
    const secondWaypoint = { ...object, id: 'waypoint-101', code: 'WP-101' };
    api.getMineWaypoints.mockImplementation((_siteId, query: { page?: number; status?: string }) => Promise.resolve(
      query.status === 'ARCHIVED'
        ? { items: [], page: 1, size: 100, total: 0 }
        : {
            items: query.page === 2 ? [secondWaypoint] : [{ ...object, id: 'waypoint-1', code: 'WP-01' }],
            page: query.page ?? 1,
            size: 100,
            total: 101
          }
    ));
    const loopRoute = {
      ...draftRoute,
      waypoints: [
        draftRoute.waypoints[0],
        draftRoute.waypoints[1],
        { ...draftRoute.waypoints[0], sequenceNumber: 3 }
      ]
    };
    api.getMineRouteVersions.mockResolvedValue([loopRoute]);
    api.updateMineRouteVersion.mockResolvedValue(loopRoute);
    authState.userInfo.buttons = ['mine:spatial:edit'];
    const wrapper = mount(SpatialManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(api.getMineWaypoints).toHaveBeenCalledWith('site-1', { page: 1, size: 100, status: 'ACTIVE' });
    expect(api.getMineWaypoints).toHaveBeenCalledWith('site-1', { page: 2, size: 100, status: 'ACTIVE' });
    const page = wrapper.vm as unknown as {
      selectObject: (value: typeof object) => Promise<void>;
      saveSelectedVersion: () => Promise<void>;
    };
    await page.selectObject(object);
    await page.saveSelectedVersion();
    expect(api.updateMineRouteVersion).toHaveBeenCalledWith(
      'site-1',
      object.id,
      loopRoute.id,
      expect.objectContaining({ waypoints: expect.arrayContaining([expect.objectContaining({ sequenceNumber: 3 })]) })
    );
  });

  it('归档路线历史仍应使用已归档途径点版本完整绘制', async () => {
    const archivedWaypoint = { ...object, id: 'waypoint-archived', code: 'WP-ARCHIVED', status: 'ARCHIVED' };
    const archivedWaypointVersion = {
      id: 'waypoint-version-archived',
      waypointId: archivedWaypoint.id,
      organizationId: 'organization-1',
      siteId: 'site-1',
      coordinateSystemId: coordinate.id,
      versionNumber: 1,
      name: '历史装载点',
      waypointType: 'LOAD',
      x: 480,
      y: 260,
      z: null,
      radiusMeters: 12,
      minZ: null,
      maxZ: null,
      detectionPolicy: { boundaryToleranceMeters: 1, enterConfirmPoints: 2, exitConfirmPoints: 2, dwellSeconds: 60, allowedLatenessSeconds: 10, idleFlushSeconds: 30 },
      validFrom: '2026-07-01T00:00:00Z',
      validTo: null,
      status: 'PUBLISHED',
      contentHash: 'sha256:waypoint',
      publishedAt: '2026-07-01T01:00:00Z',
      createdAt: '2026-07-01T00:00:00Z',
      updatedAt: '2026-07-01T01:00:00Z',
      version: 1
    };
    api.getMineWaypoints.mockImplementation((_siteId, query: { status?: string }) => Promise.resolve(
      query.status === 'ARCHIVED'
        ? { items: [archivedWaypoint], page: 1, size: 100, total: 1 }
        : { items: [], page: 1, size: 100, total: 0 }
    ));
    api.getMineWaypointVersions.mockResolvedValue([archivedWaypointVersion]);
    const historicalRoute = {
      ...publishedRoute,
      waypoints: [{ sequenceNumber: 1, waypointVersionId: archivedWaypointVersion.id, stopRequired: false, expectedDwellSeconds: null }]
    };
    api.getMineRouteVersions.mockResolvedValue([historicalRoute]);
    const wrapper = mount(SpatialManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as { selectObject: (value: typeof object) => Promise<void> };
    await page.selectObject(object);
    const geometry = wrapper.findComponent({ name: 'SpatialGeometryEditor' });
    expect(geometry.props('modelValue')).toEqual([{ sequenceNumber: 1, x: 480, y: 260 }]);
  });

  it('事件翻页应固定水位并支持证据详情与幂等重放', async () => {
    authState.userInfo.buttons = ['mine:spatial:event:view', 'mine:spatial:replay'];
    api.getMineSpatialEvent.mockResolvedValue({ ...event, anchorLocationEventId: 'location-1', createdAt: event.occurredAt, evidence: { localX: 120, localY: 60, localZ: null, classification: 'INSIDE', qualityStatus: 'GOOD', qualityReason: null, zoneContentHash: 'sha256:zone' } });
    api.createMineSpatialReplay.mockResolvedValue({ requestId: 'replay-1', siteId: 'site-1', zoneType: 'GEOFENCE', zoneVersionId: event.zoneVersionId, from: '2026-07-18T00:00:00Z', to: '2026-07-18T02:00:00Z', status: 'PENDING', createdAt: event.occurredAt });
    const wrapper = mount(SpatialManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      activateTab: (tab: string) => Promise<void>;
      loadMoreEvents: () => Promise<void>;
      openEventDetail: (value: typeof event) => Promise<void>;
      submitReplay: (value: { zoneType: string; zoneVersionId: string; from: string; to: string; reason: string }) => Promise<void>;
    };

    await page.activateTab('EVENTS');
    await page.loadMoreEvents();
    expect(api.getMineSpatialEvents).toHaveBeenLastCalledWith(
      'site-1',
      expect.objectContaining({ watermark: 100, cursor: 'cursor-2' })
    );
    await page.openEventDetail(event);
    expect(api.getMineSpatialEvent).toHaveBeenCalledWith('site-1', event.eventId);

    await page.submitReplay({ zoneType: 'GEOFENCE', zoneVersionId: event.zoneVersionId, from: '2026-07-18T00:00:00Z', to: '2026-07-18T02:00:00Z', reason: '复盘边界告警' });
    expect(api.createMineSpatialReplay).toHaveBeenCalledWith(
      'site-1',
      expect.objectContaining({ zoneVersionId: event.zoneVersionId }),
      expect.stringMatching(/^spatial-replay:/)
    );
    const idempotencyKey = api.createMineSpatialReplay.mock.calls[0]![2] as string;
    expect(idempotencyKey).toMatch(/^spatial-replay:[a-f0-9]{64}$/);
    expect(idempotencyKey.length).toBeLessThanOrEqual(128);
    await page.submitReplay({ zoneType: 'GEOFENCE', zoneVersionId: event.zoneVersionId, from: '2026-07-18T00:00:00Z', to: '2026-07-18T02:00:00Z', reason: '使用不同原因再次复盘' });
    const secondKey = api.createMineSpatialReplay.mock.calls[1]![2] as string;
    expect(secondKey).not.toBe(idempotencyKey);
  });
});
