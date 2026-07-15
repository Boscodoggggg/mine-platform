import { request } from '@/api/client';

export type UserStatus = 'ENABLED' | 'DISABLED' | 'LOCKED';
export type RoleStatus = 'ENABLED' | 'DISABLED';
export type MenuNodeType = 'DIRECTORY' | 'MENU' | 'BUTTON';

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface ManagedUser {
  id: string;
  username: string;
  displayName: string;
  status: UserStatus;
  passwordChangeRequired: boolean;
  lastLoginAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
  roleIds: string[];
}

export interface ManagedRole {
  id: string;
  code: string;
  name: string;
  status: RoleStatus;
  description: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
  menuIds: string[];
}

export interface ManagedMenuNode {
  id: string;
  parentId: string | null;
  name: string;
  type: MenuNodeType;
  path: string | null;
  componentKey: string | null;
  permission: string | null;
  icon: string | null;
  sortOrder: number;
  visible: boolean;
  status: RoleStatus;
  children: ManagedMenuNode[];
}

export interface UserQuery {
  keyword?: string;
  status?: UserStatus | '';
  page: number;
  size: number;
}

export interface RoleQuery {
  keyword?: string;
  status?: RoleStatus | '';
  page: number;
  size: number;
}

export interface UserCreatePayload {
  username: string;
  displayName: string;
  temporaryPassword: string;
  status: 'ENABLED' | 'DISABLED';
}

export interface UserUpdatePayload {
  displayName: string;
  status: 'ENABLED' | 'DISABLED';
  version: number;
}

export interface RoleCreatePayload {
  code: string;
  name: string;
  status: RoleStatus;
  description?: string;
}

export interface RoleUpdatePayload {
  name: string;
  status: RoleStatus;
  description?: string;
  version: number;
}

/** 分页查询系统用户。 */
export function getUsers(query: UserQuery) {
  return request<PageResponse<ManagedUser>>(`/system/users?${toQueryString(query)}`);
}

/** 创建普通系统用户。 */
export function createUser(payload: UserCreatePayload) {
  return request<ManagedUser>('/system/users', jsonRequest('POST', payload));
}

/** 修改用户资料和状态。 */
export function updateUser(userId: string, payload: UserUpdatePayload) {
  return request<ManagedUser>(`/system/users/${userId}`, jsonRequest('PUT', payload));
}

/** 原子替换用户角色。 */
export function replaceUserRoles(userId: string, roleIds: string[]) {
  return request<void>(`/system/users/${userId}/roles`, jsonRequest('PUT', { roleIds }));
}

/** 重置用户临时密码。 */
export function resetUserPassword(userId: string, temporaryPassword: string) {
  return request<void>(`/system/users/${userId}/password-reset`, jsonRequest('POST', { temporaryPassword }));
}

/** 分页查询系统角色。 */
export function getRoles(query: RoleQuery) {
  return request<PageResponse<ManagedRole>>(`/system/roles?${toQueryString(query)}`);
}

/** 创建角色。 */
export function createRole(payload: RoleCreatePayload) {
  return request<ManagedRole>('/system/roles', jsonRequest('POST', payload));
}

/** 修改角色。 */
export function updateRole(roleId: string, payload: RoleUpdatePayload) {
  return request<ManagedRole>(`/system/roles/${roleId}`, jsonRequest('PUT', payload));
}

/** 删除未被用户占用的角色。 */
export function deleteRole(roleId: string) {
  return request<void>(`/system/roles/${roleId}`, { method: 'DELETE' });
}

/** 原子替换角色授权节点。 */
export function replaceRoleMenus(roleId: string, menuIds: string[]) {
  return request<void>(`/system/roles/${roleId}/menus`, jsonRequest('PUT', { menuIds }));
}

/** 读取发布版本维护的完整只读权限树。 */
export function getMenuTree() {
  return request<ManagedMenuNode[]>('/system/menus/tree');
}

function jsonRequest(method: string, payload: unknown): RequestInit {
  return {
    method,
    body: JSON.stringify(payload)
  };
}

function toQueryString(query: UserQuery | RoleQuery) {
  const params = new URLSearchParams({
    page: String(query.page),
    size: String(query.size)
  });
  if (query.keyword?.trim()) params.set('keyword', query.keyword.trim());
  if (query.status) params.set('status', query.status);
  return params.toString();
}
