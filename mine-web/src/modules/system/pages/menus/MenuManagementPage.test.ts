import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { i18n, setLocale } from '@/locales';
import MenuManagementPage from './MenuManagementPage.vue';

const api = vi.hoisted(() => ({
  createMenu: vi.fn(),
  deleteMenu: vi.fn(),
  getMenuComponentOptions: vi.fn(),
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
    api.getMenuComponentOptions.mockResolvedValue([{ key: 'device-product-list', name: '产品管理' }]);
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
  });

  it('默认应折叠权限树并支持一键展开全部', async () => {
    const wrapper = mount(MenuManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.text()).toContain('设备管理');
    expect(wrapper.text()).not.toContain('创建产品');

    await wrapper.get('[data-action="toggle-tree"]').trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('创建产品');
  });

  it('应提供树节点筛选而不是普通分页', async () => {
    const wrapper = mount(MenuManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();

    expect(wrapper.find('[data-filter="menu-name"]').exists()).toBe(true);
    expect(wrapper.find('[data-filter="menu-type"]').exists()).toBe(true);
    expect(wrapper.find('[data-filter="menu-status"]').exists()).toBe(true);
    expect(wrapper.find('[data-pagination]').exists()).toBe(false);
  });

  it('创建菜单时每个当前可见字段都应提供说明入口', async () => {
    const wrapper = mount(MenuManagementPage, { global: { plugins: [i18n] } });
    await flushPromises();
    const page = wrapper.vm as unknown as { openCreate: () => void };

    page.openCreate();
    await flushPromises();

    const formItems = document.body.querySelectorAll('[data-menu-field]');
    const helpTriggers = document.body.querySelectorAll('[data-field-help]');
    expect(formItems.length).toBeGreaterThan(0);
    expect(helpTriggers.length).toBe(formItems.length);
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
