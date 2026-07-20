import { ref, shallowRef } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { i18n, setLocale } from '@/locales';
import SceneWorkbenchPage from './SceneWorkbenchPage.vue';

const api = vi.hoisted(() => ({
  archiveScene: vi.fn(),
  archiveSceneAsset: vi.fn(),
  cloneNextSceneDraft: vi.fn(),
  createScene: vi.fn(),
  createSceneDraft: vi.fn(),
  getSceneRuntime: vi.fn(),
  listSceneAssets: vi.fn(),
  listSceneVersions: vi.fn(),
  listScenes: vi.fn(),
  makeDefaultScene: vi.fn(),
  publishSceneVersion: vi.fn(),
  replaceSceneLayers: vi.fn(),
  updateSceneDraft: vi.fn(),
  uploadSceneAsset: vi.fn()
}));
const mineApi = vi.hoisted(() => ({ getMineCoordinateSystems: vi.fn() }));
const authState = vi.hoisted(() => ({ userInfo: { buttons: [] as string[] } }));
const contextState = vi.hoisted(() => ({
  selectedSiteId: 'site-1' as string | null,
  sites: [
    { id: 'site-1', name: 'A矿', siteCode: 'PIT-A' },
    { id: 'site-2', name: 'B矿', siteCode: 'PIT-B' }
  ],
  loading: false,
  refreshContext: vi.fn(),
  selectSite: vi.fn()
}));
const routeGuard = vi.hoisted(() => ({ callback: undefined as undefined | ((...args: never[]) => unknown) }));
const sceneRealtimeState = vi.hoisted(() => ({ runtime: null as Record<string, unknown> | null }));

vi.mock('../api/scene', () => api);
vi.mock('@/modules/mine/api/mine-api', () => mineApi);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));
vi.mock('@/modules/mine/stores/mine-context', () => ({ useMineContextStore: () => contextState }));
vi.mock('vue-router', () => ({
  onBeforeRouteLeave: (callback: (...args: never[]) => unknown) => {
    routeGuard.callback = callback;
  }
}));
vi.mock('../components/CesiumSceneViewport.vue', () => ({
  default: {
    props: ['snapshot', 'degradationReason', 'mode', 'locations', 'regions', 'events', 'trajectory', 'selectedTagId', 'locationStaleAfterSeconds'],
    template: '<div data-testid="scene-runtime" :data-ready="Boolean(snapshot)" :data-reason="degradationReason" :data-mode="mode" :data-locations="locations.length" :data-regions="regions.length" :data-events="events.length" :data-trajectory="trajectory.length" :data-selected="selectedTagId"></div>'
  }
}));
vi.mock('../composables/useSceneRealtime', () => ({
  useSceneRealtime: () => sceneRealtimeState.runtime
}));
vi.mock('../components/SceneAssetPanel.vue', () => ({
  default: {
    props: ['assets', 'canUpload', 'uploading', 'uploadProgress'],
    emits: ['upload', 'archive'],
    template:
      '<div data-testid="scene-asset-panel" :data-progress="uploadProgress"><button data-testid="emit-upload" @click="$emit(\'upload\', {})">upload</button></div>'
  }
}));
vi.mock('../components/SceneVersionEditor.vue', () => ({
  default: {
    props: ['version', 'assets', 'canEdit', 'canPublish'],
    emits: ['save', 'publish', 'dirty-change'],
    template:
      '<div data-testid="scene-version-editor"><button data-testid="emit-dirty" @click="$emit(\'dirty-change\', true)">dirty</button><button data-testid="emit-publish" @click="$emit(\'publish\')">publish</button></div>'
  }
}));

describe('SceneWorkbenchPage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    Object.values(api).forEach(mock => mock.mockReset());
    mineApi.getMineCoordinateSystems.mockReset();
    contextState.selectedSiteId = 'site-1';
    contextState.refreshContext.mockReset().mockResolvedValue(undefined);
    contextState.selectSite.mockReset().mockImplementation((siteId: string | null) => {
      contextState.selectedSiteId = siteId;
      return true;
    });
    api.listScenes.mockResolvedValue({ items: [scene()], page: 1, size: 100, total: 1 });
    api.listSceneAssets.mockResolvedValue({ items: [], page: 1, size: 100, total: 0 });
    const draft = version();
    api.listSceneVersions.mockResolvedValue({
      items: [draft, { ...version(), sceneId: draft.sceneId, status: 'PUBLISHED', revisionNumber: 0 }],
      page: 1,
      size: 100,
      total: 2
    });
    api.getSceneRuntime.mockResolvedValue({
      siteId: 'site-1',
      status: 'DEGRADED',
      reason: 'NO_DEFAULT_SCENE',
      snapshot: null
    });
    sceneRealtimeState.runtime = sceneRealtimeRuntime();
    mineApi.getMineCoordinateSystems.mockResolvedValue({ items: [], page: 1, size: 100, total: 0 });
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
    window.$dialog = { warning: vi.fn() } as never;
  });

  it('只读用户只加载运行场景，治理数据和写操作不进入页面', async () => {
    authState.userInfo.buttons = ['mine:scene:view'];
    const wrapper = mountPage();
    await flushPromises();

    expect(api.getSceneRuntime).toHaveBeenCalledWith('site-1', expect.any(AbortSignal));
    expect(wrapper.find('[data-testid="scene-runtime"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="scene-governance"]').exists()).toBe(false);
    expect(api.listSceneAssets).not.toHaveBeenCalled();
  });

  it('运行场景允许用户显式切换二维、三维和自动模式', async () => {
    authState.userInfo.buttons = ['mine:scene:view'];
    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.get('[data-testid="scene-runtime"]').attributes('data-mode')).toBe('AUTO');
    expect(wrapper.get('[data-testid="scene-mode-auto"]').attributes('aria-pressed')).toBe('true');
    await wrapper.get('[data-testid="scene-mode-2d"]').trigger('click');
    expect(wrapper.get('[data-testid="scene-runtime"]').attributes('data-mode')).toBe('2D');
    expect(wrapper.get('[data-testid="scene-mode-2d"]').attributes('aria-pressed')).toBe('true');
    await wrapper.get('[data-testid="scene-mode-3d"]').trigger('click');
    expect(wrapper.get('[data-testid="scene-runtime"]').attributes('data-mode')).toBe('3D');
    expect(wrapper.get('[data-testid="scene-mode-3d"]').attributes('aria-pressed')).toBe('true');
  });

  it('把同一份位置、区域、事件和按需轨迹传给二维与三维视口', async () => {
    authState.userInfo.buttons = ['mine:scene:view'];
    const realtime = sceneRealtimeState.runtime!;
    (realtime.locations as ReturnType<typeof shallowRef>).value = [location()];
    (realtime.regions as ReturnType<typeof shallowRef>).value = [{ zoneId: 'zone-1' }];
    (realtime.events as ReturnType<typeof shallowRef>).value = [{ eventId: 'event-1' }];
    (realtime.trajectory as ReturnType<typeof shallowRef>).value = [{ eventId: 'trajectory-1' }];
    const readyRuntime = { siteId: 'site-1', status: 'READY', reason: 'NONE', snapshot: runtimeSnapshot() };
    api.getSceneRuntime.mockResolvedValue(readyRuntime);
    const wrapper = mountPage();
    await flushPromises();

    expect(realtime.selectSite).toHaveBeenLastCalledWith('site-1', readyRuntime.snapshot);
    expect(wrapper.get('[data-testid="scene-realtime-summary"]').text()).toContain('1 个位置');
    expect(wrapper.get('[data-testid="scene-realtime-updated-at"]').text()).toContain('位置更新');
    expect(wrapper.get('[data-testid="scene-runtime"]').attributes()).toMatchObject({
      'data-locations': '1',
      'data-regions': '1',
      'data-events': '1',
      'data-trajectory': '1'
    });

    (wrapper.vm as unknown as { selectRuntimeTag: (tagId: string) => void }).selectRuntimeTag('tag-1');
    expect(realtime.selectTag).toHaveBeenCalledWith('tag-1');
  });

  it('同矿区刷新失败保留最后正确场景且不清空实时作用域', async () => {
    authState.userInfo.buttons = ['mine:scene:view'];
    const readyRuntime = { siteId: 'site-1', status: 'READY', reason: 'NONE', snapshot: runtimeSnapshot() };
    api.getSceneRuntime.mockResolvedValueOnce(readyRuntime).mockRejectedValueOnce(new Error('runtime unavailable'));
    const wrapper = mountPage();
    await flushPromises();
    const realtime = sceneRealtimeState.runtime!;
    vi.mocked(realtime.selectSite as ReturnType<typeof vi.fn>).mockClear();

    await (wrapper.vm as unknown as { refreshCurrentSite: () => Promise<void> }).refreshCurrentSite();
    await flushPromises();

    expect(wrapper.get('[data-testid="scene-runtime"]').attributes('data-ready')).toBe('true');
    expect(realtime.selectSite).not.toHaveBeenCalledWith(null, null);
  });

  it('切换矿区会主动取消旧作用域请求并只接受新矿区结果', async () => {
    grantAllPermissions();
    const wrapper = mountPage();
    await flushPromises();
    const oldSignal = api.listScenes.mock.calls[0]?.[2] as AbortSignal;

    await (wrapper.vm as unknown as { changeSite: (siteId: string) => Promise<void> }).changeSite('site-2');
    await flushPromises();

    expect(oldSignal.aborted).toBe(true);
    expect(api.listScenes).toHaveBeenLastCalledWith('site-2', expect.any(Object), expect.any(AbortSignal));
  });

  it('未保存草稿在切换矿区前要求确认，确认后才释放旧作用域', async () => {
    grantAllPermissions();
    const wrapper = mountPage();
    await flushPromises();
    await openGovernance(wrapper);
    await wrapper.get('[data-testid="emit-dirty"]').trigger('click');

    await (wrapper.vm as unknown as { changeSite: (siteId: string) => Promise<void> }).changeSite('site-2');
    expect(window.$dialog?.warning).toHaveBeenCalledOnce();
    expect(api.listScenes).not.toHaveBeenCalledWith('site-2', expect.anything(), expect.anything());

    const options = vi.mocked(window.$dialog!.warning).mock.calls[0]?.[0] as { onPositiveClick: () => Promise<void> };
    await options.onPositiveClick();
    await flushPromises();
    expect(api.listScenes).toHaveBeenCalledWith('site-2', expect.any(Object), expect.any(AbortSignal));
  });

  it('上传展示真实进度且错误不会污染资源清单', async () => {
    grantAllPermissions();
    let rejectUpload!: (reason?: unknown) => void;
    api.uploadSceneAsset.mockImplementation((_siteId, _file, options) => {
      options.onProgress({ loaded: 37, total: 100, percent: 37 });
      return new Promise((_resolve, reject) => {
        rejectUpload = reject;
      });
    });
    const wrapper = mountPage();
    await flushPromises();
    await openGovernance(wrapper);

    await wrapper.get('[data-testid="emit-upload"]').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-testid="scene-asset-panel"]').attributes('data-progress')).toBe('37');

    rejectUpload(new ApiError({ code: 'SCENE-ASSET-422-001', message: '资源包校验失败' }, 422));
    await flushPromises();
    expect(window.$message?.error).toHaveBeenCalledWith('资源包校验失败');
  });

  it('发布冲突会刷新服务端版本，默认切换成功后重读运行快照', async () => {
    grantAllPermissions();
    api.publishSceneVersion.mockRejectedValue(new ApiError({ code: 'SCENE-409-001' }, 409));
    api.makeDefaultScene.mockResolvedValue({ ...scene(), defaultScene: true });
    const wrapper = mountPage();
    await flushPromises();
    await openGovernance(wrapper);

    await wrapper.get('[data-testid="emit-publish"]').trigger('click');
    let options = vi.mocked(window.$dialog!.warning).mock.calls.at(-1)?.[0] as { onPositiveClick: () => Promise<void> };
    await options.onPositiveClick();
    await flushPromises();
    expect(api.listSceneVersions).toHaveBeenCalledTimes(2);
    expect(window.$message?.warning).toHaveBeenCalled();

    await wrapper.get('[data-testid="make-default-scene"]').trigger('click');
    options = vi.mocked(window.$dialog!.warning).mock.calls.at(-1)?.[0] as { onPositiveClick: () => Promise<void> };
    await options.onPositiveClick();
    await flushPromises();
    expect(api.makeDefaultScene).toHaveBeenCalledWith(
      'site-1',
      expect.any(String),
      expect.any(Number),
      expect.any(String)
    );
    expect(api.getSceneRuntime).toHaveBeenCalledTimes(2);
  });
});

function mountPage() {
  return mount(SceneWorkbenchPage, { global: { plugins: [i18n] } });
}

async function openGovernance(wrapper: ReturnType<typeof mountPage>) {
  await wrapper.get('.n-tabs-tab[data-name="GOVERNANCE"]').trigger('click');
  await flushPromises();
}

function grantAllPermissions() {
  authState.userInfo.buttons = ['mine:scene:view', 'mine:scene:edit', 'mine:scene:asset:upload', 'mine:scene:publish'];
}

function sceneRealtimeRuntime() {
  return {
    selectedTagId: ref<string | null>(null),
    selectableLocations: shallowRef<unknown[]>([]),
    locationStaleAfterSeconds: ref(120),
    locations: shallowRef<unknown[]>([]),
    regions: shallowRef<unknown[]>([]),
    events: shallowRef<unknown[]>([]),
    trajectory: shallowRef<unknown[]>([]),
    locationStatus: ref('IDLE'),
    regionStatus: ref('DISABLED'),
    eventStatus: ref('DISABLED'),
    trajectoryStatus: ref('DISABLED'),
    locationUpdatedAt: ref<string | null>('2026-07-20T08:00:00Z'),
    regionUpdatedAt: ref<string | null>('2026-07-20T08:00:00Z'),
    eventUpdatedAt: ref<string | null>('2026-07-20T08:00:00Z'),
    trajectoryUpdatedAt: ref<string | null>(null),
    loading: ref(false),
    selectSite: vi.fn().mockResolvedValue(undefined),
    selectTag: vi.fn().mockResolvedValue(undefined)
  };
}

function location() {
  return { tagId: 'tag-1', tagCode: 'TAG-001' };
}

function runtimeSnapshot() {
  return {
    sceneId: crypto.randomUUID(),
    sceneCode: 'north-pit',
    sceneName: '北采区',
    versionId: crypto.randomUUID(),
    revisionNumber: 1,
    schemaVersion: 1,
    contentHash: `sha256:${'a'.repeat(64)}`,
    coordinateSystemId: crypto.randomUUID(),
    coordinateMode: 'LOCAL_ENU',
    originX: 0,
    originY: 0,
    originZ: 0,
    headingDegrees: 0,
    pitchDegrees: 0,
    rollDegrees: 0,
    scale: 1,
    defaultCamera: {},
    fallbackViewport: {},
    publishedAt: '2026-07-20T08:00:00Z',
    layerCount: 1,
    layers: [
      {
        id: crypto.randomUUID(),
        layerCode: 'locations',
        orderNumber: 1,
        layerType: 'DYNAMIC_LOCATIONS',
        visible: true,
        opacity: 1,
        minimumDistance: 0,
        maximumDistance: 100000,
        offsetX: 0,
        offsetY: 0,
        offsetZ: 0,
        headingDegrees: 0,
        pitchDegrees: 0,
        rollDegrees: 0,
        scale: 1,
        maxItems: 5000,
        options: {},
        asset: null
      }
    ]
  };
}

function scene() {
  return {
    id: crypto.randomUUID(),
    organizationId: crypto.randomUUID(),
    siteId: 'site-1',
    sceneCode: 'north-pit',
    name: '北采区',
    description: null,
    defaultScene: false,
    status: 'ACTIVE' as const,
    createdAt: '2026-07-20T08:00:00Z',
    updatedAt: '2026-07-20T08:00:00Z',
    archivedAt: null,
    version: 0
  };
}

function version() {
  return {
    id: crypto.randomUUID(),
    sceneId: crypto.randomUUID(),
    coordinateSystemId: crypto.randomUUID(),
    revisionNumber: 1,
    schemaVersion: 1,
    coordinateMode: 'LOCAL_ENU' as const,
    originX: 0,
    originY: 0,
    originZ: 0,
    headingDegrees: 0,
    pitchDegrees: 0,
    rollDegrees: 0,
    scale: 1,
    defaultCamera: {},
    fallbackViewport: {},
    status: 'DRAFT' as const,
    contentHash: null,
    publishedAt: null,
    supersededAt: null,
    createdAt: '2026-07-20T08:00:00Z',
    version: 0,
    layers: []
  };
}
