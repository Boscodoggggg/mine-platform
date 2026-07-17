import { flushPromises, mount } from '@vue/test-utils';
import { NDatePicker } from 'naive-ui';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import AggregationGovernancePage from './AggregationGovernancePage.vue';

const deviceApi = vi.hoisted(() => ({ getDevices: vi.fn(), getProducts: vi.fn() }));
const aggregationApi = vi.hoisted(() => ({
  createAggregationRecomputation: vi.fn(),
  listAggregationExecutions: vi.fn(),
  listAggregationRecomputations: vi.fn()
}));
const authState = vi.hoisted(() => ({ userInfo: { buttons: [] as string[] } }));

vi.mock('@/modules/device/api/device', () => deviceApi);
vi.mock('@/modules/device/api/aggregation', () => aggregationApi);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

const product = {
  id: 'product-1',
  productKey: 'haul-truck',
  name: '矿用运输车',
  deviceType: 'VEHICLE',
  status: 'ACTIVE' as const,
  archivedAt: null,
  createdAt: '2026-07-01T00:00:00Z',
  updatedAt: '2026-07-01T00:00:00Z',
  version: 0
};
const device = {
  id: 'device-1',
  productId: product.id,
  deviceCode: 'TRUCK-001',
  name: '北区运输车 01',
  status: 'ENABLED' as const,
  archivedAt: null,
  createdAt: '2026-07-01T00:00:00Z',
  updatedAt: '2026-07-01T00:00:00Z',
  version: 0
};
const execution = {
  executionId: '00000000-0000-0000-0000-000000000101',
  instanceId: 'telemetry-1',
  status: 'PARTIAL' as const,
  policyCount: 3,
  ruleCount: 5,
  sourceWatermark: 100,
  bucketCount: 42,
  rollupWatermark: 88,
  lateRowsIgnored: 2,
  startedAt: '2026-07-17T02:00:00Z',
  completedAt: '2026-07-17T02:00:03Z',
  lastError: null
};
const recomputation = {
  requestId: '00000000-0000-0000-0000-000000000201',
  deviceId: device.id,
  propertyId: 'engine.rpm',
  bucket: 'PT5M',
  from: '2026-07-01T00:00:00Z',
  to: '2026-07-02T00:00:00Z',
  reason: '补算历史',
  idempotencyKey: 'request-1',
  status: 'PENDING' as const,
  requestedBy: '00000000-0000-0000-0000-000000000001',
  createdAt: '2026-07-17T03:00:00Z',
  completedAt: null,
  executionId: null,
  lastError: null
};

describe('AggregationGovernancePage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    vi.clearAllMocks();
    deviceApi.getProducts.mockResolvedValue({ items: [product], page: 1, size: 100, total: 1 });
    deviceApi.getDevices.mockResolvedValue({ items: [device], page: 1, size: 100, total: 1 });
    aggregationApi.listAggregationExecutions.mockResolvedValue([execution]);
    aggregationApi.listAggregationRecomputations.mockResolvedValue([recomputation]);
    aggregationApi.createAggregationRecomputation.mockResolvedValue(recomputation);
    window.$message = { success: vi.fn(), warning: vi.fn(), error: vi.fn() } as never;
    window.confirm = vi.fn(() => true);
  });

  it('应展示自动执行、迟到证据和显式重算状态且只读人员看不到提交入口', async () => {
    const wrapper = mountPage(['governance:aggregation:view']);
    await flushPromises();

    expect(wrapper.text()).toContain('数值聚合');
    expect(wrapper.text()).toContain('部分完成');
    expect(wrapper.text()).toContain('42');
    expect(wrapper.text()).toContain('2');
    expect(wrapper.text()).toContain('待执行');
    expect(wrapper.find('[data-action="submit-recomputation"]').exists()).toBe(false);
  });

  it('具备治理权限时应二次确认并使用稳定幂等键提交有界重算', async () => {
    const wrapper = mountPage(['governance:aggregation:view', 'governance:aggregation:execute']);
    await flushPromises();
    const range = wrapper.getComponent(NDatePicker);
    range.vm.$emit('update:value', [1782864000000, 1782950400000]);
    await wrapper.get('[data-field="aggregation-property"] input').setValue('engine.rpm');
    await wrapper.get('[data-field="aggregation-reason"] textarea').setValue('补算弱网恢复前历史');
    await wrapper.get('[data-action="submit-recomputation"]').trigger('click');
    await flushPromises();

    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('不会原地覆盖'));
    expect(aggregationApi.createAggregationRecomputation).toHaveBeenCalledWith(
      {
        deviceId: 'device-1',
        propertyId: 'engine.rpm',
        bucket: 'PT5M',
        from: '2026-07-01T00:00:00.000Z',
        to: '2026-07-02T00:00:00.000Z',
        reason: '补算弱网恢复前历史'
      },
      expect.stringContaining('aggregation-recompute-')
    );
  });
});

function mountPage(permissions: string[]) {
  authState.userInfo.buttons = permissions;
  return mount(AggregationGovernancePage, {
    global: { plugins: [i18n], stubs: { teleport: true } }
  });
}
