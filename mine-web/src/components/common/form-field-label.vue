<script setup lang="ts">
import { ref, useId } from 'vue';
import { useI18n } from 'vue-i18n';
import { NButton, NSpace, NTooltip } from 'naive-ui';

defineOptions({ name: 'FormFieldLabel' });

defineProps<{
  labelKey: App.I18n.I18nKey;
  helpKey: App.I18n.I18nKey;
}>();

const { t } = useI18n();
const helpId = useId();
const helpVisible = ref(false);
</script>

<template>
  <NSpace align="center" :size="4">
    <span>{{ t(labelKey) }}</span>
    <span :id="helpId" class="sr-only">{{ t(helpKey) }}</span>
    <NTooltip placement="top" trigger="manual" :show="helpVisible">
      <template #trigger>
        <NButton
          text
          circle
          size="tiny"
          :aria-label="t('mine.common.fieldHelp', { field: t(labelKey) })"
          :aria-describedby="helpId"
          :aria-expanded="helpVisible"
          :data-field-help="helpKey"
          @mouseenter="helpVisible = true"
          @mouseleave="helpVisible = false"
        >
          <span aria-hidden="true">?</span>
        </NButton>
      </template>
      <span>{{ t(helpKey) }}</span>
    </NTooltip>
  </NSpace>
</template>
