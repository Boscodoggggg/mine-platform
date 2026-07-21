import { computed, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';
import { defineStore } from 'pinia';
import { useLoading } from '@sa/hooks';
import { useRouterPush } from '@/hooks/common/router';
import { localStg } from '@/utils/storage';
import { SetupStoreId } from '@/enum';
import { $t } from '@/locales';
import { getCurrentUser, login as requestLogin } from '@/modules/system/api/auth';
import { getStoredUser, saveAuthSession } from '@/modules/system/auth/session';
import { useRouteStore } from '../route';
import { useTabStore } from '../tab';
import { clearAuthStorage, getToken } from './shared';

export const useAuthStore = defineStore(SetupStoreId.Auth, () => {
  const route = useRoute();
  const authStore = useAuthStore();
  const routeStore = useRouteStore();
  const tabStore = useTabStore();
  const { toLogin, redirectFromLogin } = useRouterPush(false);
  const { loading: loginLoading, startLoading, endLoading } = useLoading();

  const token = ref('');

  const userInfo: Api.Auth.UserInfo = reactive({
    userId: '',
    userName: '',
    displayName: '',
    passwordChangeRequired: false,
    roles: [],
    buttons: []
  });

  /** is super role in static route */
  const isStaticSuper = computed(() => {
    const { VITE_AUTH_ROUTE_MODE, VITE_STATIC_SUPER_ROLE } = import.meta.env;

    return VITE_AUTH_ROUTE_MODE === 'static' && userInfo.roles.includes(VITE_STATIC_SUPER_ROLE);
  });

  /** Is login */
  const isLogin = computed(() => Boolean(token.value));

  /** Reset auth store */
  async function resetStore() {
    recordUserId();

    clearAuthStorage();

    authStore.$reset();

    if (!route.meta.constant) {
      await toLogin();
    }

    tabStore.cacheTabs();
    routeStore.resetStore();
  }

  /** Record the user ID of the previous login session Used to compare with the current user ID on next login */
  function recordUserId() {
    if (!userInfo.userId) {
      return;
    }

    // Store current user ID locally for next login comparison
    localStg.set('lastLoginUserId', userInfo.userId);
  }

  /**
   * Check if current login user is different from previous login user If different, clear all tabs
   *
   * @returns {boolean} Whether to clear all tabs
   */
  function checkTabClear(): boolean {
    if (!userInfo.userId) {
      return false;
    }

    const lastLoginUserId = localStg.get('lastLoginUserId');

    // Clear all tabs if current user is different from previous user
    if (!lastLoginUserId || lastLoginUserId !== userInfo.userId) {
      localStg.remove('globalTabs');
      tabStore.clearTabs();

      localStg.remove('lastLoginUserId');
      return true;
    }

    localStg.remove('lastLoginUserId');
    return false;
  }

  /**
   * Login
   *
   * @param userName User name
   * @param password Password
   * @param [redirect=true] Whether to redirect after login. Default is `true`
   */
  async function login(userName: string, password: string, redirect = true) {
    startLoading();
    try {
      const response = await requestLogin({ username: userName, password });
      saveAuthSession(response);
      applyUser(response.user);
      token.value = response.tokenValue;

      if (response.user.passwordChangeRequired) return;

      const isClear = checkTabClear();
      await redirectFromLogin(isClear ? false : redirect);
      window.$notification?.success({
        title: $t('page.login.common.loginSuccess'),
        content: $t('page.login.common.welcomeBack', { userName: userInfo.userName }),
        duration: 4500
      });
    } catch (error) {
      await resetStore();
      const message = error instanceof Error ? error.message : '登录失败，请稍后重试';
      window.$message?.error(message);
    } finally {
      endLoading();
    }
  }

  async function getUserInfo() {
    try {
      const info = await getCurrentUser();
      localStg.set('userProfile', info);
      applyUser(info);
      return true;
    } catch {
      return false;
    }
  }

  async function initUserInfo() {
    const maybeToken = getToken();

    if (maybeToken) {
      token.value = maybeToken;
      const storedUser = getStoredUser();
      if (storedUser) {
        applyUser(storedUser);
        return;
      }
      const pass = await getUserInfo();

      if (!pass) {
        resetStore();
      }
    }
  }

  function applyUser(user: import('@/modules/system/api/auth').AuthUser) {
    Object.assign(userInfo, {
      userId: user.id,
      userName: user.username,
      displayName: user.displayName,
      passwordChangeRequired: user.passwordChangeRequired,
      roles: [],
      buttons: []
    });
  }

  /** 使用后端授权快照更新当前角色和按钮权限。 */
  function setAuthorization(roles: string[], permissions: string[]) {
    userInfo.roles = [...roles];
    userInfo.buttons = [...permissions];
  }

  return {
    token,
    userInfo,
    isStaticSuper,
    isLogin,
    loginLoading,
    resetStore,
    login,
    initUserInfo,
    setAuthorization
  };
});
