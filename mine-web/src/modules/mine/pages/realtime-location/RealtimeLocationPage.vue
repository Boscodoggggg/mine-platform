<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
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
  NFlex,
  NGi,
  NGrid,
  NInput,
  NSelect,
  NSpace,
  NStatistic,
  NTag,
  NText
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import ListActionButton from '@/components/common/list-action-button.vue';
import ListSearchPanel from '@/components/common/list-search-panel.vue';
import MineCoordinateCanvas from '../../components/MineCoordinateCanvas.vue';
import MineSiteSelector from '../../components/MineSiteSelector.vue';
import { getMineLocationTrajectory, getMinePeople, getMineSpatialEvent, getMineVehicles } from '../../api/mine-api';
import type {
  LocationSubjectType,
  LocationTrajectory,
  Person,
  SpatialEventDetail,
  Vehicle
} from '../../api/mine-api';
import { useMineLocationRealtime } from '../../composables/useMineLocationRealtime';
import type { MineLocationItem, MineLocationRuntimeStatus } from '../../composables/useMineLocationRealtime';
import { useMineSpatialEvents } from '../../composables/useMineSpatialEvents';
import type {
  MineSpatialEvent,
  MineSpatialLayerStatus
} from '../../composables/useMineSpatialEvents';
import { useMineContextStore } from '../../stores/mine-context';
import { useAuthStore } from '@/store/modules/auth';

type SubjectFilter = 'ALL' | LocationSubjectType;
type QualityFilter = 'ALL' | MineLocationItem['qualityStatus'];
type Freshness = 'FRESH' | 'STALE' | 'OFFLINE';
type TrajectoryRange = '1h' | '6h' | '24h';

const { t, locale } = useI18n();
const mineContext = useMineContextStore();
const authStore = useAuthStore();
const realtime = useMineLocationRealtime();
const spatial = useMineSpatialEvents();
const loadingCatalogs = ref(false);
const vehicles = ref<Vehicle[]>([]);
const people = ref<Person[]>([]);
const clock = ref(Date.now());
const filter = reactive<{ keyword: string; subject: SubjectFilter; quality: QualityFilter }>({
  keyword: '',
  subject: 'ALL',
  quality: 'ALL'
});
const selectedLocation = ref<MineLocationItem | null>(null);
const detailVisible = ref(false);
const trajectoryLoading = ref(false);
const trajectoryRange = ref<TrajectoryRange>('1h');
const trajectorySubjectType = ref<LocationSubjectType>('TAG');
const trajectorySubjectId = ref('');
const trajectory = ref<LocationTrajectory | null>(null);
const layers = reactive({ regions: true, events: true });
const selectedSpatialEvent = ref<MineSpatialEvent | null>(null);
const spatialEventDetail = ref<SpatialEventDetail | null>(null);
const spatialEventDetailVisible = ref(false);
const spatialEventDetailLoading = ref(false);
const simulationMode = import.meta.env.VITE_LOCATION_SIMULATION === 'true';
let clockTimer: ReturnType<typeof setInterval> | null = null;
let trajectoryGeneration = 0;
let spatialDetailGeneration = 0;
let disposed = false;

const subjectFilterOptions = computed<SelectOption[]>(() => [
  { label: t('mine.location.filter.all'), value: 'ALL' },
  { label: t('mine.location.subject.vehicle'), value: 'VEHICLE' },
  { label: t('mine.location.subject.person'), value: 'PERSON' },
  { label: t('mine.location.subject.tag'), value: 'TAG' }
]);
const qualityFilterOptions = computed<SelectOption[]>(() => [
  { label: t('mine.location.filter.all'), value: 'ALL' },
  { label: t('mine.location.quality.good'), value: 'GOOD' },
  { label: t('mine.location.quality.degraded'), value: 'DEGRADED' }
]);
const trajectoryRangeOptions = computed<SelectOption[]>(() => [
  { label: t('mine.location.range.hour'), value: '1h' },
  { label: t('mine.location.range.sixHours'), value: '6h' },
  { label: t('mine.location.range.day'), value: '24h' }
]);
const trajectorySubjectOptions = computed<SelectOption[]>(() => {
  const item = selectedLocation.value;
  if (!item) return [];
  const options: SelectOption[] = [
    { label: `${t('mine.location.subject.tag')} · ${item.tagCode}`, value: `TAG:${item.tagId}` }
  ];
  if (item.vehicleId) {
    options.unshift({
      label: `${t('mine.location.subject.vehicle')} · ${vehicleName(item.vehicleId)}`,
      value: `VEHICLE:${item.vehicleId}`
    });
  }
  if (item.personId) {
    options.unshift({
      label: `${t('mine.location.subject.person')} · ${personName(item.personId)}`,
      value: `PERSON:${item.personId}`
    });
  }
  return options;
});
const trajectorySubjectKey = computed({
  get: () => `${trajectorySubjectType.value}:${trajectorySubjectId.value}`,
  set: value => {
    const [type, id] = value.split(':', 2);
    if (!id || !['TAG', 'VEHICLE', 'PERSON'].includes(type || '')) return;
    trajectorySubjectType.value = type as LocationSubjectType;
    trajectorySubjectId.value = id;
  }
});
const filteredLocations = computed(() => {
  const keyword = filter.keyword.trim().toLocaleLowerCase(locale.value);
  return realtime.locations.value.filter(item => {
    if (filter.quality !== 'ALL' && item.qualityStatus !== filter.quality) return false;
    if (filter.subject !== 'ALL' && !matchesSubject(item, filter.subject)) return false;
    if (!keyword) return true;
    return [item.tagCode, item.vehicleId ? vehicleName(item.vehicleId) : '', item.personId ? personName(item.personId) : '']
      .some(value => value.toLocaleLowerCase(locale.value).includes(keyword));
  });
});
const degradedCount = computed(() => realtime.locations.value.filter(item => item.qualityStatus === 'DEGRADED').length);
const staleCount = computed(() => realtime.locations.value.filter(item => freshness(item) !== 'FRESH').length);
const statusType = computed(() => {
  if (realtime.status.value === 'LIVE') return 'success' as const;
  if (realtime.status.value === 'ERROR') return 'error' as const;
  if (realtime.status.value === 'RECONNECTING') return 'warning' as const;
  return 'info' as const;
});
const trajectoryPoints = computed(() => trajectory.value?.items ?? []);
const canViewRegions = computed(() => hasPermission(authStore.userInfo.buttons, 'mine:spatial:view'));
const canViewEvents = computed(() => hasPermission(authStore.userInfo.buttons, 'mine:spatial:event:view'));
const spatialLayerDegraded = computed(
  () =>
    (canViewRegions.value && spatial.regionStatus.value === 'DEGRADED') ||
    (canViewEvents.value && spatial.eventStatus.value === 'DEGRADED')
);
const recentSpatialEvents = computed(() => spatial.events.value.slice(0, 20));
const selectedSpatialEvidence = computed(
  () => spatialEventDetail.value?.evidence ?? selectedSpatialEvent.value?.evidence ?? null
);
const columns = computed<DataTableColumns<MineLocationItem>>(() => [
  { title: t('mine.location.table.tagCode'), key: 'tagCode', minWidth: 120 },
  { title: t('mine.location.table.subject'), key: 'subject', minWidth: 160, render: row => subjectLabel(row) },
  { title: t('mine.location.table.coordinate'), key: 'coordinate', minWidth: 170, render: row => `(${formatNumber(row.localX)}, ${formatNumber(row.localY)})` },
  { title: t('mine.location.table.speed'), key: 'speedMetersPerSecond', width: 100, render: row => nullableNumber(row.speedMetersPerSecond) },
  { title: t('mine.location.table.accuracy'), key: 'accuracyMeters', width: 100, render: row => nullableNumber(row.accuracyMeters) },
  { title: t('mine.location.table.occurredAt'), key: 'occurredAt', minWidth: 175, render: row => formatTime(row.occurredAt) },
  { title: t('mine.location.table.quality'), key: 'qualityStatus', width: 100, render: row => qualityTag(row) },
  { title: t('mine.location.table.freshness'), key: 'freshness', width: 100, render: row => freshnessTag(row) },
  { title: t('mine.common.action'), key: 'actions', width: 90, render: row => h(NButton, { text: true, type: 'primary', 'data-action': 'location-details', onClick: () => openDetails(row) }, () => t('mine.location.table.details')) }
]);
const spatialColumns = computed<DataTableColumns<MineSpatialEvent>>(() => [
  {
    title: t('mine.spatial.event.type'),
    key: 'eventType',
    width: 100,
    render: row => spatialEventTypeTag(row.eventType)
  },
  {
    title: t('mine.spatial.event.subject'),
    key: 'subject',
    minWidth: 180,
    render: row => spatialSubjectLabel(row)
  },
  {
    title: t('mine.spatial.event.zone'),
    key: 'zone',
    minWidth: 160,
    render: row => spatialZoneName(row)
  },
  {
    title: t('mine.spatial.event.occurredAt'),
    key: 'occurredAt',
    minWidth: 180,
    render: row => formatTime(row.occurredAt)
  },
  {
    title: t('mine.spatial.event.quality'),
    key: 'quality',
    width: 100,
    render: row => spatialQualityTag(row)
  },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 90,
    render: row =>
      h(
        NButton,
        {
          text: true,
          type: 'primary',
          'data-action': 'spatial-event-details',
          onClick: () => openSpatialEventDetails(row)
        },
        () => t('mine.spatial.event.details')
      )
  }
]);

onMounted(initialize);
watch([canViewRegions, canViewEvents], ([regions, events], [previousRegions, previousEvents]) => {
  if (regions === previousRegions && events === previousEvents) return;
  void applySpatialCapabilities(regions, events);
});
onBeforeUnmount(() => {
  disposed = true;
  if (clockTimer) clearInterval(clockTimer);
  trajectoryGeneration += 1;
  spatialDetailGeneration += 1;
  realtime.stop();
  spatial.stop();
});

async function initialize() {
  try {
    await mineContext.refreshContext();
    if (!mineContext.selectedSiteId && mineContext.sites.length === 1) {
      mineContext.selectSite(mineContext.sites[0]!.id);
    }
    if (mineContext.selectedSiteId) {
      await loadSiteData(mineContext.selectedSiteId);
    }
  } catch (error) {
    showError(error);
  }
  if (!disposed) {
    clockTimer = setInterval(() => {
      clock.value = Date.now();
    }, 15_000);
  }
}

async function changeSite(siteId: string) {
  mineContext.selectSite(siteId);
  closeDetails();
  closeSpatialEventDetails();
  filter.keyword = '';
  await loadSiteData(siteId);
}

async function refreshCurrentSite() {
  if (!mineContext.selectedSiteId) return;
  await loadSiteData(mineContext.selectedSiteId);
}

async function loadSiteData(siteId: string) {
  await Promise.all([
    loadCatalogs(siteId),
    realtime.selectSite(siteId),
    spatial.selectSite(siteId, { regions: canViewRegions.value, events: canViewEvents.value })
  ]);
}

async function applySpatialCapabilities(regions: boolean, events: boolean) {
  const siteId = mineContext.selectedSiteId;
  if (disposed || !siteId) return;
  closeSpatialEventDetails();
  try {
    await spatial.selectSite(siteId, { regions, events });
  } catch (error) {
    showError(error);
  }
}

async function loadCatalogs(siteId: string) {
  loadingCatalogs.value = true;
  try {
    const [vehiclePage, personPage] = await Promise.all([
      getMineVehicles(siteId, { page: 1, size: 100, status: 'ACTIVE' }),
      getMinePeople(siteId, { page: 1, size: 100, status: 'ACTIVE' })
    ]);
    vehicles.value = vehiclePage.items;
    people.value = personPage.items;
  } catch (error) {
    vehicles.value = [];
    people.value = [];
    showError(error);
  } finally {
    loadingCatalogs.value = false;
  }
}

async function openDetails(item: MineLocationItem) {
  selectedLocation.value = item;
  detailVisible.value = true;
  if (item.vehicleId) {
    trajectorySubjectType.value = 'VEHICLE';
    trajectorySubjectId.value = item.vehicleId;
  } else if (item.personId) {
    trajectorySubjectType.value = 'PERSON';
    trajectorySubjectId.value = item.personId;
  } else {
    trajectorySubjectType.value = 'TAG';
    trajectorySubjectId.value = item.tagId;
  }
  await loadTrajectory();
}

async function loadTrajectory() {
  const siteId = mineContext.selectedSiteId;
  if (!siteId || !trajectorySubjectId.value) return;
  const generation = ++trajectoryGeneration;
  const to = new Date(clock.value);
  const from = new Date(to.getTime() - rangeMillis(trajectoryRange.value));
  trajectoryLoading.value = true;
  try {
    const response = await getMineLocationTrajectory(siteId, {
      subjectType: trajectorySubjectType.value,
      subjectId: trajectorySubjectId.value,
      from: from.toISOString(),
      to: to.toISOString(),
      watermark: realtime.watermark.value,
      maxPoints: 5000
    });
    if (generation === trajectoryGeneration) trajectory.value = response;
  } catch (error) {
    if (generation === trajectoryGeneration) showError(error);
  } finally {
    if (generation === trajectoryGeneration) trajectoryLoading.value = false;
  }
}

function closeDetails() {
  trajectoryGeneration += 1;
  detailVisible.value = false;
  selectedLocation.value = null;
  trajectory.value = null;
}

async function openSpatialEventDetails(event: MineSpatialEvent) {
  const siteId = mineContext.selectedSiteId;
  if (!siteId || event.siteId !== siteId || !canViewEvents.value) return;
  const generation = ++spatialDetailGeneration;
  selectedSpatialEvent.value = event;
  spatialEventDetail.value = null;
  spatialEventDetailVisible.value = true;
  spatialEventDetailLoading.value = true;
  try {
    const detail = await getMineSpatialEvent(siteId, event.eventId);
    if (generation === spatialDetailGeneration && mineContext.selectedSiteId === siteId) {
      spatialEventDetail.value = detail;
    }
  } catch (error) {
    if (generation === spatialDetailGeneration) showError(error);
  } finally {
    if (generation === spatialDetailGeneration) spatialEventDetailLoading.value = false;
  }
}

function closeSpatialEventDetails() {
  spatialDetailGeneration += 1;
  spatialEventDetailVisible.value = false;
  spatialEventDetailLoading.value = false;
  selectedSpatialEvent.value = null;
  spatialEventDetail.value = null;
}

function resetFilters() {
  Object.assign(filter, { keyword: '', subject: 'ALL', quality: 'ALL' });
}

function matchesSubject(item: MineLocationItem, subject: LocationSubjectType) {
  if (subject === 'VEHICLE') return Boolean(item.vehicleId);
  if (subject === 'PERSON') return Boolean(item.personId);
  return !item.vehicleId && !item.personId;
}

function subjectLabel(item: MineLocationItem) {
  if (item.vehicleId) return `${t('mine.location.subject.vehicle')} · ${vehicleName(item.vehicleId)}`;
  if (item.personId) return `${t('mine.location.subject.person')} · ${personName(item.personId)}`;
  return t('mine.location.subject.unbound');
}

function vehicleName(id: string) {
  const vehicle = vehicles.value.find(item => item.id === id);
  return vehicle ? `${vehicle.name} · ${vehicle.vehicleCode}` : id;
}

function personName(id: string) {
  const person = people.value.find(item => item.id === id);
  return person ? `${person.name} · ${person.personCode}` : id;
}

function freshness(item: MineLocationItem): Freshness {
  const age = Math.max(0, clock.value - Date.parse(item.occurredAt));
  if (age <= 60_000) return 'FRESH';
  if (age <= 5 * 60_000) return 'STALE';
  return 'OFFLINE';
}

function qualityTag(item: MineLocationItem) {
  return h(
    NTag,
    { size: 'small', bordered: false, type: item.qualityStatus === 'GOOD' ? 'success' : 'warning' },
    () => t(`mine.location.quality.${item.qualityStatus.toLowerCase()}`)
  );
}

function freshnessTag(item: MineLocationItem) {
  const value = freshness(item);
  const type = value === 'FRESH' ? 'success' : value === 'STALE' ? 'warning' : 'error';
  return h(NTag, { size: 'small', bordered: false, type }, () => t(`mine.location.freshness.${value.toLowerCase()}`));
}

function spatialEventTypeLabel(value: MineSpatialEvent['eventType']) {
  return t(`mine.spatial.enum.eventType.${value.toLowerCase()}`);
}

function spatialEventTypeTag(value: MineSpatialEvent['eventType']) {
  const type = value === 'ENTER' ? 'success' : value === 'EXIT' ? 'info' : 'warning';
  return h(NTag, { size: 'small', bordered: false, type }, () => spatialEventTypeLabel(value));
}

function spatialQualityTag(event: MineSpatialEvent) {
  const quality = event.evidence?.qualityStatus;
  if (!quality) return t('mine.common.empty');
  return h(
    NTag,
    { size: 'small', bordered: false, type: quality === 'GOOD' ? 'success' : 'warning' },
    () => t(`mine.spatial.enum.quality.${quality.toLowerCase()}`)
  );
}

function spatialSubjectLabel(event: MineSpatialEvent) {
  if (event.vehicleId) return `${t('mine.location.subject.vehicle')} · ${vehicleName(event.vehicleId)}`;
  if (event.personId) return `${t('mine.location.subject.person')} · ${personName(event.personId)}`;
  return `${t('mine.location.subject.tag')} · ${event.tagId}`;
}

function spatialZoneName(event: MineSpatialEvent) {
  return spatial.regions.value.find(region => region.zoneId === event.zoneId)?.name ?? event.zoneId;
}

function spatialNotice(event: MineSpatialEvent) {
  return t('mine.location.spatial.notice', {
    subject: spatialSubjectLabel(event),
    type: spatialEventTypeLabel(event.eventType),
    zone: spatialZoneName(event)
  });
}

function spatialLayerStatusLabel(status: MineSpatialLayerStatus) {
  return t(`mine.location.spatial.status.${status.toLowerCase()}`);
}

function spatialLayerStatusType(status: MineSpatialLayerStatus) {
  if (status === 'READY' || status === 'LIVE') return 'success' as const;
  if (status === 'DEGRADED') return 'error' as const;
  if (status === 'RESYNCING') return 'warning' as const;
  return 'info' as const;
}

function statusLabel(status: MineLocationRuntimeStatus) {
  return t(`mine.location.status.${status.toLowerCase()}`);
}

function sourceLabel() {
  if (realtime.source.value === 'REDIS') return t('mine.location.sourceRedis');
  if (realtime.source.value === 'TIMESCALEDB') return t('mine.location.sourceTimescaledb');
  return t('mine.common.empty');
}

function formatNumber(value: number) {
  return new Intl.NumberFormat(locale.value, { maximumFractionDigits: 2 }).format(value);
}

function nullableNumber(value: number | null) {
  return value == null ? t('mine.common.empty') : formatNumber(value);
}

function formatTime(value: string) {
  return new Date(value).toLocaleString(locale.value, { hour12: false });
}

function rangeMillis(value: TrajectoryRange) {
  if (value === '1h') return 60 * 60 * 1000;
  if (value === '6h') return 6 * 60 * 60 * 1000;
  return 24 * 60 * 60 * 1000;
}

function showError(error: unknown) {
  if (error instanceof ApiError) return window.$message?.error(error.message);
  window.$message?.error(t('mine.common.operationFailed'));
}

defineExpose({
  trajectoryRange,
  openDetails,
  loadTrajectory,
  changeSite,
  filter,
  layers,
  openSpatialEventDetails
});
</script>

<template>
  <NSpace vertical :size="16">
    <MineSiteSelector @change="changeSite" @refresh="refreshCurrentSite" />

    <NCard :bordered="false" size="small">
      <NFlex data-responsive-stack class="flex-col md:flex-row" align="center" justify="space-between">
        <div>
          <NText strong>{{ t('mine.location.title') }}</NText>
          <div><NText depth="3">{{ t('mine.location.description') }}</NText></div>
        </div>
        <NFlex align="center">
          <NTag v-if="simulationMode" type="warning">{{ t('mine.location.simulation') }}</NTag>
          <NTag :type="statusType">{{ statusLabel(realtime.status.value) }}</NTag>
        </NFlex>
      </NFlex>
    </NCard>

    <NAlert v-if="realtime.status.value === 'RECONNECTING'" type="warning">
      {{ statusLabel(realtime.status.value) }}
    </NAlert>
    <NAlert v-if="realtime.status.value === 'ERROR'" type="error">
      {{ statusLabel(realtime.status.value) }}
    </NAlert>
    <NAlert v-if="realtime.truncated.value" type="warning">{{ t('mine.location.truncated') }}</NAlert>
    <NAlert v-if="spatialLayerDegraded" type="warning" :title="t('mine.location.spatial.degradedTitle')">
      {{ t('mine.location.spatial.degraded') }}
    </NAlert>

    <NGrid responsive="screen" cols="2 s:4" :x-gap="12" :y-gap="12">
      <NGi><NCard size="small"><NStatistic :label="t('mine.location.currentCount')" :value="realtime.locations.value.length" /></NCard></NGi>
      <NGi><NCard size="small"><NStatistic :label="t('mine.location.degradedCount')" :value="degradedCount" /></NCard></NGi>
      <NGi><NCard size="small"><NStatistic :label="t('mine.location.staleCount')" :value="staleCount" /></NCard></NGi>
      <NGi><NCard size="small"><NStatistic :label="t('mine.location.source')" :value="sourceLabel()" /></NCard></NGi>
    </NGrid>

    <ListSearchPanel>
      <NFlex data-responsive-stack class="flex-col md:flex-row" align="center">
        <NInput v-model:value="filter.keyword" clearable :placeholder="t('mine.location.filter.keyword')" class="w-full md:flex-1" />
        <NSelect v-model:value="filter.subject" :options="subjectFilterOptions" class="w-full md:w-48" />
        <NSelect v-model:value="filter.quality" :options="qualityFilterOptions" class="w-full md:w-48" />
        <ListActionButton kind="reset" @click="resetFilters">{{ t('mine.common.reset') }}</ListActionButton>
      </NFlex>
    </ListSearchPanel>

    <NCard :title="t('mine.location.spatial.layerTitle')" :bordered="false" size="small">
      <NFlex data-responsive-stack class="flex-col md:flex-row" align="center" justify="space-between">
        <NFlex align="center">
          <NCheckbox v-model:checked="layers.regions" :disabled="!canViewRegions">
            {{ t('mine.location.spatial.regions') }}
          </NCheckbox>
          <NTag size="small" :type="spatialLayerStatusType(spatial.regionStatus.value)">
            {{ spatialLayerStatusLabel(spatial.regionStatus.value) }}
          </NTag>
        </NFlex>
        <NFlex align="center">
          <NCheckbox v-model:checked="layers.events" :disabled="!canViewEvents">
            {{ t('mine.location.spatial.events') }}
          </NCheckbox>
          <NTag size="small" :type="spatialLayerStatusType(spatial.eventStatus.value)">
            {{ spatialLayerStatusLabel(spatial.eventStatus.value) }}
          </NTag>
        </NFlex>
      </NFlex>
    </NCard>

    <NCard :bordered="false" size="small">
      <MineCoordinateCanvas
        :items="filteredLocations"
        :trajectory="trajectoryPoints"
        :regions="spatial.regions.value"
        :events="spatial.events.value"
        :show-regions="layers.regions && canViewRegions"
        :show-events="layers.events && canViewEvents"
        :selected-tag-id="selectedLocation?.tagId ?? null"
        :loading="realtime.status.value === 'LOADING' || trajectoryLoading"
      />
    </NCard>

    <NAlert
      v-if="canViewEvents && layers.events && spatial.lastEvent.value"
      :type="spatial.lastEvent.value.eventType === 'ENTER' ? 'success' : spatial.lastEvent.value.eventType === 'EXIT' ? 'info' : 'warning'"
      :title="t('mine.location.spatial.noticeTitle')"
    >
      <NFlex align="center" justify="space-between">
        <NText>{{ spatialNotice(spatial.lastEvent.value) }}</NText>
        <NButton text type="primary" @click="openSpatialEventDetails(spatial.lastEvent.value)">
          {{ t('mine.spatial.event.details') }}
        </NButton>
      </NFlex>
    </NAlert>

    <NCard v-if="canViewEvents" :title="t('mine.location.spatial.recentEvents')" :bordered="false" size="small">
      <NDataTable
        :columns="spatialColumns"
        :data="recentSpatialEvents"
        :loading="spatial.eventStatus.value === 'LOADING' || spatial.eventStatus.value === 'RESYNCING'"
        :row-key="row => row.eventId"
        :scroll-x="830"
        :pagination="false"
      />
    </NCard>

    <NCard :title="t('mine.location.currentCount')" :bordered="false" size="small">
      <NDataTable
        :columns="columns"
        :data="filteredLocations"
        :loading="realtime.status.value === 'LOADING' || loadingCatalogs"
        :row-key="row => row.tagId"
        :scroll-x="1260"
        :pagination="{ pageSize: 20, showSizePicker: true, pageSizes: [20, 50, 100] }"
      />
    </NCard>

    <NDrawer v-model:show="detailVisible" width="min(640px, 92vw)" placement="right" @after-leave="closeDetails">
      <NDrawerContent :title="t('mine.location.detail.title')" closable>
        <NSpace v-if="selectedLocation" vertical :size="16">
          <NDescriptions bordered :column="1" :title="t('mine.location.detail.basic')">
            <NDescriptionsItem :label="t('mine.location.table.tagCode')">{{ selectedLocation.tagCode }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.location.table.subject')">{{ subjectLabel(selectedLocation) }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.location.table.coordinate')">({{ formatNumber(selectedLocation.localX) }}, {{ formatNumber(selectedLocation.localY) }})</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.location.detail.sequence')">{{ selectedLocation.ingestSequence }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.location.detail.eventId')">{{ selectedLocation.eventId }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.location.detail.deviceId')">{{ selectedLocation.sourceDeviceId }}</NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.location.detail.coordinateSystemId')">{{ selectedLocation.coordinateSystemId }}</NDescriptionsItem>
          </NDescriptions>

          <NCard size="small" :title="t('mine.location.detail.trajectory')">
            <NFlex class="flex-col md:flex-row" align="center">
              <NSelect v-model:value="trajectorySubjectKey" :options="trajectorySubjectOptions" class="w-full md:flex-1" />
              <NSelect v-model:value="trajectoryRange" :options="trajectoryRangeOptions" class="w-full md:w-48" />
              <ListActionButton kind="search" :loading="trajectoryLoading" @click="loadTrajectory">{{ t('mine.location.detail.load') }}</ListActionButton>
            </NFlex>
            <NAlert v-if="trajectory?.sampled" type="info" class="mt-4">
              {{ t('mine.location.detail.sampled', { count: trajectory.sourcePointCount }) }}
            </NAlert>
            <NText v-if="!trajectoryLoading && trajectoryPoints.length === 0" depth="3">
              {{ t('mine.location.detail.noTrajectory') }}
            </NText>
          </NCard>
        </NSpace>
      </NDrawerContent>
    </NDrawer>

    <NDrawer
      v-model:show="spatialEventDetailVisible"
      width="min(640px, 92vw)"
      placement="right"
      @after-leave="closeSpatialEventDetails"
    >
      <NDrawerContent :title="t('mine.spatial.event.detailTitle')" closable>
        <NSpace v-if="selectedSpatialEvent" vertical :size="16">
          <NText v-if="spatialEventDetailLoading" depth="3">
            {{ t('mine.location.spatial.loadingEvidence') }}
          </NText>
          <NDescriptions bordered :column="1">
            <NDescriptionsItem :label="t('mine.spatial.event.type')">
              {{ spatialEventTypeLabel(selectedSpatialEvent.eventType) }}
            </NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.spatial.event.subject')">
              {{ spatialSubjectLabel(selectedSpatialEvent) }}
            </NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.spatial.event.zone')">
              {{ spatialZoneName(selectedSpatialEvent) }}
            </NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.spatial.event.stateTransition')">
              {{ t(`mine.spatial.enum.stableState.${selectedSpatialEvent.previousState.toLowerCase()}`) }}
              →
              {{ t(`mine.spatial.enum.stableState.${selectedSpatialEvent.currentState.toLowerCase()}`) }}
            </NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.spatial.event.confirmationPoints')">
              {{ selectedSpatialEvent.confirmationPoints }}
            </NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.spatial.event.runtimeRevision')">
              {{ selectedSpatialEvent.runtimeRevision }}
            </NDescriptionsItem>
            <NDescriptionsItem :label="t('mine.spatial.event.anchorLocationEventId')">
              {{ spatialEventDetail?.anchorLocationEventId ?? selectedSpatialEvent.anchorLocationEventId ?? t('mine.common.empty') }}
            </NDescriptionsItem>
            <NDescriptionsItem v-if="selectedSpatialEvidence" :label="t('mine.spatial.event.coordinate')">
              ({{ formatNumber(selectedSpatialEvidence.localX) }}, {{ formatNumber(selectedSpatialEvidence.localY) }})
            </NDescriptionsItem>
            <NDescriptionsItem v-if="selectedSpatialEvidence" :label="t('mine.spatial.event.classification')">
              {{ t(`mine.spatial.enum.classification.${selectedSpatialEvidence.classification.toLowerCase()}`) }}
            </NDescriptionsItem>
            <NDescriptionsItem v-if="selectedSpatialEvidence" :label="t('mine.spatial.event.quality')">
              {{ t(`mine.spatial.enum.quality.${selectedSpatialEvidence.qualityStatus.toLowerCase()}`) }} ·
              {{ selectedSpatialEvidence.qualityReason ?? t('mine.common.empty') }}
            </NDescriptionsItem>
            <NDescriptionsItem v-if="selectedSpatialEvidence" :label="t('mine.spatial.event.contentHash')">
              {{ selectedSpatialEvidence.zoneContentHash }}
            </NDescriptionsItem>
          </NDescriptions>
        </NSpace>
      </NDrawerContent>
    </NDrawer>
  </NSpace>
</template>
