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
import {
  archiveMineOrganization,
  archiveMineSite,
  createMineOrganization,
  createMineSite,
  getMineOrganizations,
  getMineSites,
  updateMineOrganization,
  updateMineSite,
} from "../../api/mine-api";
import type { MineOrganization, MineSite } from "../../api/mine-api";
import { useMineContextStore } from "../../stores/mine-context";

const { t, locale } = useI18n();
const authStore = useAuthStore();
const mineContext = useMineContextStore();
const loadingOrganizations = ref(false);
const loadingSites = ref(false);
const saving = ref(false);
const organizations = ref<MineOrganization[]>([]);
const sites = ref<MineSite[]>([]);
const organizationTotal = ref(0);
const siteTotal = ref(0);
const selectedOrganizationId = ref<string | null>(null);
const organizationQuery = reactive({
  keyword: "",
  status: null as "ACTIVE" | "ARCHIVED" | null,
  page: 1,
  size: 20,
});
const siteQuery = reactive({
  keyword: "",
  status: null as "ACTIVE" | "ARCHIVED" | null,
  page: 1,
  size: 20,
});
const organizationModalVisible = ref(false);
const siteModalVisible = ref(false);
const editingOrganizationId = ref<string | null>(null);
const editingSiteId = ref<string | null>(null);
const organizationForm = reactive({
  organizationCode: "",
  name: "",
  version: 0,
});
const siteForm = reactive({
  siteCode: "",
  name: "",
  timezone: "Asia/Shanghai",
  description: "",
  version: 0,
});

const statusOptions = computed<SelectOption[]>(() => [
  { label: t("mine.assets.status.active"), value: "ACTIVE" },
  { label: t("mine.assets.status.archived"), value: "ARCHIVED" },
]);
const organizationOptions = computed<SelectOption[]>(() =>
  organizations.value.map((item) => ({
    label: `${item.name} · ${item.organizationCode}`,
    value: item.id,
  })),
);
const organizationColumns = computed<DataTableColumns<MineOrganization>>(() => [
  {
    title: t("mine.assets.organization.code"),
    key: "organizationCode",
    minWidth: 130,
  },
  { title: t("mine.assets.organization.name"), key: "name", minWidth: 160 },
  {
    title: t("mine.assets.common.status"),
    key: "status",
    width: 100,
    render: (row) => statusTag(row.status),
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
        can("mine:organization:edit") && row.status !== "ARCHIVED"
          ? h(
              NButton,
              {
                text: true,
                type: "primary",
                onClick: () => openOrganizationEdit(row),
              },
              () => t("mine.common.edit"),
            )
          : null,
        can("mine:organization:edit") && row.status !== "ARCHIVED"
          ? h(
              NButton,
              {
                text: true,
                type: "error",
                onClick: () => confirmArchiveOrganization(row),
              },
              () => t("mine.assets.action.archive"),
            )
          : null,
      ]),
  },
]);
const siteColumns = computed<DataTableColumns<MineSite>>(() => [
  { title: t("mine.assets.site.code"), key: "siteCode", minWidth: 130 },
  { title: t("mine.assets.site.name"), key: "name", minWidth: 150 },
  { title: t("mine.assets.site.timezone"), key: "timezone", minWidth: 150 },
  {
    title: t("mine.assets.common.status"),
    key: "status",
    width: 100,
    render: (row) => statusTag(row.status),
  },
  {
    title: t("mine.common.action"),
    key: "actions",
    width: 150,
    render: (row) =>
      h(NSpace, { size: 10 }, () => [
        can("mine:site:edit") && row.status !== "ARCHIVED"
          ? h(
              NButton,
              { text: true, type: "primary", onClick: () => openSiteEdit(row) },
              () => t("mine.common.edit"),
            )
          : null,
        can("mine:site:edit") && row.status !== "ARCHIVED"
          ? h(
              NButton,
              {
                text: true,
                type: "error",
                onClick: () => confirmArchiveSite(row),
              },
              () => t("mine.assets.action.archive"),
            )
          : null,
      ]),
  },
]);

onMounted(loadOrganizations);

async function loadOrganizations() {
  loadingOrganizations.value = true;
  try {
    const response = await getMineOrganizations(organizationQuery);
    organizations.value = response.items;
    organizationTotal.value = response.total;
    organizationQuery.page = response.page;
    organizationQuery.size = response.size;
    if (
      !organizations.value.some(
        (item) => item.id === selectedOrganizationId.value,
      )
    ) {
      selectedOrganizationId.value = organizations.value[0]?.id ?? null;
    }
    await loadSites();
  } catch (error) {
    showError(error);
  } finally {
    loadingOrganizations.value = false;
  }
}

async function loadSites() {
  if (!selectedOrganizationId.value) {
    sites.value = [];
    siteTotal.value = 0;
    return;
  }
  loadingSites.value = true;
  try {
    const response = await getMineSites(
      selectedOrganizationId.value,
      siteQuery,
    );
    sites.value = response.items;
    siteTotal.value = response.total;
    siteQuery.page = response.page;
    siteQuery.size = response.size;
  } catch (error) {
    showError(error);
  } finally {
    loadingSites.value = false;
  }
}

function selectOrganization(value: string) {
  selectedOrganizationId.value = value;
  siteQuery.page = 1;
  return loadSites();
}

function searchOrganizations() {
  organizationQuery.page = 1;
  return loadOrganizations();
}

function searchSites() {
  siteQuery.page = 1;
  return loadSites();
}

function changeOrganizationPage(page: number) {
  organizationQuery.page = page;
  return loadOrganizations();
}

function changeOrganizationPageSize(size: number) {
  organizationQuery.page = 1;
  organizationQuery.size = size;
  return loadOrganizations();
}

function changeSitePage(page: number) {
  siteQuery.page = page;
  return loadSites();
}

function changeSitePageSize(size: number) {
  siteQuery.page = 1;
  siteQuery.size = size;
  return loadSites();
}

function openOrganizationCreate() {
  editingOrganizationId.value = null;
  Object.assign(organizationForm, {
    organizationCode: "",
    name: "",
    version: 0,
  });
  organizationModalVisible.value = true;
}

function openOrganizationEdit(value: MineOrganization) {
  editingOrganizationId.value = value.id;
  Object.assign(organizationForm, {
    organizationCode: value.organizationCode,
    name: value.name,
    version: value.version,
  });
  organizationModalVisible.value = true;
}

async function saveOrganization() {
  if (
    !organizationForm.name.trim() ||
    (!editingOrganizationId.value && !organizationForm.organizationCode.trim())
  ) {
    return window.$message?.warning(t("mine.assets.validation.required"));
  }
  saving.value = true;
  try {
    if (editingOrganizationId.value) {
      await updateMineOrganization(editingOrganizationId.value, {
        name: organizationForm.name.trim(),
        version: organizationForm.version,
      });
    } else {
      await createMineOrganization({
        organizationCode: organizationForm.organizationCode.trim(),
        name: organizationForm.name.trim(),
      });
    }
    organizationModalVisible.value = false;
    window.$message?.success(t("mine.assets.message.saved"));
    await loadOrganizations();
    await mineContext.refreshContext();
  } catch (error) {
    await handleWriteError(error);
  } finally {
    saving.value = false;
  }
}

function openSiteCreate() {
  editingSiteId.value = null;
  Object.assign(siteForm, {
    siteCode: "",
    name: "",
    timezone: "Asia/Shanghai",
    description: "",
    version: 0,
  });
  siteModalVisible.value = true;
}

function openSiteEdit(value: MineSite) {
  editingSiteId.value = value.id;
  Object.assign(siteForm, {
    siteCode: value.siteCode,
    name: value.name,
    timezone: value.timezone,
    description: value.description ?? "",
    version: value.version,
  });
  siteModalVisible.value = true;
}

async function saveSite() {
  if (
    !selectedOrganizationId.value ||
    !siteForm.name.trim() ||
    !siteForm.timezone.trim() ||
    (!editingSiteId.value && !siteForm.siteCode.trim())
  ) {
    return window.$message?.warning(t("mine.assets.validation.required"));
  }
  saving.value = true;
  try {
    if (editingSiteId.value) {
      await updateMineSite(editingSiteId.value, {
        name: siteForm.name.trim(),
        timezone: siteForm.timezone.trim(),
        description: siteForm.description.trim() || null,
        version: siteForm.version,
      });
    } else {
      await createMineSite(selectedOrganizationId.value, {
        siteCode: siteForm.siteCode.trim(),
        name: siteForm.name.trim(),
        timezone: siteForm.timezone.trim(),
        description: siteForm.description.trim() || null,
      });
    }
    siteModalVisible.value = false;
    window.$message?.success(t("mine.assets.message.saved"));
    await loadSites();
    await mineContext.refreshContext();
  } catch (error) {
    await handleWriteError(error);
  } finally {
    saving.value = false;
  }
}

function confirmArchiveOrganization(value: MineOrganization) {
  window.$dialog?.warning({
    title: t("common.warning"),
    content: t("mine.assets.organization.confirmArchive", { name: value.name }),
    positiveText: t("mine.assets.action.archive"),
    negativeText: t("mine.common.cancel"),
    onPositiveClick: async () => {
      try {
        await archiveMineOrganization(value.id, value.version);
        window.$message?.success(t("mine.assets.message.archived"));
        await loadOrganizations();
        await mineContext.refreshContext();
      } catch (error) {
        await handleWriteError(error);
      }
    },
  });
}

function confirmArchiveSite(value: MineSite) {
  window.$dialog?.warning({
    title: t("common.warning"),
    content: t("mine.assets.site.confirmArchive", { name: value.name }),
    positiveText: t("mine.assets.action.archive"),
    negativeText: t("mine.common.cancel"),
    onPositiveClick: async () => {
      try {
        await archiveMineSite(value.id, value.version);
        window.$message?.success(t("mine.assets.message.archived"));
        await loadSites();
        await mineContext.refreshContext();
      } catch (error) {
        await handleWriteError(error);
      }
    },
  });
}

async function handleWriteError(error: unknown) {
  if (error instanceof ApiError && error.status === 409) {
    window.$message?.warning(t("mine.assets.message.optimisticConflict"));
    await Promise.all([loadOrganizations(), loadSites()]);
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
function statusTag(status: string) {
  return h(
    NTag,
    {
      size: "small",
      bordered: false,
      type: status === "ACTIVE" ? "success" : "default",
    },
    () =>
      t(
        status === "ACTIVE"
          ? "mine.assets.status.active"
          : "mine.assets.status.archived",
      ),
  );
}
function formatTime(value: string) {
  return new Date(value).toLocaleString(locale.value, { hour12: false });
}

defineExpose({
  organizationForm,
  siteForm,
  loadOrganizations,
  loadSites,
  changeOrganizationPage,
  changeOrganizationPageSize,
  changeSitePage,
  changeSitePageSize,
  openOrganizationCreate,
  openOrganizationEdit,
  saveOrganization,
  openSiteCreate,
  openSiteEdit,
  saveSite,
  confirmArchiveSite,
});
</script>

<template>
  <NSpace vertical :size="16">
    <ListSearchPanel>
      <NFlex data-responsive-stack class="flex-col md:flex-row" align="center">
        <NInput
          v-model:value="organizationQuery.keyword"
          :placeholder="t('mine.assets.organization.search')"
          clearable
          class="w-full md:flex-1"
          @keyup.enter="searchOrganizations"
        />
        <NSelect
          v-model:value="organizationQuery.status"
          :options="statusOptions"
          :placeholder="t('mine.common.allStatus')"
          clearable
          class="w-full md:w-48"
        />
        <ListActionButton kind="search" @click="searchOrganizations">
          {{
            t("mine.common.search")
          }}
        </ListActionButton>
      </NFlex>
    </ListSearchPanel>

    <div data-responsive-stack class="grid grid-cols-1 gap-4 xl:grid-cols-2">
      <NCard
        data-list-card
        :title="t('mine.assets.organization.listTitle')"
        :bordered="false"
        size="small"
      >
        <template #header-extra>
          <NFlex>
            <ListActionButton
              v-if="can('mine:organization:edit')"
              kind="create"
              data-action="create-organization"
              @click="openOrganizationCreate"
            >
              {{ t("mine.assets.organization.create") }}
            </ListActionButton>
            <ListActionButton
              kind="refresh"
              :loading="loadingOrganizations"
              @click="loadOrganizations"
            >
              {{ t("common.refresh") }}
            </ListActionButton>
          </NFlex>
        </template>
        <NEmpty
          v-if="!loadingOrganizations && organizations.length === 0"
          :description="t('mine.assets.organization.empty')"
        >
          <template #extra>
            <NText depth="3">
              {{
                t("mine.assets.organization.emptyGuide")
              }}
            </NText>
          </template>
        </NEmpty>
        <NDataTable
          v-else
          remote
          :columns="organizationColumns"
          :data="organizations"
          :loading="loadingOrganizations"
          :row-key="(row) => row.id"
          :pagination="{
            page: organizationQuery.page,
            pageSize: organizationQuery.size,
            itemCount: organizationTotal,
            showSizePicker: true,
            pageSizes: [10, 20, 50, 100],
          }"
          @update:page="changeOrganizationPage"
          @update:page-size="changeOrganizationPageSize"
        />
      </NCard>

      <NCard
        data-list-card
        :title="t('mine.assets.site.listTitle')"
        :bordered="false"
        size="small"
      >
        <template #header-extra>
          <NFlex>
            <ListActionButton
              v-if="can('mine:site:edit') && selectedOrganizationId"
              kind="create"
              data-action="create-site"
              @click="openSiteCreate"
            >
              {{ t("mine.assets.site.create") }}
            </ListActionButton>
            <ListActionButton
              kind="refresh"
              :loading="loadingSites"
              @click="loadSites"
            >
              {{ t("common.refresh") }}
            </ListActionButton>
          </NFlex>
        </template>
        <NFlex class="mb-4 flex-col md:flex-row" align="center">
          <NSelect
            :value="selectedOrganizationId"
            :options="organizationOptions"
            :placeholder="t('mine.assets.site.selectOrganization')"
            class="w-full md:w-72"
            @update:value="selectOrganization"
          />
          <NInput
            v-model:value="siteQuery.keyword"
            :placeholder="t('mine.assets.site.search')"
            clearable
            class="w-full md:flex-1"
            @keyup.enter="searchSites"
          />
        </NFlex>
        <NEmpty
          v-if="!loadingSites && sites.length === 0"
          :description="t('mine.assets.site.empty')"
        />
        <NDataTable
          v-else
          remote
          :columns="siteColumns"
          :data="sites"
          :loading="loadingSites"
          :row-key="(row) => row.id"
          :pagination="{
            page: siteQuery.page,
            pageSize: siteQuery.size,
            itemCount: siteTotal,
            showSizePicker: true,
            pageSizes: [10, 20, 50, 100],
          }"
          @update:page="changeSitePage"
          @update:page-size="changeSitePageSize"
        />
      </NCard>
    </div>

    <NModal
      v-model:show="organizationModalVisible"
      preset="card"
      :title="
        t(
          editingOrganizationId
            ? 'mine.assets.organization.editTitle'
            : 'mine.assets.organization.createTitle',
        )
      "
      class="max-w-92vw w-120"
    >
      <NForm label-placement="top">
        <NFormItem :label="t('mine.assets.organization.code')">
          <NInput
            v-model:value="organizationForm.organizationCode"
            :disabled="Boolean(editingOrganizationId)"
          />
        </NFormItem>
        <NFormItem :label="t('mine.assets.organization.name')">
          <NInput v-model:value="organizationForm.name" />
        </NFormItem>
        <NFormItem
          v-if="editingOrganizationId"
          :label="t('mine.assets.common.version')"
        >
          <NInputNumber
            v-model:value="organizationForm.version"
            disabled
            class="w-full"
          />
        </NFormItem>
      </NForm>
      <template #footer>
        <NFlex justify="end">
          <NButton @click="organizationModalVisible = false">
            {{
              t("mine.common.cancel")
            }}
          </NButton><NButton
            type="primary"
            :loading="saving"
            @click="saveOrganization"
          >
            {{ t("mine.common.save") }}
          </NButton>
        </NFlex>
      </template>
    </NModal>

    <NModal
      v-model:show="siteModalVisible"
      preset="card"
      :title="
        t(
          editingSiteId
            ? 'mine.assets.site.editTitle'
            : 'mine.assets.site.createTitle',
        )
      "
      class="max-w-92vw w-140"
    >
      <NForm label-placement="top">
        <NFormItem :label="t('mine.assets.site.code')">
          <NInput
            v-model:value="siteForm.siteCode"
            :disabled="Boolean(editingSiteId)"
          />
        </NFormItem>
        <NFormItem :label="t('mine.assets.site.name')">
          <NInput v-model:value="siteForm.name" />
        </NFormItem>
        <NFormItem :label="t('mine.assets.site.timezone')">
          <NInput v-model:value="siteForm.timezone" />
        </NFormItem>
        <NFormItem :label="t('mine.assets.site.description')">
          <NInput
            v-model:value="siteForm.description"
            type="textarea"
            :maxlength="500"
            show-count
          />
        </NFormItem>
      </NForm>
      <template #footer>
        <NFlex justify="end">
          <NButton @click="siteModalVisible = false">
            {{
              t("mine.common.cancel")
            }}
          </NButton><NButton type="primary" :loading="saving" @click="saveSite">
            {{
              t("mine.common.save")
            }}
          </NButton>
        </NFlex>
      </template>
    </NModal>
  </NSpace>
</template>
