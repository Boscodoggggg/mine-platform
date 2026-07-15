import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import RoleManagementPage from './RoleManagementPage.vue';

const api = vi.hoisted(() => ({
  createRole: vi.fn(),
  deleteRole: vi.fn(),
  getMenuTree: vi.fn(),
  getRoles: vi.fn(),
  replaceRoleMenus: vi.fn(),
  updateRole: vi.fn()
}));

const authState = vi.hoisted(() => ({ userInfo: { buttons: [] as string[] } }));

vi.mock('@/modules/system/api/access-management', () => api);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => authState }));

const role = {
  id: 'role-1',
  code: 'DISPATCHER',
  name: '调度员',
  status: 'ENABLED' as const,
  description: '负责车辆调度',
  createdAt: '2026-07-15T00:00:00Z',
  updatedAt: '2026-07-15T00:00:00Z',
  version: 0,
  menuIds: ['menu-1']
};

describe('RoleManagementPage', () => {
  beforeEach(() => {
    Object.values(api).forEach(mock => mock.mockReset());
    api.getRoles.mockResolvedValue({ items: [role], page: 1, size: 20, total: 1 });
    api.getMenuTree.mockResolvedValue([
      {
        id: 'menu-1',
        parentId: null,
        name: '查看用户',
        type: 'BUTTON',
        path: null,
        componentKey: null,
        permission: 'system:user:view',
        icon: null,
        sortOrder: 1,
        visible: false,
        status: 'ENABLED',
        children: []
      }
    ]);
    api.createRole.mockResolvedValue(role);
    api.replaceRoleMenus.mockResolvedValue(undefined);
    api.deleteRole.mockResolvedValue(undefined);
    window.$message = { success: vi.fn(), error: vi.fn(), warning: vi.fn() } as never;
    window.confirm = vi.fn(() => true);
  });

  it('应按权限展示角色创建入口并加载列表', async () => {
    const wrapper = mountPage(['system:role:create']);
    await flushPromises();

    expect(api.getRoles).toHaveBeenCalled();
    expect(wrapper.text()).toContain('角色与权限');
    expect(wrapper.find('[data-action="create-role"]').exists()).toBe(true);
  });

  it('应创建角色并发布菜单权限', async () => {
    const wrapper = mountPage(['system:role:create', 'system:role:assign-menu']);
    await flushPromises();
    const page = wrapper.vm as unknown as {
      roleForm: Record<string, string>;
      checkedMenuIds: string[];
      openCreate: () => void;
      saveRole: () => Promise<void>;
      openPermissions: (target: typeof role) => Promise<void>;
      savePermissions: () => Promise<void>;
    };

    page.openCreate();
    Object.assign(page.roleForm, {
      code: 'auditor',
      name: '审计员',
      status: 'ENABLED',
      description: '查看审计记录'
    });
    await page.saveRole();
    expect(api.createRole).toHaveBeenCalledWith(expect.objectContaining({ code: 'AUDITOR' }));

    await page.openPermissions(role);
    page.checkedMenuIds = ['menu-1'];
    await page.savePermissions();
    expect(api.replaceRoleMenus).toHaveBeenCalledWith('role-1', ['menu-1']);
  });

  it('删除占用角色时应展示服务端冲突和链路号', async () => {
    api.deleteRole.mockRejectedValue(
      new ApiError(
        {
          code: 'SYSTEM-409-004',
          message: '角色仍被用户使用，请先解除关联',
          traceId: 'trace-role-001'
        },
        409
      )
    );
    const wrapper = mountPage(['system:role:delete']);
    await flushPromises();
    const page = wrapper.vm as unknown as { removeRole: (target: typeof role) => Promise<void> };

    await page.removeRole(role);

    expect(window.$message?.error).toHaveBeenCalledWith('角色仍被用户使用，请先解除关联（链路：trace-role-001）');
  });
});

function mountPage(permissions: string[]) {
  authState.userInfo.buttons = permissions;
  return mount(RoleManagementPage);
}
