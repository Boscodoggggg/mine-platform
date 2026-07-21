<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns, SelectOption } from 'naive-ui';
import {
  NAlert,
  NButton,
  NDataTable,
  NDrawer,
  NDrawerContent,
  NEmpty,
  NFlex,
  NForm,
  NFormItem,
  NGi,
  NGrid,
  NInput,
  NSelect,
  NSpace,
  NSwitch,
  NTag,
  NText
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import {
  createNotificationRecipient,
  createNotificationRecipientGroup,
  getNotificationRecipientGroups,
  getNotificationRecipients,
  revealNotificationRecipientTarget,
  updateNotificationRecipient,
  updateNotificationRecipientGroup
} from '@/modules/notification/api/notification';
import type {
  NotificationRecipient,
  NotificationRecipientGroup,
  NotificationRecipientGroupRequest,
  NotificationRecipientRequest
} from '@/modules/notification/api/notification';
import { useAuthStore } from '@/store/modules/auth';

interface GroupForm {
  id: string | null;
  groupCode: string;
  name: string;
  description: string;
  status: 'ENABLED' | 'DISABLED';
  version: number;
}

interface RecipientForm {
  id: string | null;
  recipientType: 'SYSTEM_USER' | 'EXTERNAL_CONTACT';
  userId: string;
  displayName: string;
  channelType: NotificationRecipientRequest['channelType'];
  target: string;
  status: 'ENABLED' | 'DISABLED';
  version: number;
}

const { t } = useI18n();
const authStore = useAuthStore();
const loading = ref(false);
const recipientLoading = ref(false);
const saving = ref(false);
const problem = ref<string | null>(null);
const groups = ref<NotificationRecipientGroup[]>([]);
const recipients = ref<NotificationRecipient[]>([]);
const selectedGroupId = ref<string | null>(null);
const groupDrawerVisible = ref(false);
const recipientDrawerVisible = ref(false);
const revealedTargets = ref<Record<string, string>>({});
const groupForm = reactive<GroupForm>(emptyGroupForm());
const recipientForm = reactive<RecipientForm>(emptyRecipientForm());

const canEdit = computed(() => can('notification:recipient:edit'));
const canReveal = computed(() => can('notification:recipient:secret:view'));
const selectedGroup = computed(() => groups.value.find(item => item.id === selectedGroupId.value) ?? null);
const channelOptions = computed<SelectOption[]>(() => [
  { label: t('mine.notification.channelType.email'), value: 'EMAIL' },
  { label: t('mine.notification.channelType.sms'), value: 'SMS' },
  { label: t('mine.notification.channelType.voice'), value: 'VOICE' },
  { label: t('mine.notification.channelType.enterpriseIm'), value: 'ENTERPRISE_IM' },
  { label: t('mine.notification.channelType.webhook'), value: 'WEBHOOK' }
]);
const recipientTypeOptions = computed<SelectOption[]>(() => [
  { label: t('mine.notification.recipient.type.external'), value: 'EXTERNAL_CONTACT' },
  { label: t('mine.notification.recipient.type.system'), value: 'SYSTEM_USER' }
]);
const groupColumns = computed<DataTableColumns<NotificationRecipientGroup>>(() => [
  {
    title: t('mine.notification.recipient.group'),
    key: 'name',
    minWidth: 220,
    render: row => h(NFlex, { vertical: true, size: 1 }, () => [
      h(NText, { strong: row.id === selectedGroupId.value }, () => row.name),
      h(NText, { depth: 3, code: true }, () => row.groupCode)
    ])
  },
  {
    title: t('mine.notification.column.status'),
    key: 'status',
    width: 100,
    render: row => statusTag(row.status)
  },
  {
    title: t('mine.notification.column.action'),
    key: 'actions',
    width: 150,
    render: row => h(NSpace, { size: 12 }, () => [
      h(NButton, { text: true, type: 'primary', onClick: () => selectGroup(row.id) }, () =>
        t('mine.notification.recipient.action.members')
      ),
      ...(canEdit.value
        ? [h(NButton, { text: true, onClick: () => openGroupEdit(row) }, () =>
            t('mine.notification.action.edit')
          )]
        : [])
    ])
  }
]);
const recipientColumns = computed<DataTableColumns<NotificationRecipient>>(() => [
  {
    title: t('mine.notification.recipient.member'),
    key: 'displayName',
    minWidth: 170,
    render: row => h(NFlex, { vertical: true, size: 1 }, () => [
      h(NText, { strong: true }, () => row.displayName),
      h(NText, { depth: 3 }, () => recipientTypeLabel(row.recipientType))
    ])
  },
  {
    title: t('mine.notification.recipient.channel'),
    key: 'channelType',
    width: 130,
    render: row => h(NTag, { bordered: false, type: 'info' }, () => channelTypeLabel(row.channelType))
  },
  {
    title: t('mine.notification.recipient.target'),
    key: 'targetMasked',
    minWidth: 220,
    render: row => h(NFlex, { vertical: true, size: 2 }, () => [
      h(NText, { code: true }, () => revealedTargets.value[row.id] ?? row.targetMasked),
      ...(canReveal.value
        ? [h(NButton, {
            text: true,
            type: 'warning',
            'data-action': 'reveal-recipient-target',
            onClick: () => revealTarget(row)
          }, () => revealedTargets.value[row.id]
            ? t('mine.notification.recipient.action.revealed')
            : t('mine.notification.recipient.action.reveal'))]
        : [])
    ])
  },
  {
    title: t('mine.notification.column.status'),
    key: 'status',
    width: 100,
    render: row => statusTag(row.status)
  },
  {
    title: t('mine.notification.column.action'),
    key: 'actions',
    width: 88,
    render: row => canEdit.value
      ? h(NButton, { text: true, type: 'primary', onClick: () => openRecipientEdit(row) }, () =>
          t('mine.notification.action.edit')
        )
      : h(NText, { depth: 3 }, () => t('mine.notification.readOnly'))
  }
]);

onMounted(loadGroups);

async function loadGroups() {
  loading.value = true;
  problem.value = null;
  try {
    groups.value = await getNotificationRecipientGroups();
    if (selectedGroupId.value && groups.value.some(item => item.id === selectedGroupId.value)) {
      await loadRecipients(selectedGroupId.value);
    } else if (groups.value[0]) {
      await selectGroup(groups.value[0].id);
    } else {
      selectedGroupId.value = null;
      recipients.value = [];
    }
  } catch (error) {
    problem.value = errorMessage(error);
  } finally {
    loading.value = false;
  }
}

async function selectGroup(groupId: string) {
  selectedGroupId.value = groupId;
  await loadRecipients(groupId);
}

async function loadRecipients(groupId: string) {
  recipientLoading.value = true;
  problem.value = null;
  revealedTargets.value = {};
  try {
    recipients.value = await getNotificationRecipients(groupId);
  } catch (error) {
    problem.value = errorMessage(error);
  } finally {
    recipientLoading.value = false;
  }
}

function openGroupCreate() {
  Object.assign(groupForm, emptyGroupForm());
  groupDrawerVisible.value = true;
}

function openGroupEdit(group: NotificationRecipientGroup) {
  Object.assign(groupForm, {
    id: group.id,
    groupCode: group.groupCode,
    name: group.name,
    description: group.description ?? '',
    status: group.status,
    version: group.version
  });
  groupDrawerVisible.value = true;
}

function openRecipientCreate() {
  if (!selectedGroup.value) return;
  Object.assign(recipientForm, emptyRecipientForm());
  recipientDrawerVisible.value = true;
}

function openRecipientEdit(recipient: NotificationRecipient) {
  Object.assign(recipientForm, {
    id: recipient.id,
    recipientType: recipient.recipientType,
    userId: recipient.userId ?? '',
    displayName: recipient.displayName,
    channelType: recipient.channelType,
    target: '',
    status: recipient.status,
    version: recipient.version
  });
  recipientDrawerVisible.value = true;
}

async function saveGroup() {
  if (!canEdit.value || saving.value || !groupForm.groupCode.trim() || !groupForm.name.trim()) {
    window.$message?.warning(t('mine.notification.recipient.message.groupRequired'));
    return;
  }
  const body: NotificationRecipientGroupRequest = {
    groupCode: groupForm.groupCode.trim(),
    name: groupForm.name.trim(),
    description: groupForm.description.trim() || null,
    status: groupForm.status,
    version: groupForm.version
  };
  saving.value = true;
  try {
    const saved = groupForm.id
      ? await updateNotificationRecipientGroup(groupForm.id, body)
      : await createNotificationRecipientGroup(body);
    selectedGroupId.value = saved.id;
    groupDrawerVisible.value = false;
    window.$message?.success(t('mine.notification.recipient.message.groupSaved'));
    await loadGroups();
  } catch (error) {
    window.$message?.error(errorMessage(error));
  } finally {
    saving.value = false;
  }
}

async function saveRecipient() {
  const group = selectedGroup.value;
  if (!canEdit.value || saving.value || !group || !recipientForm.displayName.trim()
      || (!recipientForm.id && !recipientForm.target.trim())) {
    window.$message?.warning(t('mine.notification.recipient.message.recipientRequired'));
    return;
  }
  const body: NotificationRecipientRequest = {
    recipientType: recipientForm.recipientType,
    userId: recipientForm.recipientType === 'SYSTEM_USER' ? recipientForm.userId.trim() || null : null,
    displayName: recipientForm.displayName.trim(),
    channelType: recipientForm.channelType,
    target: recipientForm.target.trim() || null,
    status: recipientForm.status,
    version: recipientForm.version
  };
  saving.value = true;
  try {
    if (recipientForm.id) {
      await updateNotificationRecipient(group.id, recipientForm.id, body);
    } else {
      await createNotificationRecipient(group.id, body);
    }
    recipientDrawerVisible.value = false;
    window.$message?.success(t('mine.notification.recipient.message.recipientSaved'));
    await loadRecipients(group.id);
  } catch (error) {
    window.$message?.error(errorMessage(error));
  } finally {
    saving.value = false;
  }
}

async function revealTarget(recipient: NotificationRecipient) {
  const group = selectedGroup.value;
  if (!group || !canReveal.value || revealedTargets.value[recipient.id]) return;
  try {
    const response = await revealNotificationRecipientTarget(group.id, recipient.id);
    if (response.version !== recipient.version) {
      throw new Error(t('mine.notification.recipient.message.targetVersionChanged'));
    }
    revealedTargets.value = { ...revealedTargets.value, [recipient.id]: response.target };
    window.$message?.warning(t('mine.notification.recipient.message.targetRevealed'));
  } catch (error) {
    window.$message?.error(errorMessage(error));
  }
}

function statusTag(status: 'ENABLED' | 'DISABLED') {
  return h(NTag, { bordered: false, type: status === 'ENABLED' ? 'success' : 'default' }, () =>
    status === 'ENABLED' ? t('mine.notification.status.enabled') : t('mine.notification.status.disabled')
  );
}

function recipientTypeLabel(type: NotificationRecipient['recipientType']) {
  return type === 'SYSTEM_USER'
    ? t('mine.notification.recipient.type.system')
    : t('mine.notification.recipient.type.external');
}

function channelTypeLabel(type: NotificationRecipient['channelType']) {
  const key = {
    EMAIL: 'email',
    SMS: 'sms',
    VOICE: 'voice',
    ENTERPRISE_IM: 'enterpriseIm',
    WEBHOOK: 'webhook'
  }[type] as 'email' | 'sms' | 'voice' | 'enterpriseIm' | 'webhook';
  return t(`mine.notification.channelType.${key}`);
}

function can(permission: string) {
  return hasPermission(authStore.userInfo.buttons, permission);
}

function errorMessage(error: unknown) {
  if (error instanceof ApiError) return error.message;
  return error instanceof Error && error.message ? error.message : t('mine.notification.message.loadFailed');
}

function emptyGroupForm(): GroupForm {
  return { id: null, groupCode: '', name: '', description: '', status: 'DISABLED', version: 0 };
}

function emptyRecipientForm(): RecipientForm {
  return {
    id: null,
    recipientType: 'EXTERNAL_CONTACT',
    userId: '',
    displayName: '',
    channelType: 'EMAIL',
    target: '',
    status: 'DISABLED',
    version: 0
  };
}
</script>

<template>
  <NFlex vertical :size="20">
    <NFlex justify="space-between" align="center" wrap>
      <div>
        <NText strong>{{ t('mine.notification.recipient.title') }}</NText>
        <div><NText depth="3">{{ t('mine.notification.recipient.subtitle') }}</NText></div>
      </div>
      <NSpace>
        <NButton :loading="loading" @click="loadGroups">{{ t('mine.notification.action.refresh') }}</NButton>
        <NButton v-if="canEdit" type="primary" data-action="create-recipient-group" @click="openGroupCreate">
          {{ t('mine.notification.recipient.action.createGroup') }}
        </NButton>
      </NSpace>
    </NFlex>

    <NAlert v-if="problem" type="error" :title="t('mine.notification.message.loadFailed')">{{ problem }}</NAlert>

    <NDataTable :columns="groupColumns" :data="groups" :loading="loading" :row-key="row => row.id">
      <template #empty>
        <NEmpty :description="t('mine.notification.recipient.emptyGroups')" />
      </template>
    </NDataTable>

    <NFlex justify="space-between" align="center" wrap>
      <div>
        <NText strong>{{ selectedGroup?.name ?? t('mine.notification.recipient.noGroupSelected') }}</NText>
        <div><NText depth="3">{{ t('mine.notification.recipient.memberHint') }}</NText></div>
      </div>
      <NButton v-if="canEdit && selectedGroup" type="primary" secondary @click="openRecipientCreate">
        {{ t('mine.notification.recipient.action.createRecipient') }}
      </NButton>
    </NFlex>

    <NDataTable
      :columns="recipientColumns"
      :data="recipients"
      :loading="recipientLoading"
      :row-key="row => row.id"
      :scroll-x="900"
    >
      <template #empty>
        <NEmpty :description="t('mine.notification.recipient.emptyRecipients')" />
      </template>
    </NDataTable>

    <NDrawer v-model:show="groupDrawerVisible" :width="520" placement="right">
      <NDrawerContent :title="groupForm.id ? t('mine.notification.recipient.drawer.editGroup') : t('mine.notification.recipient.drawer.createGroup')" closable>
        <NForm label-placement="top">
          <NFormItem :label="t('mine.notification.recipient.form.groupCode')" required>
            <NInput v-model:value="groupForm.groupCode" :disabled="Boolean(groupForm.id)" :maxlength="64" />
          </NFormItem>
          <NFormItem :label="t('mine.notification.recipient.form.groupName')" required>
            <NInput v-model:value="groupForm.name" :maxlength="128" />
          </NFormItem>
          <NFormItem :label="t('mine.notification.recipient.form.description')">
            <NInput v-model:value="groupForm.description" type="textarea" :maxlength="500" />
          </NFormItem>
          <NFormItem :label="t('mine.notification.form.enabled')">
            <NSwitch :value="groupForm.status === 'ENABLED'" @update:value="value => groupForm.status = value ? 'ENABLED' : 'DISABLED'" />
          </NFormItem>
        </NForm>
        <template #footer>
          <NSpace justify="end">
            <NButton @click="groupDrawerVisible = false">{{ t('mine.notification.action.cancel') }}</NButton>
            <NButton type="primary" :loading="saving" @click="saveGroup">{{ t('mine.notification.recipient.action.saveGroup') }}</NButton>
          </NSpace>
        </template>
      </NDrawerContent>
    </NDrawer>

    <NDrawer v-model:show="recipientDrawerVisible" :width="560" placement="right">
      <NDrawerContent :title="recipientForm.id ? t('mine.notification.recipient.drawer.editRecipient') : t('mine.notification.recipient.drawer.createRecipient')" closable>
        <NAlert v-if="recipientForm.id" type="info" class="mb-4">
          {{ t('mine.notification.recipient.preserveTarget') }}
        </NAlert>
        <NForm label-placement="top">
          <NGrid responsive="screen" cols="1 m:2" :x-gap="16">
            <NGi>
              <NFormItem :label="t('mine.notification.recipient.form.type')" required>
                <NSelect v-model:value="recipientForm.recipientType" :options="recipientTypeOptions" />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.notification.recipient.form.channel')" required>
                <NSelect v-model:value="recipientForm.channelType" :options="channelOptions" />
              </NFormItem>
            </NGi>
          </NGrid>
          <NFormItem :label="t('mine.notification.recipient.form.displayName')" required>
            <NInput v-model:value="recipientForm.displayName" :maxlength="128" />
          </NFormItem>
          <NFormItem v-if="recipientForm.recipientType === 'SYSTEM_USER'" :label="t('mine.notification.recipient.form.userId')" required>
            <NInput v-model:value="recipientForm.userId" :placeholder="t('mine.notification.recipient.form.userIdPlaceholder')" />
          </NFormItem>
          <NFormItem :label="t('mine.notification.recipient.form.target')" :required="!recipientForm.id">
            <NInput v-model:value="recipientForm.target" :maxlength="2048" :placeholder="recipientForm.id ? t('mine.notification.recipient.preserveTarget') : t('mine.notification.recipient.form.targetPlaceholder')" />
          </NFormItem>
          <NFormItem :label="t('mine.notification.form.enabled')">
            <NSwitch :value="recipientForm.status === 'ENABLED'" @update:value="value => recipientForm.status = value ? 'ENABLED' : 'DISABLED'" />
          </NFormItem>
        </NForm>
        <template #footer>
          <NSpace justify="end">
            <NButton @click="recipientDrawerVisible = false">{{ t('mine.notification.action.cancel') }}</NButton>
            <NButton type="primary" :loading="saving" @click="saveRecipient">{{ t('mine.notification.recipient.action.saveRecipient') }}</NButton>
          </NSpace>
        </template>
      </NDrawerContent>
    </NDrawer>
  </NFlex>
</template>
