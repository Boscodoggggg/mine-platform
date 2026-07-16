import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import ProtocolSampleWorkbench from './ProtocolSampleWorkbench.vue';

const api = vi.hoisted(() => ({
  getProtocolSamples: vi.fn(),
  saveProtocolSamples: vi.fn(),
  testProtocolSamples: vi.fn()
}));

vi.mock('@/modules/device/api/device-model', () => api);

const sample = {
  id: 'sample-1',
  name: '扁平字段上报',
  rawPayload: { productKey: 'haul-truck', deviceCode: 'truck-001', rpm: 1200 },
  expectedSuccess: true,
  expectedProperties: { engineRpm: 1200 },
  expectedErrorCode: null,
  sortOrder: 10,
  version: 0
};

describe('ProtocolSampleWorkbench', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    Object.values(api).forEach(mock => mock.mockReset());
    api.getProtocolSamples.mockResolvedValue([sample]);
    api.saveProtocolSamples.mockResolvedValue([sample]);
    api.testProtocolSamples.mockResolvedValue({
      id: 'run-1',
      passed: true,
      totalSamples: 1,
      passedSamples: 1,
      failedSamples: 0,
      results: [{ name: sample.name, passed: true, message: '通过', actualErrorCode: null }]
    });
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
  });

  it('应展示黄金样本并明确回放门禁结果', async () => {
    const wrapper = mountWorkbench();
    await flushPromises();

    expect(api.getProtocolSamples).toHaveBeenCalledWith('protocol-1');
    expect(wrapper.text()).toContain('黄金样本');
    expect(wrapper.text()).toContain('扁平字段上报');

    await wrapper.get('[data-action="run-samples"]').trigger('click');
    await flushPromises();

    expect(api.testProtocolSamples).toHaveBeenCalledWith('protocol-1');
    expect(wrapper.text()).toContain('发布门禁已通过');
    expect(wrapper.text()).toContain('1 / 1');
  });

  it('应在保存前校验并提交全部样本草稿', async () => {
    const wrapper = mountWorkbench();
    await flushPromises();

    await wrapper.get('[data-action="add-sample"]').trigger('click');
    await wrapper.get('[data-field="sample-name"] input').setValue('嵌套字段上报');
    await wrapper.get('[data-field="raw-payload"] textarea').setValue('{"meta":{"device":"truck-001"}}');
    await wrapper.get('[data-field="expected-properties"] textarea').setValue('{"engineRpm":1500}');
    await wrapper.get('[data-action="apply-sample"]').trigger('click');
    await wrapper.get('[data-action="save-samples"]').trigger('click');
    await flushPromises();

    expect(api.saveProtocolSamples).toHaveBeenCalledWith(
      'protocol-1',
      3,
      expect.arrayContaining([expect.objectContaining({ name: '嵌套字段上报', expectedSuccess: true })])
    );
  });
});

function mountWorkbench() {
  return mount(ProtocolSampleWorkbench, {
    props: {
      mappingVersionId: 'protocol-1',
      mappingVersion: 3,
      editable: true,
      canEdit: true,
      canTest: true
    },
    global: { plugins: [i18n], stubs: { teleport: true } }
  });
}
