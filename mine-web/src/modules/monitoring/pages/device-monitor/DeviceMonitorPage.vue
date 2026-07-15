<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { SelectOption } from 'naive-ui';
import { NAlert, NCard, NDynamicTags, NEmpty, NGi, NGrid, NPageHeader, NSelect, NSpace, NTag, NText } from 'naive-ui';
import { ApiError } from '@/api/client';
import { getDeviceLatest, getDevices } from '@/modules/device/api/device';
import type { Device, TelemetryLatestItem } from '@/modules/device/api/device';
import NumericCard from '@/modules/monitoring/components/NumericCard.vue';
import { realtimeHub } from '@/realtime';
import type { RealtimeBatch, RealtimeLatestValue } from '@/realtime';

interface DisplayValue {
  value: number;
  quality?: string;
  receivedAt?: string;
}

const { t } = useI18n();
const loading = ref(false);
const devices = ref<Device[]>([]);
const selectedDeviceId = ref('');
const propertyIds = ref(['engine.rpm', 'coolant.temperature', 'vehicle.speed', 'fuel.level']);
const latestValues = reactive(new Map<string, DisplayValue>());
const subscribed = ref(false);
let releaseSubscription: (() => void) | null = null;

const deviceOptions = computed<SelectOption[]>(() =>
  devices.value.map(device => ({
    label: `${device.name} (${device.deviceCode})`,
    value: device.id,
    disabled: device.status !== 'ENABLED'
  }))
);
const propertyCards = computed(() =>
  propertyIds.value.map(propertyId => ({
    propertyId,
    ...propertyPresentation(propertyId),
    latest: latestValues.get(propertyId)
  }))
);

onMounted(loadDevices);
onBeforeUnmount(clearSubscription);

async function loadDevices() {
  loading.value = true;
  try {
    const response = await getDevices({ page: 1, size: 100, status: 'ENABLED' });
    devices.value = response.items;
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
}

async function selectDevice(value: string | null) {
  selectedDeviceId.value = value || '';
  clearSubscription();
  latestValues.clear();
  if (!selectedDeviceId.value || propertyIds.value.length === 0) return;

  loading.value = true;
  try {
    const snapshot = await getDeviceLatest(selectedDeviceId.value, propertyIds.value);
    snapshot.items.forEach(applyLatestItem);
    releaseSubscription = realtimeHub.subscribe(selectedDeviceId.value, propertyIds.value, applyRealtimeBatch);
    subscribed.value = true;
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
}

function applyLatestItem(item: TelemetryLatestItem) {
  latestValues.set(item.propertyId, {
    value: item.value,
    quality: item.quality,
    receivedAt: item.receivedAt
  });
}

function applyRealtimeBatch(batch: RealtimeBatch) {
  batch.values.forEach(item => {
    if (item.deviceId !== selectedDeviceId.value) return;
    applyRealtimeItem(item);
  });
}

function applyRealtimeItem(item: RealtimeLatestValue) {
  latestValues.set(item.propertyId, {
    value: item.value,
    quality: item.quality,
    receivedAt: item.receivedAt
  });
}

function updateProperties(values: string[]) {
  propertyIds.value = [
    ...new Set(values.map(value => value.trim()).filter(value => /^[A-Za-z][A-Za-z0-9_.-]{0,127}$/.test(value)))
  ].slice(0, 100);
  if (selectedDeviceId.value) void selectDevice(selectedDeviceId.value);
}

function clearSubscription() {
  releaseSubscription?.();
  releaseSubscription = null;
  subscribed.value = false;
}

function propertyPresentation(propertyId: string) {
  const presentations: Record<string, { label: string; unit: string }> = {
    'engine.rpm': { label: t('mine.monitor.property.engineRpm'), unit: 'RPM' },
    'coolant.temperature': { label: t('mine.monitor.property.coolantTemperature'), unit: '°C' },
    'vehicle.speed': { label: t('mine.monitor.property.vehicleSpeed'), unit: 'km/h' },
    'fuel.level': { label: t('mine.monitor.property.fuelLevel'), unit: '%' }
  };
  return presentations[propertyId] || { label: propertyId, unit: '' };
}

function showError(error: unknown) {
  if (error instanceof ApiError) {
    window.$message?.error(
      error.traceId ? `${error.message} (${t('mine.common.traceId', { traceId: error.traceId })})` : error.message
    );
    return;
  }
  window.$message?.error(t('mine.common.operationFailed'));
}

defineExpose({ selectedDeviceId, propertyIds, selectDevice, updateProperties });
</script>

<template>
  <NSpace vertical :size="16">
    <NPageHeader :title="t('mine.monitor.title')" :subtitle="t('mine.monitor.subtitle')">
      <template #extra>
        <NTag v-if="subscribed" type="success" :bordered="false">{{ t('mine.monitor.connected') }}</NTag>
      </template>
    </NPageHeader>

    <NCard size="small">
      <NGrid responsive="screen" cols="1 m:2" :x-gap="20" :y-gap="16">
        <NGi>
          <NSpace vertical :size="8">
            <NText strong>{{ t('mine.monitor.selectDevice') }}</NText>
            <NSelect
              v-model:value="selectedDeviceId"
              filterable
              clearable
              :loading="loading"
              :options="deviceOptions"
              :placeholder="t('mine.monitor.selectPlaceholder')"
              @update:value="selectDevice"
            />
          </NSpace>
        </NGi>
        <NGi>
          <NSpace vertical :size="8">
            <NText strong>{{ t('mine.monitor.propertyTitle') }}</NText>
            <NDynamicTags :value="propertyIds" @update:value="updateProperties" />
            <NText depth="3" class="text-xs">{{ t('mine.monitor.propertyHelp') }}</NText>
          </NSpace>
        </NGi>
      </NGrid>
    </NCard>

    <NEmpty v-if="!selectedDeviceId" :description="t('mine.monitor.noDevice')">
      <template #extra>
        <NText depth="3">{{ t('mine.monitor.noDeviceDescription') }}</NText>
      </template>
    </NEmpty>
    <template v-else>
      <NAlert v-if="subscribed" type="success" :bordered="false">{{ t('mine.monitor.snapshot') }}</NAlert>
      <NGrid responsive="screen" cols="1 s:2 l:4" :x-gap="16" :y-gap="16">
        <NGi v-for="card in propertyCards" :key="card.propertyId">
          <NumericCard
            :property-id="card.propertyId"
            :label="card.label"
            :unit="card.unit"
            :value="card.latest?.value ?? null"
            :quality="card.latest?.quality"
            :received-at="card.latest?.receivedAt"
          />
        </NGi>
      </NGrid>
    </template>
  </NSpace>
</template>
