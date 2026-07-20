import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import type { SceneRuntimeSnapshot } from '../api/scene';
import CesiumSceneViewport from './CesiumSceneViewport.vue';

const adapterMock = vi.hoisted(() => ({ constructor: vi.fn() }));

vi.mock('../runtime/CesiumSceneAdapter', () => ({
  CesiumSceneAdapter: adapterMock.constructor
}));

describe('CesiumSceneViewport', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    adapterMock.constructor.mockReset().mockImplementation(function AdapterMock() {
      return { mount: vi.fn(), destroy: vi.fn() };
    });
  });

  it('只负责连接生产适配器并展示去重后的图层降级状态', async () => {
    const wrapper = mount(CesiumSceneViewport, {
      props: { snapshot: snapshot() },
      global: {
        plugins: [i18n],
        stubs: {
          SceneViewport: {
            name: 'SceneViewport',
            props: ['snapshot', 'createAdapter'],
            template: '<div data-testid="scene-viewport-host"></div>'
          }
        }
      }
    });
    const host = wrapper.findComponent({ name: 'SceneViewport' });
    expect(wrapper.get('[data-testid="scene-coordinate-mode"]').text()).toContain('局部 ENU');
    const runtimeFailure = vi.fn();
    const createAdapter = host.props('createAdapter') as (
      runtime: object,
      snapshot: SceneRuntimeSnapshot,
      onRuntimeFailure: (error: unknown) => void
    ) => unknown;
    createAdapter({}, snapshot(), runtimeFailure);
    const options = adapterMock.constructor.mock.calls[0]![1] as {
      onLayerFailure: (failure: { layerCode: string; layerType: 'STATIC_GLB'; message: string }) => void;
      onRuntimeFailure: (error: unknown) => void;
    };

    options.onLayerFailure({ layerCode: 'pit-model', layerType: 'STATIC_GLB', message: 'internal failure' });
    options.onLayerFailure({ layerCode: 'pit-model', layerType: 'STATIC_GLB', message: 'duplicate' });
    await flushPromises();

    expect(wrapper.get('[data-testid="scene-layer-degraded"]').text()).toContain('pit-model');
    expect(wrapper.text()).not.toContain('internal failure');
    expect(wrapper.text().match(/pit-model/g)).toHaveLength(1);
    options.onRuntimeFailure(new Error('render failed'));
    expect(runtimeFailure).toHaveBeenCalledOnce();
  });
});

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
