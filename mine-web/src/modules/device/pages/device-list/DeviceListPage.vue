<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  NButton,
  NAlert,
  NCard,
  NDataTable,
  NDrawer,
  NDrawerContent,
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
  NSpin,
  NSwitch,
  NTag
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import {
  archiveDevice,
  createDevice,
  getDeviceAccessStatuses,
  getDeviceConnectionProfile,
  getDevices,
  getProducts,
  saveDeviceConnectionProfile,
  testDeviceAccessStatus,
  testDeviceConnectionProfile,
  updateDevice
} from '@/modules/device/api/device';
import type {
  Device,
  DeviceAccessStatus,
  DeviceAccessTestResult,
  DeviceConnectionProfile,
  DeviceConnectionProfileRequest,
  DeviceConnectionTestResult,
  DeviceStatus,
  Product
} from '@/modules/device/api/device';
import { useAuthStore } from '@/store/modules/auth';

const { t, te, locale } = useI18n();
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
const connectionDrawerVisible = ref(false);
const connectionLoading = ref(false);
const connectionSaving = ref(false);
const connectionTesting = ref(false);
const accessTesting = ref(false);
const connectionDevice = ref<Device | null>(null);
const connectionProfile = ref<DeviceConnectionProfile | null>(null);
const connectionTest = ref<DeviceConnectionTestResult | null>(null);
const accessTest = ref<DeviceAccessTestResult | null>(null);
const accessStatuses = ref<DeviceAccessStatus[]>([]);
const connectionForm = reactive<DeviceConnectionProfileRequest>({
  connectionMode: 'DEVICE_TO_PLATFORM_MQTT',
  host: '',
  port: 8883,
  tlsEnabled: true,
  tlsServerName: null,
  connectTimeoutMillis: 5000,
  keepAliveSeconds: 60,
  offlineThresholdSeconds: 180,
  version: 0
});

const productMap = computed(() => new Map(products.value.map(product => [product.id, product])));
const accessStatusMap = computed(() => new Map(accessStatuses.value.map(status => [status.deviceId, status])));
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
const connectionModeOptions = computed<SelectOption[]>(() =>
  ['DEVICE_TO_PLATFORM_MQTT', 'DEVICE_TO_PLATFORM_TCP', 'PLATFORM_TO_DEVICE_TCP'].map(value => ({
    label: t(`mine.devices.connection.mode.${connectionKey(value)}`),
    value
  }))
);
const connectionStages = computed(() => connectionTest.value?.stages || connectionProfile.value?.lastTestStages || []);
const connectionDirty = computed(() => {
  const profile = connectionProfile.value;
  if (!profile) return true;
  return (
    connectionForm.connectionMode !== profile.connectionMode ||
    connectionForm.host.trim() !== profile.host ||
    connectionForm.port !== profile.port ||
    connectionForm.tlsEnabled !== profile.tlsEnabled ||
    (connectionForm.tlsServerName?.trim() || null) !== profile.tlsServerName ||
    connectionForm.connectTimeoutMillis !== profile.connectTimeoutMillis ||
    connectionForm.keepAliveSeconds !== profile.keepAliveSeconds ||
    connectionForm.offlineThresholdSeconds !== profile.offlineThresholdSeconds ||
    connectionForm.version !== profile.version
  );
});
const columns = computed<DataTableColumns<Device>>(() => [
  { title: t('mine.devices.column.code'), key: 'deviceCode', minWidth: 150 },
  { title: t('mine.devices.column.name'), key: 'name', minWidth: 160 },
  {
    title: t('mine.devices.column.accessStatus'),
    key: 'accessStatus',
    width: 140,
    render: row => {
      const status = accessStatusMap.value.get(row.id)?.status || 'UNKNOWN';
      return h(NTag, { size: 'small', bordered: false, type: accessStatusType(status) }, () =>
        accessStatusLabel(status)
      );
    }
  },
  {
    title: t('mine.devices.column.lastReceivedAt'),
    key: 'lastReceivedAt',
    minWidth: 180,
    render: row => formatOptionalTime(accessStatusMap.value.get(row.id)?.lastReceivedAt)
  },
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
    width: 230,
    render: row =>
      h(NSpace, { size: 12 }, () => [
        can('device:connection:view') && row.status !== 'ARCHIVED'
          ? h(
              NButton,
              {
                text: true,
                type: 'primary',
                'data-action': 'connection-profile',
                onClick: () => openConnectionConfig(row)
              },
              () => t('mine.devices.action.connection')
            )
          : null,
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
    await loadAccessStatuses(response.items.map(device => device.id));
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
}

async function loadAccessStatuses(deviceIds: readonly string[]) {
  if (!deviceIds.length) {
    accessStatuses.value = [];
    return;
  }
  try {
    accessStatuses.value = await getDeviceAccessStatuses(deviceIds);
  } catch (error) {
    accessStatuses.value = [];
    showError(error);
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

async function openConnectionConfig(device: Device) {
  connectionDevice.value = device;
  connectionProfile.value = null;
  connectionTest.value = null;
  accessTest.value = null;
  Object.assign(connectionForm, defaultConnectionForm());
  connectionDrawerVisible.value = true;
  await loadConnectionProfile();
}

async function runAccessStatusTest() {
  if (!connectionDevice.value || !connectionProfile.value || connectionDirty.value) {
    return window.$message?.warning(t('mine.devices.connection.saveBeforeTest'));
  }
  accessTesting.value = true;
  try {
    accessTest.value = await testDeviceAccessStatus(connectionDevice.value.id);
    const message = connectionMessage(accessTest.value.message);
    if (accessTest.value.status === 'ONLINE') window.$message?.success(message);
    else window.$message?.warning(message);
    await loadAccessStatuses(devices.value.map(device => device.id));
  } catch (error) {
    showError(error);
  } finally {
    accessTesting.value = false;
  }
}

async function loadConnectionProfile() {
  if (!connectionDevice.value) return;
  connectionLoading.value = true;
  try {
    const profile = await getDeviceConnectionProfile(connectionDevice.value.id);
    applyConnectionProfile(profile);
  } catch (error) {
    if (!(error instanceof ApiError && error.code === 'DEVICE-404-004')) showError(error);
  } finally {
    connectionLoading.value = false;
  }
}

async function saveConnectionProfile() {
  if (!connectionDevice.value || !connectionForm.host.trim() || connectionForm.port < 1) {
    return window.$message?.warning(t('mine.devices.connection.validation'));
  }
  connectionSaving.value = true;
  try {
    const saved = await saveDeviceConnectionProfile(connectionDevice.value.id, {
      ...connectionForm,
      host: connectionForm.host.trim(),
      tlsServerName: connectionForm.tlsServerName?.trim() || null
    });
    applyConnectionProfile(saved);
    window.$message?.success(t('mine.devices.connection.saved'));
  } catch (error) {
    showError(error);
  } finally {
    connectionSaving.value = false;
  }
}

async function runConnectionTest() {
  if (!connectionDevice.value || !connectionProfile.value || connectionDirty.value) {
    return window.$message?.warning(t('mine.devices.connection.saveBeforeTest'));
  }
  connectionTesting.value = true;
  try {
    connectionTest.value = await testDeviceConnectionProfile(connectionDevice.value.id);
    if (connectionTest.value.status === 'PASSED') {
      window.$message?.success(t('mine.devices.connection.testCompleted'));
    } else {
      window.$message?.warning(connectionMessage(connectionTest.value.message));
    }
    await loadConnectionProfile();
  } catch (error) {
    showError(error);
  } finally {
    connectionTesting.value = false;
  }
}

function applyConnectionProfile(profile: DeviceConnectionProfile) {
  connectionProfile.value = profile;
  Object.assign(connectionForm, {
    connectionMode: profile.connectionMode,
    host: profile.host,
    port: profile.port,
    tlsEnabled: profile.tlsEnabled,
    tlsServerName: profile.tlsServerName,
    connectTimeoutMillis: profile.connectTimeoutMillis,
    keepAliveSeconds: profile.keepAliveSeconds,
    offlineThresholdSeconds: profile.offlineThresholdSeconds,
    version: profile.version
  });
}

function defaultConnectionForm(): DeviceConnectionProfileRequest {
  return {
    connectionMode: 'DEVICE_TO_PLATFORM_MQTT',
    host: '',
    port: 8883,
    tlsEnabled: true,
    tlsServerName: null,
    connectTimeoutMillis: 5000,
    keepAliveSeconds: 60,
    offlineThresholdSeconds: 180,
    version: 0
  };
}

function connectionKey(value: string) {
  return value.toLowerCase().replaceAll('_', '');
}

function connectionMessage(value: string | null | undefined) {
  if (!value) return t('mine.devices.connection.result.unknown');
  const key = `mine.devices.connection.result.${connectionKey(value)}`;
  return te(key) ? t(key) : t('mine.devices.connection.result.unknown');
}

function testStatusType(status: string) {
  if (status === 'PASSED') return 'success' as const;
  if (status === 'FAILED') return 'error' as const;
  if (status === 'PARTIAL' || status === 'SKIPPED') return 'warning' as const;
  return 'default' as const;
}

function accessStatusType(status: string) {
  if (status === 'ONLINE') return 'success' as const;
  if (status === 'FAILED' || status === 'STALE') return 'error' as const;
  if (status === 'WAITING_FOR_DEVICE' || status === 'TRANSPORT_ONLY') return 'warning' as const;
  if (status === 'UNKNOWN') return 'info' as const;
  return 'default' as const;
}

function accessStatusLabel(status: string) {
  const key = `mine.devices.connection.accessStatus.${connectionKey(status)}`;
  return te(key) ? t(key) : t('mine.devices.connection.accessStatus.unknown');
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
function formatOptionalTime(value: string | null | undefined) {
  return value ? formatTime(value) : t('mine.common.empty');
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
  deviceForm,
  creatableProductOptions,
  connectionForm,
  accessTest,
  openCreate,
  openEdit,
  saveDevice,
  openConnectionConfig,
  saveConnectionProfile,
  runConnectionTest,
  runAccessStatusTest
});
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

    <NDrawer v-model:show="connectionDrawerVisible" width="min(680px, 94vw)">
      <NDrawerContent
        :title="t('mine.devices.connection.title', { name: connectionDevice?.name || '' })"
        closable
        :native-scrollbar="false"
      >
        <NSpin :show="connectionLoading">
          <NSpace vertical :size="16">
            <NAlert type="info" :bordered="false">
              {{ t('mine.devices.connection.boundary') }}
            </NAlert>

            <NCard size="small" :title="t('mine.devices.connection.configuration')">
              <NForm label-placement="top">
                <NGrid responsive="screen" cols="1 s:2" :x-gap="16">
                  <NGi :span="2">
                    <NFormItem :label="t('mine.devices.connection.field.mode')">
                      <NSelect
                        v-model:value="connectionForm.connectionMode"
                        :disabled="!can('device:connection:update')"
                        :options="connectionModeOptions"
                      />
                    </NFormItem>
                  </NGi>
                  <NGi>
                    <NFormItem :label="t('mine.devices.connection.field.host')">
                      <NInput
                        v-model:value="connectionForm.host"
                        :disabled="!can('device:connection:update')"
                        :placeholder="t('mine.devices.connection.placeholder.host')"
                      />
                    </NFormItem>
                  </NGi>
                  <NGi>
                    <NFormItem :label="t('mine.devices.connection.field.port')">
                      <NInputNumber
                        v-model:value="connectionForm.port"
                        :disabled="!can('device:connection:update')"
                        :min="1"
                        :max="65535"
                        class="w-full"
                      />
                    </NFormItem>
                  </NGi>
                  <NGi>
                    <NFormItem :label="t('mine.devices.connection.field.tls')">
                      <NSwitch v-model:value="connectionForm.tlsEnabled" :disabled="!can('device:connection:update')" />
                    </NFormItem>
                  </NGi>
                  <NGi>
                    <NFormItem :label="t('mine.devices.connection.field.tlsServerName')">
                      <NInput
                        v-model:value="connectionForm.tlsServerName"
                        :disabled="!connectionForm.tlsEnabled || !can('device:connection:update')"
                        :placeholder="t('mine.devices.connection.placeholder.tlsServerName')"
                      />
                    </NFormItem>
                  </NGi>
                  <NGi>
                    <NFormItem :label="t('mine.devices.connection.field.timeout')">
                      <NInputNumber
                        v-model:value="connectionForm.connectTimeoutMillis"
                        :disabled="!can('device:connection:update')"
                        :min="250"
                        :max="30000"
                        class="w-full"
                      />
                    </NFormItem>
                  </NGi>
                  <NGi>
                    <NFormItem :label="t('mine.devices.connection.field.keepAlive')">
                      <NInputNumber
                        v-model:value="connectionForm.keepAliveSeconds"
                        :disabled="!can('device:connection:update')"
                        :min="0"
                        :max="86400"
                        class="w-full"
                      />
                    </NFormItem>
                  </NGi>
                  <NGi>
                    <NFormItem :label="t('mine.devices.connection.field.offlineThreshold')">
                      <NInputNumber
                        v-model:value="connectionForm.offlineThresholdSeconds"
                        :disabled="!can('device:connection:update')"
                        :min="1"
                        :max="604800"
                        class="w-full"
                      />
                    </NFormItem>
                  </NGi>
                </NGrid>
              </NForm>
            </NCard>

            <NCard size="small" :title="t('mine.devices.connection.diagnostics')">
              <NEmpty v-if="!connectionStages.length" :description="t('mine.devices.connection.notTested')" />
              <NSpace v-else vertical :size="12">
                <NAlert
                  v-if="connectionTest?.message || connectionProfile?.lastTestMessage"
                  :type="testStatusType(connectionTest?.status || connectionProfile?.lastTestStatus || '')"
                  :bordered="false"
                >
                  {{ connectionMessage(connectionTest?.message || connectionProfile?.lastTestMessage) }}
                </NAlert>
                <NCard v-for="stage in connectionStages" :key="stage.stage" size="small">
                  <NSpace justify="space-between" align="center">
                    <span>{{ t(`mine.devices.connection.stage.${connectionKey(stage.stage)}`) }}</span>
                    <NTag :type="testStatusType(stage.status)" :bordered="false" size="small">
                      {{ t(`mine.devices.connection.testStatus.${stage.status.toLowerCase()}`) }}
                    </NTag>
                  </NSpace>
                  <div class="mt-2 text-sm text-color-3">{{ connectionMessage(stage.message) }}</div>
                </NCard>
              </NSpace>
            </NCard>

            <NCard size="small" :title="t('mine.devices.connection.accessDiagnostics')">
              <NEmpty v-if="!accessTest" :description="t('mine.devices.connection.accessNotTested')" />
              <NSpace v-else vertical :size="12">
                <NAlert :type="accessStatusType(accessTest.status)" :bordered="false">
                  {{ connectionMessage(accessTest.message) }}
                </NAlert>
                <NCard v-for="stage in accessTest.stages" :key="stage.layer" size="small">
                  <NSpace justify="space-between" align="center">
                    <span>{{ t(`mine.devices.connection.accessLayer.${connectionKey(stage.layer)}`) }}</span>
                    <NTag :type="testStatusType(stage.status)" :bordered="false" size="small">
                      {{ t(`mine.devices.connection.accessStageStatus.${stage.status.toLowerCase()}`) }}
                    </NTag>
                  </NSpace>
                  <div class="mt-2 text-sm text-color-3">{{ connectionMessage(stage.message) }}</div>
                  <div v-if="stage.observedAt" class="mt-1 text-xs text-color-3">
                    {{ t('mine.devices.connection.observedAt', { time: formatTime(stage.observedAt) }) }}
                  </div>
                </NCard>
              </NSpace>
            </NCard>
          </NSpace>
        </NSpin>

        <template #footer>
          <NSpace justify="end">
            <NButton v-if="can('device:connection:update')" :loading="connectionSaving" @click="saveConnectionProfile">
              {{ t('mine.devices.connection.action.save') }}
            </NButton>
            <NButton
              v-if="can('device:connection:test')"
              :loading="connectionTesting"
              :disabled="!connectionProfile || connectionDirty"
              @click="runConnectionTest"
            >
              {{ t('mine.devices.connection.action.test') }}
            </NButton>
            <NButton
              v-if="can('device:connection:test')"
              type="primary"
              data-action="test-access-status"
              :loading="accessTesting"
              :disabled="!connectionProfile || connectionDirty"
              @click="runAccessStatusTest"
            >
              {{ t('mine.devices.connection.action.checkAccess') }}
            </NButton>
          </NSpace>
        </template>
      </NDrawerContent>
    </NDrawer>
  </NSpace>
</template>
