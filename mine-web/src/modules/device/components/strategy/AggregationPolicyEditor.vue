<script setup lang="ts">
import { computed, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { SelectOption } from 'naive-ui';
import {
  NAlert,
  NButton,
  NFlex,
  NFormItem,
  NGi,
  NGrid,
  NInput,
  NInputNumber,
  NList,
  NListItem,
  NSelect,
  NSwitch,
  NText
} from 'naive-ui';
import type { AggregationPolicy, AggregationRule } from '@/modules/device/api/strategy';

const props = defineProps<{
  modelValue: AggregationPolicy;
  readonly?: boolean;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: AggregationPolicy];
  validityChange: [valid: boolean];
}>();

const { t } = useI18n();
const bucketValues = [60, 300, 900, 3600, 21600, 86400] as const;
const bucketOptions = computed<SelectOption[]>(() =>
  bucketValues.map(value => ({
    value,
    label: t(`mine.strategy.aggregation.bucket.${value}`)
  }))
);
const minimumCorrection = computed(() => Math.max(300, ...props.modelValue.rules.map(rule => rule.bucketSeconds * 2)));
const duplicateRuleKeys = computed(() => {
  const seen = new Set<string>();
  const duplicates = new Set<string>();
  props.modelValue.rules.forEach(rule => {
    const key = `${rule.propertyId.trim()}::${rule.bucketSeconds}`;
    if (seen.has(key)) duplicates.add(key);
    seen.add(key);
  });
  return duplicates;
});
const valid = computed(() => isValid(props.modelValue));

watch(valid, value => emit('validityChange', value), { immediate: true });

function updatePolicy(update: Partial<AggregationPolicy>) {
  const next = { ...props.modelValue, ...update };
  emit('update:modelValue', next);
  emit('validityChange', isValid(next));
}

function updateRule(index: number, update: Partial<AggregationRule>) {
  updatePolicy({
    rules: props.modelValue.rules.map((rule, position) => (position === index ? { ...rule, ...update } : { ...rule }))
  });
}

function updateBucket(index: number, value: string | number) {
  const bucketSeconds = Number(value);
  if (isBucketSeconds(bucketSeconds)) updateRule(index, { bucketSeconds });
}

function addRule() {
  if (props.modelValue.rules.length >= 100) return;
  updatePolicy({
    rules: [...props.modelValue.rules.map(rule => ({ ...rule })), { propertyId: '', bucketSeconds: 300 }]
  });
}

function removeRule(index: number) {
  updatePolicy({ rules: props.modelValue.rules.filter((_, position) => position !== index) });
}

function isDuplicate(rule: AggregationRule) {
  return duplicateRuleKeys.value.has(`${rule.propertyId.trim()}::${rule.bucketSeconds}`);
}

function isBucketSeconds(value: number): value is AggregationRule['bucketSeconds'] {
  return bucketValues.includes(value as AggregationRule['bucketSeconds']);
}

function isValid(policy: AggregationPolicy) {
  if (
    !Number.isInteger(policy.correctionWindowSeconds) ||
    policy.correctionWindowSeconds < 300 ||
    policy.correctionWindowSeconds > 604800 ||
    policy.rules.length < 1 ||
    policy.rules.length > 100
  ) {
    return false;
  }
  const minimum = Math.max(300, ...policy.rules.map(rule => rule.bucketSeconds * 2));
  if (policy.correctionWindowSeconds < minimum) return false;
  const keys = new Set<string>();
  return policy.rules.every(rule => {
    const propertyId = rule.propertyId.trim();
    const key = `${propertyId}::${rule.bucketSeconds}`;
    if (!/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/.test(propertyId) || !isBucketSeconds(rule.bucketSeconds) || keys.has(key)) {
      return false;
    }
    keys.add(key);
    return true;
  });
}
</script>

<template>
  <NFlex vertical :size="14">
    <NAlert type="info" :bordered="false">
      {{ t('mine.strategy.aggregation.queryBoundary') }}
    </NAlert>

    <NFlex justify="space-between" align="center" :wrap="true">
      <NFlex vertical :size="2">
        <NText strong>{{ t('mine.strategy.field.aggregationEnabled') }}</NText>
        <NText depth="3">{{ t('mine.strategy.aggregation.enabledHelp') }}</NText>
      </NFlex>
      <NSwitch
        :value="modelValue.enabled"
        :disabled="readonly"
        data-field="aggregation-enabled"
        @update:value="value => updatePolicy({ enabled: value })"
      />
    </NFlex>

    <NFormItem
      :label="t('mine.strategy.field.correctionWindowSeconds')"
      :validation-status="modelValue.correctionWindowSeconds < minimumCorrection ? 'error' : undefined"
      :feedback="
        modelValue.correctionWindowSeconds < minimumCorrection
          ? t('mine.strategy.validation.aggregationCorrection', { minimum: minimumCorrection })
          : t('mine.strategy.aggregation.correctionHelp')
      "
    >
      <NInputNumber
        :value="modelValue.correctionWindowSeconds"
        :min="minimumCorrection"
        :max="604800"
        :disabled="readonly"
        :placeholder="t('mine.strategy.placeholder.enter', { field: t('mine.strategy.field.correctionWindowSeconds') })"
        data-field="aggregation-correction-window"
        @update:value="value => value !== null && updatePolicy({ correctionWindowSeconds: value })"
      >
        <template #suffix>{{ t('mine.strategy.text.seconds') }}</template>
      </NInputNumber>
    </NFormItem>

    <NFlex justify="space-between" align="center" :wrap="true">
      <NFlex vertical :size="2">
        <NText strong>{{ t('mine.strategy.section.aggregationRules') }}</NText>
        <NText depth="3">
          {{ t('mine.strategy.aggregation.ruleCount', { count: modelValue.rules.length }) }}
        </NText>
      </NFlex>
      <NButton
        v-if="!readonly"
        secondary
        type="primary"
        size="small"
        :disabled="modelValue.rules.length >= 100"
        data-action="add-aggregation-rule"
        @click="addRule"
      >
        {{ t('mine.strategy.action.addAggregationRule') }}
      </NButton>
    </NFlex>

    <NList bordered>
      <NListItem v-for="(rule, index) in modelValue.rules" :key="index">
        <NFlex vertical :size="8" class="w-full">
          <NGrid responsive="screen" cols="1 s:12" :x-gap="10" :y-gap="6" class="w-full">
            <NGi span="1 s:6">
              <NFormItem
                :label="t('mine.strategy.field.propertyId')"
                :show-feedback="false"
                :validation-status="isDuplicate(rule) ? 'error' : undefined"
              >
                <NInput
                  :value="rule.propertyId"
                  :disabled="readonly"
                  data-field="aggregation-property"
                  :placeholder="t('mine.strategy.placeholder.propertyId')"
                  @update:value="value => updateRule(index, { propertyId: value })"
                />
              </NFormItem>
            </NGi>
            <NGi span="1 s:4">
              <NFormItem :label="t('mine.strategy.field.bucketSeconds')" :show-feedback="false">
                <NSelect
                  :value="rule.bucketSeconds"
                  :disabled="readonly"
                  :options="bucketOptions"
                  :placeholder="
                    t('mine.strategy.placeholder.select', { field: t('mine.strategy.field.bucketSeconds') })
                  "
                  data-field="aggregation-bucket"
                  @update:value="value => updateBucket(index, value)"
                />
              </NFormItem>
            </NGi>
            <NGi span="1 s:2">
              <NFlex justify="end" align="end" class="h-full">
                <NButton
                  v-if="!readonly"
                  quaternary
                  type="error"
                  size="small"
                  :disabled="modelValue.rules.length === 1"
                  data-action="remove-aggregation-rule"
                  @click="removeRule(index)"
                >
                  {{ t('mine.strategy.action.removeAggregationRule') }}
                </NButton>
              </NFlex>
            </NGi>
          </NGrid>
          <NAlert v-if="isDuplicate(rule)" type="error" :bordered="false">
            {{ t('mine.strategy.validation.aggregationDuplicate') }}
          </NAlert>
        </NFlex>
      </NListItem>
    </NList>

    <NAlert v-if="!modelValue.rules.length" type="warning" :bordered="false">
      {{ t('mine.strategy.empty.aggregationRules') }}
    </NAlert>
  </NFlex>
</template>
