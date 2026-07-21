import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { NInputNumber, NSwitch } from 'naive-ui';
import { describe, expect, it } from 'vitest';
import { i18n, setLocale } from '@/locales';
import type { RetentionPolicy } from '@/modules/device/api/strategy';
import RetentionPolicyEditor from './RetentionPolicyEditor.vue';

const standardPolicy: RetentionPolicy = {
  enabled: true,
  retentionClass: 'STANDARD',
  rawTelemetryDays: 90,
  qualityEvidenceDays: 180
};

describe('RetentionPolicyEditor', () => {
  it('应展示四个安全预设、等级边界和不可逆风险', () => {
    setLocale('zh-CN');
    const wrapper = mountEditor(standardPolicy);

    expect(wrapper.findAll('[data-retention-preset]')).toHaveLength(4);
    expect(wrapper.text()).toContain('短期');
    expect(wrapper.text()).toContain('标准');
    expect(wrapper.text()).toContain('长期');
    expect(wrapper.text()).toContain('归档');
    expect(wrapper.text()).toContain('31 至 180 天');
    expect(wrapper.text()).toContain('删除不可恢复');
  });

  it.each([
    ['SHORT', 7, 30],
    ['STANDARD', 90, 180],
    ['LONG', 365, 730],
    ['ARCHIVE', 1825, 3650]
  ] as const)('选择 %s 预设时应整体替换策略', async (retentionClass, rawTelemetryDays, qualityEvidenceDays) => {
    const wrapper = mountEditor(standardPolicy);

    await wrapper.get(`[data-action="retention-preset-${retentionClass}"]`).trigger('click');

    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([
      { enabled: true, retentionClass, rawTelemetryDays, qualityEvidenceDays }
    ]);
  });

  it('应按等级限制原始遥测天数并拒绝质量证据短于原始遥测', async () => {
    const wrapper = mountEditor({
      enabled: true,
      retentionClass: 'SHORT',
      rawTelemetryDays: 7,
      qualityEvidenceDays: 30
    });
    const inputs = wrapper.findAllComponents(NInputNumber);

    expect(inputs[0]?.props('min')).toBe(7);
    expect(inputs[0]?.props('max')).toBe(30);
    inputs[0]?.vm.$emit('update:value', 30);
    await wrapper.setProps({
      modelValue: {
        enabled: true,
        retentionClass: 'SHORT',
        rawTelemetryDays: 30,
        qualityEvidenceDays: 30
      }
    });
    inputs[1]?.vm.$emit('update:value', 29);
    await flushPromises();

    expect(wrapper.text()).toContain('质量证据保留时间不能短于原始遥测');
    expect(wrapper.emitted('validityChange')?.at(-1)).toEqual([false]);
  });

  it('暂停应仅切换 enabled 并明确不会恢复已删除数据', async () => {
    const wrapper = mountEditor(standardPolicy);

    wrapper.getComponent(NSwitch).vm.$emit('update:value', false);
    await flushPromises();

    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([{ ...standardPolicy, enabled: false }]);
    expect(wrapper.text()).toContain('不会恢复已经删除的数据');
  });
});

function mountEditor(modelValue: RetentionPolicy) {
  setLocale('zh-CN');
  let wrapper!: VueWrapper;
  wrapper = mount(RetentionPolicyEditor, {
    props: {
      modelValue,
      'onUpdate:modelValue': (value: RetentionPolicy) => wrapper.setProps({ modelValue: value })
    },
    global: { plugins: [i18n] }
  });
  return wrapper;
}
