import type { SceneRuntimeReason, SceneRuntimeSnapshot } from '../api/scene';
import type { CesiumModule } from './load-cesium';

export type SceneViewportMode = 'AUTO' | '3D' | '2D';
export type SceneViewportRenderMode = '3D' | '2D';
export type SceneViewportFallbackReason =
  | Exclude<SceneRuntimeReason, 'NONE'>
  | 'FORCED_2D'
  | 'NO_RUNTIME_SNAPSHOT'
  | 'WEBGL_UNAVAILABLE'
  | 'THREE_D_LOAD_FAILED'
  | 'THREE_D_RUNTIME_FAILED';

export interface SceneViewportAdapter {
  mount(container: HTMLElement, snapshot: SceneRuntimeSnapshot): void | Promise<void>;
  destroy(): void;
}

export type SceneViewportAdapterFactory = (
  runtime: CesiumModule,
  snapshot: SceneRuntimeSnapshot,
  onRuntimeFailure: (error: unknown) => void
) => SceneViewportAdapter | Promise<SceneViewportAdapter>;

export type WebGlCapabilityProbe = () => boolean;

/** Task 11 接入真实渲染器前显式失败，禁止把空白容器报告为成功三维。 */
export function createLifecycleAdapter(): SceneViewportAdapter {
  return {
    mount: () => {
      throw new Error('Production scene viewport adapter is unavailable');
    },
    destroy: () => undefined
  };
}

/** 使用能力检测而不是 User-Agent 判断浏览器是否具备 WebGL。 */
export function detectWebGlAvailability(): boolean {
  if (typeof document === 'undefined') return false;
  try {
    const canvas = document.createElement('canvas');
    return Boolean(canvas.getContext('webgl2') || canvas.getContext('webgl'));
  } catch {
    return false;
  }
}
