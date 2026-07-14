import { flushPromises, mount } from '@vue/test-utils';
import { createMemoryHistory, createRouter } from 'vue-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { localStg } from '@/utils/storage';
import LoginPage from './LoginPage.vue';

const { changePasswordMock, loginMock } = vi.hoisted(() => ({
  changePasswordMock: vi.fn(),
  loginMock: vi.fn()
}));

vi.mock('@/modules/system/api/auth', () => ({
  changePassword: changePasswordMock,
  login: loginMock
}));

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: LoginPage },
      { path: '/', component: { template: '<div>平台首页</div>' } }
    ]
  });
  await router.push('/login');
  await router.isReady();

  const wrapper = mount(LoginPage, {
    global: {
      plugins: [router]
    }
  });
  return { router, wrapper };
}

describe('LoginPage', () => {
  beforeEach(() => {
    changePasswordMock.mockReset();
    loginMock.mockReset();
  });

  it('应展示精简品牌主张和粒子矿坑场景', async () => {
    const { wrapper } = await mountPage();

    expect(wrapper.get('.site-copy h1').text()).toBe('智联矿山洞见现场');
    expect(wrapper.get('.mine-visual').attributes('src')).toContain('login-mine-landscape');
    expect(wrapper.findAll('.mine-particle')).toHaveLength(144);
    expect(wrapper.get('.mine-particle').attributes('style')).toContain('--travel-x');
    expect(wrapper.get('.mine-particle').attributes('style')).toContain('--travel-y');
    expect(wrapper.find('.capability-list').exists()).toBe(false);
    expect(wrapper.text()).not.toContain('面向矿区本地部署');
  });

  it('用户名和密码为空时不应提交登录请求', async () => {
    const { wrapper } = await mountPage();

    await wrapper.get('form').trigger('submit');

    expect(loginMock).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('请输入用户名');
    expect(wrapper.text()).toContain('请输入密码');
  });

  it('登录失败时应展示服务端消息和链路编号', async () => {
    loginMock.mockRejectedValue({
      code: 'AUTH-401-001',
      message: '用户名或密码错误',
      traceId: 'trace-login-001'
    });
    const { wrapper } = await mountPage();

    await wrapper.get('input[name="username"]').setValue('mine-admin');
    await wrapper.get('input[name="password"]').setValue('wrong-password');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(wrapper.text()).toContain('用户名或密码错误');
    expect(wrapper.text()).toContain('trace-login-001');
  });

  it('登录成功时应保存会话并进入平台首页', async () => {
    loginMock.mockResolvedValue({
      tokenName: 'X-Mine-Token',
      tokenValue: 'token-value',
      user: {
        id: '0198aef6-2e80-7000-8000-000000000001',
        username: 'mine-admin',
        displayName: '矿区超级管理员',
        passwordChangeRequired: false
      }
    });
    const { router, wrapper } = await mountPage();

    await wrapper.get('input[name="username"]').setValue('mine-admin');
    await wrapper.get('input[name="password"]').setValue('mine-admin-password');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(loginMock).toHaveBeenCalledWith({
      username: 'mine-admin',
      password: 'mine-admin-password'
    });
    expect(localStg.get('token')).toBe('token-value');
    expect(localStg.get('tokenName')).toBe('X-Mine-Token');
    expect(localStg.get('userProfile')).toMatchObject({
      username: 'mine-admin'
    });
    expect(router.currentRoute.value.path).toBe('/');
  });

  it('首次登录必须完成改密并清除旧会话', async () => {
    loginMock.mockResolvedValue({
      tokenName: 'X-Mine-Token',
      tokenValue: 'initial-token',
      user: {
        id: '0198aef6-2e80-7000-8000-000000000001',
        username: 'mine-admin',
        displayName: '矿区超级管理员',
        passwordChangeRequired: true
      }
    });
    changePasswordMock.mockResolvedValue(undefined);
    const { router, wrapper } = await mountPage();

    await wrapper.get('input[name="username"]').setValue('mine-admin');
    await wrapper.get('input[name="password"]').setValue('initial-password');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(wrapper.text()).toContain('修改初始密码');
    expect(router.currentRoute.value.path).toBe('/login');

    await wrapper.get('input[name="currentPassword"]').setValue('initial-password');
    await wrapper.get('input[name="newPassword"]').setValue('changed-password-value');
    await wrapper.get('input[name="confirmPassword"]').setValue('changed-password-value');
    await wrapper.get('form').trigger('submit');
    await flushPromises();

    expect(changePasswordMock).toHaveBeenCalledWith({
      currentPassword: 'initial-password',
      newPassword: 'changed-password-value'
    });
    expect(wrapper.text()).toContain('密码修改成功，请重新登录');
    expect(localStg.get('token')).toBeNull();
    expect(localStg.get('userProfile')).toBeNull();
  });
});
