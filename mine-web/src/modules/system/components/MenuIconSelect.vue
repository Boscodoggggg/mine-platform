<script setup lang="ts">
import { computed, h, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { NButton, NEmpty, NInput, NPopover, NScrollbar, NSelect, NTooltip } from 'naive-ui';
import type { SelectOption } from 'naive-ui';
import { getLocalIcons } from '@/utils/icon';
import type { MenuIconType } from '@/utils/menu-icon';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { menuIconifyOptions } from './menu-icon-options';

defineOptions({ name: 'MenuIconSelect' });

defineProps<{
  value?: string | null;
  type: MenuIconType;
}>();

const emit = defineEmits<{
  (event: 'update:value', value: string | null): void;
}>();

const { t } = useI18n();
const search = ref('');
const filteredIconifyOptions = computed(() => {
  const keyword = search.value.trim().toLowerCase();
  return keyword ? menuIconifyOptions.filter(icon => icon.includes(keyword)) : menuIconifyOptions;
});
const localIconOptions = getLocalIcons().map<SelectOption>(icon => ({ label: icon, value: icon }));

function selectIcon(value: string) {
  emit('update:value', value);
}

function renderLocalIconLabel(option: SelectOption) {
  const icon = String(option.value || '');
  return h('span', { class: 'flex items-center gap-2' }, [
    h(SvgIcon, { localIcon: icon, class: 'text-18px' }),
    h('span', icon)
  ]);
}
</script>

<template>
  <NSelect
    v-if="type === 'local'"
    data-menu-icon-select
    filterable
    clearable
    :value="value || null"
    :options="localIconOptions"
    :render-label="renderLocalIconLabel"
    :placeholder="t('mine.menus.iconPicker.choose')"
    @update:value="emit('update:value', $event)"
  />
  <NPopover v-else placement="bottom-end" trigger="click" class="w-104">
    <template #trigger>
      <NInput
        data-menu-icon-select
        readonly
        clearable
        :value="value || ''"
        :placeholder="t('mine.menus.iconPicker.choose')"
        @clear="emit('update:value', null)"
      >
        <template #suffix>
          <SvgIcon v-if="value" :icon="value" class="text-20px" />
        </template>
      </NInput>
    </template>
    <template #header>
      <NInput v-model:value="search" clearable :placeholder="t('mine.menus.iconPicker.search')" />
    </template>
    <NScrollbar class="max-h-72 w-96">
      <div v-if="filteredIconifyOptions.length" class="grid grid-cols-6 gap-1 p-1">
        <NTooltip v-for="icon in filteredIconifyOptions" :key="icon" trigger="hover">
          <template #trigger>
            <NButton
              quaternary
              circle
              size="large"
              :type="value === icon ? 'primary' : 'default'"
              :aria-label="icon"
              @click="selectIcon(icon)"
            >
              <template #icon><SvgIcon :icon="icon" class="text-22px" /></template>
            </NButton>
          </template>
          {{ icon }}
        </NTooltip>
      </div>
      <NEmpty v-else :description="t('mine.menus.iconPicker.empty')" class="py-8" />
    </NScrollbar>
  </NPopover>
</template>
