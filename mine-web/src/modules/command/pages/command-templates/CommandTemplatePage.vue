<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  NAlert,
  NButton,
  NCard,
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
  NInputNumber,
  NSelect,
  NSpace,
  NSwitch,
  NTag,
  NText
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import ListActionButton from '@/components/common/list-action-button.vue';
import {
  createCommandTemplateDefinition,
  createNextCommandTemplateDraft,
  getCommandTemplateDefinitions,
  getCommandTemplateProducts,
  publishCommandTemplateDefinition,
  updateCommandTemplateDefinition
} from '@/modules/command/api/command';
import type {
  CommandTemplateDefinition,
  CommandTemplateDefinitionRequest,
  CommandTemplateProduct
} from '@/modules/command/api/command';
import { useAuthStore } from '@/store/modules/auth';

const { t, locale } = useI18n();
const authStore = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const drawerVisible = ref(false);
const editing = ref<CommandTemplateDefinition | null>(null);
const definitions = ref<CommandTemplateDefinition[]>([]);
const products = ref<CommandTemplateProduct[]>([]);
const form = reactive({
  templateKey: '',
  nameZh: '',
  nameEn: '',
  description: '',
  payloadFormat: 'RAW_HEX' as 'MQTT_JSON' | 'RAW_HEX',
  parameterSchemaText: emptySchemaText(),
  rawPayloadHex: '',
  defaultTtlSeconds: 300,
  defaultTimeoutSeconds: 30,
  requiresReason: true,
  productIds: [] as string[],
  version: 0
});

const payloadOptions = computed<SelectOption[]>(() => [
  { label: t('mine.commandTemplates.format.rawHex'), value: 'RAW_HEX' },
  { label: t('mine.commandTemplates.format.mqttJson'), value: 'MQTT_JSON' }
]);
const productOptions = computed<SelectOption[]>(() =>
  products.value.map(product => ({ label: `${product.name} (${product.productKey})`, value: product.id }))
);
const normalizedRawHex = computed(() => form.rawPayloadHex.replace(/0x/gi, '').replace(/\s+/g, '').toUpperCase());
const rawByteLength = computed(() =>
  /^[0-9A-F]+$/.test(normalizedRawHex.value) && normalizedRawHex.value.length % 2 === 0
    ? normalizedRawHex.value.length / 2
    : 0
);
const columns = computed<DataTableColumns<CommandTemplateDefinition>>(() => [
  {
    title: t('mine.commandTemplates.column.template'),
    key: 'templateKey',
    minWidth: 220,
    render: row =>
      h(NSpace, { vertical: true, size: 0 }, () => [
        h(NText, null, () => localizedName(row)),
        h(NText, { depth: 3 }, () => `${row.templateKey} · v${row.versionNumber}`)
      ])
  },
  {
    title: t('mine.commandTemplates.column.format'),
    key: 'payloadFormat',
    width: 140,
    render: row => t(`mine.commandTemplates.format.${row.payloadFormat === 'RAW_HEX' ? 'rawHex' : 'mqttJson'}`)
  },
  {
    title: t('mine.commandTemplates.column.products'),
    key: 'productIds',
    minWidth: 220,
    render: row => productNames(row.productIds)
  },
  {
    title: t('mine.commandTemplates.column.status'),
    key: 'status',
    width: 120,
    render: row => h(NTag, { bordered: false, type: statusType(row.status) }, () => statusLabel(row.status))
  },
  {
    title: t('mine.commandTemplates.column.updatedAt'),
    key: 'updatedAt',
    minWidth: 180,
    render: row => formatTime(row.updatedAt)
  },
  {
    title: t('mine.common.action'),
    key: 'action',
    width: 220,
    render: row =>
      h(NSpace, { size: 12 }, () => [
        row.status === 'DRAFT' && can('command:template:edit')
          ? h(NButton, { text: true, type: 'primary', onClick: () => openEdit(row) }, () => t('mine.common.edit'))
          : null,
        row.status === 'DRAFT' && can('command:template:publish')
          ? h(NButton, { text: true, type: 'warning', onClick: () => requestPublish(row) }, () =>
              t('mine.commandTemplates.action.publish')
            )
          : null,
        row.status === 'PUBLISHED' &&
        can('command:template:edit') &&
        !definitions.value.some(item => item.templateId === row.templateId && item.status === 'DRAFT')
          ? h(NButton, { text: true, type: 'primary', onClick: () => createNextDraft(row) }, () =>
              t('mine.commandTemplates.action.createVersion')
            )
          : null
      ])
  }
]);

onMounted(loadPage);

async function loadPage() {
  loading.value = true;
  try {
    const [templateRows, productRows] = await Promise.all([
      getCommandTemplateDefinitions(),
      getCommandTemplateProducts()
    ]);
    definitions.value = templateRows;
    products.value = productRows;
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editing.value = null;
  Object.assign(form, {
    templateKey: '',
    nameZh: '',
    nameEn: '',
    description: '',
    payloadFormat: 'RAW_HEX',
    parameterSchemaText: emptySchemaText(),
    rawPayloadHex: '',
    defaultTtlSeconds: 300,
    defaultTimeoutSeconds: 30,
    requiresReason: true,
    productIds: [],
    version: 0
  });
  drawerVisible.value = true;
}

function openEdit(row: CommandTemplateDefinition) {
  editing.value = row;
  Object.assign(form, {
    templateKey: row.templateKey,
    nameZh: row.nameZh,
    nameEn: row.nameEn || '',
    description: row.description || '',
    payloadFormat: row.payloadFormat,
    parameterSchemaText: JSON.stringify(row.parameterSchema, null, 2),
    rawPayloadHex: row.rawPayloadHex || '',
    defaultTtlSeconds: row.defaultTtlSeconds,
    defaultTimeoutSeconds: row.defaultTimeoutSeconds,
    requiresReason: row.requiresReason,
    productIds: [...row.productIds],
    version: row.version
  });
  drawerVisible.value = true;
}

async function saveDraft() {
  const request = buildRequest();
  if (!request) return;
  saving.value = true;
  try {
    if (editing.value) await updateCommandTemplateDefinition(editing.value.versionId, request);
    else await createCommandTemplateDefinition(request);
    window.$message?.success(t('mine.commandTemplates.message.saved'));
    drawerVisible.value = false;
    await loadPage();
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

function buildRequest(): CommandTemplateDefinitionRequest | null {
  if (!form.templateKey.trim() || !form.nameZh.trim() || !form.productIds.length) {
    window.$message?.warning(t('mine.commandTemplates.validation.required'));
    return null;
  }
  if (form.payloadFormat === 'RAW_HEX' && (!rawByteLength.value || rawByteLength.value > 4096)) {
    window.$message?.warning(t('mine.commandTemplates.validation.rawHex'));
    return null;
  }
  let parameterSchema: Record<string, unknown>;
  try {
    parameterSchema = JSON.parse(form.parameterSchemaText) as Record<string, unknown>;
  } catch {
    window.$message?.warning(t('mine.commandTemplates.validation.schema'));
    return null;
  }
  return {
    templateKey: form.templateKey.trim(),
    nameZh: form.nameZh.trim(),
    nameEn: form.nameEn.trim() || null,
    description: form.description.trim() || null,
    payloadFormat: form.payloadFormat,
    parameterSchema,
    rawPayloadHex: form.payloadFormat === 'RAW_HEX' ? form.rawPayloadHex : null,
    defaultTtlSeconds: form.defaultTtlSeconds,
    defaultTimeoutSeconds: form.defaultTimeoutSeconds,
    requiresReason: form.requiresReason,
    productIds: form.productIds,
    version: form.version
  };
}

function requestPublish(row: CommandTemplateDefinition) {
  window.$dialog?.warning({
    title: t('mine.commandTemplates.publish.title'),
    content: t('mine.commandTemplates.publish.content', { name: localizedName(row) }),
    positiveText: t('mine.commandTemplates.action.publish'),
    negativeText: t('mine.common.cancel'),
    onPositiveClick: () => publish(row)
  });
}

async function publish(row: CommandTemplateDefinition) {
  saving.value = true;
  try {
    await publishCommandTemplateDefinition(row.versionId, row.version);
    window.$message?.success(t('mine.commandTemplates.message.published'));
    await loadPage();
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

async function createNextDraft(row: CommandTemplateDefinition) {
  saving.value = true;
  try {
    const created = await createNextCommandTemplateDraft(row.versionId);
    window.$message?.success(t('mine.commandTemplates.message.versionCreated'));
    await loadPage();
    openEdit(created);
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

function localizedName(row: CommandTemplateDefinition) {
  return locale.value === 'zh-CN' ? row.nameZh : row.nameEn || row.nameZh;
}

function productNames(productIds: string[]) {
  const names = productIds.map(id => products.value.find(product => product.id === id)?.name || id);
  return names.join(t('mine.commandTemplates.productSeparator'));
}

function statusLabel(status: CommandTemplateDefinition['status']) {
  return t(`mine.commandTemplates.status.${status.toLowerCase()}` as App.I18n.I18nKey);
}

function statusType(status: CommandTemplateDefinition['status']) {
  if (status === 'PUBLISHED') return 'success' as const;
  if (status === 'RETIRED') return 'default' as const;
  return 'warning' as const;
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value));
}

function emptySchemaText() {
  return JSON.stringify({ type: 'object', additionalProperties: false, properties: {} }, null, 2);
}

function can(permission: string) {
  return hasPermission(authStore.userInfo.buttons, permission);
}

function showError(error: unknown) {
  const message =
    error instanceof ApiError && error.traceId
      ? `${t('mine.common.requestFailed')} ${t('mine.common.traceId', { traceId: error.traceId })}`
      : t('mine.common.requestFailed');
  window.$message?.error(message);
}
</script>

<template>
  <div>
    <NSpace vertical :size="16">
      <NFlex align="start" justify="space-between" :wrap="true">
        <NSpace vertical :size="4">
          <NText tag="h1" class="m-0 text-2xl font-semibold">{{ t('mine.commandTemplates.title') }}</NText>
          <NText depth="3">{{ t('mine.commandTemplates.subtitle') }}</NText>
        </NSpace>
        <NSpace>
          <ListActionButton v-if="can('command:template:edit')" kind="create" @click="openCreate">
            {{ t('mine.commandTemplates.action.create') }}
          </ListActionButton>
          <ListActionButton kind="refresh" :loading="loading" @click="loadPage">
            {{ t('common.refresh') }}
          </ListActionButton>
        </NSpace>
      </NFlex>

      <NAlert type="info" :title="t('mine.commandTemplates.notice.title')">
        {{ t('mine.commandTemplates.notice.content') }}
      </NAlert>

      <NCard :bordered="false" size="small">
        <NDataTable
          v-if="definitions.length || loading"
          :loading="loading"
          :columns="columns"
          :data="definitions"
          :row-key="row => row.versionId"
          :scroll-x="1040"
        />
        <NEmpty v-else :description="t('mine.commandTemplates.empty')" />
      </NCard>
    </NSpace>

    <NDrawer v-model:show="drawerVisible" width="min(680px, 94vw)">
      <NDrawerContent
        :title="editing ? t('mine.commandTemplates.drawer.edit') : t('mine.commandTemplates.drawer.create')"
        closable
      >
        <NForm label-placement="top">
          <NGrid responsive="screen" cols="1 s:2" :x-gap="12">
            <NGi>
              <NFormItem :label="t('mine.commandTemplates.field.templateKey')" required>
                <NInput
                  v-model:value="form.templateKey"
                  :disabled="Boolean(editing)"
                  :maxlength="128"
                  :placeholder="t('mine.commandTemplates.placeholder.templateKey')"
                />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.commandTemplates.field.payloadFormat')" required>
                <NSelect
                  v-model:value="form.payloadFormat"
                  :options="payloadOptions"
                  :placeholder="
                    t('mine.common.selectPlaceholder', { field: t('mine.commandTemplates.field.payloadFormat') })
                  "
                />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.commandTemplates.field.nameZh')" required>
                <NInput
                  v-model:value="form.nameZh"
                  :maxlength="128"
                  :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.commandTemplates.field.nameZh') })"
                />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.commandTemplates.field.nameEn')">
                <NInput
                  v-model:value="form.nameEn"
                  :maxlength="128"
                  :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.commandTemplates.field.nameEn') })"
                />
              </NFormItem>
            </NGi>
          </NGrid>
          <NFormItem :label="t('mine.commandTemplates.field.description')">
            <NInput
              v-model:value="form.description"
              type="textarea"
              :maxlength="1000"
              show-count
              :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.commandTemplates.field.description') })"
            />
          </NFormItem>
          <NFormItem :label="t('mine.commandTemplates.field.products')" required>
            <NSelect
              v-model:value="form.productIds"
              multiple
              filterable
              :options="productOptions"
              :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.commandTemplates.field.products') })"
            />
          </NFormItem>
          <NFormItem
            v-if="form.payloadFormat === 'RAW_HEX'"
            :label="t('mine.commandTemplates.field.rawPayloadHex')"
            required
          >
            <NInput
              v-model:value="form.rawPayloadHex"
              type="textarea"
              :autosize="{ minRows: 5, maxRows: 14 }"
              :maxlength="32768"
              :placeholder="t('mine.commandTemplates.placeholder.rawPayloadHex')"
            />
            <template #feedback>
              <NSpace vertical :size="0">
                <NText>{{ t('mine.commandTemplates.byteCount', { count: rawByteLength }) }}</NText>
                <NText depth="3">
                  {{ t('mine.commands.raw.normalizedPayload', { payload: normalizedRawHex || t('mine.common.empty') }) }}
                </NText>
              </NSpace>
            </template>
          </NFormItem>
          <NFormItem v-else :label="t('mine.commandTemplates.field.parameterSchema')" required>
            <NInput
              v-model:value="form.parameterSchemaText"
              type="textarea"
              :autosize="{ minRows: 8, maxRows: 18 }"
              :placeholder="t('mine.commandTemplates.placeholder.parameterSchema')"
            />
          </NFormItem>
          <NGrid responsive="screen" cols="1 s:2" :x-gap="12">
            <NGi>
              <NFormItem :label="t('mine.commands.field.ttl')">
                <NInputNumber
                  v-model:value="form.defaultTtlSeconds"
                  :min="1"
                  :max="86400"
                  :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.commands.field.ttl') })"
                />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.commands.field.timeout')">
                <NInputNumber
                  v-model:value="form.defaultTimeoutSeconds"
                  :min="1"
                  :max="86400"
                  :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.commands.field.timeout') })"
                />
              </NFormItem>
            </NGi>
          </NGrid>
          <NFormItem :label="t('mine.commandTemplates.field.requiresReason')">
            <NSwitch v-model:value="form.requiresReason" />
          </NFormItem>
        </NForm>
        <template #footer>
          <NSpace justify="end">
            <NButton @click="drawerVisible = false">{{ t('mine.common.cancel') }}</NButton>
            <NButton type="primary" :loading="saving" @click="saveDraft">
              {{ t('mine.commandTemplates.action.save') }}
            </NButton>
          </NSpace>
        </template>
      </NDrawerContent>
    </NDrawer>
  </div>
</template>
