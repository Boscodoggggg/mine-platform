type CesiumModule = typeof import('cesium');

const CESIUM_OFFLINE_BASE_URL = '/cesium/';

let loadingTask: Promise<CesiumModule> | undefined;

export function loadCesium(): Promise<CesiumModule> {
  globalThis.CESIUM_BASE_URL = CESIUM_OFFLINE_BASE_URL;
  loadingTask ??= import('cesium').catch(error => {
    loadingTask = undefined;
    throw error;
  });
  return loadingTask;
}
