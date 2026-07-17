<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  NAlert,
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
  NText
} from 'naive-ui';
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

const { t, locale } = useI18n();
const authStore = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const users = ref<ManagedUser[]>([]);
const total = ref(0);
const query = reactive<{ keyword: string; status: UserStatus | null; page: number; size: number }>({
  keyword: '',
  status: null,
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

const statusOptions = computed<SelectOption[]>(() => [
  { label: t('mine.common.status.enabled'), value: 'ENABLED' },
  { label: t('mine.common.status.disabled'), value: 'DISABLED' },
  { label: t('mine.common.status.locked'), value: 'LOCKED' }
]);
const editableStatusOptions = computed(() => statusOptions.value.slice(0, 2));
const columns = computed<DataTableColumns<ManagedUser>>(() => [
  { title: t('mine.users.column.username'), key: 'username', minWidth: 150 },
  { title: t('mine.users.column.displayName'), key: 'displayName', minWidth: 150 },
  {
    title: t('mine.users.column.status'),
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
    title: t('mine.users.column.passwordChange'),
    key: 'passwordChangeRequired',
    width: 120,
    render: row =>
      row.passwordChangeRequired ? t('mine.users.passwordChange.pending') : t('mine.users.passwordChange.completed')
  },
  {
    title: t('mine.users.column.lastLogin'),
    key: 'lastLoginAt',
    minWidth: 180,
    render: row => formatTime(row.lastLoginAt)
  },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 260,
    render: row =>
      h(NSpace, { size: 8 }, () => [
        can('system:user:update')
          ? h(NButton, { text: true, type: 'primary', onClick: () => openEdit(row) }, () => t('mine.common.edit'))
          : null,
        can('system:user:assign-role')
          ? h(NButton, { text: true, onClick: () => openRoles(row) }, () => t('mine.users.action.assignRole'))
          : null,
        can('system:user:reset-password')
          ? h(NButton, { text: true, type: 'warning', onClick: () => openPasswordReset(row) }, () =>
              t('mine.users.action.resetPassword')
            )
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
  Object.assign(query, { keyword: '', status: null, page: 1 });
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
  if (!userForm.displayName.trim()) return window.$message?.warning(t('mine.users.validation.displayName'));
  if (!editingUserId.value && (!userForm.username.trim() || userForm.temporaryPassword.length < 12)) {
    return window.$message?.warning(t('mine.users.validation.createRequired'));
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
    window.$message?.success(t(editingUserId.value ? 'mine.users.message.updated' : 'mine.users.message.created'));
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
    roleOptions.value = response.items.map(role => ({ label: `${role.name} (${role.code})`, value: role.id }));
  } catch (error) {
    showError(error);
  }
}

async function saveRoles() {
  if (!roleUser.value) return;
  saving.value = true;
  try {
    await replaceUserRoles(roleUser.value.id, selectedRoleIds.value);
    window.$message?.success(t('mine.users.message.rolesUpdated'));
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
    return window.$message?.warning(t('mine.users.validation.temporaryPassword'));
  }
  saving.value = true;
  try {
    await resetUserPassword(passwordUser.value.id, temporaryPassword.value);
    window.$message?.success(t('mine.users.message.passwordReset'));
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
  return t(`mine.common.status.${status.toLowerCase()}`);
}

function formatTime(value: string | null) {
  return value ? new Date(value).toLocaleString(locale.value, { hour12: false }) : t('mine.users.neverLoggedIn');
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
  <NSpace vertical :size="16">
    <NPageHeader :title="t('mine.users.title')" :subtitle="t('mine.users.subtitle')">
      <template #extra>
        <NButton v-if="can('system:user:create')" type="primary" data-action="create-user" @click="openCreate">
          {{ t('mine.users.action.create') }}
        </NButton>
      </template>
    </NPageHeader>

    <NCard size="small">
      <NFlex align="center">
        <NInput
          v-model:value="query.keyword"
          clearable
          :placeholder="t('mine.users.search')"
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
        <NSpace data-filter-actions>
          <NButton type="primary" @click="search">{{ t('mine.common.search') }}</NButton>
          <NButton @click="resetQuery">{{ t('mine.common.reset') }}</NButton>
        </NSpace>
      </NFlex>
    </NCard>

    <NCard size="small">
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
    </NCard>

    <NModal
      v-model:show="userModalVisible"
      preset="card"
      :title="t(editingUserId ? 'mine.users.modal.edit' : 'mine.users.modal.create')"
      class="w-full max-w-640px"
    >
      <NForm label-placement="top">
        <NGrid responsive="screen" cols="1 s:2" :x-gap="16">
          <NGi>
            <NFormItem :label="t('mine.users.field.username')">
              <NInput
                v-model:value="userForm.username"
                :disabled="Boolean(editingUserId)"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.users.field.username') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.users.field.displayName')">
              <NInput
                v-model:value="userForm.displayName"
                maxlength="128"
                :placeholder="t('mine.common.enterPlaceholder', { field: t('mine.users.field.displayName') })"
              />
            </NFormItem>
          </NGi>
          <NGi>
            <NFormItem :label="t('mine.users.field.status')">
              <NSelect
                v-model:value="userForm.status"
                :options="editableStatusOptions"
                :placeholder="t('mine.common.selectPlaceholder', { field: t('mine.users.field.status') })"
              />
            </NFormItem>
          </NGi>
          <NGi v-if="!editingUserId">
            <NFormItem :label="t('mine.users.field.temporaryPassword')">
              <NInput
                v-model:value="userForm.temporaryPassword"
                type="password"
                show-password-on="click"
                :placeholder="t('mine.users.placeholder.temporaryPassword')"
              />
            </NFormItem>
          </NGi>
        </NGrid>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="closeUserModal">{{ t('mine.common.cancel') }}</NButton>
          <NButton type="primary" :loading="saving" data-action="save-user" @click="saveUser">
            {{ t('mine.common.save') }}
          </NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="roleModalVisible"
      preset="card"
      :title="t('mine.users.modal.assignRole')"
      class="w-full max-w-640px"
    >
      <NSpace vertical :size="16">
        <NText depth="3">{{ t('mine.users.tip.assignRole', { name: roleUser?.displayName || '' }) }}</NText>
        <NSelect
          v-model:value="selectedRoleIds"
          multiple
          filterable
          :options="roleOptions"
          :placeholder="t('mine.users.placeholder.role')"
        />
      </NSpace>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="roleModalVisible = false">{{ t('mine.common.cancel') }}</NButton>
          <NButton type="primary" :loading="saving" @click="saveRoles">{{ t('mine.users.action.saveRole') }}</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="passwordModalVisible"
      preset="card"
      :title="t('mine.users.modal.resetPassword')"
      class="w-full max-w-640px"
    >
      <NSpace vertical :size="16">
        <NAlert type="warning">
          {{ t('mine.users.tip.resetPassword', { name: passwordUser?.displayName || '' }) }}
        </NAlert>
        <NInput
          v-model:value="temporaryPassword"
          type="password"
          show-password-on="click"
          :placeholder="t('mine.users.placeholder.temporaryPassword')"
        />
      </NSpace>
      <template #footer>
        <NSpace justify="end">
          <NButton @click="closePasswordModal">{{ t('mine.common.cancel') }}</NButton>
          <NButton type="warning" :loading="saving" data-action="reset-password" @click="savePasswordReset">
            {{ t('mine.users.action.confirmReset') }}
          </NButton>
        </NSpace>
      </template>
    </NModal>
  </NSpace>
</template>
