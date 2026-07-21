<script setup lang="ts">
import { computed, watch } from 'vue';
import { NEmpty, NSpin, useThemeVars } from 'naive-ui';
import { useI18n } from 'vue-i18n';
import { useEcharts } from '@/hooks/common/echarts';
import type { OperationsObservabilitySeries } from '@/modules/system/api/operations';

const props = defineProps<{
  series: OperationsObservabilitySeries;
  loading: boolean;
}>();

const { t, locale } = useI18n();
const themeVars = useThemeVars();
const hasPoints = computed(() => props.series.points.length > 0);

const { domRef, updateOptions } = useEcharts(() => ({
  animationDuration: 240,
  tooltip: { trigger: 'axis' },
  grid: { left: 44, right: 36, top: 24, bottom: 36 },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: [] as string[],
    axisLabel: { color: themeVars.value.textColor3 },
    axisLine: { lineStyle: { color: themeVars.value.dividerColor } }
  },
  yAxis: [
    {
      type: 'value',
      min: 0,
      max: 1,
      interval: 1,
      axisLabel: {
        color: themeVars.value.textColor3,
        formatter: (value: number) => (value === 1 ? t('mine.operations.status.up') : t('mine.operations.status.down'))
      },
      splitLine: { lineStyle: { color: themeVars.value.dividerColor } }
    },
    {
      type: 'value',
      axisLabel: { color: themeVars.value.textColor3, formatter: (value: number) => `${value} ms` },
      splitLine: { show: false }
    }
  ],
  series: [
    {
      name: t('mine.operations.column.status'),
      type: 'line',
      step: 'end',
      showSymbol: false,
      connectNulls: false,
      color: themeVars.value.warningColor,
      data: [] as Array<number | null>
    },
    {
      name: t('mine.operations.trendLatency'),
      type: 'line',
      yAxisIndex: 1,
      showSymbol: false,
      connectNulls: false,
      color: themeVars.value.primaryColor,
      data: [] as Array<number | null>
    }
  ]
}));

watch(
  [() => props.series, locale, themeVars],
  () => {
    updateOptions(options => {
      options.xAxis.data = props.series.points.map(point =>
        new Date(point.time).toLocaleTimeString(locale.value, { hour: '2-digit', minute: '2-digit' })
      );
      options.series[0].data = props.series.points.map(point => {
        if (point.status === 'UP') return 1;
        if (point.status === 'DOWN') return 0;
        return null;
      });
      options.series[1].data = props.series.points.map(point => point.latencyMillis);
      options.series[0].color = themeVars.value.warningColor;
      options.series[1].color = themeVars.value.primaryColor;
      return options;
    });
  },
  { immediate: true, deep: true }
);
</script>

<template>
  <NSpin :show="loading">
    <NEmpty v-if="!hasPoints" :description="t('mine.operations.trendEmpty')" />
    <div
      v-else
      ref="domRef"
      class="h-260px w-full overflow-hidden"
      role="img"
      :aria-label="t('mine.operations.trendAriaLabel', { item: series.key })"
    ></div>
  </NSpin>
</template>
