<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { SelectOption } from 'naive-ui';
import {
  NAlert,
  NButton,
  NCard,
  NCollapse,
  NCollapseItem,
  NDivider,
  NEmpty,
  NFlex,
  NForm,
  NFormItem,
  NGi,
  NGrid,
  NInput,
  NInputNumber,
  NList,
  NListItem,
  NPageHeader,
  NSelect,
  NSpin,
  NTag,
  NText
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import { getDevices, getProducts } from '@/modules/device/api/device';
import type { Device, Product } from '@/modules/device/api/device';
import {
  archiveDeviceGroup,
  createDeviceGroup,
  createStrategyDraft,
  getEffectiveStrategy,
  listDeviceGroupMembers,
  listDeviceGroups,
  listStrategyReleases,
  listStrategyVersions,
  publishStrategy,
  replaceDeviceGroupMembers,
  rollbackStrategyRelease,
  updateDeviceGroup,
  updateStrategyDraft
} from '@/modules/device/api/strategy';
import type {
  DeviceGroup,
  EffectiveStrategy,
  StrategyRelease,
  StrategyScopeType,
  StrategyVersion
} from '@/modules/device/api/strategy';
import { useAuthStore } from '@/store/modules/auth';
import EffectiveStrategyPanel from '@/modules/device/components/strategy/EffectiveStrategyPanel.vue';

interface StrategyProblem {
  code: string;
  message: string;
  traceId?: string;
  details?: Record<string, unknown>;
}

const { t } = useI18n();
const authStore = useAuthStore();
const loading = ref(false);
const savingGroup = ref(false);
const savingMembers = ref(false);
const savingDraft = ref(false);
const publishing = ref(false);
const rollingBackId = ref<string | null>(null);
const effectiveLoading = ref(false);
const products = ref<Product[]>([]);
const devices = ref<Device[]>([]);
const groups = ref<DeviceGroup[]>([]);
const versions = ref<StrategyVersion[]>([]);
const releases = ref<StrategyRelease[]>([]);
const productId = ref<string | null>(null);
const selectedGroupId = ref<string | null>(null);
const selectedDeviceId = ref<string | null>(null);
const selectedVersionId = ref<string | null>(null);
const memberIds = ref<string[]>([]);
const effective = ref<EffectiveStrategy | null>(null);
const operationProblem = ref<StrategyProblem | null>(null);
const groupMode = ref<'create' | 'edit'>('create');
const draftMode = ref<'create' | 'edit'>('create');
const publishIdempotencyKey = ref<string | null>(null);
const rollbackIdempotencyKeys = new Map<string, string>();

const groupForm = reactive({ groupKey: '', name: '', priority: 100, description: '' });
const draftForm = reactive({ threshold: 180, description: '' });
const publishForm = reactive({ scopeType: 'PRODUCT' as StrategyScopeType, reason: '' });
const rollbackReason = ref('');

const productOptions = computed<SelectOption[]>(() =>
  products.value.map(item => ({ label: `${item.name} (${item.productKey})`, value: item.id }))
);
const deviceOptions = computed<SelectOption[]>(() =>
  devices.value.map(item => ({ label: `${item.name} (${item.deviceCode})`, value: item.id }))
);
const memberOptions = computed<SelectOption[]>(() => deviceOptions.value);
const versionOptions = computed<SelectOption[]>(() =>
  versions.value.map(item => ({
    label: `${t('mine.strategy.text.version', { version: item.versionNumber })} · ${statusLabel(item.status)} · ${item.values.offlineThresholdSeconds} ${t('mine.strategy.text.seconds')}`,
    value: item.id
  }))
);
const scopeOptions = computed<SelectOption[]>(() =>
  (['PRODUCT', 'GROUP', 'DEVICE'] as StrategyScopeType[]).map(value => ({
    label: scopeLabel(value),
    value,
    disabled: value === 'GROUP' ? !selectedGroupId.value : value === 'DEVICE' ? !selectedDeviceId.value : false
  }))
);
const selectedGroup = computed(() => groups.value.find(item => item.id === selectedGroupId.value) || null);
const selectedDevice = computed(() => devices.value.find(item => item.id === selectedDeviceId.value) || null);
const selectedVersion = computed(() => versions.value.find(item => item.id === selectedVersionId.value) || null);
const publishScopeId = computed(() => {
  if (publishForm.scopeType === 'PRODUCT') return productId.value;
  if (publishForm.scopeType === 'GROUP') return selectedGroupId.value;
  return selectedDeviceId.value;
});

watch(
  () => [selectedVersionId.value, publishForm.scopeType, publishScopeId.value, publishForm.reason],
  () => {
    publishIdempotencyKey.value = null;
  }
);

onMounted(loadProducts);

async function loadProducts() {
  loading.value = true;
  operationProblem.value = null;
  try {
    const response = await getProducts({ page: 1, size: 100 });
    products.value = response.items.filter(item => item.status === 'ACTIVE');
    productId.value = products.value[0]?.id || null;
    await loadProductContext();
  } catch (error) {
    captureProblem(error);
  } finally {
    loading.value = false;
  }
}

async function onProductChanged() {
  selectedGroupId.value = null;
  selectedDeviceId.value = null;
  selectedVersionId.value = null;
  memberIds.value = [];
  effective.value = null;
  resetGroupForm();
  resetDraftForm();
  await loadProductContext();
}

async function loadProductContext() {
  if (!productId.value) return;
  loading.value = true;
  const jobs: Promise<unknown>[] = [loadDevices(), loadStrategyData()];
  if (can('device:group:view')) jobs.push(loadGroups());
  const results = await Promise.allSettled(jobs);
  const rejected = results.find(result => result.status === 'rejected');
  if (rejected?.status === 'rejected') captureProblem(rejected.reason);
  loading.value = false;
}

async function loadGroups(preferredId?: string) {
  if (!productId.value) return;
  const response = await listDeviceGroups({ productId: productId.value, status: 'ACTIVE', page: 1, size: 100 });
  groups.value = response.items;
  selectedGroupId.value = groups.value.some(item => item.id === (preferredId || selectedGroupId.value))
    ? preferredId || selectedGroupId.value
    : groups.value[0]?.id || null;
  await loadMembers();
}

async function loadDevices() {
  if (!productId.value) return;
  const response = await getDevices({ productId: productId.value, page: 1, size: 200 });
  devices.value = response.items.filter(item => item.status !== 'ARCHIVED');
  selectedDeviceId.value = devices.value.some(item => item.id === selectedDeviceId.value)
    ? selectedDeviceId.value
    : devices.value[0]?.id || null;
  await loadEffective();
}

async function loadStrategyData() {
  if (!productId.value) return;
  const [nextVersions, nextReleases] = await Promise.all([
    listStrategyVersions({ productId: productId.value, strategyType: 'ONLINE_STATUS', limit: 50 }),
    listStrategyReleases({ productId: productId.value, strategyType: 'ONLINE_STATUS', limit: 50 })
  ]);
  versions.value = nextVersions;
  releases.value = nextReleases;
  const preferredVersion =
    versions.value.find(item => item.id === selectedVersionId.value) ||
    versions.value.find(item => item.status === 'DRAFT') ||
    versions.value[0];
  selectedVersionId.value = preferredVersion?.id || null;
  syncDraftForm();
}

async function selectGroup(groupId: string) {
  selectedGroupId.value = groupId;
  await loadMembers();
}

async function loadMembers() {
  if (!selectedGroupId.value || !can('device:group:view')) {
    memberIds.value = [];
    return;
  }
  const members = await listDeviceGroupMembers(selectedGroupId.value);
  memberIds.value = members.map(item => item.id);
}

async function loadEffective() {
  if (!selectedDeviceId.value) {
    effective.value = null;
    return;
  }
  effectiveLoading.value = true;
  try {
    effective.value = await getEffectiveStrategy(selectedDeviceId.value, 'ONLINE_STATUS');
  } catch (error) {
    effective.value = null;
    captureProblem(error);
  } finally {
    effectiveLoading.value = false;
  }
}

function startCreateGroup() {
  groupMode.value = 'create';
  resetGroupForm();
}

function startEditGroup() {
  if (!selectedGroup.value) return;
  groupMode.value = 'edit';
  Object.assign(groupForm, {
    groupKey: selectedGroup.value.groupKey,
    name: selectedGroup.value.name,
    priority: selectedGroup.value.priority,
    description: selectedGroup.value.description || ''
  });
}

async function saveGroup() {
  if (!productId.value || !groupForm.name.trim() || !groupForm.groupKey.trim()) {
    return window.$message?.warning(t('mine.strategy.validation.required'));
  }
  savingGroup.value = true;
  operationProblem.value = null;
  try {
    let saved: DeviceGroup;
    if (groupMode.value === 'edit' && selectedGroup.value) {
      saved = await updateDeviceGroup(selectedGroup.value.id, {
        name: groupForm.name.trim(),
        priority: groupForm.priority,
        description: groupForm.description.trim() || null,
        version: selectedGroup.value.version
      });
      window.$message?.success(t('mine.strategy.message.groupUpdated'));
    } else {
      saved = await createDeviceGroup({
        productId: productId.value,
        groupKey: groupForm.groupKey.trim(),
        name: groupForm.name.trim(),
        priority: groupForm.priority,
        description: groupForm.description.trim() || null
      });
      window.$message?.success(t('mine.strategy.message.groupCreated'));
    }
    await loadGroups(saved.id);
    startEditGroup();
  } catch (error) {
    captureProblem(error);
  } finally {
    savingGroup.value = false;
  }
}

async function saveMembers() {
  if (!selectedGroup.value) return;
  savingMembers.value = true;
  operationProblem.value = null;
  try {
    await replaceDeviceGroupMembers(selectedGroup.value.id, {
      deviceIds: memberIds.value,
      version: selectedGroup.value.version
    });
    window.$message?.success(t('mine.strategy.message.membersUpdated'));
    await loadGroups(selectedGroup.value.id);
    await loadEffective();
  } catch (error) {
    captureProblem(error);
  } finally {
    savingMembers.value = false;
  }
}

async function archiveSelectedGroup() {
  if (!selectedGroup.value || !window.confirm(t('mine.strategy.confirm.archiveGroup'))) return;
  operationProblem.value = null;
  try {
    await archiveDeviceGroup(selectedGroup.value.id, selectedGroup.value.version);
    window.$message?.success(t('mine.strategy.message.groupArchived'));
    selectedGroupId.value = null;
    await loadGroups();
    resetGroupForm();
    await loadEffective();
  } catch (error) {
    captureProblem(error);
  }
}

function startNewDraft() {
  draftMode.value = 'create';
  selectedVersionId.value = null;
  resetDraftForm();
}

function syncDraftForm() {
  const version = selectedVersion.value;
  if (!version) {
    draftMode.value = 'create';
    resetDraftForm();
    return;
  }
  draftMode.value = version.status === 'DRAFT' ? 'edit' : 'create';
  Object.assign(draftForm, {
    threshold: version.values.offlineThresholdSeconds,
    description: version.description || ''
  });
}

async function saveDraft() {
  if (!productId.value || draftForm.threshold < 30 || draftForm.threshold > 86400) {
    return window.$message?.warning(t('mine.strategy.validation.threshold'));
  }
  savingDraft.value = true;
  operationProblem.value = null;
  try {
    let saved: StrategyVersion;
    if (draftMode.value === 'edit' && selectedVersion.value?.status === 'DRAFT') {
      saved = await updateStrategyDraft(selectedVersion.value.id, {
        values: { offlineThresholdSeconds: draftForm.threshold },
        description: draftForm.description.trim() || null,
        version: selectedVersion.value.version
      });
      window.$message?.success(t('mine.strategy.message.draftUpdated'));
    } else {
      saved = await createStrategyDraft({
        productId: productId.value,
        strategyType: 'ONLINE_STATUS',
        values: { offlineThresholdSeconds: draftForm.threshold },
        description: draftForm.description.trim() || null
      });
      window.$message?.success(t('mine.strategy.message.draftCreated'));
    }
    selectedVersionId.value = saved.id;
    await loadStrategyData();
  } catch (error) {
    captureProblem(error);
  } finally {
    savingDraft.value = false;
  }
}

async function runPublish() {
  if (!selectedVersion.value || selectedVersion.value.status !== 'DRAFT') {
    return window.$message?.warning(t('mine.strategy.validation.draft'));
  }
  if (!publishScopeId.value) return window.$message?.warning(t('mine.strategy.validation.scope'));
  if (publishForm.reason.trim().length < 4) return window.$message?.warning(t('mine.strategy.validation.reason'));
  publishing.value = true;
  operationProblem.value = null;
  publishIdempotencyKey.value ||= createIdempotencyKey('publish');
  try {
    await publishStrategy(
      {
        strategyVersionId: selectedVersion.value.id,
        scopeType: publishForm.scopeType,
        scopeId: publishScopeId.value,
        reason: publishForm.reason.trim()
      },
      publishIdempotencyKey.value
    );
    publishIdempotencyKey.value = null;
    publishForm.reason = '';
    window.$message?.success(t('mine.strategy.message.published'));
    await Promise.all([loadStrategyData(), loadEffective()]);
  } catch (error) {
    captureProblem(error);
  } finally {
    publishing.value = false;
  }
}

async function runRollback(release: StrategyRelease) {
  if (rollbackReason.value.trim().length < 4) {
    return window.$message?.warning(t('mine.strategy.validation.reason'));
  }
  if (!window.confirm(t('mine.strategy.confirm.rollback'))) return;
  rollingBackId.value = release.id;
  operationProblem.value = null;
  const key = rollbackIdempotencyKeys.get(release.id) || createIdempotencyKey(`rollback-${release.id}`);
  rollbackIdempotencyKeys.set(release.id, key);
  try {
    await rollbackStrategyRelease(release.id, { reason: rollbackReason.value.trim() }, key);
    rollbackIdempotencyKeys.delete(release.id);
    rollbackReason.value = '';
    window.$message?.success(t('mine.strategy.message.rolledBack'));
    await Promise.all([loadStrategyData(), loadEffective()]);
  } catch (error) {
    captureProblem(error);
  } finally {
    rollingBackId.value = null;
  }
}

function resetGroupForm() {
  Object.assign(groupForm, { groupKey: '', name: '', priority: 100, description: '' });
}

function resetDraftForm() {
  Object.assign(draftForm, { threshold: 180, description: '' });
}

function can(permission: string) {
  return hasPermission(authStore.userInfo.buttons, permission);
}

function statusLabel(status: StrategyVersion['status']) {
  return t(`mine.strategy.status.${status.toLowerCase()}`);
}

function scopeLabel(scope: StrategyScopeType) {
  return t(`mine.strategy.scope.${scope.toLowerCase()}`);
}

function releaseActionLabel(action: StrategyRelease['action']) {
  return t(`mine.strategy.status.${action.toLowerCase()}`);
}

function captureProblem(error: unknown) {
  if (error instanceof ApiError) {
    operationProblem.value = {
      code: error.code,
      message: error.message,
      traceId: error.traceId,
      details: error.details
    };
    window.$message?.error(error.message);
    return;
  }
  operationProblem.value = { code: 'SYS-UNKNOWN-001', message: t('mine.common.operationFailed') };
  window.$message?.error(t('mine.common.operationFailed'));
}

function createIdempotencyKey(prefix: string) {
  const suffix = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `strategy-${prefix}-${suffix}`;
}
</script>

<template>
  <div>
    <NFlex vertical :size="16">
      <NPageHeader :title="t('mine.strategy.title')" :subtitle="t('mine.strategy.subtitle')">
        <template #extra>
          <NTag type="primary" :bordered="false" round>{{ t('mine.strategy.policyName') }}</NTag>
        </template>
      </NPageHeader>

      <NAlert type="warning" :bordered="false">{{ t('mine.strategy.productionHelp') }}</NAlert>
      <NAlert
        v-if="!can('device:group:edit') && !can('device:strategy:edit') && !can('device:strategy:publish')"
        type="info"
        :bordered="false"
      >
        {{ t('mine.strategy.help.readonly') }}
      </NAlert>

      <NSpin :show="loading">
        <NGrid responsive="screen" cols="1 m:12 xl:12" :x-gap="16" :y-gap="16">
          <NGi span="1 m:4 xl:3" class="min-w-0">
            <NFlex vertical :size="16">
              <NCard :title="t('mine.strategy.section.context')" :bordered="false" size="small">
                <NForm label-placement="top">
                  <NFormItem :label="t('mine.strategy.field.product')">
                    <NSelect
                      v-model:value="productId"
                      filterable
                      :options="productOptions"
                      :placeholder="t('mine.strategy.placeholder.select', { field: t('mine.strategy.field.product') })"
                      @update:value="onProductChanged"
                    />
                  </NFormItem>
                  <NFormItem :label="t('mine.strategy.field.device')">
                    <NSelect
                      v-model:value="selectedDeviceId"
                      filterable
                      :options="deviceOptions"
                      :placeholder="t('mine.strategy.placeholder.select', { field: t('mine.strategy.field.device') })"
                      @update:value="loadEffective"
                    />
                  </NFormItem>
                </NForm>
                <NEmpty v-if="!products.length" :description="t('mine.strategy.empty.products')" />
                <NEmpty v-else-if="!devices.length" :description="t('mine.strategy.empty.devices')" />
              </NCard>

              <NCard
                v-if="can('device:group:view')"
                :title="t('mine.strategy.section.groups')"
                :bordered="false"
                size="small"
              >
                <template #header-extra>
                  <NButton
                    v-if="can('device:group:edit')"
                    quaternary
                    type="primary"
                    size="small"
                    data-action="new-group"
                    @click="startCreateGroup"
                  >
                    {{ t('mine.strategy.action.newGroup') }}
                  </NButton>
                </template>
                <NFlex v-if="groups.length" vertical :size="8">
                  <NButton
                    v-for="group in groups"
                    :key="group.id"
                    block
                    :type="group.id === selectedGroupId ? 'primary' : 'default'"
                    :secondary="group.id === selectedGroupId"
                    @click="selectGroup(group.id)"
                  >
                    <NFlex justify="space-between" class="w-full">
                      <NText>{{ group.name }}</NText>
                      <NText depth="3">{{ t('mine.strategy.text.priority', { priority: group.priority }) }}</NText>
                    </NFlex>
                  </NButton>
                </NFlex>
                <NEmpty v-else :description="t('mine.strategy.empty.groups')" />

                <template v-if="can('device:group:edit')">
                  <NDivider />
                  <NCollapse :default-expanded-names="['editor']">
                    <NCollapseItem
                      name="editor"
                      :title="
                        groupMode === 'edit' ? t('mine.strategy.action.editGroup') : t('mine.strategy.action.newGroup')
                      "
                    >
                      <NForm label-placement="top">
                        <NFormItem :label="t('mine.strategy.field.groupKey')">
                          <NInput
                            v-model:value="groupForm.groupKey"
                            :disabled="groupMode === 'edit'"
                            :placeholder="
                              t('mine.strategy.placeholder.enter', { field: t('mine.strategy.field.groupKey') })
                            "
                          />
                        </NFormItem>
                        <NFormItem :label="t('mine.strategy.field.groupName')">
                          <NInput
                            v-model:value="groupForm.name"
                            :placeholder="
                              t('mine.strategy.placeholder.enter', { field: t('mine.strategy.field.groupName') })
                            "
                          />
                        </NFormItem>
                        <NFormItem :label="t('mine.strategy.field.priority')">
                          <NInputNumber
                            v-model:value="groupForm.priority"
                            :min="0"
                            :max="1000000"
                            :placeholder="
                              t('mine.strategy.placeholder.enter', { field: t('mine.strategy.field.priority') })
                            "
                          />
                        </NFormItem>
                        <NFormItem :label="t('mine.strategy.field.description')">
                          <NInput
                            v-model:value="groupForm.description"
                            type="textarea"
                            :autosize="{ minRows: 2, maxRows: 4 }"
                            :placeholder="
                              t('mine.strategy.placeholder.enter', { field: t('mine.strategy.field.description') })
                            "
                          />
                        </NFormItem>
                        <NText depth="3">{{ t('mine.strategy.help.groupPriority') }}</NText>
                        <NFlex justify="end">
                          <NButton v-if="selectedGroup && groupMode !== 'edit'" @click="startEditGroup">
                            {{ t('mine.strategy.action.editGroup') }}
                          </NButton>
                          <NButton type="primary" :loading="savingGroup" data-action="save-group" @click="saveGroup">
                            {{ t('mine.strategy.action.saveGroup') }}
                          </NButton>
                        </NFlex>
                      </NForm>
                    </NCollapseItem>
                  </NCollapse>
                </template>
              </NCard>
            </NFlex>
          </NGi>

          <NGi span="1 m:8 xl:6" class="min-w-0">
            <NFlex vertical :size="16">
              <NCard
                v-if="can('device:group:view') && selectedGroup"
                :title="t('mine.strategy.section.members')"
                :bordered="false"
                size="small"
              >
                <NFlex vertical :size="10">
                  <NFlex justify="space-between" align="center" :wrap="true">
                    <NText strong>{{ selectedGroup.name }}</NText>
                    <NTag :bordered="false">
                      {{ t('mine.strategy.text.memberCount', { count: selectedGroup.memberCount }) }}
                    </NTag>
                  </NFlex>
                  <NSelect
                    v-model:value="memberIds"
                    multiple
                    filterable
                    :disabled="!can('device:group:edit')"
                    :options="memberOptions"
                    :placeholder="t('mine.strategy.placeholder.select', { field: t('mine.strategy.field.members') })"
                  />
                  <NText depth="3">{{ t('mine.strategy.help.members') }}</NText>
                  <NFlex v-if="can('device:group:edit')" justify="space-between">
                    <NButton type="error" secondary @click="archiveSelectedGroup">
                      {{ t('mine.strategy.action.archiveGroup') }}
                    </NButton>
                    <NButton type="primary" :loading="savingMembers" data-action="save-members" @click="saveMembers">
                      {{ t('mine.strategy.action.saveMembers') }}
                    </NButton>
                  </NFlex>
                </NFlex>
              </NCard>

              <NCard :title="t('mine.strategy.section.draft')" :bordered="false" size="small">
                <template #header-extra>
                  <NButton
                    v-if="can('device:strategy:edit')"
                    quaternary
                    type="primary"
                    size="small"
                    data-action="new-draft"
                    @click="startNewDraft"
                  >
                    {{ t('mine.strategy.action.newDraft') }}
                  </NButton>
                </template>
                <NForm label-placement="top">
                  <NFormItem :label="t('mine.strategy.field.version')">
                    <NSelect
                      v-model:value="selectedVersionId"
                      :options="versionOptions"
                      :placeholder="t('mine.strategy.placeholder.select', { field: t('mine.strategy.field.version') })"
                      @update:value="syncDraftForm"
                    />
                  </NFormItem>
                  <NGrid responsive="screen" cols="1 s:2" :x-gap="12">
                    <NGi>
                      <NFormItem :label="t('mine.strategy.field.threshold')">
                        <NInputNumber
                          v-model:value="draftForm.threshold"
                          :min="30"
                          :max="86400"
                          :disabled="!can('device:strategy:edit') || selectedVersion?.status === 'PUBLISHED'"
                          :placeholder="
                            t('mine.strategy.placeholder.enter', { field: t('mine.strategy.field.threshold') })
                          "
                        >
                          <template #suffix>{{ t('mine.strategy.text.seconds') }}</template>
                        </NInputNumber>
                      </NFormItem>
                    </NGi>
                    <NGi>
                      <NFormItem :label="t('mine.strategy.field.status')">
                        <NTag
                          v-if="selectedVersion"
                          :type="selectedVersion.status === 'DRAFT' ? 'warning' : 'success'"
                          :bordered="false"
                        >
                          {{ statusLabel(selectedVersion.status) }}
                        </NTag>
                        <NText v-else depth="3">{{ t('mine.strategy.status.draft') }}</NText>
                      </NFormItem>
                    </NGi>
                  </NGrid>
                  <NFormItem :label="t('mine.strategy.field.description')">
                    <NInput
                      v-model:value="draftForm.description"
                      type="textarea"
                      :disabled="!can('device:strategy:edit') || selectedVersion?.status === 'PUBLISHED'"
                      :autosize="{ minRows: 2, maxRows: 4 }"
                      :placeholder="t('mine.strategy.placeholder.enter', { field: t('mine.strategy.field.description') })"
                    />
                  </NFormItem>
                  <NFlex v-if="can('device:strategy:edit')" justify="end">
                    <NButton
                      type="primary"
                      :loading="savingDraft"
                      :disabled="selectedVersion?.status === 'PUBLISHED'"
                      data-action="save-draft"
                      @click="saveDraft"
                    >
                      {{ t('mine.strategy.action.saveDraft') }}
                    </NButton>
                  </NFlex>
                </NForm>
                <NEmpty
                  v-if="!versions.length && !can('device:strategy:edit')"
                  :description="t('mine.strategy.empty.versions')"
                />
              </NCard>

              <NCard :title="t('mine.strategy.section.publish')" :bordered="false" size="small">
                <NForm label-placement="top">
                  <NFormItem :label="t('mine.strategy.field.scope')">
                    <NSelect
                      v-model:value="publishForm.scopeType"
                      :options="scopeOptions"
                      :placeholder="t('mine.strategy.placeholder.select', { field: t('mine.strategy.field.scope') })"
                    />
                  </NFormItem>
                  <NAlert type="info" :bordered="false">{{ t('mine.strategy.help.scope') }}</NAlert>
                  <NFormItem :label="t('mine.strategy.field.reason')">
                    <NInput
                      v-model:value="publishForm.reason"
                      type="textarea"
                      :autosize="{ minRows: 2, maxRows: 4 }"
                      :placeholder="t('mine.strategy.placeholder.enter', { field: t('mine.strategy.field.reason') })"
                      data-field="publish-reason"
                    />
                  </NFormItem>
                  <NFlex v-if="can('device:strategy:publish')" justify="end">
                    <NButton type="primary" :loading="publishing" data-action="publish-strategy" @click="runPublish">
                      {{ t('mine.strategy.action.publish') }}
                    </NButton>
                  </NFlex>
                </NForm>
              </NCard>

              <NCard :title="t('mine.strategy.section.history')" :bordered="false" size="small">
                <NFlex v-if="can('device:strategy:publish') && releases.length" vertical :size="8">
                  <NInput
                    v-model:value="rollbackReason"
                    type="textarea"
                    :autosize="{ minRows: 2, maxRows: 3 }"
                    :placeholder="t('mine.strategy.placeholder.enter', { field: t('mine.strategy.field.reason') })"
                    data-field="rollback-reason"
                  />
                </NFlex>
                <NList v-if="releases.length" hoverable>
                  <NListItem v-for="release in releases" :key="release.id">
                    <NFlex justify="space-between" align="center" :wrap="true">
                      <NFlex vertical :size="3">
                        <NFlex align="center">
                          <NText strong>
                            {{ t('mine.strategy.text.sequence', { sequence: release.releaseSequence }) }}
                          </NText>
                          <NTag
                            :type="release.action === 'PUBLISH' ? 'success' : 'warning'"
                            :bordered="false"
                            size="small"
                          >
                            {{ releaseActionLabel(release.action) }}
                          </NTag>
                          <NTag :bordered="false" size="small">{{ scopeLabel(release.scopeType) }}</NTag>
                        </NFlex>
                        <NText code class="break-all">{{ release.contentHash }}</NText>
                      </NFlex>
                      <NButton
                        v-if="can('device:strategy:publish')"
                        secondary
                        type="warning"
                        size="small"
                        :loading="rollingBackId === release.id"
                        data-action="rollback-strategy"
                        @click="runRollback(release)"
                      >
                        {{ t('mine.strategy.action.rollback') }}
                      </NButton>
                    </NFlex>
                  </NListItem>
                </NList>
                <NEmpty v-else :description="t('mine.strategy.empty.releases')" />
              </NCard>
            </NFlex>
          </NGi>

          <NGi span="1 m:12 xl:3" class="min-w-0">
            <EffectiveStrategyPanel
              :device-name="selectedDevice?.name"
              :loading="effectiveLoading"
              :effective="effective"
              :problem="operationProblem"
              @refresh="loadEffective"
            />
          </NGi>
        </NGrid>
      </NSpin>
    </NFlex>
  </div>
</template>
