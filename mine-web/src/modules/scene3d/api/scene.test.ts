import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  SceneRuntimeContractError,
  archiveScene,
  archiveSceneAsset,
  cloneNextSceneDraft,
  createScene,
  createSceneDraft,
  getScene,
  getSceneAsset,
  getSceneRuntime,
  getSceneVersion,
  listSceneAssets,
  listSceneVersions,
  listScenes,
  makeDefaultScene,
  parseSceneRuntimeResponse,
  publishSceneVersion,
  replaceSceneLayers,
  updateScene,
  updateSceneDraft,
  uploadSceneAsset
} from './scene';

const client = vi.hoisted(() => ({ request: vi.fn() }));

vi.mock('@/api/client', () => client);

describe('scene api', () => {
  beforeEach(() => {
    client.request.mockReset().mockResolvedValue({});
  });

  it('使用编码后的矿区路径和有界分页参数读取资源、场景和版本', async () => {
    await listSceneAssets('site/1', { status: 'AVAILABLE', page: 2, size: 50 });
    expect(client.request).toHaveBeenLastCalledWith(
      '/mine/sites/site%2F1/scene-assets?status=AVAILABLE&page=2&size=50'
    );

    await getSceneAsset('site/1', 'asset/1', { filePage: 3, fileSize: 100 });
    expect(client.request).toHaveBeenLastCalledWith(
      '/mine/sites/site%2F1/scene-assets/asset%2F1?filePage=3&fileSize=100'
    );

    await listScenes('site/1', { keyword: 'north pit', status: 'ACTIVE', page: 1, size: 20 });
    expect(client.request).toHaveBeenLastCalledWith(
      '/mine/sites/site%2F1/scenes?keyword=north+pit&status=ACTIVE&page=1&size=20'
    );

    await listSceneVersions('site/1', 'scene/1', { page: 2, size: 30 });
    expect(client.request).toHaveBeenLastCalledWith('/mine/sites/site%2F1/scenes/scene%2F1/versions?page=2&size=30');
  });

  it('上传使用 FormData 且不手工设置 multipart Content-Type', async () => {
    const file = new File(['glb'], 'mine.glb', { type: 'model/gltf-binary' });

    await uploadSceneAsset('site/1', file);

    expect(client.request).toHaveBeenLastCalledWith(
      '/mine/sites/site%2F1/scene-assets',
      expect.objectContaining({ method: 'POST', body: expect.any(FormData) })
    );
    const init = client.request.mock.calls.at(-1)?.[1] as RequestInit;
    expect((init.body as FormData).get('file')).toBe(file);
    expect(init.headers).toBeUndefined();
  });

  it('治理动作传递 JSON、乐观版本和调用方提供的稳定幂等键', async () => {
    await archiveSceneAsset('site/1', 'asset/1', 4, 'archive-asset-key');
    expect(client.request).toHaveBeenLastCalledWith(
      '/mine/sites/site%2F1/scene-assets/asset%2F1/archive',
      mutation({ version: 4 }, 'archive-asset-key')
    );

    await archiveScene('site/1', 'scene/1', 5, 'archive-scene-key');
    expect(client.request).toHaveBeenLastCalledWith(
      '/mine/sites/site%2F1/scenes/scene%2F1/archive',
      mutation({ version: 5 }, 'archive-scene-key')
    );

    await makeDefaultScene('site/1', 'scene/1', 6, 'default-scene-key');
    expect(client.request).toHaveBeenLastCalledWith(
      '/mine/sites/site%2F1/scenes/scene%2F1/make-default',
      mutation({ version: 6 }, 'default-scene-key')
    );

    await publishSceneVersion('site/1', 'scene/1', 'version/1', 7, 'publish-version-key');
    expect(client.request).toHaveBeenLastCalledWith(
      '/mine/sites/site%2F1/scenes/scene%2F1/versions/version%2F1/publish',
      mutation({ version: 7 }, 'publish-version-key')
    );
  });

  it('封装场景和草稿完整生命周期路径', async () => {
    const sceneBody = { sceneCode: 'north-pit', name: 'North pit', version: 0 };
    const versionBody = { version: 3 } as never;
    const layers = [{ layerCode: 'grid' }] as never;

    await createScene('site/1', sceneBody);
    expect(client.request).toHaveBeenLastCalledWith(
      '/mine/sites/site%2F1/scenes',
      expect.objectContaining({ method: 'POST', body: JSON.stringify(sceneBody) })
    );
    await getScene('site/1', 'scene/1');
    expect(client.request).toHaveBeenLastCalledWith('/mine/sites/site%2F1/scenes/scene%2F1');
    await updateScene('site/1', 'scene/1', sceneBody);
    expect(client.request).toHaveBeenLastCalledWith(
      '/mine/sites/site%2F1/scenes/scene%2F1',
      expect.objectContaining({ method: 'PATCH' })
    );
    await createSceneDraft('site/1', 'scene/1', versionBody);
    expect(client.request).toHaveBeenLastCalledWith(
      '/mine/sites/site%2F1/scenes/scene%2F1/versions',
      expect.objectContaining({ method: 'POST' })
    );
    await cloneNextSceneDraft('site/1', 'scene/1');
    expect(client.request).toHaveBeenLastCalledWith('/mine/sites/site%2F1/scenes/scene%2F1/versions/next-draft', {
      method: 'POST'
    });
    await getSceneVersion('site/1', 'scene/1', 'version/1');
    expect(client.request).toHaveBeenLastCalledWith('/mine/sites/site%2F1/scenes/scene%2F1/versions/version%2F1');
    await updateSceneDraft('site/1', 'scene/1', 'version/1', versionBody);
    expect(client.request).toHaveBeenLastCalledWith(
      '/mine/sites/site%2F1/scenes/scene%2F1/versions/version%2F1',
      expect.objectContaining({ method: 'PATCH' })
    );
    await replaceSceneLayers('site/1', 'scene/1', 'version/1', 7, layers);
    expect(client.request).toHaveBeenLastCalledWith(
      '/mine/sites/site%2F1/scenes/scene%2F1/versions/version%2F1/layers?version=7',
      expect.objectContaining({ method: 'PUT', body: JSON.stringify(layers) })
    );
  });

  it('解析完整最后正确快照并拒绝跨矿区或半包响应', async () => {
    const response = readyRuntime();
    client.request.mockResolvedValue(response);

    await expect(getSceneRuntime(response.siteId)).resolves.toEqual(response);
    expect(client.request).toHaveBeenLastCalledWith(`/mine/sites/${response.siteId}/scene-runtime`);
    expect(parseSceneRuntimeResponse(response)).toEqual(response);
    expect(
      parseSceneRuntimeResponse({
        siteId: response.siteId,
        status: 'DEGRADED',
        reason: 'NO_DEFAULT_SCENE',
        snapshot: null
      })
    ).toMatchObject({ status: 'DEGRADED', snapshot: null });

    client.request.mockResolvedValue({ ...response, siteId: crypto.randomUUID() });
    await expect(getSceneRuntime(response.siteId)).rejects.toBeInstanceOf(SceneRuntimeContractError);

    expect(() =>
      parseSceneRuntimeResponse({
        ...response,
        snapshot: { ...response.snapshot!, layerCount: 2 }
      })
    ).toThrow(SceneRuntimeContractError);

    expect(() =>
      parseSceneRuntimeResponse({
        ...response,
        status: 'DEGRADED',
        reason: 'SCENE_STORAGE_UNAVAILABLE'
      })
    ).toThrow(SceneRuntimeContractError);

    const externalAsset = structuredClone(response) as unknown as {
      snapshot: { layers: Array<Record<string, unknown>> };
    };
    externalAsset.snapshot.layers[0] = {
      ...externalAsset.snapshot.layers[0],
      layerType: 'STATIC_GLB',
      asset: {
        assetId: crypto.randomUUID(),
        assetType: 'GLB',
        contentDigest: `sha256:${'b'.repeat(64)}`,
        fileCount: 1,
        entryUrl: 'https://assets.example/mine.glb'
      }
    };
    expect(() => parseSceneRuntimeResponse(externalAsset)).toThrow(SceneRuntimeContractError);
  });
});

function mutation(body: object, idempotencyKey: string) {
  return {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body)
  };
}

function readyRuntime() {
  const siteId = crypto.randomUUID();
  return {
    siteId,
    status: 'READY' as const,
    reason: 'NONE' as const,
    snapshot: {
      sceneId: crypto.randomUUID(),
      sceneCode: 'north-pit',
      sceneName: 'North pit',
      versionId: crypto.randomUUID(),
      revisionNumber: 1,
      schemaVersion: 1,
      contentHash: `sha256:${'a'.repeat(64)}`,
      coordinateSystemId: crypto.randomUUID(),
      coordinateMode: 'LOCAL_ENU' as const,
      originX: 0,
      originY: 0,
      originZ: 0,
      headingDegrees: 0,
      pitchDegrees: 0,
      rollDegrees: 0,
      scale: 1,
      defaultCamera: { x: 10, y: 20, z: 30 },
      fallbackViewport: { minX: 0, maxX: 100 },
      publishedAt: '2026-07-20T08:00:00Z',
      layerCount: 1,
      layers: [
        {
          id: crypto.randomUUID(),
          layerCode: 'grid',
          orderNumber: 1,
          layerType: 'GRID' as const,
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
          options: {},
          asset: null
        }
      ]
    }
  };
}
