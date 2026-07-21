<script setup lang="ts">
import { computed } from 'vue';
import { NAlert, NButton, NSpace } from 'naive-ui';
import { useI18n } from 'vue-i18n';
import { MineCoordinateCanvas } from '@/modules/mine';
import type { LocationTrajectory, MineLocationItem, MineSpatialEvent, MineSpatialRegion } from '@/modules/mine';
import type { SceneViewportFallbackReason } from '../runtime/types';
import type { SceneAlarmMarker, SceneOperationMarker } from '../composables/useSceneBusinessLayers';

const props = withDefaults(
  defineProps<{
    degradationReason: SceneViewportFallbackReason;
    retryable?: boolean;
    locations?: MineLocationItem[];
    trajectory?: LocationTrajectory['items'];
    regions?: MineSpatialRegion[];
    events?: MineSpatialEvent[];
    alarms?: SceneAlarmMarker[];
    operations?: SceneOperationMarker[];
    selectedTagId?: string | null;
    locationStaleAfterSeconds?: number;
    loading?: boolean;
  }>(),
  {
    retryable: false,
    locations: () => [],
    trajectory: () => [],
    regions: () => [],
    events: () => [],
    alarms: () => [],
    operations: () => [],
    selectedTagId: null,
    locationStaleAfterSeconds: 120,
    loading: false
  }
);

const emit = defineEmits<{ retry: [] }>();
const { t } = useI18n();

const reasonKeyByCode: Record<SceneViewportFallbackReason, App.I18n.I18nKey> = {
  NO_DEFAULT_SCENE: 'mine.scene3d.reason.noDefaultScene',
  DEFAULT_SCENE_UNPUBLISHED: 'mine.scene3d.reason.defaultSceneUnpublished',
  UNSUPPORTED_SCHEMA_VERSION: 'mine.scene3d.reason.unsupportedSchemaVersion',
  INVALID_PUBLISHED_SNAPSHOT: 'mine.scene3d.reason.invalidPublishedSnapshot',
  SCENE_STORAGE_UNAVAILABLE: 'mine.scene3d.reason.sceneStorageUnavailable',
  FORCED_2D: 'mine.scene3d.reason.forced2d',
  NO_RUNTIME_SNAPSHOT: 'mine.scene3d.reason.noRuntimeSnapshot',
  WEBGL_UNAVAILABLE: 'mine.scene3d.reason.webglUnavailable',
  THREE_D_LOAD_FAILED: 'mine.scene3d.reason.threeDLoadFailed',
  THREE_D_RUNTIME_FAILED: 'mine.scene3d.reason.threeDRuntimeFailed'
};

const alertType = computed(() => (props.degradationReason === 'FORCED_2D' ? 'info' : 'warning'));
</script>

<template>
  <div class="flex flex-col gap-3" data-testid="canvas-viewport" :data-reason="degradationReason">
    <NAlert :type="alertType" :title="t('mine.scene3d.fallbackTitle')">
      <NSpace vertical :size="8">
        <span data-testid="scene-fallback-reason">{{ t(reasonKeyByCode[degradationReason]) }}</span>
        <NButton
          v-if="retryable"
          data-testid="scene-retry"
          secondary
          size="small"
          :aria-label="t('mine.scene3d.retry')"
          @click="emit('retry')"
        >
          {{ t('mine.scene3d.retry') }}
        </NButton>
      </NSpace>
    </NAlert>
    <MineCoordinateCanvas
      :items="locations"
      :trajectory="trajectory"
      :regions="regions"
      :events="events"
      :alarms="alarms"
      :operations="operations"
      :selected-tag-id="selectedTagId"
      :stale-after-seconds="locationStaleAfterSeconds"
      :loading="loading"
    />
  </div>
</template>
