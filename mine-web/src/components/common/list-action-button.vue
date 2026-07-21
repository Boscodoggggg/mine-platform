<script setup lang="ts">
import { computed } from 'vue';
import { NButton } from 'naive-ui';

defineOptions({
  name: 'ListActionButton',
  inheritAttrs: false
});

type ActionKind = 'create' | 'search' | 'reset' | 'refresh' | 'columns' | 'delete';

const props = defineProps<{
  kind: ActionKind;
}>();

const buttonType = computed(() => {
  if (props.kind === 'delete') return 'error';
  if (props.kind === 'create' || props.kind === 'search') return 'primary';
  return 'default';
});

const ghost = computed(() => props.kind === 'create' || props.kind === 'delete');
</script>

<template>
  <NButton v-bind="$attrs" size="small" :type="buttonType" :ghost="ghost">
    <template #icon>
      <icon-ic-round-plus v-if="kind === 'create'" class="text-icon" />
      <icon-uil-search v-else-if="kind === 'search'" class="text-icon" />
      <icon-ant-design-reload-outlined v-else-if="kind === 'reset'" class="text-icon" />
      <icon-mdi-refresh v-else-if="kind === 'refresh'" class="text-icon" />
      <icon-ant-design-setting-outlined v-else-if="kind === 'columns'" class="text-icon" />
      <icon-ic-round-delete v-else class="text-icon" />
    </template>
    <slot />
  </NButton>
</template>
