<script setup lang="ts">
import { computed, defineComponent, h, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns, DataTableRowKey, SelectOption } from 'naive-ui';
import {
  NButton,
  NCard,
  NCheckbox,
  NCheckboxGroup,
  NDataTable,
  NDivider,
  NForm,
  NFormItem,
  NGi,
  NGrid,
  NInput,
  NInputNumber,
  NModal,
  NPageHeader,
  NPagination,
  NPopover,
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
  deleteMenus,
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

type MenuListRow = Omit<ManagedMenuNode, 'children'> & {
  parentName: string | null;
  childCount: number;
};

type MenuColumnKey =
  | 'id'
  | 'type'
  | 'name'
  | 'icon'
  | 'path'
  | 'permission'
  | 'status'
  | 'visible'
  | 'parent'
  | 'sort';

const { t } = useI18n();
const authStore = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const deleting = ref(false);
const buttonSaving = ref(false);
const tree = ref<ManagedMenuNode[]>([]);
const componentOptions = ref<MenuComponentOption[]>([]);
const modalVisible = ref(false);
const buttonEditorVisible = ref(false);
const editingId = ref<string | null>(null);
const editingButtonId = ref<string | null>(null);
const checkedRowKeys = ref<string[]>([]);
const page = ref(1);
const pageSize = ref(10);
const visibleColumnKeys = ref<MenuColumnKey[]>([
  'type',
  'name',
  'icon',
  'path',
  'permission',
  'status',
  'visible',
  'parent',
  'sort'
]);
const filters = reactive<{ name: string; type: Exclude<MenuNodeType, 'BUTTON'> | null; status: 'ENABLED' | 'DISABLED' | null }>({
  name: '',
  type: null,
  status: null
});
const form = reactive<MenuSavePayload>(emptyMenuForm());
const buttonForm = reactive<MenuSavePayload>(emptyButtonForm());

const menuRows = computed(() => projectMenuRows(tree.value));
const filteredRows = computed(() => {
  const keyword = filters.name.trim().toLocaleLowerCase();
  return menuRows.value.filter(row => {
    const matchesName = !keyword || row.name.toLocaleLowerCase().includes(keyword);
    const matchesType = !filters.type || row.type === filters.type;
    const matchesStatus = !filters.status || row.status === filters.status;
    return matchesName && matchesType && matchesStatus;
  });
});
const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize.value;
  return filteredRows.value.slice(start, start + pageSize.value);
});
const activeMenu = computed(() => (editingId.value ? findMenuNode(tree.value, editingId.value) : null));
const buttonRows = computed(() => activeMenu.value?.children.filter(node => node.type === 'BUTTON') || []);
const descendantIds = computed(() =>
  editingId.value ? collectDescendantIds(findMenuNode(tree.value, editingId.value)?.children || []) : new Set<string>()
);
const menuTypeOptions = computed<SelectOption[]>(() => [
  { label: t('mine.menus.type.directory'), value: 'DIRECTORY' },
  { label: t('mine.menus.type.menu'), value: 'MENU' }
]);
const statusOptions = computed<SelectOption[]>(() => [
  { label: t('mine.common.status.enabled'), value: 'ENABLED' },
  { label: t('mine.common.status.disabled'), value: 'DISABLED' }
]);
const parentOptions = computed<SelectOption[]>(() =>
  menuRows.value
    .filter(row => row.type === 'DIRECTORY' && row.id !== editingId.value && !descendantIds.value.has(row.id))
    .map(row => ({ label: row.name, value: row.id }))
);
const pageOptions = computed<SelectOption[]>(() =>
  componentOptions.value.map(option => ({ label: option.name, value: option.key }))
);
const columnOptions = computed(() => [
  { key: 'id' as const, label: t('mine.menus.column.id') },
  { key: 'type' as const, label: t('mine.menus.column.type') },
  { key: 'name' as const, label: t('mine.menus.column.name') },
  { key: 'icon' as const, label: t('mine.menus.column.icon') },
  { key: 'path' as const, label: t('mine.menus.column.path') },
  { key: 'permission' as const, label: t('mine.menus.column.permission') },
  { key: 'status' as const, label: t('mine.menus.column.status') },
  { key: 'visible' as const, label: t('mine.menus.column.visible') },
  { key: 'parent' as const, label: t('mine.menus.column.parent') },
  { key: 'sort' as const, label: t('mine.menus.column.sort') }
]);

const columns = computed<DataTableColumns<MenuListRow>>(() => {
  const definitions: Array<{ key: MenuColumnKey; column: DataTableColumns<MenuListRow>[number] }> = [
    {
      key: 'id',
      column: { title: t('mine.menus.column.id'), key: 'id', width: 190, ellipsis: { tooltip: true } }
    },
    {
      key: 'type',
      column: {
        title: t('mine.menus.column.type'),
        key: 'type',
        width: 100,
        render: row =>
          h(NTag, { size: 'small', bordered: false }, () => t(`mine.menus.type.${row.type.toLowerCase()}`))
      }
    },
    {
      key: 'name',
      column: {
        title: t('mine.menus.column.name'),
        key: 'name',
        minWidth: 150,
        fixed: 'left',
        render: row => h(NText, { depth: row.status === 'ENABLED' ? undefined : 3 }, () => row.name)
      }
    },
    {
      key: 'icon',
      column: {
        title: t('mine.menus.column.icon'),
        key: 'icon',
        minWidth: 120,
        ellipsis: { tooltip: true },
        render: row => row.icon || t('mine.common.empty')
      }
    },
    {
      key: 'path',
      column: {
        title: t('mine.menus.column.path'),
        key: 'path',
        minWidth: 160,
        ellipsis: { tooltip: true },
        render: row => row.path || t('mine.common.empty')
      }
    },
    {
      key: 'permission',
      column: {
        title: t('mine.menus.column.permission'),
        key: 'permission',
        minWidth: 190,
        ellipsis: { tooltip: true },
        render: row => row.permission || t('mine.common.empty')
      }
    },
    {
      key: 'status',
      column: {
        title: t('mine.menus.column.status'),
        key: 'status',
        width: 90,
        render: row =>
          h(NTag, { size: 'small', bordered: false, type: row.status === 'ENABLED' ? 'success' : 'warning' }, () =>
            t(`mine.common.status.${row.status.toLowerCase()}`)
          )
      }
    },
    {
      key: 'visible',
      column: {
        title: t('mine.menus.column.visible'),
        key: 'visible',
        width: 100,
        render: row => t(row.visible ? 'mine.common.no' : 'mine.common.yes')
      }
    },
    {
      key: 'parent',
      column: {
        title: t('mine.menus.column.parent'),
        key: 'parentName',
        minWidth: 130,
        render: row => row.parentName || t('mine.menus.root')
      }
    },
    {
      key: 'sort',
      column: { title: t('mine.menus.column.sort'), key: 'sortOrder', width: 80 }
    }
  ];
  const result: DataTableColumns<MenuListRow> = definitions
    .filter(item => visibleColumnKeys.value.includes(item.key))
    .map(item => item.column);
  if (can('system:menu:delete')) {
    result.unshift({
      type: 'selection',
      disabled: row => row.protectedNode || row.childCount > 0
    });
  }
  result.push({
    title: t('mine.common.action'),
    key: 'actions',
    width: 150,
    fixed: 'right',
    render: row =>
      h(NSpace, { size: 8, wrap: false }, () => [
        can('system:menu:update')
          ? h(NButton, { text: true, type: 'primary', onClick: () => openEdit(row) }, () => t('mine.common.edit'))
          : null,
        can('system:menu:delete') && !row.protectedNode && row.childCount === 0
          ? h(NButton, { text: true, type: 'error', onClick: () => remove(row) }, () => t('mine.common.delete'))
          : null
      ])
  });
  return result;
});

const buttonColumns = computed<DataTableColumns<ManagedMenuNode>>(() => [
  { title: t('mine.menus.column.name'), key: 'name', minWidth: 150 },
  { title: t('mine.menus.column.permission'), key: 'permission', minWidth: 220 },
  { title: t('mine.menus.column.sort'), key: 'sortOrder', width: 80 },
  {
    title: t('mine.menus.column.status'),
    key: 'status',
    width: 90,
    render: row =>
      h(NTag, { size: 'small', bordered: false, type: row.status === 'ENABLED' ? 'success' : 'warning' }, () =>
        t(`mine.common.status.${row.status.toLowerCase()}`)
      )
  },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 140,
    render: row =>
      h(NSpace, { size: 8, wrap: false }, () => [
        can('system:menu:update')
          ? h(NButton, { text: true, type: 'primary', onClick: () => openButtonEdit(row) }, () =>
              t('mine.common.edit')
            )
          : null,
        can('system:menu:delete') && !row.protectedNode
          ? h(NButton, { text: true, type: 'error', onClick: () => removeButton(row) }, () =>
              t('mine.common.delete')
            )
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
  () => {
    page.value = 1;
    checkedRowKeys.value = [];
  }
);
watch(pageSize, () => {
  page.value = 1;
  checkedRowKeys.value = [];
});
watch(
  () => filteredRows.value.length,
  total => {
    const lastPage = Math.max(1, Math.ceil(total / pageSize.value));
    if (page.value > lastPage) page.value = lastPage;
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

function openCreate(type: Exclude<MenuNodeType, 'BUTTON'> = 'DIRECTORY') {
  editingId.value = null;
  buttonEditorVisible.value = false;
  Object.assign(form, emptyMenuForm(type));
  modalVisible.value = true;
}

function openEdit(row: MenuListRow | ManagedMenuNode) {
  editingId.value = row.id;
  buttonEditorVisible.value = false;
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
  const menuRequiredMissing =
    form.type === 'MENU' && (!form.parentId || !form.path?.trim() || !form.componentKey?.trim() || !form.permission?.trim());
  if (!form.name.trim() || menuRequiredMissing) {
    return window.$message?.warning(t('mine.menus.validation.required'));
  }
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

async function remove(row: MenuListRow) {
  if (!window.confirm(t('mine.menus.confirmDelete', { name: row.name }))) return;
  try {
    await deleteMenu(row.id);
    window.$message?.success(t('mine.menus.message.deleted'));
    checkedRowKeys.value = checkedRowKeys.value.filter(id => id !== row.id);
    await load();
  } catch (error) {
    showError(error);
  }
}

async function batchRemove() {
  if (checkedRowKeys.value.length === 0) return;
  if (!window.confirm(t('mine.menus.confirmBatchDelete', { count: checkedRowKeys.value.length }))) return;
  deleting.value = true;
  try {
    await deleteMenus([...checkedRowKeys.value]);
    window.$message?.success(t('mine.menus.message.batchDeleted', { count: checkedRowKeys.value.length }));
    checkedRowKeys.value = [];
    await load();
  } catch (error) {
    showError(error);
  } finally {
    deleting.value = false;
  }
}

function updateCheckedRowKeys(keys: DataTableRowKey[]) {
  checkedRowKeys.value = keys.map(String);
}

function openButtonCreate() {
  if (!editingId.value || form.type !== 'MENU') return;
  editingButtonId.value = null;
  Object.assign(buttonForm, emptyButtonForm(editingId.value));
  buttonEditorVisible.value = true;
}

function openButtonEdit(row: ManagedMenuNode) {
  editingButtonId.value = row.id;
  Object.assign(buttonForm, {
    parentId: row.parentId,
    name: row.name,
    type: 'BUTTON',
    path: '',
    componentKey: '',
    permission: row.permission || '',
    icon: '',
    sortOrder: row.sortOrder,
    visible: false,
    status: row.status,
    i18nKey: '',
    version: row.version || 0
  });
  buttonEditorVisible.value = true;
}

async function saveButton() {
  if (!buttonForm.name.trim() || !buttonForm.permission?.trim() || !editingId.value) {
    return window.$message?.warning(t('mine.menus.validation.buttonRequired'));
  }
  buttonSaving.value = true;
  try {
    const payload = cleanPayload({ ...buttonForm, parentId: editingId.value, type: 'BUTTON' });
    if (editingButtonId.value) await updateMenu(editingButtonId.value, payload);
    else await createMenu(payload);
    window.$message?.success(
      t(editingButtonId.value ? 'mine.menus.message.buttonUpdated' : 'mine.menus.message.buttonCreated')
    );
    buttonEditorVisible.value = false;
    await load();
  } catch (error) {
    showError(error);
  } finally {
    buttonSaving.value = false;
  }
}

async function removeButton(row: ManagedMenuNode) {
  if (!window.confirm(t('mine.menus.confirmDeleteButton', { name: row.name }))) return;
  try {
    await deleteMenu(row.id);
    window.$message?.success(t('mine.menus.message.buttonDeleted'));
    await load();
  } catch (error) {
    showError(error);
  }
}

function emptyMenuForm(type: Exclude<MenuNodeType, 'BUTTON'> = 'DIRECTORY'): MenuSavePayload {
  return {
    parentId: null,
    name: '',
    type,
    path: '',
    componentKey: '',
    permission: '',
    icon: '',
    sortOrder: 10,
    visible: true,
    status: 'ENABLED',
    i18nKey: '',
    version: 0
  };
}

function emptyButtonForm(parentId: string | null = null): MenuSavePayload {
  return {
    parentId,
    name: '',
    type: 'BUTTON',
    path: '',
    componentKey: '',
    permission: '',
    icon: '',
    sortOrder: 10,
    visible: false,
    status: 'ENABLED',
    i18nKey: '',
    version: 0
  };
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

function projectMenuRows(nodes: ManagedMenuNode[], parentName: string | null = null): MenuListRow[] {
  return nodes.flatMap(node => {
    if (node.type === 'BUTTON') return [];
    const { children, ...values } = node;
    const row: MenuListRow = { ...values, parentName, childCount: children.length };
    return [row, ...projectMenuRows(children, node.name)];
  });
}

function findMenuNode(nodes: ManagedMenuNode[], id: string): ManagedMenuNode | null {
  for (const node of nodes) {
    if (node.id === id) return node;
    const child = findMenuNode(node.children, id);
    if (child) return child;
  }
  return null;
}

function collectDescendantIds(nodes: ManagedMenuNode[]): Set<string> {
  const ids = new Set<string>();
  nodes.forEach(node => {
    ids.add(node.id);
    collectDescendantIds(node.children).forEach(id => ids.add(id));
  });
  return ids;
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
    <NPageHeader :title="t('mine.menus.title')" :subtitle="t('mine.menus.subtitle')" />

    <NCard :bordered="false">
      <NSpace vertical :size="16">
        <NGrid responsive="screen" cols="1 s:2 m:3" :x-gap="12">
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
                :options="menuTypeOptions"
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
        </NGrid>

        <NSpace justify="space-between" align="center">
          <NText strong>{{ t('mine.menus.listTitle') }}</NText>
          <NSpace>
            <NButton
              v-if="can('system:menu:delete')"
              type="error"
              secondary
              :disabled="checkedRowKeys.length === 0"
              :loading="deleting"
              @click="batchRemove"
            >
              {{ t('mine.menus.action.batchDelete', { count: checkedRowKeys.length }) }}
            </NButton>
            <NButton data-action="refresh-menu" :loading="loading" @click="load">
              {{ t('mine.menus.action.refresh') }}
            </NButton>
            <NPopover trigger="click" placement="bottom-end">
              <template #trigger>
                <NButton data-action="configure-columns">{{ t('mine.menus.action.columns') }}</NButton>
              </template>
              <NCheckboxGroup v-model:value="visibleColumnKeys">
                <NSpace vertical>
                  <NCheckbox v-for="option in columnOptions" :key="option.key" :value="option.key">
                    {{ option.label }}
                  </NCheckbox>
                </NSpace>
              </NCheckboxGroup>
            </NPopover>
            <NButton v-if="can('system:menu:create')" type="primary" @click="openCreate()">
              {{ t('mine.menus.action.create') }}
            </NButton>
          </NSpace>
        </NSpace>

        <NDataTable
          :columns="columns"
          :data="pagedRows"
          :loading="loading"
          :row-key="row => row.id"
          :checked-row-keys="checkedRowKeys"
          :scroll-x="1280"
          @update:checked-row-keys="updateCheckedRowKeys"
        />

        <NSpace justify="space-between" align="center">
          <NText depth="3">{{ t('mine.menus.total', { count: filteredRows.length }) }}</NText>
          <NPagination
            v-model:page="page"
            v-model:page-size="pageSize"
            data-pagination
            show-size-picker
            :page-sizes="[10, 20, 50]"
            :item-count="filteredRows.length"
          />
        </NSpace>
      </NSpace>
    </NCard>

    <NModal
      v-model:show="modalVisible"
      preset="card"
      :title="t(editingId ? 'mine.menus.modal.edit' : 'mine.menus.modal.create')"
      :style="{ width: 'min(980px, 94vw)' }"
    >
      <NForm label-placement="left" label-width="auto" require-mark-placement="right-hanging">
        <NGrid responsive="screen" cols="1 m:2" :x-gap="24">
          <NGi>
            <NFormItem data-menu-field>
              <template #label>
                <MenuFieldLabel label-key="mine.menus.field.type" help-key="mine.menus.help.type" />
              </template>
              <NSelect v-model:value="form.type" :options="menuTypeOptions" />
            </NFormItem>
          </NGi>
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
          <NGi v-if="form.type === 'MENU'">
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
          <NGi>
            <NFormItem data-menu-field>
              <template #label>
                <MenuFieldLabel label-key="mine.menus.field.visible" help-key="mine.menus.help.visible" />
              </template>
              <NSwitch v-model:value="form.visible" />
            </NFormItem>
          </NGi>
        </NGrid>

        <template v-if="editingId && form.type === 'MENU'">
          <NDivider>{{ t('mine.menus.buttonPermissions') }}</NDivider>
          <NSpace vertical :size="12">
            <NSpace justify="space-between" align="center">
              <NText depth="3">{{ t('mine.menus.buttonPermissionTip') }}</NText>
              <NButton v-if="can('system:menu:create')" size="small" @click="openButtonCreate">
                {{ t('mine.menus.action.addButton') }}
              </NButton>
            </NSpace>
            <NDataTable :columns="buttonColumns" :data="buttonRows" :row-key="row => row.id" size="small" />

            <NGrid v-if="buttonEditorVisible" responsive="screen" cols="1 m:2" :x-gap="24">
              <NGi>
                <NFormItem data-button-field>
                  <template #label>
                    <MenuFieldLabel label-key="mine.menus.field.name" help-key="mine.menus.help.buttonName" />
                  </template>
                  <NInput v-model:value="buttonForm.name" />
                </NFormItem>
              </NGi>
              <NGi>
                <NFormItem data-button-field>
                  <template #label>
                    <MenuFieldLabel
                      label-key="mine.menus.field.permission"
                      help-key="mine.menus.help.buttonPermission"
                    />
                  </template>
                  <NInput v-model:value="buttonForm.permission" />
                </NFormItem>
              </NGi>
              <NGi>
                <NFormItem data-button-field>
                  <template #label>
                    <MenuFieldLabel label-key="mine.menus.field.sort" help-key="mine.menus.help.sort" />
                  </template>
                  <NInputNumber v-model:value="buttonForm.sortOrder" :min="0" />
                </NFormItem>
              </NGi>
              <NGi>
                <NFormItem data-button-field>
                  <template #label>
                    <MenuFieldLabel label-key="mine.menus.field.status" help-key="mine.menus.help.status" />
                  </template>
                  <NSelect v-model:value="buttonForm.status" :options="statusOptions" />
                </NFormItem>
              </NGi>
              <NGi :span="2">
                <NSpace justify="end">
                  <NButton @click="buttonEditorVisible = false">{{ t('mine.common.cancel') }}</NButton>
                  <NButton type="primary" :loading="buttonSaving" @click="saveButton">
                    {{ t('mine.common.save') }}
                  </NButton>
                </NSpace>
              </NGi>
            </NGrid>
          </NSpace>
        </template>
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
