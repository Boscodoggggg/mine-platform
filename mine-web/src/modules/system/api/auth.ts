import { request } from '@/api/client';

export { ApiError } from '@/api/client';

export interface AuthUser {
  id: string;
  username: string;
  displayName: string;
  passwordChangeRequired: boolean;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  tokenName: string;
  tokenValue: string;
  user: AuthUser;
}

export interface PasswordChangeRequest {
  currentPassword: string;
  newPassword: string;
}

export type NavigationNodeType = 'DIRECTORY' | 'MENU' | 'BUTTON';

/** 后端授权后返回的菜单树节点。 */
export interface NavigationMenuNode {
  id: string;
  parentId: string | null;
  name: string;
  type: NavigationNodeType;
  path: string | null;
  componentKey: string | null;
  permission: string | null;
  icon: string | null;
  sortOrder: number;
  visible: boolean;
  i18nKey?: string | null;
  children: NavigationMenuNode[];
}

/** 当前用户的导航与操作权限。 */
export interface NavigationResponse {
  roles: string[];
  permissions: string[];
  menus: NavigationMenuNode[];
}

/** 使用用户名和密码创建管理端会话。 */
export function login(body: LoginRequest) {
  return request<LoginResponse>('/auth/login', {
    method: 'POST',
    body: JSON.stringify(body)
  });
}

/** 读取当前登录用户。 */
export function getCurrentUser() {
  return request<AuthUser>('/auth/me');
}

/** 读取当前用户可访问的菜单、角色和按钮权限。 */
export function getNavigation() {
  return request<NavigationResponse>('/auth/menus');
}

/** 修改初始密码或当前密码。 */
export function changePassword(body: PasswordChangeRequest) {
  return request<void>('/auth/password', {
    method: 'POST',
    body: JSON.stringify(body)
  });
}

/** 注销当前管理端会话。 */
export function logout() {
  return request<void>('/auth/logout', { method: 'POST' });
}
