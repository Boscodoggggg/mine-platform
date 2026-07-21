import type { AuthUser, LoginResponse } from '../api/auth';
import { localStg } from '@/utils/storage';

/** 保存 Sa-Token 令牌名称、令牌值和当前用户快照。 */
export function saveAuthSession(response: LoginResponse) {
  localStg.set('tokenName', response.tokenName);
  localStg.set('token', response.tokenValue);
  localStg.set('userProfile', response.user);
}

/** 清除所有认证相关的本地状态。 */
export function clearAuthSession() {
  localStg.remove('token');
  localStg.remove('tokenName');
  localStg.remove('userProfile');
  localStg.remove('refreshToken');
}

/** 返回上次由服务端确认的用户快照。 */
export function getStoredUser(): AuthUser | null {
  return localStg.get('userProfile');
}
