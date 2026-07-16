<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  NAlert,
  NButton,
  NCard,
  NDescriptions,
  NDescriptionsItem,
  NEmpty,
  NFlex,
  NSpin,
  NStatistic,
  NTag,
  NText
} from 'naive-ui';
import type { EffectiveStrategy } from '@/modules/device/api/strategy';

interface StrategyProblem {
  code: string;
  message: string;
  traceId?: string;
  details?: Record<string, unknown>;
}

const props = defineProps<{
  deviceName?: string | null;
  loading: boolean;
  effective?: EffectiveStrategy | null;
  problem?: StrategyProblem | null;
}>();

defineEmits<{ refresh: [] }>();

const { t } = useI18n();
const threshold = computed(() => props.effective?.values.offlineThresholdSeconds ?? null);
const source = computed(() => props.effective?.sources.offlineThresholdSeconds || null);
const problemDetails = computed(() => formatDetails(props.problem?.details));
const hasConflict = computed(
  () => props.problem?.code === 'STRATEGY-409-001' || Boolean(props.effective?.conflicts.length)
);

function sourceLabel(scopeType?: string) {
  if (scopeType === 'PRODUCT') return t('mine.strategy.source.product');
  if (scopeType === 'GROUP') return t('mine.strategy.source.group');
  if (scopeType === 'DEVICE') return t('mine.strategy.source.device');
  return t('mine.strategy.source.unknown');
}

function formatDetails(details?: Record<string, unknown>) {
  if (!details) return '';
  return Object.entries(details)
    .map(([key, value]) => `${key}: ${formatValue(value)}`)
    .join(' · ');
}

function formatValue(value: unknown): string {
  if (Array.isArray(value)) return value.map(formatValue).join(', ');
  if (value && typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>)
      .map(([key, nested]) => `${key}=${formatValue(nested)}`)
      .join(', ');
  }
  return String(value ?? '');
}
</script>

<template>
  <NCard :title="t('mine.strategy.section.effective')" :bordered="false" size="small">
    <template #header-extra>
      <NButton
        quaternary
        type="primary"
        size="small"
        :disabled="!deviceName"
        :loading="loading"
        data-action="refresh-effective"
        @click="$emit('refresh')"
      >
        {{ t('mine.strategy.action.refresh') }}
      </NButton>
    </template>

    <NSpin :show="loading">
      <NFlex v-if="effective || problem" vertical :size="14">
        <NFlex justify="space-between" align="center" :wrap="true">
          <NFlex vertical :size="2">
            <NText depth="3">{{ t('mine.strategy.field.device') }}</NText>
            <NText strong>{{ deviceName || t('mine.common.empty') }}</NText>
          </NFlex>
          <NTag :type="effective?.valid && !problem ? 'success' : 'error'" :bordered="false" round>
            {{ effective?.valid && !problem ? t('mine.strategy.status.valid') : t('mine.strategy.status.invalid') }}
          </NTag>
        </NFlex>

        <NAlert
          v-if="problem"
          :title="hasConflict ? t('mine.strategy.error.conflictTitle') : t('mine.strategy.error.title')"
          type="error"
          :bordered="false"
        >
          <NFlex vertical :size="4">
            <NText>{{ problem.message }}</NText>
            <NText code>{{ t('mine.strategy.error.code', { code: problem.code }) }}</NText>
            <NText v-if="problem.traceId" depth="3">{{ t('mine.common.traceId', { traceId: problem.traceId }) }}</NText>
            <NText v-if="problemDetails" depth="3">
              {{ t('mine.strategy.error.details', { details: problemDetails }) }}
            </NText>
          </NFlex>
        </NAlert>

        <NAlert
          v-for="conflict in effective?.conflicts || []"
          :key="`${conflict.field}-${conflict.groupPriority}`"
          :title="t('mine.strategy.error.conflictTitle')"
          type="warning"
          :bordered="false"
        >
          {{
            t('mine.strategy.error.details', {
              details: `${conflict.field} · ${t('mine.strategy.text.priority', { priority: conflict.groupPriority })} · ${conflict.groupIds.join(', ')}`
            })
          }}
        </NAlert>

        <NStatistic v-if="threshold !== null" :label="t('mine.strategy.field.threshold')" :value="threshold">
          <template #suffix>{{ t('mine.strategy.text.seconds') }}</template>
        </NStatistic>

        <NDescriptions v-if="source" :column="1" label-placement="left" bordered size="small">
          <NDescriptionsItem :label="t('mine.strategy.field.source')">
            <NFlex align="center" :wrap="true">
              <NTag type="primary" :bordered="false">{{ sourceLabel(source.scopeType) }}</NTag>
              <NText v-if="source.groupPriority !== null && source.groupPriority !== undefined" depth="3">
                {{ t('mine.strategy.source.priority', { priority: source.groupPriority }) }}
              </NText>
            </NFlex>
          </NDescriptionsItem>
          <NDescriptionsItem :label="t('mine.strategy.field.scope')">
            <NText code class="break-all">{{ source.scopeId }}</NText>
          </NDescriptionsItem>
          <NDescriptionsItem :label="t('mine.strategy.field.release')">
            <NText code class="break-all">{{ source.releaseId }}</NText>
          </NDescriptionsItem>
        </NDescriptions>

        <NFlex v-if="effective?.contentHash" vertical :size="4">
          <NText depth="3">{{ t('mine.strategy.field.hash') }}</NText>
          <NText code class="break-all">{{ effective.contentHash }}</NText>
        </NFlex>
      </NFlex>

      <NEmpty v-else :description="t('mine.strategy.empty.effective')" />
    </NSpin>
  </NCard>
</template>
