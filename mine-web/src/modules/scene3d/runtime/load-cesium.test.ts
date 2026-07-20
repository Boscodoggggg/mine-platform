import { beforeEach, describe, expect, it, vi } from 'vitest';

const cesiumMock = vi.hoisted(() => ({
  evaluations: 0,
  defaultAccessToken: 'cesium-library-default'
}));

vi.mock('cesium', () => {
  cesiumMock.evaluations += 1;
  return {
    Ion: {
      defaultAccessToken: cesiumMock.defaultAccessToken
    }
  };
});

describe('loadCesium', () => {
  beforeEach(() => {
    vi.resetModules();
    cesiumMock.evaluations = 0;
    globalThis.CESIUM_BASE_URL = undefined;
  });

  it('模块导入时不加载 Cesium，调用时才设置同源离线资源根并动态加载', async () => {
    const loader = await import('./load-cesium');

    expect(cesiumMock.evaluations).toBe(0);
    expect(globalThis.CESIUM_BASE_URL).toBeUndefined();

    const first = loader.loadCesium();
    const second = loader.loadCesium();
    const cesium = await first;

    expect(first).toBe(second);
    expect(cesiumMock.evaluations).toBe(1);
    expect(globalThis.CESIUM_BASE_URL).toBe('/cesium/');
    expect(cesium.Ion.defaultAccessToken).toBe('cesium-library-default');
    expect(cesiumMock.defaultAccessToken).toBe('cesium-library-default');
  });
});
