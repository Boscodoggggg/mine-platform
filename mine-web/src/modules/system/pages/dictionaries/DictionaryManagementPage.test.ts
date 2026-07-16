import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import DictionaryManagementPage from './DictionaryManagementPage.vue';

const api = vi.hoisted(() => ({
  createDictionaryItem: vi.fn(),
  createDictionaryType: vi.fn(),
  deleteDictionaryItem: vi.fn(),
  deleteDictionaryType: vi.fn(),
  getDictionaryItems: vi.fn(),
  getDictionaryTypes: vi.fn(),
  updateDictionaryItem: vi.fn(),
  updateDictionaryType: vi.fn()
}));

const authState = vi.hoisted(() => ({
  userInfo: {
    buttons: ['system:dictionary:create', 'system:dictionary:update', 'system:dictionary:delete'] as string[]
  }
}));

vi.mock('@/modules/system/api/dictionary', () => api);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

const dictionaryTypes = [
  {
    id: 'type-1',
    code: 'device_status',
    name: '设备状态',
    status: 'ENABLED' as const,
    description: null,
    builtIn: true,
    version: 0
  },
  {
    id: 'type-2',
    code: 'alarm_level',
    name: '告警等级',
    status: 'ENABLED' as const,
    description: null,
    builtIn: false,
    version: 0
  }
];

describe('DictionaryManagementPage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    Object.values(api).forEach(mock => mock.mockReset());
    api.getDictionaryTypes.mockResolvedValue(dictionaryTypes);
    api.getDictionaryItems.mockResolvedValue([]);
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
  });

  it('桌面端应按约 35/65 分栏并在窄屏切换为上下布局', async () => {
    const wrapper = mountPage();
    await flushPromises();

    const layout = wrapper.get('[data-dictionary-layout]');
    const panels = layout.findAll('[data-dictionary-panel]');

    expect(layout.attributes('data-cols')).toBe('1 l:20');
    expect(panels).toHaveLength(2);
    expect(panels[0].attributes('data-span')).toBe('1 l:7');
    expect(panels[1].attributes('data-span')).toBe('1 l:13');
    expect(panels.every(panel => panel.classes().includes('h-full'))).toBe(true);
  });

  it('字典类型应支持整行选择并展示明确选中态', async () => {
    const wrapper = mountPage();
    await flushPromises();

    const firstRow = wrapper.get('[data-dictionary-type-id="type-1"]');
    const secondRow = wrapper.get('[data-dictionary-type-id="type-2"]');
    expect(firstRow.attributes('aria-selected')).toBe('true');
    expect(secondRow.attributes('aria-selected')).toBe('false');

    await secondRow.trigger('click');
    await flushPromises();

    expect(api.getDictionaryItems).toHaveBeenLastCalledWith('type-2');
    expect(wrapper.get('[data-dictionary-type-id="type-2"]').attributes('aria-selected')).toBe('true');
    expect(wrapper.findAll('button').some(button => button.text() === '数据项')).toBe(false);
  });

  it('使用键盘操作行内按钮时不应误切换字典类型', async () => {
    const wrapper = mountPage();
    await flushPromises();

    const secondRow = wrapper.get('[data-dictionary-type-id="type-2"]');
    await secondRow.get('button').trigger('keydown', { key: 'Enter' });
    await flushPromises();

    expect(wrapper.get('[data-dictionary-type-id="type-1"]').attributes('aria-selected')).toBe('true');
    expect(api.getDictionaryItems).not.toHaveBeenCalledWith('type-2');
  });
});

function mountPage() {
  return mount(DictionaryManagementPage, { global: { plugins: [i18n] } });
}
