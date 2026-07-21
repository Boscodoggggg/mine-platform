<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { SelectOption } from 'naive-ui';
import {
  NAlert,
  NButton,
  NCard,
  NCheckbox,
  NCollapse,
  NCollapseItem,
  NDescriptions,
  NDescriptionsItem,
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
  NStep,
  NSteps,
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
  listRetentionExecutions,
  listStrategyReleases,
  listStrategyVersions,
  previewRetentionGroupChange,
  previewRetentionImpact,
  publishStrategy,
  replaceDeviceGroupMembers,
  rollbackStrategyRelease,
  updateDeviceGroup,
  updateStrategyDraft
} from '@/modules/device/api/strategy';
import type {
  AggregationPolicy,
  AlarmRule,
  DeviceGroup,
  EffectiveStrategy,
  RetentionExecution,
  RetentionGroupChangePreviewRequest,
  RetentionPolicy,
  RetentionPreview,
  StrategyRelease,
  StrategyScopeType,
  StrategyType,
  StrategyVersion
} from '@/modules/device/api/strategy';
import { useAuthStore } from '@/store/modules/auth';
import AlarmRuleEditor from '@/modules/device/components/strategy/AlarmRuleEditor.vue';
import AggregationPolicyEditor from '@/modules/device/components/strategy/AggregationPolicyEditor.vue';
import EffectiveStrategyPanel from '@/modules/device/components/strategy/EffectiveStrategyPanel.vue';
import RetentionPolicyEditor from '@/modules/device/components/strategy/RetentionPolicyEditor.vue';

interface StrategyProblem {
  code: string;
  message: string;
  traceId?: string;
  details?: Record<string, unknown>;
}

interface QualityRuleForm {
  key: string;
  propertyId: string;
  required: boolean;
  minimum: number | null;
  maximum: number | null;
}

interface RetentionRollbackPreview {
  releaseId: string;
  productId: string;
  strategyVersionId: string;
  scopeType: StrategyScopeType;
  scopeId: string;
  reason: string;
  preview: RetentionPreview;
}

interface RetentionGroupChangePreviewState {
  key: string;
  changeType: RetentionGroupChangePreviewRequest['changeType'];
  preview: RetentionPreview;
}

const props = defineProps<{
  fixedStrategyType?: StrategyType;
  fixedProductId?: string;
  allowedStrategyTypes?: StrategyType[];
  embedded?: boolean;
}>();

const { locale, t } = useI18n();
const authStore = useAuthStore();
const loading = ref(false);
const savingGroup = ref(false);
const savingMembers = ref(false);
const savingDraft = ref(false);
const publishing = ref(false);
const rollingBackId = ref<string | null>(null);
const effectiveLoading = ref(false);
const retentionPreviewLoading = ref(false);
const retentionExecutionsLoading = ref(false);
const products = ref<Product[]>([]);
const devices = ref<Device[]>([]);
const deviceTotal = ref(0);
const groups = ref<DeviceGroup[]>([]);
const versions = ref<StrategyVersion[]>([]);
const releases = ref<StrategyRelease[]>([]);
const strategyType = ref<StrategyType>(props.fixedStrategyType || 'ONLINE_STATUS');
const productId = ref<string | null>(null);
const selectedGroupId = ref<string | null>(null);
const selectedDeviceId = ref<string | null>(null);
const selectedVersionId = ref<string | null>(null);
const memberIds = ref<string[]>([]);
const savedMemberIds = ref<string[]>([]);
const effective = ref<EffectiveStrategy | null>(null);
const retentionPreview = ref<RetentionPreview | null>(null);
const retentionRollbackPreview = ref<RetentionRollbackPreview | null>(null);
const retentionGroupChangePreview = ref<RetentionGroupChangePreviewState | null>(null);
const retentionExecutions = ref<RetentionExecution[]>([]);
const retentionProblem = ref<StrategyProblem | null>(null);
const retentionPolicyValid = ref(true);
const aggregationPolicyValid = ref(true);
const operationProblem = ref<StrategyProblem | null>(null);
const publishProblem = ref<StrategyProblem | null>(null);
const groupMode = ref<'create' | 'edit'>('create');
const draftMode = ref<'create' | 'edit'>('create');
const publishIdempotencyKey = ref<string | null>(null);
const rollbackIdempotencyKeys = new Map<string, string>();

const groupForm = reactive({
  groupKey: '',
  name: '',
  priority: 100,
  description: ''
});
const draftForm = reactive({
  threshold: 180,
  staleAfterSeconds: 300 as number | null,
  futureToleranceSeconds: 5 as number | null,
  qualityRules: [] as QualityRuleForm[],
  alarmRules: [] as AlarmRule[],
  retentionPolicy: {
    enabled: true,
    retentionClass: 'STANDARD',
    rawTelemetryDays: 90,
    qualityEvidenceDays: 180
  } as RetentionPolicy,
  aggregationPolicy: {
    enabled: true,
    correctionWindowSeconds: 86400,
    rules: [{ propertyId: '', bucketSeconds: 300 }]
  } as AggregationPolicy,
  description: ''
});
const publishForm = reactive({
  scopeType: 'PRODUCT' as StrategyScopeType,
  reason: ''
});
const rollbackReason = ref('');

const productOptions = computed<SelectOption[]>(() =>
  products.value.map(item => ({
    label: `${item.name} (${item.productKey})`,
    value: item.id
  }))
);
const deviceOptions = computed<SelectOption[]>(() =>
  devices.value.map(item => ({
    label: `${item.name} (${item.deviceCode})`,
    value: item.id
  }))
);
const memberOptions = computed<SelectOption[]>(() => deviceOptions.value);
const groupOptions = computed<SelectOption[]>(() =>
  groups.value.map(item => ({
    label: `${item.name} (${item.groupKey})`,
    value: item.id
  }))
);
const strategyTypeOptions = computed<SelectOption[]>(() =>
  (props.allowedStrategyTypes ||
    (['ONLINE_STATUS', 'DATA_QUALITY', 'ALARM', 'RETENTION', 'AGGREGATION'] as StrategyType[])).map(value => ({
      label: t(`mine.strategy.type.${value.toLowerCase()}`),
      value
    }))
);
const versionOptions = computed<SelectOption[]>(() =>
  versions.value.map(item => ({
    label: `${t('mine.strategy.text.version', { version: item.versionNumber })} · ${statusLabel(item.status)} · ${strategySummary(item.values)}`,
    value: item.id
  }))
);
const policyName = computed(() => t(`mine.strategy.type.${strategyType.value.toLowerCase()}`));
const pageTitle = computed(() =>
  props.fixedStrategyType === 'RETENTION' ? t('mine.retentionGovernance.title') : t('mine.strategy.title')
);
const pageSubtitle = computed(() =>
  props.fixedStrategyType === 'RETENTION' ? t('mine.retentionGovernance.subtitle') : t('mine.strategy.subtitle')
);
const productionHelp = computed(() =>
  t(
    `mine.strategy.productionHelp.${strategyType.value === 'DATA_QUALITY' ? 'quality' : strategyType.value === 'ALARM' ? 'alarm' : strategyType.value === 'RETENTION' ? 'retention' : strategyType.value === 'AGGREGATION' ? 'aggregation' : 'online'}`
  )
);
const isDataQuality = computed(() => strategyType.value === 'DATA_QUALITY');
const isAlarm = computed(() => strategyType.value === 'ALARM');
const isRetention = computed(() => strategyType.value === 'RETENTION');
const isAggregation = computed(() => strategyType.value === 'AGGREGATION');
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
const onlineCurrentThreshold = computed(() => numericValue(effective.value?.values || {}, 'offlineThresholdSeconds'));
const onlineNextThreshold = computed(() => draftForm.threshold);
const onlineDraftDirty = computed(() => {
  if (strategyType.value !== 'ONLINE_STATUS' || selectedVersion.value?.status !== 'DRAFT') return false;
  return (
    numericValue(selectedVersion.value.values, 'offlineThresholdSeconds') !== draftForm.threshold ||
    (selectedVersion.value.description || '') !== draftForm.description.trim()
  );
});
const onlineDuration = computed(() =>
  draftForm.threshold % 60 === 0
    ? t('mine.strategy.text.durationMinutes', { minutes: draftForm.threshold / 60 })
    : t('mine.strategy.text.durationSeconds', { seconds: draftForm.threshold })
);
const publishTargetCount = computed(() => {
  if (publishForm.scopeType === 'GROUP') return selectedGroup.value?.memberCount || 0;
  if (publishForm.scopeType === 'DEVICE') return selectedDeviceId.value ? 1 : 0;
  return deviceTotal.value;
});
const publishTargetName = computed(() => {
  if (publishForm.scopeType === 'GROUP') return selectedGroup.value?.name || t('mine.common.empty');
  if (publishForm.scopeType === 'DEVICE') return selectedDevice.value?.name || t('mine.common.empty');
  return products.value.find(item => item.id === productId.value)?.name || t('mine.common.empty');
});
const onlinePreview = computed(() => {
  const current = onlineCurrentThreshold.value;
  if (current === null) return { known: false, threshold: null };
  const source = effective.value?.sources.offlineThresholdSeconds;
  if (publishForm.scopeType === 'DEVICE') {
    return { known: Boolean(selectedDeviceId.value), threshold: onlineNextThreshold.value };
  }
  if (publishForm.scopeType === 'PRODUCT') {
    return source?.scopeType && source.scopeType !== 'PRODUCT'
      ? { known: true, threshold: current }
      : { known: true, threshold: onlineNextThreshold.value };
  }
  if (!selectedGroup.value || !selectedDeviceId.value || !savedMemberIds.value.includes(selectedDeviceId.value)) {
    return { known: true, threshold: current };
  }
  if (source?.scopeType === 'DEVICE') return { known: true, threshold: current };
  if (source?.scopeType === 'GROUP' && source.scopeId !== selectedGroup.value.id) {
    const sourcePriority = source.groupPriority ?? 0;
    if (sourcePriority > selectedGroup.value.priority) return { known: true, threshold: current };
    if (sourcePriority === selectedGroup.value.priority) return { known: false, threshold: null };
  }
  return { known: true, threshold: onlineNextThreshold.value };
});
const onlineChangeType = computed(() => {
  if (!onlinePreview.value.known || onlinePreview.value.threshold === null) return 'unknown';
  return onlineCurrentThreshold.value === onlinePreview.value.threshold ? 'unchanged' : 'changed';
});
const workflowStep = computed(() => {
  if (!productId.value) return 1;
  if (!selectedVersion.value || onlineDraftDirty.value) return 2;
  return publishForm.reason.trim().length >= 4 ? 4 : 3;
});
const retentionDraftDirty = computed(() => {
  if (!isRetention.value || selectedVersion.value?.status !== 'DRAFT') return false;
  const saved = retentionPolicyFrom(selectedVersion.value.values);
  const current = draftForm.retentionPolicy;
  return (
    saved.enabled !== current.enabled ||
    saved.retentionClass !== current.retentionClass ||
    saved.rawTelemetryDays !== current.rawTelemetryDays ||
    saved.qualityEvidenceDays !== current.qualityEvidenceDays
  );
});
const publishScopeId = computed(() => {
  if (publishForm.scopeType === 'PRODUCT') return productId.value;
  if (publishForm.scopeType === 'GROUP') return selectedGroupId.value;
  return selectedDeviceId.value;
});

watch(
  () => [strategyType.value, selectedVersionId.value, publishForm.scopeType, publishScopeId.value, publishForm.reason],
  () => {
    publishIdempotencyKey.value = null;
    publishProblem.value = null;
  }
);

watch(
  () => rollbackReason.value,
  () => {
    retentionRollbackPreview.value = null;
  }
);

watch(
  () => selectedGroupId.value,
  () => {
    retentionGroupChangePreview.value = null;
  }
);

watch(
  () => memberIds.value,
  () => {
    if (retentionGroupChangePreview.value?.changeType === 'MEMBERSHIP') {
      retentionGroupChangePreview.value = null;
    }
  },
  { deep: true }
);

watch(
  () => groupForm.priority,
  () => {
    if (retentionGroupChangePreview.value?.changeType === 'PRIORITY') {
      retentionGroupChangePreview.value = null;
    }
  }
);

watch(
  () =>
    releases.value
      .map(release =>
        [release.id, release.productId, release.strategyVersionId, release.scopeType, release.scopeId].join(':')
      )
      .join('|'),
  () => {
    retentionRollbackPreview.value = null;
  }
);

watch(
  () => [
    strategyType.value,
    selectedVersionId.value,
    publishForm.scopeType,
    publishScopeId.value,
    draftForm.retentionPolicy
  ],
  () => {
    retentionPreview.value = null;
  },
  { deep: true }
);

onMounted(loadInitialData);

async function loadInitialData() {
  const jobs: Promise<unknown>[] = [loadProducts()];
  if (isRetention.value) jobs.push(loadRetentionExecutions());
  await Promise.allSettled(jobs);
}

async function loadProducts() {
  loading.value = true;
  operationProblem.value = null;
  try {
    const response = await getProducts({ page: 1, size: 100 });
    products.value = response.items.filter(item => item.status === 'ACTIVE');
    productId.value = props.fixedProductId || products.value[0]?.id || null;
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
  savedMemberIds.value = [];
  effective.value = null;
  resetGroupForm();
  resetDraftForm();
  await loadProductContext();
}

async function onStrategyTypeChanged() {
  selectedVersionId.value = null;
  effective.value = null;
  operationProblem.value = null;
  resetDraftForm();
  const jobs: Promise<unknown>[] = [loadStrategyData(), loadEffective()];
  if (isRetention.value) jobs.push(loadRetentionExecutions());
  await Promise.all(jobs);
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
  const response = await listDeviceGroups({
    productId: productId.value,
    status: 'ACTIVE',
    page: 1,
    size: 100
  });
  groups.value = response.items;
  selectedGroupId.value = groups.value.some(item => item.id === (preferredId || selectedGroupId.value))
    ? preferredId || selectedGroupId.value
    : groups.value[0]?.id || null;
  await loadMembers();
}

async function loadDevices() {
  if (!productId.value) return;
  const [enabled, disabled] = await Promise.all([
    getDevices({ productId: productId.value, status: 'ENABLED', page: 1, size: 100 }),
    getDevices({ productId: productId.value, status: 'DISABLED', page: 1, size: 100 })
  ]);
  deviceTotal.value = enabled.total + disabled.total;
  devices.value = [...new Map([...enabled.items, ...disabled.items].map(item => [item.id, item])).values()];
  selectedDeviceId.value = devices.value.some(item => item.id === selectedDeviceId.value)
    ? selectedDeviceId.value
    : devices.value[0]?.id || null;
  await loadEffective();
}

async function loadStrategyData() {
  if (!productId.value) return;
  const [nextVersions, nextReleases] = await Promise.all([
    listStrategyVersions({
      productId: productId.value,
      strategyType: strategyType.value,
      limit: 50
    }),
    listStrategyReleases({
      productId: productId.value,
      strategyType: strategyType.value,
      limit: 50
    })
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
    savedMemberIds.value = [];
    return;
  }
  const members = await listDeviceGroupMembers(selectedGroupId.value);
  const nextMemberIds = members.map(item => item.id);
  memberIds.value = [...nextMemberIds];
  savedMemberIds.value = nextMemberIds;
}

async function loadEffective() {
  if (!selectedDeviceId.value) {
    effective.value = null;
    return;
  }
  effectiveLoading.value = true;
  try {
    effective.value = await getEffectiveStrategy(selectedDeviceId.value, strategyType.value);
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
      const preview =
        groupForm.priority === selectedGroup.value.priority
          ? undefined
          : await prepareRetentionGroupChange({
              groupId: selectedGroup.value.id,
              changeType: 'PRIORITY',
              groupVersion: selectedGroup.value.version,
              nextPriority: groupForm.priority
            });
      if (preview === null) return;
      saved = await updateDeviceGroup(selectedGroup.value.id, {
        name: groupForm.name.trim(),
        priority: groupForm.priority,
        description: groupForm.description.trim() || null,
        version: selectedGroup.value.version,
        ...(preview ? { retentionPreviewToken: preview.previewToken } : {})
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
    retentionGroupChangePreview.value = null;
    await loadGroups(saved.id);
    startEditGroup();
  } catch (error) {
    retentionGroupChangePreview.value = null;
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
    const preview = await prepareRetentionGroupChange({
      groupId: selectedGroup.value.id,
      changeType: 'MEMBERSHIP',
      groupVersion: selectedGroup.value.version,
      deviceIds: [...memberIds.value].sort()
    });
    if (preview === null) return;
    await replaceDeviceGroupMembers(selectedGroup.value.id, {
      deviceIds: memberIds.value,
      version: selectedGroup.value.version,
      ...(preview ? { retentionPreviewToken: preview.previewToken } : {})
    });
    retentionGroupChangePreview.value = null;
    window.$message?.success(t('mine.strategy.message.membersUpdated'));
    await loadGroups(selectedGroup.value.id);
    await loadEffective();
  } catch (error) {
    retentionGroupChangePreview.value = null;
    captureProblem(error);
  } finally {
    savingMembers.value = false;
  }
}

async function archiveSelectedGroup() {
  if (!selectedGroup.value) return;
  operationProblem.value = null;
  try {
    const preview = await prepareRetentionGroupChange({
      groupId: selectedGroup.value.id,
      changeType: 'GROUP_ARCHIVE',
      groupVersion: selectedGroup.value.version
    });
    if (preview === null) return;
    if (!preview && !window.confirm(t('mine.strategy.confirm.archiveGroup'))) return;
    await archiveDeviceGroup(selectedGroup.value.id, selectedGroup.value.version, preview?.previewToken);
    retentionGroupChangePreview.value = null;
    window.$message?.success(t('mine.strategy.message.groupArchived'));
    selectedGroupId.value = null;
    await loadGroups();
    resetGroupForm();
    await loadEffective();
  } catch (error) {
    retentionGroupChangePreview.value = null;
    captureProblem(error);
  }
}

async function prepareRetentionGroupChange(
  request: RetentionGroupChangePreviewRequest
): Promise<RetentionPreview | null | undefined> {
  if (!can('device:strategy:publish')) return undefined;
  const normalizedRequest = {
    ...request,
    ...(request.deviceIds ? { deviceIds: [...request.deviceIds].sort() } : {})
  };
  const key = JSON.stringify(normalizedRequest);
  const current = retentionGroupChangePreview.value;
  if (current?.key === key) {
    const preview = current.preview;
    if (
      !window.confirm(
        t('mine.strategy.retention.groupChangeConfirm', {
          action: retentionGroupChangeAction(request.changeType),
          observedAt: preview.observedAt,
          target: formatNumber(preview.targetDeviceCount),
          affected: formatNumber(preview.affectedDeviceCount),
          enabled: formatNumber(preview.enabledDeviceCount),
          raw: eligibleRowsText(preview.rawTelemetry),
          quality: eligibleRowsText(preview.qualityEvidence)
        })
      )
    ) {
      return null;
    }
    return preview;
  }
  retentionGroupChangePreview.value = null;
  const result = await previewRetentionGroupChange(normalizedRequest);
  if (!result.required) return undefined;
  if (!result.preview) throw new Error('RETENTION_GROUP_CHANGE_PREVIEW_MISSING');
  retentionGroupChangePreview.value = {
    key,
    changeType: request.changeType,
    preview: result.preview
  };
  window.$message?.success(t('mine.strategy.retention.groupChangePreviewLoaded'));
  return null;
}

function retentionGroupChangeAction(changeType: RetentionGroupChangePreviewRequest['changeType']) {
  if (changeType === 'MEMBERSHIP') return t('mine.strategy.action.saveMembers');
  if (changeType === 'PRIORITY') return t('mine.strategy.action.saveGroup');
  return t('mine.strategy.action.archiveGroup');
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
  resetDraftForm();
  draftForm.description = version.description || '';
  if (version.strategyType === 'ONLINE_STATUS') {
    draftForm.threshold = numericValue(version.values, 'offlineThresholdSeconds') ?? 180;
    return;
  }
  if (version.strategyType === 'ALARM') {
    draftForm.alarmRules = alarmRulesFrom(version.values);
    return;
  }
  if (version.strategyType === 'RETENTION') {
    draftForm.retentionPolicy = retentionPolicyFrom(version.values);
    return;
  }
  if (version.strategyType === 'AGGREGATION') {
    draftForm.aggregationPolicy = aggregationPolicyFrom(version.values);
    return;
  }
  draftForm.staleAfterSeconds = numericValue(version.values, 'staleAfterSeconds');
  draftForm.futureToleranceSeconds = numericValue(version.values, 'futureToleranceSeconds');
  draftForm.qualityRules = qualityRulesFrom(version.values);
}

async function saveDraft() {
  if (!productId.value) return;
  const values = strategyValues();
  if (!values) return;
  savingDraft.value = true;
  operationProblem.value = null;
  try {
    let saved: StrategyVersion;
    if (draftMode.value === 'edit' && selectedVersion.value?.status === 'DRAFT') {
      saved = await updateStrategyDraft(selectedVersion.value.id, {
        values,
        description: draftForm.description.trim() || null,
        version: selectedVersion.value.version
      });
      window.$message?.success(t('mine.strategy.message.draftUpdated'));
    } else {
      saved = await createStrategyDraft({
        productId: productId.value,
        strategyType: strategyType.value,
        values,
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
  if (onlineDraftDirty.value) {
    return window.$message?.warning(t('mine.strategy.validation.onlineSaveFirst'));
  }
  if (isRetention.value) {
    if (retentionDraftDirty.value) {
      return window.$message?.warning(t('mine.strategy.validation.retentionSaveFirst'));
    }
    if (!retentionPreview.value) {
      return window.$message?.warning(t('mine.strategy.validation.retentionPreview'));
    }
    if (
      !window.confirm(
        t('mine.strategy.confirm.retentionPublish', {
          observedAt: retentionPreview.value.observedAt
        })
      )
    ) {
      return;
    }
  }
  publishing.value = true;
  publishProblem.value = null;
  publishIdempotencyKey.value ||= createIdempotencyKey('publish');
  try {
    await publishStrategy(
      {
        strategyVersionId: selectedVersion.value.id,
        scopeType: publishForm.scopeType,
        scopeId: publishScopeId.value,
        reason: publishForm.reason.trim(),
        ...(isRetention.value && retentionPreview.value
          ? { retentionPreviewToken: retentionPreview.value.previewToken }
          : {})
      },
      publishIdempotencyKey.value
    );
    publishIdempotencyKey.value = null;
    publishForm.reason = '';
    window.$message?.success(t('mine.strategy.message.published'));
    await Promise.all([loadStrategyData(), loadEffective()]);
    if (isRetention.value) await loadRetentionExecutions();
  } catch (error) {
    if (isRetention.value) retentionPreview.value = null;
    capturePublishProblem(error);
  } finally {
    publishing.value = false;
  }
}

async function runRollback(release: StrategyRelease) {
  const reason = rollbackReason.value.trim();
  if (reason.length < 4) {
    return window.$message?.warning(t('mine.strategy.validation.reason'));
  }
  rollingBackId.value = release.id;
  operationProblem.value = null;
  let submittingRollback = false;
  try {
    let previewToken: string | null = null;
    if (isRetention.value) {
      retentionProblem.value = null;
      const currentRollbackPreview = retentionRollbackPreview.value;
      if (!currentRollbackPreview || !isRetentionRollbackPreviewCurrent(release, reason)) {
        retentionRollbackPreview.value = null;
        const preview = await previewRetentionImpact({
          productId: release.productId,
          strategyVersionId: release.strategyVersionId,
          scopeType: release.scopeType,
          scopeId: release.scopeId
        });
        retentionRollbackPreview.value = {
          releaseId: release.id,
          productId: release.productId,
          strategyVersionId: release.strategyVersionId,
          scopeType: release.scopeType,
          scopeId: release.scopeId,
          reason,
          preview
        };
        window.$message?.success(t('mine.strategy.message.retentionRollbackPreviewLoaded'));
        return;
      }
      const preview = currentRollbackPreview.preview;
      previewToken = preview.previewToken;
      if (
        !window.confirm(
          t('mine.strategy.confirm.retentionRollback', {
            observedAt: preview.observedAt,
            target: formatNumber(preview.targetDeviceCount),
            affected: formatNumber(preview.affectedDeviceCount),
            enabled: formatNumber(preview.enabledDeviceCount),
            raw: eligibleRowsText(preview.rawTelemetry),
            quality: eligibleRowsText(preview.qualityEvidence)
          })
        )
      ) {
        return;
      }
    } else if (!window.confirm(t('mine.strategy.confirm.rollback'))) {
      return;
    }
    const key = rollbackIdempotencyKeys.get(release.id) || createIdempotencyKey(`rollback-${release.id}`);
    rollbackIdempotencyKeys.set(release.id, key);
    submittingRollback = true;
    await rollbackStrategyRelease(
      release.id,
      {
        reason,
        ...(previewToken ? { retentionPreviewToken: previewToken } : {})
      },
      key
    );
    rollbackIdempotencyKeys.delete(release.id);
    rollbackReason.value = '';
    retentionRollbackPreview.value = null;
    window.$message?.success(t('mine.strategy.message.rolledBack'));
    await Promise.all([loadStrategyData(), loadEffective()]);
  } catch (error) {
    if (submittingRollback) retentionRollbackPreview.value = null;
    if (isRetention.value) captureRetentionProblem(error);
    else captureProblem(error);
  } finally {
    rollingBackId.value = null;
  }
}

function isRetentionRollbackPreviewCurrent(release: StrategyRelease, reason = rollbackReason.value.trim()) {
  const current = retentionRollbackPreview.value;
  return Boolean(
    current &&
    current.releaseId === release.id &&
    current.productId === release.productId &&
    current.strategyVersionId === release.strategyVersionId &&
    current.scopeType === release.scopeType &&
    current.scopeId === release.scopeId &&
    current.reason === reason
  );
}

async function loadRetentionPreview() {
  if (!productId.value || !publishScopeId.value) {
    return window.$message?.warning(t('mine.strategy.validation.scope'));
  }
  if (!selectedVersion.value || selectedVersion.value.status !== 'DRAFT') {
    return window.$message?.warning(t('mine.strategy.validation.draft'));
  }
  if (retentionDraftDirty.value) {
    return window.$message?.warning(t('mine.strategy.validation.retentionSaveFirst'));
  }
  retentionPreviewLoading.value = true;
  retentionProblem.value = null;
  retentionPreview.value = null;
  try {
    retentionPreview.value = await previewRetentionImpact({
      productId: productId.value,
      strategyVersionId: selectedVersion.value.id,
      scopeType: publishForm.scopeType,
      scopeId: publishScopeId.value
    });
    window.$message?.success(t('mine.strategy.message.retentionPreviewLoaded'));
  } catch (error) {
    captureRetentionProblem(error);
  } finally {
    retentionPreviewLoading.value = false;
  }
}

async function loadRetentionExecutions() {
  retentionExecutionsLoading.value = true;
  retentionProblem.value = null;
  try {
    retentionExecutions.value = await listRetentionExecutions(20);
  } catch (error) {
    retentionExecutions.value = [];
    captureRetentionProblem(error);
  } finally {
    retentionExecutionsLoading.value = false;
  }
}

function resetGroupForm() {
  Object.assign(groupForm, {
    groupKey: '',
    name: '',
    priority: 100,
    description: ''
  });
}

function resetDraftForm() {
  Object.assign(draftForm, {
    threshold: 180,
    staleAfterSeconds: 300,
    futureToleranceSeconds: 5,
    qualityRules: [],
    alarmRules: [],
    retentionPolicy: {
      enabled: true,
      retentionClass: 'STANDARD',
      rawTelemetryDays: 90,
      qualityEvidenceDays: 180
    },
    aggregationPolicy: {
      enabled: true,
      correctionWindowSeconds: 86400,
      rules: [{ propertyId: '', bucketSeconds: 300 }]
    },
    description: ''
  });
}

function addQualityRule() {
  draftForm.qualityRules.push({
    key: createIdempotencyKey('quality-rule'),
    propertyId: '',
    required: true,
    minimum: null,
    maximum: null
  });
}

function removeQualityRule(key: string) {
  draftForm.qualityRules = draftForm.qualityRules.filter(rule => rule.key !== key);
}

function strategyValues(): StrategyVersion['values'] | null {
  if (strategyType.value === 'ONLINE_STATUS') {
    if (draftForm.threshold < 30 || draftForm.threshold > 86400) {
      window.$message?.warning(t('mine.strategy.validation.threshold'));
      return null;
    }
    return { offlineThresholdSeconds: draftForm.threshold };
  }
  if (isRetention.value) {
    if (!retentionPolicyValid.value || !isRetentionPolicyValid(draftForm.retentionPolicy)) {
      window.$message?.warning(t('mine.strategy.validation.retentionPolicy'));
      return null;
    }
    return { policy: { ...draftForm.retentionPolicy } };
  }
  if (isAggregation.value) {
    if (!aggregationPolicyValid.value || !isAggregationPolicyValid(draftForm.aggregationPolicy)) {
      window.$message?.warning(t('mine.strategy.validation.aggregationPolicy'));
      return null;
    }
    return {
      policy: {
        enabled: draftForm.aggregationPolicy.enabled,
        correctionWindowSeconds: draftForm.aggregationPolicy.correctionWindowSeconds,
        rules: draftForm.aggregationPolicy.rules
          .map(rule => ({ propertyId: rule.propertyId.trim(), bucketSeconds: rule.bucketSeconds }))
          .sort(
            (left, right) => left.propertyId.localeCompare(right.propertyId) || left.bucketSeconds - right.bucketSeconds
          )
      }
    };
  }
  if (isAlarm.value) return alarmStrategyValues();
  const values: Record<string, boolean | number> = {};
  if (draftForm.staleAfterSeconds !== null) {
    if (draftForm.staleAfterSeconds < 1 || draftForm.staleAfterSeconds > 604800) {
      window.$message?.warning(t('mine.strategy.validation.stale'));
      return null;
    }
    values.staleAfterSeconds = draftForm.staleAfterSeconds;
  }
  if (draftForm.futureToleranceSeconds !== null) {
    if (draftForm.futureToleranceSeconds < 0 || draftForm.futureToleranceSeconds > 3600) {
      window.$message?.warning(t('mine.strategy.validation.future'));
      return null;
    }
    values.futureToleranceSeconds = draftForm.futureToleranceSeconds;
  }
  const propertyIds = new Set<string>();
  for (const rule of draftForm.qualityRules) {
    const propertyId = rule.propertyId.trim();
    if (!/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/.test(propertyId) || propertyIds.has(propertyId)) {
      window.$message?.warning(t('mine.strategy.validation.property'));
      return null;
    }
    if (rule.minimum !== null && rule.maximum !== null && rule.minimum > rule.maximum) {
      window.$message?.warning(t('mine.strategy.validation.range'));
      return null;
    }
    propertyIds.add(propertyId);
    values[`required.${propertyId}`] = rule.required;
    if (rule.minimum !== null) values[`minimum.${propertyId}`] = rule.minimum;
    if (rule.maximum !== null) values[`maximum.${propertyId}`] = rule.maximum;
  }
  if (!Object.keys(values).length) {
    window.$message?.warning(t('mine.strategy.validation.qualityRequired'));
    return null;
  }
  return values;
}

function alarmStrategyValues(): StrategyVersion['values'] | null {
  if (draftForm.alarmRules.length < 1 || draftForm.alarmRules.length > 100) {
    window.$message?.warning(t('mine.strategy.validation.alarmRuleCount'));
    return null;
  }
  const keys = new Set<string>();
  const normalized: AlarmRule[] = [];
  for (const rule of draftForm.alarmRules) {
    const key = rule.key.trim();
    const name = rule.name.trim();
    if (!/^[A-Za-z][A-Za-z0-9_.-]{0,63}$/.test(key) || keys.has(key) || !name || name.length > 100) {
      window.$message?.warning(t('mine.strategy.validation.alarmIdentity'));
      return null;
    }
    if (
      !Number.isInteger(rule.triggerDurationSeconds) ||
      rule.triggerDurationSeconds < 0 ||
      rule.triggerDurationSeconds > 86400 ||
      !Number.isInteger(rule.recoverDurationSeconds) ||
      rule.recoverDurationSeconds < 0 ||
      rule.recoverDurationSeconds > 86400 ||
      !Number.isInteger(rule.cooldownSeconds) ||
      rule.cooldownSeconds < 0 ||
      rule.cooldownSeconds > 604800
    ) {
      window.$message?.warning(t('mine.strategy.validation.alarmDuration'));
      return null;
    }
    keys.add(key);
    if (rule.sourceType === 'NUMERIC') {
      const propertyId = rule.propertyId.trim();
      const invalidHysteresis = ['GT', 'GTE'].includes(rule.operator)
        ? rule.recoverThreshold > rule.threshold
        : rule.recoverThreshold < rule.threshold;
      if (!/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/.test(propertyId)) {
        window.$message?.warning(t('mine.strategy.validation.property'));
        return null;
      }
      if (
        !Number.isFinite(rule.threshold) ||
        !Number.isFinite(rule.recoverThreshold) ||
        Math.abs(rule.threshold) > 1000000000000000 ||
        Math.abs(rule.recoverThreshold) > 1000000000000000 ||
        decimalScale(rule.threshold) > 12 ||
        decimalScale(rule.recoverThreshold) > 12 ||
        invalidHysteresis
      ) {
        window.$message?.warning(t('mine.strategy.validation.alarmHysteresis'));
        return null;
      }
      normalized.push({ ...rule, key, name, propertyId });
      continue;
    }
    const propertyId = rule.propertyId?.trim();
    if (propertyId && !/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/.test(propertyId)) {
      window.$message?.warning(t('mine.strategy.validation.property'));
      return null;
    }
    normalized.push({ ...rule, key, name, ...(propertyId ? { propertyId } : { propertyId: undefined }) });
  }
  return { rules: normalized };
}

function alarmRulesFrom(values: StrategyVersion['values']): AlarmRule[] {
  if (!('rules' in values) || !Array.isArray(values.rules)) return [];
  return values.rules.map(rule => ({ ...rule }));
}

function retentionPolicyFrom(values: StrategyVersion['values']): RetentionPolicy {
  if ('policy' in values && isRetentionPolicy(values.policy)) return { ...values.policy };
  return {
    enabled: true,
    retentionClass: 'STANDARD',
    rawTelemetryDays: 90,
    qualityEvidenceDays: 180
  };
}

function isRetentionPolicy(value: unknown): value is RetentionPolicy {
  if (!value || typeof value !== 'object') return false;
  const policy = value as Partial<RetentionPolicy>;
  return Boolean(
    typeof policy.enabled === 'boolean' &&
    ['SHORT', 'STANDARD', 'LONG', 'ARCHIVE'].includes(policy.retentionClass || '') &&
    typeof policy.rawTelemetryDays === 'number' &&
    typeof policy.qualityEvidenceDays === 'number'
  );
}

function isRetentionPolicyValid(policy: RetentionPolicy) {
  const ranges = {
    SHORT: [7, 30],
    STANDARD: [31, 180],
    LONG: [181, 730],
    ARCHIVE: [731, 3650]
  } as const;
  const [minimum, maximum] = ranges[policy.retentionClass];
  return (
    Number.isInteger(policy.rawTelemetryDays) &&
    policy.rawTelemetryDays >= minimum &&
    policy.rawTelemetryDays <= maximum &&
    Number.isInteger(policy.qualityEvidenceDays) &&
    policy.qualityEvidenceDays >= policy.rawTelemetryDays &&
    policy.qualityEvidenceDays <= 3650
  );
}

function aggregationPolicyFrom(values: StrategyVersion['values']): AggregationPolicy {
  if ('policy' in values && isAggregationPolicy(values.policy)) {
    return {
      enabled: values.policy.enabled,
      correctionWindowSeconds: values.policy.correctionWindowSeconds,
      rules: values.policy.rules.map(rule => ({ ...rule }))
    };
  }
  return {
    enabled: true,
    correctionWindowSeconds: 86400,
    rules: [{ propertyId: '', bucketSeconds: 300 }]
  };
}

function isAggregationPolicy(value: unknown): value is AggregationPolicy {
  if (!value || typeof value !== 'object') return false;
  const policy = value as Partial<AggregationPolicy>;
  return Boolean(
    typeof policy.enabled === 'boolean' &&
    typeof policy.correctionWindowSeconds === 'number' &&
    Array.isArray(policy.rules)
  );
}

function isAggregationPolicyValid(policy: AggregationPolicy) {
  const allowedBuckets = new Set([60, 300, 900, 3600, 21600, 86400]);
  if (
    !Number.isInteger(policy.correctionWindowSeconds) ||
    policy.correctionWindowSeconds < 300 ||
    policy.correctionWindowSeconds > 604800 ||
    policy.rules.length < 1 ||
    policy.rules.length > 100
  )
    return false;
  const minimumCorrection = Math.max(...policy.rules.map(rule => rule.bucketSeconds * 2));
  if (policy.correctionWindowSeconds < minimumCorrection) return false;
  const keys = new Set<string>();
  return policy.rules.every(rule => {
    const propertyId = rule.propertyId.trim();
    const key = `${propertyId}::${rule.bucketSeconds}`;
    if (!/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/.test(propertyId) || !allowedBuckets.has(rule.bucketSeconds) || keys.has(key))
      return false;
    keys.add(key);
    return true;
  });
}

function decimalScale(value: number) {
  const [coefficient = '', exponentText = '0'] = value.toString().toLowerCase().split('e');
  const fractionLength = coefficient.split('.')[1]?.length || 0;
  return Math.max(0, fractionLength - Number(exponentText));
}

function qualityRulesFrom(values: StrategyVersion['values']): QualityRuleForm[] {
  const properties = new Map<string, QualityRuleForm>();
  Object.entries(values).forEach(([field, value]) => {
    const match = /^(required|minimum|maximum)\.(.+)$/.exec(field);
    if (!match) return;
    const [, ruleType, propertyId] = match;
    if (!ruleType || !propertyId) return;
    const rule = properties.get(propertyId) || {
      key: createIdempotencyKey(`quality-rule-${propertyId}`),
      propertyId,
      required: false,
      minimum: null,
      maximum: null
    };
    if (ruleType === 'required') rule.required = value === true;
    if (ruleType === 'minimum' && typeof value === 'number') rule.minimum = value;
    if (ruleType === 'maximum' && typeof value === 'number') rule.maximum = value;
    properties.set(propertyId, rule);
  });
  return [...properties.values()].sort((left, right) => left.propertyId.localeCompare(right.propertyId));
}

function numericValue(values: StrategyVersion['values'], field: string) {
  const value = (values as Record<string, boolean | number>)[field];
  return typeof value === 'number' ? value : null;
}

function strategySummary(values: StrategyVersion['values']) {
  if ('rules' in values && Array.isArray(values.rules)) {
    return t('mine.strategy.text.alarmSummary', { count: values.rules.length });
  }
  if ('policy' in values && isAggregationPolicy(values.policy)) {
    return t('mine.strategy.text.aggregationSummary', {
      count: values.policy.rules.length,
      correction: values.policy.correctionWindowSeconds
    });
  }
  if ('policy' in values && isRetentionPolicy(values.policy)) {
    return t('mine.strategy.text.retentionSummary', {
      retentionClass: t(`mine.strategy.retention.class.${values.policy.retentionClass.toLowerCase()}`),
      rawDays: values.policy.rawTelemetryDays,
      qualityDays: values.policy.qualityEvidenceDays
    });
  }
  const threshold = numericValue(values, 'offlineThresholdSeconds');
  if (threshold !== null) {
    return t('mine.strategy.text.onlineSummary', {
      seconds: threshold
    });
  }
  const ruleCount = new Set(
    Object.keys(values)
      .map(field => /^(?:required|minimum|maximum)\.(.+)$/.exec(field)?.[1])
      .filter((propertyId): propertyId is string => Boolean(propertyId))
  ).size;
  return t('mine.strategy.text.qualitySummary', { count: ruleCount });
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
  operationProblem.value = {
    code: 'SYS-UNKNOWN-001',
    message: t('mine.common.operationFailed')
  };
  window.$message?.error(t('mine.common.operationFailed'));
}

function capturePublishProblem(error: unknown) {
  if (error instanceof ApiError) {
    publishProblem.value = {
      code: error.code,
      message: error.message,
      traceId: error.traceId,
      details: error.details
    };
    window.$message?.error(error.message);
    return;
  }
  publishProblem.value = {
    code: 'SYS-UNKNOWN-001',
    message: t('mine.common.operationFailed')
  };
  window.$message?.error(t('mine.common.operationFailed'));
}

function captureRetentionProblem(error: unknown) {
  if (error instanceof ApiError) {
    retentionProblem.value = {
      code: error.code,
      message: error.message,
      traceId: error.traceId,
      details: error.details
    };
    window.$message?.error(error.message);
    return;
  }
  retentionProblem.value = {
    code: 'SYS-UNKNOWN-001',
    message: t('mine.common.operationFailed')
  };
  window.$message?.error(t('mine.common.operationFailed'));
}

function formatNumber(value: number) {
  return new Intl.NumberFormat(locale.value).format(value);
}

function formatDetails(details: Record<string, unknown>) {
  return Object.entries(details)
    .map(([key, value]) => `${key}: ${formatDetailValue(value)}`)
    .join(' · ');
}

function formatDetailValue(value: unknown): string {
  if (Array.isArray(value)) return value.map(formatDetailValue).join(', ');
  if (value && typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>)
      .map(([key, nested]) => `${key}=${formatDetailValue(nested)}`)
      .join(', ');
  }
  return String(value ?? '');
}

function eligibleRowsText(impact: RetentionPreview['rawTelemetry']) {
  return t(impact.atLeast ? 'mine.strategy.text.eligibleRowsAtLeast' : 'mine.strategy.text.eligibleRows', {
    count: formatNumber(impact.eligibleRows)
  });
}

function executionStatusLabel(status: RetentionExecution['status']) {
  return t(`mine.strategy.retention.executionStatus.${status.toLowerCase()}`);
}

function executionStatusType(status: RetentionExecution['status']) {
  if (status === 'SUCCEEDED') return 'success' as const;
  if (status === 'FAILED') return 'error' as const;
  if (status === 'PARTIAL') return 'warning' as const;
  return 'info' as const;
}

function createIdempotencyKey(prefix: string) {
  const suffix = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `strategy-${prefix}-${suffix}`;
}
</script>

<template>
  <div>
    <NFlex vertical :size="16">
      <NPageHeader v-if="!props.embedded" :subtitle="pageSubtitle">
        <template #title>
          <NText
            strong
            :data-page-title="props.fixedStrategyType === 'RETENTION' ? 'retention-governance' : 'strategy-workbench'"
          >
            {{ pageTitle }}
          </NText>
        </template>
        <template #extra>
          <NTag type="primary" :bordered="false" round>{{ policyName }}</NTag>
        </template>
      </NPageHeader>

      <NAlert type="warning" :bordered="false">{{ productionHelp }}</NAlert>
      <NAlert
        v-if="!can('device:group:edit') && !can('device:strategy:edit') && !can('device:strategy:publish')"
        type="info"
        :bordered="false"
      >
        {{ t('mine.strategy.help.readonly') }}
      </NAlert>

      <NSpin v-if="strategyType === 'ONLINE_STATUS'" :show="loading">
        <NFlex vertical :size="16">
          <NSteps :current="workflowStep" size="small" data-section="strategy-steps">
            <NStep :title="t('mine.strategy.workflow.scope')" />
            <NStep :title="t('mine.strategy.workflow.rule')" />
            <NStep :title="t('mine.strategy.workflow.impact')" />
            <NStep :title="t('mine.strategy.workflow.publish')" />
          </NSteps>

          <NCard :bordered="false" size="small">
            <NGrid responsive="screen" cols="1 s:2 l:5" :x-gap="12" :y-gap="8">
              <NGi>
                <NFormItem :label="t('mine.strategy.field.strategyType')" :show-feedback="false">
                  <NSelect
                    v-model:value="strategyType"
                    data-field="strategy-type"
                    :options="strategyTypeOptions"
                    :placeholder="
                      t('mine.strategy.placeholder.select', { field: t('mine.strategy.field.strategyType') })
                    "
                    @update:value="onStrategyTypeChanged"
                  />
                </NFormItem>
              </NGi>
              <NGi v-if="!props.fixedProductId">
                <NFormItem :label="t('mine.strategy.field.product')" :show-feedback="false">
                  <NSelect
                    v-model:value="productId"
                    data-field="strategy-product"
                    filterable
                    :options="productOptions"
                    :placeholder="t('mine.strategy.placeholder.select', { field: t('mine.strategy.field.product') })"
                    @update:value="onProductChanged"
                  />
                </NFormItem>
              </NGi>
              <NGi>
                <NFormItem :label="t('mine.strategy.field.scope')" :show-feedback="false">
                  <NSelect
                    v-model:value="publishForm.scopeType"
                    data-field="publish-scope"
                    :options="scopeOptions"
                    :placeholder="t('mine.strategy.placeholder.select', { field: t('mine.strategy.field.scope') })"
                  />
                </NFormItem>
              </NGi>
              <NGi v-if="publishForm.scopeType === 'GROUP'">
                <NFormItem :label="t('mine.strategy.field.group')" :show-feedback="false">
                  <NSelect
                    v-model:value="selectedGroupId"
                    filterable
                    :options="groupOptions"
                    :placeholder="t('mine.strategy.placeholder.select', { field: t('mine.strategy.field.group') })"
                    @update:value="loadMembers"
                  />
                </NFormItem>
              </NGi>
              <NGi>
                <NFormItem
                  :label="
                    publishForm.scopeType === 'DEVICE'
                      ? t('mine.strategy.field.targetDevices')
                      : t('mine.strategy.field.device')
                  "
                  :show-feedback="false"
                >
                  <NSelect
                    v-model:value="selectedDeviceId"
                    filterable
                    :options="deviceOptions"
                    :placeholder="t('mine.strategy.placeholder.select', { field: t('mine.strategy.field.device') })"
                    @update:value="loadEffective"
                  />
                </NFormItem>
              </NGi>
            </NGrid>
          </NCard>

          <NGrid responsive="screen" cols="1 l:12" :x-gap="16" :y-gap="16">
            <NGi span="1 l:8" class="min-w-0">
              <NFlex vertical :size="16">
                <NCard
                  :title="t('mine.strategy.section.change')"
                  :bordered="false"
                  size="small"
                  data-section="strategy-change"
                >
                  <template #header-extra>
                    <NFlex align="center">
                      <NTag v-if="effective?.valid" type="success" :bordered="false" round>
                        {{ t('mine.strategy.status.currentEffective') }}
                      </NTag>
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
                    </NFlex>
                  </template>

                  <NFlex vertical :size="16">
                    <NFormItem :label="t('mine.strategy.field.version')" :show-feedback="false">
                      <NSelect
                        v-model:value="selectedVersionId"
                        :options="versionOptions"
                        :placeholder="
                          t('mine.strategy.placeholder.select', { field: t('mine.strategy.field.version') })
                        "
                        @update:value="syncDraftForm"
                      />
                    </NFormItem>

                    <NGrid responsive="screen" cols="1 s:11" :x-gap="12" :y-gap="12" class="items-stretch">
                      <NGi span="1 s:5">
                        <NCard embedded size="small" class="h-full">
                          <NFlex vertical :size="6">
                            <NText depth="3">{{ t('mine.strategy.text.currentEffective') }}</NText>
                            <NText class="text-3xl font-semibold">
                              {{ onlineCurrentThreshold ?? '—' }}
                              <NText depth="3">{{ t('mine.strategy.text.seconds') }}</NText>
                            </NText>
                          </NFlex>
                        </NCard>
                      </NGi>
                      <NGi span="1 s:1">
                        <NFlex justify="center" align="center" class="h-full">
                          <NText type="primary" class="text-2xl">→</NText>
                        </NFlex>
                      </NGi>
                      <NGi span="1 s:5">
                        <NCard embedded size="small" class="h-full">
                          <NFlex vertical :size="6">
                            <NText type="primary">{{ t('mine.strategy.text.pendingPublish') }}</NText>
                            <NInputNumber
                              v-model:value="draftForm.threshold"
                              data-field="online-threshold"
                              :min="30"
                              :max="86400"
                              :placeholder="
                                t('mine.strategy.placeholder.enter', { field: t('mine.strategy.field.threshold') })
                              "
                              :disabled="!can('device:strategy:edit') || selectedVersion?.status === 'PUBLISHED'"
                            >
                              <template #suffix>{{ t('mine.strategy.text.seconds') }}</template>
                            </NInputNumber>
                          </NFlex>
                        </NCard>
                      </NGi>
                    </NGrid>

                    <NAlert type="info" :bordered="false">
                      {{ t('mine.strategy.help.onlineMeaning', { duration: onlineDuration }) }}
                    </NAlert>

                    <NFormItem :label="t('mine.strategy.field.description')" :show-feedback="false">
                      <NInput
                        v-model:value="draftForm.description"
                        data-field="online-description"
                        type="textarea"
                        :disabled="!can('device:strategy:edit') || selectedVersion?.status === 'PUBLISHED'"
                        :autosize="{ minRows: 2, maxRows: 4 }"
                        :placeholder="
                          t('mine.strategy.placeholder.enter', { field: t('mine.strategy.field.description') })
                        "
                      />
                    </NFormItem>
                    <NFlex v-if="can('device:strategy:edit')" justify="end">
                      <NButton
                        type="primary"
                        secondary
                        :loading="savingDraft"
                        :disabled="selectedVersion?.status === 'PUBLISHED'"
                        data-action="save-draft"
                        @click="saveDraft"
                      >
                        {{ t('mine.strategy.action.saveDraft') }}
                      </NButton>
                    </NFlex>
                  </NFlex>
                </NCard>

                <NCard
                  :title="t('mine.strategy.section.impact')"
                  :bordered="false"
                  size="small"
                  data-section="strategy-impact"
                >
                  <NFlex vertical :size="14">
                    <NGrid responsive="screen" cols="1 s:3" :x-gap="12" :y-gap="12">
                      <NGi>
                        <NCard embedded size="small">
                          <NFlex vertical :size="4">
                            <NText depth="3">{{ t('mine.strategy.field.targetDevices') }}</NText>
                            <NText strong class="text-2xl">
                              {{
                                t('mine.strategy.text.affectedDeviceCount', { count: formatNumber(publishTargetCount) })
                              }}
                            </NText>
                          </NFlex>
                        </NCard>
                      </NGi>
                      <NGi>
                        <NCard embedded size="small">
                          <NFlex vertical :size="4">
                            <NText depth="3">{{ t('mine.strategy.field.device') }}</NText>
                            <NText strong>{{ selectedDevice?.name || t('mine.common.empty') }}</NText>
                          </NFlex>
                        </NCard>
                      </NGi>
                      <NGi>
                        <NCard embedded size="small">
                          <NFlex vertical :size="4">
                            <NText depth="3">{{ t('mine.strategy.field.status') }}</NText>
                            <NTag
                              :type="
                                onlineChangeType === 'changed'
                                  ? 'warning'
                                  : onlineChangeType === 'unchanged'
                                    ? 'success'
                                    : 'info'
                              "
                              :bordered="false"
                            >
                              {{
                                onlineChangeType === 'changed'
                                  ? t('mine.strategy.text.willChange')
                                  : onlineChangeType === 'unchanged'
                                    ? t('mine.strategy.text.unchanged')
                                    : t('mine.strategy.text.exactImpactPending')
                              }}
                            </NTag>
                          </NFlex>
                        </NCard>
                      </NGi>
                    </NGrid>

                    <NDescriptions :column="1" label-placement="left" bordered size="small">
                      <NDescriptionsItem :label="t('mine.strategy.field.device')">
                        {{ selectedDevice?.name || t('mine.common.empty') }}
                      </NDescriptionsItem>
                      <NDescriptionsItem :label="t('mine.strategy.text.currentRule')">
                        {{ onlineCurrentThreshold ?? '—' }} {{ t('mine.strategy.text.seconds') }}
                      </NDescriptionsItem>
                      <NDescriptionsItem :label="t('mine.strategy.text.publishedRule')">
                        {{ onlinePreview.threshold ?? '—' }} {{ t('mine.strategy.text.seconds') }}
                      </NDescriptionsItem>
                      <NDescriptionsItem :label="t('mine.strategy.field.source')">
                        {{
                          effective?.sources.offlineThresholdSeconds
                            ? scopeLabel(effective.sources.offlineThresholdSeconds.scopeType)
                            : t('mine.strategy.source.unknown')
                        }}
                      </NDescriptionsItem>
                    </NDescriptions>
                    <NAlert type="info" :bordered="false">{{ t('mine.strategy.help.impactLimited') }}</NAlert>
                  </NFlex>
                </NCard>
              </NFlex>
            </NGi>

            <NGi span="1 l:4" class="min-w-0">
              <NCard
                :title="t('mine.strategy.section.confirmation')"
                :bordered="false"
                size="small"
                data-section="strategy-publish"
              >
                <NFlex vertical :size="14">
                  <NDescriptions :column="1" label-placement="left" bordered size="small">
                    <NDescriptionsItem :label="t('mine.strategy.field.scope')">
                      {{
                        t('mine.strategy.text.publishObject', {
                          scope: scopeLabel(publishForm.scopeType),
                          name: publishTargetName
                        })
                      }}
                    </NDescriptionsItem>
                    <NDescriptionsItem :label="t('mine.strategy.field.targetDevices')">
                      {{ t('mine.strategy.text.targetDeviceCount', { count: formatNumber(publishTargetCount) }) }}
                    </NDescriptionsItem>
                    <NDescriptionsItem :label="t('mine.strategy.field.status')">
                      <NTag type="info" :bordered="false">{{ t('mine.strategy.status.publishCheck') }}</NTag>
                    </NDescriptionsItem>
                  </NDescriptions>
                  <NAlert v-if="!publishProblem" type="info" :bordered="false">
                    {{ t('mine.strategy.status.publishCheck') }}
                  </NAlert>
                  <NAlert
                    v-else
                    :title="
                      publishProblem.code === 'STRATEGY-409-001'
                        ? t('mine.strategy.error.conflictTitle')
                        : t('mine.strategy.error.title')
                    "
                    type="error"
                    :bordered="false"
                  >
                    <NFlex vertical :size="4">
                      <NText>{{ publishProblem.message }}</NText>
                      <NText code>{{ t('mine.strategy.error.code', { code: publishProblem.code }) }}</NText>
                      <NText v-if="publishProblem.traceId" depth="3">
                        {{ t('mine.common.traceId', { traceId: publishProblem.traceId }) }}
                      </NText>
                      <NText v-if="publishProblem.details" depth="3">
                        {{ t('mine.strategy.error.details', { details: formatDetails(publishProblem.details) }) }}
                      </NText>
                    </NFlex>
                  </NAlert>
                  <NFormItem :label="t('mine.strategy.field.reason')" :show-feedback="false">
                    <NInput
                      v-model:value="publishForm.reason"
                      type="textarea"
                      :autosize="{ minRows: 3, maxRows: 5 }"
                      :placeholder="t('mine.strategy.placeholder.enter', { field: t('mine.strategy.field.reason') })"
                      data-field="publish-reason"
                    />
                  </NFormItem>
                  <NButton
                    v-if="can('device:strategy:publish')"
                    block
                    type="primary"
                    size="large"
                    :loading="publishing"
                    data-action="publish-strategy"
                    @click="runPublish"
                  >
                    {{ t('mine.strategy.action.publish') }}
                  </NButton>
                </NFlex>
              </NCard>
            </NGi>
          </NGrid>

          <NCard :bordered="false" size="small" data-section="strategy-advanced">
            <NText depth="3">{{ t('mine.strategy.help.advanced') }}</NText>
            <NCollapse class="mt-3">
              <NCollapseItem
                v-if="can('device:group:view')"
                name="groups"
                :title="t('mine.strategy.section.groups')"
                data-section="group-management"
              >
                <NGrid responsive="screen" cols="1 m:2" :x-gap="16" :y-gap="16">
                  <NGi>
                    <NFlex vertical :size="10">
                      <NFlex justify="space-between" align="center">
                        <NText strong>{{ t('mine.strategy.section.groups') }}</NText>
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
                      </NFlex>
                      <NFlex v-if="groups.length" vertical :size="8">
                        <NButton
                          v-for="item in groups"
                          :key="item.id"
                          block
                          :type="item.id === selectedGroupId ? 'primary' : 'default'"
                          :secondary="item.id === selectedGroupId"
                          @click="selectGroup(item.id)"
                        >
                          <NFlex justify="space-between" class="w-full">
                            <NText>{{ item.name }}</NText>
                            <NText depth="3">{{ t('mine.strategy.text.priority', { priority: item.priority }) }}</NText>
                          </NFlex>
                        </NButton>
                      </NFlex>
                      <NEmpty v-else :description="t('mine.strategy.empty.groups')" />

                      <NForm v-if="can('device:group:edit')" label-placement="top">
                        <NGrid responsive="screen" cols="1 s:2" :x-gap="12">
                          <NGi>
                            <NFormItem :label="t('mine.strategy.field.groupKey')">
                              <NInput
                                v-model:value="groupForm.groupKey"
                                :disabled="groupMode === 'edit'"
                                :placeholder="
                                  t('mine.strategy.placeholder.enter', { field: t('mine.strategy.field.groupKey') })
                                "
                              />
                            </NFormItem>
                          </NGi>
                          <NGi>
                            <NFormItem :label="t('mine.strategy.field.groupName')">
                              <NInput
                                v-model:value="groupForm.name"
                                :placeholder="
                                  t('mine.strategy.placeholder.enter', { field: t('mine.strategy.field.groupName') })
                                "
                              />
                            </NFormItem>
                          </NGi>
                        </NGrid>
                        <NFormItem :label="t('mine.strategy.field.priority')">
                          <NInputNumber
                            v-model:value="groupForm.priority"
                            data-field="group-priority"
                            :min="0"
                            :max="1000"
                            :placeholder="
                              t('mine.strategy.placeholder.enter', { field: t('mine.strategy.field.priority') })
                            "
                          />
                        </NFormItem>
                        <NFormItem :label="t('mine.strategy.field.description')">
                          <NInput
                            v-model:value="groupForm.description"
                            type="textarea"
                            :placeholder="
                              t('mine.strategy.placeholder.enter', { field: t('mine.strategy.field.description') })
                            "
                          />
                        </NFormItem>
                        <NFlex justify="end">
                          <NButton
                            v-if="selectedGroup && groupMode !== 'edit'"
                            data-action="edit-group"
                            @click="startEditGroup"
                          >
                            {{ t('mine.strategy.action.editGroup') }}
                          </NButton>
                          <NButton type="primary" :loading="savingGroup" data-action="save-group" @click="saveGroup">
                            {{ t('mine.strategy.action.saveGroup') }}
                          </NButton>
                        </NFlex>
                      </NForm>
                    </NFlex>
                  </NGi>
                  <NGi>
                    <NFlex v-if="selectedGroup" vertical :size="10">
                      <NFlex justify="space-between" align="center">
                        <NText strong>{{ t('mine.strategy.section.members') }}</NText>
                        <NTag :bordered="false">
                          {{ t('mine.strategy.text.memberCount', { count: selectedGroup.memberCount }) }}
                        </NTag>
                      </NFlex>
                      <NAlert
                        v-if="retentionGroupChangePreview"
                        type="warning"
                        :data-group-change-preview="retentionGroupChangePreview.changeType"
                      >
                        {{
                          t('mine.strategy.retention.groupChangeSummary', {
                            action: retentionGroupChangeAction(retentionGroupChangePreview.changeType),
                            observedAt: retentionGroupChangePreview.preview.observedAt,
                            target: formatNumber(retentionGroupChangePreview.preview.targetDeviceCount),
                            affected: formatNumber(retentionGroupChangePreview.preview.affectedDeviceCount),
                            enabled: formatNumber(retentionGroupChangePreview.preview.enabledDeviceCount)
                          })
                        }}
                      </NAlert>
                      <NSelect
                        v-model:value="memberIds"
                        data-field="group-members"
                        multiple
                        filterable
                        :disabled="!can('device:group:edit')"
                        :options="memberOptions"
                        :placeholder="
                          t('mine.strategy.placeholder.select', { field: t('mine.strategy.field.members') })
                        "
                      />
                      <NFlex v-if="can('device:group:edit')" justify="space-between">
                        <NButton type="error" secondary data-action="archive-group" @click="archiveSelectedGroup">
                          {{ t('mine.strategy.action.archiveGroup') }}
                        </NButton>
                        <NButton
                          type="primary"
                          :loading="savingMembers"
                          data-action="save-members"
                          @click="saveMembers"
                        >
                          {{ t('mine.strategy.action.saveMembers') }}
                        </NButton>
                      </NFlex>
                    </NFlex>
                  </NGi>
                </NGrid>
              </NCollapseItem>

              <NCollapseItem name="effective" :title="t('mine.strategy.section.effective')">
                <EffectiveStrategyPanel
                  :device-name="selectedDevice?.name"
                  :loading="effectiveLoading"
                  :effective="effective"
                  :problem="operationProblem"
                  @refresh="loadEffective"
                />
              </NCollapseItem>

              <NCollapseItem name="history" :title="t('mine.strategy.section.history')">
                <NFlex v-if="can('device:strategy:publish') && releases.length" vertical :size="8">
                  <NInput
                    v-model:value="rollbackReason"
                    type="textarea"
                    :placeholder="t('mine.strategy.placeholder.enter', { field: t('mine.strategy.field.reason') })"
                    data-field="rollback-reason"
                  />
                </NFlex>
                <NList v-if="releases.length" hoverable>
                  <NListItem v-for="item in releases" :key="item.id">
                    <NFlex justify="space-between" align="center" :wrap="true" class="w-full">
                      <NFlex vertical :size="3">
                        <NText strong>{{ t('mine.strategy.text.sequence', { sequence: item.releaseSequence }) }}</NText>
                        <NText depth="3">
                          {{ scopeLabel(item.scopeType) }} · {{ releaseActionLabel(item.action) }}
                        </NText>
                      </NFlex>
                      <NButton
                        v-if="can('device:strategy:publish')"
                        secondary
                        type="warning"
                        size="small"
                        :loading="rollingBackId === item.id"
                        data-action="rollback-strategy"
                        @click="runRollback(item)"
                      >
                        {{ t('mine.strategy.action.rollback') }}
                      </NButton>
                    </NFlex>
                  </NListItem>
                </NList>
                <NEmpty v-else :description="t('mine.strategy.empty.releases')" />
              </NCollapseItem>
            </NCollapse>
          </NCard>
        </NFlex>
      </NSpin>

      <NSpin v-else :show="loading">
        <NGrid responsive="screen" cols="1 m:12 xl:12" :x-gap="16" :y-gap="16">
          <NGi span="1 m:4 xl:3" class="min-w-0">
            <NFlex vertical :size="16">
              <NCard :title="t('mine.strategy.section.context')" :bordered="false" size="small">
                <NForm label-placement="top">
                  <NFormItem v-if="!props.fixedStrategyType" :label="t('mine.strategy.field.strategyType')">
                    <NSelect
                      v-model:value="strategyType"
                      data-field="strategy-type"
                      :options="strategyTypeOptions"
                      :placeholder="
                        t('mine.strategy.placeholder.select', {
                          field: t('mine.strategy.field.strategyType')
                        })
                      "
                      @update:value="onStrategyTypeChanged"
                    />
                  </NFormItem>
                  <NFormItem v-if="!props.fixedProductId" :label="t('mine.strategy.field.product')">
                    <NSelect
                      v-model:value="productId"
                      data-field="strategy-product"
                      filterable
                      :options="productOptions"
                      :placeholder="
                        t('mine.strategy.placeholder.select', {
                          field: t('mine.strategy.field.product')
                        })
                      "
                      @update:value="onProductChanged"
                    />
                  </NFormItem>
                  <NFormItem :label="t('mine.strategy.field.device')">
                    <NSelect
                      v-model:value="selectedDeviceId"
                      filterable
                      :options="deviceOptions"
                      :placeholder="
                        t('mine.strategy.placeholder.select', {
                          field: t('mine.strategy.field.device')
                        })
                      "
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
                      <NText depth="3">
                        {{
                          t('mine.strategy.text.priority', {
                            priority: group.priority
                          })
                        }}
                      </NText>
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
                              t('mine.strategy.placeholder.enter', {
                                field: t('mine.strategy.field.groupKey')
                              })
                            "
                          />
                        </NFormItem>
                        <NFormItem :label="t('mine.strategy.field.groupName')">
                          <NInput
                            v-model:value="groupForm.name"
                            :placeholder="
                              t('mine.strategy.placeholder.enter', {
                                field: t('mine.strategy.field.groupName')
                              })
                            "
                          />
                        </NFormItem>
                        <NFormItem :label="t('mine.strategy.field.priority')">
                          <NInputNumber
                            v-model:value="groupForm.priority"
                            data-field="group-priority"
                            :min="0"
                            :max="1000"
                            :placeholder="
                              t('mine.strategy.placeholder.enter', {
                                field: t('mine.strategy.field.priority')
                              })
                            "
                          />
                        </NFormItem>
                        <NFormItem :label="t('mine.strategy.field.description')">
                          <NInput
                            v-model:value="groupForm.description"
                            type="textarea"
                            :autosize="{ minRows: 2, maxRows: 4 }"
                            :placeholder="
                              t('mine.strategy.placeholder.enter', {
                                field: t('mine.strategy.field.description')
                              })
                            "
                          />
                        </NFormItem>
                        <NText depth="3">{{ t('mine.strategy.help.groupPriority') }}</NText>
                        <NFlex justify="end">
                          <NButton
                            v-if="selectedGroup && groupMode !== 'edit'"
                            data-action="edit-group"
                            @click="startEditGroup"
                          >
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
                      {{
                        t('mine.strategy.text.memberCount', {
                          count: selectedGroup.memberCount
                        })
                      }}
                    </NTag>
                  </NFlex>
                  <NAlert
                    v-if="retentionGroupChangePreview"
                    type="warning"
                    :title="t('mine.strategy.retention.groupChangeImpactTitle')"
                    :data-group-change-preview="retentionGroupChangePreview.changeType"
                  >
                    <NFlex vertical :size="6">
                      <NText>
                        {{
                          t('mine.strategy.retention.groupChangeSummary', {
                            action: retentionGroupChangeAction(retentionGroupChangePreview.changeType),
                            observedAt: retentionGroupChangePreview.preview.observedAt,
                            target: formatNumber(retentionGroupChangePreview.preview.targetDeviceCount),
                            affected: formatNumber(retentionGroupChangePreview.preview.affectedDeviceCount),
                            enabled: formatNumber(retentionGroupChangePreview.preview.enabledDeviceCount)
                          })
                        }}
                      </NText>
                      <NText depth="3">
                        {{ eligibleRowsText(retentionGroupChangePreview.preview.rawTelemetry) }} ·
                        {{ eligibleRowsText(retentionGroupChangePreview.preview.qualityEvidence) }}
                      </NText>
                    </NFlex>
                  </NAlert>
                  <NSelect
                    v-model:value="memberIds"
                    data-field="group-members"
                    multiple
                    filterable
                    :disabled="!can('device:group:edit')"
                    :options="memberOptions"
                    :placeholder="
                      t('mine.strategy.placeholder.select', {
                        field: t('mine.strategy.field.members')
                      })
                    "
                  />
                  <NText depth="3">{{ t('mine.strategy.help.members') }}</NText>
                  <NFlex v-if="can('device:group:edit')" justify="space-between">
                    <NButton type="error" secondary data-action="archive-group" @click="archiveSelectedGroup">
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
                      :placeholder="
                        t('mine.strategy.placeholder.select', {
                          field: t('mine.strategy.field.version')
                        })
                      "
                      @update:value="syncDraftForm"
                    />
                  </NFormItem>
                  <NGrid
                    v-if="!isDataQuality && !isAlarm && !isRetention && !isAggregation"
                    responsive="screen"
                    cols="1 s:2"
                    :x-gap="12"
                  >
                    <NGi>
                      <NFormItem :label="t('mine.strategy.field.threshold')">
                        <NInputNumber
                          v-model:value="draftForm.threshold"
                          :min="30"
                          :max="86400"
                          :disabled="!can('device:strategy:edit') || selectedVersion?.status === 'PUBLISHED'"
                          :placeholder="
                            t('mine.strategy.placeholder.enter', {
                              field: t('mine.strategy.field.threshold')
                            })
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
                  <template v-else-if="isRetention">
                    <RetentionPolicyEditor
                      v-model="draftForm.retentionPolicy"
                      :readonly="!can('device:strategy:edit') || selectedVersion?.status === 'PUBLISHED'"
                      @validity-change="retentionPolicyValid = $event"
                    />
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
                  </template>
                  <template v-else-if="isAlarm">
                    <NAlert type="info" :bordered="false">{{ t('mine.strategy.help.alarmTemplate') }}</NAlert>
                    <AlarmRuleEditor
                      v-model="draftForm.alarmRules"
                      :readonly="!can('device:strategy:edit') || selectedVersion?.status === 'PUBLISHED'"
                    />
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
                  </template>
                  <template v-else-if="isAggregation">
                    <AggregationPolicyEditor
                      v-model="draftForm.aggregationPolicy"
                      :readonly="!can('device:strategy:edit') || selectedVersion?.status === 'PUBLISHED'"
                      @validity-change="aggregationPolicyValid = $event"
                    />
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
                  </template>
                  <template v-else>
                    <NAlert type="info" :bordered="false">{{ t('mine.strategy.help.qualityTemplate') }}</NAlert>
                    <NGrid responsive="screen" cols="1 s:2" :x-gap="12">
                      <NGi>
                        <NFormItem :label="t('mine.strategy.field.staleAfterSeconds')">
                          <NInputNumber
                            v-model:value="draftForm.staleAfterSeconds"
                            data-field="quality-stale"
                            clearable
                            :min="1"
                            :max="604800"
                            :placeholder="
                              t('mine.strategy.placeholder.enter', {
                                field: t('mine.strategy.field.staleAfterSeconds')
                              })
                            "
                            :disabled="!can('device:strategy:edit') || selectedVersion?.status === 'PUBLISHED'"
                          >
                            <template #suffix>{{ t('mine.strategy.text.seconds') }}</template>
                          </NInputNumber>
                        </NFormItem>
                      </NGi>
                      <NGi>
                        <NFormItem :label="t('mine.strategy.field.futureToleranceSeconds')">
                          <NInputNumber
                            v-model:value="draftForm.futureToleranceSeconds"
                            data-field="quality-future"
                            clearable
                            :min="0"
                            :max="3600"
                            :placeholder="
                              t('mine.strategy.placeholder.enter', {
                                field: t('mine.strategy.field.futureToleranceSeconds')
                              })
                            "
                            :disabled="!can('device:strategy:edit') || selectedVersion?.status === 'PUBLISHED'"
                          >
                            <template #suffix>{{ t('mine.strategy.text.seconds') }}</template>
                          </NInputNumber>
                        </NFormItem>
                      </NGi>
                    </NGrid>
                    <NFlex justify="space-between" align="center" :wrap="true">
                      <NFlex vertical :size="2">
                        <NText strong>{{ t('mine.strategy.section.qualityRules') }}</NText>
                        <NText depth="3">{{ t('mine.strategy.help.qualityRules') }}</NText>
                      </NFlex>
                      <NButton
                        v-if="can('device:strategy:edit') && selectedVersion?.status !== 'PUBLISHED'"
                        secondary
                        type="primary"
                        size="small"
                        data-action="add-quality-rule"
                        @click="addQualityRule"
                      >
                        {{ t('mine.strategy.action.addQualityRule') }}
                      </NButton>
                    </NFlex>
                    <NList v-if="draftForm.qualityRules.length" bordered>
                      <NListItem v-for="rule in draftForm.qualityRules" :key="rule.key">
                        <NGrid responsive="screen" cols="1 s:12" :x-gap="10" :y-gap="8" class="w-full">
                          <NGi span="1 s:4">
                            <NFormItem :label="t('mine.strategy.field.propertyId')" :show-feedback="false">
                              <NInput
                                v-model:value="rule.propertyId"
                                data-field="quality-property"
                                :disabled="!can('device:strategy:edit') || selectedVersion?.status === 'PUBLISHED'"
                                :placeholder="t('mine.strategy.placeholder.propertyId')"
                              />
                            </NFormItem>
                          </NGi>
                          <NGi span="1 s:2">
                            <NFormItem :label="t('mine.strategy.field.required')" :show-feedback="false">
                              <NCheckbox
                                v-model:checked="rule.required"
                                :disabled="!can('device:strategy:edit') || selectedVersion?.status === 'PUBLISHED'"
                              >
                                {{ t('mine.strategy.text.enabled') }}
                              </NCheckbox>
                            </NFormItem>
                          </NGi>
                          <NGi span="1 s:2">
                            <NFormItem :label="t('mine.strategy.field.minimum')" :show-feedback="false">
                              <NInputNumber
                                v-model:value="rule.minimum"
                                clearable
                                :placeholder="
                                  t('mine.strategy.placeholder.enter', {
                                    field: t('mine.strategy.field.minimum')
                                  })
                                "
                                :disabled="!can('device:strategy:edit') || selectedVersion?.status === 'PUBLISHED'"
                              />
                            </NFormItem>
                          </NGi>
                          <NGi span="1 s:2">
                            <NFormItem :label="t('mine.strategy.field.maximum')" :show-feedback="false">
                              <NInputNumber
                                v-model:value="rule.maximum"
                                clearable
                                :placeholder="
                                  t('mine.strategy.placeholder.enter', {
                                    field: t('mine.strategy.field.maximum')
                                  })
                                "
                                :disabled="!can('device:strategy:edit') || selectedVersion?.status === 'PUBLISHED'"
                              />
                            </NFormItem>
                          </NGi>
                          <NGi span="1 s:2">
                            <NFlex justify="end" align="end" class="h-full">
                              <NButton
                                v-if="can('device:strategy:edit') && selectedVersion?.status !== 'PUBLISHED'"
                                quaternary
                                type="error"
                                size="small"
                                @click="removeQualityRule(rule.key)"
                              >
                                {{ t('mine.strategy.action.removeQualityRule') }}
                              </NButton>
                            </NFlex>
                          </NGi>
                        </NGrid>
                      </NListItem>
                    </NList>
                    <NEmpty v-else :description="t('mine.strategy.empty.qualityRules')" />
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
                  </template>
                  <NFormItem :label="t('mine.strategy.field.description')">
                    <NInput
                      v-model:value="draftForm.description"
                      type="textarea"
                      :disabled="!can('device:strategy:edit') || selectedVersion?.status === 'PUBLISHED'"
                      :autosize="{ minRows: 2, maxRows: 4 }"
                      :placeholder="
                        t('mine.strategy.placeholder.enter', {
                          field: t('mine.strategy.field.description')
                        })
                      "
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
                      :placeholder="
                        t('mine.strategy.placeholder.select', {
                          field: t('mine.strategy.field.scope')
                        })
                      "
                    />
                  </NFormItem>
                  <NAlert type="info" :bordered="false">{{ t('mine.strategy.help.scope') }}</NAlert>
                  <NFlex v-if="isRetention" vertical :size="12">
                    <NFlex justify="space-between" align="center" :wrap="true">
                      <NFlex vertical :size="2">
                        <NText strong>{{ t('mine.strategy.section.retentionImpact') }}</NText>
                        <NText depth="3">{{ t('mine.strategy.retention.irreversibleRisk') }}</NText>
                      </NFlex>
                      <NButton
                        secondary
                        type="primary"
                        :loading="retentionPreviewLoading"
                        data-action="preview-retention"
                        @click="loadRetentionPreview"
                      >
                        {{ t('mine.strategy.action.previewRetention') }}
                      </NButton>
                    </NFlex>
                    <NAlert
                      v-if="retentionProblem"
                      :title="t('mine.strategy.error.title')"
                      type="error"
                      :bordered="false"
                    >
                      <NFlex vertical :size="3">
                        <NText>{{ retentionProblem.message }}</NText>
                        <NText code>{{ t('mine.strategy.error.code', { code: retentionProblem.code }) }}</NText>
                        <NText v-if="retentionProblem.traceId" depth="3">
                          {{ t('mine.common.traceId', { traceId: retentionProblem.traceId }) }}
                        </NText>
                      </NFlex>
                    </NAlert>
                    <template v-if="retentionPreview">
                      <NDescriptions :column="1" label-placement="left" bordered size="small">
                        <NDescriptionsItem :label="t('mine.strategy.field.observedAt')">
                          <NText code>{{ retentionPreview.observedAt }}</NText>
                        </NDescriptionsItem>
                        <NDescriptionsItem :label="t('mine.strategy.field.targetDevices')">
                          {{
                            t('mine.strategy.text.affectedDeviceCount', {
                              count: formatNumber(retentionPreview.targetDeviceCount)
                            })
                          }}
                        </NDescriptionsItem>
                        <NDescriptionsItem :label="t('mine.strategy.field.affectedDevices')">
                          {{
                            t('mine.strategy.text.affectedDeviceCount', {
                              count: formatNumber(retentionPreview.affectedDeviceCount)
                            })
                          }}
                        </NDescriptionsItem>
                        <NDescriptionsItem :label="t('mine.strategy.field.enabledDevices')">
                          {{
                            t('mine.strategy.text.affectedDeviceCount', {
                              count: formatNumber(retentionPreview.enabledDeviceCount)
                            })
                          }}
                        </NDescriptionsItem>
                      </NDescriptions>
                      <NList bordered>
                        <NListItem>
                          <NFlex vertical :size="3">
                            <NText strong>{{ t('mine.strategy.field.rawTelemetryDays') }}</NText>
                            <NText>{{ eligibleRowsText(retentionPreview.rawTelemetry) }}</NText>
                            <NText depth="3">
                              {{
                                retentionPreview.rawTelemetry.earliestEligibleAt ||
                                  t('mine.strategy.text.noEligibleTime')
                              }}
                            </NText>
                          </NFlex>
                        </NListItem>
                        <NListItem>
                          <NFlex vertical :size="3">
                            <NText strong>{{ t('mine.strategy.field.qualityEvidenceDays') }}</NText>
                            <NText>{{ eligibleRowsText(retentionPreview.qualityEvidence) }}</NText>
                            <NText depth="3">
                              {{
                                retentionPreview.qualityEvidence.earliestEligibleAt ||
                                  t('mine.strategy.text.noEligibleTime')
                              }}
                            </NText>
                          </NFlex>
                        </NListItem>
                      </NList>
                    </template>
                    <NEmpty v-else :description="t('mine.strategy.empty.retentionPreview')" />
                  </NFlex>
                  <NFormItem :label="t('mine.strategy.field.reason')">
                    <NInput
                      v-model:value="publishForm.reason"
                      type="textarea"
                      :autosize="{ minRows: 2, maxRows: 4 }"
                      :placeholder="
                        t('mine.strategy.placeholder.enter', {
                          field: t('mine.strategy.field.reason')
                        })
                      "
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

              <NCard
                v-if="isRetention"
                :title="t('mine.strategy.section.retentionExecutions')"
                :bordered="false"
                size="small"
              >
                <template #header-extra>
                  <NButton
                    quaternary
                    type="primary"
                    size="small"
                    :loading="retentionExecutionsLoading"
                    @click="loadRetentionExecutions"
                  >
                    {{ t('mine.strategy.action.refresh') }}
                  </NButton>
                </template>
                <NSpin :show="retentionExecutionsLoading">
                  <NList v-if="retentionExecutions.length" hoverable>
                    <NListItem v-for="execution in retentionExecutions" :key="execution.executionId">
                      <NFlex vertical :size="5" class="w-full">
                        <NFlex justify="space-between" align="center" :wrap="true">
                          <NText code>{{ execution.startedAt }}</NText>
                          <NTag :type="executionStatusType(execution.status)" :bordered="false" size="small">
                            {{ executionStatusLabel(execution.status) }}
                          </NTag>
                        </NFlex>
                        <NText>
                          {{
                            t('mine.strategy.text.deletedRows', {
                              raw: formatNumber(execution.rawRowsDeleted),
                              quality: formatNumber(execution.qualityRowsDeleted)
                            })
                          }}
                        </NText>
                        <NText depth="3">
                          {{ t('mine.strategy.field.runtimeInstance') }} · {{ execution.instanceId }}
                        </NText>
                        <NAlert v-if="execution.lastError" type="error" :bordered="false">
                          {{ execution.lastError }}
                        </NAlert>
                      </NFlex>
                    </NListItem>
                  </NList>
                  <NEmpty v-else :description="t('mine.strategy.empty.retentionExecutions')" />
                </NSpin>
              </NCard>

              <NCard :title="t('mine.strategy.section.history')" :bordered="false" size="small">
                <NFlex v-if="can('device:strategy:publish') && releases.length" vertical :size="8">
                  <NInput
                    v-model:value="rollbackReason"
                    type="textarea"
                    :autosize="{ minRows: 2, maxRows: 3 }"
                    :placeholder="
                      t('mine.strategy.placeholder.enter', {
                        field: t('mine.strategy.field.reason')
                      })
                    "
                    data-field="rollback-reason"
                  />
                </NFlex>
                <NList v-if="releases.length" hoverable>
                  <NListItem v-for="release in releases" :key="release.id">
                    <NFlex vertical :size="8" class="w-full">
                      <NFlex justify="space-between" align="center" :wrap="true">
                        <NFlex vertical :size="3">
                          <NFlex align="center">
                            <NText strong>
                              {{
                                t('mine.strategy.text.sequence', {
                                  sequence: release.releaseSequence
                                })
                              }}
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
                          {{
                            isRetention && isRetentionRollbackPreviewCurrent(release)
                              ? t('mine.strategy.action.confirmRetentionRollback')
                              : isRetention
                                ? t('mine.strategy.action.previewRetentionRollback')
                                : t('mine.strategy.action.rollback')
                          }}
                        </NButton>
                      </NFlex>
                      <NAlert
                        v-if="
                          isRetention &&
                            retentionRollbackPreview?.releaseId === release.id &&
                            isRetentionRollbackPreviewCurrent(release)
                        "
                        type="warning"
                        :bordered="false"
                        :title="t('mine.strategy.section.retentionRollbackImpact')"
                        :data-preview-release="release.id"
                      >
                        <NFlex vertical :size="8">
                          <NText>{{ t('mine.strategy.retention.irreversibleRisk') }}</NText>
                          <NDescriptions :column="1" label-placement="left" bordered size="small">
                            <NDescriptionsItem :label="t('mine.strategy.field.observedAt')">
                              <NText code>{{ retentionRollbackPreview.preview.observedAt }}</NText>
                            </NDescriptionsItem>
                            <NDescriptionsItem :label="t('mine.strategy.field.targetDevices')">
                              {{
                                t('mine.strategy.text.affectedDeviceCount', {
                                  count: formatNumber(retentionRollbackPreview.preview.targetDeviceCount)
                                })
                              }}
                            </NDescriptionsItem>
                            <NDescriptionsItem :label="t('mine.strategy.field.affectedDevices')">
                              {{
                                t('mine.strategy.text.affectedDeviceCount', {
                                  count: formatNumber(retentionRollbackPreview.preview.affectedDeviceCount)
                                })
                              }}
                            </NDescriptionsItem>
                            <NDescriptionsItem :label="t('mine.strategy.field.enabledDevices')">
                              {{
                                t('mine.strategy.text.affectedDeviceCount', {
                                  count: formatNumber(retentionRollbackPreview.preview.enabledDeviceCount)
                                })
                              }}
                            </NDescriptionsItem>
                          </NDescriptions>
                          <NText>
                            {{ t('mine.strategy.field.rawTelemetryDays') }} ·
                            {{ eligibleRowsText(retentionRollbackPreview.preview.rawTelemetry) }}
                          </NText>
                          <NText>
                            {{ t('mine.strategy.field.qualityEvidenceDays') }} ·
                            {{ eligibleRowsText(retentionRollbackPreview.preview.qualityEvidence) }}
                          </NText>
                        </NFlex>
                      </NAlert>
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
