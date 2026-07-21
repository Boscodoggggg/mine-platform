<script setup lang="ts">
import { computed, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { NAlert, NButton, NFlex, NFormItem, NGi, NGrid, NInputNumber, NSwitch, NText } from 'naive-ui';
import type { RetentionClass, RetentionPolicy } from '@/modules/device/api/strategy';

interface RetentionPreset {
  retentionClass: RetentionClass;
  minimum: number;
  maximum: number;
  rawTelemetryDays: number;
  qualityEvidenceDays: number;
}

const props = defineProps<{
  modelValue: RetentionPolicy;
  readonly?: boolean;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: RetentionPolicy];
  validityChange: [valid: boolean];
}>();

const { t } = useI18n();
const presets: RetentionPreset[] = [
  { retentionClass: 'SHORT', minimum: 7, maximum: 30, rawTelemetryDays: 7, qualityEvidenceDays: 30 },
  { retentionClass: 'STANDARD', minimum: 31, maximum: 180, rawTelemetryDays: 90, qualityEvidenceDays: 180 },
  { retentionClass: 'LONG', minimum: 181, maximum: 730, rawTelemetryDays: 365, qualityEvidenceDays: 730 },
  { retentionClass: 'ARCHIVE', minimum: 731, maximum: 3650, rawTelemetryDays: 1825, qualityEvidenceDays: 3650 }
];
const activePreset = computed(
  () => presets.find(preset => preset.retentionClass === props.modelValue.retentionClass) || presets[1]!
);
const valid = computed(() => isValid(props.modelValue));

watch(valid, value => emit('validityChange', value), { immediate: true });

function applyPreset(preset: RetentionPreset) {
  updatePolicy({
    retentionClass: preset.retentionClass,
    rawTelemetryDays: preset.rawTelemetryDays,
    qualityEvidenceDays: preset.qualityEvidenceDays
  });
}

function updatePolicy(update: Partial<RetentionPolicy>) {
  const next = { ...props.modelValue, ...update };
  emit('update:modelValue', next);
  emit('validityChange', isValid(next));
}

function isValid(policy: RetentionPolicy) {
  const preset = presets.find(item => item.retentionClass === policy.retentionClass);
  return Boolean(
    preset &&
      Number.isInteger(policy.rawTelemetryDays) &&
      policy.rawTelemetryDays >= preset.minimum &&
      policy.rawTelemetryDays <= preset.maximum &&
      Number.isInteger(policy.qualityEvidenceDays) &&
      policy.qualityEvidenceDays >= policy.rawTelemetryDays &&
      policy.qualityEvidenceDays <= 3650
  );
}
</script>

<template>
  <NFlex vertical :size="14">
    <NFlex vertical :size="4">
      <NText strong>{{ t('mine.strategy.retention.presetTitle') }}</NText>
      <NText depth="3">{{ t('mine.strategy.retention.presetHelp') }}</NText>
    </NFlex>

    <NGrid responsive="screen" cols="2 s:4" :x-gap="8" :y-gap="8">
      <NGi v-for="preset in presets" :key="preset.retentionClass">
        <NButton
          block
          :type="modelValue.retentionClass === preset.retentionClass ? 'primary' : 'default'"
          :secondary="modelValue.retentionClass === preset.retentionClass"
          :disabled="readonly"
          data-retention-preset
          :data-action="`retention-preset-${preset.retentionClass}`"
          @click="applyPreset(preset)"
        >
          {{ t(`mine.strategy.retention.class.${preset.retentionClass.toLowerCase()}`) }}
        </NButton>
      </NGi>
    </NGrid>

    <NAlert type="info" :bordered="false">
      {{
        t('mine.strategy.retention.classRange', {
          minimum: activePreset.minimum,
          maximum: activePreset.maximum
        })
      }}
    </NAlert>

    <NGrid responsive="screen" cols="1 s:2" :x-gap="12">
      <NGi>
        <NFormItem :label="t('mine.strategy.field.rawTelemetryDays')">
          <NInputNumber
            :value="modelValue.rawTelemetryDays"
            :min="activePreset.minimum"
            :max="activePreset.maximum"
            :disabled="readonly"
            data-field="retention-raw-days"
            @update:value="value => value !== null && updatePolicy({ rawTelemetryDays: value })"
          >
            <template #suffix>{{ t('mine.strategy.text.days') }}</template>
          </NInputNumber>
        </NFormItem>
      </NGi>
      <NGi>
        <NFormItem
          :label="t('mine.strategy.field.qualityEvidenceDays')"
          :validation-status="valid ? undefined : 'error'"
          :feedback="valid ? undefined : t('mine.strategy.validation.retentionEvidence')"
        >
          <NInputNumber
            :value="modelValue.qualityEvidenceDays"
            :min="modelValue.rawTelemetryDays"
            :max="3650"
            :disabled="readonly"
            data-field="retention-quality-days"
            @update:value="value => value !== null && updatePolicy({ qualityEvidenceDays: value })"
          >
            <template #suffix>{{ t('mine.strategy.text.days') }}</template>
          </NInputNumber>
        </NFormItem>
      </NGi>
    </NGrid>

    <NFlex justify="space-between" align="center" :wrap="true">
      <NFlex vertical :size="2">
        <NText strong>{{ t('mine.strategy.field.retentionEnabled') }}</NText>
        <NText depth="3">{{ t('mine.strategy.retention.pauseHelp') }}</NText>
      </NFlex>
      <NSwitch
        :value="modelValue.enabled"
        :disabled="readonly"
        data-field="retention-enabled"
        @update:value="value => updatePolicy({ enabled: value })"
      />
    </NFlex>

    <NAlert :type="modelValue.enabled ? 'warning' : 'info'" :bordered="false">
      {{
        modelValue.enabled
          ? t('mine.strategy.retention.irreversibleRisk')
          : t('mine.strategy.retention.pausedRisk')
      }}
    </NAlert>
  </NFlex>
</template>
