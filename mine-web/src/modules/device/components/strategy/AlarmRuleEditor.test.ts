import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import { NSelect } from 'naive-ui';
import { i18n, setLocale } from '@/locales';
import AlarmRuleEditor from './AlarmRuleEditor.vue';

describe('AlarmRuleEditor', () => {
  it('切换规则来源时应生成对应字段矩阵并移除不兼容字段', async () => {
    setLocale('zh-CN');
    const wrapper = mount(AlarmRuleEditor, {
      props: { modelValue: [numericRule()] },
      global: { plugins: [i18n] }
    });

    expect(wrapper.find('[data-field="alarm-operator"]').exists()).toBe(true);
    const sourceSelect = wrapper
      .findAllComponents(NSelect)
      .find(component => component.attributes('data-field') === 'alarm-source-type');
    sourceSelect?.vm.$emit('update:value', 'QUALITY');

    const updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as Array<Record<string, unknown>>;
    expect(updated[0]).toMatchObject({ sourceType: 'QUALITY', qualityRuleCode: 'STALE_TIMESTAMP' });
    expect(updated[0]).not.toHaveProperty('operator');
    expect(updated[0]).not.toHaveProperty('threshold');
  });

  it('只读模式应禁用规则字段并隐藏增删入口', () => {
    setLocale('zh-CN');
    const wrapper = mount(AlarmRuleEditor, {
      props: { modelValue: [numericRule()], readonly: true },
      global: { plugins: [i18n] }
    });

    expect(wrapper.find('[data-action="add-numeric-rule"]').exists()).toBe(false);
    expect(wrapper.find('[data-action="remove-alarm-rule"]').exists()).toBe(false);
    const sourceSelect = wrapper
      .findAllComponents(NSelect)
      .find(component => component.attributes('data-field') === 'alarm-source-type');
    expect(sourceSelect?.props('disabled')).toBe(true);
  });

  it('应复制规则并按有序规则集上移下移', async () => {
    setLocale('zh-CN');
    const second = { ...numericRule(), key: 'engine-pressure-low', name: '发动机压力过低' };
    const wrapper = mount(AlarmRuleEditor, {
      props: { modelValue: [numericRule(), second] },
      global: { plugins: [i18n] }
    });

    await wrapper.findAll('[data-action="copy-alarm-rule"]')[0]?.trigger('click');
    const copied = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof numericRule>[];
    expect(copied).toHaveLength(3);
    expect(copied[1]?.key).not.toBe(numericRule().key);
    expect(copied[1]?.name).toContain('副本');

    await wrapper.setProps({ modelValue: copied });
    await wrapper.findAll('[data-action="move-alarm-rule-down"]')[0]?.trigger('click');
    const reordered = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as ReturnType<typeof numericRule>[];
    expect(reordered.map(rule => rule.key)).toEqual([copied[1]?.key, copied[0]?.key, copied[2]?.key]);
    expect(wrapper.text()).toContain('覆盖会替换上级的完整规则集');
  });
});

function numericRule() {
  return {
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
  };
}
