<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import type { DataTableColumns, SelectOption } from "naive-ui";
import {
  NButton,
  NCard,
  NDataTable,
  NEmpty,
  NFlex,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NModal,
  NSelect,
  NSpace,
  NTag,
  NText,
} from "naive-ui";
import { ApiError } from "@/api/client";
import { hasPermission } from "@/app/permission/permission";
import ListActionButton from "@/components/common/list-action-button.vue";
import ListSearchPanel from "@/components/common/list-search-panel.vue";
import { useAuthStore } from "@/store/modules/auth";
import MineSiteSelector from "../../components/MineSiteSelector.vue";
import {
  archiveMineVehicle,
  createMineVehicle,
  getMineVehicles,
  updateMineVehicle,
} from "../../api/mine-api";
import type { Vehicle, VehicleStatus, VehicleType } from "../../api/mine-api";
import { useMineContextStore } from "../../stores/mine-context";

const { t, locale } = useI18n();
const authStore = useAuthStore();
const mineContext = useMineContextStore();
const loading = ref(false);
const saving = ref(false);
const vehicles = ref<Vehicle[]>([]);
const total = ref(0);
const query = reactive<{
  keyword: string;
  status: VehicleStatus | null;
  page: number;
  size: number;
}>({ keyword: "", status: null, page: 1, size: 20 });
const modalVisible = ref(false);
const editingId = ref<string | null>(null);
const vehicleForm = reactive({
  vehicleCode: "",
  name: "",
  vehicleType: "HAUL_TRUCK" as VehicleType,
  mineNumber: "",
  licensePlate: "",
  brand: "",
  model: "",
  ratedLoadTons: null as number | null,
  version: 0,
});

const statusOptions = computed<SelectOption[]>(() =>
  ["ACTIVE", "MAINTENANCE", "OUT_OF_SERVICE", "ARCHIVED"].map((value) => ({
    label: t(`mine.assets.vehicle.status.${statusKey(value)}`),
    value,
  })),
);
const vehicleTypeOptions = computed<SelectOption[]>(() =>
  [
    "HAUL_TRUCK",
    "EXCAVATOR",
    "LOADER",
    "DRILL",
    "DOZER",
    "WATER_TRUCK",
    "LIGHT_VEHICLE",
    "OTHER",
  ].map((value) => ({
    label: t(`mine.assets.vehicle.type.${statusKey(value)}`),
    value,
  })),
);
const columns = computed<DataTableColumns<Vehicle>>(() => [
  { title: t("mine.assets.vehicle.code"), key: "vehicleCode", minWidth: 130 },
  { title: t("mine.assets.vehicle.name"), key: "name", minWidth: 160 },
  {
    title: t("mine.assets.vehicle.typeLabel"),
    key: "vehicleType",
    minWidth: 130,
    render: (row) =>
      t(`mine.assets.vehicle.type.${statusKey(row.vehicleType)}`),
  },
  {
    title: t("mine.assets.vehicle.mineNumber"),
    key: "mineNumber",
    minWidth: 110,
    render: (row) => row.mineNumber || t("mine.common.empty"),
  },
  {
    title: t("mine.assets.vehicle.ratedLoad"),
    key: "ratedLoadTons",
    width: 120,
    render: (row) =>
      row.ratedLoadTons == null
        ? t("mine.common.empty")
        : t("mine.assets.vehicle.tons", { value: row.ratedLoadTons }),
  },
  {
    title: t("mine.assets.common.status"),
    key: "status",
    width: 120,
    render: (row) =>
      h(
        NTag,
        { size: "small", bordered: false, type: vehicleStatusType(row.status) },
        () => t(`mine.assets.vehicle.status.${statusKey(row.status)}`),
      ),
  },
  {
    title: t("mine.assets.common.updatedAt"),
    key: "updatedAt",
    minWidth: 170,
    render: (row) => formatTime(row.updatedAt),
  },
  {
    title: t("mine.common.action"),
    key: "actions",
    width: 150,
    render: (row) =>
      h(NSpace, { size: 10 }, () => [
        can("mine:vehicle:edit") && row.status !== "ARCHIVED"
          ? h(
              NButton,
              { text: true, type: "primary", onClick: () => openEdit(row) },
              () => t("mine.common.edit"),
            )
          : null,
        can("mine:vehicle:edit") && row.status !== "ARCHIVED"
          ? h(
              NButton,
              { text: true, type: "error", onClick: () => confirmArchive(row) },
              () => t("mine.assets.action.archive"),
            )
          : null,
      ]),
  },
]);

onMounted(initialize);

async function initialize() {
  try {
    await mineContext.refreshContext();
    if (!mineContext.selectedSiteId && mineContext.sites.length === 1)
      mineContext.selectSite(mineContext.sites[0]!.id);
    await loadVehicles();
  } catch (error) {
    showError(error);
  }
}

async function loadVehicles(siteId = mineContext.selectedSiteId) {
  if (!siteId) {
    vehicles.value = [];
    total.value = 0;
    return;
  }
  loading.value = true;
  try {
    const response = await getMineVehicles(siteId, query);
    vehicles.value = response.items;
    total.value = response.total;
    query.page = response.page;
    query.size = response.size;
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
}

async function changeSite(siteId: string) {
  mineContext.selectSite(siteId);
  query.page = 1;
  await loadVehicles(siteId);
}

function search() {
  query.page = 1;
  return loadVehicles();
}
function resetQuery() {
  Object.assign(query, { keyword: "", status: null, page: 1 });
  return loadVehicles();
}
function openCreate() {
  editingId.value = null;
  Object.assign(vehicleForm, {
    vehicleCode: "",
    name: "",
    vehicleType: "HAUL_TRUCK",
    mineNumber: "",
    licensePlate: "",
    brand: "",
    model: "",
    ratedLoadTons: null,
    version: 0,
  });
  modalVisible.value = true;
}
function openEdit(value: Vehicle) {
  editingId.value = value.id;
  Object.assign(vehicleForm, {
    vehicleCode: value.vehicleCode,
    name: value.name,
    vehicleType: value.vehicleType,
    mineNumber: value.mineNumber ?? "",
    licensePlate: value.licensePlate ?? "",
    brand: value.brand ?? "",
    model: value.model ?? "",
    ratedLoadTons: value.ratedLoadTons,
    version: value.version,
  });
  modalVisible.value = true;
}

async function saveVehicle() {
  const siteId = mineContext.selectedSiteId;
  if (
    !siteId ||
    !vehicleForm.name.trim() ||
    (!editingId.value && !vehicleForm.vehicleCode.trim())
  )
    return window.$message?.warning(t("mine.assets.validation.required"));
  saving.value = true;
  const details = {
    name: vehicleForm.name.trim(),
    vehicleType: vehicleForm.vehicleType,
    mineNumber: emptyToNull(vehicleForm.mineNumber),
    licensePlate: emptyToNull(vehicleForm.licensePlate),
    brand: emptyToNull(vehicleForm.brand),
    model: emptyToNull(vehicleForm.model),
    ratedLoadTons: vehicleForm.ratedLoadTons,
  };
  try {
    if (editingId.value)
      await updateMineVehicle(siteId, editingId.value, {
        ...details,
        version: vehicleForm.version,
      });
    else
      await createMineVehicle(siteId, {
        vehicleCode: vehicleForm.vehicleCode.trim(),
        ...details,
      });
    modalVisible.value = false;
    window.$message?.success(t("mine.assets.message.saved"));
    await loadVehicles();
  } catch (error) {
    await handleWriteError(error);
  } finally {
    saving.value = false;
  }
}

function confirmArchive(value: Vehicle) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId) return;
  window.$dialog?.warning({
    title: t("common.warning"),
    content: t("mine.assets.vehicle.confirmArchive", { name: value.name }),
    positiveText: t("mine.assets.action.archive"),
    negativeText: t("mine.common.cancel"),
    onPositiveClick: async () => {
      try {
        await archiveMineVehicle(siteId, value.id, value.version);
        window.$message?.success(t("mine.assets.message.archived"));
        await loadVehicles();
      } catch (error) {
        await handleWriteError(error);
      }
    },
  });
}

async function handleWriteError(error: unknown) {
  if (error instanceof ApiError && error.status === 409) {
    window.$message?.warning(t("mine.assets.message.optimisticConflict"));
    await loadVehicles();
    return;
  }
  showError(error);
}
function showError(error: unknown) {
  if (error instanceof ApiError) return window.$message?.error(error.message);
  window.$message?.error(t("mine.common.operationFailed"));
}
function can(permission: string) {
  return hasPermission(authStore.userInfo.buttons, permission);
}
function statusKey(value: string) {
  return value.toLowerCase().replaceAll("_", "") as "active";
}
function vehicleStatusType(status: VehicleStatus) {
  if (status === "ACTIVE") return "success" as const;
  if (status === "MAINTENANCE") return "warning" as const;
  if (status === "OUT_OF_SERVICE") return "error" as const;
  return "default" as const;
}
function emptyToNull(value: string) {
  return value.trim() || null;
}
function formatTime(value: string) {
  return new Date(value).toLocaleString(locale.value, { hour12: false });
}

defineExpose({
  vehicleForm,
  changeSite,
  loadVehicles,
  openCreate,
  openEdit,
  saveVehicle,
});
</script>

<template>
  <NSpace vertical :size="16">
    <MineSiteSelector @change="changeSite" @refresh="loadVehicles" />
    <ListSearchPanel>
      <NFlex data-responsive-stack class="flex-col md:flex-row" align="center">
        <NInput
          v-model:value="query.keyword"
          clearable
          :placeholder="t('mine.assets.vehicle.search')"
          class="w-full md:flex-1"
          @keyup.enter="search"
        />
        <NSelect
          v-model:value="query.status"
          clearable
          :options="statusOptions"
          :placeholder="t('mine.common.allStatus')"
          class="w-full md:w-52"
        />
        <NFlex>
          <ListActionButton kind="search" @click="search">
            {{
              t("mine.common.search")
            }}
          </ListActionButton><ListActionButton kind="reset" @click="resetQuery">
            {{
              t("mine.common.reset")
            }}
          </ListActionButton>
        </NFlex>
      </NFlex>
    </ListSearchPanel>
    <NCard
      data-list-card
      :title="t('mine.assets.vehicle.listTitle')"
      :bordered="false"
      size="small"
    >
      <template #header-extra>
        <NFlex>
          <ListActionButton
            v-if="can('mine:vehicle:edit') && mineContext.selectedSiteId"
            kind="create"
            data-action="create-vehicle"
            @click="openCreate"
          >
            {{ t("mine.assets.vehicle.create") }}
          </ListActionButton><ListActionButton
            kind="refresh"
            :loading="loading"
            @click="loadVehicles()"
          >
            {{ t("common.refresh") }}
          </ListActionButton>
        </NFlex>
      </template>
      <NEmpty
        v-if="!mineContext.selectedSiteId"
        :description="t('mine.assets.context.empty')"
      />
      <NEmpty
        v-else-if="!loading && vehicles.length === 0"
        :description="t('mine.assets.vehicle.empty')"
      >
        <template #extra>
          <NText depth="3">
            {{
              t("mine.assets.vehicle.emptyGuide")
            }}
          </NText>
        </template>
      </NEmpty>
      <NDataTable
        v-else
        remote
        :columns="columns"
        :data="vehicles"
        :loading="loading"
        :row-key="(row) => row.id"
        :scroll-x="1180"
        :pagination="{
          page: query.page,
          pageSize: query.size,
          itemCount: total,
          showSizePicker: true,
          pageSizes: [10, 20, 50, 100],
        }"
        @update:page="
          (page) => {
            query.page = page;
            loadVehicles();
          }
        "
        @update:page-size="
          (size) => {
            query.page = 1;
            query.size = size;
            loadVehicles();
          }
        "
      />
    </NCard>
    <NModal
      v-model:show="modalVisible"
      preset="card"
      :title="
        t(
          editingId
            ? 'mine.assets.vehicle.editTitle'
            : 'mine.assets.vehicle.createTitle',
        )
      "
      class="max-w-92vw w-160"
    >
      <NForm label-placement="top">
        <NFlex class="flex-col md:flex-row">
          <NFormItem :label="t('mine.assets.vehicle.code')" class="flex-1">
            <NInput
              v-model:value="vehicleForm.vehicleCode"
              :disabled="Boolean(editingId)"
            />
          </NFormItem><NFormItem :label="t('mine.assets.vehicle.name')" class="flex-1">
            <NInput v-model:value="vehicleForm.name" />
          </NFormItem>
        </NFlex><NFormItem :label="t('mine.assets.vehicle.typeLabel')">
          <NSelect
            v-model:value="vehicleForm.vehicleType"
            :options="vehicleTypeOptions"
          />
        </NFormItem><NFlex class="flex-col md:flex-row">
          <NFormItem
            :label="t('mine.assets.vehicle.mineNumber')"
            class="flex-1"
          >
            <NInput v-model:value="vehicleForm.mineNumber" />
          </NFormItem><NFormItem
            :label="t('mine.assets.vehicle.licensePlate')"
            class="flex-1"
          >
            <NInput
              v-model:value="vehicleForm.licensePlate"
            />
          </NFormItem>
        </NFlex><NFlex class="flex-col md:flex-row">
          <NFormItem :label="t('mine.assets.vehicle.brand')" class="flex-1">
            <NInput v-model:value="vehicleForm.brand" />
          </NFormItem><NFormItem :label="t('mine.assets.vehicle.model')" class="flex-1">
            <NInput v-model:value="vehicleForm.model" />
          </NFormItem><NFormItem :label="t('mine.assets.vehicle.ratedLoad')" class="flex-1">
            <NInputNumber
              v-model:value="vehicleForm.ratedLoadTons"
              :min="0.01"
              class="w-full"
            />
          </NFormItem>
        </NFlex>
      </NForm>
      <template #footer>
        <NFlex justify="end">
          <NButton @click="modalVisible = false">
            {{
              t("mine.common.cancel")
            }}
          </NButton><NButton type="primary" :loading="saving" @click="saveVehicle">
            {{
              t("mine.common.save")
            }}
          </NButton>
        </NFlex>
      </template>
    </NModal>
  </NSpace>
</template>
