import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NButton } from 'naive-ui';
import { i18n, setLocale } from '@/locales';
import ProductListPage from './ProductListPage.vue';

const api = vi.hoisted(() => ({
  archiveProduct: vi.fn(),
  createProduct: vi.fn(),
  getProducts: vi.fn(),
  updateProduct: vi.fn()
}));
const dictionaryApi = vi.hoisted(() => ({ getEnabledDictionaryItems: vi.fn() }));
const authState = vi.hoisted(() => ({ userInfo: { buttons: [] as string[] } }));

vi.mock('@/modules/device/api/device', () => api);
vi.mock('@/modules/system/api/dictionary', () => dictionaryApi);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));
vi.mock('@/modules/device/pages/strategy/StrategyWorkbenchPage.vue', () => ({
  default: {
    name: 'StrategyWorkbenchPage',
    props: ['fixedProductId', 'allowedStrategyTypes', 'embedded'],
    template: '<div data-embedded-strategy-workbench>{{ fixedProductId }}</div>'
  }
}));

const product = {
  id: 'product-1',
  productKey: 'haul-truck',
  name: '矿用运输车',
  deviceType: 'VEHICLE',
  status: 'ACTIVE' as const,
  archivedAt: null,
  createdAt: '2026-07-15T00:00:00Z',
  updatedAt: '2026-07-15T00:00:00Z',
  version: 0
};

describe('ProductListPage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    Object.values(api).forEach(mock => mock.mockReset());
    dictionaryApi.getEnabledDictionaryItems.mockReset();
    dictionaryApi.getEnabledDictionaryItems.mockImplementation((code: string) =>
      Promise.resolve(
        code === 'device_type'
          ? [dictionaryItem('VEHICLE', '车辆设备', 'Vehicle', 'primary')]
          : [
              dictionaryItem('DRAFT', '草稿', 'Draft', 'warning'),
              dictionaryItem('ACTIVE', '已发布', 'Active', 'primary'),
              dictionaryItem('ARCHIVED', '已归档', 'Archived', 'default')
            ]
      )
    );
    api.getProducts.mockResolvedValue({ items: [product], page: 1, size: 20, total: 1 });
    api.createProduct.mockResolvedValue(product);
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
  });

  it('应加载产品并按权限显示创建入口', async () => {
    const wrapper = mountPage(['device:product:create']);
    await flushPromises();

    expect(api.getProducts).toHaveBeenCalledWith(expect.objectContaining({ page: 1, size: 20 }));
    expect(wrapper.text()).toContain('产品列表');
    expect(wrapper.text()).toContain('矿用运输车');
    expect(wrapper.find('[data-action="create-product"]').exists()).toBe(true);
    expect(wrapper.find('[data-list-search-panel]').exists()).toBe(true);
    expect(wrapper.find('[data-list-card]').exists()).toBe(true);
    const listToolbar = wrapper.get('[data-list-toolbar]');
    expect(listToolbar.text()).toContain('创建产品');
    expect(listToolbar.text()).toContain('刷新');
    const createButton = wrapper
      .findAllComponents(NButton)
      .find(button => button.attributes('data-action') === 'create-product');
    expect(createButton?.props('size')).toBe('small');
    expect(createButton?.props('ghost')).toBe(true);
    expect(createButton?.find('.n-button__icon').exists()).toBe(true);
  });

  it('筛选操作按钮应保持紧凑宽度', async () => {
    const wrapper = mountPage([]);
    await flushPromises();
    await wrapper.get('[data-list-search-panel] .n-collapse-item__header-main').trigger('click');
    await flushPromises();
    const filterButtons = wrapper.findAllComponents(NButton).filter(button => ['查询', '重置'].includes(button.text()));

    expect(wrapper.find('[data-filter-actions]').exists()).toBe(true);
    expect(filterButtons).toHaveLength(2);
    expect(filterButtons.every(button => button.props('block') !== true)).toBe(true);
    expect(filterButtons.every(button => button.props('size') === 'small')).toBe(true);
    expect(filterButtons.every(button => button.find('.n-button__icon').exists())).toBe(true);
  });

  it('创建产品时应提交稳定产品编码和初始状态', async () => {
    const wrapper = mountPage(['device:product:create']);
    await flushPromises();
    const page = wrapper.vm as unknown as {
      productForm: Record<string, string>;
      openCreate: () => void;
      saveProduct: () => Promise<void>;
    };

    page.openCreate();
    Object.assign(page.productForm, {
      productKey: 'loader',
      name: '井下装载机',
      deviceType: 'VEHICLE',
      status: 'ACTIVE'
    });
    await page.saveProduct();

    expect(api.createProduct).toHaveBeenCalledWith({
      productKey: 'loader',
      name: '井下装载机',
      deviceType: 'VEHICLE',
      status: 'ACTIVE'
    });
  });

  it('应从字典加载设备类型和产品状态选项', async () => {
    const wrapper = mountPage(['device:product:create']);
    await flushPromises();
    const page = wrapper.vm as unknown as {
      deviceTypeOptions: Array<{ label: string; value: string }>;
      statusOptions: Array<{ label: string; value: string }>;
      productStatusType: (status: string) => string;
    };

    expect(dictionaryApi.getEnabledDictionaryItems).toHaveBeenCalledWith('device_type');
    expect(dictionaryApi.getEnabledDictionaryItems).toHaveBeenCalledWith('product_status');
    expect(page.deviceTypeOptions).toEqual([{ label: '车辆设备', value: 'VEHICLE' }]);
    expect(page.statusOptions.map(option => option.value)).toEqual(['DRAFT', 'ACTIVE', 'ARCHIVED']);
    expect(page.productStatusType('ACTIVE')).toBe('primary');
  });

  it('空列表应提供可执行的引导信息', async () => {
    api.getProducts.mockResolvedValue({ items: [], page: 1, size: 20, total: 0 });
    const wrapper = mountPage(['device:product:create']);
    await flushPromises();

    expect(wrapper.text()).toContain('先创建产品，再接入设备');
  });

  it('产品行应提供运行策略入口并固定当前产品上下文', async () => {
    const wrapper = mountPage(['device:strategy:view']);
    await flushPromises();

    const action = wrapper.get('[data-action="product-runtime-strategy"]');
    expect(action.text()).toBe('运行策略');

    await action.trigger('click');
    await flushPromises();

    const workbench = wrapper.get('[data-embedded-strategy-workbench]');
    expect(workbench.text()).toBe('product-1');
    expect(wrapper.text()).toContain('矿用运输车 · 运行策略');
  });
});

function mountPage(permissions: string[]) {
  authState.userInfo.buttons = permissions;
  return mount(ProductListPage, { global: { plugins: [i18n], stubs: { teleport: true } } });
}

function dictionaryItem(value: string, labelZh: string, labelEn: string, semanticType: string) {
  return {
    id: `item-${value}`,
    typeId: 'type-1',
    value,
    labelZh,
    labelEn,
    semanticType,
    sortOrder: 10,
    defaultItem: false,
    status: 'ENABLED',
    version: 0
  };
}
