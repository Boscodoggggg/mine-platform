<script lang="ts">
const TRANSFORM_FIELDS = [
  'originX',
  'originY',
  'originZ',
  'headingDegrees',
  'pitchDegrees',
  'rollDegrees',
  'scale'
] as const;

const LAYER_NUMBER_FIELDS = [
  'opacity',
  'minimumDistance',
  'maximumDistance',
  'offsetX',
  'offsetY',
  'offsetZ',
  'headingDegrees',
  'pitchDegrees',
  'rollDegrees',
  'scale',
  'maxItems'
] as const;
</script>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { SelectOption } from 'naive-ui';
import {
  NAlert,
  NButton,
  NCard,
  NCheckbox,
  NDivider,
  NEmpty,
  NFlex,
  NForm,
  NFormItemGi,
  NGrid,
  NInput,
  NInputNumber,
  NSelect,
  NTag,
  NText
} from 'naive-ui';
import type { SceneAsset, SceneLayerRequest, SceneVersion, SceneVersionRequest } from '../api/scene';

type CoordinateOption = SelectOption;

interface LayerDraft extends SceneLayerRequest {
  clientKey: string;
  optionsText: string;
}

interface VersionDraft {
  coordinateSystemId: string;
  coordinateMode: SceneVersionRequest['coordinateMode'];
  originX: number;
  originY: number;
  originZ: number;
  headingDegrees: number;
  pitchDegrees: number;
  rollDegrees: number;
  scale: number;
  defaultCameraText: string;
  fallbackViewportText: string;
  version: number;
}

const props = defineProps<{
  version: SceneVersion | null;
  assets: SceneAsset[];
  coordinateSystems: CoordinateOption[];
  canEdit: boolean;
  canPublish: boolean;
  saving: boolean;
  publishing: boolean;
}>();
const emit = defineEmits<{
  save: [body: SceneVersionRequest, layers: SceneLayerRequest[]];
  publish: [];
  dirtyChange: [dirty: boolean];
}>();

const { t } = useI18n();
const form = reactive<VersionDraft>(emptyVersionDraft());
const layers = ref<LayerDraft[]>([]);
const dirty = ref(false);
const readonly = computed(() => !props.canEdit || (props.version !== null && props.version.status !== 'DRAFT'));
const coordinateModeOptions = computed<SelectOption[]>(() => [
  { label: t('mine.sceneWorkbench.coordinateMode.localEnu'), value: 'LOCAL_ENU' },
  { label: t('mine.sceneWorkbench.coordinateMode.wgs84Anchor'), value: 'WGS84_ANCHOR' }
]);
const layerTypeOptions = computed<SelectOption[]>(() =>
  LAYER_TYPES.map(value => ({ label: layerTypeLabel(value), value }))
);

watch(
  () => props.version,
  value => hydrate(value),
  { immediate: true }
);

function hydrate(version: SceneVersion | null) {
  Object.assign(
    form,
    version
      ? {
          coordinateSystemId: version.coordinateSystemId,
          coordinateMode: version.coordinateMode,
          originX: version.originX,
          originY: version.originY,
          originZ: version.originZ,
          headingDegrees: version.headingDegrees,
          pitchDegrees: version.pitchDegrees,
          rollDegrees: version.rollDegrees,
          scale: version.scale,
          defaultCameraText: JSON.stringify(version.defaultCamera, null, 2),
          fallbackViewportText: JSON.stringify(version.fallbackViewport, null, 2),
          version: version.version
        }
      : emptyVersionDraft()
  );
  layers.value = (version?.layers ?? []).map(layer => ({
    ...layer,
    clientKey: layer.id,
    optionsText: JSON.stringify(layer.options, null, 2)
  }));
  setDirty(false);
}

function addLayer(type: SceneLayerRequest['layerType'] = 'GRID') {
  if (readonly.value) return;
  layers.value.push({
    clientKey: crypto.randomUUID(),
    layerCode: uniqueLayerCode(type),
    orderNumber: layers.value.length + 1,
    layerType: type,
    assetId: null,
    visible: true,
    opacity: 1,
    minimumDistance: 0,
    maximumDistance: 100000,
    offsetX: 0,
    offsetY: 0,
    offsetZ: 0,
    headingDegrees: 0,
    pitchDegrees: 0,
    rollDegrees: 0,
    scale: 1,
    maxItems: type.startsWith('DYNAMIC_') ? 5000 : 0,
    options: {},
    optionsText: '{}'
  });
  setDirty(true);
}

function removeLayer(index: number) {
  if (readonly.value) return;
  layers.value.splice(index, 1);
  normalizeOrder();
  setDirty(true);
}

function moveLayer(index: number, direction: -1 | 1) {
  if (readonly.value) return;
  const target = index + direction;
  if (target < 0 || target >= layers.value.length) return;
  const current = layers.value[index]!;
  layers.value[index] = layers.value[target]!;
  layers.value[target] = current;
  normalizeOrder();
  setDirty(true);
}

function changeLayerType(index: number, type: SceneLayerRequest['layerType']) {
  const layer = layers.value[index];
  if (!layer || readonly.value) return;
  layer.layerType = type;
  layer.assetId = null;
  if (!type.startsWith('DYNAMIC_')) layer.maxItems = 0;
  setDirty(true);
}

function availableAssets(index: number) {
  const layer = layers.value[index];
  if (!layer) return [];
  const assetType =
    layer.layerType === 'STATIC_GLB' ? 'GLB' : layer.layerType === 'STATIC_3D_TILES' ? 'TILES_3D' : null;
  return assetType ? props.assets.filter(asset => asset.status === 'AVAILABLE' && asset.assetType === assetType) : [];
}

function selectLayerAsset(index: number, assetId: string | null) {
  const layer = layers.value[index];
  if (!layer || readonly.value) return;
  layer.assetId = assetId;
  setDirty(true);
}

function assetOptions(index: number): SelectOption[] {
  return availableAssets(index).map(asset => ({ label: asset.originalFilename, value: asset.id }));
}

function saveDraft() {
  if (readonly.value) return;
  const defaultCamera = parseJsonObject(form.defaultCameraText);
  const fallbackViewport = parseJsonObject(form.fallbackViewportText);
  if (!defaultCamera || !fallbackViewport) {
    window.$message?.error(t('mine.sceneWorkbench.invalidJson'));
    return;
  }
  if (!form.coordinateSystemId || form.scale <= 0 || layers.value.some(invalidLayer)) {
    window.$message?.error(t('mine.sceneWorkbench.invalidDraft'));
    return;
  }
  const parsedLayers: SceneLayerRequest[] = [];
  for (const [index, layer] of layers.value.entries()) {
    const options = parseJsonObject(layer.optionsText);
    if (!options) {
      window.$message?.error(t('mine.sceneWorkbench.invalidLayerOptions', { index: index + 1 }));
      return;
    }
    parsedLayers.push({
      layerCode: layer.layerCode.trim(),
      orderNumber: index + 1,
      layerType: layer.layerType,
      assetId: staticLayer(layer.layerType) ? layer.assetId : null,
      visible: layer.visible,
      opacity: layer.opacity,
      minimumDistance: layer.minimumDistance,
      maximumDistance: layer.maximumDistance,
      offsetX: layer.offsetX,
      offsetY: layer.offsetY,
      offsetZ: layer.offsetZ,
      headingDegrees: layer.headingDegrees,
      pitchDegrees: layer.pitchDegrees,
      rollDegrees: layer.rollDegrees,
      scale: layer.scale,
      maxItems: layer.maxItems,
      options
    });
  }
  emit(
    'save',
    {
      coordinateSystemId: form.coordinateSystemId,
      coordinateMode: form.coordinateMode,
      originX: form.originX,
      originY: form.originY,
      originZ: form.originZ,
      headingDegrees: form.headingDegrees,
      pitchDegrees: form.pitchDegrees,
      rollDegrees: form.rollDegrees,
      scale: form.scale,
      defaultCamera,
      fallbackViewport,
      version: form.version
    },
    parsedLayers
  );
}

function invalidLayer(layer: LayerDraft) {
  return (
    !layer.layerCode.trim() ||
    layer.opacity < 0 ||
    layer.opacity > 1 ||
    layer.minimumDistance < 0 ||
    layer.maximumDistance < layer.minimumDistance ||
    layer.scale <= 0 ||
    layer.maxItems < 0 ||
    (staticLayer(layer.layerType) && !layer.assetId)
  );
}

function parseJsonObject(value: string): Record<string, unknown> | null {
  try {
    const parsed = JSON.parse(value) as unknown;
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? (parsed as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

function setDirty(value = true) {
  if (dirty.value === value) return;
  dirty.value = value;
  emit('dirtyChange', value);
}

function normalizeOrder() {
  layers.value.forEach((layer, index) => {
    layer.orderNumber = index + 1;
  });
}

function emptyVersionDraft(): VersionDraft {
  return {
    coordinateSystemId: String(props.coordinateSystems[0]?.value ?? ''),
    coordinateMode: 'LOCAL_ENU',
    originX: 0,
    originY: 0,
    originZ: 0,
    headingDegrees: 0,
    pitchDegrees: 0,
    rollDegrees: 0,
    scale: 1,
    defaultCameraText: '{}',
    fallbackViewportText: '{}',
    version: 0
  };
}

function uniqueLayerCode(type: SceneLayerRequest['layerType']) {
  const prefix = type.toLowerCase().replaceAll('_', '-');
  let number = layers.value.length + 1;
  while (layers.value.some(layer => layer.layerCode === `${prefix}-${number}`)) number += 1;
  return `${prefix}-${number}`;
}

function staticLayer(type: SceneLayerRequest['layerType']) {
  return type === 'STATIC_GLB' || type === 'STATIC_3D_TILES';
}

function layerTypeLabel(type: SceneLayerRequest['layerType']) {
  return t(`mine.sceneWorkbench.layerType.${layerTypeKey(type)}`);
}

function layerTypeKey(type: SceneLayerRequest['layerType']) {
  return type.toLowerCase().replaceAll('_', '') as
    | 'grid'
    | 'staticglb'
    | 'static3dtiles'
    | 'dynamiclocations'
    | 'dynamicregions'
    | 'dynamictrajectory'
    | 'dynamicalarms'
    | 'dynamicoperations';
}

const LAYER_TYPES: readonly SceneLayerRequest['layerType'][] = [
  'GRID',
  'STATIC_GLB',
  'STATIC_3D_TILES',
  'DYNAMIC_LOCATIONS',
  'DYNAMIC_REGIONS',
  'DYNAMIC_TRAJECTORY',
  'DYNAMIC_ALARMS',
  'DYNAMIC_OPERATIONS'
];
</script>

<template>
  <NCard :title="t('mine.sceneWorkbench.editorTitle')" size="small" data-testid="scene-version-editor">
    <NFlex vertical size="large">
      <NAlert v-if="readonly" type="info" :title="t('mine.sceneWorkbench.readonlyTitle')">
        {{ t('mine.sceneWorkbench.readonlyNotice') }}
      </NAlert>
      <NForm label-placement="top" :disabled="readonly">
        <NGrid responsive="screen" :cols="1" :x-gap="12" :y-gap="4" item-responsive>
          <NFormItemGi span="1 m:2" :label="t('mine.sceneWorkbench.coordinateSystem')">
            <NSelect
              v-model:value="form.coordinateSystemId"
              :options="coordinateSystems"
              :placeholder="t('mine.sceneWorkbench.coordinateSystemPlaceholder')"
              @update:value="setDirty()"
            />
          </NFormItemGi>
          <NFormItemGi span="1 m:2" :label="t('mine.sceneWorkbench.coordinateModeLabel')">
            <NSelect v-model:value="form.coordinateMode" :options="coordinateModeOptions" @update:value="setDirty()" />
          </NFormItemGi>
          <NFormItemGi
            v-for="field in TRANSFORM_FIELDS"
            :key="field"
            span="1 m:1"
            :label="t(`mine.sceneWorkbench.${field}`)"
          >
            <NInputNumber v-model:value="form[field]" class="w-full" @update:value="setDirty()" />
          </NFormItemGi>
          <NFormItemGi span="1 m:2" :label="t('mine.sceneWorkbench.defaultCamera')">
            <NInput
              v-model:value="form.defaultCameraText"
              type="textarea"
              :autosize="{ minRows: 4, maxRows: 10 }"
              @update:value="setDirty()"
            />
          </NFormItemGi>
          <NFormItemGi span="1 m:2" :label="t('mine.sceneWorkbench.fallbackViewport')">
            <NInput
              v-model:value="form.fallbackViewportText"
              type="textarea"
              :autosize="{ minRows: 4, maxRows: 10 }"
              @update:value="setDirty()"
            />
          </NFormItemGi>
        </NGrid>
      </NForm>

      <NDivider>{{ t('mine.sceneWorkbench.layersTitle') }}</NDivider>
      <NFlex v-if="!readonly" justify="end">
        <NButton data-testid="add-scene-layer" @click="addLayer()">{{ t('mine.sceneWorkbench.addLayer') }}</NButton>
      </NFlex>
      <NEmpty v-if="layers.length === 0" :description="t('mine.sceneWorkbench.noLayers')" />
      <NCard v-for="(layer, index) in layers" :key="layer.clientKey" size="small" data-testid="scene-layer-row">
        <NFlex vertical>
          <NFlex justify="space-between" align="center">
            <NFlex align="center">
              <NText strong>{{ t('mine.sceneWorkbench.layerNumber', { index: index + 1 }) }}</NText>
              <NTag :bordered="false">{{ layerTypeLabel(layer.layerType) }}</NTag>
            </NFlex>
            <NFlex v-if="!readonly">
              <NButton
                :data-testid="`move-layer-up-${index}`"
                text
                :disabled="index === 0"
                @click="moveLayer(index, -1)"
              >
                {{ t('mine.sceneWorkbench.moveUp') }}
              </NButton>
              <NButton text :disabled="index === layers.length - 1" @click="moveLayer(index, 1)">
                {{ t('mine.sceneWorkbench.moveDown') }}
              </NButton>
              <NButton text type="error" @click="removeLayer(index)">
                {{ t('mine.sceneWorkbench.removeLayer') }}
              </NButton>
            </NFlex>
          </NFlex>
          <NGrid responsive="screen" :cols="1" :x-gap="12" item-responsive>
            <NFormItemGi span="1 m:2" :label="t('mine.sceneWorkbench.layerCode')">
              <NInput v-model:value="layer.layerCode" :disabled="readonly" @update:value="setDirty()" />
            </NFormItemGi>
            <NFormItemGi span="1 m:2" :label="t('mine.sceneWorkbench.layerTypeLabel')">
              <NSelect
                :value="layer.layerType"
                :options="layerTypeOptions"
                :disabled="readonly"
                @update:value="changeLayerType(index, $event)"
              />
            </NFormItemGi>
            <NFormItemGi v-if="staticLayer(layer.layerType)" span="1 m:2" :label="t('mine.sceneWorkbench.layerAsset')">
              <NSelect
                :value="layer.assetId"
                :options="assetOptions(index)"
                :disabled="readonly"
                :placeholder="t('mine.sceneWorkbench.layerAssetPlaceholder')"
                @update:value="selectLayerAsset(index, $event)"
              />
            </NFormItemGi>
            <NFormItemGi span="1 m:2" :label="t('mine.sceneWorkbench.visible')">
              <NCheckbox v-model:checked="layer.visible" :disabled="readonly" @update:checked="setDirty()">
                {{ t('mine.sceneWorkbench.visibleNotice') }}
              </NCheckbox>
            </NFormItemGi>
            <NFormItemGi
              v-for="field in LAYER_NUMBER_FIELDS"
              :key="field"
              span="1 m:1"
              :label="t(`mine.sceneWorkbench.${field}`)"
            >
              <NInputNumber
                v-model:value="layer[field]"
                :disabled="readonly"
                class="w-full"
                @update:value="setDirty()"
              />
            </NFormItemGi>
            <NFormItemGi span="1 m:4" :label="t('mine.sceneWorkbench.layerOptions')">
              <NInput
                v-model:value="layer.optionsText"
                type="textarea"
                :disabled="readonly"
                :autosize="{ minRows: 3, maxRows: 8 }"
                @update:value="setDirty()"
              />
            </NFormItemGi>
          </NGrid>
        </NFlex>
      </NCard>

      <NFlex justify="end">
        <NButton
          v-if="version?.status === 'DRAFT' && canPublish"
          type="warning"
          :loading="publishing"
          data-testid="publish-scene-version"
          @click="emit('publish')"
        >
          {{ t('mine.sceneWorkbench.publishVersion') }}
        </NButton>
        <NButton v-if="!readonly" type="primary" :loading="saving" data-testid="save-scene-draft" @click="saveDraft">
          {{ t('mine.sceneWorkbench.saveDraft') }}
        </NButton>
      </NFlex>
    </NFlex>
  </NCard>
</template>
