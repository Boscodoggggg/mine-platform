import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import { NCollapse } from 'naive-ui';
import { i18n, setLocale } from '@/locales';
import ListSearchPanel from './list-search-panel.vue';

describe('ListSearchPanel', () => {
  it('搜索条件应默认收起并允许用户展开', async () => {
    setLocale('zh-CN');
    const wrapper = mount(ListSearchPanel, {
      slots: { default: '<div data-search-fields>筛选条件</div>' },
      global: { plugins: [i18n] }
    });
    const collapse = wrapper.getComponent(NCollapse);

    expect(collapse.props('expandedNames')).toEqual([]);
    expect(wrapper.text()).toContain('搜索');

    await wrapper.get('.n-collapse-item__header-main').trigger('click');
    expect(collapse.props('expandedNames')).toEqual(['search']);
  });
});
