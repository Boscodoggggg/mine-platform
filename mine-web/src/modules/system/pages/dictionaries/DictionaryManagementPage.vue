<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns, DataTableCreateRowProps, SelectOption } from 'naive-ui';
import {
  NButton,
  NCard,
  NDataTable,
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
  NTag,
  NText
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import {
  createDictionaryItem,
  createDictionaryType,
  deleteDictionaryItem,
  deleteDictionaryType,
  getDictionaryItems,
  getDictionaryTypes,
  updateDictionaryItem,
  updateDictionaryType
} from '@/modules/system/api/dictionary';
import type {
  DictionaryItem,
  DictionarySemanticType,
  DictionaryStatus,
  DictionaryType
} from '@/modules/system/api/dictionary';
import { useAuthStore } from '@/store/modules/auth';

const { t } = useI18n();
const authStore = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const types = ref<DictionaryType[]>([]);
const items = ref<DictionaryItem[]>([]);
const selectedType = ref<DictionaryType | null>(null);
const typeModalVisible = ref(false);
const itemModalVisible = ref(false);
const editingTypeId = ref<string | null>(null);
const editingItemId = ref<string | null>(null);
const typeForm = reactive({ code: '', name: '', status: 'ENABLED' as DictionaryStatus, description: '', version: 0 });
const itemForm = reactive({
  value: '',
  labelZh: '',
  labelEn: '',
  semanticType: 'default' as DictionarySemanticType,
  sortOrder: 10,
  defaultItem: false,
  status: 'ENABLED' as DictionaryStatus,
  version: 0
});

const statusOptions = computed<SelectOption[]>(() => [
  { label: t('mine.common.status.enabled'), value: 'ENABLED' },
  { label: t('mine.common.status.disabled'), value: 'DISABLED' }
]);
const semanticOptions = computed<SelectOption[]>(() =>
  (['default', 'primary', 'info', 'success', 'warning', 'error'] as DictionarySemanticType[]).map(value => ({
    label: t(`mine.dictionaries.semantic.${value}`),
    value
  }))
);
const typeColumns = computed<DataTableColumns<DictionaryType>>(() => [
  { title: t('mine.dictionaries.column.typeName'), key: 'name', minWidth: 140 },
  { title: t('mine.dictionaries.column.code'), key: 'code', minWidth: 150 },
  {
    title: t('mine.dictionaries.column.status'),
    key: 'status',
    width: 90,
    render: row =>
      h(NTag, { size: 'small', bordered: false, type: row.status === 'ENABLED' ? 'success' : 'warning' }, () =>
        statusLabel(row.status)
      )
  },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 120,
    render: row =>
      h(NSpace, { size: 8 }, () => [
        can('system:dictionary:update')
          ? h(
              NButton,
              {
                text: true,
                onClick: event => {
                  event.stopPropagation();
                  openEditType(row);
                }
              },
              () => t('mine.common.edit')
            )
          : null,
        can('system:dictionary:delete') && !row.builtIn
          ? h(
              NButton,
              {
                text: true,
                type: 'error',
                onClick: event => {
                  event.stopPropagation();
                  removeType(row);
                }
              },
              () => t('mine.common.delete')
            )
          : null
      ])
  }
]);
const itemColumns = computed<DataTableColumns<DictionaryItem>>(() => [
  { title: t('mine.dictionaries.column.labelZh'), key: 'labelZh', minWidth: 110 },
  { title: t('mine.dictionaries.column.value'), key: 'value', minWidth: 110 },
  {
    title: t('mine.dictionaries.column.semantic'),
    key: 'semanticType',
    width: 110,
    render: row =>
      h(NTag, { size: 'small', bordered: false, type: tagType(row.semanticType) }, () =>
        t(`mine.dictionaries.semantic.${row.semanticType}`)
      )
  },
  { title: t('mine.dictionaries.column.sort'), key: 'sortOrder', width: 80 },
  {
    title: t('mine.dictionaries.column.default'),
    key: 'defaultItem',
    width: 80,
    render: row => (row.defaultItem ? t('mine.common.yes') : t('mine.common.no'))
  },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 120,
    render: row =>
      h(NSpace, { size: 8 }, () => [
        can('system:dictionary:update')
          ? h(NButton, { text: true, type: 'primary', onClick: () => openEditItem(row) }, () => t('mine.common.edit'))
          : null,
        can('system:dictionary:delete')
          ? h(NButton, { text: true, type: 'error', onClick: () => removeItem(row) }, () => t('mine.common.delete'))
          : null
      ])
  }
]);

onMounted(loadTypes);

async function loadTypes() {
  loading.value = true;
  try {
    types.value = await getDictionaryTypes();
    if (selectedType.value) {
      selectedType.value = types.value.find(item => item.id === selectedType.value?.id) || null;
    }
    if (!selectedType.value && types.value.length) await selectType(types.value[0]);
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
}

async function selectType(type: DictionaryType) {
  selectedType.value = type;
  try {
    items.value = await getDictionaryItems(type.id);
  } catch (error) {
    showError(error);
  }
}

const typeRowProps: DataTableCreateRowProps<DictionaryType> = row => ({
  'data-dictionary-type-id': row.id,
  'aria-selected': selectedType.value?.id === row.id,
  tabindex: 0,
  class: [
    'cursor-pointer transition-colors focus-visible:outline-2 focus-visible:outline-primary',
    selectedType.value?.id === row.id ? 'bg-primary bg-opacity-10' : ''
  ],
  onClick: () => selectType(row),
  onKeydown: event => {
    if (event.currentTarget !== event.target) return;
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    selectType(row);
  }
});

function openCreateType() {
  editingTypeId.value = null;
  Object.assign(typeForm, { code: '', name: '', status: 'ENABLED', description: '', version: 0 });
  typeModalVisible.value = true;
}

function openEditType(type: DictionaryType) {
  editingTypeId.value = type.id;
  Object.assign(typeForm, {
    code: type.code,
    name: type.name,
    status: type.status,
    description: type.description || '',
    version: type.version
  });
  typeModalVisible.value = true;
}

async function saveType() {
  if (!typeForm.code.trim() || !typeForm.name.trim())
    return window.$message?.warning(t('mine.dictionaries.validation.required'));
  saving.value = true;
  try {
    if (editingTypeId.value) {
      await updateDictionaryType(editingTypeId.value, {
        name: typeForm.name.trim(),
        status: typeForm.status,
        description: typeForm.description.trim() || undefined,
        version: typeForm.version
      });
    } else {
      await createDictionaryType({
        code: typeForm.code.trim(),
        name: typeForm.name.trim(),
        status: typeForm.status,
        description: typeForm.description.trim() || undefined
      });
    }
    typeModalVisible.value = false;
    window.$message?.success(t('mine.common.saved'));
    await loadTypes();
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

async function removeType(type: DictionaryType) {
  if (!window.confirm(t('mine.dictionaries.confirmDeleteType', { name: type.name }))) return;
  try {
    await deleteDictionaryType(type.id);
    if (selectedType.value?.id === type.id) {
      selectedType.value = null;
      items.value = [];
    }
    await loadTypes();
  } catch (error) {
    showError(error);
  }
}

function openCreateItem() {
  if (!selectedType.value) return;
  editingItemId.value = null;
  Object.assign(itemForm, {
    value: '',
    labelZh: '',
    labelEn: '',
    semanticType: 'default',
    sortOrder: 10,
    defaultItem: false,
    status: 'ENABLED',
    version: 0
  });
  itemModalVisible.value = true;
}

function openEditItem(item: DictionaryItem) {
  editingItemId.value = item.id;
  Object.assign(itemForm, { ...item, labelEn: item.labelEn || '' });
  itemModalVisible.value = true;
}

async function saveItem() {
  if (!selectedType.value || !itemForm.value.trim() || !itemForm.labelZh.trim()) {
    return window.$message?.warning(t('mine.dictionaries.validation.required'));
  }
  saving.value = true;
  try {
    const common = {
      labelZh: itemForm.labelZh.trim(),
      labelEn: itemForm.labelEn.trim() || undefined,
      semanticType: itemForm.semanticType,
      sortOrder: itemForm.sortOrder,
      defaultItem: itemForm.defaultItem,
      status: itemForm.status
    };
    if (editingItemId.value) await updateDictionaryItem(editingItemId.value, { ...common, version: itemForm.version });
    else await createDictionaryItem(selectedType.value.id, { ...common, value: itemForm.value.trim() });
    itemModalVisible.value = false;
    window.$message?.success(t('mine.common.saved'));
    await selectType(selectedType.value);
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

async function removeItem(item: DictionaryItem) {
  if (!selectedType.value || !window.confirm(t('mine.dictionaries.confirmDeleteItem', { label: item.labelZh }))) return;
  try {
    await deleteDictionaryItem(item.id);
    await selectType(selectedType.value);
  } catch (error) {
    showError(error);
  }
}

function statusLabel(status: DictionaryStatus) {
  return t(`mine.common.status.${status.toLowerCase()}`);
}

function tagType(value: DictionarySemanticType) {
  return value === 'default' ? undefined : value;
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
    <NPageHeader :title="t('mine.dictionaries.title')" :subtitle="t('mine.dictionaries.subtitle')">
      <template #extra>
        <NButton v-if="can('system:dictionary:create')" type="primary" @click="openCreateType">
          {{ t('mine.dictionaries.action.createType') }}
        </NButton>
      </template>
    </NPageHeader>

    <NCard data-dictionary-workspace :bordered="false">
      <NGrid data-dictionary-layout data-cols="1 l:20" responsive="screen" cols="1 l:20" :x-gap="24" :y-gap="24">
        <NGi data-dictionary-panel data-span="1 l:7" span="1 l:7" class="h-full">
          <NSpace vertical :size="16" class="h-full">
            <NText strong>{{ t('mine.dictionaries.typeList') }}</NText>
            <NDataTable
              :columns="typeColumns"
              :data="types"
              :loading="loading"
              :row-key="row => row.id"
              :row-props="typeRowProps"
            />
          </NSpace>
        </NGi>
        <NGi data-dictionary-panel data-span="1 l:13" span="1 l:13" class="h-full">
          <NSpace vertical :size="16" class="h-full">
            <NSpace align="center" justify="space-between">
              <NSpace align="center">
                <NText strong>{{ selectedType?.name || t('mine.dictionaries.selectType') }}</NText>
                <NTag v-if="selectedType?.builtIn" size="small" :bordered="false" type="info">
                  {{ t('mine.dictionaries.builtIn') }}
                </NTag>
              </NSpace>
              <NButton
                v-if="selectedType && can('system:dictionary:create')"
                type="primary"
                secondary
                @click="openCreateItem"
              >
                {{ t('mine.dictionaries.action.createItem') }}
              </NButton>
            </NSpace>
            <NDataTable :columns="itemColumns" :data="items" :row-key="row => row.id" />
          </NSpace>
        </NGi>
      </NGrid>
    </NCard>

    <NModal
      v-model:show="typeModalVisible"
      preset="card"
      :title="t(editingTypeId ? 'mine.dictionaries.modal.editType' : 'mine.dictionaries.modal.createType')"
      :style="{ width: 'min(560px, 92vw)' }"
    >
      <NForm label-placement="top">
        <NFormItem :label="t('mine.dictionaries.field.code')">
          <NInput
            v-model:value="typeForm.code"
            :disabled="Boolean(editingTypeId)"
            :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.dictionaries.field.code') })"
          />
        </NFormItem>
        <NFormItem :label="t('mine.dictionaries.field.name')">
          <NInput
            v-model:value="typeForm.name"
            :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.dictionaries.field.name') })"
          />
        </NFormItem>
        <NFormItem :label="t('mine.dictionaries.field.status')">
          <NSelect
            v-model:value="typeForm.status"
            :options="statusOptions"
            :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.dictionaries.field.status') })"
          />
        </NFormItem>
        <NFormItem :label="t('mine.dictionaries.field.description')">
          <NInput
            v-model:value="typeForm.description"
            type="textarea"
            :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.dictionaries.field.description') })"
          />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="typeModalVisible = false">{{ t('mine.common.cancel') }}</NButton>
          <NButton type="primary" :loading="saving" @click="saveType">{{ t('mine.common.save') }}</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="itemModalVisible"
      preset="card"
      :title="t(editingItemId ? 'mine.dictionaries.modal.editItem' : 'mine.dictionaries.modal.createItem')"
      :style="{ width: 'min(640px, 92vw)' }"
    >
      <NForm label-placement="top">
        <NGrid :cols="2" :x-gap="16">
          <NGi>
            <NFormItem :label="t('mine.dictionaries.field.value')">
              <NInput
                v-model:value="itemForm.value"
                :disabled="Boolean(editingItemId)"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.dictionaries.field.value') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.dictionaries.field.labelZh')">
              <NInput
                v-model:value="itemForm.labelZh"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.dictionaries.field.labelZh') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.dictionaries.field.labelEn')">
              <NInput
                v-model:value="itemForm.labelEn"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.dictionaries.field.labelEn') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.dictionaries.field.semantic')">
              <NSelect
                v-model:value="itemForm.semanticType"
                :options="semanticOptions"
                :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.dictionaries.field.semantic') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.dictionaries.field.sort')">
              <NInputNumber
                v-model:value="itemForm.sortOrder"
                :min="0"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.dictionaries.field.sort') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.dictionaries.field.status')">
              <NSelect
                v-model:value="itemForm.status"
                :options="statusOptions"
                :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.dictionaries.field.status') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.dictionaries.field.default')">
              <NSwitch v-model:value="itemForm.defaultItem" />
            </NFormItem>
          </NGi>
        </NGrid>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="itemModalVisible = false">{{ t('mine.common.cancel') }}</NButton>
          <NButton type="primary" :loading="saving" @click="saveItem">{{ t('mine.common.save') }}</NButton>
        </NSpace>
      </template>
    </NModal>
  </NSpace>
</template>
