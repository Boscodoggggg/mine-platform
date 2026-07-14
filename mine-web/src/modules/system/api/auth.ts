import { localStg } from '@/utils/storage';

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '');

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
  children: NavigationMenuNode[];
}

/** 当前用户的导航与操作权限。 */
export interface NavigationResponse {
  roles: string[];
  permissions: string[];
  menus: NavigationMenuNode[];
}

interface ApiErrorPayload {
  code?: string;
  message?: string;
  traceId?: string;
  details?: Record<string, unknown>;
}

/** 平台 API 返回的稳定错误。 */
export class ApiError extends Error {
  readonly code: string;
  readonly traceId?: string;
  readonly details: Record<string, unknown>;

  constructor(payload: ApiErrorPayload) {
    super(payload.message || '请求处理失败');
    this.name = 'ApiError';
    this.code = payload.code || 'SYS-UNKNOWN-001';
    this.traceId = payload.traceId;
    this.details = payload.details || {};
  }
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

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body) {
    headers.set('Content-Type', 'application/json');
  }
  const token = localStg.get('token');
  const tokenName = localStg.get('tokenName');
  if (token && tokenName && /^[A-Za-z0-9-]{1,64}$/.test(tokenName)) {
    headers.set(tokenName, token);
  }

  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      ...init,
      headers,
      cache: 'no-store',
      credentials: 'same-origin'
    });
  } catch {
    throw new ApiError({
      code: 'SYS-NETWORK-001',
      message: '无法连接矿区服务，请检查网络后重试'
    });
  }

  if (!response.ok) {
    const payload = await readErrorPayload(response);
    throw new ApiError(payload);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

async function readErrorPayload(response: Response): Promise<ApiErrorPayload> {
  try {
    return (await response.json()) as ApiErrorPayload;
  } catch {
    return {
      code: `HTTP-${response.status}`,
      message: '服务返回了无法识别的响应'
    };
  }
}
