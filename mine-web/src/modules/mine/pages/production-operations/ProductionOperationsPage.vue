<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns } from 'naive-ui';
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NFlex,
  NForm,
  NFormItem,
  NGrid,
  NGridItem,
  NInput,
  NInputNumber,
  NPageHeader,
  NProgress,
  NSelect,
  NSpace,
  NSpin,
  NStatistic,
  NTabPane,
  NTabs,
  NTag,
  NText,
  NTimeline,
  NTimelineItem
} from 'naive-ui';
import { hasPermission } from '@/app/permission/permission';
import { useAuthStore } from '@/store/modules/auth';
import MineSiteContextBar from '../../components/MineSiteContextBar.vue';
import { useMineContextStore } from '../../stores/mine-context';
import { getMinePeople, getMineVehicles } from '../../api/mine-api';
import type { Person, Vehicle } from '../../api/mine-api';
import {
  assignWorkOrder,
  closeShiftInstance,
  completeWorkOrder,
  createShiftInstance,
  createWorkOrder,
  getOperationCandidates,
  getOperationRecord,
  getOperationRecords,
  getOperationSummary,
  getShiftDefinitions,
  getShiftInstances,
  getWorkOrders,
  openShiftInstance,
  pauseWorkOrder,
  releaseWorkOrder,
  resolveOperationCandidate,
  resumeWorkOrder,
  startWorkOrder
} from '../../api/operation';
import type {
  OperationCandidate,
  OperationRecord,
  OperationRecordItem,
  OperationRecordPage,
  OperationSummary,
  ShiftDefinition,
  ShiftInstance,
  WorkOrder
} from '../../api/operation';

type WorkbenchTab = 'overview' | 'shifts' | 'workOrders' | 'candidates' | 'records';
type WorkOrderLifecycleAction = 'start' | 'pause' | 'resume' | 'complete';

const { t, locale } = useI18n();
const mineContext = useMineContextStore();
const authStore = useAuthStore();
const activeTab = ref<WorkbenchTab>('overview');
const businessDate = ref(today());
const loading = ref(false);
const problem = ref<string | null>(null);
const lastUpdatedAt = ref<number | null>(null);
const summary = ref<OperationSummary | null>(null);
const shiftDefinitions = ref<ShiftDefinition[]>([]);
const shiftInstances = ref<ShiftInstance[]>([]);
const workOrders = ref<WorkOrder[]>([]);
const candidates = ref<OperationCandidate[]>([]);
const recordPage = ref<OperationRecordPage | null>(null);
const recordDetail = ref<OperationRecord | null>(null);
const recordDrawerVisible = ref(false);
const recordDetailLoading = ref(false);
const releaseDrawerVisible = ref(false);
const releaseOrder = ref<WorkOrder | null>(null);
const releaseForm = reactive({ routeVersionId: '', loadGeofenceVersionId: '', unloadGeofenceVersionId: '' });
const shiftCreateDrawerVisible = ref(false);
const shiftCreateForm = reactive({ shiftDefinitionId: '', businessDate: today() });
const workOrderCreateDrawerVisible = ref(false);
const workOrderCreateForm = reactive({
  shiftInstanceId: '',
  orderNumber: '',
  title: '',
  plannedStartAt: '',
  plannedEndAt: '',
  plannedCycles: null as number | null,
  plannedTons: null as number | null,
  priority: 50
});
const assignmentDrawerVisible = ref(false);
const assignmentOrder = ref<WorkOrder | null>(null);
const assignmentForm = reactive({
  vehicleId: '',
  driverPersonId: null as string | null,
  validFrom: '',
  validTo: '',
  assignmentReason: ''
});
const catalogLoading = ref(false);
const vehicles = ref<Vehicle[]>([]);
const people = ref<Person[]>([]);
let loadGeneration = 0;

const isReadOnly = computed(
  () =>
    !can('mine:shift:edit') &&
    !can('mine:shift:close') &&
    !can('mine:work-order:edit') &&
    !can('mine:work-order:dispatch') &&
    !can('mine:operation:correct')
);
const candidateCount = computed(() => candidates.value.filter(item => item.status === 'PENDING').length);
const shiftDefinitionOptions = computed(() =>
  shiftDefinitions.value.map(item => ({ label: `${item.name} · ${item.startLocal}—${item.endLocal}`, value: item.id }))
);
const shiftInstanceOptions = computed(() =>
  shiftInstances.value
    .filter(item => item.status === 'PLANNED' || item.status === 'OPEN')
    .map(item => ({ label: `${shiftName(item)} · ${formatTime(item.plannedStartAt)}`, value: item.id }))
);
const vehicleOptions = computed(() =>
  vehicles.value
    .filter(item => item.status === 'ACTIVE' && item.vehicleType === 'HAUL_TRUCK')
    .map(item => ({ label: `${item.name} · ${item.vehicleCode}`, value: item.id }))
);
const driverOptions = computed(() =>
  people.value
    .filter(item => item.status === 'ACTIVE' && item.personType === 'DRIVER')
    .map(item => ({ label: `${item.name} · ${item.personCode}`, value: item.id }))
);

const shiftColumns = computed<DataTableColumns<ShiftInstance>>(() => [
  { title: t('mine.production.shift.businessDate'), key: 'businessDate', width: 120 },
  { title: t('mine.production.shift.name'), key: 'name', minWidth: 140, render: row => shiftName(row) },
  {
    title: t('mine.production.shift.window'),
    key: 'window',
    minWidth: 250,
    render: row => `${formatTime(row.plannedStartAt)} — ${formatTime(row.plannedEndAt)}`
  },
  { title: t('mine.production.common.status'), key: 'status', width: 110, render: row => statusTag(row.status) },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 180,
    render: row =>
      h(NFlex, { size: 12, wrap: false }, () => [
        row.status === 'PLANNED' && can('mine:shift:edit')
          ? h(
              NButton,
              { text: true, type: 'primary', 'data-action': 'open-shift', onClick: () => executeOpenShift(row) },
              () => t('mine.production.action.openShift')
            )
          : null,
        row.status === 'OPEN' && can('mine:shift:close')
          ? h(
              NButton,
              { text: true, type: 'error', 'data-action': 'close-shift', onClick: () => confirmCloseShift(row) },
              () => t('mine.production.action.closeShift')
            )
          : null
      ])
  }
]);

const workOrderColumns = computed<DataTableColumns<WorkOrder>>(() => [
  { title: t('mine.production.workOrder.number'), key: 'orderNumber', minWidth: 170 },
  { title: t('mine.production.workOrder.title'), key: 'title', minWidth: 180 },
  {
    title: t('mine.production.workOrder.plan'),
    key: 'plan',
    width: 150,
    render: row => planLabel(row)
  },
  {
    title: t('mine.production.workOrder.assignment'),
    key: 'assignments',
    width: 120,
    render: row => t('mine.production.workOrder.assignmentCount', { count: row.assignments.length })
  },
  {
    title: t('mine.production.workOrder.frozenVersion'),
    key: 'frozen',
    minWidth: 210,
    render: row => frozenVersionLabel(row)
  },
  { title: t('mine.production.common.status'), key: 'status', width: 110, render: row => statusTag(row.status) },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 250,
    render: row => h(NFlex, { size: 12, wrap: false }, () => workOrderActions(row))
  }
]);

const candidateColumns = computed<DataTableColumns<OperationCandidate>>(() => [
  { title: t('mine.production.candidate.type'), key: 'candidateType', minWidth: 150 },
  { title: t('mine.production.candidate.reason'), key: 'reasonCode', minWidth: 180 },
  { title: t('mine.production.candidate.occurredAt'), key: 'occurredAt', minWidth: 180, render: row => formatTime(row.occurredAt) },
  { title: t('mine.production.common.status'), key: 'status', width: 110, render: row => statusTag(row.status) },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 120,
    render: row =>
      row.status === 'PENDING' && can('mine:operation:correct')
        ? h(
            NButton,
            { text: true, type: 'primary', 'data-action': 'ignore-candidate', onClick: () => confirmIgnoreCandidate(row) },
            () => t('mine.production.action.ignoreCandidate')
          )
        : null
  }
]);

const recordColumns = computed<DataTableColumns<OperationRecordItem>>(() => [
  { title: t('mine.production.record.sequence'), key: 'recordSequence', width: 100 },
  { title: t('mine.production.record.order'), key: 'orderNumber', minWidth: 170 },
  {
    title: t('mine.production.record.vehicle'),
    key: 'vehicle',
    minWidth: 150,
    render: row => `${row.vehicleName} · ${row.vehicleCode}`
  },
  { title: t('mine.production.record.driver'), key: 'driverName', minWidth: 120, render: row => row.driverName ?? t('mine.common.empty') },
  { title: t('mine.production.record.completedAt'), key: 'completedAt', minWidth: 180, render: row => formatTime(row.completedAt) },
  { title: t('mine.production.record.duration'), key: 'duration', width: 120, render: row => duration(row.totalDurationSeconds) },
  { title: t('mine.production.record.tons'), key: 'tons', width: 120, render: row => tons(row.actualTons, row.tonsKnown) },
  { title: t('mine.production.common.status'), key: 'recordStatus', width: 110, render: row => statusTag(row.recordStatus) },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 100,
    render: row =>
      h(
        NButton,
        { text: true, type: 'primary', 'data-action': 'record-details', onClick: () => openRecordDetails(row) },
        () => t('mine.production.action.viewEvidence')
      )
  }
]);

const vehicleSummaryColumns = computed<DataTableColumns<OperationSummary['vehicles'][number]>>(() => [
  { title: t('mine.production.record.vehicle'), key: 'vehicleName' },
  {
    title: t('mine.production.overview.utilization'),
    key: 'utilizationRatePercent',
    render: row => percent(row.utilizationRatePercent)
  },
  { title: t('mine.production.metric.output'), key: 'actualTons', render: row => tons(row.actualTons) }
]);

const driverSummaryColumns = computed<DataTableColumns<OperationSummary['drivers'][number]>>(() => [
  {
    title: t('mine.production.record.driver'),
    key: 'driverName',
    render: row => row.driverName ?? t('mine.common.empty')
  },
  { title: t('mine.production.overview.effectiveCycles'), key: 'effectiveCycles' },
  { title: t('mine.production.metric.output'), key: 'actualTons', render: row => tons(row.actualTons) }
]);

onMounted(initialize);

async function initialize() {
  try {
    await mineContext.refreshContext();
    if (!mineContext.selectedSiteId && mineContext.sites.length === 1) mineContext.selectSite(mineContext.sites[0]!.id);
    businessDate.value = siteBusinessDate();
    await loadActiveTab();
  } catch {
    problem.value = t('mine.production.error.context');
  }
}

async function selectTab(value: string) {
  if (!isTab(value)) return;
  activeTab.value = value;
  await loadActiveTab();
}

async function changeSite(siteId: string) {
  if (!mineContext.selectSite(siteId)) return;
  clearScopedState();
  businessDate.value = siteBusinessDate();
  await loadActiveTab();
}

async function refreshActiveTab() {
  await loadActiveTab();
}

async function loadActiveTab() {
  const siteId = mineContext.selectedSiteId;
  const generation = ++loadGeneration;
  problem.value = null;
  if (!siteId) return;
  loading.value = true;
  try {
    if (activeTab.value === 'overview') {
      const result = await getOperationSummary(siteId, dateQuery());
      if (generation === loadGeneration) summary.value = result;
    } else if (activeTab.value === 'shifts') {
      const [definitions, instances] = await Promise.all([
        getShiftDefinitions(siteId),
        getShiftInstances(siteId, { fromDate: businessDate.value, toDate: businessDate.value })
      ]);
      if (generation === loadGeneration) {
        shiftDefinitions.value = definitions;
        shiftInstances.value = instances;
      }
    } else if (activeTab.value === 'workOrders') {
      const result = await getWorkOrders(siteId);
      if (generation === loadGeneration) workOrders.value = result;
    } else if (activeTab.value === 'candidates') {
      const result = await getOperationCandidates(siteId, { status: 'PENDING' });
      if (generation === loadGeneration) candidates.value = result;
    } else {
      const result = await getOperationRecords(siteId, { ...dateQuery(), pageSize: 100 });
      if (generation === loadGeneration) recordPage.value = result;
    }
    if (generation === loadGeneration) lastUpdatedAt.value = Date.now();
  } catch {
    if (generation === loadGeneration) problem.value = t('mine.production.error.dataUnavailable');
  } finally {
    if (generation === loadGeneration) loading.value = false;
  }
}

async function executeOpenShift(row: ShiftInstance) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId || !can('mine:shift:edit')) return;
  await executeMutation(() => openShiftInstance(siteId, row.id, row.version), 'mine.production.message.shiftOpened');
}

function confirmCloseShift(row: ShiftInstance) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId || !can('mine:shift:close')) return;
  window.$dialog?.warning({
    title: t('mine.production.confirm.closeShiftTitle'),
    content: t('mine.production.confirm.closeShiftImpact'),
    positiveText: t('mine.production.confirm.closeShift'),
    negativeText: t('mine.common.cancel'),
    async onPositiveClick() {
      await executeMutation(
        () => closeShiftInstance(siteId, row.id, row.version),
        'mine.production.message.shiftClosed'
      );
    }
  });
}

function workOrderActions(row: WorkOrder) {
  if (!can('mine:work-order:dispatch')) return [];
  const action = (
    key: string,
    label: string,
    handler: () => void,
    type: 'primary' | 'warning' | 'error' = 'primary'
  ) => h(NButton, { text: true, type, 'data-action': key, onClick: handler }, () => label);
  if (row.status === 'DRAFT') {
    return [
      action('assign-work-order', t('mine.production.action.assign'), () => openAssignmentDrawer(row)),
      action('release-work-order', t('mine.production.action.release'), () => openReleaseDrawer(row))
    ];
  }
  if (row.status === 'RELEASED') {
    return [action('start-work-order', t('mine.production.action.start'), () => executeWorkAction('start', row))];
  }
  if (row.status === 'RUNNING') {
    return [
      action('pause-work-order', t('mine.production.action.pause'), () => executeWorkAction('pause', row), 'warning'),
      action('complete-work-order', t('mine.production.action.complete'), () => confirmCompleteWorkOrder(row))
    ];
  }
  if (row.status === 'PAUSED') {
    return [
      action('resume-work-order', t('mine.production.action.resume'), () => executeWorkAction('resume', row)),
      action('complete-work-order', t('mine.production.action.complete'), () => confirmCompleteWorkOrder(row))
    ];
  }
  return [];
}

function openCreateShiftDrawer() {
  if (!can('mine:shift:edit')) return;
  shiftCreateForm.shiftDefinitionId = shiftDefinitions.value[0]?.id ?? '';
  shiftCreateForm.businessDate = businessDate.value;
  shiftCreateDrawerVisible.value = true;
}

async function submitCreateShift() {
  const siteId = mineContext.selectedSiteId;
  if (!siteId || !shiftCreateForm.shiftDefinitionId || !isDate(shiftCreateForm.businessDate)) {
    return window.$message?.warning(t('mine.production.validation.shiftRequired'));
  }
  const succeeded = await executeMutation(
    () =>
      createShiftInstance(siteId, {
        shiftDefinitionId: shiftCreateForm.shiftDefinitionId,
        businessDate: shiftCreateForm.businessDate,
        supervisorPersonId: null
      }),
    'mine.production.message.shiftCreated'
  );
  if (succeeded) shiftCreateDrawerVisible.value = false;
}

async function openCreateWorkOrderDrawer() {
  const siteId = mineContext.selectedSiteId;
  if (!siteId || !can('mine:work-order:edit')) return;
  if (!shiftInstances.value.length) {
    try {
      const scopedSiteId = siteId;
      const instances = await getShiftInstances(siteId, {
        fromDate: businessDate.value,
        toDate: businessDate.value
      });
      if (mineContext.selectedSiteId !== scopedSiteId) return;
      shiftInstances.value = instances;
    } catch {
      return window.$message?.error(t('mine.production.error.dataUnavailable'));
    }
  }
  const shift = shiftInstances.value.find(item => item.status === 'OPEN') ?? shiftInstances.value.find(item => item.status === 'PLANNED');
  Object.assign(workOrderCreateForm, {
    shiftInstanceId: shift?.id ?? '',
    orderNumber: `HAUL-${businessDate.value.replaceAll('-', '')}-${String(workOrders.value.length + 1).padStart(2, '0')}`,
    title: '',
    plannedStartAt: shift?.plannedStartAt ?? '',
    plannedEndAt: shift?.plannedEndAt ?? '',
    plannedCycles: null,
    plannedTons: null,
    priority: 50
  });
  workOrderCreateDrawerVisible.value = true;
}

async function submitCreateWorkOrder() {
  const siteId = mineContext.selectedSiteId;
  const start = toIso(workOrderCreateForm.plannedStartAt);
  const end = toIso(workOrderCreateForm.plannedEndAt);
  if (
    !siteId ||
    !workOrderCreateForm.shiftInstanceId ||
    !workOrderCreateForm.orderNumber.trim() ||
    !workOrderCreateForm.title.trim() ||
    !start ||
    !end ||
    Date.parse(end) <= Date.parse(start)
  ) {
    return window.$message?.warning(t('mine.production.validation.workOrderRequired'));
  }
  const succeeded = await executeMutation(
    () =>
      createWorkOrder(siteId, {
        shiftInstanceId: workOrderCreateForm.shiftInstanceId,
        orderNumber: workOrderCreateForm.orderNumber.trim(),
        title: workOrderCreateForm.title.trim(),
        workType: 'HAULAGE',
        plannedStartAt: start,
        plannedEndAt: end,
        plannedCycles: workOrderCreateForm.plannedCycles,
        plannedTons: workOrderCreateForm.plannedTons,
        priority: workOrderCreateForm.priority,
        version: 0
      }),
    'mine.production.message.workOrderCreated'
  );
  if (succeeded) workOrderCreateDrawerVisible.value = false;
}

async function openAssignmentDrawer(row: WorkOrder) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId || !can('mine:work-order:dispatch')) return;
  assignmentOrder.value = row;
  Object.assign(assignmentForm, {
    vehicleId: '',
    driverPersonId: null,
    validFrom: row.plannedStartAt,
    validTo: row.plannedEndAt,
    assignmentReason: ''
  });
  assignmentDrawerVisible.value = true;
  catalogLoading.value = true;
  try {
    const scopedSiteId = siteId;
    const [vehiclePage, personPage] = await Promise.all([
      getMineVehicles(siteId, { status: 'ACTIVE', page: 0, size: 100 }),
      getMinePeople(siteId, { status: 'ACTIVE', page: 0, size: 100 })
    ]);
    if (mineContext.selectedSiteId !== scopedSiteId) return;
    vehicles.value = vehiclePage.items;
    people.value = personPage.items;
  } catch {
    window.$message?.error(t('mine.production.error.catalog'));
  } finally {
    catalogLoading.value = false;
  }
}

async function submitAssignment() {
  const siteId = mineContext.selectedSiteId;
  const row = assignmentOrder.value;
  const start = toIso(assignmentForm.validFrom);
  const end = toIso(assignmentForm.validTo);
  if (
    !siteId ||
    !row ||
    !assignmentForm.vehicleId ||
    !assignmentForm.assignmentReason.trim() ||
    !start ||
    !end ||
    Date.parse(end) <= Date.parse(start)
  ) {
    return window.$message?.warning(t('mine.production.validation.assignmentRequired'));
  }
  const succeeded = await executeMutation(
    () =>
      assignWorkOrder(siteId, row.id, {
        vehicleId: assignmentForm.vehicleId,
        driverPersonId: assignmentForm.driverPersonId,
        validFrom: start,
        validTo: end,
        assignmentReason: assignmentForm.assignmentReason.trim(),
        version: row.version
      }),
    'mine.production.message.assignmentCreated'
  );
  if (succeeded) assignmentDrawerVisible.value = false;
}

function openReleaseDrawer(row: WorkOrder) {
  releaseOrder.value = row;
  Object.assign(releaseForm, {
    routeVersionId: row.routeVersionId ?? '',
    loadGeofenceVersionId: row.loadGeofenceVersionId ?? '',
    unloadGeofenceVersionId: row.unloadGeofenceVersionId ?? ''
  });
  releaseDrawerVisible.value = true;
}

async function submitRelease() {
  const siteId = mineContext.selectedSiteId;
  const row = releaseOrder.value;
  if (!siteId || !row || !releaseForm.routeVersionId || !releaseForm.loadGeofenceVersionId || !releaseForm.unloadGeofenceVersionId) {
    return window.$message?.warning(t('mine.production.validation.frozenVersions'));
  }
  const succeeded = await executeMutation(
    () =>
      releaseWorkOrder(siteId, row.id, {
        version: row.version,
        routeVersionId: releaseForm.routeVersionId,
        loadGeofenceVersionId: releaseForm.loadGeofenceVersionId,
        unloadGeofenceVersionId: releaseForm.unloadGeofenceVersionId
      }),
    'mine.production.message.workOrderReleased'
  );
  if (succeeded) releaseDrawerVisible.value = false;
  shiftCreateDrawerVisible.value = false;
  workOrderCreateDrawerVisible.value = false;
  assignmentDrawerVisible.value = false;
  assignmentOrder.value = null;
  vehicles.value = [];
  people.value = [];
}

async function executeWorkAction(action: WorkOrderLifecycleAction, row: WorkOrder) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId || !can('mine:work-order:dispatch')) return;
  const calls = {
    start: () => startWorkOrder(siteId, row.id, row.version),
    pause: () => pauseWorkOrder(siteId, row.id, row.version),
    resume: () => resumeWorkOrder(siteId, row.id, row.version),
    complete: () => completeWorkOrder(siteId, row.id, row.version)
  };
  await executeMutation(calls[action], `mine.production.message.${action}` as App.I18n.I18nKey);
}

function confirmCompleteWorkOrder(row: WorkOrder) {
  window.$dialog?.warning({
    title: t('mine.production.confirm.completeTitle'),
    content: t('mine.production.confirm.completeImpact'),
    positiveText: t('mine.production.action.complete'),
    negativeText: t('mine.common.cancel'),
    onPositiveClick: () => executeWorkAction('complete', row)
  });
}

function confirmIgnoreCandidate(row: OperationCandidate) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId || !can('mine:operation:correct')) return;
  window.$dialog?.warning({
    title: t('mine.production.confirm.ignoreCandidateTitle'),
    content: t('mine.production.confirm.ignoreCandidateImpact'),
    positiveText: t('mine.production.action.ignoreCandidate'),
    negativeText: t('mine.common.cancel'),
    onPositiveClick: () =>
      executeMutation(
        () =>
          resolveOperationCandidate(siteId, row.id, {
            action: 'IGNORE',
            reason: t('mine.production.candidate.manualIgnoreReason'),
            version: row.version
          }),
        'mine.production.message.candidateIgnored'
      )
  });
}

async function openRecordDetails(row: OperationRecordItem) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId) return;
  const generation = loadGeneration;
  recordDrawerVisible.value = true;
  recordDetailLoading.value = true;
  recordDetail.value = null;
  try {
    const result = await getOperationRecord(siteId, row.recordId);
    if (generation === loadGeneration && mineContext.selectedSiteId === siteId) recordDetail.value = result;
  } catch {
    window.$message?.error(t('mine.production.error.recordDetail'));
  } finally {
    recordDetailLoading.value = false;
  }
}

async function loadMoreRecords() {
  const siteId = mineContext.selectedSiteId;
  const current = recordPage.value;
  if (!siteId || !current?.nextCursor) return;
  const generation = loadGeneration;
  loading.value = true;
  try {
    const next = await getOperationRecords(siteId, {
      ...dateQuery(),
      watermark: current.watermark,
      cursor: current.nextCursor,
      pageSize: 100
    });
    if (generation === loadGeneration && mineContext.selectedSiteId === siteId) {
      recordPage.value = { ...next, items: [...current.items, ...next.items] };
    }
  } catch {
    problem.value = t('mine.production.error.dataUnavailable');
  } finally {
    loading.value = false;
  }
}

async function executeMutation<T>(action: () => Promise<T>, successKey: App.I18n.I18nKey) {
  loading.value = true;
  try {
    await action();
    window.$message?.success(t(successKey));
    await loadActiveTab();
    return true;
  } catch {
    window.$message?.error(t('mine.production.error.actionFailed'));
    return false;
  } finally {
    loading.value = false;
  }
}

function clearScopedState() {
  loadGeneration += 1;
  problem.value = null;
  summary.value = null;
  shiftDefinitions.value = [];
  shiftInstances.value = [];
  workOrders.value = [];
  candidates.value = [];
  recordPage.value = null;
  recordDetail.value = null;
  recordDrawerVisible.value = false;
  releaseDrawerVisible.value = false;
  lastUpdatedAt.value = null;
}

function dateQuery() {
  return { fromDate: businessDate.value, toDate: businessDate.value };
}

function can(permission: string) {
  return hasPermission(authStore.userInfo.buttons, permission);
}

function isTab(value: string): value is WorkbenchTab {
  return ['overview', 'shifts', 'workOrders', 'candidates', 'records'].includes(value);
}

function shiftName(row: ShiftInstance) {
  const snapshot = row.definitionSnapshot as Record<string, unknown> | undefined;
  const legacyName = (row as ShiftInstance & { definitionName?: string }).definitionName;
  return String(snapshot?.name ?? legacyName ?? shiftDefinitions.value.find(item => item.id === row.shiftDefinitionId)?.name ?? t('mine.common.empty'));
}

function planLabel(row: WorkOrder) {
  const cycles = row.plannedCycles == null ? t('mine.production.common.unknown') : t('mine.production.common.cycles', { count: row.plannedCycles });
  const plannedTons = row.plannedTons == null ? t('mine.production.common.tonsUnknown') : t('mine.production.common.tons', { value: formatNumber(row.plannedTons) });
  return `${cycles} · ${plannedTons}`;
}

function frozenVersionLabel(row: WorkOrder) {
  if (!row.routeVersionId) return t('mine.production.workOrder.notFrozen');
  return t('mine.production.workOrder.frozenSummary', {
    route: shortId(row.routeVersionId),
    load: shortId(row.loadGeofenceVersionId),
    unload: shortId(row.unloadGeofenceVersionId)
  });
}

function statusTag(status: string) {
  const type = statusType(status);
  return h(NTag, { size: 'small', bordered: false, type }, () => statusLabel(status));
}

function statusLabel(status: string) {
  return t(`mine.production.status.${status.toLowerCase()}` as App.I18n.I18nKey);
}

function statusType(status: string) {
  if (['OPEN', 'RUNNING', 'COMPLETED', 'RESOLVED', 'CONVERTED'].includes(status)) return 'success' as const;
  if (['PLANNED', 'DRAFT', 'PENDING'].includes(status)) return 'info' as const;
  if (['PAUSED', 'RELEASED'].includes(status)) return 'warning' as const;
  return 'error' as const;
}

function snapshotValue(key: string) {
  const value = recordDetail.value?.frozenSnapshot?.[key];
  return value == null || value === '' ? t('mine.common.empty') : String(value);
}

function formatTime(value: string | null | undefined) {
  if (!value) return t('mine.common.empty');
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

function formatNumber(value: number | null | undefined) {
  return value == null ? t('mine.common.empty') : new Intl.NumberFormat(locale.value, { maximumFractionDigits: 2 }).format(value);
}

function tons(value: number | null, known = value !== null) {
  return known && value !== null
    ? t('mine.production.common.tons', { value: formatNumber(value) })
    : t('mine.production.common.tonsUnknown');
}

function percent(value: number | null) {
  return value == null ? t('mine.production.common.unknown') : `${formatNumber(value)}%`;
}

function duration(value: number | null) {
  if (value == null) return t('mine.common.empty');
  const minutes = Math.floor(value / 60);
  const seconds = value % 60;
  return t('mine.production.common.duration', { minutes, seconds });
}

function shortId(value: string | null) {
  return value ? value.slice(0, 12) : t('mine.common.empty');
}

function today() {
  return new Date().toISOString().slice(0, 10);
}

function siteBusinessDate() {
  const timezone = mineContext.selectedSite?.timezone;
  if (!timezone) return today();
  try {
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone: timezone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit'
    }).formatToParts(new Date());
    const values = Object.fromEntries(parts.map(part => [part.type, part.value]));
    return `${values.year}-${values.month}-${values.day}`;
  } catch {
    return today();
  }
}

function isDate(value: string) {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(`${value}T00:00:00Z`));
}

function toIso(value: string) {
  const timestamp = Date.parse(value);
  return Number.isNaN(timestamp) ? null : new Date(timestamp).toISOString();
}
</script>

<template>
  <NSpace vertical :size="16">
    <NPageHeader :title="t('mine.production.title')" :subtitle="t('mine.production.subtitle')">
      <template #extra>
        <NTag v-if="isReadOnly" type="warning" :bordered="false">{{ t('mine.production.readOnly') }}</NTag>
      </template>
    </NPageHeader>

    <MineSiteContextBar :loading="loading" :last-updated-at="lastUpdatedAt" @change="changeSite" @refresh="refreshActiveTab" />

    <NCard size="small">
      <NFlex data-responsive-stack class="flex-col gap-3 md:flex-row" align="center" justify="space-between">
        <NTabs :value="activeTab" type="line" animated @update:value="selectTab">
          <NTabPane name="overview" :tab="t('mine.production.tab.overview')" />
          <NTabPane name="shifts" :tab="t('mine.production.tab.shifts')" />
          <NTabPane name="workOrders" :tab="t('mine.production.tab.workOrders')" />
          <NTabPane name="candidates">
            <template #tab>
              <NFlex align="center" :size="6">
                <span>{{ t('mine.production.tab.candidates') }}</span>
                <NTag v-if="candidateCount" type="warning" size="tiny" round>{{ candidateCount }}</NTag>
              </NFlex>
            </template>
          </NTabPane>
          <NTabPane name="records" :tab="t('mine.production.tab.records')" />
        </NTabs>
        <NInput v-model:value="businessDate" class="w-full md:w-44" :placeholder="t('mine.production.filter.businessDate')" :aria-label="t('mine.production.filter.businessDate')" @change="refreshActiveTab" />
      </NFlex>
    </NCard>

    <NAlert v-if="problem" type="error" :title="t('mine.production.error.title')">
      <NFlex align="center" justify="space-between">
        <span>{{ problem }}</span>
        <NButton data-action="retry-tab" size="small" @click="refreshActiveTab">{{ t('mine.production.action.retry') }}</NButton>
      </NFlex>
    </NAlert>

    <NSpin :show="loading">
      <template v-if="mineContext.selectedSiteId">
        <template v-if="activeTab === 'overview'">
          <NSpace v-if="summary" vertical :size="16">
            <NGrid responsive="screen" cols="1 s:2 l:4" :x-gap="12" :y-gap="12">
              <NGridItem>
                <NCard size="small" :title="t('mine.production.metric.progress')">
                  <NStatistic :value="percent(summary.completionRatePercent)" />
                  <NText depth="3">{{ t('mine.production.metric.cycles', { completed: summary.effectiveCompletedCycles, planned: summary.plannedCycles ?? t('mine.production.common.unknown') }) }}</NText>
                  <NProgress class="mt-3" type="line" :show-indicator="false" :percentage="Math.min(summary.completionRatePercent ?? 0, 100)" />
                </NCard>
              </NGridItem>
              <NGridItem>
                <NCard size="small" :title="t('mine.production.metric.output')">
                  <NStatistic :value="tons(summary.actualTons)" />
                  <NText depth="3">{{ t('mine.production.metric.plannedTons', { value: summary.plannedTons == null ? t('mine.production.common.unknown') : formatNumber(summary.plannedTons) }) }}</NText>
                  <NText v-if="summary.unknownTonsCycles" class="block" type="warning">{{ t('mine.production.metric.unknownTons', { count: summary.unknownTonsCycles }) }}</NText>
                </NCard>
              </NGridItem>
              <NGridItem>
                <NCard size="small" :title="t('mine.production.metric.cycleTime')">
                  <NStatistic :value="duration(summary.averageCycleSeconds == null ? null : Math.round(summary.averageCycleSeconds))" />
                  <NText depth="3">{{ t('mine.production.metric.stageTime', { load: duration(summary.averageLoadingSeconds == null ? null : Math.round(summary.averageLoadingSeconds)), haul: duration(summary.averageHaulingSeconds == null ? null : Math.round(summary.averageHaulingSeconds)), unload: duration(summary.averageUnloadingSeconds == null ? null : Math.round(summary.averageUnloadingSeconds)) }) }}</NText>
                </NCard>
              </NGridItem>
              <NGridItem>
                <NCard size="small" :title="t('mine.production.metric.anomalies')">
                  <NStatistic :value="summary.anomalyCycles" />
                  <NText depth="3">{{ t('mine.production.metric.workOrders', { count: summary.workOrderCount }) }}</NText>
                </NCard>
              </NGridItem>
            </NGrid>

            <NGrid responsive="screen" cols="1 l:2" :x-gap="16" :y-gap="16">
              <NGridItem>
                <NCard size="small" :title="t('mine.production.overview.vehicleUtilization')">
                  <NDataTable :columns="vehicleSummaryColumns" :data="summary.vehicles" :row-key="row => row.vehicleId" :scroll-x="500" />
                </NCard>
              </NGridItem>
              <NGridItem>
                <NCard size="small" :title="t('mine.production.overview.driverWorkload')">
                  <NDataTable :columns="driverSummaryColumns" :data="summary.drivers" :row-key="row => row.driverPersonId ?? 'unassigned'" :scroll-x="500" />
                </NCard>
              </NGridItem>
            </NGrid>
          </NSpace>
        </template>

        <template v-else-if="activeTab === 'shifts'">
          <NSpace vertical :size="16">
            <NCard size="small" :title="t('mine.production.shift.templateTitle')">
              <NFlex v-if="shiftDefinitions.length" :size="8">
                <NTag v-for="item in shiftDefinitions" :key="item.id" :bordered="false">
                  {{ item.name }} · {{ item.startLocal }}—{{ item.endLocal }}
                </NTag>
              </NFlex>
              <NEmpty v-else :description="t('mine.production.shift.templateEmpty')" />
            </NCard>
            <NCard size="small" :title="t('mine.production.shift.instanceTitle')">
              <template #header-extra>
                <NButton
                  v-if="can('mine:shift:edit')"
                  data-action="create-shift"
                  size="small"
                  type="primary"
                  :disabled="!shiftDefinitions.length"
                  @click="openCreateShiftDrawer"
                >
                  {{ t('mine.production.action.createShift') }}
                </NButton>
              </template>
              <NDataTable :columns="shiftColumns" :data="shiftInstances" :row-key="row => row.id" :scroll-x="900" />
            </NCard>
          </NSpace>
        </template>

        <template v-else-if="activeTab === 'workOrders'">
          <NCard size="small" :title="t('mine.production.workOrder.listTitle')">
            <template #header-extra>
              <NButton
                v-if="can('mine:work-order:edit')"
                data-action="create-work-order"
                size="small"
                type="primary"
                @click="openCreateWorkOrderDrawer"
              >
                {{ t('mine.production.action.createWorkOrder') }}
              </NButton>
            </template>
            <NDataTable :columns="workOrderColumns" :data="workOrders" :row-key="row => row.id" :scroll-x="1200" />
          </NCard>
        </template>

        <template v-else-if="activeTab === 'candidates'">
          <NCard size="small" :title="t('mine.production.candidate.title')">
            <NDataTable v-if="candidates.length" :columns="candidateColumns" :data="candidates" :row-key="row => row.id" :scroll-x="800" />
            <NEmpty v-else :description="t('mine.production.candidate.empty')">
              <template #extra><NText depth="3">{{ t('mine.production.candidate.emptyHint') }}</NText></template>
            </NEmpty>
          </NCard>
        </template>

        <template v-else>
          <NCard size="small" :title="t('mine.production.record.title')">
            <NDataTable :columns="recordColumns" :data="recordPage?.items ?? []" :row-key="row => row.recordId" :scroll-x="1200" />
            <NFlex v-if="recordPage?.nextCursor" class="mt-4" justify="center">
              <NButton @click="loadMoreRecords">{{ t('mine.production.action.loadMore') }}</NButton>
            </NFlex>
          </NCard>
        </template>
      </template>
      <NEmpty v-else :description="t('mine.production.context.selectSite')" />
    </NSpin>
  </NSpace>

  <NDrawer v-model:show="shiftCreateDrawerVisible" placement="right" :width="440">
    <NDrawerContent closable :title="t('mine.production.shift.createTitle')">
      <NForm label-placement="top">
        <NFormItem :label="t('mine.production.shift.definition')" required>
          <NSelect
            v-model:value="shiftCreateForm.shiftDefinitionId"
            :options="shiftDefinitionOptions"
            :placeholder="t('mine.production.shift.definitionPlaceholder')"
          />
        </NFormItem>
        <NFormItem :label="t('mine.production.shift.businessDate')" required>
          <NInput
            v-model:value="shiftCreateForm.businessDate"
            :placeholder="t('mine.production.filter.businessDate')"
          />
        </NFormItem>
        <NButton type="primary" block :loading="loading" @click="submitCreateShift">
          {{ t('mine.production.action.createShift') }}
        </NButton>
      </NForm>
    </NDrawerContent>
  </NDrawer>

  <NDrawer v-model:show="workOrderCreateDrawerVisible" placement="right" :width="500">
    <NDrawerContent closable :title="t('mine.production.workOrder.createTitle')">
      <NAlert v-if="!shiftInstanceOptions.length" type="warning" class="mb-4">
        {{ t('mine.production.workOrder.shiftRequiredHint') }}
      </NAlert>
      <NForm label-placement="top">
        <NFormItem :label="t('mine.production.workOrder.shift')" required>
          <NSelect
            v-model:value="workOrderCreateForm.shiftInstanceId"
            :options="shiftInstanceOptions"
            :placeholder="t('mine.production.workOrder.shiftPlaceholder')"
          />
        </NFormItem>
        <NFormItem :label="t('mine.production.workOrder.number')" required>
          <NInput v-model:value="workOrderCreateForm.orderNumber" maxlength="64" />
        </NFormItem>
        <NFormItem :label="t('mine.production.workOrder.title')" required>
          <NInput v-model:value="workOrderCreateForm.title" maxlength="128" />
        </NFormItem>
        <NFormItem :label="t('mine.production.workOrder.plannedStart')" required>
          <NInput v-model:value="workOrderCreateForm.plannedStartAt" :placeholder="t('mine.production.common.isoTime')" />
        </NFormItem>
        <NFormItem :label="t('mine.production.workOrder.plannedEnd')" required>
          <NInput v-model:value="workOrderCreateForm.plannedEndAt" :placeholder="t('mine.production.common.isoTime')" />
        </NFormItem>
        <NGrid cols="2" :x-gap="12">
          <NGridItem>
            <NFormItem :label="t('mine.production.workOrder.plannedCycles')">
              <NInputNumber v-model:value="workOrderCreateForm.plannedCycles" :min="1" :max="1000000" class="w-full" />
            </NFormItem>
          </NGridItem>
          <NGridItem>
            <NFormItem :label="t('mine.production.workOrder.plannedTons')">
              <NInputNumber v-model:value="workOrderCreateForm.plannedTons" :min="0.01" :precision="2" class="w-full" />
            </NFormItem>
          </NGridItem>
        </NGrid>
        <NFormItem :label="t('mine.production.workOrder.priority')" required>
          <NInputNumber v-model:value="workOrderCreateForm.priority" :min="1" :max="100" class="w-full" />
        </NFormItem>
        <NButton type="primary" block :loading="loading" :disabled="!shiftInstanceOptions.length" @click="submitCreateWorkOrder">
          {{ t('mine.production.action.createWorkOrder') }}
        </NButton>
      </NForm>
    </NDrawerContent>
  </NDrawer>

  <NDrawer v-model:show="assignmentDrawerVisible" placement="right" :width="500">
    <NDrawerContent closable :title="t('mine.production.assignment.title')">
      <NAlert type="info" class="mb-4" :title="assignmentOrder?.orderNumber">
        {{ t('mine.production.assignment.qualificationHint') }}
      </NAlert>
      <NForm label-placement="top">
        <NFormItem :label="t('mine.production.assignment.vehicle')" required>
          <NSelect
            v-model:value="assignmentForm.vehicleId"
            :options="vehicleOptions"
            :loading="catalogLoading"
            :placeholder="t('mine.production.assignment.vehiclePlaceholder')"
          />
        </NFormItem>
        <NFormItem :label="t('mine.production.assignment.driver')">
          <NSelect
            v-model:value="assignmentForm.driverPersonId"
            clearable
            :options="driverOptions"
            :loading="catalogLoading"
            :placeholder="t('mine.production.assignment.driverPlaceholder')"
          />
        </NFormItem>
        <NFormItem :label="t('mine.production.assignment.validFrom')" required>
          <NInput v-model:value="assignmentForm.validFrom" :placeholder="t('mine.production.common.isoTime')" />
        </NFormItem>
        <NFormItem :label="t('mine.production.assignment.validTo')" required>
          <NInput v-model:value="assignmentForm.validTo" :placeholder="t('mine.production.common.isoTime')" />
        </NFormItem>
        <NFormItem :label="t('mine.production.assignment.reason')" required>
          <NInput
            v-model:value="assignmentForm.assignmentReason"
            type="textarea"
            maxlength="500"
            show-count
            :placeholder="t('mine.production.assignment.reasonPlaceholder')"
          />
        </NFormItem>
        <NButton type="primary" block :loading="loading || catalogLoading" @click="submitAssignment">
          {{ t('mine.production.action.assign') }}
        </NButton>
      </NForm>
    </NDrawerContent>
  </NDrawer>

  <NDrawer v-model:show="releaseDrawerVisible" placement="right" :width="480">
    <NDrawerContent closable :title="t('mine.production.release.title')">
      <NSpace vertical :size="16">
        <NAlert type="warning" :title="t('mine.production.release.immutableTitle')">
          {{ t('mine.production.release.immutableImpact') }}
        </NAlert>
        <NInput v-model:value="releaseForm.routeVersionId" :placeholder="t('mine.production.release.routeVersion')" />
        <NInput v-model:value="releaseForm.loadGeofenceVersionId" :placeholder="t('mine.production.release.loadVersion')" />
        <NInput v-model:value="releaseForm.unloadGeofenceVersionId" :placeholder="t('mine.production.release.unloadVersion')" />
        <NButton type="primary" block :loading="loading" @click="submitRelease">{{ t('mine.production.action.release') }}</NButton>
      </NSpace>
    </NDrawerContent>
  </NDrawer>

  <NDrawer v-model:show="recordDrawerVisible" placement="right" :width="560">
    <NDrawerContent closable :title="t('mine.production.record.detailTitle')">
      <NSpin :show="recordDetailLoading">
        <NSpace v-if="recordDetail" vertical :size="20">
          <NDescriptions :title="t('mine.production.record.currentProjection')" label-placement="left" :column="1" bordered>
            <NDescriptionsItem :label="t('mine.production.record.status')">{{ statusLabel(recordDetail.recordStatus) }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.production.record.tons')">{{ tons(recordDetail.actualTons) }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.production.record.correctionVersion')">v{{ recordDetail.correctionVersion }}</NDescriptionsItem>
          </NDescriptions>

          <NDescriptions :title="t('mine.production.record.frozenEvidence')" label-placement="left" :column="1" bordered>
            <NDescriptionsItem :label="t('mine.production.record.frozenRoute')">{{ snapshotValue('routeVersionId') }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.production.record.contentHash')">{{ snapshotValue('routeContentHash') }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.production.record.frozenLoad')">{{ snapshotValue('loadGeofenceVersionId') }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.production.record.frozenUnload')">{{ snapshotValue('unloadGeofenceVersionId') }}</NDescriptionsItem>
          </NDescriptions>

          <NCard size="small" :title="t('mine.production.record.evidence')">
            <NTimeline>
              <NTimelineItem type="info" :title="t('mine.production.record.started')" :time="formatTime(recordDetail.startedAt)" />
              <NTimelineItem v-if="recordDetail.loadCompletedAt" type="success" :title="t('mine.production.record.loaded')" :time="formatTime(recordDetail.loadCompletedAt)" />
              <NTimelineItem v-if="recordDetail.unloadingStartedAt" type="warning" :title="t('mine.production.record.unloading')" :time="formatTime(recordDetail.unloadingStartedAt)" />
              <NTimelineItem :type="recordDetail.recordStatus === 'COMPLETED' ? 'success' : 'error'" :title="t('mine.production.record.finished')" :time="formatTime(recordDetail.completedAt)" />
            </NTimeline>
          </NCard>

          <NCard size="small" :title="t('mine.production.record.corrections')">
            <NTimeline v-if="recordDetail.corrections.length">
              <NTimelineItem v-for="item in recordDetail.corrections" :key="item.id" type="warning" :title="`${item.correctionType} · v${item.correctionVersion}`" :time="formatTime(item.createdAt)">
                {{ item.reason }}
              </NTimelineItem>
            </NTimeline>
            <NEmpty v-else :description="t('mine.production.record.noCorrections')" />
          </NCard>
        </NSpace>
      </NSpin>
    </NDrawerContent>
  </NDrawer>
</template>
