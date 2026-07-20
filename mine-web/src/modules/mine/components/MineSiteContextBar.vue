<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { NButton, NCard, NFlex, NSelect, NTag, NText } from 'naive-ui';
import { useMineContextStore } from '../stores/mine-context';

const props = defineProps<{
  loading?: boolean;
  lastUpdatedAt?: number | null;
}>();
const emit = defineEmits<{
  change: [siteId: string];
  refresh: [];
}>();
const { t } = useI18n();
const context = useMineContextStore();
const options = computed(() =>
  context.sites.map(site => ({
    label: [site.name, site.siteCode, site.timezone].filter(Boolean).join(' · '),
    value: site.id
  }))
);
const freshness = computed(() => {
  if (props.loading) return { type: 'info' as const, label: t('mine.production.context.updating') };
  if (!props.lastUpdatedAt) return { type: 'warning' as const, label: t('mine.production.context.notUpdated') };
  const seconds = Math.max(0, Math.floor((Date.now() - props.lastUpdatedAt) / 1000));
  if (seconds < 60) return { type: 'success' as const, label: t('mine.production.context.justUpdated') };
  return { type: 'warning' as const, label: t('mine.production.context.updatedMinutes', { count: Math.floor(seconds / 60) }) };
});

function updateSite(siteId: string) {
  emit('change', siteId);
}
</script>

<template>
  <NCard size="small" :bordered="false">
    <NFlex data-responsive-stack class="flex-col gap-3 md:flex-row" align="center" justify="space-between">
      <NFlex class="w-full md:w-auto" align="center" :wrap="false">
        <NText depth="3" class="whitespace-nowrap">{{ t('mine.production.context.site') }}</NText>
        <NSelect
          :value="context.selectedSiteId"
          :options="options"
          :loading="context.loading"
          :placeholder="t('mine.production.context.selectSite')"
          class="w-full md:w-80"
          @update:value="updateSite"
        />
      </NFlex>
      <NFlex class="w-full md:w-auto" align="center" justify="space-between">
        <NTag data-freshness :type="freshness.type" size="small" :bordered="false">
          {{ freshness.label }}
        </NTag>
        <NButton size="small" :loading="loading" @click="emit('refresh')">
          {{ t('common.refresh') }}
        </NButton>
      </NFlex>
    </NFlex>
  </NCard>
</template>
