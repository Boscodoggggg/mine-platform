import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it } from 'vitest';
import { i18n, setLocale } from '@/locales';
import type { SceneAsset, SceneVersion } from '../api/scene';
import SceneVersionEditor from './SceneVersionEditor.vue';

describe('SceneVersionEditor', () => {
  beforeEach(() => setLocale('zh-CN'));

  it('草稿图层可排序且静态图层只提供类型匹配的可用资源', async () => {
    const wrapper = mountEditor(draftVersion());
    const editor = wrapper.vm as unknown as {
      addLayer: (type: string) => void;
      availableAssets: (index: number) => SceneAsset[];
      selectLayerAsset: (index: number, assetId: string) => void;
    };

    editor.addLayer('STATIC_GLB');
    await flushPromises();

    expect(wrapper.findAll('[data-testid="scene-layer-row"]')).toHaveLength(2);
    const availableAssets = editor.availableAssets(1);
    expect(availableAssets.map(item => item.originalFilename)).toEqual(['haul-truck.glb']);
    editor.selectLayerAsset(1, availableAssets[0]!.id);
    expect(wrapper.emitted('dirtyChange')?.at(-1)).toEqual([true]);

    await wrapper.get('[data-testid="move-layer-up-1"]').trigger('click');
    await wrapper.get('[data-testid="save-scene-draft"]').trigger('click');
    const layers = wrapper.emitted('save')?.[0]?.[1] as Array<{ orderNumber: number; layerType: string }>;
    expect(layers.map(layer => layer.orderNumber)).toEqual([1, 2]);
    expect(layers[0]?.layerType).toBe('STATIC_GLB');
  });

  it('发布版本和无编辑权限用户保持只读，草稿发布动作按权限显示', async () => {
    const readonly = mountEditor({ ...draftVersion(), status: 'PUBLISHED' }, { canEdit: true, canPublish: true });
    expect(readonly.find('[data-testid="save-scene-draft"]').exists()).toBe(false);
    expect(readonly.find('[data-testid="publish-scene-version"]').exists()).toBe(false);

    const viewer = mountEditor(draftVersion(), { canEdit: false, canPublish: false });
    expect(viewer.find('[data-testid="save-scene-draft"]').exists()).toBe(false);

    const publisher = mountEditor(draftVersion(), { canEdit: true, canPublish: true });
    await publisher.get('[data-testid="publish-scene-version"]').trigger('click');
    expect(publisher.emitted('publish')).toHaveLength(1);
  });

  it('无历史版本时允许编辑并提交首个草稿', async () => {
    const coordinateSystemId = crypto.randomUUID();
    const wrapper = mount(SceneVersionEditor, {
      props: {
        version: null,
        assets: [],
        coordinateSystems: [{ label: '统一坐标 · LOCAL-ENU', value: coordinateSystemId }],
        canEdit: true,
        canPublish: false,
        saving: false,
        publishing: false
      },
      global: { plugins: [i18n] }
    });

    expect(wrapper.find('[data-testid="save-scene-draft"]').exists()).toBe(true);
    await wrapper.get('[data-testid="save-scene-draft"]').trigger('click');
    expect(wrapper.emitted('save')?.[0]?.[0]).toMatchObject({ coordinateSystemId, version: 0 });
  });
});

function mountEditor(version: SceneVersion, overrides: Record<string, unknown> = {}) {
  return mount(SceneVersionEditor, {
    props: {
      version,
      assets: sceneAssets(),
      coordinateSystems: [{ label: '统一坐标 · LOCAL-ENU', value: version.coordinateSystemId }],
      canEdit: true,
      canPublish: true,
      saving: false,
      publishing: false,
      ...overrides
    },
    global: { plugins: [i18n] }
  });
}

function draftVersion(): SceneVersion {
  return {
    id: crypto.randomUUID(),
    sceneId: crypto.randomUUID(),
    coordinateSystemId: crypto.randomUUID(),
    revisionNumber: 1,
    schemaVersion: 1,
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
    status: 'DRAFT',
    contentHash: null,
    publishedAt: null,
    supersededAt: null,
    createdAt: '2026-07-20T08:00:00Z',
    version: 0,
    layers: [
      {
        id: crypto.randomUUID(),
        layerCode: 'grid',
        orderNumber: 1,
        layerType: 'GRID',
        assetId: null,
        visible: true,
        opacity: 1,
        minimumDistance: 0,
        maximumDistance: 10000,
        offsetX: 0,
        offsetY: 0,
        offsetZ: 0,
        headingDegrees: 0,
        pitchDegrees: 0,
        rollDegrees: 0,
        scale: 1,
        maxItems: 0,
        options: {}
      }
    ]
  };
}

function sceneAssets(): SceneAsset[] {
  return [
    asset('haul-truck.glb', 'GLB', 'AVAILABLE'),
    asset('pit-tiles.zip', 'TILES_3D', 'AVAILABLE'),
    asset('old-truck.glb', 'GLB', 'ARCHIVED')
  ];
}

function asset(originalFilename: string, assetType: SceneAsset['assetType'], status: SceneAsset['status']): SceneAsset {
  return {
    id: crypto.randomUUID(),
    organizationId: crypto.randomUUID(),
    siteId: crypto.randomUUID(),
    assetType,
    originalFilename,
    contentType: assetType === 'GLB' ? 'model/gltf-binary' : 'application/zip',
    contentDigest: `sha256:${'a'.repeat(64)}`,
    sourceSizeBytes: 1024,
    expandedSizeBytes: 1024,
    fileCount: 1,
    entryPath: assetType === 'GLB' ? 'model.glb' : 'tileset.json',
    status,
    createdAt: '2026-07-20T08:00:00Z',
    createdBy: crypto.randomUUID(),
    archivedAt: status === 'ARCHIVED' ? '2026-07-20T09:00:00Z' : null,
    version: 0,
    files: null
  };
}
