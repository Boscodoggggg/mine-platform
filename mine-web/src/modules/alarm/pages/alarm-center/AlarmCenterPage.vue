<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NDatePicker,
  NDescriptions,
  NDescriptionsItem,
  NDivider,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NFlex,
  NForm,
  NFormItem,
  NInput,
  NPageHeader,
  NSelect,
  NSpace,
  NTag,
  NText
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import ListActionButton from '@/components/common/list-action-button.vue';
import ListSearchPanel from '@/components/common/list-search-panel.vue';
import { acknowledgeAlarm, closeAlarm, getAlarm, getAlarms } from '@/modules/alarm/api/alarm';
import type { Alarm, AlarmSeverity, AlarmStatus } from '@/modules/alarm/api/alarm';
import { useAuthStore } from '@/store/modules/auth';

interface AlarmProblem {
  code: string;
  message: string;
  traceId?: string;
}

const { t, locale } = useI18n();
const authStore = useAuthStore();
const loading = ref(false);
const detailLoading = ref(false);
const disposing = ref(false);
const alarms = ref<Alarm[]>([]);
const total = ref(0);
const drawerVisible = ref(false);
const selectedAlarm = ref<Alarm | null>(null);
const dispositionNote = ref('');
const raisedRange = ref<[number, number] | null>(null);
const operationProblem = ref<AlarmProblem | null>(null);
const idempotencyKeys = new Map<string, string>();
const query = reactive<{
  active: boolean | null;
  closed: boolean | null;
  status: AlarmStatus | null;
  severity: AlarmSeverity | null;
  deviceKeyword: string;
  ruleKey: string;
  from: string | null;
  to: string | null;
  page: number;
  size: number;
}>({
  active: null,
  closed: false,
  status: null,
  severity: null,
  deviceKeyword: '',
  ruleKey: '',
  from: null,
  to: null,
  page: 1,
  size: 20
});
const statuses: AlarmStatus[] = [
  'ACTIVE_UNACKNOWLEDGED',
  'ACTIVE_ACKNOWLEDGED',
  'RECOVERED_UNACKNOWLEDGED',
  'RECOVERED_ACKNOWLEDGED',
  'CLOSED'
];
const severities: AlarmSeverity[] = ['INFO', 'WARNING', 'CRITICAL'];

const statusOptions = computed<SelectOption[]>(() =>
  statuses.map(value => ({ label: statusLabel(value), value }))
);
const severityOptions = computed<SelectOption[]>(() =>
  severities.map(value => ({ label: severityLabel(value), value }))
);
const pendingCount = computed(
  () => alarms.value.filter(item => ['ACTIVE_UNACKNOWLEDGED', 'RECOVERED_UNACKNOWLEDGED'].includes(item.status)).length
);
const canAcknowledge = computed(
  () => Boolean(selectedAlarm.value && !selectedAlarm.value.acknowledgedAt && !selectedAlarm.value.closedAt) && can('alarm:event:ack')
);
const canClose = computed(
  () => Boolean(selectedAlarm.value?.recoveredAt && !selectedAlarm.value?.closedAt) && can('alarm:event:close')
);
const columns = computed<DataTableColumns<Alarm>>(() => [
  {
    title: t('mine.alarms.column.severity'),
    key: 'severity',
    width: 88,
    render: row =>
      h(NTag, { size: 'small', bordered: false, type: severityType(row.severity) }, () => severityLabel(row.severity))
  },
  {
    title: t('mine.alarms.column.status'),
    key: 'status',
    minWidth: 132,
    render: row => h(NTag, { size: 'small', bordered: false, type: statusType(row.status) }, () => statusLabel(row.status))
  },
  {
    title: t('mine.alarms.column.device'),
    key: 'deviceName',
    minWidth: 180,
    render: row => h(NFlex, { vertical: true, size: 1 }, () => [h(NText, { strong: true }, () => row.deviceName), h(NText, { depth: 3 }, () => row.deviceCode)])
  },
  {
    title: t('mine.alarms.column.rule'),
    key: 'ruleName',
    minWidth: 200,
    render: row => h(NFlex, { vertical: true, size: 1 }, () => [h(NText, { strong: true }, () => row.ruleName), h(NText, { code: true, depth: 3 }, () => row.ruleKey)])
  },
  {
    title: t('mine.alarms.column.source'),
    key: 'sourceType',
    minWidth: 145,
    render: row => row.propertyId || sourceLabel(row.sourceType)
  },
  {
    title: t('mine.alarms.column.raisedAt'),
    key: 'raisedAt',
    minWidth: 176,
    render: row => formatTime(row.raisedAt)
  },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 96,
    fixed: 'right',
    render: row =>
      h(NButton, { text: true, type: 'primary', onClick: () => showDetails(row) }, () => t('mine.alarms.action.detail'))
  }
]);

onMounted(loadAlarms);

async function loadAlarms(preserveProblem = false) {
  loading.value = true;
  if (!preserveProblem) operationProblem.value = null;
  try {
    const response = await getAlarms(query);
    alarms.value = response.items;
    total.value = response.total;
    query.page = response.page;
    query.size = response.size;
  } catch (error) {
    captureProblem(error);
  } finally {
    loading.value = false;
  }
}

function search() {
  query.page = 1;
  return loadAlarms();
}

function resetQuery() {
  Object.assign(query, {
    active: null,
    closed: false,
    status: null,
    severity: null,
    deviceKeyword: '',
    ruleKey: '',
    from: null,
    to: null,
    page: 1
  });
  raisedRange.value = null;
  return loadAlarms();
}

function updateRaisedRange(value: [number, number] | null) {
  raisedRange.value = value;
  query.from = value ? new Date(value[0]).toISOString() : null;
  query.to = value ? new Date(value[1]).toISOString() : null;
}

function syncStateConstraints(status: AlarmStatus | null) {
  query.active = status?.startsWith('ACTIVE_') ? true : null;
  query.closed = status === 'CLOSED';
}

async function showDetails(alarm: Alarm) {
  selectedAlarm.value = alarm;
  dispositionNote.value = '';
  operationProblem.value = null;
  drawerVisible.value = true;
  detailLoading.value = true;
  try {
    selectedAlarm.value = await getAlarm(alarm.id);
  } catch (error) {
    selectedAlarm.value = null;
    captureProblem(error);
  } finally {
    detailLoading.value = false;
  }
}

function requestAcknowledgement() {
  if (!selectedAlarm.value) return;
  window.$dialog?.warning({
    title: t('mine.alarms.confirm.ackTitle'),
    content: t('mine.alarms.confirm.ackContent'),
    positiveText: t('mine.alarms.action.acknowledge'),
    negativeText: t('mine.common.cancel'),
    onPositiveClick: submitAcknowledgement
  });
}

async function submitAcknowledgement() {
  const alarm = selectedAlarm.value;
  if (!alarm || !canAcknowledge.value) return;
  if (dispositionNote.value.trim().length < 2) {
    window.$message?.warning(t('mine.alarms.validation.note'));
    return;
  }
  await submitDisposition('ack', alarm);
}

function requestClose() {
  if (!selectedAlarm.value) return;
  window.$dialog?.warning({
    title: t('mine.alarms.confirm.closeTitle'),
    content: t('mine.alarms.confirm.closeContent'),
    positiveText: t('mine.alarms.action.close'),
    negativeText: t('mine.common.cancel'),
    onPositiveClick: submitClose
  });
}

async function submitClose() {
  const alarm = selectedAlarm.value;
  if (!alarm || !canClose.value) return;
  if (dispositionNote.value.trim().length < 2) {
    window.$message?.warning(t('mine.alarms.validation.note'));
    return;
  }
  await submitDisposition('close', alarm);
}

async function submitDisposition(action: 'ack' | 'close', alarm: Alarm) {
  disposing.value = true;
  operationProblem.value = null;
  const operationKey = `${action}:${alarm.id}`;
  const idempotencyKey = idempotencyKeys.get(operationKey) || createIdempotencyKey(operationKey);
  idempotencyKeys.set(operationKey, idempotencyKey);
  try {
    selectedAlarm.value =
      action === 'ack'
        ? await acknowledgeAlarm(alarm.id, { note: dispositionNote.value.trim(), version: alarm.version }, idempotencyKey)
        : await closeAlarm(alarm.id, { note: dispositionNote.value.trim(), version: alarm.version }, idempotencyKey);
    window.$message?.success(t(action === 'ack' ? 'mine.alarms.message.acknowledged' : 'mine.alarms.message.closed'));
    dispositionNote.value = '';
    await loadAlarms();
  } catch (error) {
    captureProblem(error);
    if (error instanceof ApiError && error.status === 409) {
      await Promise.all([refreshDetails(), loadAlarms(true)]);
    }
  } finally {
    disposing.value = false;
  }
}

async function refreshDetails() {
  if (!selectedAlarm.value) return;
  try {
    selectedAlarm.value = await getAlarm(selectedAlarm.value.id);
  } catch (error) {
    captureProblem(error);
  }
}

function can(permission: string) {
  return hasPermission(authStore.userInfo.buttons, permission);
}

function statusLabel(status: AlarmStatus) {
  return t(`mine.alarms.status.${status.toLowerCase()}` as App.I18n.I18nKey);
}

function severityLabel(severity: AlarmSeverity) {
  return t(`mine.alarms.severity.${severity.toLowerCase()}` as App.I18n.I18nKey);
}

function sourceLabel(source: Alarm['sourceType']) {
  return t(`mine.alarms.source.${source.toLowerCase()}` as App.I18n.I18nKey);
}

function recoveryReasonLabel(reason: Alarm['recoveryReason']) {
  if (!reason) return t('mine.common.empty');
  return t(`mine.alarms.recoveryReason.${reason.toLowerCase()}` as App.I18n.I18nKey);
}

function severityType(severity: AlarmSeverity) {
  if (severity === 'CRITICAL') return 'error' as const;
  if (severity === 'WARNING') return 'warning' as const;
  return 'info' as const;
}

function statusType(status: AlarmStatus) {
  if (status === 'CLOSED') return 'default' as const;
  if (status.startsWith('RECOVERED_')) return 'success' as const;
  if (status === 'ACTIVE_ACKNOWLEDGED') return 'warning' as const;
  return 'error' as const;
}

function formatTime(value?: string | null) {
  if (!value) return t('mine.common.empty');
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value));
}

function formatEvidence(value: Record<string, unknown>) {
  return JSON.stringify(value || {}, null, 2);
}

function createIdempotencyKey(prefix: string) {
  const suffix = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `alarm-${prefix}-${suffix}`;
}

function captureProblem(error: unknown) {
  operationProblem.value =
    error instanceof ApiError
      ? { code: error.code, message: error.message, traceId: error.traceId }
      : { code: 'SYS-UNKNOWN-001', message: t('mine.common.operationFailed') };
  window.$message?.error(operationProblem.value.message);
}

defineExpose({ dispositionNote, showDetails, submitAcknowledgement, submitClose });
</script>

<template>
  <div>
    <NSpace vertical :size="16">
      <NPageHeader :title="t('mine.alarms.title')" :subtitle="t('mine.alarms.subtitle')">
        <template #extra>
          <NTag type="error" :bordered="false" round>
            {{ t('mine.alarms.pendingVisible', { count: pendingCount }) }}
          </NTag>
        </template>
      </NPageHeader>

      <NAlert v-if="operationProblem" type="error" :title="t('mine.alarms.error.title')" :bordered="false">
        <NFlex vertical :size="2">
          <NText>{{ operationProblem.message }}</NText>
          <NText code>{{ t('mine.alarms.error.code', { code: operationProblem.code }) }}</NText>
          <NText v-if="operationProblem.traceId" depth="3">
            {{ t('mine.common.traceId', { traceId: operationProblem.traceId }) }}
          </NText>
        </NFlex>
      </NAlert>

      <ListSearchPanel>
        <NFlex data-alarm-filter-row align="center" :wrap="true">
          <NSelect
            v-model:value="query.status"
            clearable
            :options="statusOptions"
            :placeholder="t('mine.alarms.placeholder.status')"
            class="min-w-48 flex-1"
            @update:value="syncStateConstraints"
          />
          <NSelect
            v-model:value="query.severity"
            clearable
            :options="severityOptions"
            :placeholder="t('mine.alarms.placeholder.severity')"
            class="min-w-40 flex-1"
          />
          <NInput
            v-model:value="query.deviceKeyword"
            clearable
            :placeholder="t('mine.alarms.placeholder.device')"
            class="min-w-56 flex-1"
            @keyup.enter="search"
          />
          <NInput
            v-model:value="query.ruleKey"
            clearable
            :placeholder="t('mine.alarms.placeholder.ruleKey')"
            class="min-w-48 flex-1"
            @keyup.enter="search"
          />
          <NDatePicker
            :value="raisedRange"
            type="datetimerange"
            clearable
            :start-placeholder="t('mine.alarms.placeholder.from')"
            :end-placeholder="t('mine.alarms.placeholder.to')"
            class="min-w-64 flex-1"
            @update:value="updateRaisedRange"
          />
          <NSpace data-filter-actions>
            <ListActionButton kind="search" @click="search">{{ t('mine.common.search') }}</ListActionButton>
            <ListActionButton kind="reset" @click="resetQuery">{{ t('mine.common.reset') }}</ListActionButton>
          </NSpace>
        </NFlex>
      </ListSearchPanel>

      <NCard data-list-card :title="t('mine.alarms.listTitle')" :bordered="false" size="small">
        <template #header-extra>
          <ListActionButton kind="refresh" :loading="loading" @click="loadAlarms">
            {{ t('common.refresh') }}
          </ListActionButton>
        </template>
        <NDataTable
          v-if="alarms.length || loading"
          remote
          :loading="loading"
          :columns="columns"
          :data="alarms"
          :scroll-x="1050"
          :pagination="{
            page: query.page,
            pageSize: query.size,
            itemCount: total,
            onChange: (page: number) => {
              query.page = page;
              loadAlarms();
            }
          }"
          :row-key="(row: Alarm) => row.id"
        />
        <NEmpty v-else :description="t('mine.alarms.empty')" />
      </NCard>
    </NSpace>

    <NDrawer v-model:show="drawerVisible" :width="620" placement="right">
      <NDrawerContent :title="t('mine.alarms.detail.title')" closable>
        <NFlex v-if="selectedAlarm" vertical :size="16">
          <NFlex justify="space-between" align="center" :wrap="true">
            <NFlex vertical :size="2">
              <NText strong>{{ selectedAlarm.ruleName }}</NText>
              <NText depth="3">{{ selectedAlarm.deviceName }} · {{ selectedAlarm.deviceCode }}</NText>
            </NFlex>
            <NFlex>
              <NTag :type="severityType(selectedAlarm.severity)" :bordered="false">
                {{ severityLabel(selectedAlarm.severity) }}
              </NTag>
              <NTag :type="statusType(selectedAlarm.status)" :bordered="false">
                {{ statusLabel(selectedAlarm.status) }}
              </NTag>
            </NFlex>
          </NFlex>

          <NDescriptions label-placement="left" bordered :column="1">
            <NDescriptionsItem :label="t('mine.alarms.field.ruleKey')">{{ selectedAlarm.ruleKey }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.alarms.field.source')">
              {{ sourceLabel(selectedAlarm.sourceType) }} · {{ selectedAlarm.propertyId || t('mine.common.empty') }}
            </NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.alarms.field.raisedAt')">{{ formatTime(selectedAlarm.raisedAt) }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.alarms.field.recoveredAt')">{{ formatTime(selectedAlarm.recoveredAt) }}</NDescriptionsItem>
            <NDescriptionsItem v-if="selectedAlarm.recoveredAt" :label="t('mine.alarms.field.recoveryReason')">
              {{ recoveryReasonLabel(selectedAlarm.recoveryReason) }}
            </NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.alarms.field.acknowledgedAt')">{{ formatTime(selectedAlarm.acknowledgedAt) }}</NDescriptionsItem>
            <NDescriptionsItem v-if="selectedAlarm.acknowledgedAt" :label="t('mine.alarms.field.acknowledgement')">
              <NFlex vertical :size="2">
                <NText>{{ selectedAlarm.acknowledgementNote }}</NText>
                <NText code depth="3">{{ selectedAlarm.acknowledgedBy }}</NText>
              </NFlex>
            </NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.alarms.field.closedAt')">{{ formatTime(selectedAlarm.closedAt) }}</NDescriptionsItem>
            <NDescriptionsItem v-if="selectedAlarm.closedAt" :label="t('mine.alarms.field.closure')">
              <NFlex vertical :size="2">
                <NText>{{ selectedAlarm.closeNote }}</NText>
                <NText code depth="3">{{ selectedAlarm.closedBy }}</NText>
              </NFlex>
            </NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.alarms.field.policyRevision')">{{ selectedAlarm.policyRuntimeRevision }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.alarms.field.policyHash')">
              <NText code class="break-all">{{ selectedAlarm.policyContentHash }}</NText>
            </NDescriptionsItem>
          </NDescriptions>

          <NCard :title="t('mine.alarms.detail.triggerEvidence')" size="small" embedded>
            <NText code class="whitespace-pre-wrap break-all">{{ formatEvidence(selectedAlarm.raisedEvidence) }}</NText>
          </NCard>
          <NCard v-if="selectedAlarm.recoveredAt" :title="t('mine.alarms.detail.recoveryEvidence')" size="small" embedded>
            <NText code class="whitespace-pre-wrap break-all">{{ formatEvidence(selectedAlarm.recoveredEvidence) }}</NText>
          </NCard>

          <NDivider>{{ t('mine.alarms.detail.disposition') }}</NDivider>
          <NAlert v-if="!canAcknowledge && !canClose" type="info" :bordered="false">
            {{ t('mine.alarms.detail.noAction') }}
          </NAlert>
          <NForm v-else label-placement="top">
            <NFormItem :label="t('mine.alarms.field.note')" required>
              <NInput
                v-model:value="dispositionNote"
                type="textarea"
                :autosize="{ minRows: 3, maxRows: 6 }"
                :placeholder="t('mine.alarms.placeholder.note')"
              />
            </NFormItem>
            <NFlex justify="end">
              <NButton
                v-if="canAcknowledge"
                type="warning"
                :loading="disposing"
                data-action="acknowledge-alarm"
                @click="requestAcknowledgement"
              >
                {{ t('mine.alarms.action.acknowledge') }}
              </NButton>
              <NButton
                v-if="canClose"
                type="primary"
                :loading="disposing"
                data-action="close-alarm"
                @click="requestClose"
              >
                {{ t('mine.alarms.action.close') }}
              </NButton>
            </NFlex>
          </NForm>
        </NFlex>
        <NEmpty v-else :description="detailLoading ? t('mine.alarms.detail.loading') : t('mine.alarms.empty')" />
      </NDrawerContent>
    </NDrawer>
  </div>
</template>
