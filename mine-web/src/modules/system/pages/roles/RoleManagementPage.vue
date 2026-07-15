<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import type { DataTableColumns, SelectOption, TreeOption } from 'naive-ui';
import { NButton, NDataTable, NInput, NModal, NSelect, NSpace, NTag, NTree } from 'naive-ui';
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

const authStore = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const roles = ref<ManagedRole[]>([]);
const total = ref(0);
const query = reactive<{ keyword: string; status: RoleStatus | ''; page: number; size: number }>({
  keyword: '',
  status: '',
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

const statusOptions: SelectOption[] = [
  { label: '启用', value: 'ENABLED' },
  { label: '停用', value: 'DISABLED' }
];

const columns = computed<DataTableColumns<ManagedRole>>(() => [
  { title: '角色编码', key: 'code', minWidth: 150 },
  { title: '角色名称', key: 'name', minWidth: 150 },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: row =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.status === 'ENABLED' ? 'success' : 'warning' },
        { default: () => (row.status === 'ENABLED' ? '启用' : '停用') }
      )
  },
  {
    title: '说明',
    key: 'description',
    minWidth: 220,
    ellipsis: { tooltip: true },
    render: row => row.description || '—'
  },
  {
    title: '授权节点',
    key: 'menuIds',
    width: 100,
    render: row => `${row.menuIds.length} 项`
  },
  {
    title: '操作',
    key: 'actions',
    width: 230,
    render: row =>
      h(NSpace, { size: 8 }, () => [
        can('system:role:update')
          ? h(NButton, { text: true, type: 'primary', onClick: () => openEdit(row) }, () => '编辑')
          : null,
        can('system:role:assign-menu')
          ? h(NButton, { text: true, onClick: () => openPermissions(row) }, () => '权限配置')
          : null,
        can('system:role:delete')
          ? h(NButton, { text: true, type: 'error', onClick: () => removeRole(row) }, () => '删除')
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
  Object.assign(query, { keyword: '', status: '', page: 1 });
  return loadRoles();
}

function openCreate() {
  editingRoleId.value = null;
  Object.assign(roleForm, {
    code: '',
    name: '',
    status: 'ENABLED',
    description: '',
    version: 0
  });
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
    return window.$message?.warning('请填写角色编码和名称');
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
    window.$message?.success(editingRoleId.value ? '角色已更新' : '角色已创建');
    roleModalVisible.value = false;
    await loadRoles();
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

async function removeRole(role: ManagedRole) {
  if (!window.confirm(`确认删除角色“${role.name}”吗？`)) return;
  try {
    await deleteRole(role.id);
    window.$message?.success('角色已删除');
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
    window.$message?.success('角色权限已更新，现有会话下一次请求即生效');
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
    const trace = error.traceId ? `（链路：${error.traceId}）` : '';
    window.$message?.error(`${error.message}${trace}`);
    return;
  }
  window.$message?.error('操作失败，请稍后重试');
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
  <main class="access-page">
    <header class="page-heading">
      <div>
        <span>SYSTEM ACCESS / ROLES</span>
        <h1>角色与权限</h1>
        <p>角色编码发布后保持不变；权限节点由平台版本维护，管理员只负责勾选授权。</p>
      </div>
      <NButton v-if="can('system:role:create')" type="primary" data-action="create-role" @click="openCreate">
        创建角色
      </NButton>
    </header>

    <section class="filter-panel">
      <NInput v-model:value="query.keyword" clearable placeholder="搜索角色编码或名称" @keyup.enter="search" />
      <NSelect v-model:value="query.status" clearable :options="statusOptions" placeholder="全部状态" />
      <NButton type="primary" @click="search">查询</NButton>
      <NButton @click="resetQuery">重置</NButton>
    </section>

    <section class="table-panel">
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
    </section>

    <NModal
      v-model:show="roleModalVisible"
      preset="card"
      :title="editingRoleId ? '编辑角色' : '创建角色'"
      class="access-modal"
    >
      <div class="form-grid">
        <label>
          角色编码
          <NInput v-model:value="roleForm.code" :disabled="Boolean(editingRoleId)" placeholder="例如 DISPATCHER" />
        </label>
        <label>
          角色名称
          <NInput v-model:value="roleForm.name" maxlength="128" />
        </label>
        <label>
          状态
          <NSelect v-model:value="roleForm.status" :options="statusOptions" />
        </label>
        <label class="form-grid__wide">
          角色说明
          <NInput v-model:value="roleForm.description" type="textarea" maxlength="500" show-count />
        </label>
      </div>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="roleModalVisible = false">取消</NButton>
          <NButton type="primary" :loading="saving" data-action="save-role" @click="saveRole">保存</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="permissionModalVisible"
      preset="card"
      title="角色权限配置"
      class="access-modal access-modal--tree"
    >
      <p class="modal-tip">
        正在配置 {{ permissionRole?.name }}。目录、页面和按钮节点均可授权；按钮节点会显示稳定权限码。
      </p>
      <NTree v-model:checked-keys="checkedMenuIds" block-line cascade checkable default-expand-all :data="menuTree" />
      <template #footer>
        <NSpace justify="end">
          <NButton @click="permissionModalVisible = false">取消</NButton>
          <NButton type="primary" :loading="saving" data-action="save-permissions" @click="savePermissions">
            发布权限
          </NButton>
        </NSpace>
      </template>
    </NModal>
  </main>
</template>

<style scoped>
.access-page {
  min-height: 100%;
  color: #282620;
}

.page-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 20px;
}

.page-heading span {
  color: #ed6500;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
}

.page-heading h1 {
  margin: 7px 0 5px;
  font-size: 32px;
  letter-spacing: -0.035em;
}

.page-heading p,
.modal-tip {
  margin: 0;
  color: #736d63;
}

.filter-panel,
.table-panel {
  border: 1px solid #e2ddd4;
  background: #fff;
  box-shadow: 0 8px 24px rgb(50 45 37 / 4%);
}

.filter-panel {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) 180px auto auto;
  gap: 12px;
  padding: 18px;
}

.table-panel {
  margin-top: 14px;
  padding: 16px;
}

.access-modal {
  width: min(640px, calc(100vw - 32px));
}

.access-modal--tree {
  width: min(760px, calc(100vw - 32px));
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}

.form-grid label {
  display: grid;
  gap: 7px;
  color: #4d4942;
  font-size: 13px;
}

.form-grid__wide {
  grid-column: 1 / -1;
}

.modal-tip {
  margin-bottom: 16px;
  line-height: 1.7;
}

@media (max-width: 720px) {
  .page-heading {
    align-items: start;
    flex-direction: column;
  }

  .filter-panel,
  .form-grid {
    grid-template-columns: 1fr;
  }

  .form-grid__wide {
    grid-column: auto;
  }
}
</style>
