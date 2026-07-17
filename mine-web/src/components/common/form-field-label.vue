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
const hovered = ref(false);
const focused = ref(false);

function syncHelpVisible() {
  helpVisible.value = hovered.value || focused.value;
}

function dismissHelp() {
  hovered.value = false;
  focused.value = false;
  helpVisible.value = false;
}
</script>

<template>
  <NSpace align="center" :size="4">
    <span>{{ t(labelKey) }}</span>
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
          @mouseenter="hovered = true; syncHelpVisible()"
          @mouseleave="hovered = false; syncHelpVisible()"
          @focus="focused = true; syncHelpVisible()"
          @blur="focused = false; syncHelpVisible()"
          @keydown.esc.stop="dismissHelp"
        >
          <span aria-hidden="true">?</span>
        </NButton>
      </template>
      <span :id="helpId">{{ t(helpKey) }}</span>
    </NTooltip>
  </NSpace>
</template>
