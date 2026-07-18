<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useI18n } from "vue-i18n";
import type { SelectOption } from "naive-ui";
import {
  NAlert,
  NButton,
  NCard,
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
  NSelect,
  NSpace,
  NStep,
  NSteps,
  NTag,
  NText,
} from "naive-ui";
import { ApiError } from "@/api/client";
import { hasPermission } from "@/app/permission/permission";
import ListActionButton from "@/components/common/list-action-button.vue";
import { getDevices } from "@/modules/device/api/device";
import type { Device } from "@/modules/device/api/device";
import { useAuthStore } from "@/store/modules/auth";
import MineSiteSelector from "../../components/MineSiteSelector.vue";
import {
  getMineLocationTags,
  getMinePeople,
  getMineVehicles,
  previewMineBinding,
  switchMineBinding,
} from "../../api/mine-api";
import type {
  BindingPreview,
  BindingSwitchRequest,
  BindingType,
  LocationTag,
  Person,
  Vehicle,
} from "../../api/mine-api";
import { useMineContextStore } from "../../stores/mine-context";

const { t, locale } = useI18n();
const authStore = useAuthStore();
const mineContext = useMineContextStore();
const loading = ref(false);
const switching = ref(false);
const drawerVisible = ref(false);
const step = ref(1);
const vehicles = ref<Vehicle[]>([]);
const people = ref<Person[]>([]);
const tags = ref<LocationTag[]>([]);
const devices = ref<Device[]>([]);
const preview = ref<BindingPreview | null>(null);
const idempotencyKey = ref<string | null>(null);
const form = reactive({
  bindingType: "VEHICLE_TAG" as BindingType,
  resourceId: "",
  targetId: "",
  role: "",
  effectiveAt: new Date().toISOString(),
  reason: "",
});
const effectiveAtValue = computed({
  get: () => Date.parse(form.effectiveAt),
  set: (value) => {
    if (value) form.effectiveAt = new Date(value).toISOString();
  },
});
const bindingTypeOptions = computed<SelectOption[]>(() =>
  ["VEHICLE_DEVICE", "VEHICLE_TAG", "PERSON_TAG", "PERSON_VEHICLE"].map(
    (value) => ({
      label: t(
        `mine.assets.binding.type.${value.toLowerCase().replaceAll("_", "")}`,
      ),
      value,
    }),
  ),
);
const resourceOptions = computed<SelectOption[]>(() =>
  form.bindingType.startsWith("VEHICLE")
    ? vehicles.value.map((value) => ({
        label: `${value.name} · ${value.vehicleCode}`,
        value: value.id,
      }))
    : people.value.map((value) => ({
        label: `${value.name} · ${value.personCode}`,
        value: value.id,
      })),
);
const targetOptions = computed<SelectOption[]>(() => {
  if (form.bindingType === "VEHICLE_DEVICE")
    return devices.value.map((value) => ({
      label: `${value.name} · ${value.deviceCode}`,
      value: value.id,
    }));
  if (form.bindingType.endsWith("_TAG"))
    return tags.value.map((value) => ({
      label: value.tagCode,
      value: value.id,
    }));
  return vehicles.value.map((value) => ({
    label: `${value.name} · ${value.vehicleCode}`,
    value: value.id,
  }));
});
const qualificationType = computed(() =>
  preview.value?.qualification.qualified ? "success" : "error",
);

onMounted(initialize);
async function initialize() {
  try {
    await mineContext.refreshContext();
    if (!mineContext.selectedSiteId && mineContext.sites.length === 1)
      mineContext.selectSite(mineContext.sites[0]!.id);
    await loadCatalogs();
  } catch (error) {
    showError(error);
  }
}
async function changeSite(siteId: string) {
  mineContext.selectSite(siteId);
  resetFlow();
  await loadCatalogs(siteId);
}
async function loadCatalogs(siteId = mineContext.selectedSiteId) {
  if (!siteId) {
    vehicles.value = [];
    people.value = [];
    tags.value = [];
    devices.value = [];
    return;
  }
  loading.value = true;
  try {
    const [vehiclePage, personPage, tagPage, devicePage] = await Promise.all([
      getMineVehicles(siteId, { page: 1, size: 100, status: "ACTIVE" }),
      getMinePeople(siteId, { page: 1, size: 100, status: "ACTIVE" }),
      getMineLocationTags(siteId, { page: 1, size: 100, status: "ACTIVE" }),
      getDevices({ page: 1, size: 100, status: "ENABLED" }),
    ]);
    vehicles.value = vehiclePage.items;
    people.value = personPage.items;
    tags.value = tagPage.items;
    devices.value = devicePage.items;
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
}
function openFlow() {
  resetFlow();
  drawerVisible.value = true;
}
function changeBindingType(value: BindingType) {
  form.bindingType = value;
  form.resourceId = "";
  form.targetId = "";
  form.role = value === "PERSON_VEHICLE" ? "DRIVER" : "";
  preview.value = null;
  step.value = 1;
  idempotencyKey.value = null;
}
async function previewBinding() {
  const siteId = mineContext.selectedSiteId;
  if (!siteId || !form.resourceId || !form.targetId || !form.reason.trim())
    return window.$message?.warning(t("mine.assets.binding.required"));
  loading.value = true;
  try {
    preview.value = await previewMineBinding(siteId, bindingRequest());
    idempotencyKey.value = crypto.randomUUID();
    step.value = 2;
    drawerVisible.value = true;
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
}
function confirmSwitch() {
  if (!preview.value?.executable)
    return window.$message?.warning(t("mine.assets.binding.blocked"));
  window.$dialog?.warning({
    title: t("common.warning"),
    content: t("mine.assets.binding.confirmSwitch"),
    positiveText: t("mine.assets.binding.execute"),
    negativeText: t("mine.common.cancel"),
    onPositiveClick: executeSwitch,
  });
}
async function executeSwitch() {
  const siteId = mineContext.selectedSiteId;
  if (!siteId || !idempotencyKey.value) return;
  switching.value = true;
  try {
    preview.value = await switchMineBinding(
      siteId,
      bindingRequest(),
      idempotencyKey.value,
    );
    step.value = 3;
    window.$message?.success(t("mine.assets.binding.switched"));
  } catch (error) {
    showError(error);
  } finally {
    switching.value = false;
  }
}
function bindingRequest(): BindingSwitchRequest {
  return {
    bindingType: form.bindingType,
    resourceId: form.resourceId,
    targetId: form.targetId,
    role: form.role.trim() || null,
    effectiveAt: new Date(form.effectiveAt).toISOString(),
    reason: form.reason.trim(),
  };
}
function resetFlow() {
  step.value = 1;
  preview.value = null;
  idempotencyKey.value = null;
  Object.assign(form, {
    bindingType: "VEHICLE_TAG",
    resourceId: "",
    targetId: "",
    role: "",
    effectiveAt: new Date().toISOString(),
    reason: "",
  });
}
function showError(error: unknown) {
  if (error instanceof ApiError) return window.$message?.error(error.message);
  window.$message?.error(t("mine.common.operationFailed"));
}
function can(permission: string) {
  return hasPermission(authStore.userInfo.buttons, permission);
}
function bindingLabel(value: BindingPreview["proposedBinding"] | null) {
  if (!value) return t("mine.assets.binding.none");
  return `${resourceLabel(value.bindingType, value.resourceId)} → ${targetLabel(value.bindingType, value.targetId)}`;
}
function resourceLabel(bindingType: BindingType, id: string) {
  return bindingType.startsWith("VEHICLE")
    ? (vehicles.value.find((item) => item.id === id)?.name ?? id)
    : (people.value.find((item) => item.id === id)?.name ?? id);
}
function targetLabel(bindingType: BindingType, id: string) {
  if (bindingType === "VEHICLE_DEVICE")
    return devices.value.find((item) => item.id === id)?.name ?? id;
  if (bindingType.endsWith("_TAG"))
    return tags.value.find((item) => item.id === id)?.tagCode ?? id;
  return vehicles.value.find((item) => item.id === id)?.name ?? id;
}
function impactLabel(value: string) {
  return t(
    `mine.assets.binding.impact.${value.toLowerCase().replaceAll("_", "")}` as never,
  );
}
function objectTypeLabel(value: string) {
  return t(`mine.assets.binding.object.${value.toLowerCase()}` as never);
}
function objectLabel(type: string, id: string) {
  if (type === "PERSON") return people.value.find((item) => item.id === id)?.name ?? id;
  if (type === "VEHICLE") return vehicles.value.find((item) => item.id === id)?.name ?? id;
  if (type === "TAG") return tags.value.find((item) => item.id === id)?.tagCode ?? id;
  if (type === "DEVICE") return devices.value.find((item) => item.id === id)?.name ?? id;
  return id;
}
function formatTime(value: string) {
  return new Date(value).toLocaleString(locale.value, { hour12: false });
}

defineExpose({ form, previewBinding, confirmSwitch, changeSite });
</script>

<template>
  <NSpace vertical :size="16">
    <MineSiteSelector @change="changeSite" @refresh="loadCatalogs" />
    <NCard :bordered="false" size="small">
      <NFlex
        data-responsive-stack
        class="flex-col md:flex-row"
        align="center"
        justify="space-between"
      >
        <div>
          <NText strong>{{ t("mine.assets.binding.title") }}</NText>
          <div>
            <NText depth="3">{{ t("mine.assets.binding.description") }}</NText>
          </div>
        </div>
        <ListActionButton
          v-if="can('mine:binding:switch')"
          kind="create"
          data-action="start-binding"
          :disabled="!mineContext.selectedSiteId"
          @click="openFlow"
        >
          {{ t("mine.assets.binding.start") }}
        </ListActionButton>
      </NFlex>
      <NAlert type="info" class="mt-4">
        {{
          t("mine.assets.binding.temporalNotice")
        }}
      </NAlert>
      <NEmpty
        v-if="!mineContext.selectedSiteId"
        class="mt-5"
        :description="t('mine.assets.context.empty')"
      />
    </NCard>

    <NDrawer v-model:show="drawerVisible" width="min(620px, 92vw)" placement="right">
      <NDrawerContent :title="t('mine.assets.binding.drawerTitle')" closable>
        <NSteps :current="step" size="small" class="mb-6">
          <NStep :title="t('mine.assets.binding.step.configure')" /><NStep
            :title="t('mine.assets.binding.step.preview')"
          /><NStep
            :title="t('mine.assets.binding.step.complete')"
          />
        </NSteps>
        <NForm v-if="step === 1" label-placement="top">
          <NFormItem :label="t('mine.assets.binding.typeLabel')">
            <NSelect
              :value="form.bindingType"
              :options="bindingTypeOptions"
              @update:value="changeBindingType"
            />
          </NFormItem>
          <NFlex class="flex-col md:flex-row">
            <NFormItem
              :label="t('mine.assets.binding.resource')"
              class="flex-1"
            >
              <NSelect
                v-model:value="form.resourceId"
                filterable
                :options="resourceOptions"
              />
            </NFormItem><NFormItem :label="t('mine.assets.binding.target')" class="flex-1">
              <NSelect
                v-model:value="form.targetId"
                filterable
                :options="targetOptions"
              />
            </NFormItem>
          </NFlex>
          <NFormItem :label="t('mine.assets.binding.role')">
            <NInput v-model:value="form.role" :maxlength="24" />
          </NFormItem>
          <NFormItem :label="t('mine.assets.binding.effectiveAt')">
            <NDatePicker
              v-model:value="effectiveAtValue"
              type="datetime"
              class="w-full"
            />
          </NFormItem>
          <NFormItem :label="t('mine.assets.binding.reason')">
            <NInput
              v-model:value="form.reason"
              type="textarea"
              :maxlength="500"
              show-count
            />
          </NFormItem>
          <NButton type="primary" :loading="loading" @click="previewBinding">
            {{
              t("mine.assets.binding.preview")
            }}
          </NButton>
        </NForm>

        <NSpace v-else-if="step === 2 && preview" vertical :size="16">
          <NDescriptions bordered :column="1">
            <NDescriptionsItem :label="t('mine.assets.binding.previous')">
              {{
                bindingLabel(preview.previousBinding)
              }}
            </NDescriptionsItem><NDescriptionsItem :label="t('mine.assets.binding.proposed')">
              {{
                bindingLabel(preview.proposedBinding)
              }}
            </NDescriptionsItem><NDescriptionsItem :label="t('mine.assets.binding.effectiveAt')">
              {{
                formatTime(preview.proposedBinding.validFrom)
              }}
            </NDescriptionsItem>
          </NDescriptions>
          <NAlert :type="qualificationType">
            <template #header>
              {{
                preview.qualification.qualified
                  ? t("mine.assets.binding.qualificationPassed")
                  : t("mine.assets.binding.qualificationFailed")
              }}
            </template>{{
              preview.qualification.required
                ? t("mine.assets.binding.qualificationRequired")
                : t("mine.assets.binding.qualificationNotRequired")
            }}
          </NAlert>
          <NCard size="small" :title="t('mine.assets.binding.affectedObjects')">
            <NSpace vertical>
              <NFlex
                v-for="item in preview.affectedObjects"
                :key="`${item.objectType}-${item.objectId}-${item.impact}`"
                justify="space-between"
              >
                <NText>
                  {{ objectTypeLabel(item.objectType) }} ·
                  {{ objectLabel(item.objectType, item.objectId) }}
                </NText><NTag size="small" type="info">
                  {{
                    impactLabel(item.impact)
                  }}
                </NTag>
              </NFlex>
            </NSpace>
          </NCard>
          <NAlert v-if="preview.blockers.length" type="error">
            <div v-for="blocker in preview.blockers" :key="blocker">
              {{ blocker }}
            </div>
          </NAlert>
          <NFlex justify="space-between">
            <NButton @click="step = 1">
              {{
                t("mine.assets.binding.back")
              }}
            </NButton><NButton
              v-if="can('mine:binding:switch')"
              type="warning"
              :disabled="!preview.executable"
              :loading="switching"
              @click="confirmSwitch"
            >
              {{ t("mine.assets.binding.execute") }}
            </NButton>
          </NFlex>
        </NSpace>

        <NSpace
          v-else-if="step === 3 && preview"
          vertical
          align="center"
          :size="16"
        >
          <NTag type="success" size="large">
            {{
              t("mine.assets.binding.complete")
            }}
          </NTag><NText>
            {{
              t("mine.assets.binding.operationId", { id: preview.operationId })
            }}
          </NText><NButton type="primary" @click="drawerVisible = false">
            {{
              t("common.close")
            }}
          </NButton>
        </NSpace>
      </NDrawerContent>
    </NDrawer>
  </NSpace>
</template>
