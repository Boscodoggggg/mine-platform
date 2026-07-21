import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { NInputNumber, NSelect, NSwitch } from 'naive-ui';
import { describe, expect, it } from 'vitest';
import { i18n, setLocale } from '@/locales';
import type { AggregationPolicy } from '@/modules/device/api/strategy';
import AggregationPolicyEditor from './AggregationPolicyEditor.vue';

const policy: AggregationPolicy = {
  enabled: true,
  correctionWindowSeconds: 86400,
  rules: [{ propertyId: 'engine.rpm', bucketSeconds: 300 }]
};

describe('AggregationPolicyEditor', () => {
  it('只提供六个固定桶宽并说明冷热查询边界', () => {
    const wrapper = mountEditor(policy);
    const bucket = wrapper
      .findAllComponents(NSelect)
      .find(component => component.attributes('data-field') === 'aggregation-bucket');

    expect(bucket?.props('options')).toHaveLength(6);
    expect(wrapper.text()).toContain('修正窗口');
    expect(wrapper.text()).toContain('长期聚合');
  });

  it('属性和桶宽重复时应拒绝并限制修正窗口至少为最大桶宽两倍', async () => {
    const duplicate: AggregationPolicy = {
      enabled: true,
      correctionWindowSeconds: 7200,
      rules: [
        { propertyId: 'engine.rpm', bucketSeconds: 3600 },
        { propertyId: 'engine.rpm', bucketSeconds: 3600 }
      ]
    };
    const wrapper = mountEditor(duplicate);
    const correction = wrapper
      .findAllComponents(NInputNumber)
      .find(component => component.attributes('data-field') === 'aggregation-correction-window');

    await flushPromises();
    expect(correction?.props('min')).toBe(7200);
    expect(wrapper.text()).toContain('重复');
    expect(wrapper.emitted('validityChange')?.at(-1)).toEqual([false]);
  });

  it('只读模式应禁用执行开关和全部规则输入', () => {
    const wrapper = mountEditor(policy, true);

    expect(wrapper.getComponent(NSwitch).props('disabled')).toBe(true);
    expect(wrapper.findAllComponents(NSelect).every(component => component.props('disabled'))).toBe(true);
  });
});

function mountEditor(modelValue: AggregationPolicy, readonly = false) {
  setLocale('zh-CN');
  let wrapper!: VueWrapper;
  wrapper = mount(AggregationPolicyEditor, {
    props: {
      modelValue,
      readonly,
      'onUpdate:modelValue': (value: AggregationPolicy) => wrapper.setProps({ modelValue: value })
    },
    global: { plugins: [i18n] }
  });
  return wrapper;
}
