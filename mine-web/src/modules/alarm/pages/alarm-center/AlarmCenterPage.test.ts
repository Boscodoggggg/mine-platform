import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import { i18n, setLocale } from '@/locales';
import AlarmCenterPage from './AlarmCenterPage.vue';

const alarmApi = vi.hoisted(() => ({
  acknowledgeAlarm: vi.fn(),
  closeAlarm: vi.fn(),
  getAlarm: vi.fn(),
  getAlarms: vi.fn()
}));
const authState = vi.hoisted(() => ({ userInfo: { buttons: [] as string[] } }));

vi.mock('@/modules/alarm/api/alarm', () => alarmApi);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

describe('AlarmCenterPage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    Object.values(alarmApi).forEach(mock => mock.mockReset());
    alarmApi.getAlarms.mockResolvedValue({ items: [alarm()], page: 1, size: 20, total: 1 });
    alarmApi.getAlarm.mockResolvedValue(alarm());
    alarmApi.acknowledgeAlarm.mockResolvedValue({ ...alarm(), acknowledgedAt: '2026-07-17T01:05:00Z' });
    alarmApi.closeAlarm.mockResolvedValue({
      ...alarm(),
      recoveredAt: '2026-07-17T01:03:00Z',
      closedAt: '2026-07-17T01:08:00Z',
      status: 'CLOSED'
    });
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
    window.$dialog = { warning: vi.fn() } as never;
  });

  it('默认应查询全部未关闭告警并展示严重度、设备与规则', async () => {
    const wrapper = mountPage(['alarm:event:view']);
    await flushPromises();

    expect(alarmApi.getAlarms).toHaveBeenCalledWith(
      expect.objectContaining({ active: null, closed: false, status: null, page: 1, size: 20 })
    );
    expect(wrapper.text()).toContain('告警处置中心');
    expect(wrapper.text()).toContain('严重');
    expect(wrapper.text()).toContain('北区运输车 01');
    expect(wrapper.text()).toContain('发动机温度过高');
  });

  it('确认操作应复用稳定幂等键并在成功后刷新列表和详情', async () => {
    alarmApi.acknowledgeAlarm.mockRejectedValueOnce(
      new ApiError({ code: 'SYS-NETWORK-001', message: '网络暂时不可用' }, 0)
    );
    const wrapper = mountPage(['alarm:event:view', 'alarm:event:ack']);
    await flushPromises();
    const page = wrapper.vm as unknown as {
      showDetails: (value: unknown) => Promise<void>;
      dispositionNote: string;
      submitAcknowledgement: () => Promise<void>;
    };

    await page.showDetails(alarm());
    page.dispositionNote = '调度已通知现场检查';
    await page.submitAcknowledgement();
    await page.submitAcknowledgement();

    expect(alarmApi.acknowledgeAlarm).toHaveBeenCalledTimes(2);
    const firstKey = alarmApi.acknowledgeAlarm.mock.calls[0]?.[2];
    expect(firstKey).toEqual(expect.any(String));
    expect(alarmApi.acknowledgeAlarm.mock.calls[1]?.[2]).toBe(firstKey);
    expect(alarmApi.getAlarms).toHaveBeenCalledTimes(2);
  });

  it('仅已恢复且有权限的告警允许关闭', async () => {
    const recovered = { ...alarm(), recoveredAt: '2026-07-17T01:03:00Z', status: 'RECOVERED_ACKNOWLEDGED' };
    alarmApi.getAlarm.mockResolvedValue(recovered);
    const wrapper = mountPage(['alarm:event:view', 'alarm:event:close']);
    const page = wrapper.vm as unknown as { showDetails: (value: unknown) => Promise<void> };
    await flushPromises();
    await page.showDetails(recovered);
    await flushPromises();

    expect(wrapper.text()).toContain('关闭告警');
  });
});

function mountPage(permissions: string[]) {
  authState.userInfo.buttons = permissions;
  return mount(AlarmCenterPage, { global: { plugins: [i18n], stubs: { teleport: true } } });
}

function alarm() {
  return {
    id: 'alarm-1',
    deviceId: 'device-1',
    deviceCode: 'TRUCK-001',
    deviceName: '北区运输车 01',
    productKey: 'haul-truck',
    ruleKey: 'engine-temperature-high',
    ruleName: '发动机温度过高',
    sourceType: 'NUMERIC',
    severity: 'CRITICAL',
    propertyId: 'engine.temperature',
    status: 'ACTIVE_UNACKNOWLEDGED',
    policyRuntimeRevision: 5,
    policySnapshotId: 'snapshot-1',
    policyContentHash: 'sha256:alarm',
    raisedAt: '2026-07-17T01:00:00Z',
    raisedEvidence: { value: 112, threshold: 110 },
    recoveredAt: null,
    recoveredEvidence: {},
    recoveryReason: null,
    acknowledgedAt: null,
    acknowledgedBy: null,
    acknowledgementNote: null,
    closedAt: null,
    closedBy: null,
    closeNote: null,
    version: 3
  };
}
