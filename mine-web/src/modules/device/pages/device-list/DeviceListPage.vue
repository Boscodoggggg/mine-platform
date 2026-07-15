<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  NButton,
  NCard,
  NDataTable,
  NEmpty,
  NForm,
  NFormItem,
  NGi,
  NGrid,
  NInput,
  NModal,
  NPageHeader,
  NSelect,
  NSpace,
  NTag
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import { archiveDevice, createDevice, getDevices, getProducts, updateDevice } from '@/modules/device/api/device';
import type { Device, DeviceStatus, Product } from '@/modules/device/api/device';
import { useAuthStore } from '@/store/modules/auth';

const { t, locale } = useI18n();
const authStore = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const devices = ref<Device[]>([]);
const products = ref<Product[]>([]);
const total = ref(0);
const query = reactive<{ productId: string; keyword: string; status: DeviceStatus | ''; page: number; size: number }>({
  productId: '',
  keyword: '',
  status: '',
  page: 1,
  size: 20
});
const modalVisible = ref(false);
const editingId = ref<string | null>(null);
const deviceForm = reactive({
  productId: '',
  deviceCode: '',
  name: '',
  status: 'ENABLED' as 'ENABLED' | 'DISABLED',
  version: 0
});

const productMap = computed(() => new Map(products.value.map(product => [product.id, product])));
const productOptions = computed<SelectOption[]>(() =>
  products.value.map(product => ({
    label: `${product.name} (${product.productKey})`,
    value: product.id
  }))
);
const creatableProductOptions = computed(() =>
  products.value
    .filter(product => product.status === 'ACTIVE')
    .map(product => ({ label: `${product.name} (${product.productKey})`, value: product.id }))
);
const statusOptions = computed<SelectOption[]>(() => [
  { label: t('mine.devices.status.enabled'), value: 'ENABLED' },
  { label: t('mine.devices.status.disabled'), value: 'DISABLED' },
  { label: t('mine.devices.status.archived'), value: 'ARCHIVED' }
]);
const editableStatusOptions = computed(() => statusOptions.value.slice(0, 2));
const columns = computed<DataTableColumns<Device>>(() => [
  { title: t('mine.devices.column.code'), key: 'deviceCode', minWidth: 150 },
  { title: t('mine.devices.column.name'), key: 'name', minWidth: 160 },
  {
    title: t('mine.devices.column.product'),
    key: 'productId',
    minWidth: 180,
    render: row => productMap.value.get(row.productId)?.name || t('mine.common.empty')
  },
  {
    title: t('mine.devices.column.status'),
    key: 'status',
    width: 110,
    render: row =>
      h(
        NTag,
        {
          size: 'small',
          bordered: false,
          type: row.status === 'ENABLED' ? 'success' : row.status === 'DISABLED' ? 'warning' : 'default'
        },
        () => statusLabel(row.status)
      )
  },
  {
    title: t('mine.devices.column.updatedAt'),
    key: 'updatedAt',
    minWidth: 180,
    render: row => formatTime(row.updatedAt)
  },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 150,
    render: row =>
      h(NSpace, { size: 12 }, () => [
        can('device:device:update') && row.status !== 'ARCHIVED'
          ? h(NButton, { text: true, type: 'primary', onClick: () => openEdit(row) }, () => t('mine.common.edit'))
          : null,
        can('device:device:archive') && row.status !== 'ARCHIVED'
          ? h(NButton, { text: true, type: 'error', onClick: () => confirmArchive(row) }, () =>
              t('mine.devices.action.archive')
            )
          : null
      ])
  }
]);

onMounted(async () => {
  await loadProducts();
  await loadDevices();
});

async function loadProducts() {
  try {
    const response = await getProducts({ page: 1, size: 100 });
    products.value = response.items;
  } catch (error) {
    showError(error);
  }
}

async function loadDevices() {
  loading.value = true;
  try {
    const response = await getDevices(query);
    devices.value = response.items;
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
  return loadDevices();
}
function resetQuery() {
  Object.assign(query, { productId: '', keyword: '', status: '', page: 1 });
  return loadDevices();
}

function openCreate() {
  editingId.value = null;
  Object.assign(deviceForm, { productId: '', deviceCode: '', name: '', status: 'ENABLED', version: 0 });
  modalVisible.value = true;
}

function openEdit(device: Device) {
  editingId.value = device.id;
  Object.assign(deviceForm, {
    productId: device.productId,
    deviceCode: device.deviceCode,
    name: device.name,
    status: device.status === 'DISABLED' ? 'DISABLED' : 'ENABLED',
    version: device.version
  });
  modalVisible.value = true;
}

async function saveDevice() {
  if (
    !deviceForm.productId ||
    !/^[A-Za-z0-9][A-Za-z0-9._:-]{1,127}$/.test(deviceForm.deviceCode) ||
    !deviceForm.name.trim()
  ) {
    return window.$message?.warning(t('mine.devices.validation.required'));
  }
  saving.value = true;
  try {
    if (editingId.value) {
      await updateDevice(editingId.value, {
        name: deviceForm.name.trim(),
        status: deviceForm.status,
        version: deviceForm.version
      });
    } else {
      await createDevice({
        productId: deviceForm.productId,
        deviceCode: deviceForm.deviceCode,
        name: deviceForm.name.trim(),
        status: deviceForm.status
      });
    }
    window.$message?.success(t(editingId.value ? 'mine.devices.message.updated' : 'mine.devices.message.created'));
    modalVisible.value = false;
    await loadDevices();
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

function confirmArchive(device: Device) {
  window.$dialog?.warning({
    title: t('common.warning'),
    content: t('mine.devices.confirmArchive', { name: device.name }),
    positiveText: t('mine.devices.action.archive'),
    negativeText: t('mine.common.cancel'),
    onPositiveClick: () => performArchive(device)
  });
}

async function performArchive(device: Device) {
  try {
    await archiveDevice(device.id, device.version);
    window.$message?.success(t('mine.devices.message.archived'));
    await loadDevices();
  } catch (error) {
    showError(error);
  }
}

function can(permission: string) {
  return hasPermission(authStore.userInfo.buttons, permission);
}
function statusLabel(status: DeviceStatus) {
  return t(`mine.devices.status.${status.toLowerCase()}`);
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

defineExpose({ deviceForm, creatableProductOptions, openCreate, openEdit, saveDevice });
</script>

<template>
  <NSpace vertical :size="16">
    <NPageHeader :title="t('mine.devices.title')" :subtitle="t('mine.devices.subtitle')">
      <template #extra>
        <NButton v-if="can('device:device:create')" type="primary" data-action="create-device" @click="openCreate">
          {{ t('mine.devices.action.create') }}
        </NButton>
      </template>
    </NPageHeader>

    <NCard size="small">
      <NGrid responsive="screen" cols="1 s:2 m:5" :x-gap="12" :y-gap="12">
        <NGi>
          <NInput
            v-model:value="query.keyword"
            clearable
            :placeholder="t('mine.devices.search')"
            @keyup.enter="search"
          />
        </NGi>
        <NGi>
          <NSelect
            v-model:value="query.productId"
            clearable
            filterable
            :options="productOptions"
            :placeholder="t('mine.devices.field.product')"
          />
        </NGi>
        <NGi>
          <NSelect
            v-model:value="query.status"
            clearable
            :options="statusOptions"
            :placeholder="t('mine.common.allStatus')"
          />
        </NGi>
        <NGi>
          <NButton block type="primary" @click="search">{{ t('mine.common.search') }}</NButton>
        </NGi>
        <NGi>
          <NButton block @click="resetQuery">{{ t('mine.common.reset') }}</NButton>
        </NGi>
      </NGrid>
    </NCard>

    <NCard size="small">
      <NEmpty v-if="!loading && devices.length === 0" :description="t('mine.devices.empty')" />
      <NDataTable
        v-else
        remote
        :columns="columns"
        :data="devices"
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
            loadDevices();
          }
        "
        @update:page-size="
          size => {
            query.size = size;
            query.page = 1;
            loadDevices();
          }
        "
      />
    </NCard>

    <NModal
      v-model:show="modalVisible"
      preset="card"
      :title="t(editingId ? 'mine.devices.modal.edit' : 'mine.devices.modal.create')"
      class="w-full max-w-640px"
    >
      <NForm label-placement="top">
        <NGrid responsive="screen" cols="1 s:2" :x-gap="16">
          <NGi>
            <NFormItem :label="t('mine.devices.field.product')">
              <NSelect
                v-model:value="deviceForm.productId"
                filterable
                :disabled="Boolean(editingId)"
                :options="editingId ? productOptions : creatableProductOptions"
                :placeholder="t('mine.devices.placeholder.product')"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.devices.field.code')">
              <NInput
                v-model:value="deviceForm.deviceCode"
                :disabled="Boolean(editingId)"
                :placeholder="t('mine.devices.placeholder.code')"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.devices.field.name')">
              <NInput v-model:value="deviceForm.name" maxlength="128" />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.devices.field.status')">
              <NSelect v-model:value="deviceForm.status" :options="editableStatusOptions" />
            </NFormItem>
          </NGi>
        </NGrid>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="modalVisible = false">{{ t('mine.common.cancel') }}</NButton>
          <NButton type="primary" :loading="saving" @click="saveDevice">{{ t('mine.common.save') }}</NButton>
        </NSpace>
      </template>
    </NModal>
  </NSpace>
</template>
