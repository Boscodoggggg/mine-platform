import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MenuIconSelect from '@/modules/system/components/MenuIconSelect.vue';
import { i18n, setLocale } from '@/locales';
import { menuIconifyOptions } from '@/modules/system/components/menu-icon-options';
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
vi.mock('@/components/custom/svg-icon.vue', () => ({
  default: {
    name: 'SvgIcon',
    props: ['icon', 'localIcon'],
    template: '<svg :data-icon="icon || localIcon" />'
  }
}));

describe('MenuManagementPage', () => {
  const wrappers: Array<ReturnType<typeof mount>> = [];

  function mountPage() {
    const wrapper = mount(MenuManagementPage, { global: { plugins: [i18n] } });
    wrappers.push(wrapper);
    return wrapper;
  }

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

  afterEach(() => {
    wrappers.splice(0).forEach(wrapper => wrapper.unmount());
    document.body.innerHTML = '';
  });

  it('图标选择器应覆盖平台已发布的菜单图标', () => {
    expect(menuIconifyOptions).toEqual(
      expect.arrayContaining([
        'mdi:account-multiple-outline',
        'mdi:book-open-variant',
        'mdi:console-network-outline',
        'mdi:menu-open',
        'mdi:package-variant-closed',
        'mdi:send-clock-outline',
        'mdi:server-network-outline',
        'mdi:vector-polyline-edit'
      ])
    );
  });

  it('主表应只展示目录和页面并隐藏按钮权限节点', async () => {
    const wrapper = mountPage();
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
    const wrapper = mountPage();
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
    const wrapper = mountPage();
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
    const wrapper = mountPage();
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
    const wrapper = mountPage();
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
    const wrapper = mountPage();
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
    const wrapper = mountPage();
    await flushPromises();
    const page = wrapper.vm as unknown as { openCreate: () => void };

    page.openCreate();
    await flushPromises();

    const formItems = document.body.querySelectorAll('[data-menu-field]');
    expect(formItems.length).toBeGreaterThan(0);
    formItems.forEach(item => expect(item.querySelector('[data-field-help]')).not.toBeNull());
  });

  it('菜单图标应通过带预览的选择器维护', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const page = wrapper.vm as unknown as { openCreate: () => void };

    page.openCreate();
    await flushPromises();

    expect(document.body.querySelector('[data-menu-icon-type]')).not.toBeNull();
    const picker = document.body.querySelector('[data-menu-icon-select]');
    expect(picker).not.toBeNull();
    expect(picker?.querySelector('input')?.hasAttribute('readonly')).toBe(true);
  });

  it('选择本地图标后应使用兼容前缀保存', async () => {
    api.createMenu.mockResolvedValue(undefined);
    const wrapper = mountPage();
    await flushPromises();
    const page = wrapper.vm as unknown as {
      form: { name: string };
      openCreate: () => void;
      save: () => Promise<void>;
      updateMenuIconType: (value: 'iconify' | 'local') => void;
    };

    page.openCreate();
    await flushPromises();
    page.form.name = '本地图标目录';
    page.updateMenuIconType('local');
    await flushPromises();
    wrapper.findComponent(MenuIconSelect).vm.$emit('update:value', 'activity');
    await page.save();

    expect(api.createMenu).toHaveBeenCalledWith(expect.objectContaining({ icon: 'local:activity' }));
  });

  it('编辑本地图标菜单时应恢复图标类型和选择值', async () => {
    const wrapper = mountPage();
    await flushPromises();
    const page = wrapper.vm as unknown as {
      menuIconType: 'iconify' | 'local';
      openEdit: (row: ReturnType<typeof menuTree>[number]) => void;
    };

    page.openEdit({ ...menuTree()[0], icon: 'local:activity' });
    await flushPromises();

    expect(page.menuIconType).toBe('local');
    expect(wrapper.findComponent(MenuIconSelect).props()).toMatchObject({ type: 'local', value: 'activity' });
  });

  it('选择 Iconify 图标时应原样保存图标键', async () => {
    api.createMenu.mockResolvedValue(undefined);
    const wrapper = mountPage();
    await flushPromises();
    const page = wrapper.vm as unknown as { form: { name: string }; openCreate: () => void; save: () => Promise<void> };

    page.openCreate();
    await flushPromises();
    page.form.name = 'Iconify 图标目录';
    wrapper.findComponent(MenuIconSelect).vm.$emit('update:value', 'mdi:factory');
    await page.save();

    expect(api.createMenu).toHaveBeenCalledWith(expect.objectContaining({ icon: 'mdi:factory' }));
  });

  it('菜单表格应渲染图标而不是显示图标键文本', async () => {
    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.find('[data-menu-icon-value="mdi:factory"]').exists()).toBe(true);
    expect(wrapper.text()).not.toContain('mdi:factory');
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
      icon: 'mdi:factory',
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
          icon: 'mdi:format-list-bulleted',
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
