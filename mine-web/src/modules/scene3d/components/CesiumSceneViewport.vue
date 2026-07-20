<script setup lang="ts">
import { ref, watch } from 'vue';
import { NAlert, NTag } from 'naive-ui';
import { useI18n } from 'vue-i18n';
import type { LocationTrajectory, MineLocationItem, MineSpatialEvent, MineSpatialRegion } from '@/modules/mine';
import type { SceneRuntimeSnapshot } from '../api/scene';
import { CesiumSceneAdapter } from '../runtime/CesiumSceneAdapter';
import type { SceneLayerFailure } from '../runtime/CesiumSceneAdapter';
import type {
  SceneViewportAdapterFactory,
  SceneViewportFallbackReason,
  SceneViewportMode,
  WebGlCapabilityProbe
} from '../runtime/types';
import SceneViewport from './SceneViewport.vue';

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
    supportsWebGl: undefined
  }
);

const { t } = useI18n();
const failedLayerCodes = ref<string[]>([]);

watch(
  () => props.snapshot?.versionId,
  () => {
    failedLayerCodes.value = [];
  }
);

const createAdapter: SceneViewportAdapterFactory = (runtime, _snapshot, onRuntimeFailure) => {
  failedLayerCodes.value = [];
  return new CesiumSceneAdapter(runtime, { onLayerFailure: recordLayerFailure, onRuntimeFailure });
};

function recordLayerFailure(failure: SceneLayerFailure) {
  if (failedLayerCodes.value.includes(failure.layerCode)) return;
  failedLayerCodes.value = [...failedLayerCodes.value, failure.layerCode];
}
</script>

<template>
  <NTag v-if="snapshot" type="info" :bordered="false" class="mb-3" data-testid="scene-coordinate-mode">
    {{
      snapshot.coordinateMode === 'LOCAL_ENU'
        ? t('mine.sceneWorkbench.coordinateMode.localEnu')
        : t('mine.sceneWorkbench.coordinateMode.wgs84Anchor')
    }}
  </NTag>
  <NAlert
    v-if="failedLayerCodes.length"
    type="warning"
    :title="t('mine.scene3d.layerDegradedTitle')"
    class="mb-3"
    data-testid="scene-layer-degraded"
  >
    {{ t('mine.scene3d.layerDegraded', { layers: failedLayerCodes.join(', ') }) }}
  </NAlert>
  <SceneViewport
    :mode="mode"
    :snapshot="snapshot"
    :degradation-reason="degradationReason"
    :locations="locations"
    :trajectory="trajectory"
    :regions="regions"
    :events="events"
    :selected-tag-id="selectedTagId"
    :loading="loading"
    :supports-web-gl="supportsWebGl"
    :create-adapter="createAdapter"
  />
</template>
