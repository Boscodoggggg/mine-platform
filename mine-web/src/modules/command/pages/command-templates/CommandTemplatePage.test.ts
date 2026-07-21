import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NButton } from 'naive-ui';
import { i18n, setLocale } from '@/locales';
import CommandTemplatePage from './CommandTemplatePage.vue';

const api = vi.hoisted(() => ({
  createCommandTemplateDefinition: vi.fn(),
  createNextCommandTemplateDraft: vi.fn(),
  getCommandTemplateDefinitions: vi.fn(),
  getCommandTemplateProducts: vi.fn(),
  publishCommandTemplateDefinition: vi.fn(),
  updateCommandTemplateDefinition: vi.fn()
}));
const authState = vi.hoisted(() => ({
  userInfo: { buttons: ['command:template:edit', 'command:template:publish'] as string[] }
}));

vi.mock('@/modules/command/api/command', () => api);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

describe('CommandTemplatePage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    Object.values(api).forEach(mock => mock.mockReset());
    api.getCommandTemplateDefinitions.mockResolvedValue([definition()]);
    api.getCommandTemplateProducts.mockResolvedValue([product()]);
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
    window.$dialog = { warning: vi.fn() } as never;
  });

  it('应展示产品绑定和不可变发布确认', async () => {
    const wrapper = mount(CommandTemplatePage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.text()).toContain('鸣笛原始命令');
    expect(wrapper.text()).toContain('矿用运输车');
    const publish = wrapper.findAllComponents(NButton).find(button => button.text() === '发布版本');
    await publish?.trigger('click');

    expect(window.$dialog?.warning).toHaveBeenCalledWith(expect.objectContaining({ title: '发布不可变模板版本' }));
  });

  it('已发布模板应能复制为下一版草稿继续编辑', async () => {
    api.getCommandTemplateDefinitions.mockResolvedValue([definition('PUBLISHED')]);
    api.createNextCommandTemplateDraft.mockResolvedValue({ ...definition(), versionNumber: 2 });
    const wrapper = mount(CommandTemplatePage, { global: { plugins: [i18n] } });
    await flushPromises();

    const createVersion = wrapper.findAllComponents(NButton).find(button => button.text() === '新建版本');
    await createVersion?.trigger('click');
    await flushPromises();

    expect(api.createNextCommandTemplateDraft).toHaveBeenCalledWith('version-1');
  });
});

function definition(status: 'DRAFT' | 'PUBLISHED' = 'DRAFT') {
  return {
    templateId: 'template-1',
    versionId: 'version-1',
    templateKey: 'HORN_RAW',
    versionNumber: 1,
    status,
    nameZh: '鸣笛原始命令',
    nameEn: 'Horn raw command',
    description: '协议联调',
    riskLevel: 'LOW',
    payloadFormat: 'RAW_HEX',
    parameterSchema: { type: 'object', additionalProperties: false, properties: {} },
    rawPayloadHex: 'AABB01',
    payloadSha256: 'a'.repeat(64),
    defaultTtlSeconds: 300,
    defaultTimeoutSeconds: 30,
    requiresReason: true,
    replyExpected: false,
    productIds: ['product-1'],
    createdAt: '2026-07-21T04:30:00Z',
    updatedAt: '2026-07-21T04:30:00Z',
    publishedAt: status === 'PUBLISHED' ? '2026-07-21T04:30:00Z' : null,
    version: 0
  };
}

function product() {
  return {
    id: 'product-1',
    productKey: 'haul-truck',
    name: '矿用运输车'
  };
}
