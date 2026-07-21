import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NButton } from 'naive-ui';
import { i18n, setLocale } from '@/locales';
import UserManagementPage from './UserManagementPage.vue';

const api = vi.hoisted(() => ({
  createUser: vi.fn(),
  getRoles: vi.fn(),
  getUsers: vi.fn(),
  replaceUserRoles: vi.fn(),
  resetUserPassword: vi.fn(),
  updateUser: vi.fn()
}));

const authState = vi.hoisted(() => ({ userInfo: { buttons: [] as string[] } }));

vi.mock('@/modules/system/api/access-management', () => api);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

const user = {
  id: 'user-1',
  username: 'dispatcher',
  displayName: '调度员',
  status: 'ENABLED' as const,
  passwordChangeRequired: true,
  lastLoginAt: null,
  createdAt: '2026-07-15T00:00:00Z',
  updatedAt: '2026-07-15T00:00:00Z',
  version: 0,
  roleIds: ['role-1']
};

describe('UserManagementPage', () => {
  beforeEach(() => {
    setLocale('zh-CN');
    Object.values(api).forEach(mock => mock.mockReset());
    api.getUsers.mockResolvedValue({ items: [user], page: 1, size: 20, total: 1 });
    api.getRoles.mockResolvedValue({
      items: [
        {
          id: 'role-1',
          code: 'DISPATCHER',
          name: '调度员',
          status: 'ENABLED',
          description: null,
          createdAt: '2026-07-15T00:00:00Z',
          updatedAt: '2026-07-15T00:00:00Z',
          version: 0,
          menuIds: []
        }
      ],
      page: 1,
      size: 100,
      total: 1
    });
    api.createUser.mockResolvedValue(user);
    api.replaceUserRoles.mockResolvedValue(undefined);
    api.resetUserPassword.mockResolvedValue(undefined);
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
  });

  it('应加载用户并按权限显示创建入口', async () => {
    const wrapper = mountPage(['system:user:create']);
    await flushPromises();

    expect(api.getUsers).toHaveBeenCalledWith(expect.objectContaining({ page: 1, size: 20 }));
    expect(wrapper.text()).toContain('用户列表');
    expect(wrapper.find('[data-action="create-user"]').exists()).toBe(true);
    expect(wrapper.find('[data-list-search-panel]').exists()).toBe(true);
    expect(wrapper.find('[data-list-card]').exists()).toBe(true);
    const listToolbar = wrapper.get('[data-list-toolbar]');
    expect(listToolbar.text()).toContain('创建用户');
    expect(listToolbar.text()).toContain('刷新');
    const createButton = wrapper
      .findAllComponents(NButton)
      .find(button => button.attributes('data-action') === 'create-user');
    expect(createButton?.props('size')).toBe('small');
    expect(createButton?.props('ghost')).toBe(true);
    expect(createButton?.find('.n-button__icon').exists()).toBe(true);
    expect(wrapper.text()).not.toContain('temporary-password');
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

  it('创建用户后应清除临时密码字段', async () => {
    const wrapper = mountPage(['system:user:create']);
    await flushPromises();
    const page = wrapper.vm as unknown as {
      userForm: Record<string, string>;
      openCreate: () => void;
      saveUser: () => Promise<void>;
    };

    page.openCreate();
    Object.assign(page.userForm, {
      username: 'dispatcher',
      displayName: '调度员',
      temporaryPassword: 'temporary-password',
      status: 'ENABLED'
    });
    await page.saveUser();

    expect(api.createUser).toHaveBeenCalledWith(
      expect.objectContaining({ username: 'dispatcher', temporaryPassword: 'temporary-password' })
    );
    expect(page.userForm.temporaryPassword).toBe('');
  });

  it('应保存角色分配并在密码重置后清空敏感输入', async () => {
    const wrapper = mountPage(['system:user:assign-role', 'system:user:reset-password']);
    await flushPromises();
    const page = wrapper.vm as unknown as {
      selectedRoleIds: string[];
      temporaryPassword: string;
      openRoles: (target: typeof user) => Promise<void>;
      saveRoles: () => Promise<void>;
      openPasswordReset: (target: typeof user) => void;
      savePasswordReset: () => Promise<void>;
    };

    await page.openRoles(user);
    page.selectedRoleIds = ['role-1'];
    await page.saveRoles();
    expect(api.replaceUserRoles).toHaveBeenCalledWith('user-1', ['role-1']);

    page.openPasswordReset(user);
    page.temporaryPassword = 'changed-temporary-password';
    await page.savePasswordReset();
    expect(api.resetUserPassword).toHaveBeenCalledWith('user-1', 'changed-temporary-password');
    expect(page.temporaryPassword).toBe('');
  });
});

function mountPage(permissions: string[]) {
  authState.userInfo.buttons = permissions;
  return mount(UserManagementPage, { global: { plugins: [i18n] } });
}
