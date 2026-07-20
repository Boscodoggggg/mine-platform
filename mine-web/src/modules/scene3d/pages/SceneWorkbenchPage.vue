<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { useI18n } from 'vue-i18n';
import type { SelectOption } from 'naive-ui';
import {
  NAlert,
  NButton,
  NButtonGroup,
  NCard,
  NEmpty,
  NFlex,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NSelect,
  NSpin,
  NTabPane,
  NTabs,
  NTag,
  NText
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import MineSiteSelector from '@/modules/mine/components/MineSiteSelector.vue';
import { getMineCoordinateSystems } from '@/modules/mine/api/mine-api';
import type { CoordinateSystem } from '@/modules/mine/api/mine-api';
import { useMineContextStore } from '@/modules/mine/stores/mine-context';
import { useAuthStore } from '@/store/modules/auth';
import {
  archiveScene,
  archiveSceneAsset,
  cloneNextSceneDraft,
  createScene,
  createSceneDraft,
  getSceneRuntime,
  listSceneAssets,
  listSceneVersions,
  listScenes,
  makeDefaultScene,
  publishSceneVersion,
  replaceSceneLayers,
  updateSceneDraft,
  uploadSceneAsset
} from '../api/scene';
import type {
  Scene,
  SceneAsset,
  SceneLayerRequest,
  SceneRuntime,
  SceneVersion,
  SceneVersionRequest
} from '../api/scene';
import type { SceneViewportMode } from '../runtime/types';
import SceneAssetPanel from '../components/SceneAssetPanel.vue';
import SceneVersionEditor from '../components/SceneVersionEditor.vue';
import CesiumSceneViewport from '../components/CesiumSceneViewport.vue';

const { t } = useI18n();
const authStore = useAuthStore();
const mineContext = useMineContextStore();
const activeTab = ref<'RUNTIME' | 'GOVERNANCE'>('RUNTIME');
const loading = ref(false);
const saving = ref(false);
const publishing = ref(false);
const uploading = ref(false);
const uploadProgress = ref<number | null>(null);
const runtime = ref<SceneRuntime | null>(null);
const viewportMode = ref<SceneViewportMode>('AUTO');
const scenes = ref<Scene[]>([]);
const assets = ref<SceneAsset[]>([]);
const versions = ref<SceneVersion[]>([]);
const coordinateSystems = ref<CoordinateSystem[]>([]);
const selectedSceneId = ref<string | null>(null);
const selectedVersionId = ref<string | null>(null);
const draftDirty = ref(false);
const createSceneVisible = ref(false);
const sceneForm = reactive({ sceneCode: '', name: '', description: '' });
let loadedSiteId: string | null = null;
let scopeGeneration = 0;
let scopeController: AbortController | null = null;
let uploadController: AbortController | null = null;

const canEdit = computed(() => can('mine:scene:edit'));
const canUpload = computed(() => can('mine:scene:asset:upload'));
const canPublish = computed(() => can('mine:scene:publish'));
const canGovern = computed(() => canEdit.value || canUpload.value || canPublish.value);
const selectedScene = computed(() => scenes.value.find(scene => scene.id === selectedSceneId.value) ?? null);
const selectedVersion = computed(() => versions.value.find(version => version.id === selectedVersionId.value) ?? null);
const publishedVersion = computed(() => versions.value.find(version => version.status === 'PUBLISHED') ?? null);
const sceneOptions = computed<SelectOption[]>(() =>
  scenes.value.map(scene => ({
    label: `${scene.name} · ${scene.sceneCode}`,
    value: scene.id
  }))
);
const versionOptions = computed<SelectOption[]>(() =>
  versions.value.map(version => ({
    label: `${t('mine.sceneWorkbench.revision')} ${version.revisionNumber} · ${versionStatusLabel(version.status)}`,
    value: version.id
  }))
);
const coordinateOptions = computed(() =>
  coordinateSystems.value.map(system => ({ label: `${system.name} · ${system.systemCode}`, value: system.id }))
);
const runtimeReason = computed(() =>
  runtime.value?.status === 'DEGRADED' && runtime.value.reason !== 'NONE' ? runtime.value.reason : null
);

onMounted(async () => {
  try {
    await mineContext.refreshContext();
    if (!mineContext.selectedSiteId && mineContext.sites.length === 1) {
      mineContext.selectSite(mineContext.sites[0]!.id);
    }
    if (mineContext.selectedSiteId) await loadSite(mineContext.selectedSiteId);
  } catch (error) {
    showError(error, 'mine.sceneWorkbench.loadFailed');
  }
});

onBeforeUnmount(() => {
  scopeGeneration += 1;
  scopeController?.abort();
  uploadController?.abort();
});

onBeforeRouteLeave((_to, _from, next) => {
  if (!draftDirty.value) {
    next();
    return;
  }
  window.$dialog?.warning({
    title: t('common.warning'),
    content: t('mine.sceneWorkbench.unsavedImpact'),
    positiveText: t('mine.sceneWorkbench.discardChanges'),
    negativeText: t('mine.common.cancel'),
    onPositiveClick: () => next(),
    onNegativeClick: () => next(false)
  });
});

async function changeSite(siteId: string) {
  if (siteId === loadedSiteId) return;
  if (draftDirty.value) {
    if (loadedSiteId) mineContext.selectSite(loadedSiteId);
    window.$dialog?.warning({
      title: t('common.warning'),
      content: t('mine.sceneWorkbench.unsavedImpact'),
      positiveText: t('mine.sceneWorkbench.discardChanges'),
      negativeText: t('mine.common.cancel'),
      onPositiveClick: () => applySiteChange(siteId)
    });
    return;
  }
  await applySiteChange(siteId);
}

async function applySiteChange(siteId: string) {
  mineContext.selectSite(siteId);
  draftDirty.value = false;
  await loadSite(siteId);
}

async function loadSite(siteId: string) {
  scopeController?.abort();
  uploadController?.abort();
  const controller = new AbortController();
  scopeController = controller;
  const generation = ++scopeGeneration;
  clearSiteState();
  loadedSiteId = siteId;
  loading.value = true;
  try {
    if (!canGovern.value) {
      const nextRuntime = await getSceneRuntime(siteId, controller.signal);
      if (!scopeCurrent(generation, siteId)) return;
      runtime.value = nextRuntime;
      return;
    }
    const [nextRuntime, scenePage, assetPage, coordinatePage] = await Promise.all([
      getSceneRuntime(siteId, controller.signal),
      listScenes(siteId, { status: 'ACTIVE', page: 1, size: 100 }, controller.signal),
      listSceneAssets(siteId, { page: 1, size: 100 }, controller.signal),
      getMineCoordinateSystems(siteId, { status: 'ACTIVE', page: 1, size: 100 }, controller.signal)
    ]);
    if (!scopeCurrent(generation, siteId)) return;
    runtime.value = nextRuntime;
    scenes.value = scenePage.items;
    assets.value = assetPage.items;
    coordinateSystems.value = coordinatePage.items.filter(item => item.unified);
    const preferredScene = scenes.value.find(scene => scene.defaultScene) ?? scenes.value[0] ?? null;
    selectedSceneId.value = preferredScene?.id ?? null;
    if (preferredScene) await loadVersions(preferredScene.id, generation, controller.signal);
  } catch (error) {
    if (!isCancelled(error) && scopeCurrent(generation, siteId)) showError(error, 'mine.sceneWorkbench.loadFailed');
  } finally {
    if (scopeCurrent(generation, siteId)) loading.value = false;
  }
}

async function loadVersions(
  sceneId = selectedSceneId.value,
  generation = scopeGeneration,
  signal = scopeController?.signal
) {
  const siteId = loadedSiteId;
  if (!siteId || !sceneId || !signal) return;
  try {
    const page = await listSceneVersions(siteId, sceneId, { page: 1, size: 100 }, signal);
    if (!scopeCurrent(generation, siteId) || sceneId !== selectedSceneId.value) return;
    versions.value = [...page.items].sort((left, right) => right.revisionNumber - left.revisionNumber);
    const preferred = versions.value.find(version => version.status === 'DRAFT') ?? versions.value[0] ?? null;
    selectedVersionId.value = preferred?.id ?? null;
    draftDirty.value = false;
  } catch (error) {
    if (!isCancelled(error) && scopeCurrent(generation, siteId))
      showError(error, 'mine.sceneWorkbench.versionsLoadFailed');
  }
}

async function selectScene(sceneId: string) {
  if (sceneId === selectedSceneId.value) return;
  if (draftDirty.value) {
    window.$message?.warning(t('mine.sceneWorkbench.saveOrDiscardFirst'));
    return;
  }
  selectedSceneId.value = sceneId;
  versions.value = [];
  selectedVersionId.value = null;
  await loadVersions(sceneId);
}

function selectVersion(versionId: string) {
  if (draftDirty.value) {
    window.$message?.warning(t('mine.sceneWorkbench.saveOrDiscardFirst'));
    return;
  }
  selectedVersionId.value = versionId;
}

async function refreshCurrentSite() {
  if (loadedSiteId) await loadSite(loadedSiteId);
}

async function refreshRuntime() {
  if (!loadedSiteId) return;
  try {
    runtime.value = await getSceneRuntime(loadedSiteId, scopeController?.signal);
  } catch (error) {
    if (!isCancelled(error)) showError(error, 'mine.sceneWorkbench.runtimeLoadFailed');
  }
}

async function submitScene() {
  const siteId = loadedSiteId;
  if (!siteId || !canEdit.value || !sceneForm.sceneCode.trim() || !sceneForm.name.trim()) return;
  saving.value = true;
  try {
    const created = await createScene(siteId, {
      sceneCode: sceneForm.sceneCode.trim(),
      name: sceneForm.name.trim(),
      description: sceneForm.description.trim() || null,
      version: 0
    });
    scenes.value = [...scenes.value, created];
    selectedSceneId.value = created.id;
    versions.value = [];
    selectedVersionId.value = null;
    createSceneVisible.value = false;
    Object.assign(sceneForm, { sceneCode: '', name: '', description: '' });
    window.$message?.success(t('mine.sceneWorkbench.sceneCreated'));
  } catch (error) {
    showError(error, 'mine.sceneWorkbench.operationFailed');
  } finally {
    saving.value = false;
  }
}

async function saveVersion(body: SceneVersionRequest, layers: SceneLayerRequest[]) {
  const siteId = loadedSiteId;
  const sceneId = selectedSceneId.value;
  if (!siteId || !sceneId || !canEdit.value) return;
  saving.value = true;
  try {
    const updated = selectedVersion.value
      ? await updateSceneDraft(siteId, sceneId, selectedVersion.value.id, body)
      : await createSceneDraft(siteId, sceneId, body);
    await replaceSceneLayers(siteId, sceneId, updated.id, updated.version, layers);
    draftDirty.value = false;
    await loadVersions(sceneId);
    window.$message?.success(t('mine.sceneWorkbench.draftSaved'));
  } catch (error) {
    await handleConflict(error, () => loadVersions(sceneId));
  } finally {
    saving.value = false;
  }
}

async function cloneDraft() {
  const siteId = loadedSiteId;
  const sceneId = selectedSceneId.value;
  if (!siteId || !sceneId || !canEdit.value) return;
  saving.value = true;
  try {
    await cloneNextSceneDraft(siteId, sceneId);
    await loadVersions(sceneId);
    window.$message?.success(t('mine.sceneWorkbench.draftCloned'));
  } catch (error) {
    await handleConflict(error, () => loadVersions(sceneId));
  } finally {
    saving.value = false;
  }
}

function confirmPublish() {
  const siteId = loadedSiteId;
  const sceneId = selectedSceneId.value;
  const version = selectedVersion.value;
  if (!siteId || !sceneId || !version || version.status !== 'DRAFT' || !canPublish.value) return;
  const idempotencyKey = actionKey('publish', siteId, version.id);
  window.$dialog?.warning({
    title: t('mine.sceneWorkbench.publishTitle'),
    content: t('mine.sceneWorkbench.publishImpact'),
    positiveText: t('mine.sceneWorkbench.publishVersion'),
    negativeText: t('mine.common.cancel'),
    onPositiveClick: async () => {
      publishing.value = true;
      try {
        await publishSceneVersion(siteId, sceneId, version.id, version.version, idempotencyKey);
        draftDirty.value = false;
        await loadVersions(sceneId);
        await refreshRuntime();
        window.$message?.success(t('mine.sceneWorkbench.versionPublished'));
      } catch (error) {
        await handleConflict(error, () => loadVersions(sceneId));
      } finally {
        publishing.value = false;
      }
    }
  });
}

function confirmMakeDefault() {
  const siteId = loadedSiteId;
  const scene = selectedScene.value;
  if (!siteId || !scene || scene.defaultScene || !publishedVersion.value || !canPublish.value) return;
  const idempotencyKey = actionKey('default', siteId, scene.id);
  window.$dialog?.warning({
    title: t('mine.sceneWorkbench.defaultTitle'),
    content: t('mine.sceneWorkbench.defaultImpact', { name: scene.name }),
    positiveText: t('mine.sceneWorkbench.makeDefault'),
    negativeText: t('mine.common.cancel'),
    onPositiveClick: async () => {
      try {
        const updated = await makeDefaultScene(siteId, scene.id, scene.version, idempotencyKey);
        scenes.value = scenes.value.map(item => ({
          ...item,
          defaultScene: item.id === updated.id,
          version: item.id === updated.id ? updated.version : item.version
        }));
        await refreshRuntime();
        window.$message?.success(t('mine.sceneWorkbench.defaultChanged'));
      } catch (error) {
        await handleConflict(error, refreshCurrentSite);
      }
    }
  });
}

function confirmArchiveScene() {
  const siteId = loadedSiteId;
  const scene = selectedScene.value;
  if (!siteId || !scene || scene.defaultScene || !canEdit.value) return;
  const idempotencyKey = actionKey('archive-scene', siteId, scene.id);
  window.$dialog?.warning({
    title: t('mine.sceneWorkbench.archiveSceneTitle'),
    content: t('mine.sceneWorkbench.archiveSceneImpact', { name: scene.name }),
    positiveText: t('mine.sceneWorkbench.archive'),
    negativeText: t('mine.common.cancel'),
    onPositiveClick: async () => {
      try {
        await archiveScene(siteId, scene.id, scene.version, idempotencyKey);
        await refreshCurrentSite();
        window.$message?.success(t('mine.sceneWorkbench.sceneArchived'));
      } catch (error) {
        await handleConflict(error, refreshCurrentSite);
      }
    }
  });
}

async function uploadAsset(file: File) {
  const siteId = loadedSiteId;
  if (!siteId || !canUpload.value) return;
  uploadController?.abort();
  const controller = new AbortController();
  uploadController = controller;
  uploading.value = true;
  uploadProgress.value = 0;
  try {
    await uploadSceneAsset(siteId, file, {
      signal: controller.signal,
      onProgress: progress => {
        if (uploadController === controller) uploadProgress.value = progress.percent;
      }
    });
    await refreshAssets();
    window.$message?.success(t('mine.sceneWorkbench.assetUploaded'));
  } catch (error) {
    if (!isCancelled(error)) showError(error, 'mine.sceneWorkbench.uploadFailed');
  } finally {
    if (uploadController === controller) {
      uploading.value = false;
      uploadProgress.value = null;
      uploadController = null;
    }
  }
}

function confirmArchiveAsset(asset: SceneAsset) {
  const siteId = loadedSiteId;
  if (!siteId || !canUpload.value || asset.siteId !== siteId) return;
  const idempotencyKey = actionKey('archive-asset', siteId, asset.id);
  window.$dialog?.warning({
    title: t('mine.sceneWorkbench.archiveAssetTitle'),
    content: t('mine.sceneWorkbench.archiveAssetImpact', { name: asset.originalFilename }),
    positiveText: t('mine.sceneWorkbench.archive'),
    negativeText: t('mine.common.cancel'),
    onPositiveClick: async () => {
      try {
        await archiveSceneAsset(siteId, asset.id, asset.version, idempotencyKey);
        await refreshAssets();
        window.$message?.success(t('mine.sceneWorkbench.assetArchived'));
      } catch (error) {
        await handleConflict(error, refreshAssets);
      }
    }
  });
}

async function refreshAssets() {
  const siteId = loadedSiteId;
  if (!siteId) return;
  const page = await listSceneAssets(siteId, { page: 1, size: 100 }, scopeController?.signal);
  if (siteId === loadedSiteId) assets.value = page.items;
}

async function handleConflict(error: unknown, refresh: () => Promise<void>) {
  if (error instanceof ApiError && error.status === 409) {
    window.$message?.warning(t('mine.sceneWorkbench.optimisticConflict'));
    await refresh();
    return;
  }
  showError(error, 'mine.sceneWorkbench.operationFailed');
}

function clearSiteState() {
  runtime.value = null;
  scenes.value = [];
  assets.value = [];
  versions.value = [];
  coordinateSystems.value = [];
  selectedSceneId.value = null;
  selectedVersionId.value = null;
  draftDirty.value = false;
  uploadProgress.value = null;
  uploading.value = false;
}

function scopeCurrent(generation: number, siteId: string) {
  return generation === scopeGeneration && siteId === loadedSiteId && !scopeController?.signal.aborted;
}

function showError(error: unknown, fallbackKey: App.I18n.I18nKey) {
  if (error instanceof ApiError) return window.$message?.error(error.message);
  window.$message?.error(t(fallbackKey));
}

function isCancelled(error: unknown) {
  return error instanceof ApiError && error.code === 'SYS-REQUEST-CANCELLED';
}

function can(permission: string) {
  return hasPermission(authStore.userInfo.buttons, permission);
}

function actionKey(action: string, siteId: string, resourceId: string) {
  return `${action}:${siteId}:${resourceId}:${crypto.randomUUID()}`;
}

function versionStatusLabel(status: SceneVersion['status']) {
  return t(`mine.sceneWorkbench.versionStatus.${status.toLowerCase()}`);
}

function versionStatusType(status: SceneVersion['status']) {
  if (status === 'PUBLISHED') return 'success' as const;
  if (status === 'DRAFT') return 'warning' as const;
  return 'default' as const;
}
</script>

<template>
  <div class="flex flex-col gap-4">
    <NCard :bordered="false">
      <NFlex vertical size="small">
        <NText tag="h1" class="text-xl font-semibold">{{ t('mine.sceneWorkbench.title') }}</NText>
        <NText depth="3">{{ t('mine.sceneWorkbench.description') }}</NText>
      </NFlex>
    </NCard>

    <MineSiteSelector @change="changeSite" @refresh="refreshCurrentSite" />

    <NCard :bordered="false">
      <NTabs v-model:value="activeTab" type="line" animated>
        <NTabPane name="RUNTIME" :tab="t('mine.sceneWorkbench.runtimeTab')">
          <NSpin :show="loading">
            <NFlex justify="space-between" align="center" class="mb-4">
              <NText depth="3">{{ t('mine.sceneWorkbench.viewMode') }}</NText>
              <NButtonGroup role="group" :aria-label="t('mine.sceneWorkbench.viewMode')">
                <NButton
                  data-testid="scene-mode-auto"
                  :type="viewportMode === 'AUTO' ? 'primary' : 'default'"
                  :aria-pressed="viewportMode === 'AUTO'"
                  @click="viewportMode = 'AUTO'"
                >
                  {{ t('mine.sceneWorkbench.viewModeAuto') }}
                </NButton>
                <NButton
                  data-testid="scene-mode-3d"
                  :type="viewportMode === '3D' ? 'primary' : 'default'"
                  :aria-pressed="viewportMode === '3D'"
                  @click="viewportMode = '3D'"
                >
                  {{ t('mine.sceneWorkbench.viewMode3d') }}
                </NButton>
                <NButton
                  data-testid="scene-mode-2d"
                  :type="viewportMode === '2D' ? 'primary' : 'default'"
                  :aria-pressed="viewportMode === '2D'"
                  @click="viewportMode = '2D'"
                >
                  {{ t('mine.sceneWorkbench.viewMode2d') }}
                </NButton>
              </NButtonGroup>
            </NFlex>
            <NAlert
              v-if="runtime?.status === 'DEGRADED'"
              type="warning"
              :title="t('mine.sceneWorkbench.runtimeEmptyTitle')"
              data-testid="runtime-empty"
              class="mb-4"
            >
              {{ t('mine.sceneWorkbench.runtimeEmpty') }}
            </NAlert>
            <CesiumSceneViewport
              :snapshot="runtime?.snapshot ?? null"
              :degradation-reason="runtimeReason"
              :mode="viewportMode"
            />
          </NSpin>
        </NTabPane>

        <NTabPane v-if="canGovern" name="GOVERNANCE" :tab="t('mine.sceneWorkbench.governanceTab')">
          <NSpin :show="loading">
            <NFlex vertical size="large" data-testid="scene-governance">
              <NCard :title="t('mine.sceneWorkbench.scenesTitle')" size="small">
                <NFlex vertical>
                  <NFlex data-responsive-stack class="flex-col md:flex-row" justify="space-between">
                    <NFlex class="w-full md:w-auto" align="center">
                      <NSelect
                        :value="selectedSceneId"
                        :options="sceneOptions"
                        :placeholder="t('mine.sceneWorkbench.scenePlaceholder')"
                        class="w-full md:w-96"
                        @update:value="selectScene"
                      />
                      <NTag v-if="selectedScene?.defaultScene" type="success" :bordered="false">
                        {{ t('mine.sceneWorkbench.defaultScene') }}
                      </NTag>
                    </NFlex>
                    <NFlex>
                      <NButton v-if="canEdit" @click="createSceneVisible = true">
                        {{ t('mine.sceneWorkbench.createScene') }}
                      </NButton>
                      <NButton
                        v-if="canPublish && selectedScene && !selectedScene.defaultScene"
                        type="primary"
                        :disabled="!publishedVersion"
                        data-testid="make-default-scene"
                        @click="confirmMakeDefault"
                      >
                        {{ t('mine.sceneWorkbench.makeDefault') }}
                      </NButton>
                      <NButton
                        v-if="canEdit && selectedScene && !selectedScene.defaultScene"
                        type="warning"
                        @click="confirmArchiveScene"
                      >
                        {{ t('mine.sceneWorkbench.archive') }}
                      </NButton>
                    </NFlex>
                  </NFlex>
                  <NEmpty v-if="scenes.length === 0" :description="t('mine.sceneWorkbench.noScenes')" />
                </NFlex>
              </NCard>

              <SceneAssetPanel
                :assets="assets"
                :can-upload="canUpload"
                :uploading="uploading"
                :upload-progress="uploadProgress"
                @upload="uploadAsset"
                @archive="confirmArchiveAsset"
              />

              <NCard v-if="selectedScene" :title="t('mine.sceneWorkbench.versionsTitle')" size="small">
                <NFlex data-responsive-stack class="flex-col md:flex-row" justify="space-between">
                  <NSelect
                    :value="selectedVersionId"
                    :options="versionOptions"
                    :placeholder="t('mine.sceneWorkbench.versionPlaceholder')"
                    class="w-full md:w-96"
                    @update:value="selectVersion"
                  />
                  <NFlex align="center">
                    <NTag v-if="selectedVersion" :type="versionStatusType(selectedVersion.status)" :bordered="false">
                      {{ versionStatusLabel(selectedVersion.status) }}
                    </NTag>
                    <NButton
                      v-if="canEdit && publishedVersion && !versions.some(item => item.status === 'DRAFT')"
                      :loading="saving"
                      @click="cloneDraft"
                    >
                      {{ t('mine.sceneWorkbench.cloneDraft') }}
                    </NButton>
                  </NFlex>
                </NFlex>
              </NCard>

              <SceneVersionEditor
                v-if="selectedScene && (selectedVersion || (canEdit && versions.length === 0))"
                :version="selectedVersion"
                :assets="assets"
                :coordinate-systems="coordinateOptions"
                :can-edit="canEdit"
                :can-publish="canPublish"
                :saving="saving"
                :publishing="publishing"
                @save="saveVersion"
                @publish="confirmPublish"
                @dirty-change="draftDirty = $event"
              />
              <NEmpty v-else-if="selectedScene" :description="t('mine.sceneWorkbench.noVersions')" />
            </NFlex>
          </NSpin>
        </NTabPane>
      </NTabs>
    </NCard>

    <NModal v-model:show="createSceneVisible">
      <NCard :title="t('mine.sceneWorkbench.createSceneTitle')" class="w-full max-w-xl" role="dialog">
        <NForm label-placement="top">
          <NFormItem :label="t('mine.sceneWorkbench.sceneCode')">
            <NInput v-model:value="sceneForm.sceneCode" :placeholder="t('mine.sceneWorkbench.sceneCodePlaceholder')" />
          </NFormItem>
          <NFormItem :label="t('mine.sceneWorkbench.sceneName')">
            <NInput v-model:value="sceneForm.name" :placeholder="t('mine.sceneWorkbench.sceneNamePlaceholder')" />
          </NFormItem>
          <NFormItem :label="t('mine.sceneWorkbench.sceneDescription')">
            <NInput v-model:value="sceneForm.description" type="textarea" />
          </NFormItem>
        </NForm>
        <template #footer>
          <NFlex justify="end">
            <NButton @click="createSceneVisible = false">{{ t('mine.common.cancel') }}</NButton>
            <NButton type="primary" :loading="saving" @click="submitScene">
              {{ t('mine.sceneWorkbench.createScene') }}
            </NButton>
          </NFlex>
        </template>
      </NCard>
    </NModal>
  </div>
</template>
