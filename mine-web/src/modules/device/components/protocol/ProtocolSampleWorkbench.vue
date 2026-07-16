<script setup lang="ts">
import { computed, h, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import type { DataTableColumns } from 'naive-ui';
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NEmpty,
  NFlex,
  NForm,
  NFormItem,
  NGrid,
  NGi,
  NInput,
  NInputNumber,
  NSpace,
  NSwitch,
  NTag,
  NText
} from 'naive-ui';
import { ApiError } from '@/api/client';
import {
  getProtocolSamples,
  saveProtocolSamples,
  testProtocolSamples
} from '@/modules/device/api/device-model';
import type {
  ProtocolSampleInput,
  ProtocolTestRun
} from '@/modules/device/api/device-model';

const props = defineProps<{
  mappingVersionId: string;
  mappingVersion: number;
  editable: boolean;
  canEdit: boolean;
  canTest: boolean;
}>();
const emit = defineEmits<{
  saved: [];
  tested: [passed: boolean];
}>();

const { t } = useI18n();
const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
const dirty = ref(false);
const samples = ref<ProtocolSampleInput[]>([]);
const testRun = ref<ProtocolTestRun | null>(null);
const editorVisible = ref(false);
const editingIndex = ref<number | null>(null);
const editor = reactive(emptyEditor());

const columns = computed<DataTableColumns<ProtocolSampleInput>>(() => [
  {
    title: t('mine.models.samples.column.name'),
    key: 'name',
    minWidth: 180,
    render: row => h(NText, { strong: true }, () => row.name)
  },
  {
    title: t('mine.models.samples.column.expectation'),
    key: 'expectedSuccess',
    width: 120,
    render: row =>
      h(
        NTag,
        { type: row.expectedSuccess ? 'success' : 'warning', bordered: false, size: 'small' },
        () =>
          row.expectedSuccess
            ? t('mine.models.samples.expectation.success')
            : t('mine.models.samples.expectation.failure')
      )
  },
  {
    title: t('mine.models.samples.column.payload'),
    key: 'rawPayload',
    minWidth: 260,
    ellipsis: { tooltip: true },
    render: row => compactJson(row.rawPayload)
  },
  {
    title: t('mine.models.samples.column.expected'),
    key: 'expectedProperties',
    minWidth: 220,
    ellipsis: { tooltip: true },
    render: row => row.expectedSuccess ? compactJson(row.expectedProperties) : row.expectedErrorCode || t('mine.common.empty')
  },
  {
    title: t('mine.common.action'),
    key: 'actions',
    width: 140,
    render: (_row, index) =>
      props.editable && props.canEdit
        ? h(NSpace, { size: 4 }, () => [
            h(NButton, { text: true, type: 'primary', onClick: () => startEdit(index) }, () =>
              t('mine.common.edit')
            ),
            h(NButton, { text: true, type: 'error', onClick: () => removeSample(index) }, () =>
              t('mine.common.delete')
            )
          ])
        : null
  }
]);

const resultColumns = computed<DataTableColumns<ProtocolTestRun['results'][number]>>(() => [
  { title: t('mine.models.samples.column.name'), key: 'name', minWidth: 180 },
  {
    title: t('mine.models.samples.column.result'),
    key: 'passed',
    width: 120,
    render: row =>
      h(NTag, { type: row.passed ? 'success' : 'error', bordered: false, size: 'small' }, () =>
        row.passed ? t('mine.models.samples.result.passed') : t('mine.models.samples.result.failed')
      )
  },
  {
    title: t('mine.models.samples.column.errorCode'),
    key: 'actualErrorCode',
    minWidth: 180,
    render: row => row.actualErrorCode || t('mine.common.empty')
  }
]);

watch(
  () => [props.mappingVersionId, props.mappingVersion],
  () => loadSamples(),
  { immediate: true }
);

async function loadSamples() {
  loading.value = true;
  editorVisible.value = false;
  testRun.value = null;
  try {
    const response = await getProtocolSamples(props.mappingVersionId);
    samples.value = response.map(toInput);
    dirty.value = false;
  } catch (error) {
    showError(error);
  } finally {
    loading.value = false;
  }
}

function startCreate() {
  editingIndex.value = null;
  Object.assign(editor, emptyEditor(samples.value.length * 10 + 10));
  editorVisible.value = true;
}

function startEdit(index: number) {
  const sample = samples.value[index];
  if (!sample) return;
  editingIndex.value = index;
  Object.assign(editor, {
    name: sample.name,
    rawPayloadJson: prettyJson(sample.rawPayload),
    expectedSuccess: sample.expectedSuccess,
    expectedPropertiesJson: prettyJson(sample.expectedProperties),
    expectedErrorCode: sample.expectedErrorCode || '',
    sortOrder: sample.sortOrder
  });
  editorVisible.value = true;
}

function applySample() {
  const name = editor.name.trim();
  const rawPayload = parseObject(editor.rawPayloadJson);
  const expectedProperties = parseObject(editor.expectedPropertiesJson);
  const expectedErrorCode = editor.expectedErrorCode.trim();
  if (!name || !rawPayload || !expectedProperties || (!editor.expectedSuccess && !expectedErrorCode)) {
    window.$message?.warning(t('mine.models.samples.validation.invalid'));
    return;
  }
  const value: ProtocolSampleInput = {
    name,
    rawPayload,
    expectedSuccess: editor.expectedSuccess,
    expectedProperties,
    expectedErrorCode: editor.expectedSuccess ? null : expectedErrorCode,
    sortOrder: editor.sortOrder
  };
  if (editingIndex.value === null) samples.value.push(value);
  else samples.value.splice(editingIndex.value, 1, value);
  samples.value.sort((left, right) => left.sortOrder - right.sortOrder);
  dirty.value = true;
  testRun.value = null;
  editorVisible.value = false;
}

function removeSample(index: number) {
  samples.value.splice(index, 1);
  dirty.value = true;
  testRun.value = null;
}

async function persistSamples() {
  if (!samples.value.length) {
    window.$message?.warning(t('mine.models.samples.validation.empty'));
    return;
  }
  saving.value = true;
  try {
    const response = await saveProtocolSamples(
      props.mappingVersionId,
      props.mappingVersion,
      samples.value
    );
    samples.value = response.map(toInput);
    dirty.value = false;
    window.$message?.success(t('mine.models.samples.message.saved'));
    emit('saved');
  } catch (error) {
    showError(error);
  } finally {
    saving.value = false;
  }
}

async function runTest() {
  if (dirty.value) {
    window.$message?.warning(t('mine.models.samples.validation.saveBeforeTest'));
    return;
  }
  testing.value = true;
  try {
    testRun.value = await testProtocolSamples(props.mappingVersionId);
    emit('tested', testRun.value.passed);
  } catch (error) {
    showError(error);
  } finally {
    testing.value = false;
  }
}

function toInput(sample: {
  name: string;
  rawPayload: Record<string, unknown>;
  expectedSuccess: boolean;
  expectedProperties: Record<string, unknown>;
  expectedErrorCode?: string | null;
  sortOrder: number;
}): ProtocolSampleInput {
  return {
    name: sample.name,
    rawPayload: structuredClone(sample.rawPayload),
    expectedSuccess: sample.expectedSuccess,
    expectedProperties: structuredClone(sample.expectedProperties),
    expectedErrorCode: sample.expectedErrorCode || null,
    sortOrder: sample.sortOrder
  };
}

function emptyEditor(sortOrder = 10) {
  return {
    name: '',
    rawPayloadJson: '{\n  \n}',
    expectedSuccess: true,
    expectedPropertiesJson: '{\n  \n}',
    expectedErrorCode: '',
    sortOrder
  };
}

function parseObject(value: string): Record<string, unknown> | null {
  try {
    const parsed = JSON.parse(value) as unknown;
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

function prettyJson(value: Record<string, unknown>) {
  return JSON.stringify(value, null, 2);
}

function compactJson(value: Record<string, unknown>) {
  return JSON.stringify(value);
}

function showError(error: unknown) {
  if (error instanceof ApiError) return window.$message?.error(error.message);
  window.$message?.error(t('mine.common.operationFailed'));
}
</script>

<template>
  <NCard data-testid="protocol-sample-workbench" :bordered="false">
    <template #header>
      <NSpace vertical :size="2">
        <NText strong>{{ t('mine.models.samples.title') }}</NText>
        <NText depth="3">{{ t('mine.models.samples.subtitle') }}</NText>
      </NSpace>
    </template>
    <template #header-extra>
      <NSpace>
        <NButton
          v-if="editable && canEdit"
          data-action="add-sample"
          secondary
          type="primary"
          @click="startCreate"
        >
          {{ t('mine.models.samples.action.add') }}
        </NButton>
        <NButton
          v-if="editable && canEdit"
          data-action="save-samples"
          :disabled="!dirty"
          :loading="saving"
          @click="persistSamples"
        >
          {{ t('mine.models.samples.action.save') }}
        </NButton>
        <NButton
          v-if="canTest"
          data-action="run-samples"
          type="primary"
          :loading="testing"
          @click="runTest"
        >
          {{ t('mine.models.samples.action.run') }}
        </NButton>
      </NSpace>
    </template>

    <NSpace vertical :size="16">
      <NAlert v-if="dirty" type="warning" :bordered="false">
        {{ t('mine.models.samples.unsaved') }}
      </NAlert>

      <NDataTable
        v-if="samples.length || loading"
        :columns="columns"
        :data="samples"
        :loading="loading"
        :scroll-x="940"
        :single-line="false"
        size="small"
      />
      <NEmpty v-else :description="t('mine.models.samples.empty')" />

      <NCard v-if="editorVisible" data-testid="sample-editor" embedded :bordered="false">
        <template #header>{{ t('mine.models.samples.editorTitle') }}</template>
        <NForm label-placement="top">
          <NGrid responsive="screen" cols="1 m:2" :x-gap="16">
            <NGi>
              <NFormItem :label="t('mine.models.samples.field.name')">
                <NInput v-model:value="editor.name" data-field="sample-name" />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.models.samples.field.sortOrder')">
                <NInputNumber v-model:value="editor.sortOrder" class="w-full" :min="0" />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.models.samples.field.rawPayload')">
                <NInput
                  v-model:value="editor.rawPayloadJson"
                  data-field="raw-payload"
                  type="textarea"
                  :autosize="{ minRows: 8, maxRows: 16 }"
                />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.models.samples.field.expectedProperties')">
                <NInput
                  v-model:value="editor.expectedPropertiesJson"
                  data-field="expected-properties"
                  type="textarea"
                  :autosize="{ minRows: 8, maxRows: 16 }"
                />
              </NFormItem>
            </NGi>
            <NGi>
              <NFormItem :label="t('mine.models.samples.field.expectedSuccess')">
                <NSwitch v-model:value="editor.expectedSuccess" />
              </NFormItem>
            </NGi>
            <NGi v-if="!editor.expectedSuccess">
              <NFormItem :label="t('mine.models.samples.field.expectedErrorCode')">
                <NInput v-model:value="editor.expectedErrorCode" />
              </NFormItem>
            </NGi>
          </NGrid>
        </NForm>
        <NFlex justify="end">
          <NButton @click="editorVisible = false">{{ t('mine.common.cancel') }}</NButton>
          <NButton data-action="apply-sample" type="primary" @click="applySample">
            {{ t('mine.models.samples.action.apply') }}
          </NButton>
        </NFlex>
      </NCard>

      <template v-if="testRun">
        <NAlert :type="testRun.passed ? 'success' : 'error'" :bordered="false">
          <NFlex justify="space-between" align="center">
            <NText strong>
              {{
                testRun.passed
                  ? t('mine.models.samples.gate.passed')
                  : t('mine.models.samples.gate.failed')
              }}
            </NText>
            <NTag :type="testRun.passed ? 'success' : 'error'" :bordered="false">
              {{ t('mine.models.samples.gate.summary', { passed: testRun.passedSamples, total: testRun.totalSamples }) }}
            </NTag>
          </NFlex>
        </NAlert>
        <NDataTable :columns="resultColumns" :data="testRun.results" :single-line="false" size="small" />
      </template>
    </NSpace>
  </NCard>
</template>
