<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  NButton,
  NCard,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NFlex,
  NForm,
  NFormItem,
  NGi,
  NGrid,
  NInput,
  NInputNumber,
  NModal,
  NPageHeader,
  NSelect,
  NSpace,
  NTag,
  NText
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import {
  cancelCommand,
  createCommand,
  getCommand,
  getCommands,
  getCommandTargets,
  getCommandTemplates
} from '@/modules/command/api/command';
import type { Command, CommandStatus, CommandTarget, CommandTemplate } from '@/modules/command/api/command';
import CommandTimeline from '@/modules/command/components/CommandTimeline.vue';
import { useAuthStore } from '@/store/modules/auth';

const { t, locale } = useI18n();
const authStore = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const commands = ref<Command[]>([]);
const targets = ref<CommandTarget[]>([]);
const templates = ref<CommandTemplate[]>([]);
const total = ref(0);
const modalVisible = ref(false);
const drawerVisible = ref(false);
const cancelModalVisible = ref(false);
const cancellingCommand = ref<Command | null>(null);
const cancelReason = ref('');
const selectedCommand = ref<Command | null>(null);
const currentIdempotencyKey = ref('');
const query = reactive<{
  deviceId: string | null;
  status: CommandStatus | null;
  templateKey: string | null;
  page: number;
  size: number;
}>({
  deviceId: null,
  status: null,
  templateKey: null,
  page: 1,
  size: 20
});
const commandForm = reactive({
  deviceId: null as string | null,
  templateKey: null as string | null,
  parameters: {} as Record<string, unknown>,
  reason: '',
  workOrderNo: '',
  ttlSeconds: 300,
  timeoutSeconds: 30
});
const commandStatuses: CommandStatus[] = [
  'CREATED',
  'QUEUED',
  'SENT',
  'DEVICE_RECEIVED',
  'SUCCEEDED',
  'REFUSED',
  'FAILED',
  'EXPIRED',
  'TIMEOUT',
  'CANCELLED'
];

const deviceMap = computed(() => new Map(targets.value.map(device => [device.id, device])));
const deviceOptions = computed<SelectOption[]>(() =>
  targets.value.map(device => ({
    label: `${device.name} (${device.deviceCode})`,
    value: device.id
  }))
);
const templateOptions = computed<SelectOption[]>(() =>
  templates.value.map(template => ({
    label: templateLabel(template.key),
    value: template.key
  }))
);
const selectedTemplate = computed(() => templates.value.find(item => item.key === commandForm.templateKey) || null);
const parameterFields = computed(() => {
  const schema = selectedTemplate.value?.parameterSchema as Record<string, unknown> | undefined;
  const properties = schema?.properties as Record<string, Record<string, unknown>> | undefined;
  const required = new Set(Array.isArray(schema?.required) ? (schema.required as string[]) : []);
  return Object.entries(properties || {}).map(([key, definition]) => ({
    key,
    type: String(definition.type || 'string'),
    minimum: typeof definition.minimum === 'number' ? definition.minimum : undefined,
    maximum: typeof definition.maximum === 'number' ? definition.maximum : undefined,
    required: required.has(key)
  }));
});
const statusOptions = computed<SelectOption[]>(() =>
  commandStatuses.map(status => ({
    label: statusLabel(status),
    value: status
  }))
);
const columns = computed<DataTableColumns<Command>>(() => [
  {
    title: t('mine.commands.column.device'),
    key: 'deviceId',
    minWidth: 180,
    render: row => deviceMap.value.get(row.deviceId)?.name || row.deviceId
  },
  {
    title: t('mine.commands.column.template'),
    key: 'templateKey',
    minWidth: 140,
    render: row => templateLabel(row.templateKey)
  },
  {
    title: t('mine.commands.column.status'),
    key: 'status',
    width: 130,
    render: row =>
      h(NTag, { size: 'small', bordered: false, type: statusType(row.status) }, () => statusLabel(row.status))
  },
  {
    title: t('mine.commands.column.createdAt'),
    key: 'createdAt',
    minWidth: 180,
    render: row => formatTime(row.createdAt)
  },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 160,
    render: row =>
      h(NSpace, { size: 8 }, () => [
        h(NButton, { text: true, type: 'primary', onClick: () => showDetails(row) }, () =>
          t('mine.commands.action.detail')
        ),
        row.status === 'QUEUED' && can('command:instance:cancel')
          ? h(NButton, { text: true, type: 'error', onClick: () => openCancel(row) }, () =>
              t('mine.commands.action.cancel')
            )
          : null
      ])
  }
]);

onMounted(async () => {
  await Promise.all([loadTargets(), loadTemplates(), loadCommands()]);
});

async function loadTargets(keyword = '') {
  try {
    const response = await getCommandTargets({ keyword, page: 1, size: 100 });
    targets.value = response.items;
  } catch (error) {
    showError(error);
  }
}

async function loadTemplates() {
  if (!can('command:template:view')) return;
  try {
    templates.value = await getCommandTemplates();
  } catch (error) {
    showError(error);
  }
}

async function loadCommands() {
  loading.value = true;
  try {
    const response = await getCommands(query);
    commands.value = response.items;
    total.value = response.total;
    query.page = response.page;
    query.size = response.size;
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
}

function search() {
  query.page = 1;
  return loadCommands();
}

function resetQuery() {
  Object.assign(query, { deviceId: null, status: null, templateKey: null, page: 1 });
  return loadCommands();
}

function openCreate() {
  const template = templates.value[0];
  Object.assign(commandForm, {
    deviceId: targets.value[0]?.id || null,
    templateKey: template?.key || null,
    parameters: {},
    reason: '',
    workOrderNo: '',
    ttlSeconds: template?.defaultTtlSeconds || 300,
    timeoutSeconds: template?.defaultTimeoutSeconds || 30
  });
  currentIdempotencyKey.value = createIdempotencyKey();
  modalVisible.value = true;
}

function syncTemplateDefaults(templateKey: string) {
  const template = templates.value.find(item => item.key === templateKey);
  if (!template) return;
  commandForm.parameters = {};
  commandForm.ttlSeconds = template.defaultTtlSeconds;
  commandForm.timeoutSeconds = template.defaultTimeoutSeconds;
}

function requestSubmit() {
  if (!commandForm.deviceId || !commandForm.templateKey) {
    window.$message?.warning(t('mine.commands.validation.required'));
    return;
  }
  const template = templates.value.find(item => item.key === commandForm.templateKey);
  if (!template || (template.requiresReason && !commandForm.reason.trim())) {
    window.$message?.warning(t('mine.commands.validation.reasonRequired'));
    return;
  }
  if (parameterFields.value.some(field => field.required && commandForm.parameters[field.key] === undefined)) {
    window.$message?.warning(t('mine.commands.validation.parametersRequired'));
    return;
  }
  window.$dialog?.warning({
    title: t('mine.commands.confirm.title'),
    content: t('mine.commands.confirm.content', {
      device: deviceMap.value.get(commandForm.deviceId)?.name || commandForm.deviceId,
      template: templateLabel(commandForm.templateKey)
    }),
    positiveText: t('mine.commands.action.confirmSend'),
    negativeText: t('mine.common.cancel'),
    onPositiveClick: submitCommand
  });
}

async function submitCommand() {
  const { deviceId, templateKey } = commandForm;
  if (!deviceId || !templateKey) return;
  saving.value = true;
  try {
    await createCommand(
      {
        deviceId,
        templateKey,
        parameters: commandForm.parameters,
        reason: commandForm.reason.trim() || undefined,
        workOrderNo: commandForm.workOrderNo.trim() || undefined,
        ttlSeconds: commandForm.ttlSeconds,
        timeoutSeconds: commandForm.timeoutSeconds
      },
      currentIdempotencyKey.value
    );
    window.$message?.success(t('mine.commands.message.created'));
    modalVisible.value = false;
    await loadCommands();
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

function setParameter(key: string, value: string | number | null) {
  if (value === null || value === '') Reflect.deleteProperty(commandForm.parameters, key);
  else commandForm.parameters[key] = value;
}

function getParameterLabel(key: string) {
  return t(`mine.commands.parameter.${key}` as App.I18n.I18nKey);
}

function getNumberParameter(key: string) {
  const value = commandForm.parameters[key];
  return typeof value === 'number' ? value : undefined;
}

function getStringParameter(key: string) {
  const value = commandForm.parameters[key];
  return typeof value === 'string' ? value : undefined;
}

function openCancel(command: Command) {
  cancellingCommand.value = command;
  cancelReason.value = '';
  cancelModalVisible.value = true;
}

async function performCancel() {
  if (!cancellingCommand.value || !cancelReason.value.trim()) {
    return window.$message?.warning(t('mine.commands.validation.cancelReasonRequired'));
  }
  saving.value = true;
  try {
    await cancelCommand(cancellingCommand.value.id, cancellingCommand.value.version, cancelReason.value.trim());
    cancelModalVisible.value = false;
    window.$message?.success(t('mine.commands.message.cancelled'));
    await loadCommands();
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

async function showDetails(command: Command) {
  selectedCommand.value = command;
  drawerVisible.value = true;
  await refreshDetails();
}

async function refreshDetails() {
  if (!selectedCommand.value) return;
  try {
    selectedCommand.value = await getCommand(selectedCommand.value.id);
  } catch (error) {
    showError(error);
  }
}

function can(permission: string) {
  return hasPermission(authStore.userInfo.buttons, permission);
}

function templateLabel(key: string) {
  const labels: Record<string, App.I18n.I18nKey> = {
    QUERY_STATUS: 'mine.commands.template.queryStatus',
    SET_REPORT_INTERVAL: 'mine.commands.template.setReportInterval',
    REQUEST_LOCATION: 'mine.commands.template.requestLocation'
  };
  return labels[key] ? t(labels[key]) : key;
}

function statusLabel(status: CommandStatus) {
  return t(`mine.commands.status.${status.toLowerCase()}` as App.I18n.I18nKey);
}

function statusType(status: CommandStatus) {
  if (status === 'SUCCEEDED') return 'success' as const;
  if (status === 'REFUSED' || status === 'EXPIRED' || status === 'TIMEOUT') return 'warning' as const;
  if (status === 'FAILED' || status === 'CANCELLED') return 'error' as const;
  return 'info' as const;
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value));
}

function formatJson(value: Record<string, unknown> | null | undefined) {
  return JSON.stringify(value || {}, null, 2);
}

function createIdempotencyKey() {
  return globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function showError(error: unknown) {
  const message =
    error instanceof ApiError && error.traceId
      ? `${t('mine.common.requestFailed')} ${t('mine.common.traceId', { traceId: error.traceId })}`
      : t('mine.common.requestFailed');
  window.$message?.error(message);
}

defineExpose({ commandForm, openCreate, requestSubmit, submitCommand, showDetails, currentIdempotencyKey });
</script>

<template>
  <div>
    <NSpace vertical :size="16">
      <NPageHeader :title="t('mine.commands.title')" :subtitle="t('mine.commands.subtitle')">
        <template #extra>
          <NSpace>
            <NButton :loading="loading" @click="loadCommands">{{ t('common.refresh') }}</NButton>
            <NButton
              v-if="can('command:instance:create') && can('command:template:view')"
              type="primary"
              @click="openCreate"
            >
              {{ t('mine.commands.action.create') }}
            </NButton>
          </NSpace>
        </template>
      </NPageHeader>

      <NCard>
        <NFlex data-command-filter-row align="center">
          <NSelect
            v-model:value="query.deviceId"
            clearable
            filterable
            remote
            :options="deviceOptions"
            :placeholder="t('mine.commands.placeholder.device')"
            class="min-w-56 flex-1"
            @search="loadTargets"
          />
          <NSelect
            v-model:value="query.templateKey"
            clearable
            :options="templateOptions"
            :placeholder="t('mine.commands.placeholder.template')"
            class="min-w-56 flex-1"
          />
          <NSelect
            v-model:value="query.status"
            clearable
            :options="statusOptions"
            :placeholder="t('mine.commands.placeholder.status')"
            class="min-w-56 flex-1"
          />
          <NSpace data-filter-actions>
            <NButton type="primary" @click="search">{{ t('mine.common.search') }}</NButton>
            <NButton @click="resetQuery">{{ t('mine.common.reset') }}</NButton>
          </NSpace>
        </NFlex>
      </NCard>

      <NCard>
        <NDataTable
          v-if="commands.length || loading"
          remote
          :loading="loading"
          :columns="columns"
          :data="commands"
          :pagination="{
            page: query.page,
            pageSize: query.size,
            itemCount: total,
            onChange: (page: number) => {
              query.page = page;
              loadCommands();
            }
          }"
          :row-key="(row: Command) => row.id"
        />
        <NEmpty v-else :description="t('mine.commands.empty')" />
      </NCard>
    </NSpace>

    <NModal v-model:show="modalVisible" preset="card" :title="t('mine.commands.modal.create')" class="max-w-92vw w-150">
      <NForm label-placement="top">
        <NFormItem :label="t('mine.commands.field.device')" required>
          <NSelect
            v-model:value="commandForm.deviceId"
            filterable
            remote
            :options="deviceOptions"
            :placeholder="t('mine.commands.placeholder.device')"
            @search="loadTargets"
          />
        </NFormItem>
        <NFormItem :label="t('mine.commands.field.template')" required>
          <NSelect
            v-model:value="commandForm.templateKey"
            :options="templateOptions"
            :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.commands.field.template') })"
            @update:value="syncTemplateDefaults"
          />
        </NFormItem>
        <NFormItem
          v-for="field in parameterFields"
          :key="field.key"
          :label="getParameterLabel(field.key)"
          :required="field.required"
        >
          <NInputNumber
            v-if="field.type === 'integer' || field.type === 'number'"
            :value="getNumberParameter(field.key)"
            :min="field.minimum"
            :max="field.maximum"
            :placeholder="t('mine.common.enterPlaceholder', { field: getParameterLabel(field.key) })"
            @update:value="value => setParameter(field.key, value)"
          />
          <NInput
            v-else
            :value="getStringParameter(field.key)"
            :placeholder="t('mine.common.enterPlaceholder', { field: getParameterLabel(field.key) })"
            @update:value="value => setParameter(field.key, value)"
          />
        </NFormItem>
        <NGrid responsive="screen" cols="1 s:2" :x-gap="12">
          <NGi>
            <NFormItem :label="t('mine.commands.field.reason')" :required="selectedTemplate?.requiresReason">
              <NInput
                v-model:value="commandForm.reason"
                type="textarea"
                :maxlength="1000"
                show-count
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.commands.field.reason') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.commands.field.workOrderNo')">
              <NInput
                v-model:value="commandForm.workOrderNo"
                :maxlength="128"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.commands.field.workOrderNo') })"
              />
            </NFormItem>
          </NGi>
        </NGrid>
        <NGrid responsive="screen" cols="1 s:2" :x-gap="12">
          <NGi>
            <NFormItem :label="t('mine.commands.field.ttl')">
              <NInputNumber
                v-model:value="commandForm.ttlSeconds"
                :min="1"
                :max="86400"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.commands.field.ttl') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.commands.field.timeout')">
              <NInputNumber
                v-model:value="commandForm.timeoutSeconds"
                :min="1"
                :max="86400"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.commands.field.timeout') })"
              />
            </NFormItem>
          </NGi>
        </NGrid>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="modalVisible = false">{{ t('mine.common.cancel') }}</NButton>
          <NButton type="primary" :loading="saving" @click="requestSubmit">
            {{ t('mine.commands.action.send') }}
          </NButton>
        </NSpace>
      </template>
    </NModal>

    <NDrawer v-model:show="drawerVisible" width="min(520px, 92vw)">
      <NDrawerContent :title="t('mine.commands.detail.title')" closable>
        <template v-if="selectedCommand">
          <NSpace vertical :size="16">
            <NSpace justify="end">
              <NButton text :loading="loading" @click="refreshDetails">{{ t('common.refresh') }}</NButton>
            </NSpace>
            <NDescriptions label-placement="left" :column="1" bordered>
              <NDescriptionsItem :label="t('mine.commands.field.device')">
                {{ deviceMap.get(selectedCommand.deviceId)?.name || selectedCommand.deviceId }}
              </NDescriptionsItem>
              <NDescriptionsItem :label="t('mine.commands.field.template')">
                {{ templateLabel(selectedCommand.templateKey) }}
              </NDescriptionsItem>
              <NDescriptionsItem :label="t('mine.commands.field.status')">
                <NTag :type="statusType(selectedCommand.status)">{{ statusLabel(selectedCommand.status) }}</NTag>
              </NDescriptionsItem>
              <NDescriptionsItem :label="t('mine.commands.field.id')">{{ selectedCommand.id }}</NDescriptionsItem>
              <NDescriptionsItem :label="t('mine.commands.field.ttl')">
                {{ selectedCommand.ttlSeconds }}
              </NDescriptionsItem>
              <NDescriptionsItem :label="t('mine.commands.field.timeout')">
                {{ selectedCommand.timeoutSeconds }}
              </NDescriptionsItem>
              <NDescriptionsItem :label="t('mine.commands.field.parameters')">
                <NText code>{{ formatJson(selectedCommand.parameters) }}</NText>
              </NDescriptionsItem>
              <NDescriptionsItem :label="t('mine.commands.field.reason')">
                {{ selectedCommand.reason || t('mine.common.empty') }}
              </NDescriptionsItem>
              <NDescriptionsItem :label="t('mine.commands.field.workOrderNo')">
                {{ selectedCommand.workOrderNo || t('mine.common.empty') }}
              </NDescriptionsItem>
              <NDescriptionsItem v-if="selectedCommand.cancelReason" :label="t('mine.commands.field.cancelReason')">
                {{ selectedCommand.cancelReason }}
              </NDescriptionsItem>
              <NDescriptionsItem :label="t('mine.commands.field.reply')">
                <NText code>{{ formatJson(selectedCommand.reply) }}</NText>
              </NDescriptionsItem>
              <NDescriptionsItem :label="t('mine.commands.field.errorCode')">
                {{ selectedCommand.lastErrorCode || t('mine.common.empty') }}
              </NDescriptionsItem>
            </NDescriptions>
            <NCard :title="t('mine.commands.detail.timeline')" embedded>
              <CommandTimeline :command="selectedCommand" />
            </NCard>
          </NSpace>
        </template>
      </NDrawerContent>
    </NDrawer>

    <NModal
      v-model:show="cancelModalVisible"
      preset="card"
      :title="t('mine.commands.modal.cancel')"
      class="max-w-92vw w-120"
    >
      <NForm label-placement="top">
        <NFormItem :label="t('mine.commands.field.cancelReason')" required>
          <NInput
            v-model:value="cancelReason"
            type="textarea"
            :maxlength="500"
            show-count
            :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.commands.field.cancelReason') })"
          />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="cancelModalVisible = false">{{ t('mine.common.cancel') }}</NButton>
          <NButton type="error" :loading="saving" @click="performCancel">
            {{ t('mine.commands.action.confirmCancel') }}
          </NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>
