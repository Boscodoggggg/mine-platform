<script setup lang="tsx">
import { computed, h, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  NAlert,
  NButton,
  NButtonGroup,
  NCard,
  NDataTable,
  NDivider,
  NDrawer,
  NDrawerContent,
  NFlex,
  NInput,
  NPageHeader,
  NSelect,
  NTag,
  NText
} from 'naive-ui';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import { ApiError } from '@/api/client';
import {
  getSystemOperationSeries,
  getSystemOperations,
  getSystemOperationsOverview
} from '@/modules/system/api/operations';
import type {
  OperationsObservabilityItem,
  OperationsObservabilityOverview,
  OperationsObservabilityRange,
  OperationsObservabilitySeries,
  SystemOperationCategory,
  SystemOperationItem,
  SystemOperationsOverview
} from '@/modules/system/api/operations';
import OperationsTrendChart from './OperationsTrendChart.vue';

const { t, locale } = useI18n();

const ranges: OperationsObservabilityRange[] = ['1h', '6h', '24h', '7d', '15d'];
const range = ref<OperationsObservabilityRange>('24h');
const historyLoading = ref(false);
const checkLoading = ref(false);
const seriesLoading = ref(false);
const historyError = ref(false);
const overview = ref<OperationsObservabilityOverview | null>(null);
const immediate = ref<SystemOperationsOverview | null>(null);
const selectedSeries = ref<OperationsObservabilitySeries | null>(null);
const keyword = ref('');
const statusFilter = ref<string | null>(null);
const categoryFilter = ref<SystemOperationCategory | null>(null);

const filteredItems = computed(() => {
  const normalizedKeyword = keyword.value.trim().toLocaleLowerCase(locale.value);
  return (overview.value?.items || []).filter(item => {
    const matchesKeyword = !normalizedKeyword || itemName(item.key).toLocaleLowerCase(locale.value).includes(normalizedKeyword);
    const matchesStatus = !statusFilter.value || item.status === statusFilter.value;
    const matchesCategory = !categoryFilter.value || item.category === categoryFilter.value;
    return matchesKeyword && matchesStatus && matchesCategory;
  });
});

const immediateItems = computed(() => immediate.value?.items || []);
const selectedItem = computed(() =>
  overview.value?.items.find(item => item.key === selectedSeries.value?.key) || null
);

const statusOptions = computed<SelectOption[]>(() =>
  ['UP', 'DEGRADED', 'DOWN', 'UNKNOWN'].map(value => ({
    value,
    label: statusLabel(value as OperationsObservabilityItem['status'])
  }))
);

const categoryOptions = computed<SelectOption[]>(() =>
  ['SERVICE', 'MESSAGE', 'STORAGE'].map(value => ({
    value,
    label: categoryLabel(value as SystemOperationCategory)
  }))
);

const columns = computed<DataTableColumns<OperationsObservabilityItem>>(() => [
  {
    title: t('mine.operations.column.status'),
    key: 'status',
    width: 104,
    render: item => h(NTag, { type: statusType(item.status), size: 'small', bordered: false }, {
      default: () => statusLabel(item.status)
    })
  },
  {
    title: t('mine.operations.column.item'),
    key: 'key',
    minWidth: 180,
    render: item => h(NText, { strong: true }, { default: () => itemName(item.key) })
  },
  {
    title: t('mine.operations.column.category'),
    key: 'category',
    width: 136,
    render: item => categoryLabel(item.category)
  },
  {
    title: t('mine.operations.column.availability'),
    key: 'availabilityPercent',
    width: 128,
    render: item => h('span', { class: 'whitespace-nowrap font-semibold' }, formatAvailability(item.availabilityPercent))
  },
  {
    title: t('mine.operations.column.p95Latency'),
    key: 'p95LatencyMillis',
    width: 112,
    render: item => h('span', { class: 'whitespace-nowrap' }, formatLatency(item.p95LatencyMillis))
  },
  {
    title: t('mine.operations.column.lastChanged'),
    key: 'lastChangedAt',
    minWidth: 180,
    render: item => (item.lastChangedAt ? formatTime(item.lastChangedAt) : t('mine.operations.notAvailable'))
  },
  {
    title: t('mine.operations.column.action'),
    key: 'actions',
    width: 108,
    render: item => h(
      NButton,
      { text: true, type: 'primary', onClick: () => loadSeries(item.key) },
      { default: () => t('mine.operations.action.viewTrend') }
    )
  }
]);

onMounted(loadHistory);

async function loadHistory() {
  historyLoading.value = true;
  historyError.value = false;
  selectedSeries.value = null;
  try {
    overview.value = await getSystemOperationsOverview(range.value);
  } catch (error) {
    historyError.value = true;
    overview.value = null;
    showError(error, 'mine.operations.historyLoadFailed');
  } finally {
    historyLoading.value = false;
  }
}

async function runImmediateCheck() {
  checkLoading.value = true;
  try {
    immediate.value = await getSystemOperations();
  } catch (error) {
    showError(error, 'mine.operations.loadFailed');
  } finally {
    checkLoading.value = false;
  }
}

async function loadSeries(key: string) {
  seriesLoading.value = true;
  try {
    selectedSeries.value = await getSystemOperationSeries(key, range.value);
  } catch (error) {
    showError(error, 'mine.operations.trendLoadFailed');
  } finally {
    seriesLoading.value = false;
  }
}

function handleTrendDrawerVisibility(show: boolean) {
  if (!show) selectedSeries.value = null;
}

async function selectRange(value: OperationsObservabilityRange) {
  if (range.value === value) return;
  range.value = value;
  await loadHistory();
}

function overallType() {
  return statusType(overview.value?.status || 'UNKNOWN');
}

function statusType(status: OperationsObservabilityItem['status'] | SystemOperationItem['status']) {
  if (status === 'UP') return 'success' as const;
  if (status === 'DEGRADED') return 'warning' as const;
  if (status === 'DOWN') return 'error' as const;
  return 'default' as const;
}

function statusLabel(status: OperationsObservabilityItem['status'] | SystemOperationItem['status']) {
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
  return t(`mine.operations.detail.${detail}` as App.I18n.I18nKey);
}

function rangeLabel(value: OperationsObservabilityRange) {
  const rangeKeys: Record<OperationsObservabilityRange, App.I18n.I18nKey> = {
    '1h': 'mine.operations.range.hour',
    '6h': 'mine.operations.range.sixHours',
    '24h': 'mine.operations.range.day',
    '7d': 'mine.operations.range.week',
    '15d': 'mine.operations.range.fifteenDays'
  };

  return t(rangeKeys[value]);
}

function formatTime(value: string) {
  return new Date(value).toLocaleString(locale.value, { hour12: false });
}

function formatAvailability(value: number | null) {
  return value === null ? t('mine.operations.notAvailable') : `${value.toFixed(1)}%`;
}

function formatLatency(value: number | null) {
  return value === null ? t('mine.operations.notAvailable') : t('mine.operations.latency', { value });
}

function showError(error: unknown, fallbackKey: App.I18n.I18nKey) {
  if (error instanceof ApiError) {
    window.$message?.error(
      error.traceId ? `${error.message} (${t('mine.common.traceId', { traceId: error.traceId })})` : error.message
    );
  } else {
    window.$message?.error(t(fallbackKey));
  }
}
</script>

<template>
  <NFlex vertical :size="16">
    <NPageHeader :title="t('mine.operations.title')" :subtitle="t('mine.operations.subtitle')">
      <template #extra>
        <NButton :loading="historyLoading" @click="loadHistory">{{ t('common.refresh') }}</NButton>
      </template>
    </NPageHeader>

    <NCard size="small">
      <NFlex justify="space-between" align="center" :wrap="true">
        <NFlex align="center" :size="12">
          <NTag :type="overallType()" :bordered="false" size="large">
            {{ statusLabel(overview?.status || 'UNKNOWN') }}
          </NTag>
          <NText v-if="overview" strong>
            {{
              t('mine.operations.summary', {
                healthy: overview.healthy,
                down: overview.down + overview.degraded,
                unknown: overview.unknown
              })
            }}
          </NText>
        </NFlex>
        <NFlex align="center">
          <NText v-if="overview" depth="3">
            {{ t('mine.operations.generatedAt', { time: formatTime(overview.generatedAt) }) }}
          </NText>
          <NButton type="primary" :loading="checkLoading" @click="runImmediateCheck">
            {{ t('mine.operations.action.checkNow') }}
          </NButton>
        </NFlex>
      </NFlex>
    </NCard>

    <NAlert
      v-if="historyError || overview?.dataStatus === 'UNAVAILABLE'"
      type="warning"
      :title="t('mine.operations.historyUnavailableTitle')"
    >
      {{ t('mine.operations.historyUnavailableDescription') }}
    </NAlert>

    <NFlex justify="space-between" align="center" :wrap="true">
      <NButtonGroup>
        <NButton
          v-for="value in ranges"
          :key="value"
          :type="range === value ? 'primary' : 'default'"
          :secondary="range === value"
          @click="selectRange(value)"
        >
          {{ rangeLabel(value) }}
        </NButton>
      </NButtonGroup>
      <NFlex :wrap="true">
        <NInput v-model:value="keyword" clearable :placeholder="t('mine.operations.filter.search')" class="w-220px" />
        <NSelect
          v-model:value="statusFilter"
          clearable
          :options="statusOptions"
          :placeholder="t('mine.operations.filter.status')"
          class="w-150px"
        />
        <NSelect
          v-model:value="categoryFilter"
          clearable
          :options="categoryOptions"
          :placeholder="t('mine.operations.filter.category')"
          class="w-170px"
        />
      </NFlex>
    </NFlex>

    <NCard :title="t('mine.operations.historyTitle')" size="small">
      <NDataTable
        :columns="columns"
        :data="filteredItems"
        :loading="historyLoading"
        :row-key="item => item.key"
        :scroll-x="980"
        :bordered="false"
      />
    </NCard>

    <NCard v-if="immediate" :title="t('mine.operations.immediateTitle')" size="small">
      <NFlex vertical :size="10">
        <NFlex v-for="item in immediateItems" :key="item.key" justify="space-between" align="center">
          <div>
            <NText strong>{{ itemName(item.key) }}</NText>
            <NText depth="3" class="ml-12px">{{ detailLabel(item.summary) }}</NText>
          </div>
          <NFlex align="center">
            <NTag :type="statusType(item.status)" size="small" :bordered="false">
              {{ statusLabel(item.status) }}
            </NTag>
            <NText depth="3" class="whitespace-nowrap">{{ t('mine.operations.latency', { value: item.latencyMillis }) }}</NText>
          </NFlex>
        </NFlex>
      </NFlex>
    </NCard>
  </NFlex>

  <NDrawer
    :show="Boolean(selectedSeries)"
    :width="760"
    placement="right"
    @update:show="handleTrendDrawerVisibility"
  >
    <NDrawerContent v-if="selectedSeries" closable>
      <template #header>
        <NFlex align="center" :size="10">
          <NText strong>
            {{
              t('mine.operations.trendTitle', {
                item: itemName(selectedSeries.key),
                range: rangeLabel(selectedSeries.range)
              })
            }}
          </NText>
          <NTag v-if="selectedItem" :type="statusType(selectedItem.status)" size="small" :bordered="false">
            {{ statusLabel(selectedItem.status) }}
          </NTag>
        </NFlex>
      </template>

      <NFlex vertical :size="20">
        <NFlex v-if="selectedItem" justify="space-between" :wrap="true">
          <NFlex vertical :size="2">
            <NText depth="3">{{ t('mine.operations.column.category') }}</NText>
            <NText strong>{{ categoryLabel(selectedItem.category) }}</NText>
          </NFlex>
          <NFlex vertical :size="2">
            <NText depth="3">{{ t('mine.operations.column.availability') }}</NText>
            <NText strong>{{ formatAvailability(selectedItem.availabilityPercent) }}</NText>
          </NFlex>
          <NFlex vertical :size="2">
            <NText depth="3">{{ t('mine.operations.column.lastChanged') }}</NText>
            <NText strong>
              {{
                selectedItem.lastChangedAt
                  ? formatTime(selectedItem.lastChangedAt)
                  : t('mine.operations.notAvailable')
              }}
            </NText>
          </NFlex>
        </NFlex>

        <NDivider />
        <OperationsTrendChart :series="selectedSeries" :loading="seriesLoading" />
      </NFlex>
    </NDrawerContent>
  </NDrawer>
</template>
