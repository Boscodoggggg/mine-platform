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
  NDescriptions,
  NDescriptionsItem,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NFlex,
  NForm,
  NFormItem,
  NInput,
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
  archiveMinePerson,
  createMineDriverQualification,
  createMinePerson,
  getMineDriverQualifications,
  getMinePeople,
  getMinePersonSensitive,
  revokeMineDriverQualification,
  updateMinePerson,
  updateMinePersonSensitive,
} from "../../api/mine-api";
import type {
  DriverQualification,
  DriverQualificationRequest,
  Person,
  PersonSensitive,
} from "../../api/mine-api";
import { useMineContextStore } from "../../stores/mine-context";

const { t } = useI18n();
const authStore = useAuthStore();
const mineContext = useMineContextStore();
const loading = ref(false);
const saving = ref(false);
const people = ref<Person[]>([]);
const total = ref(0);
const query = reactive({
  keyword: "",
  status: null as "ACTIVE" | "INACTIVE" | "ARCHIVED" | null,
  page: 1,
  size: 20,
});
const personModalVisible = ref(false);
const editingPersonId = ref<string | null>(null);
const personForm = reactive({
  personCode: "",
  name: "",
  personType: "DRIVER" as Person["personType"],
  phone: "",
  certificateNumber: "",
  assignmentFrom: Date.now(),
  assignmentTo: null as number | null,
  status: "ACTIVE" as "ACTIVE" | "INACTIVE",
  version: 0,
});
const sensitiveVisible = ref(false);
const sensitivePerson = ref<Person | null>(null);
const sensitive = ref<PersonSensitive | null>(null);
const sensitiveForm = reactive({
  phone: "",
  certificateNumber: "",
  version: 0,
});
const qualificationVisible = ref(false);
const qualificationPerson = ref<Person | null>(null);
const qualifications = ref<DriverQualification[]>([]);
const qualificationModalVisible = ref(false);
const qualificationForm = reactive({
  qualificationCode: "",
  name: "",
  allowedVehicleTypes: [] as DriverQualificationRequest["allowedVehicleTypes"],
  validFrom: Date.now(),
  validTo: Date.now() + 365 * 86400000,
});

const statusOptions = computed<SelectOption[]>(() =>
  ["ACTIVE", "INACTIVE", "ARCHIVED"].map((value) => ({
    label: t(`mine.assets.person.status.${value.toLowerCase()}`),
    value,
  })),
);
const editableStatusOptions = computed(() =>
  statusOptions.value.filter((item) => item.value !== "ARCHIVED"),
);
const personTypeOptions = computed<SelectOption[]>(() =>
  ["DRIVER", "OPERATOR", "SUPERVISOR", "WORKER", "VISITOR", "OTHER"].map(
    (value) => ({
      label: t(`mine.assets.person.type.${value.toLowerCase()}`),
      value,
    }),
  ),
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
    label: t(
      `mine.assets.vehicle.type.${value.toLowerCase().replaceAll("_", "")}`,
    ),
    value,
  })),
);
const columns = computed<DataTableColumns<Person>>(() => [
  { title: t("mine.assets.person.code"), key: "personCode", minWidth: 120 },
  { title: t("mine.assets.person.name"), key: "name", minWidth: 120 },
  {
    title: t("mine.assets.person.typeLabel"),
    key: "personType",
    width: 110,
    render: (row) =>
      t(`mine.assets.person.type.${row.personType.toLowerCase()}`),
  },
  { title: t("mine.assets.person.phone"), key: "phoneMasked", minWidth: 140 },
  {
    title: t("mine.assets.person.certificate"),
    key: "certificateMasked",
    minWidth: 170,
  },
  {
    title: t("mine.assets.common.status"),
    key: "status",
    width: 100,
    render: (row) =>
      h(
        NTag,
        { size: "small", bordered: false, type: personStatusType(row.status) },
        () => t(`mine.assets.person.status.${row.status.toLowerCase()}`),
      ),
  },
  {
    title: t("mine.common.action"),
    key: "actions",
    width: 260,
    render: (row) =>
      h(NSpace, { size: 8 }, () => [
        can("mine:person:edit") && row.status !== "ARCHIVED"
          ? h(
              NButton,
              { text: true, type: "primary", onClick: () => openEdit(row) },
              () => t("mine.common.edit"),
            )
          : null,
        can("mine:person:sensitive:view")
          ? h(
              NButton,
              {
                text: true,
                "data-action": "view-sensitive",
                onClick: () => openSensitive(row),
              },
              () => t("mine.assets.person.sensitiveAction"),
            )
          : null,
        h(NButton, { text: true, onClick: () => openQualifications(row) }, () =>
          t("mine.assets.person.qualificationAction"),
        ),
        can("mine:person:edit") && row.status !== "ARCHIVED"
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
    await loadPeople();
  } catch (error) {
    showError(error);
  }
}
async function loadPeople(siteId = mineContext.selectedSiteId) {
  if (!siteId) {
    people.value = [];
    total.value = 0;
    return;
  }
  loading.value = true;
  try {
    const response = await getMinePeople(siteId, query);
    people.value = response.items;
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
  closeSensitive();
  qualificationVisible.value = false;
  await loadPeople(siteId);
}
function search() {
  query.page = 1;
  return loadPeople();
}
function resetQuery() {
  Object.assign(query, { keyword: "", status: null, page: 1 });
  return loadPeople();
}
function openCreate() {
  editingPersonId.value = null;
  Object.assign(personForm, {
    personCode: "",
    name: "",
    personType: "DRIVER",
    phone: "",
    certificateNumber: "",
    assignmentFrom: Date.now(),
    assignmentTo: null,
    status: "ACTIVE",
    version: 0,
  });
  personModalVisible.value = true;
}
function openEdit(value: Person) {
  editingPersonId.value = value.id;
  Object.assign(personForm, {
    personCode: value.personCode,
    name: value.name,
    personType: value.personType,
    phone: "",
    certificateNumber: "",
    assignmentFrom: Date.parse(value.assignmentFrom),
    assignmentTo: value.assignmentTo ? Date.parse(value.assignmentTo) : null,
    status: value.status === "INACTIVE" ? "INACTIVE" : "ACTIVE",
    version: value.version,
  });
  personModalVisible.value = true;
}
async function savePerson() {
  const siteId = mineContext.selectedSiteId;
  if (
    !siteId ||
    !personForm.name.trim() ||
    (!editingPersonId.value &&
      (!personForm.personCode.trim() ||
        !personForm.phone.trim() ||
        !personForm.certificateNumber.trim()))
  )
    return window.$message?.warning(t("mine.assets.validation.required"));
  saving.value = true;
  try {
    if (editingPersonId.value)
      await updateMinePerson(siteId, editingPersonId.value, {
        name: personForm.name.trim(),
        personType: personForm.personType,
        status: personForm.status,
        version: personForm.version,
      });
    else
      await createMinePerson(siteId, {
        personCode: personForm.personCode.trim(),
        name: personForm.name.trim(),
        personType: personForm.personType,
        phone: personForm.phone.trim(),
        certificateNumber: personForm.certificateNumber.trim(),
        assignmentFrom: dateOnly(personForm.assignmentFrom),
        assignmentTo: personForm.assignmentTo
          ? dateOnly(personForm.assignmentTo)
          : null,
      });
    clearPersonSensitiveInput();
    personModalVisible.value = false;
    window.$message?.success(t("mine.assets.message.saved"));
    await loadPeople();
  } catch (error) {
    await handleWriteError(error);
  } finally {
    saving.value = false;
  }
}
function confirmArchive(value: Person) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId) return;
  window.$dialog?.warning({
    title: t("common.warning"),
    content: t("mine.assets.person.confirmArchive", { name: value.name }),
    positiveText: t("mine.assets.action.archive"),
    negativeText: t("mine.common.cancel"),
    onPositiveClick: async () => {
      try {
        await archiveMinePerson(siteId, value.id, value.version);
        window.$message?.success(t("mine.assets.message.archived"));
        await loadPeople();
      } catch (error) {
        await handleWriteError(error);
      }
    },
  });
}
async function openSensitive(value: Person) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId || !can("mine:person:sensitive:view")) return;
  sensitivePerson.value = value;
  sensitive.value = null;
  sensitiveVisible.value = true;
  try {
    sensitive.value = await getMinePersonSensitive(siteId, value.id);
    Object.assign(sensitiveForm, {
      phone: sensitive.value.phone,
      certificateNumber: sensitive.value.certificateNumber,
      version: value.version,
    });
  } catch (error) {
    showError(error);
    closeSensitive();
  }
}
async function saveSensitive() {
  const siteId = mineContext.selectedSiteId;
  if (!siteId || !sensitivePerson.value || !can("mine:person:sensitive:edit"))
    return;
  saving.value = true;
  try {
    await updateMinePersonSensitive(siteId, sensitivePerson.value.id, {
      phone: sensitiveForm.phone,
      certificateNumber: sensitiveForm.certificateNumber,
      version: sensitiveForm.version,
    });
    window.$message?.success(t("mine.assets.message.saved"));
    closeSensitive();
    await loadPeople();
  } catch (error) {
    await handleWriteError(error);
  } finally {
    saving.value = false;
  }
}
function closeSensitive() {
  sensitiveVisible.value = false;
  sensitive.value = null;
  sensitivePerson.value = null;
  Object.assign(sensitiveForm, {
    phone: "",
    certificateNumber: "",
    version: 0,
  });
}
async function openQualifications(value: Person) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId) return;
  qualificationPerson.value = value;
  qualificationVisible.value = true;
  try {
    qualifications.value = await getMineDriverQualifications(siteId, value.id);
  } catch (error) {
    showError(error);
  }
}
function openQualificationCreate() {
  Object.assign(qualificationForm, {
    qualificationCode: "",
    name: "",
    allowedVehicleTypes: [],
    validFrom: Date.now(),
    validTo: Date.now() + 365 * 86400000,
  });
  qualificationModalVisible.value = true;
}
async function saveQualification() {
  const siteId = mineContext.selectedSiteId;
  if (
    !siteId ||
    !qualificationPerson.value ||
    !qualificationForm.qualificationCode.trim() ||
    !qualificationForm.name.trim() ||
    qualificationForm.allowedVehicleTypes.length === 0
  )
    return window.$message?.warning(t("mine.assets.validation.required"));
  saving.value = true;
  try {
    await createMineDriverQualification(siteId, qualificationPerson.value.id, {
      qualificationCode: qualificationForm.qualificationCode.trim(),
      name: qualificationForm.name.trim(),
      allowedVehicleTypes: qualificationForm.allowedVehicleTypes,
      validFrom: dateOnly(qualificationForm.validFrom),
      validTo: dateOnly(qualificationForm.validTo),
    });
    qualificationModalVisible.value = false;
    window.$message?.success(t("mine.assets.message.saved"));
    await openQualifications(qualificationPerson.value);
  } catch (error) {
    await handleWriteError(error);
  } finally {
    saving.value = false;
  }
}
function confirmRevokeQualification(value: DriverQualification) {
  const siteId = mineContext.selectedSiteId;
  const person = qualificationPerson.value;
  if (!siteId || !person) return;
  window.$dialog?.warning({
    title: t("common.warning"),
    content: t("mine.assets.person.qualificationConfirmRevoke", {
      name: value.name,
    }),
    positiveText: t("mine.assets.person.revokeQualification"),
    negativeText: t("mine.common.cancel"),
    onPositiveClick: async () => {
      try {
        await revokeMineDriverQualification(
          siteId,
          person.id,
          value.id,
          value.version,
        );
        await openQualifications(person);
      } catch (error) {
        await handleWriteError(error);
      }
    },
  });
}
async function handleWriteError(error: unknown) {
  if (error instanceof ApiError && error.status === 409) {
    window.$message?.warning(t("mine.assets.message.optimisticConflict"));
    await loadPeople();
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
function personStatusType(status: Person["status"]) {
  if (status === "ACTIVE") return "success" as const;
  if (status === "INACTIVE") return "warning" as const;
  return "default" as const;
}
function dateOnly(value: number) {
  return new Date(value).toISOString().slice(0, 10);
}
function clearPersonSensitiveInput() {
  personForm.phone = "";
  personForm.certificateNumber = "";
}

defineExpose({
  personForm,
  openCreate,
  openEdit,
  savePerson,
  openSensitive,
  openQualifications,
});
</script>

<template>
  <NSpace vertical :size="16">
    <MineSiteSelector @change="changeSite" @refresh="loadPeople" />
    <ListSearchPanel>
      <NFlex data-responsive-stack class="flex-col md:flex-row" align="center">
        <NInput
          v-model:value="query.keyword"
          clearable
          :placeholder="t('mine.assets.person.search')"
          class="w-full md:flex-1"
          @keyup.enter="search"
        /><NSelect
          v-model:value="query.status"
          clearable
          :options="statusOptions"
          :placeholder="t('mine.common.allStatus')"
          class="w-full md:w-52"
        /><NFlex>
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
      :title="t('mine.assets.person.listTitle')"
      :bordered="false"
      size="small"
    >
      <template #header-extra>
        <NFlex>
          <ListActionButton
            v-if="can('mine:person:edit') && mineContext.selectedSiteId"
            kind="create"
            data-action="create-person"
            @click="openCreate"
          >
            {{ t("mine.assets.person.create") }}
          </ListActionButton><ListActionButton
            kind="refresh"
            :loading="loading"
            @click="loadPeople()"
          >
            {{ t("common.refresh") }}
          </ListActionButton>
        </NFlex>
      </template><NEmpty
        v-if="!mineContext.selectedSiteId"
        :description="t('mine.assets.context.empty')"
      /><NEmpty
        v-else-if="!loading && people.length === 0"
        :description="t('mine.assets.person.empty')"
      >
        <template #extra>
          <NText depth="3">
            {{
              t("mine.assets.person.emptyGuide")
            }}
          </NText>
        </template>
      </NEmpty><NDataTable
        v-else
        remote
        :columns="columns"
        :data="people"
        :loading="loading"
        :row-key="(row) => row.id"
        :scroll-x="1160"
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
            loadPeople();
          }
        "
        @update:page-size="
          (size) => {
            query.page = 1;
            query.size = size;
            loadPeople();
          }
        "
      />
    </NCard>

    <NModal
      v-model:show="personModalVisible"
      preset="card"
      :title="
        t(
          editingPersonId
            ? 'mine.assets.person.editTitle'
            : 'mine.assets.person.createTitle',
        )
      "
      class="max-w-92vw w-160"
    >
      <NForm label-placement="top">
        <NFlex class="flex-col md:flex-row">
          <NFormItem :label="t('mine.assets.person.code')" class="flex-1">
            <NInput
              v-model:value="personForm.personCode"
              :disabled="Boolean(editingPersonId)"
            />
          </NFormItem><NFormItem :label="t('mine.assets.person.name')" class="flex-1">
            <NInput v-model:value="personForm.name" />
          </NFormItem>
        </NFlex><NFlex class="flex-col md:flex-row">
          <NFormItem :label="t('mine.assets.person.typeLabel')" class="flex-1">
            <NSelect
              v-model:value="personForm.personType"
              :options="personTypeOptions"
            />
          </NFormItem><NFormItem
            v-if="editingPersonId"
            :label="t('mine.assets.common.status')"
            class="flex-1"
          >
            <NSelect
              v-model:value="personForm.status"
              :options="editableStatusOptions"
            />
          </NFormItem>
        </NFlex><template v-if="!editingPersonId">
          <NAlert type="warning" class="mb-4">
            {{
              t("mine.assets.person.sensitiveCreateNotice")
            }}
          </NAlert><NFlex class="flex-col md:flex-row">
            <NFormItem :label="t('mine.assets.person.phone')" class="flex-1">
              <NInput v-model:value="personForm.phone" />
            </NFormItem><NFormItem
              :label="t('mine.assets.person.certificate')"
              class="flex-1"
            >
              <NInput
                v-model:value="
                  personForm.certificateNumber
                "
              />
            </NFormItem>
          </NFlex><NFlex class="flex-col md:flex-row">
            <NFormItem
              :label="t('mine.assets.person.assignmentFrom')"
              class="flex-1"
            >
              <NDatePicker
                v-model:value="personForm.assignmentFrom"
                type="date"
                class="w-full"
              />
            </NFormItem><NFormItem
              :label="t('mine.assets.person.assignmentTo')"
              class="flex-1"
            >
              <NDatePicker
                v-model:value="personForm.assignmentTo"
                type="date"
                clearable
                class="w-full"
              />
            </NFormItem>
          </NFlex>
        </template>
      </NForm><template #footer>
        <NFlex justify="end">
          <NButton
            @click="
              personModalVisible = false;
              clearPersonSensitiveInput();
            "
          >
            {{ t("mine.common.cancel") }}
          </NButton><NButton type="primary" :loading="saving" @click="savePerson">
            {{
              t("mine.common.save")
            }}
          </NButton>
        </NFlex>
      </template>
    </NModal>

    <NDrawer
      v-model:show="sensitiveVisible"
      width="min(480px, 92vw)"
      placement="right"
      @after-leave="closeSensitive"
    >
      <NDrawerContent :title="t('mine.assets.person.sensitiveTitle')" closable>
        <NAlert type="warning" class="mb-4">
          {{
            t("mine.assets.person.sensitiveNotice")
          }}
        </NAlert><NDescriptions
          v-if="sensitive && !can('mine:person:sensitive:edit')"
          bordered
          :column="1"
        >
          <NDescriptionsItem :label="t('mine.assets.person.phone')">
            {{
              sensitive.phone
            }}
          </NDescriptionsItem><NDescriptionsItem :label="t('mine.assets.person.certificate')">
            {{
              sensitive.certificateNumber
            }}
          </NDescriptionsItem>
        </NDescriptions><NForm v-else-if="sensitive" label-placement="top">
          <NFormItem :label="t('mine.assets.person.phone')">
            <NInput v-model:value="sensitiveForm.phone" />
          </NFormItem><NFormItem :label="t('mine.assets.person.certificate')">
            <NInput
              v-model:value="sensitiveForm.certificateNumber"
            />
          </NFormItem><NButton type="primary" :loading="saving" @click="saveSensitive">
            {{
              t("mine.common.save")
            }}
          </NButton>
        </NForm>
      </NDrawerContent>
    </NDrawer>

    <NDrawer v-model:show="qualificationVisible" width="min(560px, 92vw)" placement="right">
      <NDrawerContent
        :title="t('mine.assets.person.qualificationTitle')"
        closable
      >
        <NFlex
          v-if="can('mine:person:qualification:edit')"
          class="mb-4"
          justify="end"
        >
          <ListActionButton kind="create" @click="openQualificationCreate">
            {{
              t("mine.assets.person.addQualification")
            }}
          </ListActionButton>
        </NFlex><NEmpty
          v-if="qualifications.length === 0"
          :description="t('mine.assets.person.qualificationEmpty')"
        /><NSpace v-else vertical>
          <NCard v-for="item in qualifications" :key="item.id" size="small">
            <NFlex justify="space-between">
              <div>
                <NText strong>
                  {{ item.name }} · {{ item.qualificationCode }}
                </NText>
                <div>
                  <NText depth="3">
                    {{ item.validFrom }} — {{ item.validTo }}
                  </NText>
                </div>
              </div>
              <NTag :type="item.status === 'ACTIVE' ? 'success' : 'warning'">
                {{
                  t(
                    `mine.assets.person.qualificationStatus.${item.status.toLowerCase()}`,
                  )
                }}
              </NTag>
            </NFlex><NAlert
              v-if="item.status === 'EXPIRED'"
              type="warning"
              class="mt-3"
            >
              {{ t("mine.assets.person.qualificationExpired") }}
            </NAlert><NButton
              v-if="
                can('mine:person:qualification:edit') &&
                  item.status === 'ACTIVE'
              "
              text
              type="error"
              class="mt-3"
              @click="confirmRevokeQualification(item)"
            >
              {{ t("mine.assets.person.revokeQualification") }}
            </NButton>
          </NCard>
        </NSpace>
      </NDrawerContent>
    </NDrawer>

    <NModal
      v-model:show="qualificationModalVisible"
      preset="card"
      :title="t('mine.assets.person.addQualification')"
      class="max-w-92vw w-140"
    >
      <NForm label-placement="top">
        <NFormItem :label="t('mine.assets.person.qualificationCode')">
          <NInput
            v-model:value="qualificationForm.qualificationCode"
          />
        </NFormItem><NFormItem :label="t('mine.assets.person.qualificationName')">
          <NInput v-model:value="qualificationForm.name" />
        </NFormItem><NFormItem :label="t('mine.assets.person.allowedVehicleTypes')">
          <NSelect
            v-model:value="qualificationForm.allowedVehicleTypes"
            multiple
            :options="vehicleTypeOptions"
          />
        </NFormItem><NFlex class="flex-col md:flex-row">
          <NFormItem
            :label="t('mine.assets.person.qualificationValidFrom')"
            class="flex-1"
          >
            <NDatePicker
              v-model:value="qualificationForm.validFrom"
              type="date"
              class="w-full"
            />
          </NFormItem><NFormItem
            :label="t('mine.assets.person.qualificationValidTo')"
            class="flex-1"
          >
            <NDatePicker
              v-model:value="qualificationForm.validTo"
              type="date"
              class="w-full"
            />
          </NFormItem>
        </NFlex>
      </NForm><template #footer>
        <NFlex justify="end">
          <NButton @click="qualificationModalVisible = false">
            {{
              t("mine.common.cancel")
            }}
          </NButton><NButton
            type="primary"
            :loading="saving"
            @click="saveQualification"
          >
            {{ t("mine.common.save") }}
          </NButton>
        </NFlex>
      </template>
    </NModal>
  </NSpace>
</template>
