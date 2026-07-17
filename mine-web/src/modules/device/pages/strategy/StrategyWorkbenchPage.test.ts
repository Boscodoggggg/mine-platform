import { flushPromises, mount } from '@vue/test-utils';
import { NInputNumber, NSelect } from 'naive-ui';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { i18n, setLocale } from '@/locales';
import AlarmRuleEditor from '@/modules/device/components/strategy/AlarmRuleEditor.vue';
import AggregationPolicyEditor from '@/modules/device/components/strategy/AggregationPolicyEditor.vue';
import RetentionPolicyEditor from '@/modules/device/components/strategy/RetentionPolicyEditor.vue';
import StrategyWorkbenchPage from './StrategyWorkbenchPage.vue';

const deviceApi = vi.hoisted(() => ({
  getDevices: vi.fn(),
  getProducts: vi.fn()
}));
const strategyApi = vi.hoisted(() => ({
  archiveDeviceGroup: vi.fn(),
  createDeviceGroup: vi.fn(),
  createStrategyDraft: vi.fn(),
  getEffectiveStrategy: vi.fn(),
  listDeviceGroupMembers: vi.fn(),
  listDeviceGroups: vi.fn(),
  listRetentionExecutions: vi.fn(),
  listStrategyReleases: vi.fn(),
  listStrategyVersions: vi.fn(),
  previewRetentionGroupChange: vi.fn(),
  previewRetentionImpact: vi.fn(),
  publishStrategy: vi.fn(),
  replaceDeviceGroupMembers: vi.fn(),
  rollbackStrategyRelease: vi.fn(),
  updateDeviceGroup: vi.fn(),
  updateStrategyDraft: vi.fn()
}));
const authState = vi.hoisted(() => ({ userInfo: { buttons: [] as string[] } }));

vi.mock('@/modules/device/api/device', () => deviceApi);
vi.mock('@/modules/device/api/strategy', () => strategyApi);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

const product = {
  id: 'product-1',
  productKey: 'haul-truck',
  name: '矿用运输车',
  deviceType: 'VEHICLE',
  status: 'ACTIVE' as const,
  archivedAt: null,
  createdAt: '2026-07-16T00:00:00Z',
  updatedAt: '2026-07-16T00:00:00Z',
  version: 0
};
const device = {
  id: 'device-1',
  productId: 'product-1',
  deviceCode: 'TRUCK-001',
  name: '北区运输车 01',
  status: 'ENABLED' as const,
  archivedAt: null,
  createdAt: '2026-07-16T00:00:00Z',
  updatedAt: '2026-07-16T00:00:00Z',
  version: 0
};
const group = {
  id: 'group-1',
  productId: 'product-1',
  groupKey: 'north-haul',
  name: '北区运输组',
  priority: 900,
  status: 'ACTIVE' as const,
  description: '北区运输设备',
  archivedAt: null,
  createdAt: '2026-07-16T00:00:00Z',
  updatedAt: '2026-07-16T00:00:00Z',
  version: 2,
  memberCount: 1
};
const draft = {
  id: 'version-3',
  productId: 'product-1',
  strategyType: 'ONLINE_STATUS' as const,
  versionNumber: 3,
  status: 'DRAFT' as const,
  values: { offlineThresholdSeconds: 180 },
  contentHash: null,
  description: '雨季运输基线',
  publishedAt: null,
  createdAt: '2026-07-16T00:00:00Z',
  updatedAt: '2026-07-16T00:00:00Z',
  version: 1
};
const qualityDraft = {
  ...draft,
  id: 'quality-version-2',
  strategyType: 'DATA_QUALITY' as const,
  versionNumber: 2,
  values: {
    staleAfterSeconds: 120,
    futureToleranceSeconds: 5,
    'required.engine.rpm': true,
    'minimum.engine.rpm': 500,
    'maximum.engine.rpm': 2500
  },
  description: '运输车数据可信基线'
};
const alarmDraft = {
  ...draft,
  id: 'alarm-version-4',
  strategyType: 'ALARM' as const,
  versionNumber: 4,
  values: {
    rules: [
      {
        key: 'engine-temperature-high',
        name: '发动机温度过高',
        sourceType: 'NUMERIC' as const,
        severity: 'CRITICAL' as const,
        triggerDurationSeconds: 10,
        recoverDurationSeconds: 30,
        cooldownSeconds: 300,
        enabled: true,
        propertyId: 'engine.temperature',
        operator: 'GT' as const,
        threshold: 110,
        recoverThreshold: 100
      },
      {
        key: 'any-required-missing',
        name: '任意必填属性缺失',
        sourceType: 'QUALITY' as const,
        severity: 'WARNING' as const,
        triggerDurationSeconds: 0,
        recoverDurationSeconds: 30,
        cooldownSeconds: 300,
        enabled: true,
        qualityRuleCode: 'REQUIRED_MISSING' as const
      }
    ]
  },
  description: '运输车关键温度告警'
};
const retentionDraft = {
  ...draft,
  id: 'retention-version-2',
  strategyType: 'RETENTION' as const,
  versionNumber: 2,
  values: {
    policy: {
      enabled: true,
      retentionClass: 'STANDARD' as const,
      rawTelemetryDays: 90,
      qualityEvidenceDays: 180
    }
  },
  description: '运输车标准保留基线'
};
const aggregationDraft = {
  ...draft,
  id: 'aggregation-version-2',
  strategyType: 'AGGREGATION' as const,
  versionNumber: 2,
  values: {
    policy: {
      enabled: true,
      correctionWindowSeconds: 86400,
      rules: [
        { propertyId: 'engine.rpm', bucketSeconds: 300 },
        { propertyId: 'payload', bucketSeconds: 3600 }
      ]
    }
  },
  description: '运输车长期数值聚合基线'
};
const release = {
  id: 'release-8',
  productId: 'product-1',
  strategyVersionId: 'version-2',
  strategyType: 'ONLINE_STATUS' as const,
  releaseSequence: 8,
  scopeType: 'GROUP' as const,
  scopeId: 'group-1',
  groupPriority: 900,
  action: 'PUBLISH' as const,
  rollbackOfReleaseId: null,
  contentHash: `sha256:${'b'.repeat(64)}`
};
const effective = {
  deviceId: 'device-1',
  productId: 'product-1',
  strategyType: 'ONLINE_STATUS' as const,
  valid: true,
  values: { offlineThresholdSeconds: 180 },
  sources: {
    offlineThresholdSeconds: {
      scopeType: 'GROUP' as const,
      scopeId: 'group-1',
      releaseId: 'release-8',
      groupPriority: 900
    }
  },
  conflicts: [],
  contentHash: release.contentHash
};
const qualityEffective = {
  ...effective,
  strategyType: 'DATA_QUALITY' as const,
  values: qualityDraft.values,
  sources: Object.fromEntries(
    Object.keys(qualityDraft.values).map(field => [field, effective.sources.offlineThresholdSeconds])
  )
};
const retentionEffective = {
  ...effective,
  strategyType: 'RETENTION' as const,
  values: retentionDraft.values,
  sources: { policy: effective.sources.offlineThresholdSeconds }
};
const aggregationEffective = {
  ...effective,
  strategyType: 'AGGREGATION' as const,
  values: aggregationDraft.values,
  sources: { policy: effective.sources.offlineThresholdSeconds }
};
const retentionPreview = {
  previewToken: '00000000-0000-0000-0000-000000000201',
  observedAt: '2026-07-17T02:30:00Z',
  expiresAt: '2026-07-17T02:45:00Z',
  targetDeviceCount: 1001,
  affectedDeviceCount: 1001,
  enabledDeviceCount: 1001,
  policy: retentionDraft.values.policy,
  rawTelemetry: {
    eligibleRows: 1_000_000,
    earliestEligibleAt: '2025-01-01T00:00:00Z',
    atLeast: true
  },
  qualityEvidence: {
    eligibleRows: 420,
    earliestEligibleAt: '2025-02-01T00:00:00Z',
    atLeast: false
  }
};
const retentionExecution = {
  executionId: '00000000-0000-0000-0000-000000000101',
  instanceId: 'telemetry-1',
  status: 'SUCCEEDED' as const,
  policyCount: 3,
  rawRowsDeleted: 800,
  qualityRowsDeleted: 120,
  batchCount: 4,
  startedAt: '2026-07-17T02:00:00Z',
  completedAt: '2026-07-17T02:00:03Z',
  lastError: null
};

describe('StrategyWorkbenchPage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    vi.clearAllMocks();
    deviceApi.getProducts.mockResolvedValue({
      items: [product],
      page: 1,
      size: 100,
      total: 1
    });
    deviceApi.getDevices.mockResolvedValue({
      items: [device],
      page: 1,
      size: 100,
      total: 1
    });
    strategyApi.listDeviceGroups.mockResolvedValue({
      items: [group],
      page: 1,
      size: 100,
      total: 1
    });
    strategyApi.listDeviceGroupMembers.mockResolvedValue([{ ...device, effectiveFrom: '2026-07-16T01:00:00Z' }]);
    strategyApi.listStrategyVersions.mockImplementation(({ strategyType }: { strategyType: string }) =>
      Promise.resolve(
        strategyType === 'DATA_QUALITY'
          ? [qualityDraft]
          : strategyType === 'ALARM'
            ? [alarmDraft]
            : strategyType === 'RETENTION'
              ? [retentionDraft]
              : strategyType === 'AGGREGATION'
                ? [aggregationDraft]
                : [draft]
      )
    );
    strategyApi.listStrategyReleases.mockImplementation(({ strategyType }: { strategyType: string }) =>
      Promise.resolve(strategyType === 'DATA_QUALITY' ? [] : [release])
    );
    strategyApi.getEffectiveStrategy.mockImplementation((_deviceId: string, strategyType: string) =>
      Promise.resolve(
        strategyType === 'DATA_QUALITY'
          ? qualityEffective
          : strategyType === 'RETENTION'
            ? retentionEffective
            : strategyType === 'AGGREGATION'
              ? aggregationEffective
              : effective
      )
    );
    strategyApi.previewRetentionImpact.mockResolvedValue(retentionPreview);
    strategyApi.previewRetentionGroupChange.mockResolvedValue({ required: true, preview: retentionPreview });
    strategyApi.listRetentionExecutions.mockResolvedValue([retentionExecution]);
    strategyApi.publishStrategy.mockResolvedValue(release);
    strategyApi.createStrategyDraft.mockResolvedValue(draft);
    strategyApi.updateStrategyDraft.mockResolvedValue(draft);
    strategyApi.updateDeviceGroup.mockResolvedValue({ ...group, priority: 800, version: 3 });
    strategyApi.archiveDeviceGroup.mockResolvedValue(undefined);
    strategyApi.replaceDeviceGroupMembers.mockResolvedValue({
      groupId: 'group-1',
      addedDeviceIds: [],
      removedDeviceIds: [],
      memberCount: 1,
      version: 3
    });
    window.$message = {
      success: vi.fn(),
      error: vi.fn(),
      warning: vi.fn()
    } as never;
    window.confirm = vi.fn(() => true);
  });

  it('应加载产品、设备组、设备、发布历史和最终有效值', async () => {
    const wrapper = mountPage(['device:group:view', 'device:strategy:view']);
    await flushPromises();

    expect(wrapper.text()).toContain('策略工作台');
    expect(wrapper.text()).toContain('矿用运输车');
    expect(wrapper.text()).toContain('北区运输组');
    expect(wrapper.text()).toContain('北区运输车 01');
    expect(wrapper.text()).toContain('180');
    expect(wrapper.text()).toContain('设备组覆盖');
    expect(strategyApi.getEffectiveStrategy).toHaveBeenCalledWith('device-1', 'ONLINE_STATUS');
  });

  it('只读人员不应看到设备组维护、草稿编辑、发布和回滚入口', async () => {
    const wrapper = mountPage(['device:group:view', 'device:strategy:view']);
    await flushPromises();

    expect(wrapper.find('[data-action="new-group"]').exists()).toBe(false);
    expect(wrapper.find('[data-action="save-draft"]').exists()).toBe(false);
    expect(wrapper.find('[data-action="publish-strategy"]').exists()).toBe(false);
    expect(wrapper.find('[data-action="rollback-strategy"]').exists()).toBe(false);
  });

  it('设备组成员变化应先展示保留影响再以凭证提交', async () => {
    const wrapper = mountPage([
      'device:group:view',
      'device:group:edit',
      'device:strategy:view',
      'device:strategy:publish'
    ]);
    await flushPromises();
    const memberSelector = wrapper
      .findAllComponents(NSelect)
      .find(component => component.attributes('data-field') === 'group-members');
    memberSelector?.vm.$emit('update:value', []);
    await flushPromises();

    await wrapper.get('[data-action="save-members"]').trigger('click');
    await flushPromises();

    expect(strategyApi.previewRetentionGroupChange).toHaveBeenCalledWith({
      groupId: 'group-1',
      changeType: 'MEMBERSHIP',
      groupVersion: 2,
      deviceIds: []
    });
    expect(strategyApi.replaceDeviceGroupMembers).not.toHaveBeenCalled();
    expect(wrapper.get('[data-group-change-preview="MEMBERSHIP"]').text()).toContain('1,001');

    await wrapper.get('[data-action="save-members"]').trigger('click');
    await flushPromises();

    expect(strategyApi.replaceDeviceGroupMembers).toHaveBeenCalledWith('group-1', {
      deviceIds: [],
      version: 2,
      retentionPreviewToken: retentionPreview.previewToken
    });
  });

  it('设备组优先级变化应先预览并以一次性凭证提交', async () => {
    const wrapper = mountPage([
      'device:group:view',
      'device:group:edit',
      'device:strategy:view',
      'device:strategy:publish'
    ]);
    await flushPromises();
    await wrapper.get('[data-action="edit-group"]').trigger('click');
    const priorityInput = wrapper
      .findAllComponents(NInputNumber)
      .find(component => component.attributes('data-field') === 'group-priority');
    expect(priorityInput?.props('max')).toBe(1000);
    priorityInput?.vm.$emit('update:value', 800);
    await flushPromises();

    await wrapper.get('[data-action="save-group"]').trigger('click');
    await flushPromises();
    expect(strategyApi.previewRetentionGroupChange).toHaveBeenCalledWith({
      groupId: 'group-1',
      changeType: 'PRIORITY',
      groupVersion: 2,
      nextPriority: 800
    });
    expect(strategyApi.updateDeviceGroup).not.toHaveBeenCalled();

    await wrapper.get('[data-action="save-group"]').trigger('click');
    await flushPromises();
    expect(strategyApi.updateDeviceGroup).toHaveBeenCalledWith(
      'group-1',
      expect.objectContaining({ priority: 800, retentionPreviewToken: retentionPreview.previewToken })
    );
  });

  it('归档有保留发布的设备组应先展示影响再确认提交', async () => {
    const wrapper = mountPage([
      'device:group:view',
      'device:group:edit',
      'device:strategy:view',
      'device:strategy:publish'
    ]);
    await flushPromises();

    await wrapper.get('[data-action="archive-group"]').trigger('click');
    await flushPromises();
    expect(strategyApi.previewRetentionGroupChange).toHaveBeenCalledWith({
      groupId: 'group-1',
      changeType: 'GROUP_ARCHIVE',
      groupVersion: 2
    });
    expect(window.confirm).not.toHaveBeenCalled();
    expect(strategyApi.archiveDeviceGroup).not.toHaveBeenCalled();

    await wrapper.get('[data-action="archive-group"]').trigger('click');
    await flushPromises();
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('不可恢复'));
    expect(strategyApi.archiveDeviceGroup).toHaveBeenCalledWith('group-1', 2, retentionPreview.previewToken);
  });

  it('应携带业务原因和稳定幂等键发布，并刷新历史与有效值', async () => {
    const wrapper = mountPage([
      'device:group:view',
      'device:group:edit',
      'device:strategy:view',
      'device:strategy:edit',
      'device:strategy:publish'
    ]);
    await flushPromises();

    await wrapper.get('[data-field="publish-reason"] textarea').setValue('雨季延长离线判定窗口');
    await wrapper.get('[data-action="publish-strategy"]').trigger('click');
    await flushPromises();

    expect(strategyApi.publishStrategy).toHaveBeenCalledWith(
      {
        strategyVersionId: 'version-3',
        scopeType: 'PRODUCT',
        scopeId: 'product-1',
        reason: '雨季延长离线判定窗口'
      },
      expect.any(String)
    );
    expect(strategyApi.listStrategyReleases).toHaveBeenCalledTimes(2);
    expect(strategyApi.getEffectiveStrategy).toHaveBeenCalledTimes(2);
    expect(window.$message?.success).toHaveBeenCalled();
  });

  it('发布冲突应展示错误码、链路编号和冲突设备组证据', async () => {
    strategyApi.publishStrategy.mockRejectedValue(
      new ApiError(
        {
          code: 'STRATEGY-409-001',
          message: '同优先级设备组配置冲突',
          traceId: 'trace-conflict-1',
          details: {
            groupIds: ['group-a', 'group-b'],
            field: 'offlineThresholdSeconds'
          }
        },
        409
      )
    );
    const wrapper = mountPage(['device:strategy:view', 'device:strategy:publish']);
    await flushPromises();

    await wrapper.get('[data-field="publish-reason"] textarea').setValue('发布冲突演练');
    await wrapper.get('[data-action="publish-strategy"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('策略存在冲突');
    expect(wrapper.text()).toContain('STRATEGY-409-001');
    expect(wrapper.text()).toContain('trace-conflict-1');
    expect(wrapper.text()).toContain('group-a');
    expect(wrapper.text()).toContain('group-b');
  });

  it('应切换到数据质量策略并编辑时间与属性规则', async () => {
    const wrapper = mountPage(['device:strategy:view', 'device:strategy:edit']);
    await flushPromises();

    const strategyTypeSelector = wrapper
      .findAllComponents(NSelect)
      .find(component => component.attributes('data-field') === 'strategy-type');
    expect(strategyTypeSelector).toBeDefined();
    strategyTypeSelector?.vm.$emit('update:value', 'DATA_QUALITY');
    await flushPromises();

    expect(strategyApi.listStrategyVersions).toHaveBeenLastCalledWith({
      productId: 'product-1',
      strategyType: 'DATA_QUALITY',
      limit: 50
    });
    expect(strategyApi.getEffectiveStrategy).toHaveBeenLastCalledWith('device-1', 'DATA_QUALITY');
    expect(wrapper.text()).toContain('数据质量策略');
    expect(wrapper.text()).toContain('陈旧阈值');
    expect(wrapper.text()).toContain('engine.rpm');

    await wrapper.get('[data-action="save-draft"]').trigger('click');
    await flushPromises();

    expect(strategyApi.updateStrategyDraft).toHaveBeenCalledWith('quality-version-2', {
      values: qualityDraft.values,
      description: '运输车数据可信基线',
      version: 1
    });
  });

  it('应切换到告警策略、展示规则并按完整契约保存草稿', async () => {
    const wrapper = mountPage(['device:strategy:view', 'device:strategy:edit']);
    await flushPromises();
    const selector = wrapper
      .findAllComponents(NSelect)
      .find(component => component.attributes('data-field') === 'strategy-type');

    selector?.vm.$emit('update:value', 'ALARM');
    await flushPromises();

    expect(wrapper.text()).toContain('告警策略');
    expect(wrapper.text()).toContain('发动机温度过高');
    await wrapper.get('[data-action="save-draft"]').trigger('click');
    await flushPromises();

    expect(strategyApi.updateStrategyDraft).toHaveBeenCalledWith('alarm-version-4', {
      values: alarmDraft.values,
      description: '运输车关键温度告警',
      version: 1
    });
  });

  it('告警阈值应允许十二位小数并在提交前拒绝十三位小数', async () => {
    const wrapper = mountPage(['device:strategy:view', 'device:strategy:edit']);
    await flushPromises();
    const selector = wrapper
      .findAllComponents(NSelect)
      .find(component => component.attributes('data-field') === 'strategy-type');
    selector?.vm.$emit('update:value', 'ALARM');
    await flushPromises();
    const editor = wrapper.getComponent(AlarmRuleEditor);
    const rules = alarmDraft.values.rules;

    editor.vm.$emit('update:modelValue', [{ ...rules[0], threshold: 1.123456789012, recoverThreshold: 1 }, rules[1]]);
    await wrapper.get('[data-action="save-draft"]').trigger('click');
    await flushPromises();
    expect(strategyApi.updateStrategyDraft).toHaveBeenCalled();

    strategyApi.updateStrategyDraft.mockClear();
    editor.vm.$emit('update:modelValue', [{ ...rules[0], threshold: 1.1234567890123, recoverThreshold: 1 }, rules[1]]);
    await wrapper.get('[data-action="save-draft"]').trigger('click');
    await flushPromises();
    expect(strategyApi.updateStrategyDraft).not.toHaveBeenCalled();
    expect(window.$message?.warning).toHaveBeenCalledWith(expect.stringContaining('12 位小数'));
  });

  it('应以原子 policy 保存保留草稿并展示数据库预览与最近执行事实', async () => {
    const wrapper = mountPage(['device:strategy:view', 'device:strategy:edit', 'device:strategy:publish']);
    await flushPromises();
    const selector = wrapper
      .findAllComponents(NSelect)
      .find(component => component.attributes('data-field') === 'strategy-type');

    selector?.vm.$emit('update:value', 'RETENTION');
    await flushPromises();

    expect(wrapper.findComponent(RetentionPolicyEditor).exists()).toBe(true);
    expect(strategyApi.listRetentionExecutions).toHaveBeenCalledWith(20);
    expect(wrapper.text()).toContain('800');
    await wrapper.get('[data-action="preview-retention"]').trigger('click');
    await flushPromises();

    expect(strategyApi.previewRetentionImpact).toHaveBeenCalledWith({
      productId: 'product-1',
      strategyVersionId: 'retention-version-2',
      scopeType: 'PRODUCT',
      scopeId: 'product-1'
    });
    expect(wrapper.text()).toContain('2026-07-17');
    expect(wrapper.text()).toContain('1,000,000+');
    expect(wrapper.text()).toContain('1,001');

    await wrapper.get('[data-action="save-draft"]').trigger('click');
    await flushPromises();
    expect(strategyApi.updateStrategyDraft).toHaveBeenCalledWith('retention-version-2', {
      values: { policy: retentionDraft.values.policy },
      description: '运输车标准保留基线',
      version: 1
    });
  });

  it('应切换到数值聚合策略并以固定规则集保存原子策略', async () => {
    const wrapper = mountPage(['device:strategy:view', 'device:strategy:edit']);
    await flushPromises();
    const selector = wrapper
      .findAllComponents(NSelect)
      .find(component => component.attributes('data-field') === 'strategy-type');

    selector?.vm.$emit('update:value', 'AGGREGATION');
    await flushPromises();

    expect(wrapper.findComponent(AggregationPolicyEditor).exists()).toBe(true);
    expect(wrapper.text()).toContain('长期聚合');
    expect(wrapper.text()).toContain('engine.rpm');
    await wrapper.get('[data-action="save-draft"]').trigger('click');
    await flushPromises();

    expect(strategyApi.updateStrategyDraft).toHaveBeenCalledWith('aggregation-version-2', {
      values: aggregationDraft.values,
      description: '运输车长期数值聚合基线',
      version: 1
    });
  });

  it('保留策略发布前应要求当前预览并确认统计时点与不可逆风险', async () => {
    const wrapper = mountPage(['device:strategy:view', 'device:strategy:publish']);
    await flushPromises();
    const selector = wrapper
      .findAllComponents(NSelect)
      .find(component => component.attributes('data-field') === 'strategy-type');
    selector?.vm.$emit('update:value', 'RETENTION');
    await flushPromises();
    await wrapper.get('[data-field="publish-reason"] textarea').setValue('按标准周期清理历史遥测');

    await wrapper.get('[data-action="publish-strategy"]').trigger('click');
    expect(strategyApi.publishStrategy).not.toHaveBeenCalled();
    expect(window.$message?.warning).toHaveBeenCalledWith(expect.stringContaining('影响预览'));

    await wrapper.get('[data-action="preview-retention"]').trigger('click');
    await flushPromises();
    await wrapper.get('[data-action="publish-strategy"]').trigger('click');
    await flushPromises();

    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('2026-07-17'));
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('不可恢复'));
    expect(strategyApi.publishStrategy).toHaveBeenCalledWith(
      expect.objectContaining({
        strategyVersionId: 'retention-version-2',
        retentionPreviewToken: retentionPreview.previewToken
      }),
      expect.any(String)
    );
  });

  it('保留草稿存在未保存修改时必须阻止预览和发布', async () => {
    const wrapper = mountPage(['device:strategy:view', 'device:strategy:edit', 'device:strategy:publish']);
    await flushPromises();
    const selector = wrapper
      .findAllComponents(NSelect)
      .find(component => component.attributes('data-field') === 'strategy-type');
    selector?.vm.$emit('update:value', 'RETENTION');
    await flushPromises();

    wrapper.getComponent(RetentionPolicyEditor).vm.$emit('update:modelValue', {
      ...retentionDraft.values.policy,
      rawTelemetryDays: 365,
      qualityEvidenceDays: 365
    });
    await wrapper.get('[data-action="preview-retention"]').trigger('click');
    await wrapper.get('[data-field="publish-reason"] textarea').setValue('发布未保存的保留周期');
    await wrapper.get('[data-action="publish-strategy"]').trigger('click');

    expect(strategyApi.previewRetentionImpact).not.toHaveBeenCalled();
    expect(strategyApi.publishStrategy).not.toHaveBeenCalled();
    expect(window.$message?.warning).toHaveBeenCalledWith(expect.stringContaining('先保存'));
  });

  it('数据保留回滚必须先展示历史版本影响再由第二次操作提交新凭证', async () => {
    const retentionRelease = {
      ...release,
      id: 'retention-release-8',
      strategyVersionId: 'retention-history-version-1',
      strategyType: 'RETENTION' as const,
      scopeType: 'PRODUCT' as const,
      scopeId: 'product-1',
      groupPriority: null
    };
    strategyApi.listStrategyReleases.mockImplementation(({ strategyType }: { strategyType: string }) =>
      Promise.resolve(strategyType === 'RETENTION' ? [retentionRelease] : [release])
    );
    const wrapper = mountPage(['device:strategy:view', 'device:strategy:publish']);
    await flushPromises();
    const selector = wrapper
      .findAllComponents(NSelect)
      .find(component => component.attributes('data-field') === 'strategy-type');
    selector?.vm.$emit('update:value', 'RETENTION');
    await flushPromises();
    await wrapper.get('[data-field="rollback-reason"] textarea').setValue('恢复历史保留周期');

    await wrapper.get('[data-action="rollback-strategy"]').trigger('click');
    await flushPromises();

    expect(strategyApi.previewRetentionImpact).toHaveBeenCalledWith({
      productId: 'product-1',
      strategyVersionId: 'retention-history-version-1',
      scopeType: 'PRODUCT',
      scopeId: 'product-1'
    });
    expect(window.confirm).not.toHaveBeenCalled();
    expect(strategyApi.rollbackStrategyRelease).not.toHaveBeenCalled();
    expect(wrapper.get('[data-preview-release="retention-release-8"]').text()).toContain('1,001');
    expect(wrapper.get('[data-preview-release="retention-release-8"]').text()).toContain('1,000,000+');

    await wrapper.get('[data-action="rollback-strategy"]').trigger('click');
    await flushPromises();

    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('不可恢复'));
    expect(strategyApi.rollbackStrategyRelease).toHaveBeenCalledWith(
      'retention-release-8',
      {
        reason: '恢复历史保留周期',
        retentionPreviewToken: retentionPreview.previewToken
      },
      expect.any(String)
    );
  });

  it('修改回滚原因后应清空已展示的保留预览并要求重新预览', async () => {
    const retentionRelease = {
      ...release,
      id: 'retention-release-8',
      strategyVersionId: 'retention-history-version-1',
      strategyType: 'RETENTION' as const,
      scopeType: 'PRODUCT' as const,
      scopeId: 'product-1',
      groupPriority: null
    };
    strategyApi.listStrategyReleases.mockImplementation(({ strategyType }: { strategyType: string }) =>
      Promise.resolve(strategyType === 'RETENTION' ? [retentionRelease] : [release])
    );
    const wrapper = mountPage(['device:strategy:view', 'device:strategy:publish']);
    await flushPromises();
    const selector = wrapper
      .findAllComponents(NSelect)
      .find(component => component.attributes('data-field') === 'strategy-type');
    selector?.vm.$emit('update:value', 'RETENTION');
    await flushPromises();
    await wrapper.get('[data-field="rollback-reason"] textarea').setValue('恢复历史保留周期');

    await wrapper.get('[data-action="rollback-strategy"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-preview-release="retention-release-8"]').exists()).toBe(true);

    await wrapper.get('[data-field="rollback-reason"] textarea').setValue('改用另一个回滚原因');
    await flushPromises();
    expect(wrapper.find('[data-preview-release="retention-release-8"]').exists()).toBe(false);

    await wrapper.get('[data-action="rollback-strategy"]').trigger('click');
    await flushPromises();
    expect(strategyApi.previewRetentionImpact).toHaveBeenCalledTimes(2);
    expect(strategyApi.rollbackStrategyRelease).not.toHaveBeenCalled();
  });

  it('专属入口应固定加载数据保留策略并隐藏通用策略类型选择器', async () => {
    const wrapper = mountPage(['device:strategy:view'], { fixedStrategyType: 'RETENTION' });
    await flushPromises();

    expect(strategyApi.listStrategyVersions).toHaveBeenLastCalledWith({
      productId: 'product-1',
      strategyType: 'RETENTION',
      limit: 50
    });
    expect(strategyApi.listRetentionExecutions).toHaveBeenCalledWith(20);
    expect(wrapper.find('[data-field="strategy-type"]').exists()).toBe(false);
    expect(wrapper.get('[data-page-title="retention-governance"]').text()).toBe('数据保留');
    expect(wrapper.findComponent(RetentionPolicyEditor).exists()).toBe(true);
  });

  it('专属入口没有活动产品时仍应加载全局执行事实', async () => {
    deviceApi.getProducts.mockResolvedValue({ items: [], page: 1, size: 100, total: 0 });

    const wrapper = mountPage(['device:strategy:view'], { fixedStrategyType: 'RETENTION' });
    await flushPromises();

    expect(strategyApi.listRetentionExecutions).toHaveBeenCalledWith(20);
    expect(strategyApi.listStrategyVersions).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('800');
  });

  it('专属入口产品查询失败时仍应加载全局执行事实', async () => {
    deviceApi.getProducts.mockRejectedValue(new Error('product service unavailable'));

    const wrapper = mountPage(['device:strategy:view'], { fixedStrategyType: 'RETENTION' });
    await flushPromises();

    expect(strategyApi.listRetentionExecutions).toHaveBeenCalledWith(20);
    expect(wrapper.text()).toContain('800');
  });
});

function mountPage(permissions: string[], props: { fixedStrategyType?: 'RETENTION' } = {}) {
  authState.userInfo.buttons = permissions;
  return mount(StrategyWorkbenchPage, {
    props,
    global: { plugins: [i18n], stubs: { teleport: true } }
  });
}
