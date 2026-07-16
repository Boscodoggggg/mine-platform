<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRouter } from 'vue-router';
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
  NProgress,
  NSkeleton,
  NSpace,
  NStatistic,
  NTag,
  NText,
  NThing
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { getOperationsDashboard } from '@/modules/dashboard/api/operations';
import type { DashboardAttentionDevice, OperationsOverview } from '@/modules/dashboard/api/operations';
import { useAuthStore } from '@/store/modules/auth';
import OperationsTrendChart from './modules/operations-trend-chart.vue';

const { t, locale } = useI18n();
const router = useRouter();
const authStore = useAuthStore();
const loading = ref(false);
const overview = ref<OperationsOverview | null>(null);
const loadError = ref(false);
const dataUnavailable = ref(false);

const metrics = computed(() => [
  {
    key: 'enabled',
    value: overview.value?.devices.enabled ?? null,
    tone: 'default' as const,
    icon: 'carbon:devices'
  },
  {
    key: 'reporting',
    value: overview.value?.devices.reporting ?? null,
    tone: 'success' as const,
    icon: 'carbon:checkmark-outline'
  },
  {
    key: 'stale',
    value: overview.value?.devices.stale ?? null,
    tone: 'warning' as const,
    icon: 'carbon:warning-alt'
  },
  {
    key: 'todayMessages',
    value: overview.value?.telemetry.todayMessages ?? null,
    tone: 'primary' as const,
    icon: 'carbon:data-structured'
  }
]);

const runningState = computed(() => {
  if (loadError.value || dataUnavailable.value) return 'unavailable';
  if (!overview.value || loading.value) return 'loading';
  if (overview.value.devices.enabled === 0) return 'empty';
  if ((overview.value.devices.stale || 0) + (overview.value.devices.neverReported || 0) > 0) return 'attention';
  return 'normal';
});

const runningTagType = computed(() => {
  if (runningState.value === 'normal') return 'success' as const;
  if (runningState.value === 'attention') return 'warning' as const;
  if (runningState.value === 'unavailable') return 'error' as const;
  return 'default' as const;
});

const distribution = computed(() => {
  const enabled = overview.value?.devices.enabled || 0;
  if (!enabled || overview.value?.dataStatus !== 'AVAILABLE') return [];
  return [
    { key: 'reporting', count: overview.value.devices.reporting || 0, type: 'success' as const },
    { key: 'stale', count: overview.value.devices.stale || 0, type: 'warning' as const },
    { key: 'neverReported', count: overview.value.devices.neverReported || 0, type: 'default' as const }
  ].map(item => ({ ...item, percentage: Math.round((item.count / enabled) * 100) }));
});

const telemetrySummary = computed(() => {
  const counts = overview.value?.telemetry.hourly.map(bucket => bucket.count) ?? [];
  if (loadError.value || dataUnavailable.value) return t('mine.home.telemetry.unavailableSummary');
  if (!counts.length) return t('mine.home.telemetry.emptySummary');

  const total = counts.reduce((sum, count) => sum + count, 0);
  const peak = Math.max(...counts, 0);
  return t('mine.home.telemetry.summary', { total: metricValue(total), peak: metricValue(peak) });
});

const quickActions = computed(() =>
  [
    { key: 'monitor', permission: 'device:telemetry:view', path: '/device/monitor', icon: 'carbon:play-outline' },
    { key: 'devices', permission: 'device:device:view', path: '/device/devices', icon: 'carbon:devices' },
    { key: 'commands', permission: 'command:instance:view', path: '/command/center', icon: 'carbon:code' }
  ].filter(item => hasPermission(authStore.userInfo.buttons, item.permission))
);

onMounted(loadOverview);

async function loadOverview() {
  loading.value = true;
  loadError.value = false;
  try {
    const nextOverview = await getOperationsDashboard();
    dataUnavailable.value = nextOverview.dataStatus === 'UNAVAILABLE';
    if (!dataUnavailable.value || overview.value?.dataStatus !== 'AVAILABLE') {
      overview.value = nextOverview;
    }
  } catch (error) {
    loadError.value = true;
    showError(error);
  } finally {
    loading.value = false;
  }
}

function metricValue(value: number | null) {
  return value === null ? t('mine.home.unavailable') : new Intl.NumberFormat(locale.value).format(value);
}

function formatTime(value: string | null | undefined) {
  if (!value) return t('mine.home.never');
  return new Date(value).toLocaleString(locale.value, { hour12: false });
}

function attentionState(device: DashboardAttentionDevice) {
  return t(`mine.home.attention.state.${device.state === 'STALE' ? 'stale' : 'neverReported'}`);
}

function attentionType(device: DashboardAttentionDevice) {
  return device.state === 'STALE' ? ('warning' as const) : ('default' as const);
}

function open(path: string) {
  return router.push(path);
}

function showError(error: unknown) {
  if (error instanceof ApiError) {
    window.$message?.error(
      error.traceId ? `${error.message} (${t('mine.common.traceId', { traceId: error.traceId })})` : error.message
    );
  } else {
    window.$message?.error(t('mine.home.loadFailed'));
  }
}
</script>

<template>
  <div>
    <NSpace vertical :size="16">
      <NPageHeader :title="t('mine.home.title')" :subtitle="t('mine.home.subtitle')">
        <template #extra>
          <NFlex align="center">
            <NText v-if="overview" depth="3">
              {{ t('mine.home.updatedAt', { time: formatTime(overview.generatedAt) }) }}
            </NText>
            <NTag :type="runningTagType" :bordered="false">
              {{ t(`mine.home.runningState.${runningState}`) }}
            </NTag>
            <NButton :loading="loading" @click="loadOverview">{{ t('common.refresh') }}</NButton>
          </NFlex>
        </template>
      </NPageHeader>

      <NAlert v-if="loadError || dataUnavailable" type="error" :title="t('mine.home.unavailableTitle')">
        {{ t('mine.home.unavailableDescription') }}
      </NAlert>

      <NCard size="small">
        <NGrid responsive="screen" cols="2 m:4" :x-gap="16" :y-gap="16">
          <NGi v-for="metric in metrics" :key="metric.key">
            <NSkeleton v-if="loading && !overview" text :repeat="2" />
            <NFlex v-else align="center" :size="12">
              <NText :type="metric.tone === 'default' ? undefined : metric.tone">
                <SvgIcon :icon="metric.icon" class="text-28px" />
              </NText>
              <div>
                <NStatistic :label="t(`mine.home.metric.${metric.key}.label`)" :value="metricValue(metric.value)" />
                <NText depth="3">{{ t(`mine.home.metric.${metric.key}.description`) }}</NText>
              </div>
            </NFlex>
          </NGi>
        </NGrid>
      </NCard>

      <NGrid responsive="screen" cols="1 l:5" :x-gap="16" :y-gap="16">
        <NGi span="1 l:3">
          <NCard :title="t('mine.home.attention.title')" class="h-full" data-testid="home-attention">
            <template #header-extra>
              <NTag v-if="overview?.attentionDevices.length" type="warning" :bordered="false">
                {{ t('mine.home.attention.count', { count: overview.attentionDevices.length }) }}
              </NTag>
            </template>
            <NSkeleton v-if="loading && !overview" text :repeat="5" />
            <NList v-else-if="overview?.attentionDevices.length" hoverable>
              <NListItem v-for="device in overview.attentionDevices" :key="device.deviceId">
                <NThing :title="device.name" :description="device.deviceCode">
                  <template #header-extra>
                    <NTag :type="attentionType(device)" size="small" :bordered="false">
                      {{ attentionState(device) }}
                    </NTag>
                  </template>
                </NThing>
                <template #suffix>
                  <NText depth="3">
                    {{
                      device.lastSeenAt
                        ? t('mine.home.attention.lastSeen', { time: formatTime(device.lastSeenAt) })
                        : t('mine.home.attention.noTelemetry')
                    }}
                  </NText>
                </template>
              </NListItem>
            </NList>
            <NFlex
              v-else-if="loadError || dataUnavailable"
              vertical
              align="center"
              justify="center"
              :size="12"
              class="min-h-150px py-4"
            >
              <NText depth="3" class="text-28px">
                <SvgIcon icon="carbon:unknown" />
              </NText>
              <NText strong>{{ t('mine.home.attention.unavailable') }}</NText>
              <NText depth="3">{{ t('mine.home.attention.unavailableHint') }}</NText>
            </NFlex>
            <NFlex v-else vertical align="center" justify="center" :size="12" class="min-h-150px py-4">
              <NText type="success" class="text-28px">
                <SvgIcon icon="carbon:checkmark-outline" />
              </NText>
              <NText strong>{{ t('mine.home.attention.empty') }}</NText>
              <NButton
                v-if="hasPermission(authStore.userInfo.buttons, 'device:device:view')"
                data-testid="attention-device-registry"
                @click="open('/device/devices')"
              >
                {{ t('mine.home.quick.devices') }}
              </NButton>
              <NText depth="3">{{ t('mine.home.attention.emptyHint') }}</NText>
            </NFlex>
          </NCard>
        </NGi>

        <NGi span="1 l:2">
          <NCard :title="t('mine.home.distribution.title')" class="h-full">
            <template v-if="distribution.length">
              <NSpace vertical :size="20">
                <div v-for="item in distribution" :key="item.key">
                  <NFlex justify="space-between">
                    <NText>{{ t(`mine.home.distribution.${item.key}`) }}</NText>
                    <NText strong>{{ item.count }}</NText>
                  </NFlex>
                  <NProgress
                    class="mt-2"
                    type="line"
                    :percentage="item.percentage"
                    :status="item.type"
                    :show-indicator="false"
                  />
                </div>
                <NText depth="3">
                  {{ t('mine.home.distribution.freshness', { seconds: overview?.freshnessSeconds }) }}
                </NText>
              </NSpace>
            </template>
            <NEmpty v-else :description="t('mine.home.distribution.empty')" />
          </NCard>
        </NGi>
      </NGrid>

      <NGrid responsive="screen" cols="1 l:5" :x-gap="16" :y-gap="16">
        <NGi span="1 l:3">
          <NCard :title="t('mine.home.telemetry.title')" class="h-full">
            <template #header-extra>
              <NText depth="3">
                {{ t('mine.home.telemetry.latest', { time: formatTime(overview?.telemetry.latestReceivedAt) }) }}
              </NText>
            </template>
            <OperationsTrendChart
              :buckets="overview?.telemetry.hourly ?? []"
              :locale="locale"
              :empty-text="t('mine.home.telemetry.empty')"
              :zero-text="t('mine.home.telemetry.zero')"
              :chart-label="telemetrySummary"
            />
          </NCard>
        </NGi>

        <NGi span="1 l:2">
          <NCard :title="t('mine.home.quick.title')" class="h-full">
            <NSpace vertical :size="12">
              <NButton
                v-for="action in quickActions"
                :key="action.key"
                block
                secondary
                :type="action.key === 'monitor' ? 'primary' : 'default'"
                size="large"
                class="h-auto whitespace-normal py-3"
                data-testid="quick-action"
                :data-action-priority="action.key === 'monitor' ? 'primary' : 'secondary'"
                @click="open(action.path)"
              >
                <NFlex align="center" justify="space-between" class="min-w-0 w-full text-left">
                  <NFlex align="center" :wrap="false" class="min-w-0">
                    <SvgIcon :icon="action.icon" class="text-22px" />
                    <div class="min-w-0">
                      <div class="font-500">{{ t(`mine.home.quick.${action.key}`) }}</div>
                      <NText depth="3" class="block whitespace-normal break-words text-xs leading-relaxed">
                        {{ t(`mine.home.quick.${action.key}Description`) }}
                      </NText>
                    </div>
                  </NFlex>
                  <SvgIcon icon="carbon:chevron-right" />
                </NFlex>
              </NButton>
              <NText v-if="quickActions.length === 0" depth="3">{{ t('mine.home.quick.empty') }}</NText>
            </NSpace>
          </NCard>
        </NGi>
      </NGrid>
    </NSpace>
  </div>
</template>
