<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { NSpin } from 'naive-ui';
import { useI18n } from 'vue-i18n';
import type { LocationTrajectory, MineLocationItem, MineSpatialEvent, MineSpatialRegion } from '@/modules/mine';
import type { SceneRuntimeSnapshot } from '../api/scene';
import { loadCesium } from '../runtime/load-cesium';
import { createLifecycleAdapter, detectWebGlAvailability } from '../runtime/types';
import type {
  SceneViewportAdapter,
  SceneViewportAdapterFactory,
  SceneViewportFallbackReason,
  SceneViewportMode,
  SceneViewportRenderMode,
  WebGlCapabilityProbe
} from '../runtime/types';
import CanvasSceneViewport from './CanvasSceneViewport.vue';

const props = withDefaults(
  defineProps<{
    mode?: SceneViewportMode;
    snapshot: SceneRuntimeSnapshot | null;
    degradationReason?: SceneViewportFallbackReason | null;
    locations?: MineLocationItem[];
    trajectory?: LocationTrajectory['items'];
    regions?: MineSpatialRegion[];
    events?: MineSpatialEvent[];
    selectedTagId?: string | null;
    loading?: boolean;
    supportsWebGl?: WebGlCapabilityProbe;
    createAdapter?: SceneViewportAdapterFactory;
  }>(),
  {
    mode: 'AUTO',
    degradationReason: null,
    locations: () => [],
    trajectory: () => [],
    regions: () => [],
    events: () => [],
    selectedTagId: null,
    loading: false,
    supportsWebGl: detectWebGlAvailability,
    createAdapter: createLifecycleAdapter
  }
);

const { t } = useI18n();
const containerRef = ref<HTMLElement | null>(null);
const renderMode = ref<SceneViewportRenderMode>('2D');
const fallbackReason = ref<SceneViewportFallbackReason>('NO_RUNTIME_SNAPSHOT');
const initializing = ref(false);
let generation = 0;
let activeAdapter: SceneViewportAdapter | null = null;

const retryable = computed(
  () =>
    props.mode !== '2D' &&
    props.snapshot !== null &&
    ['WEBGL_UNAVAILABLE', 'THREE_D_LOAD_FAILED', 'THREE_D_RUNTIME_FAILED'].includes(fallbackReason.value)
);

watch(
  [
    () => props.mode,
    () => props.snapshot,
    () => props.degradationReason,
    () => props.supportsWebGl,
    () => props.createAdapter
  ],
  () => {
    void activate();
  },
  { immediate: true, flush: 'post' }
);

onBeforeUnmount(() => {
  generation += 1;
  releaseAdapter();
});

async function activate() {
  const currentGeneration = ++generation;
  releaseAdapter();
  initializing.value = false;

  if (props.mode === '2D') {
    useCanvas(props.degradationReason ?? 'FORCED_2D');
    return;
  }
  const snapshot = props.snapshot;
  if (!snapshot) {
    useCanvas(props.degradationReason ?? 'NO_RUNTIME_SNAPSHOT');
    return;
  }
  if (!props.supportsWebGl()) {
    useCanvas('WEBGL_UNAVAILABLE');
    return;
  }

  renderMode.value = '3D';
  initializing.value = true;
  await nextTick();

  let stage: 'LOAD' | 'RUNTIME' = 'LOAD';
  let candidate: SceneViewportAdapter | null = null;
  try {
    const runtime = await loadCesium();
    if (currentGeneration !== generation) return;
    stage = 'RUNTIME';
    candidate = await props.createAdapter(runtime, snapshot);
    if (currentGeneration !== generation) {
      candidate.destroy();
      return;
    }
    activeAdapter = candidate;
    candidate = null;
    const container = containerRef.value;
    if (!container) throw new Error('Scene viewport container is unavailable');
    await activeAdapter.mount(container, snapshot);
    if (currentGeneration !== generation) releaseAdapter();
  } catch {
    if (currentGeneration !== generation) {
      candidate?.destroy();
      return;
    }
    releaseAdapter();
    useCanvas(stage === 'LOAD' ? 'THREE_D_LOAD_FAILED' : 'THREE_D_RUNTIME_FAILED');
  } finally {
    if (currentGeneration === generation) initializing.value = false;
  }
}

function useCanvas(reason: SceneViewportFallbackReason) {
  renderMode.value = '2D';
  fallbackReason.value = reason;
}

function releaseAdapter() {
  activeAdapter?.destroy();
  activeAdapter = null;
}
</script>

<template>
  <NSpin v-if="renderMode === '3D'" :show="initializing" :description="t('mine.scene3d.loading')">
    <div
      ref="containerRef"
      class="h-96 min-h-96 w-full overflow-hidden md:h-140"
      data-testid="scene-3d-viewport"
      role="application"
      :aria-label="t('mine.scene3d.ariaLabel')"
    ></div>
  </NSpin>
  <CanvasSceneViewport
    v-else
    :degradation-reason="fallbackReason"
    :retryable="retryable"
    :locations="locations"
    :trajectory="trajectory"
    :regions="regions"
    :events="events"
    :selected-tag-id="selectedTagId"
    :loading="loading"
    @retry="activate"
  />
</template>
