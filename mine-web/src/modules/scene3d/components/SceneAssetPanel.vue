<script setup lang="ts">
import { computed, h, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns, SelectOption, UploadFileInfo } from 'naive-ui';
import { NAlert, NButton, NCard, NDataTable, NEmpty, NFlex, NProgress, NSelect, NTag, NText, NUpload } from 'naive-ui';
import type { SceneAsset } from '../api/scene';

const props = defineProps<{
  assets: SceneAsset[];
  canUpload: boolean;
  uploading: boolean;
  uploadProgress: number | null;
}>();
const emit = defineEmits<{
  upload: [file: File];
  archive: [asset: SceneAsset];
}>();

const { t, locale } = useI18n();
const typeFilter = ref<SceneAsset['assetType'] | 'ALL'>('ALL');
const statusFilter = ref<SceneAsset['status'] | 'ALL'>('AVAILABLE');
const filteredAssets = computed(() =>
  props.assets.filter(
    asset =>
      (typeFilter.value === 'ALL' || asset.assetType === typeFilter.value) &&
      (statusFilter.value === 'ALL' || asset.status === statusFilter.value)
  )
);
const typeOptions = computed<SelectOption[]>(() => [
  { label: t('mine.sceneWorkbench.allTypes'), value: 'ALL' },
  { label: t('mine.sceneWorkbench.assetType.glb'), value: 'GLB' },
  { label: t('mine.sceneWorkbench.assetType.tiles3d'), value: 'TILES_3D' }
]);
const statusOptions = computed<SelectOption[]>(() => [
  { label: t('mine.sceneWorkbench.allStatuses'), value: 'ALL' },
  ...(['QUARANTINED', 'AVAILABLE', 'ARCHIVED'] as const).map(value => ({
    label: t(`mine.sceneWorkbench.assetStatus.${value.toLowerCase()}`),
    value
  }))
]);
const columns = computed<DataTableColumns<SceneAsset>>(() => [
  { title: t('mine.sceneWorkbench.filename'), key: 'originalFilename', minWidth: 180 },
  {
    title: t('mine.sceneWorkbench.assetTypeLabel'),
    key: 'assetType',
    width: 120,
    render: row => t(`mine.sceneWorkbench.assetType.${row.assetType === 'GLB' ? 'glb' : 'tiles3d'}`)
  },
  {
    title: t('mine.sceneWorkbench.assetSize'),
    key: 'expandedSizeBytes',
    width: 120,
    render: row => formatBytes(row.expandedSizeBytes)
  },
  {
    title: t('mine.sceneWorkbench.assetStatusLabel'),
    key: 'status',
    width: 120,
    render: row =>
      h(NTag, { type: assetStatusType(row.status), bordered: false }, () =>
        t(`mine.sceneWorkbench.assetStatus.${row.status.toLowerCase()}`)
      )
  },
  {
    title: t('mine.sceneWorkbench.createdAt'),
    key: 'createdAt',
    minWidth: 180,
    render: row => new Date(row.createdAt).toLocaleString(locale.value, { hour12: false })
  },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 100,
    render: row =>
      row.status === 'AVAILABLE' && props.canUpload
        ? h(
            NButton,
            {
              text: true,
              type: 'warning',
              'aria-label': t('mine.sceneWorkbench.archiveAssetAria', { name: row.originalFilename }),
              onClick: () => emit('archive', row)
            },
            () => t('mine.sceneWorkbench.archive')
          )
        : null
  }
]);

function selectUpload({ file }: { file: UploadFileInfo }) {
  if (file.file && !props.uploading) emit('upload', file.file);
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return formatUnit(bytes, 'byte');
  if (bytes < 1024 * 1024) return formatUnit(bytes / 1024, 'kilobyte');
  return formatUnit(bytes / 1024 / 1024, 'megabyte');
}

function formatUnit(value: number, unit: 'byte' | 'kilobyte' | 'megabyte') {
  return new Intl.NumberFormat(locale.value, {
    style: 'unit',
    unit,
    unitDisplay: 'short',
    maximumFractionDigits: 1
  }).format(value);
}

function assetStatusType(status: SceneAsset['status']) {
  if (status === 'AVAILABLE') return 'success' as const;
  if (status === 'QUARANTINED') return 'warning' as const;
  return 'default' as const;
}
</script>

<template>
  <NCard :title="t('mine.sceneWorkbench.assetsTitle')" size="small">
    <NFlex vertical size="large">
      <NAlert type="info" :title="t('mine.sceneWorkbench.uploadRulesTitle')">
        {{ t('mine.sceneWorkbench.uploadRules') }}
      </NAlert>
      <NAlert type="warning" :title="t('mine.sceneWorkbench.archiveImpactTitle')">
        {{ t('mine.sceneWorkbench.archiveImpact') }}
      </NAlert>
      <NFlex data-responsive-stack class="flex-col md:flex-row" justify="space-between">
        <NFlex class="w-full md:w-auto">
          <NSelect
            v-model:value="typeFilter"
            :options="typeOptions"
            :placeholder="t('mine.sceneWorkbench.filterAssetType')"
            class="w-full md:w-44"
          />
          <NSelect
            v-model:value="statusFilter"
            :options="statusOptions"
            :placeholder="t('mine.sceneWorkbench.filterAssetStatus')"
            class="w-full md:w-44"
          />
        </NFlex>
        <NUpload
          v-if="canUpload"
          accept=".glb,.zip,model/gltf-binary,application/zip"
          :default-upload="false"
          :max="1"
          :show-file-list="false"
          :disabled="uploading"
          @change="selectUpload"
        >
          <NButton type="primary" :loading="uploading" data-testid="upload-scene-asset">
            {{ t('mine.sceneWorkbench.uploadAsset') }}
          </NButton>
        </NUpload>
      </NFlex>
      <NProgress
        v-if="uploading"
        type="line"
        :percentage="uploadProgress ?? 0"
        :processing="uploadProgress === null"
        indicator-placement="inside"
      />
      <NDataTable
        v-if="filteredAssets.length"
        :columns="columns"
        :data="filteredAssets"
        :row-key="row => row.id"
        :scroll-x="920"
      />
      <NEmpty v-else :description="t('mine.sceneWorkbench.noAssets')">
        <template #extra>
          <NText depth="3">{{ t('mine.sceneWorkbench.noAssetsHint') }}</NText>
        </template>
      </NEmpty>
    </NFlex>
  </NCard>
</template>
