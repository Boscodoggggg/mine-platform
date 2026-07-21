import { request } from '@/api/client';
import type { components } from '@/generated/mine-api';

export type NotificationChannel = components['schemas']['NotificationChannelResponse'];
export type NotificationChannelRequest = components['schemas']['NotificationChannelRequest'];
export type NotificationChannelSecrets = components['schemas']['NotificationChannelSecretsResponse'];
export type NotificationProviderDefinition = components['schemas']['NotificationProviderDefinition'];
export type NotificationProviderField = components['schemas']['NotificationProviderField'];

/** 查询平台随版本发布的全部内置通知 Provider。 */
export function getNotificationProviders() {
  return request<NotificationProviderDefinition[]>('/notifications/providers');
}

/** 查询全部数据库通知渠道安全摘要。 */
export function getNotificationChannels() {
  return request<NotificationChannel[]>('/notifications/channels');
}

/** 查询指定通知渠道安全详情。 */
export function getNotificationChannel(channelId: string) {
  return request<NotificationChannel>(`/notifications/channels/${encodeURIComponent(channelId)}`);
}

/** 使用独立敏感权限读取指定渠道的真实凭据。 */
export function revealNotificationChannelSecrets(channelId: string) {
  return request<NotificationChannelSecrets>(
    `/notifications/channels/${encodeURIComponent(channelId)}/secrets`
  );
}

/** 创建数据库通知渠道。 */
export function createNotificationChannel(body: NotificationChannelRequest) {
  return request<NotificationChannel>('/notifications/channels', {
    method: 'POST',
    body: JSON.stringify(body)
  });
}

/** 使用乐观锁更新指定通知渠道。 */
export function updateNotificationChannel(channelId: string, body: NotificationChannelRequest) {
  return request<NotificationChannel>(`/notifications/channels/${encodeURIComponent(channelId)}`, {
    method: 'PUT',
    body: JSON.stringify(body)
  });
}
