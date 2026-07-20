import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import type { SceneRuntimeSnapshot } from '../api/scene';
import type { SceneViewportAdapter } from '../runtime/types';
import CanvasSceneViewport from './CanvasSceneViewport.vue';
import SceneViewport from './SceneViewport.vue';

const cesiumLoader = vi.hoisted(() => ({ load: vi.fn() }));

vi.mock('../runtime/load-cesium', () => ({ loadCesium: cesiumLoader.load }));

const canvasStub = {
  props: ['degradationReason', 'retryable'],
  emits: ['retry'],
  template:
    '<div data-testid="canvas-viewport" :data-reason="degradationReason"><button v-if="retryable" data-testid="scene-retry" @click="$emit(\'retry\')">retry</button></div>'
};

describe('SceneViewport', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    cesiumLoader.load.mockReset().mockResolvedValue({});
  });

  it('AUTO 模式在 WebGL 不可用时直接进入二维且不加载 Cesium', async () => {
    const adapterFactory = vi.fn();
    const wrapper = mountViewport({
      mode: 'AUTO',
      supportsWebGl: () => false,
      createAdapter: adapterFactory
    });
    await flushPromises();

    expect(wrapper.get('[data-testid="canvas-viewport"]').attributes('data-reason')).toBe('WEBGL_UNAVAILABLE');
    expect(cesiumLoader.load).not.toHaveBeenCalled();
    expect(adapterFactory).not.toHaveBeenCalled();
  });

  it('显式二维模式完全不触达 Cesium 加载边界', async () => {
    const wrapper = mountViewport({ mode: '2D', supportsWebGl: () => true });
    await flushPromises();

    expect(wrapper.get('[data-testid="canvas-viewport"]').attributes('data-reason')).toBe('FORCED_2D');
    expect(cesiumLoader.load).not.toHaveBeenCalled();
  });

  it('Cesium 动态加载失败时降级二维，重试成功后切回三维', async () => {
    const adapter = mockAdapter();
    const createAdapterFactory = vi.fn().mockReturnValue(adapter);
    cesiumLoader.load.mockRejectedValueOnce(new Error('chunk failed')).mockResolvedValueOnce({});
    const wrapper = mountViewport({
      mode: '3D',
      supportsWebGl: () => true,
      createAdapter: createAdapterFactory
    });
    await flushPromises();

    expect(wrapper.get('[data-testid="canvas-viewport"]').attributes('data-reason')).toBe('THREE_D_LOAD_FAILED');
    await wrapper.get('[data-testid="scene-retry"]').trigger('click');
    await flushPromises();

    expect(wrapper.get('[data-testid="scene-3d-viewport"]').attributes('role')).toBe('application');
    expect(cesiumLoader.load).toHaveBeenCalledTimes(2);
    expect(createAdapterFactory).toHaveBeenCalledOnce();
    expect(adapter.mount).toHaveBeenCalledOnce();
  });

  it('卸载或切换到二维时释放三维适配器', async () => {
    const first = mockAdapter();
    const wrapper = mountViewport({
      mode: '3D',
      supportsWebGl: () => true,
      createAdapter: () => first
    });
    await flushPromises();

    await wrapper.setProps({ mode: '2D' });
    await flushPromises();
    expect(first.destroy).toHaveBeenCalledOnce();

    const second = mockAdapter();
    await wrapper.setProps({ mode: '3D', createAdapter: () => second });
    await flushPromises();
    wrapper.unmount();
    expect(second.destroy).toHaveBeenCalledOnce();
  });

  it('旧挂载晚于新挂载完成时只释放旧适配器', async () => {
    const firstMount = deferred<void>();
    const first = mockAdapter();
    first.mount.mockReturnValue(firstMount.promise);
    const second = mockAdapter();
    const wrapper = mountViewport({
      mode: '3D',
      supportsWebGl: () => true,
      createAdapter: () => first
    });
    await flushPromises();

    await wrapper.setProps({ createAdapter: () => second });
    await flushPromises();
    expect(first.destroy).toHaveBeenCalledOnce();
    expect(second.mount).toHaveBeenCalledOnce();

    firstMount.resolve();
    await flushPromises();
    expect(second.destroy).not.toHaveBeenCalled();
    expect(wrapper.find('[data-testid="scene-3d-viewport"]').exists()).toBe(true);
  });

  it('旧挂载晚失败时不覆盖新一代三维视口', async () => {
    const firstMount = deferred<void>();
    const first = mockAdapter();
    first.mount.mockReturnValue(firstMount.promise);
    const second = mockAdapter();
    const wrapper = mountViewport({
      mode: '3D',
      supportsWebGl: () => true,
      createAdapter: () => first
    });
    await flushPromises();

    await wrapper.setProps({ createAdapter: () => second });
    await flushPromises();
    firstMount.reject(new Error('stale mount failed'));
    await flushPromises();

    expect(second.destroy).not.toHaveBeenCalled();
    expect(wrapper.find('[data-testid="scene-3d-viewport"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="canvas-viewport"]').exists()).toBe(false);
  });

  it('挂载中卸载只释放一次适配器', async () => {
    const mountTask = deferred<void>();
    const adapter = mockAdapter();
    adapter.mount.mockReturnValue(mountTask.promise);
    const wrapper = mountViewport({
      mode: '3D',
      supportsWebGl: () => true,
      createAdapter: () => adapter
    });
    await flushPromises();

    wrapper.unmount();
    mountTask.resolve();
    await flushPromises();

    expect(adapter.destroy).toHaveBeenCalledOnce();
  });

  it('未提供生产适配器时明确降级而不是把空白容器当作成功三维', async () => {
    const wrapper = mountViewport({ mode: '3D', supportsWebGl: () => true });
    await flushPromises();

    expect(wrapper.get('[data-testid="canvas-viewport"]').attributes('data-reason')).toBe('THREE_D_RUNTIME_FAILED');
  });
});

describe('CanvasSceneViewport', () => {
  it('展示具体降级原因、透传二维数据并提供可访问重试操作', async () => {
    const wrapper = mount(CanvasSceneViewport, {
      props: {
        degradationReason: 'SCENE_STORAGE_UNAVAILABLE',
        retryable: true,
        locations: [],
        trajectory: [],
        regions: [],
        events: [],
        selectedTagId: null,
        loading: false
      },
      global: {
        plugins: [i18n],
        stubs: {
          MineCoordinateCanvas: {
            props: ['items', 'trajectory', 'regions', 'events', 'selectedTagId', 'loading'],
            template: '<div data-testid="mine-coordinate-canvas"></div>'
          }
        }
      }
    });

    expect(wrapper.text()).toContain('场景资源存储暂不可读取');
    expect(wrapper.get('[data-testid="mine-coordinate-canvas"]').element).toBeInstanceOf(Element);
    const retry = wrapper.get('[data-testid="scene-retry"]');
    expect(retry.text()).toContain('重试三维视图');
    await retry.trigger('click');
    expect(wrapper.emitted('retry')).toHaveLength(1);
  });
});

function mountViewport(overrides: Record<string, unknown>) {
  return mount(SceneViewport, {
    props: {
      snapshot: snapshot(),
      mode: 'AUTO',
      degradationReason: null,
      locations: [],
      trajectory: [],
      regions: [],
      events: [],
      selectedTagId: null,
      loading: false,
      ...overrides
    },
    global: {
      plugins: [i18n],
      stubs: { CanvasSceneViewport: canvasStub }
    }
  });
}

function mockAdapter() {
  const mountAdapter = vi.fn<SceneViewportAdapter['mount']>();
  const destroy = vi.fn<SceneViewportAdapter['destroy']>();
  return { mount: mountAdapter, destroy };
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function snapshot(): SceneRuntimeSnapshot {
  return {
    sceneId: crypto.randomUUID(),
    sceneCode: 'north-pit',
    sceneName: 'North pit',
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
    layerCount: 0,
    layers: []
  };
}
