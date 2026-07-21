import { mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import { NButton } from 'naive-ui';
import ListActionButton from './list-action-button.vue';

const iconStubs = {
  'icon-ic-round-plus': { template: '<span data-icon="create" />' },
  'icon-uil-search': { template: '<span data-icon="search" />' },
  'icon-ant-design-reload-outlined': { template: '<span data-icon="reset" />' },
  'icon-mdi-refresh': { template: '<span data-icon="refresh" />' },
  'icon-ant-design-setting-outlined': { template: '<span data-icon="columns" />' },
  'icon-ic-round-delete': { template: '<span data-icon="delete" />' }
};

const variants = [
  { kind: 'create', type: 'primary', ghost: true },
  { kind: 'search', type: 'primary', ghost: false },
  { kind: 'reset', type: 'default', ghost: false },
  { kind: 'refresh', type: 'default', ghost: false },
  { kind: 'columns', type: 'default', ghost: false },
  { kind: 'delete', type: 'error', ghost: true }
] as const;

describe('ListActionButton', () => {
  it.each(variants)('应为 $kind 操作提供统一的小尺寸层级和图标', ({ kind, type, ghost }) => {
    const wrapper = mount(ListActionButton, {
      props: { kind },
      slots: { default: kind },
      global: { stubs: iconStubs }
    });
    const button = wrapper.getComponent(NButton);

    expect(button.props('size')).toBe('small');
    expect(button.props('type')).toBe(type);
    expect(button.props('ghost')).toBe(ghost);
    expect(wrapper.find(`[data-icon="${kind}"]`).exists()).toBe(true);
  });

  it('应向内部按钮透传点击事件和数据属性', async () => {
    const onClick = vi.fn();
    const wrapper = mount(ListActionButton, {
      props: { kind: 'refresh' },
      attrs: { 'data-action': 'refresh-list', onClick },
      slots: { default: '刷新' },
      global: { stubs: iconStubs }
    });
    const button = wrapper.getComponent(NButton);

    expect(button.attributes('data-action')).toBe('refresh-list');
    await button.trigger('click');
    expect(onClick).toHaveBeenCalledOnce();
  });

  it('应向内部按钮透传加载状态', () => {
    const wrapper = mount(ListActionButton, {
      props: { kind: 'refresh' },
      attrs: { loading: true },
      slots: { default: '刷新' },
      global: { stubs: iconStubs }
    });

    expect(wrapper.getComponent(NButton).props('loading')).toBe(true);
  });

  it('禁用时不应触发点击事件', async () => {
    const onClick = vi.fn();
    const wrapper = mount(ListActionButton, {
      props: { kind: 'delete' },
      attrs: { disabled: true, onClick },
      slots: { default: '删除' },
      global: { stubs: iconStubs }
    });

    await wrapper.getComponent(NButton).trigger('click');
    expect(onClick).not.toHaveBeenCalled();
  });
});
