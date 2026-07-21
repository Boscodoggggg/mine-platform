<script setup lang="ts">
import { computed, h, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns } from 'naive-ui';
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NEmpty,
  NFlex,
  NGi,
  NGrid,
  NInput,
  NPopconfirm,
  NSpace,
  NTag,
  NText
} from 'naive-ui';
import { ApiError } from '@/api/client';
import {
  getConfigRelease,
  getConfigReleases,
  publishConfigRelease,
  rollbackConfigRelease
} from '@/modules/device/api/device-model';
import type {
  ConfigReleaseDetail,
  ConfigReleaseSummary
} from '@/modules/device/api/device-model';

const props = defineProps<{
  productId: string;
  mappingVersionId: string;
  protocolPublished: boolean;
  canView: boolean;
  canPublish: boolean;
  canRollback: boolean;
}>();

const { t, locale } = useI18n();
const loading = ref(false);
const detailLoading = ref(false);
const publishing = ref(false);
const rollingBack = ref(false);
const releases = ref<ConfigReleaseSummary[]>([]);
const detail = ref<ConfigReleaseDetail | null>(null);
const publishReason = ref('');
const rollbackReason = ref('');
const publishRequestKey = ref(newRequestKey());
const rollbackRequestKeys = new Map<string, string>();
const lastRefreshedAt = ref<string | null>(null);

const releaseColumns = computed<DataTableColumns<ConfigReleaseSummary>>(() => [
  {
    title: t('mine.models.releases.column.sequence'),
    key: 'releaseSequence',
    width: 150,
    render: row =>
      h(NButton, { text: true, type: 'primary', onClick: () => selectRelease(row.id) }, () =>
        t('mine.models.releases.sequence', { sequence: row.releaseSequence })
      )
  },
  {
    title: t('mine.models.releases.column.action'),
    key: 'action',
    width: 110,
    render: row =>
      h(NTag, { type: row.action === 'ROLLBACK' ? 'warning' : 'info', bordered: false, size: 'small' }, () =>
        t(`mine.models.releases.action.${row.action.toLowerCase()}`)
      )
  },
  { title: t('mine.models.releases.column.reason'), key: 'reason', minWidth: 220 },
  {
    title: t('mine.models.releases.column.createdAt'),
    key: 'createdAt',
    minWidth: 190,
    render: row => formatTime(row.createdAt)
  }
]);

const nodeColumns = computed<DataTableColumns<ConfigReleaseDetail['nodes'][number]>>(() => [
  {
    title: t('mine.models.releases.node.id'),
    key: 'nodeId',
    minWidth: 190,
    render: row => h(NText, { strong: true }, () => row.nodeId)
  },
  {
    title: t('mine.models.releases.node.type'),
    key: 'nodeType',
    width: 120,
    render: row => t(`mine.models.releases.nodeType.${row.nodeType.toLowerCase()}`)
  },
  {
    title: t('mine.models.releases.node.status'),
    key: 'status',
    width: 120,
    render: row =>
      h(NTag, { type: nodeStatusType(row.status), bordered: false, size: 'small' }, () =>
        t(`mine.models.releases.nodeStatus.${row.status.toLowerCase()}`)
      )
  },
  {
    title: t('mine.models.releases.node.hash'),
    key: 'actualHash',
    minWidth: 170,
    render: row => shortHash(row.actualHash)
  },
  {
    title: t('mine.models.releases.node.updatedAt'),
    key: 'updatedAt',
    minWidth: 190,
    render: row => formatTime(row.updatedAt)
  }
]);

watch(
  () => [props.productId, props.canView],
  () => {
    if (props.productId && props.canView) loadReleases();
    else {
      releases.value = [];
      detail.value = null;
    }
  },
  { immediate: true }
);

async function loadReleases(preferredReleaseId?: string) {
  loading.value = true;
  try {
    releases.value = await getConfigReleases(props.productId, 50);
    lastRefreshedAt.value = new Date().toISOString();
    const targetId = preferredReleaseId && releases.value.some(item => item.id === preferredReleaseId)
      ? preferredReleaseId
      : releases.value[0]?.id;
    if (targetId) await selectRelease(targetId);
    else detail.value = null;
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
}

async function selectRelease(releaseId: string) {
  detailLoading.value = true;
  try {
    detail.value = await getConfigRelease(releaseId);
    rollbackReason.value = '';
  } catch (error) {
    showError(error);
  } finally {
    detailLoading.value = false;
  }
}

async function runPublish() {
  const reason = publishReason.value.trim();
  if (!reason) {
    window.$message?.warning(t('mine.models.releases.validation.reason'));
    return;
  }
  if (!props.protocolPublished) {
    window.$message?.warning(t('mine.models.releases.validation.publishProtocol'));
    return;
  }
  publishing.value = true;
  try {
    const release = await publishConfigRelease(
      { productId: props.productId, mappingVersionId: props.mappingVersionId, reason },
      publishRequestKey.value
    );
    publishReason.value = '';
    publishRequestKey.value = newRequestKey();
    window.$message?.success(t('mine.models.releases.message.published'));
    await loadReleases(release.id);
  } catch (error) {
    showError(error);
  } finally {
    publishing.value = false;
  }
}

async function runRollback(release: ConfigReleaseSummary) {
  const reason = rollbackReason.value.trim();
  if (!reason) {
    window.$message?.warning(t('mine.models.releases.validation.rollbackReason'));
    return;
  }
  rollingBack.value = true;
  const requestKey = rollbackRequestKeys.get(release.id) || newRequestKey();
  rollbackRequestKeys.set(release.id, requestKey);
  try {
    const created = await rollbackConfigRelease(release.id, reason, requestKey);
    rollbackRequestKeys.delete(release.id);
    rollbackReason.value = '';
    window.$message?.success(t('mine.models.releases.message.rolledBack'));
    await loadReleases(created.id);
  } catch (error) {
    showError(error);
  } finally {
    rollingBack.value = false;
  }
}

function newRequestKey() {
  return globalThis.crypto?.randomUUID?.() || `config-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value));
}

function shortHash(value?: string | null) {
  if (!value) return t('mine.common.empty');
  return `${value.slice(0, 15)}…${value.slice(-8)}`;
}

function nodeStatusType(status: ConfigReleaseDetail['nodes'][number]['status']) {
  if (status === 'LOADED') return 'success';
  if (status === 'FAILED') return 'error';
  return 'warning';
}

function showError(error: unknown) {
  if (error instanceof ApiError) return window.$message?.error(error.message);
  window.$message?.error(t('mine.common.operationFailed'));
}

defineExpose({ rollbackReason, runRollback });
</script>

<template>
  <NSpace vertical :size="16">
    <NAlert v-if="!canView" type="warning" :bordered="false">
      {{ t('mine.models.releases.permissionMissing') }}
    </NAlert>

    <template v-else>
      <NGrid responsive="screen" cols="1 l:5" :x-gap="16" :y-gap="16">
        <NGi span="1 l:2">
          <NCard :bordered="false" class="h-full">
            <template #header>
              <NSpace vertical :size="2">
                <NText strong>{{ t('mine.models.releases.publishTitle') }}</NText>
                <NText depth="3">{{ t('mine.models.releases.publishSubtitle') }}</NText>
              </NSpace>
            </template>
            <NSpace vertical :size="12">
              <NAlert :type="protocolPublished ? 'info' : 'warning'" :bordered="false">
                {{
                  protocolPublished
                    ? t('mine.models.releases.gateReady')
                    : t('mine.models.releases.protocolNotPublished')
                }}
              </NAlert>
              <NInput
                v-model:value="publishReason"
                data-field="publish-reason"
                type="textarea"
                :placeholder="t('mine.models.releases.publishReason')"
                :autosize="{ minRows: 3, maxRows: 6 }"
                maxlength="500"
                show-count
              />
              <NButton
                v-if="canPublish"
                data-action="publish-config"
                type="primary"
                block
                :disabled="!protocolPublished"
                :loading="publishing"
                @click="runPublish"
              >
                {{ t('mine.models.releases.publishAction') }}
              </NButton>
            </NSpace>
          </NCard>
        </NGi>

        <NGi span="1 l:3">
          <NCard :bordered="false" class="h-full">
            <template #header>
              <NSpace vertical :size="2">
                <NText strong>{{ t('mine.models.releases.evidenceTitle') }}</NText>
                <NText depth="3">
                  {{
                    lastRefreshedAt
                      ? t('mine.models.releases.refreshedAt', { time: formatTime(lastRefreshedAt) })
                      : t('mine.models.releases.notRefreshed')
                  }}
                </NText>
              </NSpace>
            </template>
            <template #header-extra>
              <NButton :loading="loading" @click="loadReleases()">
                {{ t('mine.models.releases.refresh') }}
              </NButton>
            </template>
            <NDataTable
              v-if="releases.length || loading"
              :columns="releaseColumns"
              :data="releases"
              :loading="loading"
              :scroll-x="760"
              :single-line="false"
              size="small"
            />
            <NEmpty v-else :description="t('mine.models.releases.empty')" />
          </NCard>
        </NGi>
      </NGrid>

      <NCard v-if="detail" :bordered="false" :loading="detailLoading">
        <template #header>
          <NFlex align="center">
            <NText strong>{{ t('mine.models.releases.detailTitle') }}</NText>
            <NTag :type="detail.release.action === 'ROLLBACK' ? 'warning' : 'info'" :bordered="false">
              {{ t('mine.models.releases.sequence', { sequence: detail.release.releaseSequence }) }}
            </NTag>
          </NFlex>
        </template>

        <NSpace vertical :size="16">
          <NDescriptions responsive="screen" label-placement="top" :column="3" bordered size="small">
            <NDescriptionsItem :label="t('mine.models.releases.field.releaseId')">
              {{ detail.release.id }}
            </NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.models.releases.field.contentHash')">
              {{ detail.release.contentHash }}
            </NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.models.releases.field.reason')">
              {{ detail.release.reason }}
            </NDescriptionsItem>
          </NDescriptions>

          <NFlex justify="space-between" align="center">
            <NText strong>{{ t('mine.models.releases.nodesTitle') }}</NText>
            <NText depth="3">{{ t('mine.models.releases.nodesCount', { count: detail.nodes.length }) }}</NText>
          </NFlex>
          <NDataTable
            v-if="detail.nodes.length"
            :columns="nodeColumns"
            :data="detail.nodes"
            :scroll-x="800"
            :single-line="false"
            size="small"
          />
          <NEmpty v-else :description="t('mine.models.releases.nodesEmpty')" />

          <NCard v-if="canRollback" embedded :bordered="false">
            <NGrid responsive="screen" cols="1 m:4" :x-gap="12" :y-gap="12">
              <NGi span="1 m:3">
                <NInput
                  v-model:value="rollbackReason"
                  :placeholder="t('mine.models.releases.rollbackReason')"
                  maxlength="500"
                />
              </NGi>
              <NGi>
                <NPopconfirm
                  :positive-text="t('mine.models.releases.rollbackConfirm')"
                  :negative-text="t('mine.common.cancel')"
                  @positive-click="runRollback(detail.release)"
                >
                  <template #trigger>
                    <NButton
                      data-action="rollback-release"
                      type="error"
                      secondary
                      block
                      :loading="rollingBack"
                    >
                      {{ t('mine.models.releases.rollbackAction') }}
                    </NButton>
                  </template>
                  {{ t('mine.models.releases.rollbackWarning') }}
                </NPopconfirm>
              </NGi>
            </NGrid>
          </NCard>
        </NSpace>
      </NCard>
    </template>
  </NSpace>
</template>
