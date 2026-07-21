<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import type { TagProps } from 'naive-ui';
import { NCard, NSpace, NStatistic, NTag, NText } from 'naive-ui';

const props = withDefaults(
  defineProps<{
    propertyId: string;
    label: string;
    value: number | null;
    unit?: string;
    quality?: string;
    receivedAt?: string;
  }>(),
  { unit: '', quality: '', receivedAt: '' }
);

const { t, locale } = useI18n();
const qualityType = computed<TagProps['type']>(() => {
  if (props.quality === 'GOOD') return 'success';
  if (props.quality === 'UNCERTAIN' || props.quality === 'TIME_UNTRUSTED') return 'warning';
  if (props.quality === 'BAD') return 'error';
  return 'default';
});
const qualityLabel = computed(() => {
  const keys: Record<string, App.I18n.I18nKey> = {
    GOOD: 'mine.monitor.quality.good',
    UNCERTAIN: 'mine.monitor.quality.uncertain',
    BAD: 'mine.monitor.quality.bad',
    TIME_UNTRUSTED: 'mine.monitor.quality.timeUntrusted'
  };
  return t(keys[props.quality] || 'mine.monitor.quality.unknown');
});
const formattedValue = computed(() => {
  if (props.value === null || !Number.isFinite(props.value)) return null;
  return new Intl.NumberFormat(locale.value, { maximumFractionDigits: 2, useGrouping: false }).format(props.value);
});
const formattedTime = computed(() => {
  if (!props.receivedAt) return '';
  return new Date(props.receivedAt).toLocaleString(locale.value, { hour12: false });
});
</script>

<template>
  <NCard size="small" embedded>
    <NSpace vertical :size="12">
      <NSpace justify="space-between" align="center" :wrap="false">
        <div class="min-w-0">
          <NText strong class="block truncate">{{ label }}</NText>
          <NText depth="3" class="block truncate text-xs">{{ propertyId }}</NText>
        </div>
        <NTag size="small" :type="qualityType" :bordered="false">{{ qualityLabel }}</NTag>
      </NSpace>

      <NStatistic v-if="formattedValue !== null" :value="formattedValue">
        <template v-if="unit" #suffix>
          <NText depth="3" class="text-sm">{{ unit }}</NText>
        </template>
      </NStatistic>
      <NText v-else depth="3">{{ t('mine.monitor.waiting') }}</NText>

      <NText v-if="formattedTime" depth="3" class="text-xs">
        {{ t('mine.monitor.updatedAt', { time: formattedTime }) }}
      </NText>
    </NSpace>
  </NCard>
</template>
