import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import { i18n, setLocale } from '@/locales';
import NumericCard from './NumericCard.vue';

describe('NumericCard', () => {
  it('应同时显示属性名称、数值、单位和质量状态', () => {
    setLocale('zh-CN');
    const wrapper = mount(NumericCard, {
      props: {
        propertyId: 'engine.rpm',
        label: '发动机转速',
        value: 1280,
        unit: 'RPM',
        quality: 'GOOD',
        receivedAt: '2026-07-15T00:00:00Z'
      },
      global: { plugins: [i18n] }
    });

    expect(wrapper.text()).toContain('发动机转速');
    expect(wrapper.text()).toContain('1280');
    expect(wrapper.text()).toContain('RPM');
    expect(wrapper.text()).toContain('数据可信');
  });

  it('没有数据时应明确显示等待上报而不是伪造零值', () => {
    setLocale('zh-CN');
    const wrapper = mount(NumericCard, {
      props: { propertyId: 'custom.field', label: 'custom.field', value: null },
      global: { plugins: [i18n] }
    });

    expect(wrapper.text()).toContain('等待设备上报');
    expect(wrapper.text()).not.toContain('0');
  });
});
