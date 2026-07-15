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
  NForm,
  NFormItem,
  NGi,
  NGrid,
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
const selectedCommand = ref<Command | null>(null);
const currentIdempotencyKey = ref('');
const query = reactive<{ deviceId: string; status: CommandStatus | ''; page: number; size: number }>({
  deviceId: '',
  status: '',
  page: 1,
  size: 20
});
const commandForm = reactive({ deviceId: '', templateKey: '', ttlSeconds: 300, timeoutSeconds: 30 });
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
  templates.value.filter(supportsEmptyParameters).map(template => ({
    label: templateLabel(template.key),
    value: template.key
  }))
);
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
    width: 100,
    render: row =>
      h(NButton, { text: true, type: 'primary', onClick: () => showDetails(row) }, () =>
        t('mine.commands.action.detail')
      )
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
  Object.assign(query, { deviceId: '', status: '', page: 1 });
  return loadCommands();
}

function openCreate() {
  const template = templates.value.find(supportsEmptyParameters);
  Object.assign(commandForm, {
    deviceId: targets.value[0]?.id || '',
    templateKey: template?.key || '',
    ttlSeconds: template?.defaultTtlSeconds || 300,
    timeoutSeconds: template?.defaultTimeoutSeconds || 30
  });
  currentIdempotencyKey.value = createIdempotencyKey();
  modalVisible.value = true;
}

function syncTemplateDefaults(templateKey: string) {
  const template = templates.value.find(item => item.key === templateKey && supportsEmptyParameters(item));
  if (!template) return;
  commandForm.ttlSeconds = template.defaultTtlSeconds;
  commandForm.timeoutSeconds = template.defaultTimeoutSeconds;
}

function requestSubmit() {
  if (!commandForm.deviceId || !commandForm.templateKey) {
    window.$message?.warning(t('mine.commands.validation.required'));
    return;
  }
  const template = templates.value.find(item => item.key === commandForm.templateKey);
  if (!template || !supportsEmptyParameters(template)) {
    window.$message?.warning(t('mine.commands.validation.unsupportedParameters'));
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
  saving.value = true;
  try {
    await createCommand(
      {
        deviceId: commandForm.deviceId,
        templateKey: commandForm.templateKey,
        parameters: {},
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
  return key === 'QUERY_STATUS' ? t('mine.commands.template.queryStatus') : key;
}

function supportsEmptyParameters(template: CommandTemplate) {
  const schema = template.parameterSchema;
  const properties = schema.properties;
  return schema.type === 'object'
    && schema.additionalProperties === false
    && typeof properties === 'object'
    && properties !== null
    && Object.keys(properties).length === 0;
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
      <NGrid responsive="screen" cols="1 s:2 m:4" :x-gap="12" :y-gap="12">
        <NGi>
          <NSelect
            v-model:value="query.deviceId"
            clearable
            filterable
            remote
            :options="deviceOptions"
            :placeholder="t('mine.commands.placeholder.device')"
            @search="loadTargets"
          />
        </NGi>
        <NGi>
          <NSelect
            v-model:value="query.status"
            clearable
            :options="statusOptions"
            :placeholder="t('mine.commands.placeholder.status')"
          />
        </NGi>
        <NGi span="1 s:2">
          <NSpace>
            <NButton type="primary" @click="search">{{ t('mine.common.search') }}</NButton>
            <NButton @click="resetQuery">{{ t('mine.common.reset') }}</NButton>
          </NSpace>
        </NGi>
      </NGrid>
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
          @update:value="syncTemplateDefaults"
        />
      </NFormItem>
      <NGrid responsive="screen" cols="1 s:2" :x-gap="12">
        <NGi>
          <NFormItem :label="t('mine.commands.field.ttl')">
            <NInputNumber v-model:value="commandForm.ttlSeconds" :min="1" :max="86400" />
          </NFormItem>
        </NGi>
        <NGi>
          <NFormItem :label="t('mine.commands.field.timeout')">
            <NInputNumber v-model:value="commandForm.timeoutSeconds" :min="1" :max="86400" />
          </NFormItem>
        </NGi>
      </NGrid>
    </NForm>
    <template #footer>
      <NSpace justify="end">
        <NButton @click="modalVisible = false">{{ t('mine.common.cancel') }}</NButton>
        <NButton type="primary" :loading="saving" @click="requestSubmit">{{ t('mine.commands.action.send') }}</NButton>
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
            <NDescriptionsItem :label="t('mine.commands.field.ttl')">{{ selectedCommand.ttlSeconds }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.commands.field.timeout')">
              {{ selectedCommand.timeoutSeconds }}
            </NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.commands.field.parameters')">
              <NText code>{{ formatJson(selectedCommand.parameters) }}</NText>
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
</template>
