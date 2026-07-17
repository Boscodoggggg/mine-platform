<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { SelectOption } from 'naive-ui';
import {
  NAlert,
  NButton,
  NCard,
  NDatePicker,
  NDescriptions,
  NDescriptionsItem,
  NEmpty,
  NFlex,
  NForm,
  NFormItem,
  NGi,
  NGrid,
  NInput,
  NList,
  NListItem,
  NPageHeader,
  NSelect,
  NSpin,
  NTag,
  NText
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import {
  createAggregationRecomputation,
  listAggregationExecutions,
  listAggregationRecomputations
} from '@/modules/device/api/aggregation';
import type {
  AggregationExecution,
  AggregationRecompute,
  AggregationRecomputeRequest
} from '@/modules/device/api/aggregation';
import { getDevices, getProducts } from '@/modules/device/api/device';
import type { Device, Product } from '@/modules/device/api/device';
import { useAuthStore } from '@/store/modules/auth';

interface GovernanceProblem {
  code: string;
  message: string;
  traceId?: string;
}

const { locale, t } = useI18n();
const authStore = useAuthStore();
const loadingContext = ref(false);
const loadingFacts = ref(false);
const submitting = ref(false);
const products = ref<Product[]>([]);
const devices = ref<Device[]>([]);
const executions = ref<AggregationExecution[]>([]);
const recomputations = ref<AggregationRecompute[]>([]);
const problem = ref<GovernanceProblem | null>(null);
const lastRefreshedAt = ref<Date | null>(null);
const idempotencyFingerprint = ref('');
const idempotencyKey = ref('');

const form = reactive({
  productId: null as string | null,
  deviceId: null as string | null,
  propertyId: '',
  bucket: 'PT5M' as AggregationRecomputeRequest['bucket'],
  range: null as [number, number] | null,
  reason: ''
});

const productOptions = computed<SelectOption[]>(() =>
  products.value.map(item => ({ label: `${item.name} (${item.productKey})`, value: item.id }))
);
const deviceOptions = computed<SelectOption[]>(() =>
  devices.value.map(item => ({ label: `${item.name} (${item.deviceCode})`, value: item.id }))
);
const bucketOptions = computed<SelectOption[]>(() =>
  ['PT1M', 'PT5M', 'PT15M', 'PT1H', 'PT6H', 'P1D'].map(value => ({
    value,
    label: t(`mine.aggregationGovernance.bucket.${value}`)
  }))
);
const lastExecution = computed(() => executions.value[0] || null);

onMounted(loadInitial);

async function loadInitial() {
  await Promise.allSettled([loadProducts(), refreshFacts()]);
}

async function loadProducts() {
  loadingContext.value = true;
  try {
    const response = await getProducts({ page: 1, size: 100 });
    products.value = response.items.filter(item => item.status === 'ACTIVE');
    form.productId = products.value[0]?.id || null;
    await loadDevices();
  } catch (error) {
    captureProblem(error);
  } finally {
    loadingContext.value = false;
  }
}

async function loadDevices() {
  devices.value = [];
  form.deviceId = null;
  if (!form.productId) return;
  try {
    const response = await getDevices({ productId: form.productId, page: 1, size: 100 });
    devices.value = response.items.filter(item => item.status !== 'ARCHIVED');
    form.deviceId = devices.value[0]?.id || null;
  } catch (error) {
    captureProblem(error);
  }
}

async function refreshFacts() {
  loadingFacts.value = true;
  problem.value = null;
  const [executionResult, recomputationResult] = await Promise.allSettled([
    listAggregationExecutions(20),
    listAggregationRecomputations(20)
  ]);
  if (executionResult.status === 'fulfilled') executions.value = executionResult.value;
  else {
    executions.value = [];
    captureProblem(executionResult.reason);
  }
  if (recomputationResult.status === 'fulfilled') recomputations.value = recomputationResult.value;
  else {
    recomputations.value = [];
    captureProblem(recomputationResult.reason);
  }
  lastRefreshedAt.value = new Date();
  loadingFacts.value = false;
}

async function submitRecomputation() {
  const normalizedProperty = form.propertyId.trim();
  const normalizedReason = form.reason.trim();
  if (
    !form.deviceId ||
    !form.range ||
    !/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/.test(normalizedProperty) ||
    normalizedReason.length < 4
  ) {
    return window.$message?.warning(t('mine.aggregationGovernance.validation.required'));
  }
  const [from, to] = form.range;
  const bucketMilliseconds = bucketDurationMilliseconds(form.bucket);
  if (
    from >= to ||
    to > Date.now() ||
    to - from > 31 * 86400000 ||
    (to - from) / bucketMilliseconds > 10000 ||
    from % bucketMilliseconds !== 0 ||
    to % bucketMilliseconds !== 0
  ) {
    return window.$message?.warning(t('mine.aggregationGovernance.validation.range'));
  }
  if (!window.confirm(t('mine.aggregationGovernance.confirm.recompute'))) return;

  const payload: AggregationRecomputeRequest = {
    deviceId: form.deviceId,
    propertyId: normalizedProperty,
    bucket: form.bucket,
    from: new Date(from).toISOString(),
    to: new Date(to).toISOString(),
    reason: normalizedReason
  };
  const fingerprint = JSON.stringify(payload);
  if (idempotencyFingerprint.value !== fingerprint) {
    idempotencyFingerprint.value = fingerprint;
    idempotencyKey.value = createIdempotencyKey();
  }
  submitting.value = true;
  problem.value = null;
  try {
    const accepted = await createAggregationRecomputation(payload, idempotencyKey.value);
    recomputations.value = [
      accepted,
      ...recomputations.value.filter(item => item.requestId !== accepted.requestId)
    ].slice(0, 20);
    idempotencyFingerprint.value = '';
    idempotencyKey.value = '';
    window.$message?.success(t('mine.aggregationGovernance.message.accepted'));
  } catch (error) {
    captureProblem(error);
  } finally {
    submitting.value = false;
  }
}

function can(permission: string) {
  return hasPermission(authStore.userInfo.buttons, permission);
}

function captureProblem(error: unknown) {
  if (error instanceof ApiError) {
    problem.value = { code: error.code, message: error.message, traceId: error.traceId };
    window.$message?.error(error.message);
    return;
  }
  problem.value = { code: 'SYS-UNKNOWN-001', message: t('mine.common.operationFailed') };
  window.$message?.error(t('mine.common.operationFailed'));
}

function executionStatusType(status: AggregationExecution['status']) {
  if (status === 'SUCCEEDED') return 'success' as const;
  if (status === 'FAILED') return 'error' as const;
  if (status === 'PARTIAL') return 'warning' as const;
  return 'info' as const;
}

function recomputationStatusType(status: AggregationRecompute['status']) {
  if (status === 'SUCCEEDED') return 'success' as const;
  if (status === 'FAILED') return 'error' as const;
  if (status === 'RUNNING') return 'info' as const;
  return 'warning' as const;
}

function statusLabel(status: string) {
  return t(`mine.aggregationGovernance.status.${status.toLowerCase()}`);
}

function formatNumber(value: number) {
  return new Intl.NumberFormat(locale.value).format(value);
}

function formatTime(value: string | Date | null | undefined) {
  if (!value) return t('mine.common.empty');
  const date = value instanceof Date ? value : new Date(value);
  return new Intl.DateTimeFormat(locale.value, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  }).format(date);
}

function bucketDurationMilliseconds(bucket: AggregationRecomputeRequest['bucket']) {
  const durations: Record<AggregationRecomputeRequest['bucket'], number> = {
    PT1M: 60000,
    PT5M: 300000,
    PT15M: 900000,
    PT1H: 3600000,
    PT6H: 21600000,
    P1D: 86400000
  };
  return durations[bucket] || 0;
}

function createIdempotencyKey() {
  const suffix = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `aggregation-recompute-${suffix}`;
}
</script>

<template>
  <div>
    <NFlex vertical :size="16">
      <NPageHeader :subtitle="t('mine.aggregationGovernance.subtitle')">
        <template #title>
          <NText strong data-page-title="aggregation-governance">{{ t('mine.aggregationGovernance.title') }}</NText>
        </template>
        <template #extra>
          <NFlex align="center" :wrap="true">
            <NText v-if="lastRefreshedAt" depth="3">
              {{ t('mine.aggregationGovernance.lastRefreshed', { time: formatTime(lastRefreshedAt) }) }}
            </NText>
            <NButton
              quaternary
              type="primary"
              :loading="loadingFacts"
              data-action="refresh-aggregation"
              @click="refreshFacts"
            >
              {{ t('mine.aggregationGovernance.action.refresh') }}
            </NButton>
          </NFlex>
        </template>
      </NPageHeader>

      <NAlert type="info" :bordered="false">
        {{ t('mine.aggregationGovernance.boundary') }}
      </NAlert>
      <NAlert v-if="problem" type="error" :title="t('mine.aggregationGovernance.error.title')" :bordered="false">
        <NFlex vertical :size="3">
          <NText>{{ problem.message }}</NText>
          <NText code>{{ problem.code }}</NText>
          <NText v-if="problem.traceId" depth="3">{{ t('mine.common.traceId', { traceId: problem.traceId }) }}</NText>
        </NFlex>
      </NAlert>

      <NGrid responsive="screen" cols="1 m:12" :x-gap="16" :y-gap="16">
        <NGi span="1 m:5" class="min-w-0">
          <NCard :title="t('mine.aggregationGovernance.section.recompute')" :bordered="false" size="small">
            <NSpin :show="loadingContext">
              <NForm label-placement="top">
                <NGrid responsive="screen" cols="1 s:2" :x-gap="12">
                  <NGi>
                    <NFormItem :label="t('mine.aggregationGovernance.field.product')">
                      <NSelect
                        v-model:value="form.productId"
                        :options="productOptions"
                        :placeholder="
                          t('mine.common.selectPlaceholder', { field: t('mine.aggregationGovernance.field.product') })
                        "
                        filterable
                        @update:value="loadDevices"
                      />
                    </NFormItem>
                  </NGi>
                  <NGi>
                    <NFormItem :label="t('mine.aggregationGovernance.field.device')">
                      <NSelect
                        v-model:value="form.deviceId"
                        :options="deviceOptions"
                        :placeholder="
                          t('mine.common.selectPlaceholder', { field: t('mine.aggregationGovernance.field.device') })
                        "
                        filterable
                      />
                    </NFormItem>
                  </NGi>
                </NGrid>
                <NGrid responsive="screen" cols="1 s:2" :x-gap="12">
                  <NGi>
                    <NFormItem :label="t('mine.aggregationGovernance.field.propertyId')">
                      <NInput
                        v-model:value="form.propertyId"
                        data-field="aggregation-property"
                        :placeholder="t('mine.aggregationGovernance.placeholder.propertyId')"
                      />
                    </NFormItem>
                  </NGi>
                  <NGi>
                    <NFormItem :label="t('mine.aggregationGovernance.field.bucket')">
                      <NSelect
                        v-model:value="form.bucket"
                        :options="bucketOptions"
                        :placeholder="
                          t('mine.common.selectPlaceholder', { field: t('mine.aggregationGovernance.field.bucket') })
                        "
                        data-field="aggregation-bucket"
                      />
                    </NFormItem>
                  </NGi>
                </NGrid>
                <NFormItem :label="t('mine.aggregationGovernance.field.range')">
                  <NDatePicker
                    v-model:value="form.range"
                    type="datetimerange"
                    clearable
                    class="w-full"
                    data-field="aggregation-range"
                    :is-date-disabled="(timestamp: number) => timestamp > Date.now()"
                  />
                </NFormItem>
                <NFormItem :label="t('mine.aggregationGovernance.field.reason')">
                  <NInput
                    v-model:value="form.reason"
                    type="textarea"
                    :maxlength="500"
                    show-count
                    :autosize="{ minRows: 3, maxRows: 5 }"
                    data-field="aggregation-reason"
                    :placeholder="t('mine.aggregationGovernance.placeholder.reason')"
                  />
                </NFormItem>
                <NAlert type="warning" :bordered="false">
                  {{ t('mine.aggregationGovernance.recomputeHelp') }}
                </NAlert>
                <NFlex v-if="can('governance:aggregation:execute')" justify="end" class="mt-3">
                  <NButton
                    type="primary"
                    :loading="submitting"
                    data-action="submit-recomputation"
                    @click="submitRecomputation"
                  >
                    {{ t('mine.aggregationGovernance.action.submit') }}
                  </NButton>
                </NFlex>
                <NAlert v-else type="info" :bordered="false" class="mt-3">
                  {{ t('mine.aggregationGovernance.readonly') }}
                </NAlert>
              </NForm>
            </NSpin>
          </NCard>
        </NGi>

        <NGi span="1 m:7" class="min-w-0">
          <NFlex vertical :size="16">
            <NCard :title="t('mine.aggregationGovernance.section.latest')" :bordered="false" size="small">
              <NDescriptions v-if="lastExecution" :column="2" label-placement="top" bordered size="small">
                <NDescriptionsItem :label="t('mine.aggregationGovernance.field.status')">
                  <NTag :type="executionStatusType(lastExecution.status)" :bordered="false">
                    {{ statusLabel(lastExecution.status) }}
                  </NTag>
                </NDescriptionsItem>
                <NDescriptionsItem :label="t('mine.aggregationGovernance.field.startedAt')">
                  {{ formatTime(lastExecution.startedAt) }}
                </NDescriptionsItem>
                <NDescriptionsItem :label="t('mine.aggregationGovernance.field.bucketCount')">
                  {{ formatNumber(lastExecution.bucketCount) }}
                </NDescriptionsItem>
                <NDescriptionsItem :label="t('mine.aggregationGovernance.field.lateRowsIgnored')">
                  {{ formatNumber(lastExecution.lateRowsIgnored) }}
                </NDescriptionsItem>
                <NDescriptionsItem :label="t('mine.aggregationGovernance.field.sourceWatermark')">
                  {{ formatNumber(lastExecution.sourceWatermark) }}
                </NDescriptionsItem>
                <NDescriptionsItem :label="t('mine.aggregationGovernance.field.rollupWatermark')">
                  {{
                    lastExecution.rollupWatermark == null
                      ? t('mine.common.empty')
                      : formatNumber(lastExecution.rollupWatermark)
                  }}
                </NDescriptionsItem>
              </NDescriptions>
              <NEmpty v-else :description="t('mine.aggregationGovernance.empty.executions')" />
            </NCard>

            <NCard :title="t('mine.aggregationGovernance.section.executions')" :bordered="false" size="small">
              <NSpin :show="loadingFacts">
                <NList v-if="executions.length" hoverable>
                  <NListItem v-for="execution in executions" :key="execution.executionId">
                    <NFlex vertical :size="5" class="w-full">
                      <NFlex justify="space-between" align="center" :wrap="true">
                        <NText code>{{ formatTime(execution.startedAt) }}</NText>
                        <NTag :type="executionStatusType(execution.status)" :bordered="false" size="small">
                          {{ statusLabel(execution.status) }}
                        </NTag>
                      </NFlex>
                      <NText>
                        {{
                          t('mine.aggregationGovernance.executionSummary', {
                            rules: formatNumber(execution.ruleCount),
                            buckets: formatNumber(execution.bucketCount),
                            late: formatNumber(execution.lateRowsIgnored)
                          })
                        }}
                      </NText>
                      <NText depth="3">{{ execution.instanceId }}</NText>
                      <NAlert v-if="execution.lastError" type="error" :bordered="false">
                        {{ execution.lastError }}
                      </NAlert>
                    </NFlex>
                  </NListItem>
                </NList>
                <NEmpty v-else :description="t('mine.aggregationGovernance.empty.executions')" />
              </NSpin>
            </NCard>
          </NFlex>
        </NGi>
      </NGrid>

      <NCard :title="t('mine.aggregationGovernance.section.requests')" :bordered="false" size="small">
        <NList v-if="recomputations.length" hoverable>
          <NListItem v-for="request in recomputations" :key="request.requestId">
            <NGrid responsive="screen" cols="1 s:12" :x-gap="12" :y-gap="6" class="w-full">
              <NGi span="1 s:3">
                <NFlex vertical :size="3">
                  <NText strong>{{ request.propertyId }}</NText>
                  <NText depth="3">{{ request.deviceId }}</NText>
                </NFlex>
              </NGi>
              <NGi span="1 s:4">
                <NFlex vertical :size="3">
                  <NText>{{ formatTime(request.from) }} — {{ formatTime(request.to) }}</NText>
                  <NText depth="3">{{ request.bucket }} · {{ request.reason }}</NText>
                </NFlex>
              </NGi>
              <NGi span="1 s:3">
                <NFlex vertical :size="3">
                  <NText>{{ formatTime(request.createdAt) }}</NText>
                  <NText v-if="request.executionId" code>{{ request.executionId }}</NText>
                </NFlex>
              </NGi>
              <NGi span="1 s:2">
                <NFlex justify="end">
                  <NTag :type="recomputationStatusType(request.status)" :bordered="false">
                    {{ statusLabel(request.status) }}
                  </NTag>
                </NFlex>
              </NGi>
              <NGi v-if="request.lastError" span="1 s:12">
                <NAlert type="error" :bordered="false">{{ request.lastError }}</NAlert>
              </NGi>
            </NGrid>
          </NListItem>
        </NList>
        <NEmpty v-else :description="t('mine.aggregationGovernance.empty.requests')" />
      </NCard>
    </NFlex>
  </div>
</template>
