import { flushPromises, mount } from '@vue/test-utils';
import { NButton } from 'naive-ui';
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

  it('字典类型和字典项的每个字段都应提供用途说明', async () => {
    const wrapper = mountPage();
    await flushPromises();

    await wrapper.findAllComponents(NButton).find(button => button.text() === '新增字典类型')?.trigger('click');
    await flushPromises();
    const typeFields = document.body.querySelectorAll('[data-dictionary-type-field]');

    expect(typeFields).toHaveLength(4);
    expect([...typeFields].map(field => field.querySelector('[data-field-help]')?.getAttribute('data-field-help'))).toEqual([
      'mine.dictionaries.help.code',
      'mine.dictionaries.help.name',
      'mine.dictionaries.help.status',
      'mine.dictionaries.help.description'
    ]);

    const codeHelpButton = typeFields[0].querySelector<HTMLButtonElement>('[data-field-help]');
    expect(codeHelpButton?.getAttribute('aria-label')).toContain('稳定编码');
    expect(codeHelpButton?.getAttribute('aria-describedby')).toBeTruthy();
    expect(document.getElementById(codeHelpButton?.getAttribute('aria-describedby') ?? '')?.textContent).toContain(
      '必须以小写字母开头'
    );
    expect(codeHelpButton?.getAttribute('aria-expanded')).toBe('false');
    codeHelpButton?.focus();
    await flushPromises();
    expect(codeHelpButton?.getAttribute('aria-expanded')).toBe('false');
    codeHelpButton?.dispatchEvent(new MouseEvent('mouseenter'));
    await flushPromises();
    expect(codeHelpButton?.getAttribute('aria-expanded')).toBe('true');
    expect(document.body.textContent).toContain('必须以小写字母开头');
    codeHelpButton?.dispatchEvent(new MouseEvent('mouseleave'));
    await flushPromises();
    expect(codeHelpButton?.getAttribute('aria-expanded')).toBe('false');

    await wrapper.findAllComponents(NButton).find(button => button.text() === '新增数据项')?.trigger('click');
    await flushPromises();
    const itemFields = document.body.querySelectorAll('[data-dictionary-item-field]');

    expect(itemFields).toHaveLength(7);
    expect([...itemFields].map(field => field.querySelector('[data-field-help]')?.getAttribute('data-field-help'))).toEqual([
      'mine.dictionaries.help.value',
      'mine.dictionaries.help.labelZh',
      'mine.dictionaries.help.labelEn',
      'mine.dictionaries.help.semantic',
      'mine.dictionaries.help.sort',
      'mine.dictionaries.help.status',
      'mine.dictionaries.help.default'
    ]);
    const valueHelpButton = itemFields[0].querySelector<HTMLButtonElement>('[data-field-help]');
    expect(valueHelpButton?.getAttribute('aria-expanded')).toBe('false');
    valueHelpButton?.focus();
    await flushPromises();
    expect(valueHelpButton?.getAttribute('aria-expanded')).toBe('false');
  });
});

function mountPage() {
  return mount(DictionaryManagementPage, { global: { plugins: [i18n] } });
}
