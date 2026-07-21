<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NDivider,
  NDrawer,
  NDrawerContent,
  NDynamicTags,
  NEmpty,
  NFlex,
  NForm,
  NFormItem,
  NGi,
  NGrid,
  NInput,
  NInputNumber,
  NPageHeader,
  NSelect,
  NSpace,
  NSwitch,
  NTag,
  NText
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import {
  createNotificationChannel,
  getNotificationChannel,
  getNotificationChannels,
  getNotificationProviders,
  revealNotificationChannelSecrets,
  updateNotificationChannel
} from '@/modules/notification/api/notification';
import type {
  NotificationChannel,
  NotificationChannelRequest,
  NotificationProviderDefinition,
  NotificationProviderField
} from '@/modules/notification/api/notification';
import { useAuthStore } from '@/store/modules/auth';

interface ChannelForm {
  id: string | null;
  channelCode: string;
  name: string;
  provider: string;
  status: 'ENABLED' | 'DISABLED';
  configuration: Record<string, unknown>;
  secrets: Record<string, string>;
  connectTimeoutMs: number;
  readTimeoutMs: number;
  version: number;
}

const fieldLabelKeys: Readonly<Record<string, App.I18n.I18nKey>> = {
  host: 'mine.notification.field.host',
  port: 'mine.notification.field.port',
  securityMode: 'mine.notification.field.securityMode',
  fromAddress: 'mine.notification.field.fromAddress',
  fromName: 'mine.notification.field.fromName',
  replyTo: 'mine.notification.field.replyTo',
  username: 'mine.notification.field.username',
  password: 'mine.notification.field.password',
  webhookUrl: 'mine.notification.field.webhookUrl',
  signingSecret: 'mine.notification.field.signingSecret',
  allowedHosts: 'mine.notification.field.allowedHosts',
  signatureHeader: 'mine.notification.field.signatureHeader',
  endpointUrl: 'mine.notification.field.endpointUrl',
  bearerToken: 'mine.notification.field.bearerToken',
  endpoint: 'mine.notification.field.endpoint',
  regionId: 'mine.notification.field.regionId',
  signName: 'mine.notification.field.signName',
  templateCode: 'mine.notification.field.templateCode',
  accessKeyId: 'mine.notification.field.accessKeyId',
  accessKeySecret: 'mine.notification.field.accessKeySecret',
  calledShowNumber: 'mine.notification.field.calledShowNumber',
  ttsCode: 'mine.notification.field.ttsCode',
  playTimes: 'mine.notification.field.playTimes',
  volume: 'mine.notification.field.volume',
  speed: 'mine.notification.field.speed'
};

const { t, locale } = useI18n();
const authStore = useAuthStore();
const loading = ref(false);
const detailLoading = ref(false);
const saving = ref(false);
const drawerVisible = ref(false);
const problem = ref<string | null>(null);
const secretProblem = ref<string | null>(null);
const providers = ref<NotificationProviderDefinition[]>([]);
const channels = ref<NotificationChannel[]>([]);
const secretsLoaded = ref(false);
const form = reactive<ChannelForm>(emptyForm());

const canEdit = computed(() => can('notification:channel:edit'));
const canRevealSecrets = computed(() => can('notification:channel:secret:view'));
const editing = computed(() => Boolean(form.id));
const selectedProvider = computed(() => providers.value.find(item => item.key.provider === form.provider) ?? null);
const providerOptions = computed<SelectOption[]>(() =>
  providers.value.map(item => ({ label: providerLabel(item), value: item.key.provider }))
);
const columns = computed<DataTableColumns<NotificationChannel>>(() => [
  {
    title: t('mine.notification.column.name'),
    key: 'name',
    minWidth: 190,
    render: row => h(NFlex, { vertical: true, size: 1 }, () => [
      h(NText, { strong: true }, () => row.name),
      h(NText, { depth: 3, code: true }, () => row.channelCode)
    ])
  },
  {
    title: t('mine.notification.column.provider'),
    key: 'provider',
    minWidth: 160,
    render: row => h(NTag, { bordered: false, type: 'info' }, () => providerLabelByCode(row.provider))
  },
  {
    title: t('mine.notification.column.status'),
    key: 'status',
    width: 100,
    render: row => h(NTag, { bordered: false, type: row.status === 'ENABLED' ? 'success' : 'default' }, () =>
      statusLabel(row.status)
    )
  },
  {
    title: t('mine.notification.column.credentials'),
    key: 'secretConfigured',
    width: 120,
    render: row => h(NTag, { bordered: false, type: row.secretConfigured ? 'success' : 'warning' }, () =>
      row.secretConfigured
        ? t('mine.notification.credential.configured')
        : t('mine.notification.credential.missing')
    )
  },
  {
    title: t('mine.notification.column.timeout'),
    key: 'timeout',
    minWidth: 150,
    render: row => t('mine.notification.timeoutSummary', {
      connect: row.connectTimeoutMs,
      read: row.readTimeoutMs
    })
  },
  {
    title: t('mine.notification.column.updatedAt'),
    key: 'updatedAt',
    minWidth: 180,
    render: row => formatTime(row.updatedAt)
  },
  {
    title: t('mine.notification.column.action'),
    key: 'actions',
    width: 88,
    fixed: 'right',
    render: row => canEdit.value
      ? h(NButton, {
          text: true,
          type: 'primary',
          'data-action': 'edit-channel',
          onClick: () => openEdit(row)
        }, () => t('mine.notification.action.edit'))
      : h(NText, { depth: 3 }, () => t('mine.notification.readOnly'))
  }
]);

onMounted(loadChannels);

async function loadChannels() {
  loading.value = true;
  problem.value = null;
  try {
    const [providerCatalog, channelList] = await Promise.all([
      getNotificationProviders(),
      getNotificationChannels()
    ]);
    providers.value = providerCatalog;
    channels.value = channelList;
  } catch (error) {
    problem.value = errorMessage(error);
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  const definition = providers.value[0];
  if (!definition || !canEdit.value) return;
  Object.assign(form, emptyForm());
  form.provider = definition.key.provider;
  resetProviderValues(definition);
  secretsLoaded.value = true;
  secretProblem.value = null;
  drawerVisible.value = true;
}

async function openEdit(value: NotificationChannel) {
  if (!canEdit.value) return;
  drawerVisible.value = true;
  detailLoading.value = true;
  secretProblem.value = null;
  secretsLoaded.value = false;
  try {
    const detail = await getNotificationChannel(value.id);
    applyDetail(detail);
    if (canRevealSecrets.value) {
      try {
        const revealed = await revealNotificationChannelSecrets(detail.id);
        if (revealed.version !== detail.version || revealed.provider !== detail.provider) {
          throw new Error(t('mine.notification.message.secretVersionChanged'));
        }
        selectedProvider.value?.secretFields.forEach(field => {
          form.secrets[field.key] = revealed.secrets[field.key] ?? '';
        });
        secretsLoaded.value = true;
      } catch (error) {
        secretProblem.value = errorMessage(error);
      }
    }
  } catch (error) {
    problem.value = errorMessage(error);
    closeDrawer();
  } finally {
    detailLoading.value = false;
  }
}

async function saveChannel() {
  if (!canEdit.value || saving.value) return;
  const definition = selectedProvider.value;
  if (!definition || !form.channelCode.trim() || !form.name.trim()) {
    window.$message?.warning(t('mine.notification.message.required'));
    return;
  }
  const body: NotificationChannelRequest = {
    channelCode: form.channelCode.trim(),
    name: form.name.trim(),
    channelType: definition.key.channelType,
    provider: definition.key.provider,
    status: form.status,
    configuration: providerConfiguration(definition),
    secrets: !editing.value || secretsLoaded.value ? providerSecrets(definition) : {},
    connectTimeoutMs: form.connectTimeoutMs,
    readTimeoutMs: form.readTimeoutMs,
    version: form.version
  };
  saving.value = true;
  try {
    if (form.id) {
      await updateNotificationChannel(form.id, body);
    } else {
      await createNotificationChannel(body);
    }
    window.$message?.success(t('mine.notification.message.saved'));
    closeDrawer();
    await loadChannels();
  } catch (error) {
    window.$message?.error(errorMessage(error));
  } finally {
    saving.value = false;
  }
}

function changeProvider(provider: string) {
  const definition = providers.value.find(item => item.key.provider === provider);
  if (!definition) return;
  form.provider = provider;
  resetProviderValues(definition);
  secretsLoaded.value = true;
  secretProblem.value = null;
}

function resetProviderValues(definition: NotificationProviderDefinition) {
  form.configuration = Object.fromEntries(
    definition.configurationFields.map(field => [field.key, cloneDefault(field.defaultValue, field.type)])
  );
  form.secrets = Object.fromEntries(definition.secretFields.map(field => [field.key, '']));
}

function applyDetail(detail: NotificationChannel) {
  Object.assign(form, {
    id: detail.id,
    channelCode: detail.channelCode,
    name: detail.name,
    provider: detail.provider,
    status: detail.status,
    configuration: { ...detail.configuration },
    secrets: {},
    connectTimeoutMs: detail.connectTimeoutMs,
    readTimeoutMs: detail.readTimeoutMs,
    version: detail.version
  });
  selectedProvider.value?.configurationFields.forEach(field => {
    if (form.configuration[field.key] === undefined) {
      form.configuration[field.key] = cloneDefault(field.defaultValue, field.type);
    }
  });
  selectedProvider.value?.secretFields.forEach(field => {
    form.secrets[field.key] = '';
  });
}

function closeDrawer() {
  drawerVisible.value = false;
  secretProblem.value = null;
  secretsLoaded.value = false;
  Object.assign(form, emptyForm());
}

function providerConfiguration(definition: NotificationProviderDefinition) {
  return Object.fromEntries(definition.configurationFields.map(field => [field.key, form.configuration[field.key]]));
}

function providerSecrets(definition: NotificationProviderDefinition) {
  return Object.fromEntries(definition.secretFields.map(field => [field.key, form.secrets[field.key] ?? '']));
}

function stringConfig(key: string) {
  const value = form.configuration[key];
  return typeof value === 'string' ? value : '';
}

function numberConfig(key: string) {
  const value = form.configuration[key];
  return typeof value === 'number' ? value : null;
}

function booleanConfig(key: string) {
  return form.configuration[key] === true;
}

function stringListConfig(key: string) {
  const value = form.configuration[key];
  return Array.isArray(value) ? value.filter(item => typeof item === 'string') as string[] : [];
}

function setConfiguration(key: string, value: unknown) {
  form.configuration[key] = value;
}

function setStringListConfiguration(key: string, value: string[]) {
  form.configuration[key] = value;
}

function setSecret(key: string, value: string) {
  form.secrets[key] = value;
}

function fieldLabel(field: NotificationProviderField) {
  return t(fieldLabelKeys[field.key] ?? 'mine.notification.field.unknown');
}

function fieldPlaceholder(field: NotificationProviderField) {
  return field.required
    ? t('mine.notification.form.requiredField')
    : t('mine.notification.form.optionalField');
}

function providerLabel(definition: NotificationProviderDefinition) {
  return t(definition.displayNameKey as App.I18n.I18nKey);
}

function providerLabelByCode(provider: string) {
  const definition = providers.value.find(item => item.key.provider === provider);
  return definition ? providerLabel(definition) : provider;
}

function statusLabel(status: 'ENABLED' | 'DISABLED') {
  return status === 'ENABLED'
    ? t('mine.notification.status.enabled')
    : t('mine.notification.status.disabled');
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

function cloneDefault(value: unknown, type: NotificationProviderField['type']) {
  if (type === 'STRING_LIST') return Array.isArray(value) ? [...value] : [];
  if (type === 'BOOLEAN') return typeof value === 'boolean' ? value : false;
  if (type === 'INTEGER') return typeof value === 'number' ? value : null;
  return typeof value === 'string' ? value : '';
}

function can(permission: string) {
  return hasPermission(authStore.userInfo.buttons, permission);
}

function errorMessage(error: unknown) {
  if (error instanceof ApiError) {
    return error.traceId
      ? t('mine.notification.message.failedWithTrace', { message: error.message, traceId: error.traceId })
      : error.message;
  }
  return error instanceof Error && error.message
    ? error.message
    : t('mine.notification.message.loadFailed');
}

function emptyForm(): ChannelForm {
  return {
    id: null,
    channelCode: '',
    name: '',
    provider: '',
    status: 'DISABLED',
    configuration: {},
    secrets: {},
    connectTimeoutMs: 3000,
    readTimeoutMs: 5000,
    version: 0
  };
}

defineExpose({ openEdit, saveChannel });
</script>

<template>
  <div class="flex flex-col gap-4">
    <NPageHeader :title="t('mine.notification.title')" :subtitle="t('mine.notification.subtitle')">
      <template #extra>
        <NSpace>
          <NButton :loading="loading" @click="loadChannels">
            {{ t('mine.notification.action.refresh') }}
          </NButton>
          <NButton v-if="canEdit" type="primary" data-action="create-channel" @click="openCreate">
            {{ t('mine.notification.action.create') }}
          </NButton>
        </NSpace>
      </template>
    </NPageHeader>

    <NAlert type="info" :title="t('mine.notification.noticeTitle')">
      {{ t('mine.notification.noticeContent') }}
    </NAlert>
    <NAlert v-if="problem" type="error" :title="t('mine.notification.message.loadFailed')">
      {{ problem }}
    </NAlert>

    <NCard :bordered="false">
      <NDataTable
        :columns="columns"
        :data="channels"
        :loading="loading"
        :row-key="row => row.id"
        :scroll-x="1080"
      >
        <template #empty>
          <NEmpty :description="t('mine.notification.empty')">
            <template v-if="canEdit" #extra>
              <NButton type="primary" @click="openCreate">
                {{ t('mine.notification.action.createFirst') }}
              </NButton>
            </template>
          </NEmpty>
        </template>
      </NDataTable>
    </NCard>

    <NDrawer v-model:show="drawerVisible" :width="720" placement="right" @after-leave="closeDrawer">
      <NDrawerContent
        :title="editing ? t('mine.notification.drawer.edit') : t('mine.notification.drawer.create')"
        closable
      >
        <NFlex vertical :size="20">
          <NAlert v-if="secretProblem" type="warning" :title="t('mine.notification.secret.loadFailed')">
            {{ secretProblem }}
          </NAlert>
          <NAlert v-else-if="editing && !canRevealSecrets" type="info">
            {{ t('mine.notification.secret.noPermission') }}
          </NAlert>
          <NAlert v-else-if="editing && secretsLoaded" type="warning">
            {{ t('mine.notification.secret.revealed') }}
          </NAlert>

          <NForm label-placement="top" :show-require-mark="true" :disabled="detailLoading">
            <NText strong>{{ t('mine.notification.section.identity') }}</NText>
            <NGrid responsive="screen" cols="1 m:2" :x-gap="16">
              <NGi>
                <NFormItem :label="t('mine.notification.form.channelCode')" required>
                  <NInput
                    v-model:value="form.channelCode"
                    :disabled="editing"
                    :maxlength="64"
                    :placeholder="t('mine.notification.form.channelCodePlaceholder')"
                  />
                </NFormItem>
              </NGi>
              <NGi>
                <NFormItem :label="t('mine.notification.form.name')" required>
                  <NInput v-model:value="form.name" :maxlength="128" />
                </NFormItem>
              </NGi>
              <NGi>
                <NFormItem :label="t('mine.notification.form.provider')" required>
                  <NSelect
                    :value="form.provider"
                    :options="providerOptions"
                    @update:value="changeProvider"
                  />
                </NFormItem>
              </NGi>
              <NGi>
                <NFormItem :label="t('mine.notification.form.enabled')">
                  <NSwitch
                    :value="form.status === 'ENABLED'"
                    @update:value="value => form.status = value ? 'ENABLED' : 'DISABLED'"
                  >
                    <template #checked>{{ t('mine.notification.status.enabled') }}</template>
                    <template #unchecked>{{ t('mine.notification.status.disabled') }}</template>
                  </NSwitch>
                </NFormItem>
              </NGi>
            </NGrid>

            <NDivider />
            <NText strong>{{ t('mine.notification.section.provider') }}</NText>
            <NGrid v-if="selectedProvider" responsive="screen" cols="1 m:2" :x-gap="16">
              <NGi v-for="field in selectedProvider.configurationFields" :key="field.key">
                <NFormItem :label="fieldLabel(field)" :required="field.required">
                  <NSelect
                    v-if="field.type === 'STRING' && field.options.length > 0"
                    :value="stringConfig(field.key)"
                    :options="field.options.map(value => ({ label: value, value }))"
                    @update:value="value => setConfiguration(field.key, value)"
                  />
                  <NInput
                    v-else-if="field.type === 'STRING'"
                    :value="stringConfig(field.key)"
                    :maxlength="field.maxLength ?? undefined"
                    :placeholder="fieldPlaceholder(field)"
                    @update:value="value => setConfiguration(field.key, value)"
                  />
                  <NInputNumber
                    v-else-if="field.type === 'INTEGER'"
                    :value="numberConfig(field.key)"
                    class="w-full"
                    @update:value="value => setConfiguration(field.key, value)"
                  />
                  <NSwitch
                    v-else-if="field.type === 'BOOLEAN'"
                    :value="booleanConfig(field.key)"
                    @update:value="value => setConfiguration(field.key, value)"
                  />
                  <NDynamicTags
                    v-else-if="field.type === 'STRING_LIST'"
                    :value="stringListConfig(field.key)"
                    @update:value="setStringListConfiguration(field.key, $event)"
                  />
                </NFormItem>
              </NGi>
            </NGrid>

            <NDivider />
            <NFlex align="center" justify="space-between">
              <NText strong>{{ t('mine.notification.section.secrets') }}</NText>
              <NTag v-if="editing" :bordered="false" :type="secretsLoaded ? 'warning' : 'default'">
                {{ secretsLoaded
                  ? t('mine.notification.secret.loaded')
                  : t('mine.notification.secret.preserved') }}
              </NTag>
            </NFlex>
            <NGrid v-if="selectedProvider" responsive="screen" cols="1 m:2" :x-gap="16">
              <NGi v-for="field in selectedProvider.secretFields" :key="field.key">
                <NFormItem :label="fieldLabel(field)" :required="field.required && (!editing || secretsLoaded)">
                  <NInput
                    :value="form.secrets[field.key] ?? ''"
                    type="password"
                    show-password-on="click"
                    :maxlength="field.maxLength ?? undefined"
                    :disabled="editing && !secretsLoaded"
                    :placeholder="editing && !secretsLoaded
                      ? t('mine.notification.secret.preservePlaceholder')
                      : fieldPlaceholder(field)"
                    :data-field="`secret-${field.key}`"
                    @update:value="value => setSecret(field.key, value)"
                  />
                </NFormItem>
              </NGi>
            </NGrid>

            <NDivider />
            <NText strong>{{ t('mine.notification.section.timeout') }}</NText>
            <NGrid responsive="screen" cols="1 m:2" :x-gap="16">
              <NGi>
                <NFormItem :label="t('mine.notification.form.connectTimeout')" required>
                  <NInputNumber v-model:value="form.connectTimeoutMs" class="w-full" :min="100" :max="30000" />
                </NFormItem>
              </NGi>
              <NGi>
                <NFormItem :label="t('mine.notification.form.readTimeout')" required>
                  <NInputNumber v-model:value="form.readTimeoutMs" class="w-full" :min="100" :max="60000" />
                </NFormItem>
              </NGi>
            </NGrid>
          </NForm>
        </NFlex>

        <template #footer>
          <NSpace justify="end">
            <NButton @click="closeDrawer">{{ t('mine.notification.action.cancel') }}</NButton>
            <NButton type="primary" :loading="saving" :disabled="detailLoading" @click="saveChannel">
              {{ t('mine.notification.action.save') }}
            </NButton>
          </NSpace>
        </template>
      </NDrawerContent>
    </NDrawer>
  </div>
</template>
