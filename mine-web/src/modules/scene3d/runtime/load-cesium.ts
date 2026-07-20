export type CesiumModule = typeof import('cesium');

const CESIUM_OFFLINE_BASE_URL = resolveCesiumBaseUrl(import.meta.env.BASE_URL);

let loadingTask: Promise<CesiumModule> | undefined;

export function loadCesium(): Promise<CesiumModule> {
  globalThis.CESIUM_BASE_URL = CESIUM_OFFLINE_BASE_URL;
  loadingTask ??= import('cesium').catch(error => {
    loadingTask = undefined;
    throw error;
  });
  return loadingTask;
}

/** 将 Cesium 离线资源目录绑定到 Vite 应用部署基路径。 */
export function resolveCesiumBaseUrl(baseUrl: string): string {
  const normalizedBaseUrl = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`;
  return `${normalizedBaseUrl}cesium/`;
}
