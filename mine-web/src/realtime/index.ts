import { localStg } from '@/utils/storage';
import { RealtimeHub, buildRealtimeUrl } from './RealtimeHub';

/** 实时令牌失效后通知应用认证层的浏览器事件。 */
export const REALTIME_AUTHENTICATION_EXPIRED_EVENT = 'mine:authentication-expired';

/** 全站共享的单一实时连接管理器。 */
export const realtimeHub = new RealtimeHub({
  url: buildRealtimeUrl(
    import.meta.env.VITE_API_BASE_URL || import.meta.env.VITE_SERVICE_BASE_URL,
    window.location.origin
  ),
  token: () => localStg.get('token') ?? '',
  onAuthenticationFailed: message => {
    localStg.remove('token');
    localStg.remove('tokenName');
    localStg.remove('userProfile');
    localStg.remove('refreshToken');
    window.dispatchEvent(new CustomEvent(REALTIME_AUTHENTICATION_EXPIRED_EVENT, { detail: { message } }));
  }
});

export * from './RealtimeHub';
