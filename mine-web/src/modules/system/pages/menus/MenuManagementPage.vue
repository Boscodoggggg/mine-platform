<script setup lang="ts">
import { computed, defineComponent, h, nextTick, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  NButton,
  NCard,
  NDataTable,
  NForm,
  NFormItem,
  NGi,
  NGrid,
  NInput,
  NInputNumber,
  NModal,
  NPageHeader,
  NSelect,
  NSpace,
  NSwitch,
  NTag,
  NText,
  NTooltip
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import {
  createMenu,
  deleteMenu,
  getMenuComponentOptions,
  getMenuTree,
  updateMenu
} from '@/modules/system/api/access-management';
import type {
  ManagedMenuNode,
  MenuComponentOption,
  MenuNodeType,
  MenuSavePayload
} from '@/modules/system/api/access-management';
import { useAuthStore } from '@/store/modules/auth';

const { t } = useI18n();
const authStore = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const tree = ref<ManagedMenuNode[]>([]);
const componentOptions = ref<MenuComponentOption[]>([]);
const expandedRowKeys = ref<Array<string | number>>([]);
const modalVisible = ref(false);
const editingId = ref<string | null>(null);
const filters = reactive<{ name: string; type: MenuNodeType | null; status: 'ENABLED' | 'DISABLED' | null }>({
  name: '',
  type: null,
  status: null
});
const form = reactive<MenuSavePayload>({
  parentId: null,
  name: '',
  type: 'MENU',
  path: '',
  componentKey: '',
  permission: '',
  icon: '',
  sortOrder: 10,
  visible: true,
  status: 'ENABLED',
  i18nKey: '',
  version: 0
});

const flatRows = computed(() => flatten(tree.value));
const filteredTree = computed(() => filterTree(tree.value));
const expandableRowKeys = computed(() => collectExpandableKeys(filteredTree.value));
const allExpanded = computed(
  () => expandableRowKeys.value.length > 0 && expandableRowKeys.value.every(key => expandedRowKeys.value.includes(key))
);
const typeOptions = computed<SelectOption[]>(() => [
  { label: t('mine.menus.type.directory'), value: 'DIRECTORY' },
  { label: t('mine.menus.type.menu'), value: 'MENU' },
  { label: t('mine.menus.type.button'), value: 'BUTTON' }
]);
const statusOptions = computed<SelectOption[]>(() => [
  { label: t('mine.common.status.enabled'), value: 'ENABLED' },
  { label: t('mine.common.status.disabled'), value: 'DISABLED' }
]);
const parentOptions = computed<SelectOption[]>(() =>
  flatRows.value
    .filter(row => row.id !== editingId.value && validParentType(row.type, form.type))
    .map(row => ({ label: `${'· '.repeat(row.depth)}${row.name}`, value: row.id }))
);
const pageOptions = computed<SelectOption[]>(() =>
  componentOptions.value.map(option => ({ label: option.name, value: option.key }))
);
const columns = computed<DataTableColumns<ManagedMenuNode>>(() => [
  {
    title: t('mine.menus.column.name'),
    key: 'name',
    minWidth: 220,
    render: row => h(NText, { depth: row.status === 'ENABLED' ? undefined : 3 }, () => row.name)
  },
  {
    title: t('mine.menus.column.type'),
    key: 'type',
    width: 110,
    render: row => h(NTag, { size: 'small', bordered: false }, () => t(`mine.menus.type.${row.type.toLowerCase()}`))
  },
  { title: t('mine.menus.column.path'), key: 'path', minWidth: 160, render: row => row.path || t('mine.common.empty') },
  {
    title: t('mine.menus.column.permission'),
    key: 'permission',
    minWidth: 200,
    render: row => row.permission || t('mine.common.empty')
  },
  {
    title: t('mine.menus.column.status'),
    key: 'status',
    width: 100,
    render: row =>
      h(NTag, { size: 'small', bordered: false, type: row.status === 'ENABLED' ? 'success' : 'warning' }, () =>
        t(`mine.common.status.${row.status.toLowerCase()}`)
      )
  },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 170,
    render: row =>
      h(NSpace, { size: 8 }, () => [
        can('system:menu:update')
          ? h(NButton, { text: true, type: 'primary', onClick: () => openEdit(row) }, () => t('mine.common.edit'))
          : null,
        can('system:menu:delete') && !row.protectedNode
          ? h(NButton, { text: true, type: 'error', onClick: () => remove(row) }, () => t('mine.common.delete'))
          : null
      ])
  }
]);

const MenuFieldLabel = defineComponent({
  name: 'MenuFieldLabel',
  props: {
    labelKey: { type: String, required: true },
    helpKey: { type: String, required: true }
  },
  setup(props) {
    return () =>
      h(NSpace, { align: 'center', size: 4 }, () => [
        h('span', t(props.labelKey as App.I18n.I18nKey)),
        h(
          NTooltip,
          { placement: 'top', trigger: 'hover' },
          {
            trigger: () =>
              h(
                NButton,
                {
                  text: true,
                  circle: true,
                  size: 'tiny',
                  'aria-label': t('mine.menus.help.label'),
                  'data-field-help': props.helpKey
                },
                () => h('span', { 'aria-hidden': 'true' }, '?')
              ),
            default: () => t(props.helpKey as App.I18n.I18nKey)
          }
        )
      ]);
  }
});

watch(
  () => [filters.name, filters.type, filters.status],
  async () => {
    await nextTick();
    expandedRowKeys.value = hasActiveFilters() ? [...expandableRowKeys.value] : [];
  }
);

onMounted(load);

async function load() {
  loading.value = true;
  try {
    [tree.value, componentOptions.value] = await Promise.all([getMenuTree(), getMenuComponentOptions()]);
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
}

function openCreate(type: MenuNodeType = 'MENU') {
  editingId.value = null;
  Object.assign(form, {
    parentId: null,
    name: '',
    type,
    path: '',
    componentKey: '',
    permission: '',
    icon: '',
    sortOrder: 10,
    visible: type !== 'BUTTON',
    status: 'ENABLED',
    i18nKey: '',
    version: 0
  });
  modalVisible.value = true;
}

function openEdit(row: ManagedMenuNode) {
  editingId.value = row.id;
  Object.assign(form, {
    parentId: row.parentId,
    name: row.name,
    type: row.type,
    path: row.path || '',
    componentKey: row.componentKey || '',
    permission: row.permission || '',
    icon: row.icon || '',
    sortOrder: row.sortOrder,
    visible: row.visible,
    status: row.status,
    i18nKey: row.i18nKey || '',
    version: row.version || 0
  });
  modalVisible.value = true;
}

async function save() {
  if (!form.name.trim()) return window.$message?.warning(t('mine.menus.validation.required'));
  saving.value = true;
  try {
    const payload = cleanPayload(form);
    if (editingId.value) await updateMenu(editingId.value, payload);
    else await createMenu(payload);
    window.$message?.success(t(editingId.value ? 'mine.menus.message.updated' : 'mine.menus.message.created'));
    modalVisible.value = false;
    await load();
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

async function remove(row: ManagedMenuNode) {
  if (!window.confirm(t('mine.menus.confirmDelete', { name: row.name }))) return;
  try {
    await deleteMenu(row.id);
    window.$message?.success(t('mine.menus.message.deleted'));
    await load();
  } catch (error) {
    showError(error);
  }
}

function cleanPayload(value: MenuSavePayload): MenuSavePayload {
  return {
    ...value,
    parentId: value.parentId || null,
    name: value.name.trim(),
    path: value.path?.trim() || undefined,
    componentKey: value.componentKey?.trim() || undefined,
    permission: value.permission?.trim() || undefined,
    icon: value.icon?.trim() || undefined,
    i18nKey: value.i18nKey?.trim() || undefined,
    visible: value.type === 'BUTTON' ? false : value.visible
  };
}

function flatten(nodes: ManagedMenuNode[], depth = 0): MenuRow[] {
  return nodes.flatMap(node => [{ ...node, depth }, ...flatten(node.children, depth + 1)]);
}

type MenuRow = ManagedMenuNode & { depth: number };

function filterTree(nodes: ManagedMenuNode[]): ManagedMenuNode[] {
  const keyword = filters.name.trim().toLocaleLowerCase();
  return nodes.flatMap(node => {
    const children = filterTree(node.children);
    const matchesName = !keyword || node.name.toLocaleLowerCase().includes(keyword);
    const matchesType = !filters.type || node.type === filters.type;
    const matchesStatus = !filters.status || node.status === filters.status;
    return (matchesName && matchesType && matchesStatus) || children.length > 0 ? [{ ...node, children }] : [];
  });
}

function collectExpandableKeys(nodes: ManagedMenuNode[]): Array<string | number> {
  return nodes.flatMap(node => (node.children.length > 0 ? [node.id, ...collectExpandableKeys(node.children)] : []));
}

function toggleExpandAll() {
  expandedRowKeys.value = allExpanded.value ? [] : [...expandableRowKeys.value];
}

function hasActiveFilters() {
  return Boolean(filters.name.trim() || filters.type || filters.status);
}

function validParentType(parent: MenuNodeType, child: MenuNodeType) {
  return child === 'BUTTON' ? parent === 'MENU' : parent === 'DIRECTORY';
}

function can(permission: string) {
  return hasPermission(authStore.userInfo.buttons, permission);
}

function showError(error: unknown) {
  if (error instanceof ApiError) return window.$message?.error(error.message);
  window.$message?.error(t('mine.common.operationFailed'));
}
</script>

<template>
  <NSpace vertical :size="16">
    <NPageHeader :title="t('mine.menus.title')" :subtitle="t('mine.menus.subtitle')">
      <template #extra>
        <NButton v-if="can('system:menu:create')" type="primary" @click="openCreate()">
          {{ t('mine.menus.action.create') }}
        </NButton>
      </template>
    </NPageHeader>

    <NCard :bordered="false">
      <NSpace vertical :size="16">
        <NGrid responsive="screen" cols="1 s:2 m:4" :x-gap="12">
          <NGi>
            <NFormItem :label="t('mine.menus.filter.name')" :show-feedback="false">
              <NInput
                v-model:value="filters.name"
                data-filter="menu-name"
                clearable
                :placeholder="t('mine.menus.filter.namePlaceholder')"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.menus.filter.type')" :show-feedback="false">
              <NSelect
                v-model:value="filters.type"
                data-filter="menu-type"
                clearable
                :options="typeOptions"
                :placeholder="t('mine.menus.filter.allTypes')"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.menus.filter.status')" :show-feedback="false">
              <NSelect
                v-model:value="filters.status"
                data-filter="menu-status"
                clearable
                :options="statusOptions"
                :placeholder="t('mine.menus.filter.allStatuses')"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NSpace justify="end" align="end">
              <NButton data-action="toggle-tree" @click="toggleExpandAll">
                {{ t(allExpanded ? 'mine.menus.action.collapseAll' : 'mine.menus.action.expandAll') }}
              </NButton>
            </NSpace>
          </NGi>
        </NGrid>

        <NDataTable
          :columns="columns"
          :data="filteredTree"
          :loading="loading"
          :row-key="row => row.id"
          :expanded-row-keys="expandedRowKeys"
          @update:expanded-row-keys="expandedRowKeys = $event"
        />
      </NSpace>
    </NCard>

    <NModal
      v-model:show="modalVisible"
      preset="card"
      :title="t(editingId ? 'mine.menus.modal.edit' : 'mine.menus.modal.create')"
      :style="{ width: 'min(760px, 92vw)' }"
    >
      <NForm label-placement="top">
        <NGrid :cols="2" :x-gap="16">
          <NGi>
            <NFormItem data-menu-field>
              <template #label>
                <MenuFieldLabel label-key="mine.menus.field.name" help-key="mine.menus.help.name" />
              </template>
              <NInput v-model:value="form.name" />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem data-menu-field>
              <template #label>
                <MenuFieldLabel label-key="mine.menus.field.type" help-key="mine.menus.help.type" />
              </template>
              <NSelect v-model:value="form.type" :options="typeOptions" />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem data-menu-field>
              <template #label>
                <MenuFieldLabel label-key="mine.menus.field.parent" help-key="mine.menus.help.parent" />
              </template>
              <NSelect v-model:value="form.parentId" clearable :options="parentOptions" />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem data-menu-field>
              <template #label>
                <MenuFieldLabel label-key="mine.menus.field.sort" help-key="mine.menus.help.sort" />
              </template>
              <NInputNumber v-model:value="form.sortOrder" :min="0" />
            </NFormItem>
          </NGi>
          <NGi v-if="form.type === 'MENU'">
            <NFormItem data-menu-field>
              <template #label>
                <MenuFieldLabel label-key="mine.menus.field.path" help-key="mine.menus.help.path" />
              </template>
              <NInput v-model:value="form.path" />
            </NFormItem>
          </NGi>
          <NGi v-if="form.type === 'MENU'">
            <NFormItem data-menu-field>
              <template #label>
                <MenuFieldLabel label-key="mine.menus.field.component" help-key="mine.menus.help.component" />
              </template>
              <NSelect v-model:value="form.componentKey" filterable :options="pageOptions" />
            </NFormItem>
          </NGi>
          <NGi v-if="form.type !== 'DIRECTORY'">
            <NFormItem data-menu-field>
              <template #label>
                <MenuFieldLabel label-key="mine.menus.field.permission" help-key="mine.menus.help.permission" />
              </template>
              <NInput v-model:value="form.permission" />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem data-menu-field>
              <template #label>
                <MenuFieldLabel label-key="mine.menus.field.icon" help-key="mine.menus.help.icon" />
              </template>
              <NInput v-model:value="form.icon" />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem data-menu-field>
              <template #label>
                <MenuFieldLabel label-key="mine.menus.field.i18nKey" help-key="mine.menus.help.i18nKey" />
              </template>
              <NInput v-model:value="form.i18nKey" />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem data-menu-field>
              <template #label>
                <MenuFieldLabel label-key="mine.menus.field.status" help-key="mine.menus.help.status" />
              </template>
              <NSelect v-model:value="form.status" :options="statusOptions" />
            </NFormItem>
          </NGi>
          <NGi v-if="form.type !== 'BUTTON'">
            <NFormItem data-menu-field>
              <template #label>
                <MenuFieldLabel label-key="mine.menus.field.visible" help-key="mine.menus.help.visible" />
              </template>
              <NSwitch v-model:value="form.visible" />
            </NFormItem>
          </NGi>
        </NGrid>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="modalVisible = false">{{ t('mine.common.cancel') }}</NButton>
          <NButton type="primary" :loading="saving" @click="save">{{ t('mine.common.save') }}</NButton>
        </NSpace>
      </template>
    </NModal>
  </NSpace>
</template>
