<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns, SelectOption, TreeOption } from 'naive-ui';
import {
  NButton,
  NCard,
  NDataTable,
  NFlex,
  NForm,
  NFormItem,
  NGi,
  NGrid,
  NInput,
  NModal,
  NPageHeader,
  NSelect,
  NSpace,
  NTag,
  NText,
  NTree
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import {
  createRole,
  deleteRole,
  getMenuTree,
  getRoles,
  replaceRoleMenus,
  updateRole
} from '@/modules/system/api/access-management';
import type { ManagedMenuNode, ManagedRole, RoleStatus } from '@/modules/system/api/access-management';
import { useAuthStore } from '@/store/modules/auth';

const { t } = useI18n();
const authStore = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const roles = ref<ManagedRole[]>([]);
const total = ref(0);
const query = reactive<{ keyword: string; status: RoleStatus | null; page: number; size: number }>({
  keyword: '',
  status: null,
  page: 1,
  size: 20
});
const roleModalVisible = ref(false);
const editingRoleId = ref<string | null>(null);
const roleForm = reactive({
  code: '',
  name: '',
  status: 'ENABLED' as RoleStatus,
  description: '',
  version: 0
});
const permissionModalVisible = ref(false);
const permissionRole = ref<ManagedRole | null>(null);
const menuTree = ref<TreeOption[]>([]);
const checkedMenuIds = ref<Array<string | number>>([]);

const statusOptions = computed<SelectOption[]>(() => [
  { label: t('mine.common.status.enabled'), value: 'ENABLED' },
  { label: t('mine.common.status.disabled'), value: 'DISABLED' }
]);
const columns = computed<DataTableColumns<ManagedRole>>(() => [
  { title: t('mine.roles.column.code'), key: 'code', minWidth: 150 },
  { title: t('mine.roles.column.name'), key: 'name', minWidth: 150 },
  {
    title: t('mine.roles.column.status'),
    key: 'status',
    width: 100,
    render: row =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.status === 'ENABLED' ? 'success' : 'warning' },
        { default: () => t(`mine.common.status.${row.status.toLowerCase()}`) }
      )
  },
  {
    title: t('mine.roles.column.description'),
    key: 'description',
    minWidth: 220,
    ellipsis: { tooltip: true },
    render: row => row.description || t('mine.common.empty')
  },
  {
    title: t('mine.roles.column.permissions'),
    key: 'menuIds',
    width: 120,
    render: row => t('mine.roles.permissionCount', { count: row.menuIds.length })
  },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 230,
    render: row =>
      h(NSpace, { size: 8 }, () => [
        can('system:role:update')
          ? h(NButton, { text: true, type: 'primary', onClick: () => openEdit(row) }, () => t('mine.common.edit'))
          : null,
        can('system:role:assign-menu')
          ? h(NButton, { text: true, onClick: () => openPermissions(row) }, () => t('mine.roles.action.permissions'))
          : null,
        can('system:role:delete')
          ? h(NButton, { text: true, type: 'error', onClick: () => removeRole(row) }, () => t('mine.common.delete'))
          : null
      ])
  }
]);

onMounted(loadRoles);

async function loadRoles() {
  loading.value = true;
  try {
    const response = await getRoles(query);
    roles.value = response.items;
    total.value = response.total;
    query.page = response.page;
    query.size = response.size;
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
}

function search() {
  query.page = 1;
  return loadRoles();
}

function resetQuery() {
  Object.assign(query, { keyword: '', status: null, page: 1 });
  return loadRoles();
}

function openCreate() {
  editingRoleId.value = null;
  Object.assign(roleForm, { code: '', name: '', status: 'ENABLED', description: '', version: 0 });
  roleModalVisible.value = true;
}

function openEdit(role: ManagedRole) {
  editingRoleId.value = role.id;
  Object.assign(roleForm, {
    code: role.code,
    name: role.name,
    status: role.status,
    description: role.description || '',
    version: role.version
  });
  roleModalVisible.value = true;
}

async function saveRole() {
  if (!roleForm.name.trim() || (!editingRoleId.value && !roleForm.code.trim())) {
    return window.$message?.warning(t('mine.roles.validation.required'));
  }
  saving.value = true;
  try {
    if (editingRoleId.value) {
      await updateRole(editingRoleId.value, {
        name: roleForm.name.trim(),
        status: roleForm.status,
        description: roleForm.description.trim() || undefined,
        version: roleForm.version
      });
    } else {
      await createRole({
        code: roleForm.code.trim().toUpperCase(),
        name: roleForm.name.trim(),
        status: roleForm.status,
        description: roleForm.description.trim() || undefined
      });
    }
    window.$message?.success(t(editingRoleId.value ? 'mine.roles.message.updated' : 'mine.roles.message.created'));
    roleModalVisible.value = false;
    await loadRoles();
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

async function removeRole(role: ManagedRole) {
  if (!window.confirm(t('mine.roles.confirmDelete', { name: role.name }))) return;
  try {
    await deleteRole(role.id);
    window.$message?.success(t('mine.roles.message.deleted'));
    await loadRoles();
  } catch (error) {
    showError(error);
  }
}

async function openPermissions(role: ManagedRole) {
  permissionRole.value = role;
  checkedMenuIds.value = [...role.menuIds];
  permissionModalVisible.value = true;
  try {
    const nodes = await getMenuTree();
    menuTree.value = nodes.map(toTreeOption);
  } catch (error) {
    showError(error);
  }
}

async function savePermissions() {
  if (!permissionRole.value) return;
  saving.value = true;
  try {
    await replaceRoleMenus(permissionRole.value.id, checkedMenuIds.value.map(String));
    window.$message?.success(t('mine.roles.message.permissionsUpdated'));
    permissionModalVisible.value = false;
    await loadRoles();
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

function toTreeOption(node: ManagedMenuNode): TreeOption {
  const suffix = node.permission ? ` · ${node.permission}` : '';
  return {
    key: node.id,
    label: `${node.name}${suffix}`,
    disabled: node.status !== 'ENABLED',
    children: node.children.map(toTreeOption)
  };
}

function can(permission: string) {
  return hasPermission(authStore.userInfo.buttons, permission);
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

defineExpose({
  roleForm,
  checkedMenuIds,
  openCreate,
  openEdit,
  saveRole,
  removeRole,
  openPermissions,
  savePermissions
});
</script>

<template>
  <NSpace vertical :size="16">
    <NPageHeader :title="t('mine.roles.title')" :subtitle="t('mine.roles.subtitle')">
      <template #extra>
        <NButton v-if="can('system:role:create')" type="primary" data-action="create-role" @click="openCreate">
          {{ t('mine.roles.action.create') }}
        </NButton>
      </template>
    </NPageHeader>

    <NCard size="small">
      <NFlex align="center">
        <NInput
          v-model:value="query.keyword"
          clearable
          :placeholder="t('mine.roles.search')"
          class="min-w-60 flex-1"
          @keyup.enter="search"
        />
        <NSelect
          v-model:value="query.status"
          clearable
          :options="statusOptions"
          :placeholder="t('mine.common.allStatus')"
          class="w-52"
        />
        <NSpace>
          <NButton type="primary" @click="search">{{ t('mine.common.search') }}</NButton>
          <NButton @click="resetQuery">{{ t('mine.common.reset') }}</NButton>
        </NSpace>
      </NFlex>
    </NCard>

    <NCard size="small">
      <NDataTable
        remote
        :columns="columns"
        :data="roles"
        :loading="loading"
        :pagination="{
          page: query.page,
          pageSize: query.size,
          itemCount: total,
          showSizePicker: true,
          pageSizes: [10, 20, 50, 100]
        }"
        :row-key="row => row.id"
        @update:page="
          page => {
            query.page = page;
            loadRoles();
          }
        "
        @update:page-size="
          size => {
            query.size = size;
            query.page = 1;
            loadRoles();
          }
        "
      />
    </NCard>

    <NModal
      v-model:show="roleModalVisible"
      preset="card"
      :title="t(editingRoleId ? 'mine.roles.modal.edit' : 'mine.roles.modal.create')"
      class="w-full max-w-640px"
    >
      <NForm label-placement="top">
        <NGrid responsive="screen" cols="1 s:2" :x-gap="16">
          <NGi>
            <NFormItem :label="t('mine.roles.field.code')">
              <NInput
                v-model:value="roleForm.code"
                :disabled="Boolean(editingRoleId)"
                :placeholder="t('mine.roles.placeholder.code')"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.roles.field.name')">
              <NInput
                v-model:value="roleForm.name"
                maxlength="128"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.roles.field.name') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.roles.field.status')">
              <NSelect
                v-model:value="roleForm.status"
                :options="statusOptions"
                :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.roles.field.status') })"
              />
            </NFormItem>
          </NGi>
          <NGi :span="2">
            <NFormItem :label="t('mine.roles.field.description')">
              <NInput
                v-model:value="roleForm.description"
                type="textarea"
                maxlength="500"
                show-count
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.roles.field.description') })"
              />
            </NFormItem>
          </NGi>
        </NGrid>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="roleModalVisible = false">{{ t('mine.common.cancel') }}</NButton>
          <NButton type="primary" :loading="saving" data-action="save-role" @click="saveRole">
            {{ t('mine.common.save') }}
          </NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="permissionModalVisible"
      preset="card"
      :title="t('mine.roles.modal.permissions')"
      class="w-full max-w-760px"
    >
      <NSpace vertical :size="16">
        <NText depth="3">{{ t('mine.roles.tip.permissions', { name: permissionRole?.name || '' }) }}</NText>
        <NTree v-model:checked-keys="checkedMenuIds" block-line cascade checkable default-expand-all :data="menuTree" />
      </NSpace>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="permissionModalVisible = false">{{ t('mine.common.cancel') }}</NButton>
          <NButton type="primary" :loading="saving" data-action="save-permissions" @click="savePermissions">
            {{ t('mine.roles.action.publishPermissions') }}
          </NButton>
        </NSpace>
      </template>
    </NModal>
  </NSpace>
</template>
