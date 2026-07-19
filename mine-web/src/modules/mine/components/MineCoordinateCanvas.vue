<script setup lang="ts">
import { computed, watch } from 'vue';
import { NEmpty, NSpin, useThemeVars } from 'naive-ui';
import type { LineSeriesOption, ScatterSeriesOption } from 'echarts/charts';
import { useI18n } from 'vue-i18n';
import { useEcharts } from '@/hooks/common/echarts';
import type { LocationTrajectory } from '../api/mine-api';
import type { MineLocationItem } from '../composables/useMineLocationRealtime';
import type { MineSpatialEvent, MineSpatialRegion } from '../composables/useMineSpatialEvents';

const props = withDefaults(
  defineProps<{
    items: MineLocationItem[];
    trajectory: LocationTrajectory['items'];
    regions?: MineSpatialRegion[];
    events?: MineSpatialEvent[];
    showRegions?: boolean;
    showEvents?: boolean;
    selectedTagId: string | null;
    loading: boolean;
  }>(),
  {
    regions: () => [],
    events: () => [],
    showRegions: true,
    showEvents: true
  }
);

const { t, locale } = useI18n();
const themeVars = useThemeVars();
const hasData = computed(
  () =>
    props.items.length > 0 ||
    props.trajectory.length > 0 ||
    (props.showRegions && props.regions.length > 0) ||
    (props.showEvents && props.events.some(event => event.evidence))
);
type CanvasSeries = LineSeriesOption | ScatterSeriesOption;

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
  series: buildSeries()
}));

watch(
  [
    () => props.items,
    () => props.trajectory,
    () => props.regions,
    () => props.events,
    () => props.showRegions,
    () => props.showEvents,
    () => props.selectedTagId,
    locale,
    themeVars
  ],
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
      options.series = buildSeries();
      return options;
    });
  },
  { immediate: true, deep: true }
);

function buildSeries(): CanvasSeries[] {
  const series: CanvasSeries[] = [
    {
      id: 'trajectory',
      name: t('mine.location.canvas.trajectory'),
      type: 'line',
      showSymbol: false,
      connectNulls: false,
      color: themeVars.value.primaryColor,
      lineStyle: { width: 2 },
      z: 2,
      data: props.trajectory.map(point => [point.localX, point.localY])
    }
  ];

  if (props.showRegions) {
    const regionGroups = [
      { id: 'waypoint-regions', shape: 'CIRCLE', lineType: 'dashed' },
      { id: 'geofence-regions', shape: 'POLYGON', lineType: 'solid' }
    ] as const;
    regionGroups.forEach(group => {
      const regions = props.regions.filter(region => region.shape === group.shape);
      if (regions.length === 0) return;
      series.push({
        id: group.id,
        name: t('mine.location.spatial.regions'),
        type: 'line',
        showSymbol: false,
        silent: true,
        connectNulls: false,
        lineStyle: {
          color: themeVars.value.infoColor,
          width: 2,
          type: group.lineType,
          opacity: 0.8
        },
        z: 1,
        data: groupedRegionCoordinates(regions)
      });
    });
  }

  if (props.showEvents) {
    series.push({
      id: 'spatial-events',
      name: t('mine.location.canvas.events'),
      type: 'scatter',
      symbol: 'diamond',
      symbolSize: 20,
      z: 4,
      data: props.events.flatMap(event =>
        event.evidence
          ? [
              {
                name: t(`mine.spatial.enum.eventType.${event.eventType.toLowerCase()}`),
                value: [event.evidence.localX, event.evidence.localY],
                itemStyle: {
                  color: eventColor(event.eventType),
                  borderColor: themeVars.value.dividerColor,
                  borderWidth: 2
                }
              }
            ]
          : []
      )
    });
  }

  series.push({
    id: 'current-locations',
    name: t('mine.location.canvas.current'),
    type: 'scatter',
    symbolSize: 14,
    z: 5,
    data: props.items.map(item => {
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
    })
  });
  return series;
}

function regionCoordinates(region: MineSpatialRegion) {
  if (region.shape === 'POLYGON') {
    const points = region.vertices.map(vertex => [vertex.x, vertex.y]);
    return points.length > 0 ? [...points, points[0]!] : points;
  }
  return Array.from({ length: 49 }, (_unused, index) => {
    const angle = (Math.PI * 2 * index) / 48;
    return [
      region.x + Math.cos(angle) * region.radiusMeters,
      region.y + Math.sin(angle) * region.radiusMeters
    ];
  });
}

function groupedRegionCoordinates(regions: MineSpatialRegion[]) {
  return regions.flatMap((region, index) => [
    ...(index === 0 ? [] : [[null, null]]),
    ...regionCoordinates(region)
  ]);
}

function eventColor(eventType: MineSpatialEvent['eventType']) {
  if (eventType === 'ENTER') return themeVars.value.successColor;
  if (eventType === 'EXIT') return themeVars.value.infoColor;
  return themeVars.value.warningColor;
}
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
