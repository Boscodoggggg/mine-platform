<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import type { DataTableColumns, SelectOption } from "naive-ui";
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NDatePicker,
  NEmpty,
  NFlex,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NModal,
  NSelect,
  NSpace,
  NSwitch,
  NTabPane,
  NTabs,
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
  archiveMineCoordinateSystem,
  archiveMineLocationTag,
  createMineCoordinateSystem,
  createMineCoordinateTransform,
  createMineLocationTag,
  getMineCoordinateSystems,
  getMineCoordinateTransforms,
  getMineLocationTags,
  publishMineCoordinateTransform,
  updateMineCoordinateSystem,
  updateMineCoordinateTransform,
  updateMineLocationTag,
} from "../../api/mine-api";
import type {
  CoordinateSystem,
  CoordinateTransform,
  LocationTag,
  LocationTagStatus,
} from "../../api/mine-api";
import { useMineContextStore } from "../../stores/mine-context";

const { t, locale } = useI18n();
const authStore = useAuthStore();
const mineContext = useMineContextStore();
const activeTab = ref<"tags" | "coordinates">("tags");
const loading = ref(false);
const saving = ref(false);
const tags = ref<LocationTag[]>([]);
const tagTotal = ref(0);
const tagQuery = reactive<{
  keyword: string;
  status: LocationTagStatus | null;
  page: number;
  size: number;
}>({ keyword: "", status: null, page: 1, size: 20 });
const systems = ref<CoordinateSystem[]>([]);
const transforms = ref<CoordinateTransform[]>([]);
const systemTotal = ref(0);
const transformTotal = ref(0);
const systemQuery = reactive({
  keyword: "",
  status: null as "ACTIVE" | "ARCHIVED" | null,
  page: 1,
  size: 20,
});
const transformQuery = reactive({
  status: null as "DRAFT" | "PUBLISHED" | null,
  page: 1,
  size: 20,
});
const tagModalVisible = ref(false);
const editingTagId = ref<string | null>(null);
const tagForm = reactive({
  tagCode: "",
  vendor: "",
  model: "",
  batteryPercent: null as number | null,
  batteryObservedAt: null as number | null,
  version: 0,
});
const systemModalVisible = ref(false);
const editingSystemId = ref<string | null>(null);
const systemForm = reactive({
  systemCode: "",
  name: "",
  coordinateType: "LOCAL_CARTESIAN" as CoordinateSystem["coordinateType"],
  unit: "METER" as CoordinateSystem["unit"],
  epsgCode: null as number | null,
  unified: false,
  minX: 0,
  maxX: 1000,
  minY: 0,
  maxY: 1000,
  minZ: null as number | null,
  maxZ: null as number | null,
  version: 0,
});
const transformModalVisible = ref(false);
const editingTransformId = ref<string | null>(null);
const transformForm = reactive({
  sourceCoordinateSystemId: "",
  targetCoordinateSystemId: "",
  versionNumber: 1,
  algorithm: "AFFINE_3D" as CoordinateTransform["algorithm"],
  parameters: '{\n  "matrix": []\n}',
  validFrom: Date.now(),
  validTo: null as number | null,
  version: 0,
});

const tagStatusOptions = computed<SelectOption[]>(() =>
  ["ACTIVE", "LOST", "RETIRED", "ARCHIVED"].map((value) => ({
    label: t(`mine.assets.tag.status.${value.toLowerCase()}`),
    value,
  })),
);
const archiveStatusOptions = computed<SelectOption[]>(() =>
  ["ACTIVE", "ARCHIVED"].map((value) => ({
    label: t(`mine.assets.status.${value.toLowerCase()}`),
    value,
  })),
);
const transformStatusOptions = computed<SelectOption[]>(() =>
  ["DRAFT", "PUBLISHED"].map((value) => ({
    label: t(`mine.assets.coordinate.transformStatus.${value.toLowerCase()}`),
    value,
  })),
);
const coordinateTypeOptions = computed<SelectOption[]>(() =>
  ["LOCAL_CARTESIAN", "WGS84", "ENU"].map((value) => ({
    label: t(
      `mine.assets.coordinate.type.${value.toLowerCase().replaceAll("_", "")}`,
    ),
    value,
  })),
);
const unitOptions = computed<SelectOption[]>(() =>
  ["METER", "MILLIMETER", "DEGREE"].map((value) => ({
    label: t(`mine.assets.coordinate.unit.${value.toLowerCase()}`),
    value,
  })),
);
const algorithmOptions = computed<SelectOption[]>(() =>
  ["IDENTITY", "AFFINE_3D", "WGS84_ENU"].map((value) => ({
    label: t(
      `mine.assets.coordinate.algorithm.${value.toLowerCase().replaceAll("_", "")}`,
    ),
    value,
  })),
);
const systemOptions = computed<SelectOption[]>(() =>
  systems.value
    .filter((item) => item.status === "ACTIVE")
    .map((item) => ({
      label: `${item.name} · ${item.systemCode}`,
      value: item.id,
    })),
);
const tagColumns = computed<DataTableColumns<LocationTag>>(() => [
  { title: t("mine.assets.tag.code"), key: "tagCode", minWidth: 130 },
  {
    title: t("mine.assets.tag.vendor"),
    key: "vendor",
    minWidth: 130,
    render: (row) => row.vendor || t("mine.common.empty"),
  },
  {
    title: t("mine.assets.tag.model"),
    key: "model",
    minWidth: 110,
    render: (row) => row.model || t("mine.common.empty"),
  },
  {
    title: t("mine.assets.tag.battery"),
    key: "batteryPercent",
    width: 100,
    render: (row) =>
      row.batteryPercent == null
        ? t("mine.common.empty")
        : `${row.batteryPercent}%`,
  },
  {
    title: t("mine.assets.tag.batteryObservedAt"),
    key: "batteryObservedAt",
    minWidth: 170,
    render: (row) =>
      row.batteryObservedAt
        ? formatTime(row.batteryObservedAt)
        : t("mine.common.empty"),
  },
  {
    title: t("mine.assets.common.status"),
    key: "status",
    width: 100,
    render: (row) =>
      h(
        NTag,
        { size: "small", bordered: false, type: tagStatusType(row.status) },
        () => t(`mine.assets.tag.status.${row.status.toLowerCase()}`),
      ),
  },
  {
    title: t("mine.common.action"),
    key: "actions",
    width: 150,
    render: (row) =>
      h(NSpace, { size: 10 }, () => [
        can("mine:location-tag:edit") && row.status !== "ARCHIVED"
          ? h(
              NButton,
              { text: true, type: "primary", onClick: () => openTagEdit(row) },
              () => t("mine.common.edit"),
            )
          : null,
        can("mine:location-tag:edit") && row.status !== "ARCHIVED"
          ? h(
              NButton,
              {
                text: true,
                type: "error",
                onClick: () => confirmArchiveTag(row),
              },
              () => t("mine.assets.action.archive"),
            )
          : null,
      ]),
  },
]);
const systemColumns = computed<DataTableColumns<CoordinateSystem>>(() => [
  { title: t("mine.assets.coordinate.code"), key: "systemCode", minWidth: 120 },
  { title: t("mine.assets.coordinate.name"), key: "name", minWidth: 160 },
  {
    title: t("mine.assets.coordinate.typeLabel"),
    key: "coordinateType",
    width: 130,
    render: (row) =>
      t(
        `mine.assets.coordinate.type.${row.coordinateType.toLowerCase().replaceAll("_", "")}`,
      ),
  },
  {
    title: t("mine.assets.coordinate.unitLabel"),
    key: "unit",
    width: 100,
    render: (row) => t(`mine.assets.coordinate.unit.${row.unit.toLowerCase()}`),
  },
  {
    title: t("mine.assets.coordinate.range"),
    key: "range",
    minWidth: 180,
    render: (row) =>
      t("mine.assets.coordinate.rangeValue", {
        minX: row.minX,
        maxX: row.maxX,
        minY: row.minY,
        maxY: row.maxY,
      }),
  },
  {
    title: t("mine.assets.coordinate.unified"),
    key: "unified",
    width: 100,
    render: (row) =>
      h(NTag, { size: "small", type: row.unified ? "success" : "info" }, () =>
        t(
          row.unified
            ? "mine.assets.coordinate.unifiedYes"
            : "mine.assets.coordinate.unifiedNo",
        ),
      ),
  },
  {
    title: t("mine.common.action"),
    key: "actions",
    width: 150,
    render: (row) =>
      h(NSpace, { size: 10 }, () => [
        can("mine:coordinate:edit") && row.status !== "ARCHIVED"
          ? h(
              NButton,
              {
                text: true,
                type: "primary",
                onClick: () => openSystemEdit(row),
              },
              () => t("mine.common.edit"),
            )
          : null,
        can("mine:coordinate:edit") && row.status !== "ARCHIVED"
          ? h(
              NButton,
              {
                text: true,
                type: "error",
                onClick: () => confirmArchiveSystem(row),
              },
              () => t("mine.assets.action.archive"),
            )
          : null,
      ]),
  },
]);
const transformColumns = computed<DataTableColumns<CoordinateTransform>>(() => [
  {
    title: t("mine.assets.coordinate.version"),
    key: "versionNumber",
    width: 90,
  },
  {
    title: t("mine.assets.coordinate.source"),
    key: "sourceCoordinateSystemId",
    minWidth: 150,
    render: (row) => systemName(row.sourceCoordinateSystemId),
  },
  {
    title: t("mine.assets.coordinate.target"),
    key: "targetCoordinateSystemId",
    minWidth: 150,
    render: (row) => systemName(row.targetCoordinateSystemId),
  },
  {
    title: t("mine.assets.coordinate.algorithmLabel"),
    key: "algorithm",
    width: 120,
    render: (row) =>
      t(
        `mine.assets.coordinate.algorithm.${row.algorithm.toLowerCase().replaceAll("_", "")}`,
      ),
  },
  {
    title: t("mine.assets.common.status"),
    key: "status",
    width: 100,
    render: (row) =>
      h(
        NTag,
        {
          size: "small",
          type: row.status === "PUBLISHED" ? "success" : "warning",
        },
        () =>
          t(
            `mine.assets.coordinate.transformStatus.${row.status.toLowerCase()}`,
          ),
      ),
  },
  {
    title: t("mine.common.action"),
    key: "actions",
    width: 150,
    render: (row) =>
      h(NSpace, { size: 10 }, () => [
        can("mine:coordinate:edit") && row.status === "DRAFT"
          ? h(
              NButton,
              {
                text: true,
                type: "primary",
                onClick: () => openTransformEdit(row),
              },
              () => t("mine.common.edit"),
            )
          : null,
        can("mine:coordinate:publish") && row.status === "DRAFT"
          ? h(
              NButton,
              {
                text: true,
                type: "warning",
                "data-permission": "coordinate-publish",
                onClick: () => confirmPublish(row),
              },
              () => t("mine.assets.coordinate.publish"),
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
    await loadTags();
  } catch (error) {
    showError(error);
  }
}
async function changeSite(siteId: string) {
  mineContext.selectSite(siteId);
  tagQuery.page = systemQuery.page = transformQuery.page = 1;
  if (activeTab.value === "coordinates") await loadCoordinates(siteId);
  else await loadTags(siteId);
}
async function loadTags(siteId = mineContext.selectedSiteId) {
  if (!siteId) {
    tags.value = [];
    return;
  }
  loading.value = true;
  try {
    const response = await getMineLocationTags(siteId, tagQuery);
    tags.value = response.items;
    tagTotal.value = response.total;
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
}
function changeTagPage(page: number) {
  tagQuery.page = page;
  return loadTags();
}
function changeTagPageSize(size: number) {
  tagQuery.page = 1;
  tagQuery.size = size;
  return loadTags();
}
async function activateCoordinates() {
  if (!can("mine:coordinate:view")) return;
  activeTab.value = "coordinates";
  await loadCoordinates();
}
async function loadCoordinates(siteId = mineContext.selectedSiteId) {
  if (!siteId) {
    systems.value = [];
    transforms.value = [];
    return;
  }
  loading.value = true;
  try {
    const [systemPage, transformPage] = await Promise.all([
      getMineCoordinateSystems(siteId, systemQuery),
      getMineCoordinateTransforms(siteId, transformQuery),
    ]);
    systems.value = systemPage.items;
    systemTotal.value = systemPage.total;
    transforms.value = transformPage.items;
    transformTotal.value = transformPage.total;
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
}
function changeSystemPage(page: number) {
  systemQuery.page = page;
  return loadCoordinates();
}
function changeSystemPageSize(size: number) {
  systemQuery.page = 1;
  systemQuery.size = size;
  return loadCoordinates();
}
function searchSystems() {
  systemQuery.page = 1;
  return loadCoordinates();
}
function changeTransformPage(page: number) {
  transformQuery.page = page;
  return loadCoordinates();
}
function changeTransformPageSize(size: number) {
  transformQuery.page = 1;
  transformQuery.size = size;
  return loadCoordinates();
}
function searchTransforms() {
  transformQuery.page = 1;
  return loadCoordinates();
}
async function changeTab(value: string) {
  if (value === "coordinates") await activateCoordinates();
  else {
    activeTab.value = "tags";
    await loadTags();
  }
}
function openTagCreate() {
  editingTagId.value = null;
  Object.assign(tagForm, {
    tagCode: "",
    vendor: "",
    model: "",
    batteryPercent: null,
    batteryObservedAt: null,
    version: 0,
  });
  tagModalVisible.value = true;
}
function openTagEdit(value: LocationTag) {
  editingTagId.value = value.id;
  Object.assign(tagForm, {
    tagCode: value.tagCode,
    vendor: value.vendor ?? "",
    model: value.model ?? "",
    batteryPercent: value.batteryPercent,
    batteryObservedAt: value.batteryObservedAt
      ? Date.parse(value.batteryObservedAt)
      : null,
    version: value.version,
  });
  tagModalVisible.value = true;
}
async function saveTag() {
  const siteId = mineContext.selectedSiteId;
  if (!siteId || (!editingTagId.value && !tagForm.tagCode.trim()))
    return window.$message?.warning(t("mine.assets.validation.required"));
  saving.value = true;
  const detail = {
    vendor: emptyToNull(tagForm.vendor),
    model: emptyToNull(tagForm.model),
    batteryPercent: tagForm.batteryPercent,
    batteryObservedAt: tagForm.batteryObservedAt
      ? new Date(tagForm.batteryObservedAt).toISOString()
      : null,
  };
  try {
    if (editingTagId.value)
      await updateMineLocationTag(siteId, editingTagId.value, {
        ...detail,
        version: tagForm.version,
      });
    else
      await createMineLocationTag(siteId, {
        tagCode: tagForm.tagCode.trim(),
        ...detail,
      });
    tagModalVisible.value = false;
    window.$message?.success(t("mine.assets.message.saved"));
    await loadTags();
  } catch (error) {
    await handleWriteError(error, loadTags);
  } finally {
    saving.value = false;
  }
}
function confirmArchiveTag(value: LocationTag) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId) return;
  confirmArchive(
    t("mine.assets.tag.confirmArchive", { code: value.tagCode }),
    () => archiveMineLocationTag(siteId, value.id, value.version),
    loadTags,
  );
}
function openSystemCreate() {
  editingSystemId.value = null;
  Object.assign(systemForm, {
    systemCode: "",
    name: "",
    coordinateType: "LOCAL_CARTESIAN",
    unit: "METER",
    epsgCode: null,
    unified: false,
    minX: 0,
    maxX: 1000,
    minY: 0,
    maxY: 1000,
    minZ: null,
    maxZ: null,
    version: 0,
  });
  systemModalVisible.value = true;
}
function openSystemEdit(value: CoordinateSystem) {
  editingSystemId.value = value.id;
  Object.assign(systemForm, value);
  systemModalVisible.value = true;
}
async function saveSystem() {
  const siteId = mineContext.selectedSiteId;
  if (
    !siteId ||
    !systemForm.name.trim() ||
    (!editingSystemId.value && !systemForm.systemCode.trim())
  )
    return window.$message?.warning(t("mine.assets.validation.required"));
  saving.value = true;
  const detail = {
    name: systemForm.name.trim(),
    coordinateType: systemForm.coordinateType,
    unit: systemForm.unit,
    epsgCode: systemForm.epsgCode,
    unified: systemForm.unified,
    minX: systemForm.minX,
    maxX: systemForm.maxX,
    minY: systemForm.minY,
    maxY: systemForm.maxY,
    minZ: systemForm.minZ,
    maxZ: systemForm.maxZ,
  };
  try {
    if (editingSystemId.value)
      await updateMineCoordinateSystem(siteId, editingSystemId.value, {
        ...detail,
        version: systemForm.version,
      });
    else
      await createMineCoordinateSystem(siteId, {
        systemCode: systemForm.systemCode.trim(),
        ...detail,
      });
    systemModalVisible.value = false;
    window.$message?.success(t("mine.assets.message.saved"));
    await loadCoordinates();
  } catch (error) {
    await handleWriteError(error, loadCoordinates);
  } finally {
    saving.value = false;
  }
}
function confirmArchiveSystem(value: CoordinateSystem) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId) return;
  confirmArchive(
    t("mine.assets.coordinate.confirmArchive", { name: value.name }),
    () => archiveMineCoordinateSystem(siteId, value.id, value.version),
    loadCoordinates,
  );
}
function openTransformCreate() {
  editingTransformId.value = null;
  Object.assign(transformForm, {
    sourceCoordinateSystemId: "",
    targetCoordinateSystemId: "",
    versionNumber: 1,
    algorithm: "AFFINE_3D",
    parameters: '{\n  "matrix": []\n}',
    validFrom: Date.now(),
    validTo: null,
    version: 0,
  });
  transformModalVisible.value = true;
}
function openTransformEdit(value: CoordinateTransform) {
  editingTransformId.value = value.id;
  Object.assign(transformForm, {
    sourceCoordinateSystemId: value.sourceCoordinateSystemId,
    targetCoordinateSystemId: value.targetCoordinateSystemId,
    versionNumber: value.versionNumber,
    algorithm: value.algorithm,
    parameters: JSON.stringify(value.parameters, null, 2),
    validFrom: Date.parse(value.validFrom),
    validTo: value.validTo ? Date.parse(value.validTo) : null,
    version: value.version,
  });
  transformModalVisible.value = true;
}
async function saveTransform() {
  const siteId = mineContext.selectedSiteId;
  if (
    !siteId ||
    !transformForm.sourceCoordinateSystemId ||
    !transformForm.targetCoordinateSystemId
  )
    return window.$message?.warning(t("mine.assets.validation.required"));
  let parameters: Record<string, unknown>;
  try {
    parameters = JSON.parse(transformForm.parameters) as Record<
      string,
      unknown
    >;
  } catch {
    return window.$message?.warning(
      t("mine.assets.coordinate.invalidParameters"),
    );
  }
  saving.value = true;
  const detail = {
    algorithm: transformForm.algorithm,
    parameters,
    validFrom: new Date(transformForm.validFrom).toISOString(),
    validTo: transformForm.validTo
      ? new Date(transformForm.validTo).toISOString()
      : null,
  };
  try {
    if (editingTransformId.value)
      await updateMineCoordinateTransform(siteId, editingTransformId.value, {
        ...detail,
        version: transformForm.version,
      });
    else
      await createMineCoordinateTransform(siteId, {
        sourceCoordinateSystemId: transformForm.sourceCoordinateSystemId,
        targetCoordinateSystemId: transformForm.targetCoordinateSystemId,
        versionNumber: transformForm.versionNumber,
        ...detail,
      });
    transformModalVisible.value = false;
    window.$message?.success(t("mine.assets.message.saved"));
    await loadCoordinates();
  } catch (error) {
    await handleWriteError(error, loadCoordinates);
  } finally {
    saving.value = false;
  }
}
function confirmPublish(value: CoordinateTransform) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId) return;
  window.$dialog?.warning({
    title: t("common.warning"),
    content: t("mine.assets.coordinate.confirmPublish"),
    positiveText: t("mine.assets.coordinate.publish"),
    negativeText: t("mine.common.cancel"),
    onPositiveClick: async () => {
      try {
        await publishMineCoordinateTransform(siteId, value.id, value.version);
        window.$message?.success(t("mine.assets.coordinate.published"));
        await loadCoordinates();
      } catch (error) {
        await handleWriteError(error, loadCoordinates);
      }
    },
  });
}
function confirmArchive(
  content: string,
  action: () => Promise<void>,
  refresh: () => Promise<void>,
) {
  window.$dialog?.warning({
    title: t("common.warning"),
    content,
    positiveText: t("mine.assets.action.archive"),
    negativeText: t("mine.common.cancel"),
    onPositiveClick: async () => {
      try {
        await action();
        window.$message?.success(t("mine.assets.message.archived"));
        await refresh();
      } catch (error) {
        await handleWriteError(error, refresh);
      }
    },
  });
}
async function handleWriteError(error: unknown, refresh: () => Promise<void>) {
  if (error instanceof ApiError && error.status === 409) {
    window.$message?.warning(t("mine.assets.message.optimisticConflict"));
    await refresh();
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
function tagStatusType(status: LocationTagStatus) {
  if (status === "ACTIVE") return "success" as const;
  if (status === "LOST") return "error" as const;
  if (status === "RETIRED") return "warning" as const;
  return "default" as const;
}
function systemName(id: string) {
  return systems.value.find((item) => item.id === id)?.name ?? id;
}
function emptyToNull(value: string) {
  return value.trim() || null;
}
function formatTime(value: string) {
  return new Date(value).toLocaleString(locale.value, { hour12: false });
}

defineExpose({ activeTab, activateCoordinates, loadTags, loadCoordinates });
</script>

<template>
  <NSpace vertical :size="16">
    <MineSiteSelector
      @change="changeSite"
      @refresh="activeTab === 'coordinates' ? loadCoordinates() : loadTags()"
    /><NCard :bordered="false" size="small">
      <NTabs :value="activeTab" type="line" animated @update:value="changeTab">
        <NTabPane name="tags" :tab="t('mine.assets.tag.tab')">
          <ListSearchPanel>
            <NFlex
              data-responsive-stack
              class="flex-col md:flex-row"
              align="center"
            >
              <NInput
                v-model:value="tagQuery.keyword"
                clearable
                :placeholder="t('mine.assets.tag.search')"
                class="w-full md:flex-1"
                @keyup.enter="loadTags()"
              /><NSelect
                v-model:value="tagQuery.status"
                clearable
                :options="tagStatusOptions"
                :placeholder="t('mine.common.allStatus')"
                class="w-full md:w-48"
              /><ListActionButton kind="search" @click="loadTags">
                {{
                  t("mine.common.search")
                }}
              </ListActionButton>
            </NFlex>
          </ListSearchPanel><NFlex class="my-4" justify="end">
            <ListActionButton
              v-if="can('mine:location-tag:edit') && mineContext.selectedSiteId"
              kind="create"
              data-action="create-tag"
              @click="openTagCreate"
            >
              {{ t("mine.assets.tag.create") }}
            </ListActionButton><ListActionButton
              kind="refresh"
              :loading="loading"
              @click="loadTags"
            >
              {{ t("common.refresh") }}
            </ListActionButton>
          </NFlex><NEmpty
            v-if="!loading && tags.length === 0"
            :description="t('mine.assets.tag.empty')"
          /><NDataTable
            v-else
            remote
            :columns="tagColumns"
            :data="tags"
            :loading="loading"
            :scroll-x="1050"
            :pagination="{
              page: tagQuery.page,
              pageSize: tagQuery.size,
              itemCount: tagTotal,
              showSizePicker: true,
              pageSizes: [10, 20, 50, 100],
            }"
            @update:page="changeTagPage"
            @update:page-size="changeTagPageSize"
          />
        </NTabPane><NTabPane
          v-if="can('mine:coordinate:view')"
          name="coordinates"
          :tab="t('mine.assets.coordinate.tab')"
        >
          <NAlert type="info" class="mb-4">
            {{
              t("mine.assets.coordinate.dualModelNotice")
            }}
          </NAlert><ListSearchPanel>
            <NFlex data-responsive-stack class="flex-col md:flex-row" align="center">
              <NInput v-model:value="systemQuery.keyword" clearable :placeholder="t('mine.assets.coordinate.systemSearch')" class="w-full md:flex-1" @keyup.enter="searchSystems" /><NSelect v-model:value="systemQuery.status" clearable :options="archiveStatusOptions" :placeholder="t('mine.common.allStatus')" class="w-full md:w-48" /><ListActionButton kind="search" @click="searchSystems">{{ t("mine.common.search") }}</ListActionButton>
            </NFlex>
          </ListSearchPanel><NFlex justify="space-between" align="center">
            <NText strong>{{ t("mine.assets.coordinate.systemList") }}</NText><NFlex>
              <ListActionButton
                v-if="can('mine:coordinate:edit') && mineContext.selectedSiteId"
                kind="create"
                @click="openSystemCreate"
              >
                {{
                  t("mine.assets.coordinate.createSystem")
                }}
              </ListActionButton><ListActionButton
                kind="refresh"
                :loading="loading"
                @click="loadCoordinates"
              >
                {{ t("common.refresh") }}
              </ListActionButton>
            </NFlex>
          </NFlex><NDataTable
            class="mt-3"
            remote
            :columns="systemColumns"
            :data="systems"
            :loading="loading"
            :scroll-x="1000"
            :pagination="{
              page: systemQuery.page,
              pageSize: systemQuery.size,
              itemCount: systemTotal,
              showSizePicker: true,
              pageSizes: [10, 20, 50, 100],
            }"
            @update:page="changeSystemPage"
            @update:page-size="changeSystemPageSize"
          /><NFlex
            class="mt-6"
            justify="space-between"
            align="center"
          >
            <NText strong>
              {{
                t("mine.assets.coordinate.transformList")
              }}
            </NText><ListActionButton
              v-if="can('mine:coordinate:edit') && mineContext.selectedSiteId"
              kind="create"
              @click="openTransformCreate"
            >
              {{
                t("mine.assets.coordinate.createTransform")
              }}
            </ListActionButton>
          </NFlex><NFlex class="mt-3" justify="end">
            <NSelect v-model:value="transformQuery.status" clearable :options="transformStatusOptions" :placeholder="t('mine.common.allStatus')" class="w-full md:w-48" @update:value="searchTransforms" />
          </NFlex><NEmpty
            v-if="!loading && transforms.length === 0"
            class="mt-3"
            :description="
              t('mine.assets.coordinate.transformEmpty')
            "
          /><NDataTable
            v-else
            class="mt-3"
            remote
            :columns="transformColumns"
            :data="transforms"
            :loading="loading"
            :scroll-x="900"
            :pagination="{
              page: transformQuery.page,
              pageSize: transformQuery.size,
              itemCount: transformTotal,
              showSizePicker: true,
              pageSizes: [10, 20, 50, 100],
            }"
            @update:page="changeTransformPage"
            @update:page-size="changeTransformPageSize"
          />
        </NTabPane>
      </NTabs>
    </NCard>
    <NModal
      v-model:show="tagModalVisible"
      preset="card"
      :title="
        t(
          editingTagId
            ? 'mine.assets.tag.editTitle'
            : 'mine.assets.tag.createTitle',
        )
      "
      class="max-w-92vw w-140"
    >
      <NForm label-placement="top">
        <NFormItem :label="t('mine.assets.tag.code')">
          <NInput
            v-model:value="tagForm.tagCode"
            :disabled="Boolean(editingTagId)"
          />
        </NFormItem><NFlex class="flex-col md:flex-row">
          <NFormItem :label="t('mine.assets.tag.vendor')" class="flex-1">
            <NInput v-model:value="tagForm.vendor" />
          </NFormItem><NFormItem :label="t('mine.assets.tag.model')" class="flex-1">
            <NInput v-model:value="tagForm.model" />
          </NFormItem>
        </NFlex><NFlex class="flex-col md:flex-row">
          <NFormItem :label="t('mine.assets.tag.battery')" class="flex-1">
            <NInputNumber
              v-model:value="tagForm.batteryPercent"
              :min="0"
              :max="100"
              class="w-full"
            />
          </NFormItem><NFormItem
            :label="t('mine.assets.tag.batteryObservedAt')"
            class="flex-1"
          >
            <NDatePicker
              v-model:value="tagForm.batteryObservedAt"
              type="datetime"
              clearable
              class="w-full"
            />
          </NFormItem>
        </NFlex>
      </NForm><template #footer>
        <NFlex justify="end">
          <NButton @click="tagModalVisible = false">
            {{
              t("mine.common.cancel")
            }}
          </NButton><NButton type="primary" :loading="saving" @click="saveTag">
            {{
              t("mine.common.save")
            }}
          </NButton>
        </NFlex>
      </template>
    </NModal>
    <NModal
      v-model:show="systemModalVisible"
      preset="card"
      :title="
        t(
          editingSystemId
            ? 'mine.assets.coordinate.editSystem'
            : 'mine.assets.coordinate.createSystem',
        )
      "
      class="max-w-92vw w-160"
    >
      <NForm label-placement="top">
        <NFlex class="flex-col md:flex-row">
          <NFormItem :label="t('mine.assets.coordinate.code')" class="flex-1">
            <NInput
              v-model:value="systemForm.systemCode"
              :disabled="Boolean(editingSystemId)"
            />
          </NFormItem><NFormItem :label="t('mine.assets.coordinate.name')" class="flex-1">
            <NInput v-model:value="systemForm.name" />
          </NFormItem>
        </NFlex><NFlex class="flex-col md:flex-row">
          <NFormItem
            :label="t('mine.assets.coordinate.typeLabel')"
            class="flex-1"
          >
            <NSelect
              v-model:value="systemForm.coordinateType"
              :options="coordinateTypeOptions"
            />
          </NFormItem><NFormItem
            :label="t('mine.assets.coordinate.unitLabel')"
            class="flex-1"
          >
            <NSelect
              v-model:value="systemForm.unit"
              :options="unitOptions"
            />
          </NFormItem><NFormItem :label="t('mine.assets.coordinate.epsg')" class="flex-1">
            <NInputNumber
              v-model:value="systemForm.epsgCode"
              class="w-full"
            />
          </NFormItem>
        </NFlex><NFormItem :label="t('mine.assets.coordinate.unified')">
          <NSwitch v-model:value="systemForm.unified">
            <template #checked>
              {{
                t("mine.assets.coordinate.unifiedYes")
              }}
            </template><template #unchecked>
              {{
                t("mine.assets.coordinate.unifiedNo")
              }}
            </template>
          </NSwitch>
        </NFormItem><NFlex class="flex-col md:flex-row">
          <NFormItem :label="t('mine.assets.coordinate.minX')" class="flex-1">
            <NInputNumber
              v-model:value="systemForm.minX"
              class="w-full"
            />
          </NFormItem><NFormItem :label="t('mine.assets.coordinate.maxX')" class="flex-1">
            <NInputNumber
              v-model:value="systemForm.maxX"
              class="w-full"
            />
          </NFormItem><NFormItem :label="t('mine.assets.coordinate.minY')" class="flex-1">
            <NInputNumber
              v-model:value="systemForm.minY"
              class="w-full"
            />
          </NFormItem><NFormItem :label="t('mine.assets.coordinate.maxY')" class="flex-1">
            <NInputNumber
              v-model:value="systemForm.maxY"
              class="w-full"
            />
          </NFormItem>
        </NFlex><NFlex class="flex-col md:flex-row">
          <NFormItem :label="t('mine.assets.coordinate.minZ')" class="flex-1">
            <NInputNumber
              v-model:value="systemForm.minZ"
              clearable
              class="w-full"
            />
          </NFormItem><NFormItem :label="t('mine.assets.coordinate.maxZ')" class="flex-1">
            <NInputNumber
              v-model:value="systemForm.maxZ"
              clearable
              class="w-full"
            />
          </NFormItem>
        </NFlex>
      </NForm><template #footer>
        <NFlex justify="end">
          <NButton @click="systemModalVisible = false">
            {{
              t("mine.common.cancel")
            }}
          </NButton><NButton type="primary" :loading="saving" @click="saveSystem">
            {{
              t("mine.common.save")
            }}
          </NButton>
        </NFlex>
      </template>
    </NModal>
    <NModal
      v-model:show="transformModalVisible"
      preset="card"
      :title="
        t(
          editingTransformId
            ? 'mine.assets.coordinate.editTransform'
            : 'mine.assets.coordinate.createTransform',
        )
      "
      class="max-w-92vw w-160"
    >
      <NForm label-placement="top">
        <NFlex class="flex-col md:flex-row">
          <NFormItem :label="t('mine.assets.coordinate.source')" class="flex-1">
            <NSelect
              v-model:value="transformForm.sourceCoordinateSystemId"
              :options="systemOptions"
            />
          </NFormItem><NFormItem :label="t('mine.assets.coordinate.target')" class="flex-1">
            <NSelect
              v-model:value="transformForm.targetCoordinateSystemId"
              :options="systemOptions"
            />
          </NFormItem>
        </NFlex><NFlex class="flex-col md:flex-row">
          <NFormItem
            :label="t('mine.assets.coordinate.algorithmLabel')"
            class="flex-1"
          >
            <NSelect
              v-model:value="transformForm.algorithm"
              :options="algorithmOptions"
            />
          </NFormItem><NFormItem
            :label="t('mine.assets.coordinate.version')"
            class="flex-1"
          >
            <NInputNumber
              v-model:value="transformForm.versionNumber"
              :min="1"
              :disabled="Boolean(editingTransformId)"
              class="w-full"
            />
          </NFormItem>
        </NFlex><NFormItem :label="t('mine.assets.coordinate.parameters')">
          <NInput
            v-model:value="transformForm.parameters"
            type="textarea"
            :autosize="{ minRows: 6, maxRows: 12 }"
          />
        </NFormItem><NFlex class="flex-col md:flex-row">
          <NFormItem
            :label="t('mine.assets.coordinate.validFrom')"
            class="flex-1"
          >
            <NDatePicker
              v-model:value="transformForm.validFrom"
              type="datetime"
              class="w-full"
            />
          </NFormItem><NFormItem
            :label="t('mine.assets.coordinate.validTo')"
            class="flex-1"
          >
            <NDatePicker
              v-model:value="transformForm.validTo"
              type="datetime"
              clearable
              class="w-full"
            />
          </NFormItem>
        </NFlex>
      </NForm><template #footer>
        <NFlex justify="end">
          <NButton @click="transformModalVisible = false">
            {{
              t("mine.common.cancel")
            }}
          </NButton><NButton type="primary" :loading="saving" @click="saveTransform">
            {{
              t("mine.common.save")
            }}
          </NButton>
        </NFlex>
      </template>
    </NModal>
  </NSpace>
</template>
