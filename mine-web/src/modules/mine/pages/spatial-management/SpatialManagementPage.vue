<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  NAlert,
  NButton,
  NCard,
  NCheckbox,
  NDataTable,
  NDescriptions,
  NDescriptionsItem,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NFlex,
  NForm,
  NFormItem,
  NFormItemGi,
  NGrid,
  NInput,
  NInputNumber,
  NList,
  NListItem,
  NModal,
  NPagination,
  NSelect,
  NSpin,
  NTabPane,
  NTabs,
  NTag,
  NText
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import { useAuthStore } from '@/store/modules/auth';
import MineSiteSelector from '../../components/MineSiteSelector.vue';
import SpatialGeometryEditor from '../../components/SpatialGeometryEditor.vue';
import type { SpatialGeometryPoint } from '../../components/SpatialGeometryEditor.vue';
import {
  archiveMineGeofence,
  archiveMineRoute,
  archiveMineWaypoint,
  createMineGeofence,
  createMineGeofenceVersion,
  createMineRoute,
  createMineRouteVersion,
  createMineSpatialReplay,
  createMineWaypoint,
  createMineWaypointVersion,
  getMineCoordinateSystems,
  getMineGeofences,
  getMineGeofenceVersions,
  getMineRoutes,
  getMineRouteVersions,
  getMineSpatialEvent,
  getMineSpatialEvents,
  getMineWaypoints,
  getMineWaypointVersions,
  publishMineGeofenceVersion,
  publishMineRouteVersion,
  publishMineWaypointVersion,
  updateMineGeofenceVersion,
  updateMineRouteVersion,
  updateMineWaypointVersion
} from '../../api/mine-api';
import type {
  CoordinateSystem,
  GeofenceType,
  GeofenceVersion,
  LocationSubjectType,
  RouteDirection,
  RouteVersion,
  SpatialDetectionPolicy,
  SpatialEvent,
  SpatialEventDetail,
  SpatialEventType,
  SpatialObject,
  SpatialReplayRequest,
  SpatialVersionStatus,
  SpatialZoneType,
  VehicleType,
  WaypointType,
  WaypointVersion
} from '../../api/mine-api';
import { useMineContextStore } from '../../stores/mine-context';

type SpatialTab = 'ROUTES' | 'WAYPOINTS' | 'GEOFENCES' | 'EVENTS';
type ObjectTab = Exclude<SpatialTab, 'EVENTS'>;
type SpatialVersion = RouteVersion | WaypointVersion | GeofenceVersion;

interface RouteWaypointDraft {
  sequenceNumber: number;
  waypointVersionId: string;
  stopRequired: boolean;
  expectedDwellSeconds: number | null;
}

interface VersionForm {
  coordinateSystemId: string;
  versionNumber: number;
  name: string;
  validFrom: string;
  validTo: string;
  waypointType: WaypointType;
  x: number;
  y: number;
  z: number | null;
  radiusMeters: number;
  minZ: number | null;
  maxZ: number | null;
  direction: RouteDirection;
  applicableVehicleTypes: VehicleType[];
  routeWaypoints: RouteWaypointDraft[];
  geofenceType: GeofenceType;
  vertices: SpatialGeometryPoint[];
  detectionPolicy: SpatialDetectionPolicy;
}

const { t, locale } = useI18n();
const authStore = useAuthStore();
const mineContext = useMineContextStore();
const activeTab = ref<SpatialTab>('ROUTES');
const loading = ref(false);
const loadError = ref(false);
const saving = ref(false);
const objects = ref<SpatialObject[]>([]);
const objectTotal = ref(0);
const objectQuery = reactive({ keyword: '', status: 'ACTIVE' as 'ACTIVE' | 'ARCHIVED', page: 1, size: 100 });
const versions = ref<SpatialVersion[]>([]);
const coordinates = ref<CoordinateSystem[]>([]);
const waypointCatalog = ref<WaypointVersion[]>([]);
const activeWaypointIds = ref<Set<string>>(new Set());
const selectedObject = ref<SpatialObject | null>(null);
const selectedVersion = ref<SpatialVersion | null>(null);
const createObjectVisible = ref(false);
const objectCode = ref('');
const geometryValid = ref(true);
let scopeGeneration = 0;
let objectRequestGeneration = 0;
let versionGeneration = 0;
let eventGeneration = 0;
let eventDetailGeneration = 0;
let waypointCatalogScopeGeneration = -1;

const form = reactive<VersionForm>(emptyVersionForm());
const eventLoading = ref(false);
const eventError = ref(false);
const events = ref<SpatialEvent[]>([]);
const eventWatermark = ref<number | null>(null);
const eventCursor = ref<string | null>(null);
const eventDetail = ref<SpatialEventDetail | null>(null);
const eventDetailVisible = ref(false);
const replayVisible = ref(false);
const replaySubmitting = ref(false);
const now = Date.now();
const eventFilter = reactive({
  zoneType: null as SpatialZoneType | null,
  zoneId: '',
  subjectType: null as LocationSubjectType | null,
  subjectId: '',
  eventType: null as SpatialEventType | null,
  from: new Date(now - 24 * 60 * 60 * 1000).toISOString(),
  to: new Date(now).toISOString()
});
const replayForm = reactive<SpatialReplayRequest>({
  zoneType: 'GEOFENCE',
  zoneVersionId: '',
  from: '',
  to: '',
  reason: ''
});

const objectTab = computed(() => activeTab.value as ObjectTab);
const versionReadonly = computed(() => selectedVersion.value?.status === 'PUBLISHED');
const objectArchived = computed(() => selectedObject.value?.status === 'ARCHIVED');
const editReadonly = computed(() => versionReadonly.value || objectArchived.value || !can('mine:spatial:edit'));
const isNewDraft = computed(() => selectedVersion.value === null && selectedObject.value?.status === 'ACTIVE');
const coordinateOptions = computed<SelectOption[]>(() =>
  coordinates.value.map(item => ({ label: `${item.name} · ${item.systemCode}`, value: item.id }))
);
const objectStatusOptions = computed<SelectOption[]>(() => [
  { label: t('mine.assets.status.active'), value: 'ACTIVE' },
  { label: t('mine.assets.status.archived'), value: 'ARCHIVED' }
]);
const waypointVersionOptions = computed<SelectOption[]>(() =>
  waypointCatalog.value.filter(isWaypointEligible).map(item => ({
    label: `${item.name} · v${item.versionNumber}`,
    value: item.id,
    disabled: item.status !== 'PUBLISHED'
  }))
);
const waypointTypeOptions = computed(() => enumOptions<WaypointType>('waypointType', ['LOAD', 'UNLOAD', 'CHECK', 'WAIT', 'ENTRY', 'EXIT']));
const geofenceTypeOptions = computed(() => enumOptions<GeofenceType>('geofenceType', ['RESTRICTED', 'WORK', 'LOAD', 'UNLOAD', 'WAIT', 'SAFETY', 'OTHER']));
const directionOptions = computed(() => enumOptions<RouteDirection>('direction', ['FORWARD', 'REVERSE', 'BIDIRECTIONAL']));
const vehicleTypeOptions = computed(() => enumOptions<VehicleType>('vehicleType', ['HAUL_TRUCK', 'EXCAVATOR', 'LOADER', 'DRILL', 'DOZER', 'WATER_TRUCK', 'LIGHT_VEHICLE', 'OTHER']));
const zoneTypeOptions = computed(() => enumOptions<SpatialZoneType>('zoneType', ['WAYPOINT', 'GEOFENCE']));
const eventTypeOptions = computed(() => enumOptions<SpatialEventType>('eventType', ['ENTER', 'EXIT', 'DWELL']));
const subjectTypeOptions = computed(() => enumOptions<LocationSubjectType>('subjectType', ['TAG', 'VEHICLE', 'PERSON']));
const eventColumns = computed<DataTableColumns<SpatialEvent>>(() => [
  { title: t('mine.spatial.event.sequence'), key: 'eventSequence', width: 100 },
  { title: t('mine.spatial.event.type'), key: 'eventType', width: 100, render: row => eventTypeLabel(row.eventType) },
  { title: t('mine.spatial.event.zone'), key: 'zone', minWidth: 180, render: row => `${zoneTypeLabel(row.zoneType)} · ${row.zoneId}` },
  { title: t('mine.spatial.event.subject'), key: 'subject', minWidth: 180, render: row => row.vehicleId || row.personId || row.tagId },
  { title: t('mine.spatial.event.occurredAt'), key: 'occurredAt', minWidth: 180, render: row => formatTime(row.occurredAt) },
  { title: t('mine.spatial.event.runtimeRevision'), key: 'runtimeRevision', width: 110 },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 90,
    render: row => h(NButton, { text: true, type: 'primary', onClick: () => openEventDetail(row) }, () => t('mine.spatial.event.details'))
  }
]);
const waypointGeometry = computed({
  get: (): SpatialGeometryPoint[] => [{ sequenceNumber: 1, x: form.x, y: form.y }],
  set: points => {
    const point = points[0];
    if (point) {
      form.x = point.x;
      form.y = point.y;
    }
  }
});
const routeGeometry = computed<SpatialGeometryPoint[]>(() => {
  const catalog = new Map(waypointCatalog.value.map(item => [item.id, item]));
  return form.routeWaypoints.flatMap((item, index) => {
    const waypoint = catalog.get(item.waypointVersionId);
    return waypoint ? [{ sequenceNumber: index + 1, x: waypoint.x, y: waypoint.y }] : [];
  });
});
const geometryType = computed(() => objectTab.value === 'WAYPOINTS' ? 'POINT' : objectTab.value === 'GEOFENCES' ? 'POLYGON' : 'POLYLINE');

onMounted(initialize);

async function initialize() {
  try {
    await mineContext.refreshContext();
    if (!mineContext.selectedSiteId && mineContext.sites.length === 1) {
      mineContext.selectSite(mineContext.sites[0]!.id);
    }
    if (mineContext.selectedSiteId) await loadCurrentTab();
  } catch (error) {
    showError(error);
  }
}

async function activateTab(tab: string) {
  if (!['ROUTES', 'WAYPOINTS', 'GEOFENCES', 'EVENTS'].includes(tab)) return;
  scopeGeneration += 1;
  eventGeneration += 1;
  activeTab.value = tab as SpatialTab;
  Object.assign(objectQuery, { keyword: '', status: 'ACTIVE', page: 1 });
  clearSelection();
  await loadCurrentTab();
}

async function changeSite(siteId: string) {
  mineContext.selectSite(siteId);
  scopeGeneration += 1;
  clearWorkspace();
  await loadCurrentTab();
}

async function refreshCurrentSite() {
  scopeGeneration += 1;
  clearWorkspace();
  await loadCurrentTab();
}

async function loadCurrentTab() {
  const siteId = mineContext.selectedSiteId;
  if (!siteId) return;
  if (activeTab.value === 'EVENTS') {
    if (can('mine:spatial:event:view')) await searchEvents();
    return;
  }
  await loadObjects(siteId);
}

async function loadObjects(siteId: string) {
  const generation = scopeGeneration;
  const requestGeneration = ++objectRequestGeneration;
  const tab = activeTab.value;
  loading.value = true;
  loadError.value = false;
  try {
    const query = { ...objectQuery };
    const objectRequest = tab === 'ROUTES'
      ? getMineRoutes(siteId, query)
      : tab === 'WAYPOINTS'
        ? getMineWaypoints(siteId, query)
        : getMineGeofences(siteId, query);
    const [objectPage, coordinatePage] = await Promise.all([
      objectRequest,
      getMineCoordinateSystems(siteId, { page: 1, size: 100, status: 'ACTIVE' })
    ]);
    if (generation !== scopeGeneration || requestGeneration !== objectRequestGeneration || siteId !== mineContext.selectedSiteId || tab !== activeTab.value) return;
    objects.value = objectPage.items;
    objectTotal.value = objectPage.total;
    coordinates.value = coordinatePage.items.filter(item => item.unified);
    if (tab === 'ROUTES' && waypointCatalogScopeGeneration !== generation) {
      await loadWaypointCatalog(siteId, generation, requestGeneration);
    }
  } catch (error) {
    if (generation === scopeGeneration && requestGeneration === objectRequestGeneration) {
      loadError.value = true;
      showError(error);
    }
  } finally {
    if (generation === scopeGeneration && requestGeneration === objectRequestGeneration) loading.value = false;
  }
}

async function searchObjects() {
  objectQuery.page = 1;
  clearSelection();
  if (mineContext.selectedSiteId) await loadObjects(mineContext.selectedSiteId);
}

async function changeObjectPage(page: number) {
  objectQuery.page = page;
  clearSelection();
  if (mineContext.selectedSiteId) await loadObjects(mineContext.selectedSiteId);
}

async function loadWaypointCatalog(siteId: string, generation: number, requestGeneration: number) {
  const [activeWaypoints, archivedWaypoints] = await Promise.all([
    loadAllWaypoints(siteId, 'ACTIVE'),
    loadAllWaypoints(siteId, 'ARCHIVED')
  ]);
  if (generation !== scopeGeneration || requestGeneration !== objectRequestGeneration || siteId !== mineContext.selectedSiteId) return;
  const allWaypoints = [...activeWaypoints, ...archivedWaypoints];
  const versionGroups = await mapWithConcurrency(
    allWaypoints,
    8,
    item => getMineWaypointVersions(siteId, item.id)
  );
  if (generation !== scopeGeneration || requestGeneration !== objectRequestGeneration || siteId !== mineContext.selectedSiteId) return;
  activeWaypointIds.value = new Set(activeWaypoints.map(item => item.id));
  waypointCatalog.value = versionGroups.flat().filter(item => item.status === 'PUBLISHED');
  waypointCatalogScopeGeneration = generation;
}

async function loadAllWaypoints(siteId: string, status: 'ACTIVE' | 'ARCHIVED') {
  const firstPage = await getMineWaypoints(siteId, { page: 1, size: 100, status });
  const pageCount = Math.ceil(firstPage.total / firstPage.size);
  const remainingPages = await Promise.all(
    Array.from({ length: Math.max(0, pageCount - 1) }, (_, index) =>
      getMineWaypoints(siteId, { page: index + 2, size: 100, status })
    )
  );
  return [firstPage, ...remainingPages].flatMap(page => page.items);
}

async function selectObject(value: SpatialObject) {
  versionGeneration += 1;
  selectedObject.value = value;
  selectedVersion.value = null;
  Object.assign(form, emptyVersionForm());
  await loadVersions(value.id);
}

async function loadVersions(objectId: string) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId) return;
  const generation = ++versionGeneration;
  loading.value = true;
  try {
    const result = objectTab.value === 'ROUTES'
      ? await getMineRouteVersions(siteId, objectId)
      : objectTab.value === 'WAYPOINTS'
        ? await getMineWaypointVersions(siteId, objectId)
        : await getMineGeofenceVersions(siteId, objectId);
    if (generation !== versionGeneration || siteId !== mineContext.selectedSiteId || objectId !== selectedObject.value?.id) return;
    versions.value = [...result].sort((a, b) => b.versionNumber - a.versionNumber);
    const preferred = versions.value.find(item => item.status === 'DRAFT') ?? versions.value[0];
    if (preferred) selectVersion(preferred);
    else startNewDraft();
  } catch (error) {
    if (generation === versionGeneration) showError(error);
  } finally {
    if (generation === versionGeneration) loading.value = false;
  }
}

function selectVersion(value: SpatialVersion) {
  selectedVersion.value = value;
  fillVersionForm(value);
}

function startNewDraft() {
  if (!selectedObject.value || selectedObject.value.status !== 'ACTIVE' || !can('mine:spatial:edit')) return;
  selectedVersion.value = null;
  Object.assign(form, emptyVersionForm());
  form.coordinateSystemId = coordinates.value[0]?.id ?? '';
  form.versionNumber = Math.max(0, ...versions.value.map(item => item.versionNumber)) + 1;
}

function openCreateObject() {
  objectCode.value = '';
  createObjectVisible.value = true;
}

async function saveObject() {
  const siteId = mineContext.selectedSiteId;
  const code = objectCode.value.trim();
  if (!can('mine:spatial:edit')) return;
  if (!siteId || !code) {
    window.$message?.warning(t('mine.spatial.validation.required'));
    return;
  }
  const generation = scopeGeneration;
  saving.value = true;
  try {
    const created = objectTab.value === 'ROUTES'
      ? await createMineRoute(siteId, { code })
      : objectTab.value === 'WAYPOINTS'
        ? await createMineWaypoint(siteId, { code })
        : await createMineGeofence(siteId, { code });
    if (generation !== scopeGeneration || siteId !== mineContext.selectedSiteId) return;
    Object.assign(objectQuery, { keyword: '', status: 'ACTIVE', page: 1 });
    createObjectVisible.value = false;
    await loadObjects(siteId);
    await selectObject(created);
    window.$message?.success(t('mine.spatial.message.objectCreated'));
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

function confirmArchiveObject(value: SpatialObject) {
  window.$dialog?.warning({
    title: t('mine.spatial.object.archiveTitle'),
    content: t('mine.spatial.object.archiveImpact', { code: value.code }),
    positiveText: t('mine.spatial.object.archive'),
    negativeText: t('mine.common.cancel'),
    onPositiveClick: () => archiveObject(value)
  });
}

async function archiveObject(value: SpatialObject) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId || !can('mine:spatial:edit') || value.siteId !== siteId) return;
  const generation = scopeGeneration;
  saving.value = true;
  try {
    if (objectTab.value === 'ROUTES') await archiveMineRoute(siteId, value.id, value.version);
    else if (objectTab.value === 'WAYPOINTS') await archiveMineWaypoint(siteId, value.id, value.version);
    else await archiveMineGeofence(siteId, value.id, value.version);
    if (generation !== scopeGeneration || siteId !== mineContext.selectedSiteId) return;
    clearSelection();
    await loadObjects(siteId);
    window.$message?.success(t('mine.spatial.message.archived'));
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

async function saveSelectedVersion() {
  const siteId = mineContext.selectedSiteId;
  const object = selectedObject.value;
  if (!siteId || !object || !can('mine:spatial:edit') || object.siteId !== siteId || object.status !== 'ACTIVE' || versionReadonly.value) return;
  const generation = scopeGeneration;
  if (!isFormValid()) {
    window.$message?.warning(t('mine.spatial.validation.invalidDraft'));
    return;
  }
  saving.value = true;
  try {
    let saved: SpatialVersion;
    if (objectTab.value === 'ROUTES') {
      const body = routeBody();
      saved = selectedVersion.value
        ? await updateMineRouteVersion(siteId, object.id, selectedVersion.value.id, { ...body, version: selectedVersion.value.version })
        : await createMineRouteVersion(siteId, object.id, { ...body, versionNumber: form.versionNumber });
    } else if (objectTab.value === 'WAYPOINTS') {
      const body = waypointBody();
      saved = selectedVersion.value
        ? await updateMineWaypointVersion(siteId, object.id, selectedVersion.value.id, { ...body, version: selectedVersion.value.version })
        : await createMineWaypointVersion(siteId, object.id, { ...body, versionNumber: form.versionNumber });
    } else {
      const body = geofenceBody();
      saved = selectedVersion.value
        ? await updateMineGeofenceVersion(siteId, object.id, selectedVersion.value.id, { ...body, version: selectedVersion.value.version })
        : await createMineGeofenceVersion(siteId, object.id, { ...body, versionNumber: form.versionNumber });
    }
    if (generation !== scopeGeneration || siteId !== mineContext.selectedSiteId) return;
    await loadVersions(object.id);
    selectVersion(saved);
    window.$message?.success(t('mine.spatial.message.draftSaved'));
  } catch (error) {
    if (error instanceof ApiError && error.code === 'MINE-SPATIAL-409-002') {
      if (generation !== scopeGeneration || siteId !== mineContext.selectedSiteId) return;
      await loadVersions(object.id);
      window.$message?.warning(t('mine.spatial.message.optimisticConflict'));
    } else {
      showError(error);
    }
  } finally {
    saving.value = false;
  }
}

function confirmPublish() {
  if (!can('mine:spatial:publish') || selectedObject.value?.status !== 'ACTIVE' || !selectedVersion.value || selectedVersion.value.status !== 'DRAFT') return;
  if (isSelectedVersionDirty()) {
    window.$message?.warning(t('mine.spatial.publish.saveBeforePublish'));
    return;
  }
  window.$dialog?.warning({
    title: t('mine.spatial.publish.title'),
    content: t('mine.spatial.publish.impact', {
      name: form.name,
      version: form.versionNumber,
      validFrom: form.validFrom,
      validTo: form.validTo || t('mine.spatial.publish.noExpiry'),
      references: objectTab.value === 'ROUTES' ? form.routeWaypoints.length : objectTab.value === 'GEOFENCES' ? form.vertices.length : 1
    }),
    positiveText: t('mine.spatial.publish.confirm'),
    negativeText: t('mine.common.cancel'),
    onPositiveClick: publishSelectedVersion
  });
}

async function publishSelectedVersion() {
  const siteId = mineContext.selectedSiteId;
  const object = selectedObject.value;
  const version = selectedVersion.value;
  if (!siteId || !object || !version || !can('mine:spatial:publish') || object.siteId !== siteId || object.status !== 'ACTIVE' || version.status !== 'DRAFT') return;
  if (isSelectedVersionDirty()) {
    window.$message?.warning(t('mine.spatial.publish.saveBeforePublish'));
    return;
  }
  const generation = scopeGeneration;
  saving.value = true;
  try {
    if (objectTab.value === 'ROUTES') await publishMineRouteVersion(siteId, object.id, version.id, version.version);
    else if (objectTab.value === 'WAYPOINTS') await publishMineWaypointVersion(siteId, object.id, version.id, version.version);
    else await publishMineGeofenceVersion(siteId, object.id, version.id, version.version);
    if (generation !== scopeGeneration || siteId !== mineContext.selectedSiteId) return;
    await loadVersions(object.id);
    window.$message?.success(t('mine.spatial.message.published'));
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

function addRouteWaypoint() {
  form.routeWaypoints.push({ sequenceNumber: form.routeWaypoints.length + 1, waypointVersionId: '', stopRequired: false, expectedDwellSeconds: null });
}

function removeRouteWaypoint(index: number) {
  form.routeWaypoints.splice(index, 1);
  normalizeRouteWaypoints();
}

function moveRouteWaypoint(index: number, offset: number) {
  const target = index + offset;
  if (target < 0 || target >= form.routeWaypoints.length) return;
  const [item] = form.routeWaypoints.splice(index, 1);
  if (!item) return;
  form.routeWaypoints.splice(target, 0, item);
  normalizeRouteWaypoints();
}

function normalizeRouteWaypoints() {
  form.routeWaypoints.forEach((item, index) => { item.sequenceNumber = index + 1; });
}

async function searchEvents() {
  eventWatermark.value = null;
  eventCursor.value = null;
  events.value = [];
  await loadEventPage(false);
}

async function loadMoreEvents() {
  if (!eventCursor.value) return;
  await loadEventPage(true);
}

async function loadEventPage(append: boolean) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId || !can('mine:spatial:event:view')) return;
  const generation = ++eventGeneration;
  eventLoading.value = true;
  eventError.value = false;
  try {
    const page = await getMineSpatialEvents(siteId, {
      from: eventFilter.from,
      to: eventFilter.to,
      zoneType: eventFilter.zoneType,
      zoneId: eventFilter.zoneId.trim() || null,
      subjectType: eventFilter.subjectType,
      subjectId: eventFilter.subjectId.trim() || null,
      eventType: eventFilter.eventType,
      watermark: append ? eventWatermark.value : null,
      cursor: append ? eventCursor.value : null,
      pageSize: 100
    });
    if (generation !== eventGeneration || siteId !== mineContext.selectedSiteId) return;
    events.value = append ? [...events.value, ...page.items] : page.items;
    eventWatermark.value = page.watermark;
    eventCursor.value = page.nextCursor;
  } catch (error) {
    if (generation === eventGeneration) {
      eventError.value = true;
      showError(error);
    }
  } finally {
    if (generation === eventGeneration) eventLoading.value = false;
  }
}

async function openEventDetail(value: SpatialEvent) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId || !can('mine:spatial:event:view') || value.siteId !== siteId) return;
  const generation = ++eventDetailGeneration;
  eventDetailVisible.value = true;
  eventDetail.value = null;
  try {
    const detail = await getMineSpatialEvent(siteId, value.eventId);
    if (generation === eventDetailGeneration && siteId === mineContext.selectedSiteId) eventDetail.value = detail;
  } catch (error) {
    if (generation === eventDetailGeneration) showError(error);
  }
}

function openReplay(value?: SpatialEvent) {
  if (value) {
    replayForm.zoneType = value.zoneType;
    replayForm.zoneVersionId = value.zoneVersionId;
    replayForm.to = value.occurredAt;
    replayForm.from = new Date(new Date(value.occurredAt).getTime() - 60 * 60 * 1000).toISOString();
  }
  replayForm.reason = '';
  replayVisible.value = true;
}

async function submitReplay(value: SpatialReplayRequest = replayForm) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId || !can('mine:spatial:replay')) return;
  if (!isReplayValid(value)) {
    window.$message?.warning(t('mine.spatial.validation.invalidReplay'));
    return;
  }
  const generation = scopeGeneration;
  replaySubmitting.value = true;
  try {
    const key = await replayIdempotencyKey(siteId, value);
    await createMineSpatialReplay(siteId, { ...value, reason: value.reason.trim() }, key);
    if (generation !== scopeGeneration || siteId !== mineContext.selectedSiteId) return;
    replayVisible.value = false;
    window.$message?.success(t('mine.spatial.replay.created'));
  } catch (error) {
    showError(error);
  } finally {
    replaySubmitting.value = false;
  }
}

function fillVersionForm(value: SpatialVersion) {
  Object.assign(form, emptyVersionForm(), {
    coordinateSystemId: value.coordinateSystemId,
    versionNumber: value.versionNumber,
    name: value.name,
    validFrom: value.validFrom,
    validTo: value.validTo ?? ''
  });
  if (isWaypointVersion(value)) {
    Object.assign(form, {
      waypointType: value.waypointType,
      x: value.x,
      y: value.y,
      z: value.z,
      radiusMeters: value.radiusMeters,
      minZ: value.minZ,
      maxZ: value.maxZ,
      detectionPolicy: { ...value.detectionPolicy }
    });
  } else if (isRouteVersion(value)) {
    form.direction = value.direction;
    form.applicableVehicleTypes = [...value.applicableVehicleTypes];
    form.routeWaypoints = value.waypoints.map(item => ({ ...item }));
  } else {
    form.geofenceType = value.geofenceType;
    form.minZ = value.minZ;
    form.maxZ = value.maxZ;
    form.detectionPolicy = { ...value.detectionPolicy };
    form.vertices = value.vertices.map(item => ({ ...item }));
  }
}

function emptyVersionForm(): VersionForm {
  return {
    coordinateSystemId: '',
    versionNumber: 1,
    name: '',
    validFrom: new Date().toISOString(),
    validTo: '',
    waypointType: 'WAIT',
    x: 0,
    y: 0,
    z: null,
    radiusMeters: 10,
    minZ: null,
    maxZ: null,
    direction: 'BIDIRECTIONAL',
    applicableVehicleTypes: [],
    routeWaypoints: [],
    geofenceType: 'WORK',
    vertices: [],
    detectionPolicy: {
      boundaryToleranceMeters: 1,
      enterConfirmPoints: 2,
      exitConfirmPoints: 2,
      dwellSeconds: 60,
      allowedLatenessSeconds: 10,
      idleFlushSeconds: 30
    }
  };
}

function routeBody() {
  return {
    coordinateSystemId: form.coordinateSystemId,
    name: form.name.trim(),
    direction: form.direction,
    applicableVehicleTypes: [...form.applicableVehicleTypes],
    validFrom: form.validFrom,
    validTo: form.validTo || null,
    waypoints: form.routeWaypoints.map(item => ({ ...item }))
  };
}

function waypointBody() {
  return {
    coordinateSystemId: form.coordinateSystemId,
    name: form.name.trim(),
    waypointType: form.waypointType,
    x: form.x,
    y: form.y,
    z: form.z,
    radiusMeters: form.radiusMeters,
    minZ: form.minZ,
    maxZ: form.maxZ,
    detectionPolicy: { ...form.detectionPolicy },
    validFrom: form.validFrom,
    validTo: form.validTo || null
  };
}

function geofenceBody() {
  return {
    coordinateSystemId: form.coordinateSystemId,
    name: form.name.trim(),
    geofenceType: form.geofenceType,
    minZ: form.minZ,
    maxZ: form.maxZ,
    detectionPolicy: { ...form.detectionPolicy },
    validFrom: form.validFrom,
    validTo: form.validTo || null,
    vertices: form.vertices.map(item => ({ ...item }))
  };
}

function isFormValid() {
  if (!form.coordinateSystemId || !form.name.trim() || !form.validFrom) return false;
  const validFrom = Date.parse(form.validFrom);
  const validTo = form.validTo ? Date.parse(form.validTo) : null;
  if (!Number.isFinite(validFrom) || (validTo !== null && (!Number.isFinite(validTo) || validTo <= validFrom))) return false;
  if (form.minZ !== null && form.maxZ !== null && form.minZ > form.maxZ) return false;
  if (objectTab.value === 'ROUTES') {
    const waypointIds = form.routeWaypoints.map(item => item.waypointVersionId);
    return waypointIds.length >= 2 && waypointIds.every(Boolean) && new Set(waypointIds).size >= 2;
  }
  if (!geometryValid.value) return false;
  if (objectTab.value === 'WAYPOINTS' && form.radiusMeters <= 0) return false;
  const policy = form.detectionPolicy;
  return policy.idleFlushSeconds > policy.allowedLatenessSeconds && policy.enterConfirmPoints > 0 && policy.exitConfirmPoints > 0;
}

function isSelectedVersionDirty() {
  const version = selectedVersion.value;
  if (!version) return true;
  const current = objectTab.value === 'ROUTES'
    ? routeBody()
    : objectTab.value === 'WAYPOINTS'
      ? waypointBody()
      : geofenceBody();
  return stableSerialize(current) !== stableSerialize(persistedVersionBody(version));
}

function stableSerialize(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableSerialize).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>)
      .sort(([first], [second]) => first.localeCompare(second))
      .map(([key, item]) => `${JSON.stringify(key)}:${stableSerialize(item)}`);
    return `{${entries.join(',')}}`;
  }
  return JSON.stringify(value);
}

function persistedVersionBody(version: SpatialVersion) {
  const common = {
    coordinateSystemId: version.coordinateSystemId,
    name: version.name,
    validFrom: version.validFrom,
    validTo: version.validTo
  };
  if (isRouteVersion(version)) {
    return {
      ...common,
      direction: version.direction,
      applicableVehicleTypes: [...version.applicableVehicleTypes],
      waypoints: version.waypoints.map(item => ({ ...item }))
    };
  }
  if (isWaypointVersion(version)) {
    return {
      ...common,
      waypointType: version.waypointType,
      x: version.x,
      y: version.y,
      z: version.z,
      radiusMeters: version.radiusMeters,
      minZ: version.minZ,
      maxZ: version.maxZ,
      detectionPolicy: { ...version.detectionPolicy }
    };
  }
  return {
    ...common,
    geofenceType: version.geofenceType,
    minZ: version.minZ,
    maxZ: version.maxZ,
    detectionPolicy: { ...version.detectionPolicy },
    vertices: version.vertices.map(item => ({ ...item }))
  };
}

async function replayIdempotencyKey(siteId: string, value: SpatialReplayRequest) {
  const payload = JSON.stringify([siteId, value.zoneType, value.zoneVersionId, value.from, value.to, value.reason.trim()]);
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(payload));
  const hash = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
  return `spatial-replay:${hash}`;
}

async function mapWithConcurrency<T, R>(items: T[], concurrency: number, worker: (item: T) => Promise<R>) {
  const results: R[] = [];
  let nextIndex = 0;
  async function runWorker() {
    while (nextIndex < items.length) {
      const index = nextIndex;
      nextIndex += 1;
      results[index] = await worker(items[index]!);
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, runWorker));
  return results;
}

function isReplayValid(value: SpatialReplayRequest) {
  const from = Date.parse(value.from);
  const to = Date.parse(value.to);
  const maximumWindow = 31 * 24 * 60 * 60 * 1000;
  return Boolean(value.zoneVersionId && value.reason.trim())
    && Number.isFinite(from)
    && Number.isFinite(to)
    && to > from
    && to <= Date.now()
    && to - from <= maximumWindow;
}

function isWaypointEligible(value: WaypointVersion) {
  if (!activeWaypointIds.value.has(value.waypointId) || value.status !== 'PUBLISHED' || value.coordinateSystemId !== form.coordinateSystemId) return false;
  const routeFrom = Date.parse(form.validFrom);
  const waypointFrom = Date.parse(value.validFrom);
  if (!Number.isFinite(routeFrom) || waypointFrom > routeFrom) return false;
  const routeTo = form.validTo ? Date.parse(form.validTo) : Number.POSITIVE_INFINITY;
  const waypointTo = value.validTo ? Date.parse(value.validTo) : Number.POSITIVE_INFINITY;
  return waypointTo >= routeTo;
}

function isWaypointVersion(value: SpatialVersion): value is WaypointVersion {
  return 'waypointType' in value;
}

function isRouteVersion(value: SpatialVersion): value is RouteVersion {
  return 'direction' in value;
}

function versionStatusType(status: SpatialVersionStatus) {
  return status === 'PUBLISHED' ? 'success' as const : 'warning' as const;
}

function enumOptions<T extends string>(group: string, values: readonly T[]) {
  return values.map(value => ({ label: t(`mine.spatial.enum.${group}.${value.toLowerCase()}`), value }));
}

function can(permission: string) {
  return hasPermission(authStore.userInfo.buttons, permission);
}

function clearSelection() {
  versionGeneration += 1;
  selectedObject.value = null;
  selectedVersion.value = null;
  versions.value = [];
  Object.assign(form, emptyVersionForm());
}

function clearWorkspace() {
  eventGeneration += 1;
  eventDetailGeneration += 1;
  objectRequestGeneration += 1;
  clearSelection();
  objects.value = [];
  objectTotal.value = 0;
  loadError.value = false;
  coordinates.value = [];
  waypointCatalog.value = [];
  activeWaypointIds.value = new Set();
  waypointCatalogScopeGeneration = -1;
  events.value = [];
  eventError.value = false;
  eventWatermark.value = null;
  eventCursor.value = null;
  eventDetail.value = null;
  eventDetailVisible.value = false;
  replayVisible.value = false;
  createObjectVisible.value = false;
}

function formatTime(value: string | null | undefined) {
  if (!value) return t('mine.common.empty');
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value));
}

function zoneTypeLabel(value: SpatialZoneType) {
  return t(`mine.spatial.enum.zoneType.${value.toLowerCase()}`);
}

function eventTypeLabel(value: SpatialEventType) {
  return t(`mine.spatial.enum.eventType.${value.toLowerCase()}`);
}

function classificationLabel(value: SpatialEventDetail['evidence']['classification']) {
  return t(`mine.spatial.enum.classification.${value.toLowerCase()}`);
}

function qualityLabel(value: SpatialEventDetail['evidence']['qualityStatus']) {
  return t(`mine.spatial.enum.quality.${value.toLowerCase()}`);
}

function stableStateLabel(value: SpatialEvent['currentState']) {
  return t(`mine.spatial.enum.stableState.${value.toLowerCase()}`);
}

function showError(error: unknown) {
  const message = error instanceof ApiError && error.traceId
    ? `${t('mine.common.operationFailed')} · ${t('mine.common.traceId', { traceId: error.traceId })}`
    : t('mine.common.operationFailed');
  window.$message?.error(message);
}

defineExpose({
  activateTab,
  changeSite,
  selectObject,
  selectVersion,
  saveSelectedVersion,
  confirmPublish,
  loadMoreEvents,
  openEventDetail,
  submitReplay,
  confirmArchiveObject,
  archiveObject,
  selectedObject
});
</script>

<template>
  <NFlex vertical :size="16">
    <NFlex vertical :size="4">
      <NText tag="h1" class="m-0 text-2xl font-semibold">{{ t('mine.spatial.title') }}</NText>
      <NText depth="3">{{ t('mine.spatial.description') }}</NText>
    </NFlex>
    <MineSiteSelector @change="changeSite" @refresh="refreshCurrentSite" />

    <NCard :bordered="false">
      <NTabs :value="activeTab" type="line" animated @update:value="activateTab">
        <NTabPane name="ROUTES" :tab="t('mine.spatial.tab.routes')" />
        <NTabPane name="WAYPOINTS" :tab="t('mine.spatial.tab.waypoints')" />
        <NTabPane name="GEOFENCES" :tab="t('mine.spatial.tab.geofences')" />
        <NTabPane name="EVENTS" :tab="t('mine.spatial.tab.events')" />
      </NTabs>
    </NCard>

    <template v-if="activeTab !== 'EVENTS'">
      <NSpin :show="loading">
        <NFlex data-responsive-workbench class="flex-col xl:flex-row" :size="16" align="stretch">
          <NCard class="w-full xl:w-72" :title="t(`mine.spatial.object.${objectTab.toLowerCase()}.title`)" size="small">
            <template #header-extra>
              <NButton v-if="can('mine:spatial:edit')" data-action="create-object" size="small" type="primary" @click="openCreateObject">
                {{ t('mine.spatial.object.create') }}
              </NButton>
            </template>
            <NFlex vertical :size="12">
              <NInput
                v-model:value="objectQuery.keyword"
                clearable
                :placeholder="t('mine.spatial.object.search')"
                @keyup.enter="searchObjects"
                @clear="searchObjects"
              />
              <NSelect v-model:value="objectQuery.status" :options="objectStatusOptions" @update:value="searchObjects" />
            </NFlex>
            <NAlert v-if="loadError" class="mt-3" type="error" :title="t('mine.spatial.object.loadFailed')">
              <NButton size="small" @click="searchObjects">{{ t('mine.spatial.object.retry') }}</NButton>
            </NAlert>
            <NEmpty v-else-if="objects.length === 0" class="mt-3" :description="t('mine.spatial.object.empty')" />
            <NList v-else class="mt-3" hoverable clickable>
              <NListItem v-for="item in objects" :key="item.id" @click="selectObject(item)">
                <NFlex justify="space-between" align="center">
                  <NText :strong="selectedObject?.id === item.id">{{ item.code }}</NText>
                  <NFlex align="center" :wrap="false">
                    <NTag size="small" :type="item.status === 'ACTIVE' ? 'success' : 'default'">
                      {{ t(`mine.assets.status.${item.status.toLowerCase()}`) }}
                    </NTag>
                    <NButton
                      v-if="can('mine:spatial:edit') && item.status === 'ACTIVE'"
                      text
                      type="error"
                      :aria-label="t('mine.spatial.object.archiveAria', { code: item.code })"
                      @click.stop="confirmArchiveObject(item)"
                    >
                      {{ t('mine.spatial.object.archive') }}
                    </NButton>
                  </NFlex>
                </NFlex>
              </NListItem>
            </NList>
            <NPagination
              v-if="objectTotal > objectQuery.size"
              class="mt-3"
              :page="objectQuery.page"
              :page-size="objectQuery.size"
              :item-count="objectTotal"
              @update:page="changeObjectPage"
            />
          </NCard>

          <NCard class="min-w-0 flex-1" :title="t('mine.spatial.editor.title')" size="small">
            <NAlert v-if="coordinates.length === 0 && !loading" type="warning" :title="t('mine.spatial.editor.noCoordinateTitle')">
              {{ t('mine.spatial.editor.noCoordinate') }}
            </NAlert>
            <NEmpty v-if="!selectedObject" class="mt-3" :description="t('mine.spatial.editor.selectObject')" />
            <NFlex v-else vertical :size="16">
              <NAlert v-if="objectArchived" data-object-archived type="warning" :title="t('mine.spatial.editor.archivedTitle')">
                {{ t('mine.spatial.editor.archivedNotice') }}
              </NAlert>
              <NAlert v-else-if="versionReadonly" data-history-readonly type="info" :title="t('mine.spatial.editor.historyTitle')">
                {{ t('mine.spatial.geometry.historyReadonly') }}
              </NAlert>
              <NAlert v-else-if="isNewDraft" type="warning" :title="t('mine.spatial.editor.newDraftTitle')">
                {{ t('mine.spatial.editor.newDraftNotice') }}
              </NAlert>

              <NForm label-placement="top" :disabled="editReadonly" :data-editor-readonly="editReadonly">
                <NGrid responsive="screen" cols="1 s:2 l:3" :x-gap="16">
                  <NFormItemGi :label="t('mine.spatial.field.name')">
                    <NInput v-model:value="form.name" :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.spatial.field.name') })" />
                  </NFormItemGi>
                  <NFormItemGi :label="t('mine.spatial.field.coordinateSystem')">
                    <NSelect v-model:value="form.coordinateSystemId" :options="coordinateOptions" :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.spatial.field.coordinateSystem') })" />
                  </NFormItemGi>
                  <NFormItemGi :label="t('mine.spatial.field.versionNumber')">
                    <NInputNumber v-model:value="form.versionNumber" class="w-full" :min="1" :disabled="!isNewDraft" />
                  </NFormItemGi>
                  <NFormItemGi :label="t('mine.spatial.field.validFrom')">
                    <NInput v-model:value="form.validFrom" :placeholder="t('mine.spatial.field.isoTime')" />
                  </NFormItemGi>
                  <NFormItemGi :label="t('mine.spatial.field.validTo')">
                    <NInput v-model:value="form.validTo" :placeholder="t('mine.spatial.field.optionalIsoTime')" />
                  </NFormItemGi>

                  <template v-if="objectTab === 'WAYPOINTS'">
                    <NFormItemGi :label="t('mine.spatial.field.waypointType')">
                      <NSelect v-model:value="form.waypointType" :options="waypointTypeOptions" />
                    </NFormItemGi>
                    <NFormItemGi :label="t('mine.spatial.field.radiusMeters')"><NInputNumber v-model:value="form.radiusMeters" class="w-full" :min="0.01" /></NFormItemGi>
                    <NFormItemGi :label="t('mine.spatial.field.z')"><NInputNumber v-model:value="form.z" class="w-full" clearable /></NFormItemGi>
                  </template>
                  <template v-if="objectTab === 'GEOFENCES'">
                    <NFormItemGi :label="t('mine.spatial.field.geofenceType')"><NSelect v-model:value="form.geofenceType" :options="geofenceTypeOptions" /></NFormItemGi>
                  </template>
                  <template v-if="objectTab !== 'ROUTES'">
                    <NFormItemGi :label="t('mine.spatial.field.minZ')"><NInputNumber v-model:value="form.minZ" class="w-full" clearable /></NFormItemGi>
                    <NFormItemGi :label="t('mine.spatial.field.maxZ')"><NInputNumber v-model:value="form.maxZ" class="w-full" clearable /></NFormItemGi>
                  </template>
                  <template v-if="objectTab === 'ROUTES'">
                    <NFormItemGi :label="t('mine.spatial.field.direction')"><NSelect v-model:value="form.direction" :options="directionOptions" /></NFormItemGi>
                    <NFormItemGi :label="t('mine.spatial.field.vehicleTypes')" :span="2"><NSelect v-model:value="form.applicableVehicleTypes" multiple :options="vehicleTypeOptions" /></NFormItemGi>
                  </template>
                </NGrid>
              </NForm>

              <template v-if="objectTab === 'ROUTES'">
                <NFlex justify="space-between" align="center">
                  <NText strong>{{ t('mine.spatial.route.sequenceTitle') }}</NText>
                  <NButton v-if="!editReadonly" size="small" @click="addRouteWaypoint">{{ t('mine.spatial.route.addWaypoint') }}</NButton>
                </NFlex>
                <NEmpty v-if="form.routeWaypoints.length === 0" :description="t('mine.spatial.route.empty')" />
                <NList v-else bordered>
                  <NListItem v-for="(item, index) in form.routeWaypoints" :key="`${item.sequenceNumber}-${index}`">
                    <NFlex class="flex-col lg:flex-row" align="center" :size="12">
                      <NTag round>{{ item.sequenceNumber }}</NTag>
                      <NSelect v-model:value="item.waypointVersionId" class="min-w-48 flex-1" :options="waypointVersionOptions" :disabled="editReadonly" />
                      <NCheckbox v-model:checked="item.stopRequired" :disabled="editReadonly">{{ t('mine.spatial.route.stopRequired') }}</NCheckbox>
                      <NInputNumber v-model:value="item.expectedDwellSeconds" class="w-44" clearable :disabled="editReadonly" :placeholder="t('mine.spatial.route.dwellSeconds')" />
                      <NFlex v-if="!editReadonly" :wrap="false">
                        <NButton size="small" :disabled="index === 0" @click="moveRouteWaypoint(index, -1)">{{ t('mine.spatial.geometry.moveUp') }}</NButton>
                        <NButton size="small" :disabled="index === form.routeWaypoints.length - 1" @click="moveRouteWaypoint(index, 1)">{{ t('mine.spatial.geometry.moveDown') }}</NButton>
                        <NButton size="small" type="error" @click="removeRouteWaypoint(index)">{{ t('mine.spatial.geometry.removePoint') }}</NButton>
                      </NFlex>
                    </NFlex>
                  </NListItem>
                </NList>
              </template>

              <SpatialGeometryEditor
                v-if="objectTab === 'WAYPOINTS'"
                v-model="waypointGeometry"
                geometry-type="POINT"
                :readonly="editReadonly"
                :loading="loading"
                @validity-change="geometryValid = $event"
              />
              <SpatialGeometryEditor
                v-else-if="objectTab === 'GEOFENCES'"
                v-model="form.vertices"
                geometry-type="POLYGON"
                :readonly="editReadonly"
                :loading="loading"
                @validity-change="geometryValid = $event"
              />
              <SpatialGeometryEditor
                v-else
                :model-value="routeGeometry"
                :geometry-type="geometryType"
                readonly
                :loading="loading"
              />

              <NCard v-if="objectTab !== 'ROUTES'" size="small" :title="t('mine.spatial.policy.title')">
                <NGrid responsive="screen" cols="1 s:2 l:3" :x-gap="16">
                  <NFormItemGi :label="t('mine.spatial.policy.boundaryTolerance')"><NInputNumber v-model:value="form.detectionPolicy.boundaryToleranceMeters" class="w-full" :disabled="editReadonly" :min="0" /></NFormItemGi>
                  <NFormItemGi :label="t('mine.spatial.policy.enterConfirm')"><NInputNumber v-model:value="form.detectionPolicy.enterConfirmPoints" class="w-full" :disabled="editReadonly" :min="1" /></NFormItemGi>
                  <NFormItemGi :label="t('mine.spatial.policy.exitConfirm')"><NInputNumber v-model:value="form.detectionPolicy.exitConfirmPoints" class="w-full" :disabled="editReadonly" :min="1" /></NFormItemGi>
                  <NFormItemGi :label="t('mine.spatial.policy.dwell')"><NInputNumber v-model:value="form.detectionPolicy.dwellSeconds" class="w-full" :disabled="editReadonly" :min="0" /></NFormItemGi>
                  <NFormItemGi :label="t('mine.spatial.policy.allowedLateness')"><NInputNumber v-model:value="form.detectionPolicy.allowedLatenessSeconds" class="w-full" :disabled="editReadonly" :min="0" /></NFormItemGi>
                  <NFormItemGi :label="t('mine.spatial.policy.idleFlush')"><NInputNumber v-model:value="form.detectionPolicy.idleFlushSeconds" class="w-full" :disabled="editReadonly" :min="1" /></NFormItemGi>
                </NGrid>
              </NCard>

              <NFlex justify="end">
                <NButton v-if="can('mine:spatial:edit') && !editReadonly" data-action="save-version" :loading="saving" @click="saveSelectedVersion">
                  {{ t('mine.common.save') }}
                </NButton>
                <NButton v-if="can('mine:spatial:publish') && selectedObject?.status === 'ACTIVE' && selectedVersion?.status === 'DRAFT'" data-action="publish-version" type="primary" :loading="saving" @click="confirmPublish">
                  {{ t('mine.spatial.publish.action') }}
                </NButton>
              </NFlex>
            </NFlex>
          </NCard>

          <NCard class="w-full xl:w-72" :title="t('mine.spatial.version.title')" size="small">
            <template #header-extra>
              <NButton v-if="selectedObject?.status === 'ACTIVE' && can('mine:spatial:edit')" data-action="new-draft" size="small" @click="startNewDraft">{{ t('mine.spatial.version.newDraft') }}</NButton>
            </template>
            <NEmpty v-if="!selectedObject" :description="t('mine.spatial.version.selectObject')" />
            <NEmpty v-else-if="versions.length === 0" :description="t('mine.spatial.version.empty')" />
            <NList v-else hoverable clickable>
              <NListItem v-for="item in versions" :key="item.id" @click="selectVersion(item)">
                <NFlex justify="space-between" align="center">
                  <NFlex vertical :size="2">
                    <NText :strong="selectedVersion?.id === item.id">v{{ item.versionNumber }} · {{ item.name }}</NText>
                    <NText depth="3" class="text-xs">{{ formatTime(item.updatedAt) }}</NText>
                  </NFlex>
                  <NTag size="small" :type="versionStatusType(item.status)">{{ t(`mine.spatial.version.status.${item.status.toLowerCase()}`) }}</NTag>
                </NFlex>
              </NListItem>
            </NList>
          </NCard>
        </NFlex>
      </NSpin>
    </template>

    <template v-else>
      <NAlert v-if="!can('mine:spatial:event:view')" type="warning" :title="t('mine.spatial.event.noPermissionTitle')">
        {{ t('mine.spatial.event.noPermission') }}
      </NAlert>
      <NFlex v-else vertical :size="16">
        <NCard :title="t('mine.spatial.event.filterTitle')" size="small">
          <NGrid responsive="screen" cols="1 s:2 l:4 xxl:7" :x-gap="16">
            <NFormItemGi :label="t('mine.spatial.event.zoneType')"><NSelect v-model:value="eventFilter.zoneType" clearable :options="zoneTypeOptions" /></NFormItemGi>
            <NFormItemGi :label="t('mine.spatial.event.zoneId')"><NInput v-model:value="eventFilter.zoneId" clearable /></NFormItemGi>
            <NFormItemGi :label="t('mine.spatial.event.subjectType')"><NSelect v-model:value="eventFilter.subjectType" clearable :options="subjectTypeOptions" /></NFormItemGi>
            <NFormItemGi :label="t('mine.spatial.event.subjectId')"><NInput v-model:value="eventFilter.subjectId" clearable /></NFormItemGi>
            <NFormItemGi :label="t('mine.spatial.event.type')"><NSelect v-model:value="eventFilter.eventType" clearable :options="eventTypeOptions" /></NFormItemGi>
            <NFormItemGi :label="t('mine.spatial.field.validFrom')"><NInput v-model:value="eventFilter.from" /></NFormItemGi>
            <NFormItemGi :label="t('mine.spatial.field.validTo')"><NInput v-model:value="eventFilter.to" /></NFormItemGi>
            <NFormItemGi :show-label="false"><NButton type="primary" :loading="eventLoading" @click="searchEvents">{{ t('mine.common.search') }}</NButton></NFormItemGi>
          </NGrid>
        </NCard>
        <NCard :title="t('mine.spatial.event.title')" size="small">
          <template #header-extra>
            <NButton v-if="can('mine:spatial:replay')" data-action="replay" @click="openReplay()">{{ t('mine.spatial.replay.action') }}</NButton>
          </template>
          <NAlert v-if="eventError" class="mb-4" type="error" :title="t('mine.spatial.event.loadFailed')">
            <NButton size="small" @click="searchEvents">{{ t('mine.spatial.object.retry') }}</NButton>
          </NAlert>
          <NDataTable :columns="eventColumns" :data="events" :loading="eventLoading" :row-key="row => row.eventId" :scroll-x="900" />
          <NFlex v-if="eventCursor" class="mt-4" justify="center">
            <NButton :loading="eventLoading" @click="loadMoreEvents">{{ t('mine.spatial.event.loadMore') }}</NButton>
          </NFlex>
        </NCard>
      </NFlex>
    </template>

    <NModal v-model:show="createObjectVisible" preset="card" class="w-[min(92vw,32rem)]" :title="t('mine.spatial.object.createTitle')">
      <NForm label-placement="top">
        <NFormItem :label="t('mine.spatial.object.code')"><NInput v-model:value="objectCode" :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.spatial.object.code') })" /></NFormItem>
      </NForm>
      <template #footer>
        <NFlex justify="end">
          <NButton @click="createObjectVisible = false">{{ t('mine.common.cancel') }}</NButton>
          <NButton type="primary" :loading="saving" @click="saveObject">{{ t('mine.common.save') }}</NButton>
        </NFlex>
      </template>
    </NModal>

    <NDrawer v-model:show="eventDetailVisible" width="min(92vw, 36rem)">
      <NDrawerContent :title="t('mine.spatial.event.detailTitle')" closable>
        <NSpin :show="!eventDetail">
          <NDescriptions v-if="eventDetail" bordered label-placement="left" :column="1">
            <NDescriptionsItem :label="t('mine.spatial.event.sequence')">{{ eventDetail.eventSequence }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.spatial.event.type')">{{ eventTypeLabel(eventDetail.eventType) }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.spatial.event.zone')">{{ zoneTypeLabel(eventDetail.zoneType) }} · {{ eventDetail.zoneVersionId }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.spatial.event.stateTransition')">{{ stableStateLabel(eventDetail.previousState) }} → {{ stableStateLabel(eventDetail.currentState) }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.spatial.event.confirmationPoints')">{{ eventDetail.confirmationPoints }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.spatial.event.classification')">{{ classificationLabel(eventDetail.evidence.classification) }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.spatial.event.quality')">{{ qualityLabel(eventDetail.evidence.qualityStatus) }}<template v-if="eventDetail.evidence.qualityReason"> · {{ eventDetail.evidence.qualityReason }}</template></NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.spatial.event.coordinate')">({{ eventDetail.evidence.localX }}, {{ eventDetail.evidence.localY }}, {{ eventDetail.evidence.localZ ?? t('mine.common.empty') }})</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.spatial.event.anchorLocationEventId')">{{ eventDetail.anchorLocationEventId }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.spatial.event.contentHash')">{{ eventDetail.evidence.zoneContentHash }}</NDescriptionsItem>
          </NDescriptions>
        </NSpin>
      </NDrawerContent>
    </NDrawer>

    <NModal v-model:show="replayVisible" preset="card" class="w-[min(92vw,40rem)]" :title="t('mine.spatial.replay.title')">
      <NAlert type="warning" class="mb-4">{{ t('mine.spatial.replay.notice') }}</NAlert>
      <NForm label-placement="top">
        <NGrid responsive="screen" cols="1 s:2" :x-gap="16">
          <NFormItemGi :label="t('mine.spatial.event.zoneType')"><NSelect v-model:value="replayForm.zoneType" :options="zoneTypeOptions" /></NFormItemGi>
          <NFormItemGi :label="t('mine.spatial.replay.zoneVersionId')"><NInput v-model:value="replayForm.zoneVersionId" /></NFormItemGi>
          <NFormItemGi :label="t('mine.spatial.field.validFrom')"><NInput v-model:value="replayForm.from" /></NFormItemGi>
          <NFormItemGi :label="t('mine.spatial.field.validTo')"><NInput v-model:value="replayForm.to" /></NFormItemGi>
          <NFormItemGi :label="t('mine.spatial.replay.reason')" :span="2"><NInput v-model:value="replayForm.reason" type="textarea" /></NFormItemGi>
        </NGrid>
      </NForm>
      <template #footer>
        <NFlex justify="end">
          <NButton @click="replayVisible = false">{{ t('mine.common.cancel') }}</NButton>
          <NButton type="primary" :loading="replaySubmitting" @click="submitReplay()">{{ t('mine.spatial.replay.submit') }}</NButton>
        </NFlex>
      </template>
    </NModal>
  </NFlex>
</template>
