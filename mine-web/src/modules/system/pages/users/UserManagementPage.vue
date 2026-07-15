<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import { NButton, NDataTable, NInput, NModal, NSelect, NSpace, NTag } from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import {
  createUser,
  getRoles,
  getUsers,
  replaceUserRoles,
  resetUserPassword,
  updateUser
} from '@/modules/system/api/access-management';
import type { ManagedUser, UserStatus } from '@/modules/system/api/access-management';
import { useAuthStore } from '@/store/modules/auth';

const authStore = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const users = ref<ManagedUser[]>([]);
const total = ref(0);
const query = reactive<{ keyword: string; status: UserStatus | ''; page: number; size: number }>({
  keyword: '',
  status: '',
  page: 1,
  size: 20
});

const userModalVisible = ref(false);
const editingUserId = ref<string | null>(null);
const userForm = reactive({
  username: '',
  displayName: '',
  status: 'ENABLED' as 'ENABLED' | 'DISABLED',
  temporaryPassword: '',
  version: 0
});

const roleModalVisible = ref(false);
const roleUser = ref<ManagedUser | null>(null);
const selectedRoleIds = ref<string[]>([]);
const roleOptions = ref<SelectOption[]>([]);

const passwordModalVisible = ref(false);
const passwordUser = ref<ManagedUser | null>(null);
const temporaryPassword = ref('');

const statusOptions: SelectOption[] = [
  { label: '启用', value: 'ENABLED' },
  { label: '停用', value: 'DISABLED' },
  { label: '安全锁定', value: 'LOCKED' }
];

const editableStatusOptions = statusOptions.slice(0, 2);

const columns = computed<DataTableColumns<ManagedUser>>(() => [
  { title: '登录名', key: 'username', minWidth: 150 },
  { title: '显示名称', key: 'displayName', minWidth: 150 },
  {
    title: '状态',
    key: 'status',
    width: 110,
    render: row =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.status === 'ENABLED' ? 'success' : 'warning' },
        { default: () => statusLabel(row.status) }
      )
  },
  {
    title: '首次改密',
    key: 'passwordChangeRequired',
    width: 110,
    render: row => (row.passwordChangeRequired ? '待完成' : '已完成')
  },
  {
    title: '最近登录',
    key: 'lastLoginAt',
    minWidth: 170,
    render: row => formatTime(row.lastLoginAt)
  },
  {
    title: '操作',
    key: 'actions',
    width: 260,
    render: row =>
      h(NSpace, { size: 8 }, () => [
        can('system:user:update')
          ? h(NButton, { text: true, type: 'primary', onClick: () => openEdit(row) }, () => '编辑')
          : null,
        can('system:user:assign-role')
          ? h(NButton, { text: true, onClick: () => openRoles(row) }, () => '分配角色')
          : null,
        can('system:user:reset-password')
          ? h(NButton, { text: true, type: 'warning', onClick: () => openPasswordReset(row) }, () => '重置密码')
          : null
      ])
  }
]);

onMounted(loadUsers);

async function loadUsers() {
  loading.value = true;
  try {
    const response = await getUsers(query);
    users.value = response.items;
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
  return loadUsers();
}

function resetQuery() {
  query.keyword = '';
  query.status = '';
  query.page = 1;
  return loadUsers();
}

function openCreate() {
  editingUserId.value = null;
  Object.assign(userForm, {
    username: '',
    displayName: '',
    status: 'ENABLED',
    temporaryPassword: '',
    version: 0
  });
  userModalVisible.value = true;
}

function openEdit(user: ManagedUser) {
  editingUserId.value = user.id;
  Object.assign(userForm, {
    username: user.username,
    displayName: user.displayName,
    status: user.status === 'DISABLED' ? 'DISABLED' : 'ENABLED',
    temporaryPassword: '',
    version: user.version
  });
  userModalVisible.value = true;
}

async function saveUser() {
  if (!userForm.displayName.trim()) return window.$message?.warning('请输入显示名称');
  if (!editingUserId.value && (!userForm.username.trim() || userForm.temporaryPassword.length < 12)) {
    return window.$message?.warning('请填写登录名和至少 12 位临时密码');
  }

  saving.value = true;
  try {
    if (editingUserId.value) {
      await updateUser(editingUserId.value, {
        displayName: userForm.displayName.trim(),
        status: userForm.status,
        version: userForm.version
      });
    } else {
      await createUser({
        username: userForm.username.trim(),
        displayName: userForm.displayName.trim(),
        temporaryPassword: userForm.temporaryPassword,
        status: userForm.status
      });
    }
    window.$message?.success(editingUserId.value ? '用户已更新' : '用户已创建');
    closeUserModal();
    await loadUsers();
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

function closeUserModal() {
  userModalVisible.value = false;
  userForm.temporaryPassword = '';
}

async function openRoles(user: ManagedUser) {
  roleUser.value = user;
  selectedRoleIds.value = [...user.roleIds];
  roleModalVisible.value = true;
  try {
    const response = await getRoles({ page: 1, size: 100, status: 'ENABLED' });
    roleOptions.value = response.items.map(role => ({
      label: `${role.name}（${role.code}）`,
      value: role.id
    }));
  } catch (error) {
    showError(error);
  }
}

async function saveRoles() {
  if (!roleUser.value) return;
  saving.value = true;
  try {
    await replaceUserRoles(roleUser.value.id, selectedRoleIds.value);
    window.$message?.success('用户角色已更新');
    roleModalVisible.value = false;
    await loadUsers();
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

function openPasswordReset(user: ManagedUser) {
  passwordUser.value = user;
  temporaryPassword.value = '';
  passwordModalVisible.value = true;
}

async function savePasswordReset() {
  if (!passwordUser.value || temporaryPassword.value.length < 12) {
    return window.$message?.warning('临时密码至少 12 位');
  }
  saving.value = true;
  try {
    await resetUserPassword(passwordUser.value.id, temporaryPassword.value);
    window.$message?.success('临时密码已重置，用户旧会话已失效');
    closePasswordModal();
    await loadUsers();
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

function closePasswordModal() {
  passwordModalVisible.value = false;
  temporaryPassword.value = '';
}

function can(permission: string) {
  return hasPermission(authStore.userInfo.buttons, permission);
}

function statusLabel(status: UserStatus) {
  return { ENABLED: '启用', DISABLED: '停用', LOCKED: '锁定' }[status];
}

function formatTime(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '从未登录';
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
  userForm,
  selectedRoleIds,
  temporaryPassword,
  openCreate,
  openEdit,
  saveUser,
  openRoles,
  saveRoles,
  openPasswordReset,
  savePasswordReset
});
</script>

<template>
  <main class="access-page">
    <header class="page-heading">
      <div>
        <span>SYSTEM ACCESS / USERS</span>
        <h1>用户管理</h1>
        <p>维护矿区管理端账号、启停状态和角色分配。用户密码不会在页面回显。</p>
      </div>
      <NButton v-if="can('system:user:create')" type="primary" data-action="create-user" @click="openCreate">
        创建用户
      </NButton>
    </header>

    <section class="filter-panel">
      <NInput v-model:value="query.keyword" clearable placeholder="搜索登录名或显示名称" @keyup.enter="search" />
      <NSelect v-model:value="query.status" clearable :options="statusOptions" placeholder="全部状态" />
      <NButton type="primary" @click="search">查询</NButton>
      <NButton @click="resetQuery">重置</NButton>
    </section>

    <section class="table-panel">
      <NDataTable
        remote
        :columns="columns"
        :data="users"
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
            loadUsers();
          }
        "
        @update:page-size="
          size => {
            query.size = size;
            query.page = 1;
            loadUsers();
          }
        "
      />
    </section>

    <NModal
      v-model:show="userModalVisible"
      preset="card"
      :title="editingUserId ? '编辑用户' : '创建用户'"
      class="access-modal"
    >
      <div class="form-grid">
        <label>
          登录名
          <NInput v-model:value="userForm.username" :disabled="Boolean(editingUserId)" />
        </label>
        <label>
          显示名称
          <NInput v-model:value="userForm.displayName" maxlength="128" />
        </label>
        <label>
          状态
          <NSelect v-model:value="userForm.status" :options="editableStatusOptions" />
        </label>
        <label v-if="!editingUserId">
          临时密码
          <NInput v-model:value="userForm.temporaryPassword" type="password" show-password-on="click" />
        </label>
      </div>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="closeUserModal">取消</NButton>
          <NButton type="primary" :loading="saving" data-action="save-user" @click="saveUser">保存</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal v-model:show="roleModalVisible" preset="card" title="分配用户角色" class="access-modal">
      <p class="modal-tip">正在为 {{ roleUser?.displayName }} 分配角色，保存后下一次权限校验立即生效。</p>
      <NSelect v-model:value="selectedRoleIds" multiple filterable :options="roleOptions" placeholder="请选择角色" />
      <template #footer>
        <NSpace justify="end">
          <NButton @click="roleModalVisible = false">取消</NButton>
          <NButton type="primary" :loading="saving" @click="saveRoles">保存角色</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal v-model:show="passwordModalVisible" preset="card" title="重置临时密码" class="access-modal">
      <p class="modal-tip">重置后 {{ passwordUser?.displayName }} 的旧会话立即失效，下次登录必须修改密码。</p>
      <NInput
        v-model:value="temporaryPassword"
        type="password"
        show-password-on="click"
        placeholder="输入 12～72 位临时密码"
      />
      <template #footer>
        <NSpace justify="end">
          <NButton @click="closePasswordModal">取消</NButton>
          <NButton type="warning" :loading="saving" data-action="reset-password" @click="savePasswordReset">
            确认重置
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
  width: min(620px, calc(100vw - 32px));
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
}
</style>
