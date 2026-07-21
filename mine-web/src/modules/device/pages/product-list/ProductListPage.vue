<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
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
  NModal,
  NSelect,
  NSpace,
  NTag,
  NText
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import ListActionButton from '@/components/common/list-action-button.vue';
import ListSearchPanel from '@/components/common/list-search-panel.vue';
import { archiveProduct, createProduct, getProducts, updateProduct } from '@/modules/device/api/device';
import type { Product, ProductStatus } from '@/modules/device/api/device';
import type { StrategyType } from '@/modules/device/api/strategy';
import StrategyWorkbenchPage from '@/modules/device/pages/strategy/StrategyWorkbenchPage.vue';
import { getEnabledDictionaryItems } from '@/modules/system/api/dictionary';
import type { DictionaryItem } from '@/modules/system/api/dictionary';
import { useAuthStore } from '@/store/modules/auth';

const { t, locale } = useI18n();
const authStore = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const products = ref<Product[]>([]);
const deviceTypeItems = ref<DictionaryItem[]>([]);
const productStatusItems = ref<DictionaryItem[]>([]);
const total = ref(0);
const query = reactive<{ keyword: string; status: ProductStatus | null; page: number; size: number }>({
  keyword: '',
  status: null,
  page: 1,
  size: 20
});
const modalVisible = ref(false);
const strategyDrawerVisible = ref(false);
const strategyProduct = ref<Product | null>(null);
const productStrategyTypes: StrategyType[] = ['ONLINE_STATUS', 'DATA_QUALITY', 'ALARM'];
const editingId = ref<string | null>(null);
const productForm = reactive({
  productKey: '',
  name: '',
  deviceType: null as string | null,
  status: 'DRAFT' as 'DRAFT' | 'ACTIVE',
  version: 0
});

const deviceTypeOptions = computed<SelectOption[]>(() => dictionaryOptions(deviceTypeItems.value));
const statusOptions = computed<SelectOption[]>(() => dictionaryOptions(productStatusItems.value));
const editableStatusOptions = computed(() =>
  statusOptions.value.filter(option => option.value === 'DRAFT' || option.value === 'ACTIVE')
);
const columns = computed<DataTableColumns<Product>>(() => [
  { title: t('mine.products.column.key'), key: 'productKey', minWidth: 150 },
  { title: t('mine.products.column.name'), key: 'name', minWidth: 160 },
  {
    title: t('mine.products.column.type'),
    key: 'deviceType',
    minWidth: 130,
    render: row => dictionaryLabel(deviceTypeItems.value, row.deviceType) || row.deviceType
  },
  {
    title: t('mine.products.column.status'),
    key: 'status',
    width: 110,
    render: row =>
      h(NTag, { size: 'small', bordered: false, type: productStatusType(row.status) }, () => statusLabel(row.status))
  },
  {
    title: t('mine.products.column.updatedAt'),
    key: 'updatedAt',
    minWidth: 180,
    render: row => formatTime(row.updatedAt)
  },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 230,
    render: row =>
      h(NSpace, { size: 12 }, () => [
        can('device:strategy:view') && row.status === 'ACTIVE'
          ? h(
              NButton,
              {
                text: true,
                type: 'primary',
                'data-action': 'product-runtime-strategy',
                onClick: () => openRuntimeStrategy(row)
              },
              () => t('mine.products.action.runtimeStrategy')
            )
          : null,
        can('device:product:update') && row.status !== 'ARCHIVED'
          ? h(NButton, { text: true, type: 'primary', onClick: () => openEdit(row) }, () => t('mine.common.edit'))
          : null,
        can('device:product:archive') && row.status !== 'ARCHIVED'
          ? h(NButton, { text: true, type: 'error', onClick: () => confirmArchive(row) }, () =>
              t('mine.products.action.archive')
            )
          : null
      ])
  }
]);

onMounted(async () => {
  await Promise.all([loadDictionaries(), loadProducts()]);
});

async function loadDictionaries() {
  try {
    const [types, statuses] = await Promise.all([
      getEnabledDictionaryItems('device_type'),
      getEnabledDictionaryItems('product_status')
    ]);
    deviceTypeItems.value = types;
    productStatusItems.value = statuses.filter(item => ['DRAFT', 'ACTIVE', 'ARCHIVED'].includes(item.value));
  } catch (error) {
    showError(error);
  }
}

async function loadProducts() {
  loading.value = true;
  try {
    const response = await getProducts(query);
    products.value = response.items;
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
  return loadProducts();
}

function resetQuery() {
  Object.assign(query, { keyword: '', status: null, page: 1 });
  return loadProducts();
}

function openCreate() {
  editingId.value = null;
  Object.assign(productForm, { productKey: '', name: '', deviceType: null, status: 'DRAFT', version: 0 });
  modalVisible.value = true;
}

function openEdit(product: Product) {
  editingId.value = product.id;
  Object.assign(productForm, {
    productKey: product.productKey,
    name: product.name,
    deviceType: product.deviceType,
    status: product.status === 'ACTIVE' ? 'ACTIVE' : 'DRAFT',
    version: product.version
  });
  modalVisible.value = true;
}

function openRuntimeStrategy(product: Product) {
  strategyProduct.value = product;
  strategyDrawerVisible.value = true;
}

async function saveProduct() {
  const deviceType = productForm.deviceType?.trim();
  if (
    !/^[a-z][a-z0-9-]{2,63}$/.test(productForm.productKey) ||
    !productForm.name.trim() ||
    !deviceType
  ) {
    return window.$message?.warning(t('mine.products.validation.required'));
  }
  saving.value = true;
  try {
    if (editingId.value) {
      await updateProduct(editingId.value, {
        name: productForm.name.trim(),
        deviceType,
        status: productForm.status,
        version: productForm.version
      });
    } else {
      await createProduct({
        productKey: productForm.productKey,
        name: productForm.name.trim(),
        deviceType,
        status: productForm.status
      });
    }
    window.$message?.success(t(editingId.value ? 'mine.products.message.updated' : 'mine.products.message.created'));
    modalVisible.value = false;
    await loadProducts();
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

function confirmArchive(product: Product) {
  window.$dialog?.warning({
    title: t('common.warning'),
    content: t('mine.products.confirmArchive', { name: product.name }),
    positiveText: t('mine.products.action.archive'),
    negativeText: t('mine.common.cancel'),
    onPositiveClick: () => performArchive(product)
  });
}

async function performArchive(product: Product) {
  try {
    await archiveProduct(product.id, product.version);
    window.$message?.success(t('mine.products.message.archived'));
    await loadProducts();
  } catch (error) {
    showError(error);
  }
}

function can(permission: string) {
  return hasPermission(authStore.userInfo.buttons, permission);
}

function statusLabel(status: ProductStatus) {
  return dictionaryLabel(productStatusItems.value, status) || t(`mine.products.status.${status.toLowerCase()}`);
}

function dictionaryOptions(items: DictionaryItem[]): SelectOption[] {
  return items.map(item => ({ label: dictionaryItemLabel(item), value: item.value }));
}

function dictionaryLabel(items: DictionaryItem[], value: string) {
  const item = items.find(candidate => candidate.value === value);
  return item ? dictionaryItemLabel(item) : null;
}

function dictionaryItemLabel(item: DictionaryItem) {
  return locale.value.toLowerCase().startsWith('en') && item.labelEn ? item.labelEn : item.labelZh;
}

function productStatusType(status: ProductStatus) {
  const item = productStatusItems.value.find(candidate => candidate.value === status);
  if (item) return item.semanticType;
  if (status === 'ACTIVE') return 'success' as const;
  if (status === 'ARCHIVED') return 'default' as const;
  return 'warning' as const;
}

function formatTime(value: string) {
  return new Date(value).toLocaleString(locale.value, { hour12: false });
}

function showError(error: unknown) {
  if (error instanceof ApiError) {
    window.$message?.error(
      error.traceId ? `${error.message} (${t('mine.common.traceId', { traceId: error.traceId })})` : error.message
    );
    return;
  }
  window.$message?.error(t('mine.common.operationFailed'));
}

defineExpose({
  productForm,
  deviceTypeOptions,
  statusOptions,
  productStatusType,
  openCreate,
  openEdit,
  openRuntimeStrategy,
  saveProduct
});
</script>

<template>
  <NSpace vertical :size="16">
    <ListSearchPanel>
      <NFlex align="center">
        <NInput
          v-model:value="query.keyword"
          clearable
          :placeholder="t('mine.products.search')"
          class="min-w-60 flex-1"
          @keyup.enter="search"
        />
        <NSelect
          v-model:value="query.status"
          clearable
          :options="statusOptions"
          :placeholder="t('mine.common.allStatus')"
          class="w-52"
        />
        <NSpace data-filter-actions>
          <ListActionButton kind="search" @click="search">{{ t('mine.common.search') }}</ListActionButton>
          <ListActionButton kind="reset" @click="resetQuery">{{ t('mine.common.reset') }}</ListActionButton>
        </NSpace>
      </NFlex>
    </ListSearchPanel>

    <NCard data-list-card :title="t('mine.products.listTitle')" :bordered="false" size="small">
      <template #header-extra>
        <NSpace data-list-toolbar>
          <ListActionButton
            v-if="can('device:product:create')"
            kind="create"
            data-action="create-product"
            @click="openCreate"
          >
            {{ t('mine.products.action.create') }}
          </ListActionButton>
          <ListActionButton kind="refresh" :loading="loading" @click="loadProducts">
            {{ t('common.refresh') }}
          </ListActionButton>
        </NSpace>
      </template>
      <NEmpty v-if="!loading && products.length === 0" :description="t('mine.products.empty')">
        <template #extra>
          <NText depth="3">{{ t('mine.products.emptyDescription') }}</NText>
        </template>
      </NEmpty>
      <NDataTable
        v-else
        remote
        :columns="columns"
        :data="products"
        :loading="loading"
        :pagination="{
          page: query.page,
          pageSize: query.size,
          itemCount: total,
          showSizePicker: true,
          pageSizes: [10, 20, 50, 100]
        }"
        :row-key="row => row.id"
        @update:page="
          page => {
            query.page = page;
            loadProducts();
          }
        "
        @update:page-size="
          size => {
            query.size = size;
            query.page = 1;
            loadProducts();
          }
        "
      />
    </NCard>

    <NModal
      v-model:show="modalVisible"
      preset="card"
      :title="t(editingId ? 'mine.products.modal.edit' : 'mine.products.modal.create')"
      class="w-full max-w-640px"
    >
      <NForm label-placement="top">
        <NGrid responsive="screen" cols="1 s:2" :x-gap="16">
          <NGi>
            <NFormItem :label="t('mine.products.field.key')">
              <NInput
                v-model:value="productForm.productKey"
                :disabled="Boolean(editingId)"
                :placeholder="t('mine.products.placeholder.key')"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.products.field.name')">
              <NInput
                v-model:value="productForm.name"
                maxlength="128"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.products.field.name') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.products.field.type')">
              <NSelect
                v-model:value="productForm.deviceType"
                :options="deviceTypeOptions"
                :placeholder="t('mine.products.placeholder.type')"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.products.field.status')">
              <NSelect
                v-model:value="productForm.status"
                :options="editableStatusOptions"
                :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.products.field.status') })"
              />
            </NFormItem>
          </NGi>
        </NGrid>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="modalVisible = false">{{ t('mine.common.cancel') }}</NButton>
          <NButton type="primary" :loading="saving" @click="saveProduct">{{ t('mine.common.save') }}</NButton>
        </NSpace>
      </template>
    </NModal>

    <NDrawer v-model:show="strategyDrawerVisible" width="min(1440px, 96vw)">
      <NDrawerContent
        :title="t('mine.products.strategy.title', { name: strategyProduct?.name || '' })"
        closable
        :native-scrollbar="false"
      >
        <StrategyWorkbenchPage
          v-if="strategyProduct"
          :key="strategyProduct.id"
          embedded
          :fixed-product-id="strategyProduct.id"
          :allowed-strategy-types="productStrategyTypes"
        />
      </NDrawerContent>
    </NDrawer>
  </NSpace>
</template>
