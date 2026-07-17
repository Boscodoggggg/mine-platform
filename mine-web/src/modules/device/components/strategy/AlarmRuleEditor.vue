<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import type { SelectOption } from 'naive-ui';
import {
  NAlert,
  NButton,
  NCard,
  NCheckbox,
  NEmpty,
  NFlex,
  NFormItem,
  NGi,
  NGrid,
  NInput,
  NInputNumber,
  NSelect,
  NTag,
  NText
} from 'naive-ui';
import type { AlarmQualityRule, AlarmRule } from '@/modules/device/api/strategy';

const props = withDefaults(
  defineProps<{
    modelValue: AlarmRule[];
    readonly?: boolean;
  }>(),
  { readonly: false }
);
const emit = defineEmits<{ 'update:modelValue': [value: AlarmRule[]] }>();
const { t } = useI18n();

const sourceOptions = computed<SelectOption[]>(() =>
  (['NUMERIC', 'QUALITY'] as const).map(value => ({
    label: t(`mine.strategy.alarm.source.${value.toLowerCase()}`),
    value
  }))
);
const severityOptions = computed<SelectOption[]>(() =>
  (['INFO', 'WARNING', 'CRITICAL'] as const).map(value => ({
    label: t(`mine.strategy.alarm.severity.${value.toLowerCase()}`),
    value
  }))
);
const operatorOptions = computed<SelectOption[]>(() =>
  (['GT', 'GTE', 'LT', 'LTE'] as const).map(value => ({
    label: t(`mine.strategy.alarm.operator.${value.toLowerCase()}`),
    value
  }))
);
const qualityCodeOptions = computed<SelectOption[]>(() =>
  (['FUTURE_TIMESTAMP', 'STALE_TIMESTAMP', 'REQUIRED_MISSING', 'BELOW_MINIMUM', 'ABOVE_MAXIMUM'] as const).map(
    value => ({
      label: t(`mine.strategy.alarm.qualityCode.${value.toLowerCase()}`),
      value
    })
  )
);

function updateRule(index: number, patch: Partial<AlarmRule>) {
  emit(
    'update:modelValue',
    props.modelValue.map((rule, current) => (current === index ? ({ ...rule, ...patch } as AlarmRule) : rule))
  );
}

function changeSource(index: number, sourceType: 'NUMERIC' | 'QUALITY') {
  const current = props.modelValue[index];
  if (!current) return;
  const common = {
    key: current.key,
    name: current.name,
    sourceType,
    severity: current.severity,
    triggerDurationSeconds: current.triggerDurationSeconds,
    recoverDurationSeconds: current.recoverDurationSeconds,
    cooldownSeconds: current.cooldownSeconds,
    enabled: current.enabled
  };
  const replacement: AlarmRule =
    sourceType === 'NUMERIC'
      ? {
          ...common,
          sourceType: 'NUMERIC',
          propertyId: current.propertyId || '',
          operator: 'GT',
          threshold: 0,
          recoverThreshold: 0
        }
      : {
          ...common,
          sourceType: 'QUALITY',
          propertyId: current.propertyId || undefined,
          qualityRuleCode: 'STALE_TIMESTAMP'
        };
  emit(
    'update:modelValue',
    props.modelValue.map((rule, currentIndex) => (currentIndex === index ? replacement : rule))
  );
}

function addRule(sourceType: 'NUMERIC' | 'QUALITY') {
  const common = {
    key: createRuleKey(),
    name: '',
    severity: 'WARNING' as const,
    triggerDurationSeconds: 0,
    recoverDurationSeconds: 0,
    cooldownSeconds: 300,
    enabled: true
  };
  const rule: AlarmRule =
    sourceType === 'NUMERIC'
      ? {
          ...common,
          sourceType: 'NUMERIC',
          propertyId: '',
          operator: 'GT',
          threshold: 0,
          recoverThreshold: 0
        }
      : { ...common, sourceType: 'QUALITY', qualityRuleCode: 'STALE_TIMESTAMP' };
  emit('update:modelValue', [...props.modelValue, rule]);
}

function removeRule(index: number) {
  emit(
    'update:modelValue',
    props.modelValue.filter((_rule, current) => current !== index)
  );
}

function copyRule(index: number) {
  const source = props.modelValue[index];
  if (!source) return;
  const copied = {
    ...source,
    key: createRuleKey(),
    name: `${source.name}${t('mine.strategy.alarm.copySuffix')}`.slice(0, 100)
  };
  const next = [...props.modelValue];
  next.splice(index + 1, 0, copied);
  emit('update:modelValue', next);
}

function moveRule(index: number, offset: -1 | 1) {
  const target = index + offset;
  if (target < 0 || target >= props.modelValue.length) return;
  const next = [...props.modelValue];
  const [moving] = next.splice(index, 1);
  if (!moving) return;
  next.splice(target, 0, moving);
  emit('update:modelValue', next);
}

function hasInvalidHysteresis(rule: AlarmRule) {
  if (rule.sourceType !== 'NUMERIC') return false;
  return ['GT', 'GTE'].includes(rule.operator)
    ? rule.recoverThreshold > rule.threshold
    : rule.recoverThreshold < rule.threshold;
}

function createRuleKey() {
  const suffix = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `alarm-${suffix}`;
}
</script>

<template>
  <NFlex vertical :size="12">
    <NFlex justify="space-between" align="center" :wrap="true">
      <NFlex vertical :size="2">
        <NText strong>{{ t('mine.strategy.section.alarmRules') }}</NText>
        <NText depth="3">{{ t('mine.strategy.help.alarmRules') }}</NText>
      </NFlex>
      <NFlex v-if="!readonly" :size="8">
        <NButton
          secondary
          type="primary"
          size="small"
          data-action="add-numeric-rule"
          @click="addRule('NUMERIC')"
        >
          {{ t('mine.strategy.action.addNumericAlarmRule') }}
        </NButton>
        <NButton secondary size="small" data-action="add-quality-alarm-rule" @click="addRule('QUALITY')">
          {{ t('mine.strategy.action.addQualityAlarmRule') }}
        </NButton>
      </NFlex>
    </NFlex>

    <NAlert type="warning" :bordered="false">{{ t('mine.strategy.help.alarmAtomicOverride') }}</NAlert>

    <NCard v-for="(rule, index) in modelValue" :key="`${rule.key}-${index}`" size="small" embedded>
      <template #header>
        <NFlex align="center" :wrap="true">
          <NText strong>{{ rule.name || t('mine.strategy.alarm.unnamedRule') }}</NText>
          <NTag size="small" :bordered="false" :type="rule.enabled ? 'success' : 'default'">
            {{ rule.enabled ? t('mine.strategy.text.enabled') : t('mine.strategy.text.disabled') }}
          </NTag>
        </NFlex>
      </template>
      <template v-if="!readonly" #header-extra>
        <NFlex :size="4">
          <NButton quaternary size="small" data-action="copy-alarm-rule" @click="copyRule(index)">
            {{ t('mine.strategy.action.copyAlarmRule') }}
          </NButton>
          <NButton
            quaternary
            size="small"
            :disabled="index === 0"
            data-action="move-alarm-rule-up"
            @click="moveRule(index, -1)"
          >
            {{ t('mine.strategy.action.moveAlarmRuleUp') }}
          </NButton>
          <NButton
            quaternary
            size="small"
            :disabled="index === modelValue.length - 1"
            data-action="move-alarm-rule-down"
            @click="moveRule(index, 1)"
          >
            {{ t('mine.strategy.action.moveAlarmRuleDown') }}
          </NButton>
          <NButton
            quaternary
            type="error"
            size="small"
            data-action="remove-alarm-rule"
            @click="removeRule(index)"
          >
            {{ t('mine.strategy.action.removeAlarmRule') }}
          </NButton>
        </NFlex>
      </template>

      <NGrid responsive="screen" cols="1 s:12" :x-gap="12" :y-gap="2">
        <NGi span="1 s:4">
          <NFormItem :label="t('mine.strategy.field.alarmRuleKey')">
            <NInput
              :value="rule.key"
              :disabled="readonly"
              :placeholder="t('mine.strategy.placeholder.alarmRuleKey')"
              @update:value="updateRule(index, { key: $event })"
            />
          </NFormItem>
        </NGi>
        <NGi span="1 s:4">
          <NFormItem :label="t('mine.strategy.field.alarmRuleName')">
            <NInput
              :value="rule.name"
              :disabled="readonly"
              :placeholder="t('mine.strategy.placeholder.alarmRuleName')"
              @update:value="updateRule(index, { name: $event })"
            />
          </NFormItem>
        </NGi>
        <NGi span="1 s:2">
          <NFormItem :label="t('mine.strategy.field.alarmSourceType')">
            <NSelect
              :value="rule.sourceType"
              :disabled="readonly"
              :options="sourceOptions"
              data-field="alarm-source-type"
              @update:value="changeSource(index, $event)"
            />
          </NFormItem>
        </NGi>
        <NGi span="1 s:2">
          <NFormItem :label="t('mine.strategy.field.alarmSeverity')">
            <NSelect
              :value="rule.severity"
              :disabled="readonly"
              :options="severityOptions"
              @update:value="updateRule(index, { severity: $event })"
            />
          </NFormItem>
        </NGi>

        <NGi span="1 s:2">
          <NFormItem :label="t('mine.strategy.field.alarmEnabled')">
            <NCheckbox
              :checked="rule.enabled"
              :disabled="readonly"
              @update:checked="updateRule(index, { enabled: $event })"
            >
              {{ t('mine.strategy.text.enabled') }}
            </NCheckbox>
          </NFormItem>
        </NGi>
        <NGi span="1 s:3">
          <NFormItem :label="t('mine.strategy.field.triggerDurationSeconds')">
            <NInputNumber
              :value="rule.triggerDurationSeconds"
              :min="0"
              :max="86400"
              :precision="0"
              :disabled="readonly"
              @update:value="updateRule(index, { triggerDurationSeconds: $event ?? 0 })"
            >
              <template #suffix>{{ t('mine.strategy.text.seconds') }}</template>
            </NInputNumber>
          </NFormItem>
        </NGi>
        <NGi span="1 s:3">
          <NFormItem :label="t('mine.strategy.field.recoverDurationSeconds')">
            <NInputNumber
              :value="rule.recoverDurationSeconds"
              :min="0"
              :max="86400"
              :precision="0"
              :disabled="readonly"
              @update:value="updateRule(index, { recoverDurationSeconds: $event ?? 0 })"
            >
              <template #suffix>{{ t('mine.strategy.text.seconds') }}</template>
            </NInputNumber>
          </NFormItem>
        </NGi>
        <NGi span="1 s:4">
          <NFormItem :label="t('mine.strategy.field.cooldownSeconds')">
            <NInputNumber
              :value="rule.cooldownSeconds"
              :min="0"
              :max="604800"
              :precision="0"
              :disabled="readonly"
              @update:value="updateRule(index, { cooldownSeconds: $event ?? 0 })"
            >
              <template #suffix>{{ t('mine.strategy.text.seconds') }}</template>
            </NInputNumber>
          </NFormItem>
        </NGi>

        <template v-if="rule.sourceType === 'NUMERIC'">
          <NGi span="1 s:4">
            <NFormItem :label="t('mine.strategy.field.propertyId')">
              <NInput
                :value="rule.propertyId"
                :disabled="readonly"
                :placeholder="t('mine.strategy.placeholder.propertyId')"
                @update:value="updateRule(index, { propertyId: $event })"
              />
            </NFormItem>
          </NGi>
          <NGi span="1 s:2">
            <NFormItem :label="t('mine.strategy.field.alarmOperator')">
              <NSelect
                :value="rule.operator"
                :disabled="readonly"
                :options="operatorOptions"
                data-field="alarm-operator"
                @update:value="updateRule(index, { operator: $event })"
              />
            </NFormItem>
          </NGi>
          <NGi span="1 s:3">
            <NFormItem :label="t('mine.strategy.field.alarmThreshold')">
              <NInputNumber
                :value="rule.threshold"
                :min="-1000000000000000"
                :max="1000000000000000"
                :precision="12"
                :disabled="readonly"
                @update:value="updateRule(index, { threshold: $event ?? 0 })"
              />
            </NFormItem>
          </NGi>
          <NGi span="1 s:3">
            <NFormItem :label="t('mine.strategy.field.recoverThreshold')">
              <NInputNumber
                :value="rule.recoverThreshold"
                :min="-1000000000000000"
                :max="1000000000000000"
                :precision="12"
                :disabled="readonly"
                @update:value="updateRule(index, { recoverThreshold: $event ?? 0 })"
              />
            </NFormItem>
          </NGi>
          <NGi v-if="hasInvalidHysteresis(rule)" span="1 s:12">
            <NAlert type="warning" :bordered="false">{{ t('mine.strategy.help.alarmHysteresis') }}</NAlert>
          </NGi>
        </template>
        <template v-else>
          <NGi span="1 s:5">
            <NFormItem :label="t('mine.strategy.field.qualityRuleCode')">
              <NSelect
                :value="rule.qualityRuleCode"
                :disabled="readonly"
                :options="qualityCodeOptions"
                @update:value="updateRule(index, { qualityRuleCode: $event } as Partial<AlarmQualityRule>)"
              />
            </NFormItem>
          </NGi>
          <NGi span="1 s:7">
            <NFormItem :label="t('mine.strategy.field.optionalPropertyId')">
              <NInput
                :value="rule.propertyId || ''"
                :disabled="readonly"
                :placeholder="t('mine.strategy.placeholder.optionalPropertyId')"
                @update:value="updateRule(index, { propertyId: $event || undefined } as Partial<AlarmQualityRule>)"
              />
            </NFormItem>
          </NGi>
        </template>
      </NGrid>
    </NCard>
    <NEmpty v-if="!modelValue.length" :description="t('mine.strategy.empty.alarmRules')" />
  </NFlex>
</template>
