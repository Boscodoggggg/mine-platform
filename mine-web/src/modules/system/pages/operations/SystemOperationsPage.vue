<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  NAlert,
  NButton,
  NCard,
  NEmpty,
  NFlex,
  NGi,
  NGrid,
  NList,
  NListItem,
  NPageHeader,
  NSkeleton,
  NTag,
  NText,
  NThing
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { getSystemOperations } from '@/modules/system/api/operations';
import type {
  SystemOperationCategory,
  SystemOperationItem,
  SystemOperationsOverview
} from '@/modules/system/api/operations';

const { t, locale } = useI18n();
const loading = ref(false);
const overview = ref<SystemOperationsOverview | null>(null);
const loadError = ref(false);

const categories: SystemOperationCategory[] = ['SERVICE', 'MESSAGE', 'STORAGE'];
const groups = computed(() =>
  categories.map(category => ({
    category,
    items: overview.value?.items.filter(item => item.category === category) || []
  }))
);

onMounted(loadOperations);

async function loadOperations() {
  loading.value = true;
  loadError.value = false;
  try {
    overview.value = await getSystemOperations();
  } catch (error) {
    loadError.value = true;
    showError(error);
  } finally {
    loading.value = false;
  }
}

function overallType() {
  if (!overview.value) return 'default' as const;
  if (overview.value.status === 'UP') return 'success' as const;
  if (overview.value.status === 'DEGRADED') return 'warning' as const;
  return 'error' as const;
}

function statusType(status: SystemOperationItem['status']) {
  if (status === 'UP') return 'success' as const;
  if (status === 'DEGRADED') return 'warning' as const;
  if (status === 'DOWN') return 'error' as const;
  return 'default' as const;
}

function statusLabel(status: SystemOperationItem['status']) {
  return t(`mine.operations.status.${status.toLowerCase()}` as App.I18n.I18nKey);
}

function categoryLabel(category: SystemOperationCategory) {
  return t(`mine.operations.category.${category.toLowerCase()}` as App.I18n.I18nKey);
}

function itemName(key: string) {
  const names: Record<string, App.I18n.I18nKey> = {
    'mine-server': 'mine.operations.item.mineServer',
    'mine-gateway': 'mine.operations.item.mineGateway',
    'mine-telemetry': 'mine.operations.item.mineTelemetry',
    rabbitmq: 'mine.operations.item.rabbitMq',
    postgres: 'mine.operations.item.postgres',
    redis: 'mine.operations.item.redis',
    timescale: 'mine.operations.item.timescale'
  };
  return names[key] ? t(names[key]) : key;
}

function detailLabel(detail: string) {
  const key = `mine.operations.detail.${detail}` as App.I18n.I18nKey;
  return t(key);
}

function formatTime(value: string) {
  return new Date(value).toLocaleString(locale.value, { hour12: false });
}

function showError(error: unknown) {
  if (error instanceof ApiError) {
    window.$message?.error(
      error.traceId ? `${error.message} (${t('mine.common.traceId', { traceId: error.traceId })})` : error.message
    );
  } else {
    window.$message?.error(t('mine.operations.loadFailed'));
  }
}
</script>

<template>
  <div>
    <NFlex vertical :size="16">
      <NPageHeader :title="t('mine.operations.title')" :subtitle="t('mine.operations.subtitle')">
        <template #extra>
          <NFlex align="center">
            <NText v-if="overview" depth="3">
              {{ t('mine.operations.checkedAt', { time: formatTime(overview.checkedAt) }) }}
            </NText>
            <NTag v-if="overview" :type="overallType()" :bordered="false">
              {{ statusLabel(overview.status) }}
            </NTag>
            <NButton :loading="loading" @click="loadOperations">{{ t('common.refresh') }}</NButton>
          </NFlex>
        </template>
      </NPageHeader>

      <NAlert v-if="loadError" type="error" :title="t('mine.operations.loadFailed')">
        {{ t('mine.operations.loadFailedDescription') }}
      </NAlert>

      <NAlert type="info" :show-icon="false">{{ t('mine.operations.onDemandNote') }}</NAlert>

      <NGrid responsive="screen" cols="1 m:2 xl:3" :x-gap="16" :y-gap="16">
        <NGi v-for="group in groups" :key="group.category">
          <NCard :title="categoryLabel(group.category)" class="h-full">
            <NSkeleton v-if="loading && !overview" text :repeat="5" />
            <NList v-else-if="group.items.length">
              <NListItem v-for="item in group.items" :key="item.key">
                <NThing :title="itemName(item.key)" :description="detailLabel(item.summary)">
                  <template #header-extra>
                    <NTag :type="statusType(item.status)" size="small" :bordered="false">
                      {{ statusLabel(item.status) }}
                    </NTag>
                  </template>
                </NThing>
                <template #suffix>
                  <NText depth="3">{{ t('mine.operations.latency', { value: item.latencyMillis }) }}</NText>
                </template>
              </NListItem>
            </NList>
            <NEmpty v-else :description="t('mine.operations.empty')" />
          </NCard>
        </NGi>
      </NGrid>
    </NFlex>
  </div>
</template>
