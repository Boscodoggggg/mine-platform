<script setup lang="ts">
import { computed } from 'vue';
import { NEmpty, NText } from 'naive-ui';

defineOptions({ name: 'OperationsTrendChart' });

interface TelemetryBucket {
  hour: string;
  count: number;
}

const props = defineProps<{
  buckets: TelemetryBucket[];
  locale: string;
  emptyText: string;
  zeroText: string;
  chartLabel: string;
}>();

const hasBuckets = computed(() => props.buckets.length > 0);
const hasReceivedData = computed(() => props.buckets.some(bucket => bucket.count > 0));
const maxValue = computed(() => Math.max(...props.buckets.map(bucket => bucket.count), 1));
const yTicks = computed(() => [maxValue.value, maxValue.value / 2, 0]);
const points = computed(() => {
  if (!props.buckets.length) return '';

  return props.buckets
    .map((bucket, index) => {
      const x = 48 + (index * 560) / Math.max(props.buckets.length - 1, 1);
      const y = 132 - (bucket.count / maxValue.value) * 96;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
});
const labels = computed(() =>
  props.buckets
    .map((bucket, index) => ({
      index,
      x: 48 + (index * 560) / Math.max(props.buckets.length - 1, 1),
      text: new Date(bucket.hour).toLocaleTimeString(props.locale, { hour: '2-digit' })
    }))
    .filter((_, index) => index % 4 === 0 || index === props.buckets.length - 1)
);
</script>

<template>
  <NText tag="div" depth="3" class="relative h-210px w-full" data-testid="home-telemetry-chart">
    <svg class="h-full w-full" viewBox="0 0 640 180" role="img" :aria-label="chartLabel">
      <g v-for="(tick, index) in yTicks" :key="index">
        <line x1="48" :y1="36 + index * 48" x2="608" :y2="36 + index * 48" stroke="currentColor" opacity="0.2" />
        <text x="4" :y="40 + index * 48" fill="currentColor" font-size="11" data-testid="telemetry-y-tick">
          {{ tick }}
        </text>
      </g>
      <line x1="48" y1="132" x2="608" y2="132" stroke="currentColor" opacity="0.45" />
      <polyline
        v-if="buckets.length"
        :points="points"
        fill="none"
        stroke="currentColor"
        stroke-linecap="round"
        stroke-linejoin="round"
        stroke-width="2"
        class="text-primary"
      />
      <circle
        v-for="(bucket, index) in buckets"
        :key="bucket.hour"
        :cx="48 + (index * 560) / Math.max(buckets.length - 1, 1)"
        :cy="132 - (bucket.count / maxValue) * 96"
        r="2.5"
        fill="currentColor"
        class="text-primary"
      >
        <title>{{ new Date(bucket.hour).toLocaleString(locale, { hour12: false }) }}: {{ bucket.count }}</title>
      </circle>
      <text
        v-for="label in labels"
        :key="label.index"
        :x="label.x"
        y="160"
        fill="currentColor"
        font-size="11"
        text-anchor="middle"
      >
        {{ label.text }}
      </text>
    </svg>
    <div v-if="!hasBuckets" class="absolute inset-0 flex items-center justify-center pb-4">
      <NEmpty size="small" :description="emptyText" />
    </div>
    <div v-else-if="!hasReceivedData" class="absolute inset-0 flex items-center justify-center pb-4">
      <NText depth="3">{{ zeroText }}</NText>
    </div>
  </NText>
</template>
