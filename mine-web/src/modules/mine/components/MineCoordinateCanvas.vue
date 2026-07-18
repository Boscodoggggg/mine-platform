<script setup lang="ts">
import { computed, watch } from 'vue';
import { NEmpty, NSpin, useThemeVars } from 'naive-ui';
import { useI18n } from 'vue-i18n';
import { useEcharts } from '@/hooks/common/echarts';
import type { LocationTrajectory } from '../api/mine-api';
import type { MineLocationItem } from '../composables/useMineLocationRealtime';

const props = defineProps<{
  items: MineLocationItem[];
  trajectory: LocationTrajectory['items'];
  selectedTagId: string | null;
  loading: boolean;
}>();

const { t, locale } = useI18n();
const themeVars = useThemeVars();
const hasData = computed(() => props.items.length > 0 || props.trajectory.length > 0);

const { domRef, updateOptions } = useEcharts(() => ({
  animationDuration: 180,
  animationDurationUpdate: 120,
  tooltip: { trigger: 'item' },
  grid: { left: 64, right: 32, top: 32, bottom: 52 },
  xAxis: {
    type: 'value',
    name: t('mine.location.canvas.xAxis'),
    nameTextStyle: { color: themeVars.value.textColor3 },
    axisLabel: { color: themeVars.value.textColor3 },
    axisLine: { lineStyle: { color: themeVars.value.dividerColor } },
    splitLine: { lineStyle: { color: themeVars.value.dividerColor } }
  },
  yAxis: {
    type: 'value',
    name: t('mine.location.canvas.yAxis'),
    nameTextStyle: { color: themeVars.value.textColor3 },
    axisLabel: { color: themeVars.value.textColor3 },
    axisLine: { lineStyle: { color: themeVars.value.dividerColor } },
    splitLine: { lineStyle: { color: themeVars.value.dividerColor } }
  },
  series: [
    {
      name: t('mine.location.canvas.trajectory'),
      type: 'line',
      showSymbol: false,
      connectNulls: false,
      color: themeVars.value.primaryColor,
      lineStyle: { width: 2 },
      data: [] as number[][]
    },
    {
      name: t('mine.location.canvas.current'),
      type: 'scatter',
      symbolSize: 14,
      data: [] as Array<{
        name: string;
        value: [number, number];
        itemStyle: { color: string; borderColor: string; borderWidth: number };
      }>
    }
  ]
}));

watch(
  [() => props.items, () => props.trajectory, () => props.selectedTagId, locale, themeVars],
  () => {
    updateOptions(options => {
      options.xAxis.name = t('mine.location.canvas.xAxis');
      options.yAxis.name = t('mine.location.canvas.yAxis');
      options.xAxis.nameTextStyle = { color: themeVars.value.textColor3 };
      options.yAxis.nameTextStyle = { color: themeVars.value.textColor3 };
      options.xAxis.axisLabel = { color: themeVars.value.textColor3 };
      options.yAxis.axisLabel = { color: themeVars.value.textColor3 };
      options.xAxis.axisLine = { lineStyle: { color: themeVars.value.dividerColor } };
      options.yAxis.axisLine = { lineStyle: { color: themeVars.value.dividerColor } };
      options.xAxis.splitLine = { lineStyle: { color: themeVars.value.dividerColor } };
      options.yAxis.splitLine = { lineStyle: { color: themeVars.value.dividerColor } };
      options.series[0].name = t('mine.location.canvas.trajectory');
      options.series[0].color = themeVars.value.primaryColor;
      options.series[0].data = props.trajectory.map(point => [point.localX, point.localY]);
      options.series[1].name = t('mine.location.canvas.current');
      options.series[1].data = props.items.map(item => {
        const selected = item.tagId === props.selectedTagId;
        return {
          name: item.tagCode,
          value: [item.localX, item.localY] as [number, number],
          itemStyle: {
            color: selected
              ? themeVars.value.primaryColor
              : item.qualityStatus === 'GOOD'
                ? themeVars.value.successColor
                : themeVars.value.warningColor,
            borderColor: selected ? themeVars.value.primaryColor : themeVars.value.dividerColor,
            borderWidth: selected ? 3 : 1
          }
        };
      });
      return options;
    });
  },
  { immediate: true, deep: true }
);
</script>

<template>
  <NSpin :show="loading">
    <NEmpty v-if="!hasData" :description="t('mine.location.canvas.empty')" />
    <div
      v-else
      ref="domRef"
      class="h-96 w-full overflow-hidden md:h-140"
      role="img"
      :aria-label="t('mine.location.canvas.ariaLabel')"
    ></div>
  </NSpin>
</template>
