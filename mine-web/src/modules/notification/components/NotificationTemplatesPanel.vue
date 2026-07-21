<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  NAlert,
  NButton,
  NDataTable,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NFlex,
  NForm,
  NFormItem,
  NGi,
  NGrid,
  NInput,
  NSelect,
  NSpace,
  NSwitch,
  NTag,
  NText
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import {
  createNotificationTemplate,
  getNotificationTemplates,
  getNotificationTemplateVariables,
  updateNotificationTemplate
} from '@/modules/notification/api/notification';
import type { NotificationTemplate, NotificationTemplateRequest } from '@/modules/notification/api/notification';
import { useAuthStore } from '@/store/modules/auth';

interface TemplateForm {
  id: string | null;
  templateCode: string;
  name: string;
  channelType: NotificationTemplateRequest['channelType'];
  eventType: NotificationTemplateRequest['eventType'];
  locale: NotificationTemplateRequest['locale'];
  subjectTemplate: string;
  bodyTemplate: string;
  providerTemplateCode: string;
  allowedVariables: string[];
  status: 'ENABLED' | 'DISABLED';
  version: number;
}

const { t } = useI18n();
const authStore = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const problem = ref<string | null>(null);
const drawerVisible = ref(false);
const templates = ref<NotificationTemplate[]>([]);
const variables = ref<string[]>([]);
const form = reactive<TemplateForm>(emptyForm());
const canEdit = computed(() => hasPermission(authStore.userInfo.buttons, 'notification:template:edit'));
const editing = computed(() => Boolean(form.id));
const variableOptions = computed<SelectOption[]>(() => variables.value.map(value => ({ label: value, value })));
const channelOptions = computed<SelectOption[]>(() => [
  { label: t('mine.notification.channelType.email'), value: 'EMAIL' },
  { label: t('mine.notification.channelType.sms'), value: 'SMS' },
  { label: t('mine.notification.channelType.voice'), value: 'VOICE' },
  { label: t('mine.notification.channelType.enterpriseIm'), value: 'ENTERPRISE_IM' },
  { label: t('mine.notification.channelType.webhook'), value: 'WEBHOOK' }
]);
const eventOptions = computed<SelectOption[]>(() => [
  'ALARM_RAISED',
  'ALARM_ACKNOWLEDGED',
  'ALARM_RECOVERED',
  'ALARM_CLOSED',
  'TICKET_CREATED',
  'TICKET_ESCALATED',
  'TICKET_CLOSED'
].map(value => ({ label: eventLabel(value as NotificationTemplate['eventType']), value })));
const localeOptions = computed<SelectOption[]>(() => [
  { label: t('mine.notification.template.locale.zhCn'), value: 'zh-CN' },
  { label: t('mine.notification.template.locale.enUs'), value: 'en-US' }
]);
const columns = computed<DataTableColumns<NotificationTemplate>>(() => [
  {
    title: t('mine.notification.template.column.template'),
    key: 'name',
    minWidth: 220,
    render: row => h(NFlex, { vertical: true, size: 1 }, () => [
      h(NText, { strong: true }, () => row.name),
      h(NText, { depth: 3, code: true }, () => row.templateCode)
    ])
  },
  {
    title: t('mine.notification.template.column.route'),
    key: 'route',
    minWidth: 220,
    render: row => h(NSpace, { size: 6 }, () => [
      h(NTag, { bordered: false, type: 'info' }, () => channelTypeLabel(row.channelType)),
      h(NTag, { bordered: false }, () => eventLabel(row.eventType))
    ])
  },
  {
    title: t('mine.notification.template.column.locale'),
    key: 'locale',
    width: 100
  },
  {
    title: t('mine.notification.template.column.variables'),
    key: 'allowedVariables',
    minWidth: 180,
    render: row => row.allowedVariables.length
      ? h(NText, { depth: 2 }, () => row.allowedVariables.join(', '))
      : h(NText, { depth: 3 }, () => t('mine.notification.template.noVariables'))
  },
  {
    title: t('mine.notification.column.status'),
    key: 'status',
    width: 100,
    render: row => h(NTag, { bordered: false, type: row.status === 'ENABLED' ? 'success' : 'default' }, () =>
      row.status === 'ENABLED' ? t('mine.notification.status.enabled') : t('mine.notification.status.disabled')
    )
  },
  {
    title: t('mine.notification.column.action'),
    key: 'actions',
    width: 88,
    render: row => canEdit.value
      ? h(NButton, { text: true, type: 'primary', onClick: () => openEdit(row) }, () =>
          t('mine.notification.action.edit')
        )
      : h(NText, { depth: 3 }, () => t('mine.notification.readOnly'))
  }
]);

onMounted(loadTemplates);

async function loadTemplates() {
  loading.value = true;
  problem.value = null;
  try {
    const [catalog, list] = await Promise.all([
      getNotificationTemplateVariables(),
      getNotificationTemplates()
    ]);
    variables.value = catalog;
    templates.value = list;
  } catch (error) {
    problem.value = errorMessage(error);
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  Object.assign(form, emptyForm());
  drawerVisible.value = true;
}

function openEdit(template: NotificationTemplate) {
  Object.assign(form, {
    id: template.id,
    templateCode: template.templateCode,
    name: template.name,
    channelType: template.channelType,
    eventType: template.eventType,
    locale: template.locale,
    subjectTemplate: template.subjectTemplate ?? '',
    bodyTemplate: template.bodyTemplate,
    providerTemplateCode: template.providerTemplateCode ?? '',
    allowedVariables: [...template.allowedVariables],
    status: template.status,
    version: template.version
  });
  drawerVisible.value = true;
}

async function saveTemplate() {
  if (!canEdit.value || saving.value || !form.templateCode.trim()
      || !form.name.trim() || !form.bodyTemplate.trim()) {
    window.$message?.warning(t('mine.notification.template.message.required'));
    return;
  }
  const body: NotificationTemplateRequest = {
    templateCode: form.templateCode.trim(),
    name: form.name.trim(),
    channelType: form.channelType,
    eventType: form.eventType,
    locale: form.locale,
    subjectTemplate: form.subjectTemplate.trim() || null,
    bodyTemplate: form.bodyTemplate,
    providerTemplateCode: form.providerTemplateCode.trim() || null,
    allowedVariables: [...form.allowedVariables],
    status: form.status,
    version: form.version
  };
  saving.value = true;
  try {
    if (form.id) {
      await updateNotificationTemplate(form.id, body);
    } else {
      await createNotificationTemplate(body);
    }
    drawerVisible.value = false;
    window.$message?.success(t('mine.notification.template.message.saved'));
    await loadTemplates();
  } catch (error) {
    window.$message?.error(errorMessage(error));
  } finally {
    saving.value = false;
  }
}

function eventLabel(event: NotificationTemplate['eventType']) {
  const key = {
    ALARM_RAISED: 'alarmRaised',
    ALARM_ACKNOWLEDGED: 'alarmAcknowledged',
    ALARM_RECOVERED: 'alarmRecovered',
    ALARM_CLOSED: 'alarmClosed',
    TICKET_CREATED: 'ticketCreated',
    TICKET_ESCALATED: 'ticketEscalated',
    TICKET_CLOSED: 'ticketClosed'
  }[event] as 'alarmRaised' | 'alarmAcknowledged' | 'alarmRecovered' | 'alarmClosed'
    | 'ticketCreated' | 'ticketEscalated' | 'ticketClosed';
  return t(`mine.notification.event.${key}`);
}

function channelTypeLabel(type: NotificationTemplate['channelType']) {
  const key = {
    EMAIL: 'email',
    SMS: 'sms',
    VOICE: 'voice',
    ENTERPRISE_IM: 'enterpriseIm',
    WEBHOOK: 'webhook'
  }[type] as 'email' | 'sms' | 'voice' | 'enterpriseIm' | 'webhook';
  return t(`mine.notification.channelType.${key}`);
}

function errorMessage(error: unknown) {
  if (error instanceof ApiError) return error.message;
  return error instanceof Error && error.message ? error.message : t('mine.notification.message.loadFailed');
}

function emptyForm(): TemplateForm {
  return {
    id: null,
    templateCode: '',
    name: '',
    channelType: 'EMAIL',
    eventType: 'ALARM_RAISED',
    locale: 'zh-CN',
    subjectTemplate: '',
    bodyTemplate: '',
    providerTemplateCode: '',
    allowedVariables: [],
    status: 'DISABLED',
    version: 0
  };
}
</script>

<template>
  <NFlex vertical :size="20">
    <NFlex justify="space-between" align="center" wrap>
      <div>
        <NText strong>{{ t('mine.notification.template.title') }}</NText>
        <div><NText depth="3">{{ t('mine.notification.template.subtitle') }}</NText></div>
      </div>
      <NSpace>
        <NButton :loading="loading" @click="loadTemplates">{{ t('mine.notification.action.refresh') }}</NButton>
        <NButton v-if="canEdit" type="primary" data-action="create-template" @click="openCreate">
          {{ t('mine.notification.template.action.create') }}
        </NButton>
      </NSpace>
    </NFlex>

    <NAlert type="info" :title="t('mine.notification.template.variableTitle')">
      {{ t('mine.notification.template.variableHint') }}
      <NSpace class="mt-2" size="small">
        <NTag v-for="variable in variables" :key="variable" size="small" :bordered="false">{{ variable }}</NTag>
      </NSpace>
    </NAlert>
    <NAlert v-if="problem" type="error" :title="t('mine.notification.message.loadFailed')">{{ problem }}</NAlert>

    <NDataTable :columns="columns" :data="templates" :loading="loading" :row-key="row => row.id" :scroll-x="1020">
      <template #empty>
        <NEmpty :description="t('mine.notification.template.empty')" />
      </template>
    </NDataTable>

    <NDrawer v-model:show="drawerVisible" :width="680" placement="right">
      <NDrawerContent :title="editing ? t('mine.notification.template.drawer.edit') : t('mine.notification.template.drawer.create')" closable>
        <NForm label-placement="top">
          <NGrid responsive="screen" cols="1 m:2" :x-gap="16">
            <NGi>
              <NFormItem :label="t('mine.notification.template.form.code')" required>
                <NInput v-model:value="form.templateCode" :disabled="editing" :maxlength="64" />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.notification.template.form.name')" required>
                <NInput v-model:value="form.name" :maxlength="128" />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.notification.template.form.channel')" required>
                <NSelect v-model:value="form.channelType" :options="channelOptions" />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.notification.template.form.event')" required>
                <NSelect v-model:value="form.eventType" :options="eventOptions" />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.notification.template.form.locale')" required>
                <NSelect v-model:value="form.locale" :options="localeOptions" />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.notification.template.form.providerCode')">
                <NInput v-model:value="form.providerTemplateCode" :maxlength="128" />
              </NFormItem>
            </NGi>
          </NGrid>
          <NFormItem :label="t('mine.notification.template.form.variables')">
            <NSelect v-model:value="form.allowedVariables" multiple filterable :options="variableOptions" />
          </NFormItem>
          <NFormItem :label="t('mine.notification.template.form.subject')">
            <NInput v-model:value="form.subjectTemplate" :maxlength="1000" />
          </NFormItem>
          <NFormItem :label="t('mine.notification.template.form.body')" required>
            <NInput v-model:value="form.bodyTemplate" type="textarea" :autosize="{ minRows: 7, maxRows: 16 }" :maxlength="16384" show-count />
          </NFormItem>
          <NFormItem :label="t('mine.notification.form.enabled')">
            <NSwitch :value="form.status === 'ENABLED'" @update:value="value => form.status = value ? 'ENABLED' : 'DISABLED'" />
          </NFormItem>
        </NForm>
        <template #footer>
          <NSpace justify="end">
            <NButton @click="drawerVisible = false">{{ t('mine.notification.action.cancel') }}</NButton>
            <NButton type="primary" :loading="saving" @click="saveTemplate">{{ t('mine.notification.template.action.save') }}</NButton>
          </NSpace>
        </template>
      </NDrawerContent>
    </NDrawer>
  </NFlex>
</template>
