<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, watch } from 'vue';
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
  NRadio,
  NRadioGroup,
  NSelect,
  NSpace,
  NSwitch,
  NTag,
  NText,
  NTooltip
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import FormFieldLabel from '@/components/common/form-field-label.vue';
import SvgIcon from '@/components/custom/svg-icon.vue';
import MenuIconSelect from '@/modules/system/components/MenuIconSelect.vue';
import {
  createMenu,
  deleteMenu,
  deleteMenus,
  getMenuButtons,
  getMenuComponentOptions,
  getMenus,
  getMenuTree,
  updateMenu
} from '@/modules/system/api/access-management';
import type {
  ManagedMenuListItem,
  ManagedMenuNode,
  MenuComponentOption,
  MenuNodeType,
  MenuSavePayload
} from '@/modules/system/api/access-management';
import { useAuthStore } from '@/store/modules/auth';
import { encodeMenuIcon, resolveMenuIcon, resolveMenuIconType } from '@/utils/menu-icon';
import type { MenuIconType } from '@/utils/menu-icon';

type MenuListRow = ManagedMenuListItem & {
  isLeaf: boolean;
  hierarchyPath: string | null;
  children?: MenuListRow[];
};

type MenuColumnKey = 'id' | 'type' | 'name' | 'icon' | 'path' | 'permission' | 'status' | 'visible' | 'parent' | 'sort';

const { t } = useI18n();
const authStore = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const deleting = ref(false);
const buttonSaving = ref(false);
const buttonLoading = ref(false);
const rootRows = ref<MenuListRow[]>([]);
const menuTree = ref<ManagedMenuNode[]>([]);
const menuTreeLoaded = ref(false);
const total = ref(0);
const buttonRows = ref<ManagedMenuNode[]>([]);
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
const filters = reactive<{
  name: string;
  type: Exclude<MenuNodeType, 'BUTTON'> | null;
  status: 'ENABLED' | 'DISABLED' | null;
}>({
  name: '',
  type: null,
  status: null
});
const form = reactive<MenuSavePayload>(emptyMenuForm());
const buttonForm = reactive<MenuSavePayload>(emptyButtonForm());
const menuIconType = ref<MenuIconType>('iconify');

const pagedRows = computed(() => rootRows.value);
const descendantIds = computed(() =>
  editingId.value
    ? collectDescendantIds(findMenuNode(menuTree.value, editingId.value)?.children || [])
    : new Set<string>()
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
  projectDirectoryOptions(menuTree.value)
    .filter(row => row.id !== editingId.value && !descendantIds.value.has(row.id))
    .map(row => ({ label: row.label, value: row.id }))
);
const pageOptions = computed<SelectOption[]>(() =>
  componentOptions.value.map(option => ({ label: option.name, value: option.key }))
);
const selectedMenuIcon = computed({
  get() {
    const resolved = resolveMenuIcon(form.icon);
    return menuIconType.value === 'local' ? resolved.localIcon || null : resolved.icon || null;
  },
  set(value: string | null) {
    form.icon = encodeMenuIcon(menuIconType.value, value);
  }
});
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
        render: row => h(NTag, { size: 'small', bordered: false }, () => t(`mine.menus.type.${row.type.toLowerCase()}`))
      }
    },
    {
      key: 'name',
      column: {
        title: t('mine.menus.column.name'),
        key: 'name',
        minWidth: 150,
        fixed: 'left',
        tree: true,
        render: row => h(NText, { depth: row.status === 'ENABLED' ? undefined : 3 }, () => row.name)
      }
    },
    {
      key: 'icon',
      column: {
        title: t('mine.menus.column.icon'),
        key: 'icon',
        width: 72,
        align: 'center',
        render: row => renderMenuIcon(row.icon)
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
        key: 'hierarchyPath',
        minWidth: 180,
        ellipsis: { tooltip: true },
        render: row => row.hierarchyPath || t('mine.menus.root')
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
      disabled: row => row.protectedNode || row.hasChildren
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
        can('system:menu:delete') && !row.protectedNode && !row.hasChildren
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
          ? h(NButton, { text: true, type: 'primary', onClick: () => openButtonEdit(row) }, () => t('mine.common.edit'))
          : null,
        can('system:menu:delete') && !row.protectedNode
          ? h(NButton, { text: true, type: 'error', onClick: () => removeButton(row) }, () => t('mine.common.delete'))
          : null
      ])
  }
]);

let loadSequence = 0;

watch(
  () => [filters.name, filters.type, filters.status],
  () => {
    checkedRowKeys.value = [];
    if (page.value === 1) void load();
    else page.value = 1;
  }
);
watch(page, () => {
  checkedRowKeys.value = [];
  void load();
});
watch(pageSize, () => {
  checkedRowKeys.value = [];
  if (page.value === 1) void load();
  else page.value = 1;
});

onMounted(async () => {
  await Promise.all([load(), loadComponentOptions()]);
});

async function load() {
  const sequence = ++loadSequence;
  loading.value = true;
  try {
    const result = await getMenus({
      parentId: undefined,
      keyword: filters.name,
      type: filters.type || '',
      status: filters.status || '',
      page: page.value,
      size: pageSize.value
    });
    if (sequence !== loadSequence) return;
    rootRows.value = result.items.map(item => toMenuRow(item, null));
    total.value = result.total;
    const lastPage = Math.max(1, Math.ceil(result.total / pageSize.value));
    if (page.value > lastPage) page.value = lastPage;
  } catch (error) {
    showError(error);
  } finally {
    if (sequence === loadSequence) loading.value = false;
  }
}

async function loadComponentOptions() {
  try {
    componentOptions.value = await getMenuComponentOptions();
  } catch (error) {
    showError(error);
  }
}

async function loadChildren(row: object) {
  const menuRow = row as MenuListRow;
  if (menuRow.children) return;
  try {
    const items: ManagedMenuListItem[] = [];
    let childPage = 1;
    let childTotal = 0;
    do {
      const result = await getMenus({
        parentId: menuRow.id,
        keyword: filters.name,
        type: filters.type || '',
        status: filters.status || '',
        page: childPage,
        size: 100
      });
      items.push(...result.items);
      childTotal = result.total;
      childPage += 1;
      if (result.items.length === 0) break;
    } while (items.length < childTotal);
    const childPath = menuRow.hierarchyPath ? `${menuRow.hierarchyPath} / ${menuRow.name}` : menuRow.name;
    menuRow.children = items.map(item => toMenuRow(item, childPath));
    menuRow.isLeaf = menuRow.children.length === 0;
    rootRows.value = [...rootRows.value];
  } catch (error) {
    showError(error);
  }
}

async function ensureMenuTree() {
  if (menuTreeLoaded.value) return;
  try {
    menuTree.value = await getMenuTree();
    menuTreeLoaded.value = true;
  } catch (error) {
    showError(error);
  }
}

function openCreate(type: Exclude<MenuNodeType, 'BUTTON'> = 'DIRECTORY') {
  editingId.value = null;
  buttonEditorVisible.value = false;
  menuIconType.value = 'iconify';
  Object.assign(form, emptyMenuForm(type));
  modalVisible.value = true;
  void ensureMenuTree();
}

function openEdit(row: MenuListRow | ManagedMenuNode) {
  editingId.value = row.id;
  buttonEditorVisible.value = false;
  buttonRows.value = [];
  menuIconType.value = resolveMenuIconType(row.icon);
  Object.assign(form, {
    parentId: row.parentId,
    name: row.name,
    type: row.type,
    path: row.path || '',
    componentKey: row.componentKey || undefined,
    permission: row.permission || '',
    icon: row.icon || '',
    sortOrder: row.sortOrder,
    visible: row.visible,
    status: row.status,
    i18nKey: row.i18nKey || '',
    version: row.version || 0
  });
  modalVisible.value = true;
  void ensureMenuTree();
  if (row.type === 'MENU') void loadButtons(row.id);
}

async function loadButtons(menuId: string) {
  buttonLoading.value = true;
  try {
    buttonRows.value = await getMenuButtons(menuId);
  } catch (error) {
    showError(error);
  } finally {
    buttonLoading.value = false;
  }
}

async function save() {
  const menuRequiredMissing =
    form.type === 'MENU' &&
    (!form.parentId || !form.path?.trim() || !form.componentKey?.trim() || !form.permission?.trim());
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
    menuTreeLoaded.value = false;
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
    menuTreeLoaded.value = false;
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
    menuTreeLoaded.value = false;
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
    componentKey: undefined,
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
    await loadButtons(editingId.value);
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
    if (editingId.value) await loadButtons(editingId.value);
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
    componentKey: undefined,
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

function toMenuRow(item: ManagedMenuListItem, hierarchyPath: string | null): MenuListRow {
  return {
    ...item,
    isLeaf: !item.hasChildren,
    hierarchyPath,
    children: item.hasChildren ? undefined : []
  };
}

function projectDirectoryOptions(
  nodes: ManagedMenuNode[],
  ancestors: string[] = []
): Array<{ id: string; label: string }> {
  return nodes.flatMap(node => {
    if (node.type !== 'DIRECTORY') return [];
    const label = [...ancestors, node.name].join(' / ');
    return [{ id: node.id, label }, ...projectDirectoryOptions(node.children, [...ancestors, node.name])];
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

function updateMenuIconType(value: MenuIconType) {
  if (menuIconType.value === value) return;
  menuIconType.value = value;
  form.icon = undefined;
}

function renderMenuIcon(value?: string | null) {
  const { icon, localIcon } = resolveMenuIcon(value);
  if (!icon && !localIcon) return t('mine.common.empty');
  return h(
    NTooltip,
    { trigger: 'hover' },
    {
      trigger: () =>
        h(
          'span',
          {
            class: 'inline-flex items-center text-20px',
            'data-menu-icon-value': value,
            'aria-label': value
          },
          [h(SvgIcon, { icon, localIcon })]
        ),
      default: () => value
    }
  );
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
          :on-load="loadChildren"
          :checked-row-keys="checkedRowKeys"
          :scroll-x="1280"
          :indent="20"
          @update:checked-row-keys="updateCheckedRowKeys"
        />

        <NSpace justify="space-between" align="center">
          <NText depth="3">{{ t('mine.menus.total', { count: total }) }}</NText>
          <NPagination
            v-model:page="page"
            v-model:page-size="pageSize"
            data-pagination
            show-size-picker
            :page-sizes="[10, 20, 50]"
            :item-count="total"
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
                <FormFieldLabel label-key="mine.menus.field.type" help-key="mine.menus.help.type" />
              </template>
              <NSelect
                v-model:value="form.type"
                :options="menuTypeOptions"
                :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.menus.field.type') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem data-menu-field>
              <template #label>
                <FormFieldLabel label-key="mine.menus.field.name" help-key="mine.menus.help.name" />
              </template>
              <NInput
                v-model:value="form.name"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.menus.field.name') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem data-menu-field>
              <template #label>
                <FormFieldLabel label-key="mine.menus.field.parent" help-key="mine.menus.help.parent" />
              </template>
              <NSelect
                v-model:value="form.parentId"
                clearable
                :options="parentOptions"
                :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.menus.field.parent') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem data-menu-field>
              <template #label>
                <FormFieldLabel label-key="mine.menus.field.sort" help-key="mine.menus.help.sort" />
              </template>
              <NInputNumber
                v-model:value="form.sortOrder"
                :min="0"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.menus.field.sort') })"
              />
            </NFormItem>
          </NGi>
          <NGi v-if="form.type === 'MENU'">
            <NFormItem data-menu-field>
              <template #label>
                <FormFieldLabel label-key="mine.menus.field.path" help-key="mine.menus.help.path" />
              </template>
              <NInput
                v-model:value="form.path"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.menus.field.path') })"
              />
            </NFormItem>
          </NGi>
          <NGi v-if="form.type === 'MENU'">
            <NFormItem data-menu-field>
              <template #label>
                <FormFieldLabel label-key="mine.menus.field.component" help-key="mine.menus.help.component" />
              </template>
              <NSelect
                v-model:value="form.componentKey"
                filterable
                :options="pageOptions"
                :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.menus.field.component') })"
              />
            </NFormItem>
          </NGi>
          <NGi v-if="form.type === 'MENU'">
            <NFormItem data-menu-field>
              <template #label>
                <FormFieldLabel label-key="mine.menus.field.permission" help-key="mine.menus.help.permission" />
              </template>
              <NInput
                v-model:value="form.permission"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.menus.field.permission') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem data-menu-field>
              <template #label>
                <FormFieldLabel label-key="mine.menus.field.iconType" help-key="mine.menus.help.iconType" />
              </template>
              <NRadioGroup
                data-menu-icon-type
                :value="menuIconType"
                @update:value="updateMenuIconType($event as MenuIconType)"
              >
                <NSpace>
                  <NRadio value="iconify">{{ t('mine.menus.iconType.iconify') }}</NRadio>
                  <NRadio value="local">{{ t('mine.menus.iconType.local') }}</NRadio>
                </NSpace>
              </NRadioGroup>
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem data-menu-field>
              <template #label>
                <FormFieldLabel label-key="mine.menus.field.icon" help-key="mine.menus.help.icon" />
              </template>
              <MenuIconSelect v-model:value="selectedMenuIcon" :type="menuIconType" />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem data-menu-field>
              <template #label>
                <FormFieldLabel label-key="mine.menus.field.i18nKey" help-key="mine.menus.help.i18nKey" />
              </template>
              <NInput
                v-model:value="form.i18nKey"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.menus.field.i18nKey') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem data-menu-field>
              <template #label>
                <FormFieldLabel label-key="mine.menus.field.status" help-key="mine.menus.help.status" />
              </template>
              <NSelect
                v-model:value="form.status"
                :options="statusOptions"
                :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.menus.field.status') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem data-menu-field>
              <template #label>
                <FormFieldLabel label-key="mine.menus.field.visible" help-key="mine.menus.help.visible" />
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
            <NDataTable
              :columns="buttonColumns"
              :data="buttonRows"
              :loading="buttonLoading"
              :row-key="row => row.id"
              size="small"
            />

            <NGrid v-if="buttonEditorVisible" responsive="screen" cols="1 m:2" :x-gap="24">
              <NGi>
                <NFormItem data-button-field>
                  <template #label>
                    <FormFieldLabel label-key="mine.menus.field.name" help-key="mine.menus.help.buttonName" />
                  </template>
                  <NInput
                    v-model:value="buttonForm.name"
                    :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.menus.field.name') })"
                  />
                </NFormItem>
              </NGi>
              <NGi>
                <NFormItem data-button-field>
                  <template #label>
                    <FormFieldLabel
                      label-key="mine.menus.field.permission"
                      help-key="mine.menus.help.buttonPermission"
                    />
                  </template>
                  <NInput
                    v-model:value="buttonForm.permission"
                    :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.menus.field.permission') })"
                  />
                </NFormItem>
              </NGi>
              <NGi>
                <NFormItem data-button-field>
                  <template #label>
                    <FormFieldLabel label-key="mine.menus.field.sort" help-key="mine.menus.help.sort" />
                  </template>
                  <NInputNumber
                    v-model:value="buttonForm.sortOrder"
                    :min="0"
                    :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.menus.field.sort') })"
                  />
                </NFormItem>
              </NGi>
              <NGi>
                <NFormItem data-button-field>
                  <template #label>
                    <FormFieldLabel label-key="mine.menus.field.status" help-key="mine.menus.help.status" />
                  </template>
                  <NSelect
                    v-model:value="buttonForm.status"
                    :options="statusOptions"
                    :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.menus.field.status') })"
                  />
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
