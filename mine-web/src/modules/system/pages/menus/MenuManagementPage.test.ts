import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import MenuManagementPage from './MenuManagementPage.vue';

const api = vi.hoisted(() => ({
  createMenu: vi.fn(),
  deleteMenu: vi.fn(),
  deleteMenus: vi.fn(),
  getMenuButtons: vi.fn(),
  getMenuComponentOptions: vi.fn(),
  getMenus: vi.fn(),
  getMenuTree: vi.fn(),
  updateMenu: vi.fn()
}));

const authState = vi.hoisted(() => ({
  userInfo: {
    buttons: ['system:menu:create', 'system:menu:update', 'system:menu:delete'] as string[]
  }
}));

vi.mock('@/modules/system/api/access-management', () => api);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

describe('MenuManagementPage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    Object.values(api).forEach(mock => mock.mockReset());
    api.getMenuTree.mockResolvedValue(menuTree());
    api.getMenus.mockImplementation((query: { parentId?: string }) => {
      if (query.parentId === 'directory-1') {
        return Promise.resolve({
          items: [{ ...menuTree()[0].children[0], hasChildren: false, children: undefined }],
          page: 1,
          size: 100,
          total: 1
        });
      }
      return Promise.resolve({
        items: [{ ...menuTree()[0], hasChildren: true, children: undefined }],
        page: 1,
        size: 10,
        total: 1
      });
    });
    api.getMenuButtons.mockResolvedValue(menuTree()[0].children[0].children);
    api.getMenuComponentOptions.mockResolvedValue([{ key: 'device-product-list', name: '产品管理' }]);
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
  });

  it('主表应只展示目录和页面并隐藏按钮权限节点', async () => {
    const wrapper = mount(MenuManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      pagedRows: Array<{ name: string; children?: Array<{ name: string }> }>;
      loadChildren: (row: { name: string }) => Promise<void>;
    };

    await page.loadChildren(page.pagedRows[0]);
    await flushPromises();

    expect(wrapper.text()).toContain('设备管理');
    expect(page.pagedRows[0].children?.map(row => row.name)).toEqual(['产品管理']);
    expect(page.pagedRows[0].children?.map(row => row.name)).not.toContain('创建产品');
    expect(api.getMenus).toHaveBeenCalledWith({
      parentId: 'directory-1',
      keyword: '',
      type: '',
      status: '',
      page: 1,
      size: 100
    });
  });

  it('首次应只展示根节点并等待展开加载子节点', async () => {
    const wrapper = mount(MenuManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as { pagedRows: Array<{ name: string }> };

    expect(page.pagedRows.map(row => row.name)).toEqual(['设备管理']);
    expect(wrapper.text()).not.toContain('产品管理');
    expect(api.getMenus).toHaveBeenCalledWith({
      parentId: undefined,
      keyword: '',
      type: '',
      status: '',
      page: 1,
      size: 10
    });
  });

  it('应提供筛选、分页、刷新和列设置', async () => {
    const wrapper = mount(MenuManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.find('[data-filter="menu-name"]').exists()).toBe(true);
    expect(wrapper.find('[data-filter="menu-type"]').exists()).toBe(true);
    expect(wrapper.find('[data-filter="menu-status"]').exists()).toBe(true);
    expect(wrapper.find('[data-pagination]').exists()).toBe(true);
    expect(wrapper.find('[data-action="refresh-menu"]').exists()).toBe(true);
    expect(wrapper.find('[data-action="configure-columns"]').exists()).toBe(true);
  });

  it('菜单主表应按每页十条进行分页', async () => {
    const roots = Array.from({ length: 12 }, (_, index) => ({
      ...menuTree()[0],
      id: `directory-${index + 1}`,
      name: `目录${index + 1}`,
      protectedNode: false,
      hasChildren: false,
      children: undefined
    }));
    api.getMenus.mockImplementation((query: { page: number; size: number }) => {
      const start = (query.page - 1) * query.size;
      return Promise.resolve({ items: roots.slice(start, start + query.size), page: query.page, size: query.size, total: 12 });
    });
    const wrapper = mount(MenuManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as { pagedRows: unknown[]; page: number };

    expect(page.pagedRows).toHaveLength(10);
    page.page = 2;
    await flushPromises();
    expect(page.pagedRows).toHaveLength(2);
  });

  it('批量删除应一次提交选中的菜单标识', async () => {
    window.confirm = vi.fn(() => true);
    api.deleteMenus.mockResolvedValue(undefined);
    const wrapper = mount(MenuManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      checkedRowKeys: string[];
      batchRemove: () => Promise<void>;
    };

    page.checkedRowKeys = ['menu-1'];
    await page.batchRemove();
    await flushPromises();

    expect(api.deleteMenus).toHaveBeenCalledWith(['menu-1']);
  });

  it('编辑页面时应在独立区域维护按钮权限', async () => {
    const wrapper = mount(MenuManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as {
      openEdit: (row: ReturnType<typeof menuTree>[number]['children'][number]) => void;
      openButtonCreate: () => void;
    };

    page.openEdit(menuTree()[0].children[0]);
    await flushPromises();

    expect(document.body.textContent).toContain('按钮权限');
    expect(document.body.textContent).toContain('创建产品');

    page.openButtonCreate();
    await flushPromises();
    const buttonFields = document.body.querySelectorAll('[data-button-field]');
    expect(buttonFields.length).toBeGreaterThan(0);
    buttonFields.forEach(item => expect(item.querySelector('[data-field-help]')).not.toBeNull());
  });

  it('创建菜单时每个当前可见字段都应提供说明入口', async () => {
    const wrapper = mount(MenuManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as { openCreate: () => void };

    page.openCreate();
    await flushPromises();

    const formItems = document.body.querySelectorAll('[data-menu-field]');
    expect(formItems.length).toBeGreaterThan(0);
    formItems.forEach(item => expect(item.querySelector('[data-field-help]')).not.toBeNull());
  });
});

function menuTree() {
  return [
    {
      id: 'directory-1',
      parentId: null,
      name: '设备管理',
      type: 'DIRECTORY',
      path: '/device',
      componentKey: null,
      permission: null,
      icon: 'factory',
      sortOrder: 10,
      visible: true,
      status: 'ENABLED',
      i18nKey: 'mine.menu.device',
      protectedNode: true,
      version: 0,
      children: [
        {
          id: 'menu-1',
          parentId: 'directory-1',
          name: '产品管理',
          type: 'MENU',
          path: '/device/products',
          componentKey: 'device-product-list',
          permission: 'device:product:view',
          icon: 'list',
          sortOrder: 10,
          visible: true,
          status: 'ENABLED',
          i18nKey: 'mine.menu.products',
          protectedNode: true,
          version: 0,
          children: [
            {
              id: 'button-1',
              parentId: 'menu-1',
              name: '创建产品',
              type: 'BUTTON',
              path: null,
              componentKey: null,
              permission: 'device:product:create',
              icon: null,
              sortOrder: 10,
              visible: false,
              status: 'ENABLED',
              i18nKey: null,
              protectedNode: false,
              version: 0,
              children: []
            }
          ]
        }
      ]
    }
  ];
}
