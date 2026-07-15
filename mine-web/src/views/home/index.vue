<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import {
  NAlert,
  NCard,
  NGi,
  NGrid,
  NList,
  NListItem,
  NPageHeader,
  NSpace,
  NStatistic,
  NStep,
  NSteps,
  NTag,
  NText,
  NThing
} from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { useAuthStore } from '@/store/modules/auth';

const { t } = useI18n();
const authStore = useAuthStore();
const displayName = computed(() => authStore.userInfo.displayName || authStore.userInfo.userName);

const metrics = computed(() => [
  { key: 'onlineDevices', icon: 'material-symbols:memory-outline' },
  { key: 'todayTelemetry', icon: 'material-symbols:monitoring-outline' },
  { key: 'activeAlarms', icon: 'material-symbols:warning-outline' },
  { key: 'weakNetworkBacklog', icon: 'material-symbols:signal-cellular-connected-no-internet-0-bar' }
]);
const pipeline = computed(() => [
  { key: 'devices', description: 'MQTT / TCP', status: 'wait' as const },
  { key: 'gateway', description: 'mine-gateway', status: 'finish' as const },
  { key: 'broker', description: 'RabbitMQ', status: 'finish' as const },
  { key: 'telemetry', description: 'mine-telemetry', status: 'finish' as const }
]);
const readiness = computed(() => [
  { key: 'authentication', ready: true },
  { key: 'passwordChange', ready: true },
  { key: 'rabbitMq', ready: true },
  { key: 'deviceModel', ready: false }
]);
</script>

<template>
  <NSpace vertical :size="16">
    <NPageHeader :title="t('mine.home.title')" :subtitle="t('mine.home.welcome', { name: displayName })">
      <template #extra>
        <NTag type="success" :bordered="false">
          <template #icon><SvgIcon icon="material-symbols:location-on-outline" /></template>
          {{ t('mine.home.localEnvironment') }}
        </NTag>
      </template>
    </NPageHeader>

    <NGrid responsive="screen" cols="1 s:2 l:4" :x-gap="16" :y-gap="16">
      <NGi v-for="metric in metrics" :key="metric.key">
        <NCard size="small">
          <NStatistic :label="t(`mine.home.metric.${metric.key}.label`)" value="—">
            <template #prefix><SvgIcon :icon="metric.icon" class="text-primary" /></template>
          </NStatistic>
          <NText depth="3">{{ t(`mine.home.metric.${metric.key}.description`) }}</NText>
        </NCard>
      </NGi>
    </NGrid>

    <NGrid responsive="screen" cols="1 l:3" :x-gap="16" :y-gap="16">
      <NGi span="1 l:2">
        <NCard :title="t('mine.home.pipeline.title')">
          <template #header-extra>
            <NTag type="warning">{{ t('mine.home.pendingMetrics') }}</NTag>
          </template>
          <NSteps :current="1" status="process" responsive>
            <NStep
              v-for="item in pipeline"
              :key="item.key"
              :status="item.status"
              :title="t(`mine.home.pipeline.${item.key}`)"
              :description="item.description"
            />
          </NSteps>
          <NAlert class="mt-6" type="info" :show-icon="false">{{ t('mine.home.pipeline.note') }}</NAlert>
        </NCard>
      </NGi>

      <NGi>
        <NCard :title="t('mine.home.readiness.title')">
          <NList>
            <NListItem v-for="item in readiness" :key="item.key">
              <NThing :title="t(`mine.home.readiness.${item.key}`)" />
              <template #suffix>
                <NTag :type="item.ready ? 'success' : 'warning'" :bordered="false">
                  {{ t(item.ready ? 'mine.home.ready' : 'mine.home.pending') }}
                </NTag>
              </template>
            </NListItem>
          </NList>
        </NCard>
      </NGi>
    </NGrid>
  </NSpace>
</template>
