import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { i18n, setLocale } from '@/locales';
import StrategyWorkbenchPage from './StrategyWorkbenchPage.vue';

const deviceApi = vi.hoisted(() => ({ getDevices: vi.fn(), getProducts: vi.fn() }));
const strategyApi = vi.hoisted(() => ({
  archiveDeviceGroup: vi.fn(),
  createDeviceGroup: vi.fn(),
  createStrategyDraft: vi.fn(),
  getEffectiveStrategy: vi.fn(),
  listDeviceGroupMembers: vi.fn(),
  listDeviceGroups: vi.fn(),
  listStrategyReleases: vi.fn(),
  listStrategyVersions: vi.fn(),
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

describe('StrategyWorkbenchPage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    vi.clearAllMocks();
    deviceApi.getProducts.mockResolvedValue({ items: [product], page: 1, size: 100, total: 1 });
    deviceApi.getDevices.mockResolvedValue({ items: [device], page: 1, size: 200, total: 1 });
    strategyApi.listDeviceGroups.mockResolvedValue({ items: [group], page: 1, size: 100, total: 1 });
    strategyApi.listDeviceGroupMembers.mockResolvedValue([{ ...device, effectiveFrom: '2026-07-16T01:00:00Z' }]);
    strategyApi.listStrategyVersions.mockResolvedValue([draft]);
    strategyApi.listStrategyReleases.mockResolvedValue([release]);
    strategyApi.getEffectiveStrategy.mockResolvedValue(effective);
    strategyApi.publishStrategy.mockResolvedValue(release);
    strategyApi.createStrategyDraft.mockResolvedValue(draft);
    strategyApi.updateStrategyDraft.mockResolvedValue(draft);
    strategyApi.replaceDeviceGroupMembers.mockResolvedValue({
      groupId: 'group-1',
      addedDeviceIds: [],
      removedDeviceIds: [],
      memberCount: 1,
      version: 3
    });
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
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
          details: { groupIds: ['group-a', 'group-b'], field: 'offlineThresholdSeconds' }
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
});

function mountPage(permissions: string[]) {
  authState.userInfo.buttons = permissions;
  return mount(StrategyWorkbenchPage, { global: { plugins: [i18n], stubs: { teleport: true } } });
}
