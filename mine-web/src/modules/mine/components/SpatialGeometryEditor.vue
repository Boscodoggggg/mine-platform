<script setup lang="ts">
import { computed, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  NAlert,
  NButton,
  NEmpty,
  NFlex,
  NInputNumber,
  NList,
  NListItem,
  NSpin,
  NTag,
  NText,
  useThemeVars
} from 'naive-ui';
import { useEcharts } from '@/hooks/common/echarts';

export interface SpatialGeometryPoint {
  sequenceNumber: number;
  x: number;
  y: number;
}

type GeometryType = 'POINT' | 'POLYLINE' | 'POLYGON';
type CoordinateAxis = 'x' | 'y';
type ValidationKey =
  | 'pointCount'
  | 'polylineCount'
  | 'polygonCount'
  | 'finite'
  | 'duplicate'
  | 'selfIntersection'
  | 'area';

const props = defineProps<{
  modelValue: SpatialGeometryPoint[];
  geometryType: 'POINT' | 'POLYLINE' | 'POLYGON';
  readonly: boolean;
  loading: boolean;
}>();

const emit = defineEmits<{
  'update:modelValue': [points: SpatialGeometryPoint[]];
  validityChange: [valid: boolean];
}>();
const { t, locale } = useI18n();
const themeVars = useThemeVars();
const validationKeys = computed(() => validateGeometry(props.modelValue, props.geometryType));
const validationErrors = computed(() =>
  validationKeys.value.map(key => t(`mine.spatial.geometry.invalid.${key}`))
);
const ariaLabel = computed(() => t(`mine.spatial.geometry.aria.${props.geometryType.toLowerCase()}`));
const chartData = computed(() => {
  const values = props.modelValue.map(point => [point.x, point.y]);
  return props.geometryType === 'POLYGON' && values.length > 0 ? [...values, values[0]] : values;
});

const { domRef, updateOptions } = useEcharts(() => ({
  animationDuration: 180,
  animationDurationUpdate: 120,
  tooltip: { trigger: 'item' },
  grid: { left: 64, right: 28, top: 24, bottom: 48 },
  xAxis: axisOptions(t('mine.spatial.geometry.xAxis')),
  yAxis: axisOptions(t('mine.spatial.geometry.yAxis')),
  series: [seriesOptions()]
}));

watch(
  [() => props.modelValue, () => props.geometryType, locale, themeVars],
  () => {
    updateOptions(options => {
      options.xAxis = axisOptions(t('mine.spatial.geometry.xAxis'));
      options.yAxis = axisOptions(t('mine.spatial.geometry.yAxis'));
      options.series = [seriesOptions()];
      return options;
    });
  },
  { deep: true }
);

watch(
  validationKeys,
  keys => emit('validityChange', keys.length === 0),
  { immediate: true }
);

function axisOptions(name: string) {
  return {
    type: 'value' as const,
    name,
    nameTextStyle: { color: themeVars.value.textColor3 },
    axisLabel: { color: themeVars.value.textColor3 },
    axisLine: { lineStyle: { color: themeVars.value.dividerColor } },
    splitLine: { lineStyle: { color: themeVars.value.dividerColor } }
  };
}

function seriesOptions() {
  if (props.geometryType === 'POINT') {
    return {
      type: 'scatter' as const,
      symbolSize: 16,
      itemStyle: { color: themeVars.value.primaryColor },
      data: chartData.value
    };
  }
  return {
    type: 'line' as const,
    showSymbol: true,
    symbolSize: 10,
    lineStyle: { color: themeVars.value.primaryColor, width: 3 },
    itemStyle: { color: themeVars.value.primaryColor },
    areaStyle:
      props.geometryType === 'POLYGON'
        ? { color: themeVars.value.primaryColorHover, opacity: 0.16 }
        : undefined,
    data: chartData.value
  };
}

function addPoint() {
  if (props.readonly || (props.geometryType === 'POINT' && props.modelValue.length >= 1)) return;
  const last = props.modelValue.at(-1);
  emitPoints([...props.modelValue, { sequenceNumber: props.modelValue.length + 1, x: (last?.x ?? -10) + 10, y: (last?.y ?? -10) + 10 }]);
}

function removePoint(index: number) {
  if (props.readonly) return;
  emitPoints(props.modelValue.filter((_, pointIndex) => pointIndex !== index));
}

function movePoint(index: number, offset: number) {
  if (props.readonly) return;
  const target = index + offset;
  if (target < 0 || target >= props.modelValue.length) return;
  const points = [...props.modelValue];
  const [point] = points.splice(index, 1);
  if (!point) return;
  points.splice(target, 0, point);
  emitPoints(points);
}

function updateCoordinate(index: number, axis: CoordinateAxis, value: number | null) {
  if (props.readonly || value === null || !Number.isFinite(value)) return;
  emitPoints(
    props.modelValue.map((point, pointIndex) =>
      pointIndex === index ? { ...point, [axis]: value } : point
    )
  );
}

function emitPoints(points: SpatialGeometryPoint[]) {
  emit(
    'update:modelValue',
    points.map((point, index) => ({ ...point, sequenceNumber: index + 1 }))
  );
}

function validateGeometry(points: SpatialGeometryPoint[], geometryType: GeometryType): ValidationKey[] {
  if (geometryType === 'POINT' && points.length !== 1) return ['pointCount'];
  if (geometryType === 'POLYLINE' && points.length < 2) return ['polylineCount'];
  if (geometryType === 'POLYGON' && points.length < 3) return ['polygonCount'];
  if (points.some(point => !Number.isFinite(point.x) || !Number.isFinite(point.y))) return ['finite'];
  const unique = new Set(points.map(point => `${point.x}:${point.y}`));
  if (unique.size !== points.length) return ['duplicate'];
  if (geometryType === 'POLYGON' && selfIntersects(points)) return ['selfIntersection'];
  if (geometryType === 'POLYGON' && polygonArea(points) <= 0.01) return ['area'];
  return [];
}

function polygonArea(points: SpatialGeometryPoint[]) {
  return Math.abs(
    points.reduce((sum, point, index) => {
      const next = points[(index + 1) % points.length]!;
      return sum + point.x * next.y - next.x * point.y;
    }, 0) / 2
  );
}

function selfIntersects(points: SpatialGeometryPoint[]) {
  for (let first = 0; first < points.length; first += 1) {
    for (let second = first + 1; second < points.length; second += 1) {
      if (second === first + 1 || (first === 0 && second === points.length - 1)) continue;
      if (
        segmentsIntersect(
          points[first]!,
          points[(first + 1) % points.length]!,
          points[second]!,
          points[(second + 1) % points.length]!
        )
      ) {
        return true;
      }
    }
  }
  return false;
}

function segmentsIntersect(
  firstStart: SpatialGeometryPoint,
  firstEnd: SpatialGeometryPoint,
  secondStart: SpatialGeometryPoint,
  secondEnd: SpatialGeometryPoint
) {
  const firstSideStart = cross(firstStart, firstEnd, secondStart);
  const firstSideEnd = cross(firstStart, firstEnd, secondEnd);
  const secondSideStart = cross(secondStart, secondEnd, firstStart);
  const secondSideEnd = cross(secondStart, secondEnd, firstEnd);
  return (
    opposite(firstSideStart, firstSideEnd) && opposite(secondSideStart, secondSideEnd)
  ) || onSegment(firstStart, firstEnd, secondStart, firstSideStart)
    || onSegment(firstStart, firstEnd, secondEnd, firstSideEnd)
    || onSegment(secondStart, secondEnd, firstStart, secondSideStart)
    || onSegment(secondStart, secondEnd, firstEnd, secondSideEnd);
}

function cross(start: SpatialGeometryPoint, end: SpatialGeometryPoint, point: SpatialGeometryPoint) {
  return (end.x - start.x) * (point.y - start.y) - (end.y - start.y) * (point.x - start.x);
}

function opposite(first: number, second: number) {
  const epsilon = 1e-9;
  return (first > epsilon && second < -epsilon)
    || (first < -epsilon && second > epsilon);
}

function onSegment(
  start: SpatialGeometryPoint,
  end: SpatialGeometryPoint,
  point: SpatialGeometryPoint,
  area: number
) {
  const epsilon = 1e-9;
  return Math.abs(area) <= epsilon
    && point.x >= Math.min(start.x, end.x) - epsilon
    && point.x <= Math.max(start.x, end.x) + epsilon
    && point.y >= Math.min(start.y, end.y) - epsilon
    && point.y <= Math.max(start.y, end.y) + epsilon;
}

defineExpose({ addPoint, removePoint, movePoint, updateCoordinate, validationErrors });
</script>

<template>
  <NFlex vertical :size="16">
    <NSpin :show="loading">
      <NEmpty
        v-if="modelValue.length === 0"
        :description="t('mine.spatial.geometry.empty')"
      />
      <div
        v-else
        ref="domRef"
        class="h-72 w-full overflow-hidden md:h-96"
        role="img"
        :aria-label="ariaLabel"
      ></div>
    </NSpin>

    <NAlert v-if="validationErrors.length" type="error">
      <template #header>{{ t('mine.spatial.geometry.invalidTitle') }}</template>
      <div v-for="error in validationErrors" :key="error">{{ error }}</div>
    </NAlert>
    <NAlert v-if="readonly" type="info">
      {{ t('mine.spatial.geometry.historyReadonly') }}
    </NAlert>

    <NList v-if="modelValue.length" bordered>
      <NListItem v-for="(point, index) in modelValue" :key="`${point.sequenceNumber}-${index}`">
        <NFlex data-responsive-stack class="flex-col md:flex-row" align="center" :wrap="false">
          <NTag round :bordered="false">{{ point.sequenceNumber }}</NTag>
          <NFlex class="w-full flex-1" align="center" :wrap="false">
            <NText depth="3">{{ t('mine.spatial.geometry.x') }}</NText>
            <NInputNumber
              :value="point.x"
              :disabled="readonly"
              class="w-full"
              :aria-label="t('mine.spatial.geometry.coordinateAria', { sequence: point.sequenceNumber, axis: 'X' })"
              @update:value="value => updateCoordinate(index, 'x', value)"
            />
          </NFlex>
          <NFlex class="w-full flex-1" align="center" :wrap="false">
            <NText depth="3">{{ t('mine.spatial.geometry.y') }}</NText>
            <NInputNumber
              :value="point.y"
              :disabled="readonly"
              class="w-full"
              :aria-label="t('mine.spatial.geometry.coordinateAria', { sequence: point.sequenceNumber, axis: 'Y' })"
              @update:value="value => updateCoordinate(index, 'y', value)"
            />
          </NFlex>
          <NFlex v-if="!readonly" :wrap="false">
            <NButton size="small" quaternary :disabled="index === 0" @click="movePoint(index, -1)">
              {{ t('mine.spatial.geometry.moveUp') }}
            </NButton>
            <NButton
              size="small"
              quaternary
              :disabled="index === modelValue.length - 1"
              @click="movePoint(index, 1)"
            >
              {{ t('mine.spatial.geometry.moveDown') }}
            </NButton>
            <NButton size="small" quaternary type="error" @click="removePoint(index)">
              {{ t('mine.spatial.geometry.removePoint') }}
            </NButton>
          </NFlex>
        </NFlex>
      </NListItem>
    </NList>

    <NButton
      v-if="!readonly"
      data-action="add-point"
      dashed
      :disabled="geometryType === 'POINT' && modelValue.length >= 1"
      @click="addPoint"
    >
      {{ t('mine.spatial.geometry.addPoint') }}
    </NButton>
  </NFlex>
</template>
