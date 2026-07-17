<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  NAlert,
  NButton,
  NCard,
  NDescriptions,
  NDescriptionsItem,
  NEmpty,
  NFlex,
  NList,
  NListItem,
  NSpin,
  NTag,
  NText
} from 'naive-ui';
import type { AggregationPolicy, AlarmRule, EffectiveStrategy, RetentionPolicy } from '@/modules/device/api/strategy';

interface StrategyProblem {
  code: string;
  message: string;
  traceId?: string;
  details?: Record<string, unknown>;
}

const props = defineProps<{
  deviceName?: string | null;
  loading: boolean;
  effective?: EffectiveStrategy | null;
  problem?: StrategyProblem | null;
}>();

defineEmits<{ refresh: [] }>();

const { t } = useI18n();
const valueEntries = computed(() =>
  Object.entries(props.effective?.values || {})
    .map(([field, value]) => ({
      field,
      value,
      source: props.effective?.sources[field]
    }))
    .sort((left, right) => fieldOrder(left.field) - fieldOrder(right.field) || left.field.localeCompare(right.field))
);
const problemDetails = computed(() => formatDetails(props.problem?.details));
const hasConflict = computed(
  () => props.problem?.code === 'STRATEGY-409-001' || Boolean(props.effective?.conflicts.length)
);

function sourceLabel(scopeType?: string) {
  if (scopeType === 'PRODUCT') return t('mine.strategy.source.product');
  if (scopeType === 'GROUP') return t('mine.strategy.source.group');
  if (scopeType === 'DEVICE') return t('mine.strategy.source.device');
  return t('mine.strategy.source.unknown');
}

function fieldLabel(field: string, value: unknown) {
  if (field === 'offlineThresholdSeconds') return t('mine.strategy.field.threshold');
  if (field === 'staleAfterSeconds') return t('mine.strategy.field.staleAfterSeconds');
  if (field === 'futureToleranceSeconds') return t('mine.strategy.field.futureToleranceSeconds');
  if (field === 'rules') return t('mine.strategy.section.alarmRules');
  if (field === 'policy' && aggregationPolicy(value)) return t('mine.strategy.field.aggregationPolicy');
  if (field === 'policy') return t('mine.strategy.field.retentionPolicy');
  const match = /^(required|minimum|maximum)\.(.+)$/.exec(field);
  if (!match?.[1] || !match[2]) return field;
  return t(`mine.strategy.qualityField.${match[1]}`, { property: match[2] });
}

function displayValue(field: string, value: unknown) {
  if (field === 'rules' && Array.isArray(value)) {
    return t('mine.strategy.text.alarmSummary', { count: value.length });
  }
  if (field === 'policy' && retentionPolicy(value)) {
    return t(`mine.strategy.retention.class.${retentionPolicy(value)?.retentionClass.toLowerCase()}`);
  }
  if (field === 'policy' && aggregationPolicy(value)) {
    return t('mine.strategy.text.aggregationSummary', {
      count: aggregationPolicy(value)?.rules.length,
      correction: aggregationPolicy(value)?.correctionWindowSeconds
    });
  }
  if (typeof value === 'boolean') {
    return value ? t('mine.strategy.text.enabled') : t('mine.strategy.text.disabled');
  }
  if (['offlineThresholdSeconds', 'staleAfterSeconds', 'futureToleranceSeconds'].includes(field)) {
    return `${value} ${t('mine.strategy.text.seconds')}`;
  }
  return String(value);
}

function retentionPolicy(value: unknown): RetentionPolicy | null {
  if (!value || typeof value !== 'object') return null;
  const policy = value as Partial<RetentionPolicy>;
  if (
    typeof policy.enabled !== 'boolean' ||
    !['SHORT', 'STANDARD', 'LONG', 'ARCHIVE'].includes(policy.retentionClass || '') ||
    typeof policy.rawTelemetryDays !== 'number' ||
    typeof policy.qualityEvidenceDays !== 'number'
  ) {
    return null;
  }
  return policy as RetentionPolicy;
}

function aggregationPolicy(value: unknown): AggregationPolicy | null {
  if (!value || typeof value !== 'object') return null;
  const policy = value as Partial<AggregationPolicy>;
  if (
    typeof policy.enabled !== 'boolean' ||
    typeof policy.correctionWindowSeconds !== 'number' ||
    !Array.isArray(policy.rules)
  )
    return null;
  return policy as AggregationPolicy;
}

function alarmRules(value: unknown): AlarmRule[] {
  if (!Array.isArray(value)) return [];
  return value.filter(
    (rule): rule is AlarmRule =>
      Boolean(rule) &&
      typeof rule === 'object' &&
      'sourceType' in rule &&
      ((rule as { sourceType?: unknown }).sourceType === 'NUMERIC' ||
        (rule as { sourceType?: unknown }).sourceType === 'QUALITY')
  );
}

function alarmRuleDetail(rule: AlarmRule) {
  const timing = t('mine.strategy.alarm.timing', {
    trigger: rule.triggerDurationSeconds,
    recover: rule.recoverDurationSeconds,
    cooldown: rule.cooldownSeconds
  });
  if (rule.sourceType === 'NUMERIC') {
    return `${rule.propertyId} · ${t(`mine.strategy.alarm.operator.${rule.operator.toLowerCase()}`)} ${rule.threshold} · ${t('mine.strategy.field.recoverThreshold')} ${rule.recoverThreshold} · ${timing}`;
  }
  return `${t(`mine.strategy.alarm.qualityCode.${rule.qualityRuleCode.toLowerCase()}`)}${rule.propertyId ? ` · ${rule.propertyId}` : ''} · ${timing}`;
}

function severityType(severity: AlarmRule['severity']) {
  if (severity === 'CRITICAL') return 'error' as const;
  if (severity === 'WARNING') return 'warning' as const;
  return 'info' as const;
}

function fieldOrder(field: string) {
  const knownFields = ['offlineThresholdSeconds', 'staleAfterSeconds', 'futureToleranceSeconds'];
  const index = knownFields.indexOf(field);
  return index === -1 ? knownFields.length : index;
}

function formatDetails(details?: Record<string, unknown>) {
  if (!details) return '';
  return Object.entries(details)
    .map(([key, value]) => `${key}: ${formatValue(value)}`)
    .join(' · ');
}

function formatValue(value: unknown): string {
  if (Array.isArray(value)) return value.map(formatValue).join(', ');
  if (value && typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>)
      .map(([key, nested]) => `${key}=${formatValue(nested)}`)
      .join(', ');
  }
  return String(value ?? '');
}
</script>

<template>
  <NCard :title="t('mine.strategy.section.effective')" :bordered="false" size="small">
    <template #header-extra>
      <NButton
        quaternary
        type="primary"
        size="small"
        :disabled="!deviceName"
        :loading="loading"
        data-action="refresh-effective"
        @click="$emit('refresh')"
      >
        {{ t('mine.strategy.action.refresh') }}
      </NButton>
    </template>

    <NSpin :show="loading">
      <NFlex v-if="effective || problem" vertical :size="14">
        <NFlex justify="space-between" align="center" :wrap="true">
          <NFlex vertical :size="2">
            <NText depth="3">{{ t('mine.strategy.field.device') }}</NText>
            <NText strong>{{ deviceName || t('mine.common.empty') }}</NText>
          </NFlex>
          <NTag :type="effective?.valid && !problem ? 'success' : 'error'" :bordered="false" round>
            {{ effective?.valid && !problem ? t('mine.strategy.status.valid') : t('mine.strategy.status.invalid') }}
          </NTag>
        </NFlex>

        <NAlert
          v-if="problem"
          :title="hasConflict ? t('mine.strategy.error.conflictTitle') : t('mine.strategy.error.title')"
          type="error"
          :bordered="false"
        >
          <NFlex vertical :size="4">
            <NText>{{ problem.message }}</NText>
            <NText code>{{ t('mine.strategy.error.code', { code: problem.code }) }}</NText>
            <NText v-if="problem.traceId" depth="3">{{ t('mine.common.traceId', { traceId: problem.traceId }) }}</NText>
            <NText v-if="problemDetails" depth="3">
              {{ t('mine.strategy.error.details', { details: problemDetails }) }}
            </NText>
          </NFlex>
        </NAlert>

        <NAlert
          v-for="conflict in effective?.conflicts || []"
          :key="`${conflict.field}-${conflict.groupPriority}`"
          :title="t('mine.strategy.error.conflictTitle')"
          type="warning"
          :bordered="false"
        >
          {{
            t('mine.strategy.error.details', {
              details: `${conflict.field} · ${t('mine.strategy.text.priority', { priority: conflict.groupPriority })} · ${conflict.groupIds.join(', ')}`
            })
          }}
        </NAlert>

        <NList v-if="valueEntries.length" bordered>
          <NListItem v-for="entry in valueEntries" :key="entry.field">
            <NFlex vertical :size="8" class="w-full">
              <NFlex justify="space-between" align="center" :wrap="true">
                <NText strong>{{ fieldLabel(entry.field, entry.value) }}</NText>
                <NTag type="primary" :bordered="false">{{ displayValue(entry.field, entry.value) }}</NTag>
              </NFlex>
              <NList v-if="alarmRules(entry.value).length" bordered size="small">
                <NListItem v-for="rule in alarmRules(entry.value)" :key="rule.key">
                  <NFlex vertical :size="3" class="w-full">
                    <NFlex justify="space-between" align="center" :wrap="true">
                      <NText strong>{{ rule.name }}</NText>
                      <NTag :type="severityType(rule.severity)" :bordered="false" size="small">
                        {{ t(`mine.strategy.alarm.severity.${rule.severity.toLowerCase()}`) }}
                      </NTag>
                      <NTag :type="rule.enabled ? 'success' : 'default'" :bordered="false" size="small">
                        {{ rule.enabled ? t('mine.strategy.text.enabled') : t('mine.strategy.text.disabled') }}
                      </NTag>
                    </NFlex>
                    <NText code>{{ rule.key }}</NText>
                    <NText depth="3">{{ alarmRuleDetail(rule) }}</NText>
                  </NFlex>
                </NListItem>
              </NList>
              <NFlex v-if="retentionPolicy(entry.value)" vertical :size="3">
                <NText>
                  {{
                    t('mine.strategy.retention.rawSummary', {
                      days: retentionPolicy(entry.value)?.rawTelemetryDays
                    })
                  }}
                </NText>
                <NText>
                  {{
                    t('mine.strategy.retention.qualitySummary', {
                      days: retentionPolicy(entry.value)?.qualityEvidenceDays
                    })
                  }}
                </NText>
                <NTag :type="retentionPolicy(entry.value)?.enabled ? 'success' : 'default'" :bordered="false">
                  {{
                    retentionPolicy(entry.value)?.enabled
                      ? t('mine.strategy.text.enabled')
                      : t('mine.strategy.text.disabled')
                  }}
                </NTag>
              </NFlex>
              <NFlex v-if="aggregationPolicy(entry.value)" vertical :size="6">
                <NText>
                  {{
                    t('mine.strategy.aggregation.correctionSummary', {
                      seconds: aggregationPolicy(entry.value)?.correctionWindowSeconds
                    })
                  }}
                </NText>
                <NList bordered size="small">
                  <NListItem
                    v-for="rule in aggregationPolicy(entry.value)?.rules || []"
                    :key="`${rule.propertyId}-${rule.bucketSeconds}`"
                  >
                    <NFlex justify="space-between" class="w-full" :wrap="true">
                      <NText code>{{ rule.propertyId }}</NText>
                      <NTag :bordered="false" size="small">
                        {{ t(`mine.strategy.aggregation.bucket.${rule.bucketSeconds}`) }}
                      </NTag>
                    </NFlex>
                  </NListItem>
                </NList>
                <NTag :type="aggregationPolicy(entry.value)?.enabled ? 'success' : 'default'" :bordered="false">
                  {{
                    aggregationPolicy(entry.value)?.enabled
                      ? t('mine.strategy.text.enabled')
                      : t('mine.strategy.text.disabled')
                  }}
                </NTag>
              </NFlex>
              <NDescriptions v-if="entry.source" :column="1" label-placement="left" bordered size="small">
                <NDescriptionsItem :label="t('mine.strategy.field.source')">
                  <NFlex align="center" :wrap="true">
                    <NTag type="primary" :bordered="false">{{ sourceLabel(entry.source.scopeType) }}</NTag>
                    <NText
                      v-if="entry.source.groupPriority !== null && entry.source.groupPriority !== undefined"
                      depth="3"
                    >
                      {{
                        t('mine.strategy.source.priority', {
                          priority: entry.source.groupPriority
                        })
                      }}
                    </NText>
                  </NFlex>
                </NDescriptionsItem>
                <NDescriptionsItem :label="t('mine.strategy.field.scope')">
                  <NText code class="break-all">{{ entry.source.scopeId }}</NText>
                </NDescriptionsItem>
                <NDescriptionsItem :label="t('mine.strategy.field.release')">
                  <NText code class="break-all">{{ entry.source.releaseId }}</NText>
                </NDescriptionsItem>
              </NDescriptions>
              <NText v-else depth="3">{{ t('mine.strategy.source.unknown') }}</NText>
            </NFlex>
          </NListItem>
        </NList>

        <NFlex v-if="effective?.contentHash" vertical :size="4">
          <NText depth="3">{{ t('mine.strategy.field.hash') }}</NText>
          <NText code class="break-all">{{ effective.contentHash }}</NText>
        </NFlex>
      </NFlex>

      <NEmpty v-else :description="t('mine.strategy.empty.effective')" />
    </NSpin>
  </NCard>
</template>
