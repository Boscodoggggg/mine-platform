<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { NCard, NFlex, NSelect, NText } from "naive-ui";
import ListActionButton from "@/components/common/list-action-button.vue";
import { useMineContextStore } from "../stores/mine-context";

const emit = defineEmits<{
  change: [siteId: string];
  refresh: [];
}>();
const { t } = useI18n();
const context = useMineContextStore();
const options = computed(() =>
  context.sites.map((site) => ({
    label: `${site.name} · ${site.siteCode}`,
    value: site.id,
  })),
);

function updateSite(siteId: string) {
  if (context.selectSite(siteId)) emit("change", siteId);
}

async function refresh() {
  try {
    await context.refreshContext();
    emit("refresh");
  } catch {
    window.$message?.error(t("mine.context.loadFailed"));
  }
}
</script>

<template>
  <NCard size="small" :bordered="false">
    <NFlex
      data-responsive-stack
      class="flex-col md:flex-row"
      align="center"
      justify="space-between"
    >
      <NFlex class="w-full md:w-auto" align="center">
        <NText depth="3">{{ t("mine.assets.context.label") }}</NText>
        <NSelect
          :value="context.selectedSiteId"
          :options="options"
          :loading="context.loading"
          :placeholder="t('mine.assets.context.placeholder')"
          class="w-full md:w-80"
          @update:value="updateSite"
        />
      </NFlex>
      <ListActionButton
        kind="refresh"
        :loading="context.loading"
        @click="refresh"
      >
        {{ t("mine.assets.context.refresh") }}
      </ListActionButton>
    </NFlex>
  </NCard>
</template>
