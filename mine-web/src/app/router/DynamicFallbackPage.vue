<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute } from 'vue-router';
import { NCard, NResult, NText } from 'naive-ui';

const { t } = useI18n();
const route = useRoute();
const componentKey = computed(() => route.meta.componentKey || t('mine.fallback.unconfigured'));
</script>

<template>
  <NCard>
    <NResult
      status="info"
      :title="t('mine.fallback.title')"
      :description="t('mine.fallback.description', { componentKey })"
    >
      <template #footer>
        <NText depth="3">{{ t('mine.fallback.help') }}</NText>
      </template>
    </NResult>
  </NCard>
</template>
