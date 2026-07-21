<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, toRaw } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NEmpty,
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
  NSwitch,
  NTabPane,
  NTabs,
  NTag,
  NText
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import {
  createModelDraft,
  createProtocolDraft,
  getProductConfiguration,
  publishModel,
  publishProtocol,
  saveModelFields,
  saveProtocolFields
} from '@/modules/device/api/device-model';
import type {
  ModelDataType,
  ModelField,
  ModelVersion,
  ProductConfiguration,
  ProtocolField,
  ProtocolType,
  ProtocolVersion
} from '@/modules/device/api/device-model';
import { getProducts } from '@/modules/device/api/device';
import type { Product } from '@/modules/device/api/device';
import { useAuthStore } from '@/store/modules/auth';
import ProtocolSampleWorkbench from '@/modules/device/components/protocol/ProtocolSampleWorkbench.vue';
import ConfigReleasePanel from '@/modules/device/components/protocol/ConfigReleasePanel.vue';

const { t } = useI18n();
const authStore = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const products = ref<Product[]>([]);
const productId = ref<string | null>(null);
const configuration = ref<ProductConfiguration>({ models: [], protocols: [] });
const selectedModelId = ref<string | null>(null);
const selectedProtocolId = ref<string | null>(null);
const modelFields = ref<ModelField[]>([]);
const protocolFields = ref<ProtocolField[]>([]);
const modelFieldModal = ref(false);
const protocolDraftModal = ref(false);
const protocolFieldModal = ref(false);
const modelDescription = ref('');
const modelFieldForm = reactive<ModelField>(emptyModelField());
const protocolDraftForm = reactive({
  profileKey: '',
  name: '',
  protocolType: 'MQTT_JSON' as ProtocolType,
  messageType: 'TELEMETRY' as ProtocolVersion['messageType'],
  pluginKey: '',
  pluginVersion: '',
  routingMode: 'TRUSTED_IDENTITY' as ProtocolVersion['routingMode'],
  topicOrFrame: '',
  description: ''
});
const protocolFieldForm = reactive<ProtocolField>(emptyProtocolField());

const productOptions = computed<SelectOption[]>(() =>
  products.value.map(item => ({ label: `${item.name} (${item.productKey})`, value: item.id }))
);
const modelOptions = computed<SelectOption[]>(() =>
  configuration.value.models.map(item => ({
    label: `${t('mine.models.version', { version: item.version.versionNumber })} · ${statusLabel(item.version.status)}`,
    value: item.version.id
  }))
);
const protocolOptions = computed<SelectOption[]>(() =>
  configuration.value.protocols.map(item => ({
    label: `${item.version.name} · v${item.version.versionNumber} · ${statusLabel(item.version.status)}`,
    value: item.version.id
  }))
);
const currentModel = computed(
  () => configuration.value.models.find(item => item.version.id === selectedModelId.value)?.version || null
);
const currentProtocol = computed(
  () => configuration.value.protocols.find(item => item.version.id === selectedProtocolId.value)?.version || null
);
const publishedTargetOptions = computed<SelectOption[]>(() => {
  const published = configuration.value.models.find(item => item.version.status === 'PUBLISHED');
  return (published?.fields || []).map(field => ({
    label: `${field.nameZh} (${field.identifier})`,
    value: field.identifier
  }));
});
const modelTypeOptions = computed<SelectOption[]>(() =>
  (['INTEGER', 'DECIMAL', 'BOOLEAN', 'STRING', 'ENUM', 'DATETIME', 'GEO_POINT'] as ModelDataType[]).map(value => ({
    label: t(`mine.models.dataType.${value.toLowerCase()}`),
    value
  }))
);
const accessOptions = computed<SelectOption[]>(() =>
  ['READ', 'WRITE', 'READ_WRITE'].map(value => ({ label: t(`mine.models.access.${value.toLowerCase()}`), value }))
);
const protocolTypeOptions = computed<SelectOption[]>(() =>
  ['MQTT_JSON', 'MQTT_BINARY', 'TCP_BINARY'].map(value => ({
    label: t(`mine.models.protocol.${value.toLowerCase()}`),
    value
  }))
);
const usesPlugin = computed(() => protocolDraftForm.protocolType !== 'MQTT_JSON');
const routingModeOptions = computed<SelectOption[]>(() =>
  (['TRUSTED_IDENTITY', 'LEGACY_LISTENER'] as ProtocolVersion['routingMode'][]).map(value => ({
    label: t(`mine.models.routing.${value.toLowerCase()}`),
    value
  }))
);
const messageTypeOptions = computed<SelectOption[]>(() =>
  ['TELEMETRY', 'STATE', 'EVENT', 'COMMAND_REPLY'].map(value => ({
    label: t(`mine.models.messageType.${value.toLowerCase()}`),
    value
  }))
);
const sourceTypeOptions = computed<SelectOption[]>(() =>
  ['INTEGER', 'DECIMAL', 'BOOLEAN', 'STRING', 'BINARY', 'DATETIME'].map(value => ({ label: value, value }))
);
const transformOptions = computed<SelectOption[]>(() =>
  ['NONE', 'SCALE', 'OFFSET', 'SCALE_OFFSET', 'ENUM', 'EXPRESSION'].map(value => ({
    label: t(`mine.models.transform.${value.toLowerCase()}`),
    value
  }))
);

const modelColumns = computed<DataTableColumns<ModelField>>(() => [
  { title: t('mine.models.column.identifier'), key: 'identifier', minWidth: 140 },
  { title: t('mine.models.column.name'), key: 'nameZh', minWidth: 130 },
  { title: t('mine.models.column.dataType'), key: 'dataType', width: 110 },
  { title: t('mine.models.column.unit'), key: 'unit', width: 90, render: row => row.unit || t('mine.common.empty') },
  { title: t('mine.models.column.access'), key: 'accessMode', width: 110 },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 80,
    render: (_row, index) =>
      currentModel.value?.status === 'DRAFT' && can('device:model:edit')
        ? h(NButton, { text: true, type: 'error', onClick: () => modelFields.value.splice(index, 1) }, () =>
            t('mine.common.delete')
          )
        : null
  }
]);
const protocolColumns = computed<DataTableColumns<ProtocolField>>(() => [
  { title: t('mine.models.column.source'), key: 'sourcePath', minWidth: 150 },
  { title: t('mine.models.column.sourceType'), key: 'sourceDataType', width: 110 },
  { title: t('mine.models.column.target'), key: 'targetIdentifier', minWidth: 140 },
  { title: t('mine.models.column.transform'), key: 'transformType', width: 120 },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 80,
    render: (_row, index) =>
      currentProtocol.value?.status === 'DRAFT' && can('device:model:edit')
        ? h(NButton, { text: true, type: 'error', onClick: () => protocolFields.value.splice(index, 1) }, () =>
            t('mine.common.delete')
          )
        : null
  }
]);

onMounted(loadProducts);

async function loadProducts() {
  try {
    const response = await getProducts({ page: 1, size: 100 });
    products.value = response.items.filter(item => item.status !== 'ARCHIVED');
    if (products.value.length) {
      productId.value = products.value[0].id;
      await loadConfiguration();
    }
  } catch (error) {
    showError(error);
  }
}

async function loadConfiguration() {
  if (!productId.value) return;
  loading.value = true;
  try {
    const previousModelId = selectedModelId.value;
    const previousProtocolId = selectedProtocolId.value;
    configuration.value = await getProductConfiguration(productId.value);
    selectedModelId.value = configuration.value.models.some(item => item.version.id === previousModelId)
      ? previousModelId
      : configuration.value.models[0]?.version.id || null;
    selectedProtocolId.value = configuration.value.protocols.some(item => item.version.id === previousProtocolId)
      ? previousProtocolId
      : configuration.value.protocols[0]?.version.id || null;
    syncModelFields();
    syncProtocolFields();
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
}

async function reloadAfterSamples() {
  await loadConfiguration();
}

function syncModelFields() {
  const fields = configuration.value.models.find(item => item.version.id === selectedModelId.value)?.fields || [];
  modelFields.value = structuredClone(toRaw(fields));
}

function syncProtocolFields() {
  const fields = configuration.value.protocols.find(item => item.version.id === selectedProtocolId.value)?.fields || [];
  protocolFields.value = structuredClone(toRaw(fields));
}

async function newModelDraft() {
  if (!productId.value) return;
  try {
    const created = await createModelDraft(productId.value, modelDescription.value.trim() || undefined);
    await loadConfiguration();
    selectedModelId.value = created.id;
    syncModelFields();
  } catch (error) {
    showError(error);
  }
}

function addModelField() {
  if (!modelFieldForm.identifier.trim() || !modelFieldForm.nameZh.trim())
    return window.$message?.warning(t('mine.models.validation.required'));
  modelFields.value.push(structuredClone(modelFieldForm));
  Object.assign(modelFieldForm, emptyModelField());
  modelFieldModal.value = false;
}

async function persistModelFields() {
  if (!currentModel.value) return;
  saving.value = true;
  try {
    await saveModelFields(currentModel.value.id, modelFields.value);
    window.$message?.success(t('mine.common.saved'));
    await loadConfiguration();
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

async function releaseModel() {
  if (!currentModel.value || !window.confirm(t('mine.models.confirmPublishModel'))) return;
  try {
    await publishModel(currentModel.value.id, currentModel.value.version);
    await loadConfiguration();
  } catch (error) {
    showError(error);
  }
}

async function newProtocolDraft() {
  if (!productId.value) return;
  if (usesPlugin.value && (!protocolDraftForm.pluginKey.trim() || !protocolDraftForm.pluginVersion.trim())) {
    window.$message?.warning(t('mine.models.validation.pluginRequired'));
    return;
  }
  try {
    const created = await createProtocolDraft(productId.value, {
      ...protocolDraftForm,
      pluginKey: usesPlugin.value ? protocolDraftForm.pluginKey.trim() : null,
      pluginVersion: usesPlugin.value ? protocolDraftForm.pluginVersion.trim() : null,
      routingMode:
        protocolDraftForm.protocolType === 'TCP_BINARY' ? protocolDraftForm.routingMode : 'TRUSTED_IDENTITY',
      topicOrFrame: protocolDraftForm.topicOrFrame.trim() || null,
      description: protocolDraftForm.description.trim() || null
    });
    protocolDraftModal.value = false;
    await loadConfiguration();
    selectedProtocolId.value = created.id;
    syncProtocolFields();
  } catch (error) {
    showError(error);
  }
}

function changeProtocolType(protocolType: ProtocolType) {
  protocolDraftForm.protocolType = protocolType;
  if (protocolType !== 'TCP_BINARY') protocolDraftForm.routingMode = 'TRUSTED_IDENTITY';
  if (protocolType === 'MQTT_JSON') {
    protocolDraftForm.pluginKey = '';
    protocolDraftForm.pluginVersion = '';
  }
}

function addProtocolField() {
  if (!protocolFieldForm.sourcePath.trim() || !protocolFieldForm.targetIdentifier)
    return window.$message?.warning(t('mine.models.validation.required'));
  protocolFields.value.push(structuredClone(protocolFieldForm));
  Object.assign(protocolFieldForm, emptyProtocolField());
  protocolFieldModal.value = false;
}

async function persistProtocolFields() {
  if (!currentProtocol.value) return;
  saving.value = true;
  try {
    await saveProtocolFields(currentProtocol.value.id, protocolFields.value);
    window.$message?.success(t('mine.common.saved'));
    await loadConfiguration();
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

async function releaseProtocol() {
  if (!currentProtocol.value || !window.confirm(t('mine.models.confirmPublishProtocol'))) return;
  try {
    await publishProtocol(currentProtocol.value.id, currentProtocol.value.version);
    await loadConfiguration();
  } catch (error) {
    showError(error);
  }
}

function emptyModelField(): ModelField {
  return {
    identifier: '',
    nameZh: '',
    nameEn: '',
    dataType: 'DECIMAL',
    unit: '',
    accessMode: 'READ',
    requiredField: false,
    minimumValue: null,
    maximumValue: null,
    decimalPlaces: 2,
    sortOrder: 10,
    description: ''
  };
}

function emptyProtocolField(): ProtocolField {
  return {
    sourcePath: '',
    sourceDataType: 'DECIMAL',
    targetIdentifier: '',
    transformType: 'NONE',
    scaleFactor: null,
    offsetValue: null,
    expression: '',
    requiredField: false,
    sortOrder: 10
  };
}

function statusLabel(status: ModelVersion['status']) {
  return t(`mine.models.status.${status.toLowerCase()}`);
}

function statusType(status: ModelVersion['status']) {
  if (status === 'PUBLISHED') return 'success';
  if (status === 'DRAFT') return 'warning';
  return 'default';
}

function can(permission: string) {
  return hasPermission(authStore.userInfo.buttons, permission);
}

function showError(error: unknown) {
  if (error instanceof ApiError) return window.$message?.error(error.message);
  window.$message?.error(t('mine.common.operationFailed'));
}
</script>

<template>
  <NSpace vertical :size="16">
    <NPageHeader :title="t('mine.models.title')" :subtitle="t('mine.models.subtitle')" />
    <NCard :bordered="false">
      <NFormItem :label="t('mine.models.product')">
        <NSelect
          v-model:value="productId"
          filterable
          :options="productOptions"
          :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.models.product') })"
          @update:value="loadConfiguration"
        />
      </NFormItem>
      <NAlert type="info" :bordered="false">{{ t('mine.models.publishHelp') }}</NAlert>
    </NCard>

    <NTabs type="line" animated>
      <NTabPane name="model" :tab="t('mine.models.tab.model')">
        <NSpace vertical :size="16">
          <NCard :bordered="false">
            <NSpace align="center" justify="space-between">
              <NSelect
                v-model:value="selectedModelId"
                :options="modelOptions"
                :placeholder="t('mine.models.selectVersion')"
                @update:value="syncModelFields"
              />
              <NSpace>
                <NInput v-model:value="modelDescription" :placeholder="t('mine.models.description')" />
                <NButton v-if="can('device:model:edit')" secondary type="primary" @click="newModelDraft">
                  {{ t('mine.models.action.newModel') }}
                </NButton>
              </NSpace>
            </NSpace>
          </NCard>
          <NCard :bordered="false">
            <template #header>
              <NSpace align="center">
                <NText strong>{{ t('mine.models.modelFields') }}</NText>
                <NTag v-if="currentModel" :bordered="false" :type="statusType(currentModel.status)">
                  {{ statusLabel(currentModel.status) }}
                </NTag>
              </NSpace>
            </template>
            <template #header-extra>
              <NSpace v-if="currentModel?.status === 'DRAFT'">
                <NButton v-if="can('device:model:edit')" @click="modelFieldModal = true">
                  {{ t('mine.models.action.addField') }}
                </NButton>
                <NButton v-if="can('device:model:edit')" type="primary" :loading="saving" @click="persistModelFields">
                  {{ t('mine.common.save') }}
                </NButton>
                <NButton v-if="can('device:model:publish')" type="success" @click="releaseModel">
                  {{ t('mine.models.action.publish') }}
                </NButton>
              </NSpace>
            </template>
            <NDataTable :columns="modelColumns" :data="modelFields" :loading="loading" />
          </NCard>
        </NSpace>
      </NTabPane>

      <NTabPane name="protocol" :tab="t('mine.models.tab.protocol')">
        <NSpace vertical :size="16">
          <NCard :bordered="false">
            <NSpace justify="space-between">
              <NSelect
                v-model:value="selectedProtocolId"
                :options="protocolOptions"
                :placeholder="t('mine.models.selectProtocol')"
                @update:value="syncProtocolFields"
              />
              <NButton v-if="can('device:model:edit')" type="primary" secondary @click="protocolDraftModal = true">
                {{ t('mine.models.action.newProtocol') }}
              </NButton>
            </NSpace>
          </NCard>
          <NCard :bordered="false">
            <template #header>
              <NSpace align="center">
                <NText strong>{{ t('mine.models.protocolFields') }}</NText>
                <NTag v-if="currentProtocol" :bordered="false" :type="statusType(currentProtocol.status)">
                  {{ statusLabel(currentProtocol.status) }}
                </NTag>
              </NSpace>
            </template>
            <template #header-extra>
              <NSpace v-if="currentProtocol?.status === 'DRAFT'">
                <NButton
                  v-if="can('device:model:edit') && currentProtocol.parserType === 'CONFIGURATION'"
                  :disabled="!publishedTargetOptions.length"
                  @click="protocolFieldModal = true"
                >
                  {{ t('mine.models.action.addMapping') }}
                </NButton>
                <NButton
                  v-if="can('device:model:edit') && currentProtocol.parserType === 'CONFIGURATION'"
                  type="primary"
                  :loading="saving"
                  @click="persistProtocolFields"
                >
                  {{ t('mine.common.save') }}
                </NButton>
                <NButton v-if="can('device:model:publish')" type="success" @click="releaseProtocol">
                  {{ t('mine.models.action.publish') }}
                </NButton>
              </NSpace>
            </template>
            <NAlert v-if="currentProtocol" type="info" :bordered="false">
              {{
                currentProtocol.parserType === 'CONFIGURATION'
                  ? t('mine.models.binding.configuration')
                  : t('mine.models.binding.plugin', {
                    plugin: currentProtocol.pluginKey,
                    version: currentProtocol.pluginVersion,
                    routing: t(`mine.models.routing.${currentProtocol.routingMode.toLowerCase()}`)
                  })
              }}
            </NAlert>
            <NDataTable :columns="protocolColumns" :data="protocolFields" :loading="loading" />
          </NCard>
          <ProtocolSampleWorkbench
            v-if="currentProtocol"
            :mapping-version-id="currentProtocol.id"
            :mapping-version="currentProtocol.version"
            :protocol-type="currentProtocol.protocolType"
            :editable="currentProtocol.status === 'DRAFT'"
            :can-edit="can('device:protocol:sample:edit')"
            :can-test="can('device:protocol:test')"
            @saved="reloadAfterSamples"
          />
        </NSpace>
      </NTabPane>

      <NTabPane name="release" :tab="t('mine.models.tab.release')">
        <ConfigReleasePanel
          v-if="productId && currentProtocol"
          :product-id="productId"
          :mapping-version-id="currentProtocol.id"
          :protocol-published="currentProtocol.status === 'PUBLISHED'"
          :can-view="can('device:configuration:view')"
          :can-publish="can('device:configuration:publish')"
          :can-rollback="can('device:configuration:rollback')"
        />
        <NEmpty v-else :description="t('mine.models.releases.selectProtocol')" />
      </NTabPane>
    </NTabs>

    <NModal
      v-model:show="modelFieldModal"
      preset="card"
      :title="t('mine.models.modal.field')"
      :style="{ width: 'min(720px, 92vw)' }"
    >
      <NForm label-placement="top">
        <NGrid :cols="2" :x-gap="16">
          <NGi>
            <NFormItem :label="t('mine.models.field.identifier')">
              <NInput
                v-model:value="modelFieldForm.identifier"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.models.field.identifier') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.models.field.nameZh')">
              <NInput
                v-model:value="modelFieldForm.nameZh"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.models.field.nameZh') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.models.field.dataType')">
              <NSelect
                v-model:value="modelFieldForm.dataType"
                :options="modelTypeOptions"
                :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.models.field.dataType') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.models.field.unit')">
              <NInput
                v-model:value="modelFieldForm.unit"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.models.field.unit') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.models.field.access')">
              <NSelect
                v-model:value="modelFieldForm.accessMode"
                :options="accessOptions"
                :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.models.field.access') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.models.field.required')">
              <NSwitch v-model:value="modelFieldForm.requiredField" />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.models.field.minimum')">
              <NInputNumber
                v-model:value="modelFieldForm.minimumValue"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.models.field.minimum') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.models.field.maximum')">
              <NInputNumber
                v-model:value="modelFieldForm.maximumValue"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.models.field.maximum') })"
              />
            </NFormItem>
          </NGi>
        </NGrid>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="modelFieldModal = false">{{ t('mine.common.cancel') }}</NButton>
          <NButton type="primary" @click="addModelField">{{ t('mine.common.save') }}</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="protocolDraftModal"
      preset="card"
      :title="t('mine.models.modal.protocol')"
      :style="{ width: 'min(680px, 92vw)' }"
    >
      <NForm label-placement="top">
        <NGrid :cols="2" :x-gap="16">
          <NGi>
            <NFormItem :label="t('mine.models.field.profileKey')">
              <NInput
                v-model:value="protocolDraftForm.profileKey"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.models.field.profileKey') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.models.field.profileName')">
              <NInput
                v-model:value="protocolDraftForm.name"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.models.field.profileName') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.models.field.protocolType')">
              <NSelect
                v-model:value="protocolDraftForm.protocolType"
                :options="protocolTypeOptions"
                :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.models.field.protocolType') })"
                @update:value="changeProtocolType"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.models.field.messageType')">
              <NSelect
                v-model:value="protocolDraftForm.messageType"
                :options="messageTypeOptions"
                :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.models.field.messageType') })"
              />
            </NFormItem>
          </NGi>
          <NGi v-if="usesPlugin">
            <NFormItem :label="t('mine.models.field.pluginKey')">
              <NInput
                v-model:value="protocolDraftForm.pluginKey"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.models.field.pluginKey') })"
              />
            </NFormItem>
          </NGi>
          <NGi v-if="usesPlugin">
            <NFormItem :label="t('mine.models.field.pluginVersion')">
              <NInput
                v-model:value="protocolDraftForm.pluginVersion"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.models.field.pluginVersion') })"
              />
            </NFormItem>
          </NGi>
          <NGi v-if="protocolDraftForm.protocolType === 'TCP_BINARY'" :span="2">
            <NFormItem :label="t('mine.models.field.routingMode')">
              <NSelect
                v-model:value="protocolDraftForm.routingMode"
                :options="routingModeOptions"
                :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.models.field.routingMode') })"
              />
            </NFormItem>
          </NGi>
          <NGi v-if="usesPlugin" :span="2">
            <NAlert type="info" :bordered="false">{{ t('mine.models.binding.pluginHelp') }}</NAlert>
          </NGi>
          <NGi :span="2">
            <NFormItem :label="t('mine.models.field.topicOrFrame')">
              <NInput
                v-model:value="protocolDraftForm.topicOrFrame"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.models.field.topicOrFrame') })"
              />
            </NFormItem>
          </NGi>
        </NGrid>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="protocolDraftModal = false">{{ t('mine.common.cancel') }}</NButton>
          <NButton type="primary" @click="newProtocolDraft">{{ t('mine.common.save') }}</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="protocolFieldModal"
      preset="card"
      :title="t('mine.models.modal.mapping')"
      :style="{ width: 'min(720px, 92vw)' }"
    >
      <NForm label-placement="top">
        <NGrid :cols="2" :x-gap="16">
          <NGi>
            <NFormItem :label="t('mine.models.field.sourcePath')">
              <NInput
                v-model:value="protocolFieldForm.sourcePath"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.models.field.sourcePath') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.models.field.sourceType')">
              <NSelect
                v-model:value="protocolFieldForm.sourceDataType"
                :options="sourceTypeOptions"
                :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.models.field.sourceType') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.models.field.target')">
              <NSelect
                v-model:value="protocolFieldForm.targetIdentifier"
                filterable
                :options="publishedTargetOptions"
                :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.models.field.target') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.models.field.transform')">
              <NSelect
                v-model:value="protocolFieldForm.transformType"
                :options="transformOptions"
                :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.models.field.transform') })"
              />
            </NFormItem>
          </NGi>
          <NGi v-if="protocolFieldForm.transformType.includes('SCALE')">
            <NFormItem :label="t('mine.models.field.scale')">
              <NInputNumber
                v-model:value="protocolFieldForm.scaleFactor"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.models.field.scale') })"
              />
            </NFormItem>
          </NGi>
          <NGi v-if="protocolFieldForm.transformType.includes('OFFSET')">
            <NFormItem :label="t('mine.models.field.offset')">
              <NInputNumber
                v-model:value="protocolFieldForm.offsetValue"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.models.field.offset') })"
              />
            </NFormItem>
          </NGi>
          <NGi v-if="protocolFieldForm.transformType === 'EXPRESSION'" :span="2">
            <NFormItem :label="t('mine.models.field.expression')">
              <NInput
                v-model:value="protocolFieldForm.expression"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.models.field.expression') })"
              />
            </NFormItem>
          </NGi>
        </NGrid>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="protocolFieldModal = false">{{ t('mine.common.cancel') }}</NButton>
          <NButton type="primary" @click="addProtocolField">{{ t('mine.common.save') }}</NButton>
        </NSpace>
      </template>
    </NModal>
  </NSpace>
</template>
