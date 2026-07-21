<script setup lang="ts">
import { ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { NCard, NCollapse, NCollapseItem } from 'naive-ui';

defineOptions({ name: 'ListSearchPanel' });

const { t } = useI18n();
const expandedNames = ref<Array<string | number>>([]);
</script>

<template>
  <NCard data-list-search-panel :bordered="false" size="small">
    <NCollapse v-model:expanded-names="expandedNames" arrow-placement="left">
      <NCollapseItem name="search" :title="t('mine.common.searchTitle')">
        <slot />
      </NCollapseItem>
    </NCollapse>
  </NCard>
</template>
