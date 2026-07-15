import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { createMemoryHistory, createRouter } from 'vue-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { localStg } from '@/utils/storage';
import LoginPage from './LoginPage.vue';

const { changePasswordMock, loginMock } = vi.hoisted(() => ({
  changePasswordMock: vi.fn(),
  loginMock: vi.fn()
}));

function mockPreferredDarkMode(matches: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockReturnValue({
      matches,
      media: '(prefers-color-scheme: dark)',
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn()
    })
  );
}

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
      plugins: [createPinia(), router]
    }
  });
  return { router, wrapper };
}

describe('LoginPage', () => {
  beforeEach(() => {
    changePasswordMock.mockReset();
    loginMock.mockReset();
    window.localStorage.removeItem('mine-login-theme');
    mockPreferredDarkMode(false);
  });

  it('应展示智慧矿山品牌和深蓝登录舱', async () => {
    const { wrapper } = await mountPage();

    expect(wrapper.find('.network-canvas').exists()).toBe(false);
    expect(wrapper.get('.mine-scene-asset').attributes('src')).toContain('smart-mine-tunnel.svg');
    expect(wrapper.get('.brand-stage').text()).toContain('智慧矿山数据平台');
    expect(wrapper.get('.brand-stage').classes()).toContain('brand-stage--lower-left');
    expect(wrapper.get('.login-card').classes()).toContain('login-card');
    expect(wrapper.get('.login-card').classes()).toContain('liquid-glass');
    expect(wrapper.find('#liquid-glass-distortion').exists()).toBe(true);
    expect(wrapper.find('.mine-visual').exists()).toBe(false);
  });

  it('液态玻璃高光应保持静态并展示表单语义图标', async () => {
    const { wrapper } = await mountPage();
    const card = wrapper.get('.liquid-glass');

    await card.trigger('pointermove', { clientX: 110, clientY: 60 });

    expect(card.attributes('style')).toBeUndefined();
    expect(wrapper.find('[data-login-icon="username"]').exists()).toBe(true);
    expect(wrapper.find('[data-login-icon="password"]').exists()).toBe(true);
    expect(wrapper.find('[data-login-icon="submit"]').exists()).toBe(true);
    expect(wrapper.find('[data-login-icon="service"]').exists()).toBe(true);
  });

  it('应读取并记住登录页的亮暗主题偏好', async () => {
    window.localStorage.setItem('mine-login-theme', 'dark');
    const { wrapper } = await mountPage();
    const switchButton = wrapper.get('[data-login-theme-switch]');

    expect(wrapper.get('.login-shell').classes()).toContain('login-shell--dark');
    expect(switchButton.attributes('aria-label')).toBe('切换为亮色模式');

    await switchButton.trigger('click');

    expect(wrapper.get('.login-shell').classes()).toContain('login-shell--light');
    expect(window.localStorage.getItem('mine-login-theme')).toBe('light');
    expect(switchButton.attributes('aria-label')).toBe('切换为暗色模式');
  });

  it('没有本地偏好时应跟随系统深色模式', async () => {
    mockPreferredDarkMode(true);

    const { wrapper } = await mountPage();

    expect(wrapper.get('.login-shell').classes()).toContain('login-shell--dark');
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
