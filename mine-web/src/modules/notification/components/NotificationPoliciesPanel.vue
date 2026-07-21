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
  NDynamicTags,
  NEmpty,
  NFlex,
  NForm,
  NFormItem,
  NGi,
  NGrid,
  NInput,
  NInputNumber,
  NSelect,
  NSpace,
  NSwitch,
  NTag,
  NText
} from 'naive-ui';
import { ApiError } from '@/api/client';
import { hasPermission } from '@/app/permission/permission';
import {
  archiveNotificationPolicy,
  createNotificationPolicy,
  createNotificationPolicyDraft,
  getNotificationChannels,
  getNotificationPolicies,
  getNotificationRecipientGroups,
  getNotificationTemplates,
  publishNotificationPolicy,
  updateNotificationPolicyDraft
} from '@/modules/notification/api/notification';
import type {
  NotificationChannel,
  NotificationPolicy,
  NotificationPolicyRequest,
  NotificationRecipientGroup,
  NotificationTemplate
} from '@/modules/notification/api/notification';
import { useAuthStore } from '@/store/modules/auth';

type PolicyStep = NotificationPolicyRequest['steps'][number];

interface PolicyForm {
  id: string | null;
  policyCode: string;
  name: string;
  description: string;
  priority: number;
  mineIds: string[];
  ruleKeys: string[];
  severityFilter: NotificationPolicyRequest['severityFilter'];
  stopOnAck: boolean;
  notifyOnRecovery: boolean;
  steps: PolicyStep[];
  version: number;
}

const { t } = useI18n();
const authStore = useAuthStore();
const loading = ref(false);
const saving = ref(false);
const publishing = ref(false);
const problem = ref<string | null>(null);
const policies = ref<NotificationPolicy[]>([]);
const channels = ref<NotificationChannel[]>([]);
const groups = ref<NotificationRecipientGroup[]>([]);
const templates = ref<NotificationTemplate[]>([]);
const drawerVisible = ref(false);
const publishDrawerVisible = ref(false);
const publicationReason = ref('');
const publishingPolicy = ref<NotificationPolicy | null>(null);
const form = reactive<PolicyForm>(emptyForm());
const canEdit = computed(() => hasPermission(authStore.userInfo.buttons, 'notification:policy:edit'));
const canPublish = computed(() => hasPermission(authStore.userInfo.buttons, 'notification:policy:publish'));
const severityOptions = computed<SelectOption[]>(() => [
  { label: t('mine.notification.policy.severity.info'), value: 'INFO' },
  { label: t('mine.notification.policy.severity.warning'), value: 'WARNING' },
  { label: t('mine.notification.policy.severity.critical'), value: 'CRITICAL' }
]);
const groupOptions = computed<SelectOption[]>(() => groups.value.map(item => ({
  label: item.name,
  value: item.id,
  disabled: item.status !== 'ENABLED'
})));
const channelOptions = computed<SelectOption[]>(() => channels.value.map(item => ({
  label: `${item.name} · ${channelTypeLabel(item.channelType)}`,
  value: item.id,
  disabled: item.status !== 'ENABLED'
})));
const ticketActionOptions = computed<SelectOption[]>(() => [
  { label: t('mine.notification.policy.ticket.none'), value: 'NONE' },
  { label: t('mine.notification.policy.ticket.create'), value: 'CREATE' },
  { label: t('mine.notification.policy.ticket.assign'), value: 'ASSIGN' },
  { label: t('mine.notification.policy.ticket.escalate'), value: 'ESCALATE' }
]);
const columns = computed<DataTableColumns<NotificationPolicy>>(() => [
  {
    title: t('mine.notification.policy.column.policy'),
    key: 'name',
    minWidth: 230,
    render: row => h(NFlex, { vertical: true, size: 1 }, () => [
      h(NText, { strong: true }, () => row.name),
      h(NText, { depth: 3, code: true }, () => row.policyCode)
    ])
  },
  {
    title: t('mine.notification.policy.column.priority'),
    key: 'priority',
    width: 90
  },
  {
    title: t('mine.notification.policy.column.scope'),
    key: 'scope',
    minWidth: 170,
    render: row => h(NText, { depth: 2 }, () => scopeSummary(row))
  },
  {
    title: t('mine.notification.policy.column.version'),
    key: 'versionState',
    minWidth: 190,
    render: row => h(NSpace, { size: 6 }, () => [
      ...(row.published
        ? [h(NTag, { bordered: false, type: 'success' }, () =>
            `v${row.published?.versionNumber} · ${t('mine.notification.policy.state.published')}`
          )]
        : []),
      ...(row.draft
        ? [h(NTag, { bordered: false, type: 'warning' }, () =>
            `v${row.draft?.versionNumber} · ${t('mine.notification.policy.state.draft')}`
          )]
        : [])
    ])
  },
  {
    title: t('mine.notification.column.status'),
    key: 'status',
    width: 100,
    render: row => h(NTag, { bordered: false, type: row.status === 'ACTIVE' ? 'success' : 'default' }, () =>
      row.status === 'ACTIVE'
        ? t('mine.notification.policy.status.active')
        : t('mine.notification.policy.status.archived')
    )
  },
  {
    title: t('mine.notification.column.action'),
    key: 'actions',
    width: 260,
    fixed: 'right',
    render: row => h(NSpace, { size: 12 }, () => policyActions(row))
  }
]);

onMounted(loadPolicies);

async function loadPolicies() {
  loading.value = true;
  problem.value = null;
  try {
    const [policyList, channelList, groupList, templateList] = await Promise.all([
      getNotificationPolicies(),
      getNotificationChannels(),
      getNotificationRecipientGroups(),
      getNotificationTemplates()
    ]);
    policies.value = policyList;
    channels.value = channelList;
    groups.value = groupList;
    templates.value = templateList;
  } catch (error) {
    problem.value = errorMessage(error);
  } finally {
    loading.value = false;
  }
}

function policyActions(policy: NotificationPolicy) {
  if (policy.status !== 'ACTIVE') {
    return [h(NText, { depth: 3 }, () => t('mine.notification.policy.status.archived'))];
  }
  const actions = [];
  if (canEdit.value && policy.draft) {
    actions.push(h(NButton, { text: true, type: 'primary', onClick: () => openEdit(policy) }, () =>
      t('mine.notification.policy.action.editDraft')
    ));
  }
  if (canEdit.value && !policy.draft && policy.published) {
    actions.push(h(NButton, {
      text: true,
      type: 'primary',
      'data-action': 'derive-policy-draft',
      onClick: () => deriveDraft(policy)
    }, () => t('mine.notification.policy.action.deriveDraft')));
  }
  if (canPublish.value && policy.draft) {
    actions.push(h(NButton, { text: true, type: 'warning', onClick: () => openPublish(policy) }, () =>
      t('mine.notification.policy.action.publish')
    ));
  }
  if (canEdit.value) {
    actions.push(h(NButton, { text: true, onClick: () => confirmArchive(policy) }, () =>
      t('mine.notification.policy.action.archive')
    ));
  }
  return actions.length ? actions : [h(NText, { depth: 3 }, () => t('mine.notification.readOnly'))];
}

function openCreate() {
  Object.assign(form, emptyForm());
  drawerVisible.value = true;
}

function openEdit(policy: NotificationPolicy) {
  const draft = policy.draft;
  if (!draft) return;
  Object.assign(form, {
    id: policy.id,
    policyCode: policy.policyCode,
    name: policy.name,
    description: policy.description ?? '',
    priority: policy.priority,
    mineIds: [...draft.matchConditions.mineIds],
    ruleKeys: [...draft.matchConditions.ruleKeys],
    severityFilter: [...draft.severityFilter],
    stopOnAck: draft.stopOnAck,
    notifyOnRecovery: draft.notifyOnRecovery,
    steps: draft.steps.map(step => ({
      stepOrder: step.stepOrder,
      delaySeconds: step.delaySeconds,
      channelId: step.channelId,
      recipientGroupId: step.recipientGroupId,
      templateId: step.templateId,
      recoveryTemplateId: step.recoveryTemplateId,
      ticketAction: step.ticketAction,
      ticketPriority: step.ticketPriority
    })),
    version: policy.version
  });
  drawerVisible.value = true;
}

function addStep() {
  form.steps.push(emptyStep(form.steps.length));
}

function removeStep(index: number) {
  if (form.steps.length <= 1) return;
  form.steps.splice(index, 1);
  form.steps.forEach((step, stepOrder) => { step.stepOrder = stepOrder; });
}

function changeStepChannel(step: PolicyStep, channelId: string) {
  step.channelId = channelId;
  step.templateId = '';
  step.recoveryTemplateId = null;
}

function triggerTemplateOptions(step: PolicyStep) {
  return templateOptions(step.channelId, 'ALARM_RAISED');
}

function recoveryTemplateOptions(step: PolicyStep) {
  return templateOptions(step.channelId, 'ALARM_RECOVERED');
}

function templateOptions(channelId: string, eventType: NotificationTemplate['eventType']) {
  const channel = channels.value.find(item => item.id === channelId);
  if (!channel) return [];
  return templates.value
    .filter(item => item.channelType === channel.channelType && item.eventType === eventType)
    .map(item => ({ label: item.name, value: item.id, disabled: item.status !== 'ENABLED' }));
}

async function savePolicy() {
  if (!canEdit.value || saving.value || !form.policyCode.trim() || !form.name.trim() || !validSteps()) {
    window.$message?.warning(t('mine.notification.policy.message.required'));
    return;
  }
  const body: NotificationPolicyRequest = {
    policyCode: form.policyCode.trim(),
    name: form.name.trim(),
    description: form.description.trim() || null,
    priority: form.priority,
    matchConditions: {
      mineIds: form.mineIds.map(value => value.trim()).filter(Boolean),
      ruleKeys: form.ruleKeys.map(value => value.trim()).filter(Boolean)
    },
    severityFilter: [...form.severityFilter],
    stopOnAck: form.stopOnAck,
    notifyOnRecovery: form.notifyOnRecovery,
    steps: form.steps.map((step, stepOrder) => ({
      ...step,
      stepOrder,
      recoveryTemplateId: form.notifyOnRecovery ? step.recoveryTemplateId : null,
      ticketPriority: ['CREATE', 'ESCALATE'].includes(step.ticketAction) ? step.ticketPriority : null
    })),
    version: form.version
  };
  saving.value = true;
  try {
    if (form.id) {
      await updateNotificationPolicyDraft(form.id, body);
    } else {
      await createNotificationPolicy(body);
    }
    drawerVisible.value = false;
    window.$message?.success(t('mine.notification.policy.message.saved'));
    await loadPolicies();
  } catch (error) {
    window.$message?.error(errorMessage(error));
  } finally {
    saving.value = false;
  }
}

function validSteps() {
  return form.steps.length > 0 && form.severityFilter.length > 0 && form.steps.every((step, index) =>
    step.delaySeconds >= 0
    && (index === 0 || step.delaySeconds >= (form.steps[index - 1]?.delaySeconds ?? 0))
    && Boolean(step.channelId && step.recipientGroupId && step.templateId)
    && (!form.notifyOnRecovery || Boolean(step.recoveryTemplateId))
    && (!['CREATE', 'ESCALATE'].includes(step.ticketAction) || Boolean(step.ticketPriority))
  );
}

async function deriveDraft(policy: NotificationPolicy) {
  if (!canEdit.value || saving.value) return;
  saving.value = true;
  try {
    await createNotificationPolicyDraft(policy.id, { version: policy.version });
    window.$message?.success(t('mine.notification.policy.message.draftCreated'));
    await loadPolicies();
  } catch (error) {
    window.$message?.error(errorMessage(error));
  } finally {
    saving.value = false;
  }
}

function openPublish(policy: NotificationPolicy) {
  if (!policy.draft || !canPublish.value) return;
  publishingPolicy.value = policy;
  publicationReason.value = '';
  publishDrawerVisible.value = true;
}

async function confirmPublish() {
  const policy = publishingPolicy.value;
  if (!policy?.draft || !publicationReason.value.trim() || publishing.value) {
    window.$message?.warning(t('mine.notification.policy.message.publicationReasonRequired'));
    return;
  }
  publishing.value = true;
  try {
    await publishNotificationPolicy(policy.id, {
      versionId: policy.draft.id,
      publicationReason: publicationReason.value.trim(),
      version: policy.version
    });
    publishDrawerVisible.value = false;
    window.$message?.success(t('mine.notification.policy.message.published'));
    await loadPolicies();
  } catch (error) {
    window.$message?.error(errorMessage(error));
  } finally {
    publishing.value = false;
  }
}

function confirmArchive(policy: NotificationPolicy) {
  if (!canEdit.value) return;
  const execute = async () => {
    try {
      await archiveNotificationPolicy(policy.id, { version: policy.version });
      window.$message?.success(t('mine.notification.policy.message.archived'));
      await loadPolicies();
    } catch (error) {
      window.$message?.error(errorMessage(error));
    }
  };
  if (window.$dialog) {
    window.$dialog.warning({
      title: t('mine.notification.policy.archive.title'),
      content: t('mine.notification.policy.archive.content'),
      positiveText: t('mine.notification.policy.action.archive'),
      negativeText: t('mine.notification.action.cancel'),
      onPositiveClick: execute
    });
  }
}

function scopeSummary(policy: NotificationPolicy) {
  const version = policy.draft ?? policy.published;
  if (!version) return t('mine.notification.policy.scope.none');
  const mineCount = version.matchConditions.mineIds.length;
  const ruleCount = version.matchConditions.ruleKeys.length;
  if (!mineCount && !ruleCount) return t('mine.notification.policy.scope.all');
  return t('mine.notification.policy.scope.summary', { mineCount, ruleCount });
}

function channelTypeLabel(type: NotificationChannel['channelType']) {
  const key = {
    EMAIL: 'email', SMS: 'sms', VOICE: 'voice', ENTERPRISE_IM: 'enterpriseIm', WEBHOOK: 'webhook'
  }[type] as 'email' | 'sms' | 'voice' | 'enterpriseIm' | 'webhook';
  return t(`mine.notification.channelType.${key}`);
}

function errorMessage(error: unknown) {
  if (error instanceof ApiError) return error.message;
  return error instanceof Error && error.message ? error.message : t('mine.notification.message.loadFailed');
}

function emptyStep(stepOrder: number): PolicyStep {
  return {
    stepOrder,
    delaySeconds: stepOrder === 0 ? 0 : 300,
    channelId: '',
    recipientGroupId: '',
    templateId: '',
    recoveryTemplateId: null,
    ticketAction: 'NONE',
    ticketPriority: null
  };
}

function emptyForm(): PolicyForm {
  return {
    id: null,
    policyCode: '',
    name: '',
    description: '',
    priority: 500,
    mineIds: [],
    ruleKeys: [],
    severityFilter: ['CRITICAL'],
    stopOnAck: true,
    notifyOnRecovery: false,
    steps: [emptyStep(0)],
    version: 0
  };
}
</script>

<template>
  <NFlex vertical :size="20">
    <NFlex justify="space-between" align="center" wrap>
      <div>
        <NText strong>{{ t('mine.notification.policy.title') }}</NText>
        <div><NText depth="3">{{ t('mine.notification.policy.subtitle') }}</NText></div>
      </div>
      <NSpace>
        <NButton :loading="loading" @click="loadPolicies">{{ t('mine.notification.action.refresh') }}</NButton>
        <NButton v-if="canEdit" type="primary" data-action="create-policy" @click="openCreate">
          {{ t('mine.notification.policy.action.create') }}
        </NButton>
      </NSpace>
    </NFlex>

    <NAlert type="info" :title="t('mine.notification.policy.versionTitle')">
      {{ t('mine.notification.policy.versionHint') }}
    </NAlert>
    <NAlert v-if="problem" type="error" :title="t('mine.notification.message.loadFailed')">{{ problem }}</NAlert>

    <NDataTable :columns="columns" :data="policies" :loading="loading" :row-key="row => row.id" :scroll-x="1180">
      <template #empty>
        <NEmpty :description="t('mine.notification.policy.empty')" />
      </template>
    </NDataTable>

    <NDrawer v-model:show="drawerVisible" :width="860" placement="right">
      <NDrawerContent :title="form.id ? t('mine.notification.policy.drawer.edit') : t('mine.notification.policy.drawer.create')" closable>
        <NForm label-placement="top">
          <NGrid responsive="screen" cols="1 m:2" :x-gap="16">
            <NGi>
              <NFormItem :label="t('mine.notification.policy.form.code')" required>
                <NInput v-model:value="form.policyCode" :disabled="Boolean(form.id)" :maxlength="64" />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.notification.form.name')" required>
                <NInput v-model:value="form.name" :maxlength="128" />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.notification.policy.form.priority')" required>
                <NInputNumber v-model:value="form.priority" class="w-full" :min="0" :max="1000" />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.notification.policy.form.severity')" required>
                <NSelect v-model:value="form.severityFilter" multiple :options="severityOptions" />
              </NFormItem>
            </NGi>
          </NGrid>
          <NFormItem :label="t('mine.notification.policy.form.description')">
            <NInput v-model:value="form.description" type="textarea" :maxlength="500" />
          </NFormItem>
          <NGrid responsive="screen" cols="1 m:2" :x-gap="16">
            <NGi>
              <NFormItem :label="t('mine.notification.policy.form.mineIds')">
                <NDynamicTags v-model:value="form.mineIds" />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.notification.policy.form.ruleKeys')">
                <NDynamicTags v-model:value="form.ruleKeys" />
              </NFormItem>
            </NGi>
          </NGrid>
          <NGrid responsive="screen" cols="1 m:2" :x-gap="16">
            <NGi>
              <NFormItem :label="t('mine.notification.policy.form.stopOnAck')">
                <NSwitch v-model:value="form.stopOnAck" />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.notification.policy.form.notifyOnRecovery')">
                <NSwitch v-model:value="form.notifyOnRecovery" />
              </NFormItem>
            </NGi>
          </NGrid>

          <NFlex justify="space-between" align="center" class="mb-3">
            <NText strong>{{ t('mine.notification.policy.form.steps') }}</NText>
            <NButton secondary type="primary" :disabled="form.steps.length >= 20" @click="addStep">
              {{ t('mine.notification.policy.action.addStep') }}
            </NButton>
          </NFlex>
          <NFlex vertical :size="16">
            <div v-for="(step, index) in form.steps" :key="index" class="py-2">
              <NFlex justify="space-between" align="center" class="mb-3">
                <NText strong>{{ t('mine.notification.policy.step.title', { number: index + 1 }) }}</NText>
                <NButton text :disabled="form.steps.length <= 1" @click="removeStep(index)">
                  {{ t('mine.notification.policy.action.removeStep') }}
                </NButton>
              </NFlex>
              <NGrid responsive="screen" cols="1 m:2" :x-gap="16">
                <NGi>
                  <NFormItem :label="t('mine.notification.policy.step.delay')" required>
                    <NInputNumber v-model:value="step.delaySeconds" class="w-full" :min="0" :max="604800" />
                  </NFormItem>
                </NGi>
                <NGi>
                  <NFormItem :label="t('mine.notification.policy.step.channel')" required>
                    <NSelect :value="step.channelId" :options="channelOptions" @update:value="changeStepChannel(step, $event)" />
                  </NFormItem>
                </NGi>
                <NGi>
                  <NFormItem :label="t('mine.notification.policy.step.group')" required>
                    <NSelect v-model:value="step.recipientGroupId" :options="groupOptions" />
                  </NFormItem>
                </NGi>
                <NGi>
                  <NFormItem :label="t('mine.notification.policy.step.triggerTemplate')" required>
                    <NSelect v-model:value="step.templateId" :options="triggerTemplateOptions(step)" />
                  </NFormItem>
                </NGi>
                <NGi v-if="form.notifyOnRecovery">
                  <NFormItem :label="t('mine.notification.policy.step.recoveryTemplate')" required>
                    <NSelect v-model:value="step.recoveryTemplateId" :options="recoveryTemplateOptions(step)" />
                  </NFormItem>
                </NGi>
                <NGi>
                  <NFormItem :label="t('mine.notification.policy.step.ticketAction')">
                    <NSelect v-model:value="step.ticketAction" :options="ticketActionOptions" />
                  </NFormItem>
                </NGi>
                <NGi v-if="['CREATE', 'ESCALATE'].includes(step.ticketAction)">
                  <NFormItem :label="t('mine.notification.policy.step.ticketPriority')" required>
                    <NInputNumber v-model:value="step.ticketPriority" class="w-full" :min="1" :max="100" />
                  </NFormItem>
                </NGi>
              </NGrid>
            </div>
          </NFlex>
        </NForm>
        <template #footer>
          <NSpace justify="end">
            <NButton @click="drawerVisible = false">{{ t('mine.notification.action.cancel') }}</NButton>
            <NButton type="primary" :loading="saving" @click="savePolicy">{{ t('mine.notification.action.save') }}</NButton>
          </NSpace>
        </template>
      </NDrawerContent>
    </NDrawer>

    <NDrawer v-model:show="publishDrawerVisible" :width="520" placement="right">
      <NDrawerContent :title="t('mine.notification.policy.publish.title')" closable>
        <NAlert type="warning" :title="t('mine.notification.policy.publish.warningTitle')" class="mb-4">
          {{ t('mine.notification.policy.publish.warningContent') }}
        </NAlert>
        <NForm label-placement="top">
          <NFormItem :label="t('mine.notification.policy.publish.reason')" required>
            <NInput v-model:value="publicationReason" type="textarea" :maxlength="500" show-count />
          </NFormItem>
        </NForm>
        <template #footer>
          <NSpace justify="end">
            <NButton @click="publishDrawerVisible = false">{{ t('mine.notification.action.cancel') }}</NButton>
            <NButton type="warning" :loading="publishing" @click="confirmPublish">
              {{ t('mine.notification.policy.action.confirmPublish') }}
            </NButton>
          </NSpace>
        </template>
      </NDrawerContent>
    </NDrawer>
  </NFlex>
</template>
