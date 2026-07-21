import { request } from '@/api/client';
import type { components } from '@/generated/mine-api';

export type NotificationChannel = components['schemas']['NotificationChannelResponse'];
export type NotificationChannelRequest = components['schemas']['NotificationChannelRequest'];
export type NotificationChannelSecrets = components['schemas']['NotificationChannelSecretsResponse'];
export type NotificationProviderDefinition = components['schemas']['NotificationProviderDefinition'];
export type NotificationProviderField = components['schemas']['NotificationProviderField'];
export type NotificationRecipientGroup = components['schemas']['NotificationRecipientGroupResponse'];
export type NotificationRecipientGroupRequest = components['schemas']['NotificationRecipientGroupRequest'];
export type NotificationRecipient = components['schemas']['NotificationRecipientResponse'];
export type NotificationRecipientRequest = components['schemas']['NotificationRecipientRequest'];
export type NotificationRecipientTarget = components['schemas']['NotificationRecipientTargetResponse'];
export type NotificationTemplate = components['schemas']['NotificationTemplateResponse'];
export type NotificationTemplateRequest = components['schemas']['NotificationTemplateRequest'];
export type NotificationPolicy = components['schemas']['NotificationPolicyResponse'];
export type NotificationPolicyRequest = components['schemas']['NotificationPolicyRequest'];
export type NotificationPolicyActionRequest = components['schemas']['NotificationPolicyActionRequest'];
export type NotificationPolicyPublishRequest = components['schemas']['NotificationPolicyPublishRequest'];

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

/** 查询全部通知接收组。 */
export function getNotificationRecipientGroups() {
  return request<NotificationRecipientGroup[]>('/notifications/recipient-groups');
}

/** 创建通知接收组。 */
export function createNotificationRecipientGroup(body: NotificationRecipientGroupRequest) {
  return request<NotificationRecipientGroup>('/notifications/recipient-groups', jsonBody('POST', body));
}

/** 使用乐观锁修改通知接收组。 */
export function updateNotificationRecipientGroup(groupId: string, body: NotificationRecipientGroupRequest) {
  return request<NotificationRecipientGroup>(
    `/notifications/recipient-groups/${segment(groupId)}`,
    jsonBody('PUT', body)
  );
}

/** 查询指定接收组内的脱敏接收对象。 */
export function getNotificationRecipients(groupId: string) {
  return request<NotificationRecipient[]>(`/notifications/recipient-groups/${segment(groupId)}/recipients`);
}

/** 在指定接收组内创建加密接收对象。 */
export function createNotificationRecipient(groupId: string, body: NotificationRecipientRequest) {
  return request<NotificationRecipient>(
    `/notifications/recipient-groups/${segment(groupId)}/recipients`,
    jsonBody('POST', body)
  );
}

/** 使用乐观锁修改接收对象。 */
export function updateNotificationRecipient(
  groupId: string,
  recipientId: string,
  body: NotificationRecipientRequest
) {
  return request<NotificationRecipient>(
    `/notifications/recipient-groups/${segment(groupId)}/recipients/${segment(recipientId)}`,
    jsonBody('PUT', body)
  );
}

/** 使用独立敏感权限读取接收目标明文。 */
export function revealNotificationRecipientTarget(groupId: string, recipientId: string) {
  return request<NotificationRecipientTarget>(
    `/notifications/recipient-groups/${segment(groupId)}/recipients/${segment(recipientId)}/target`
  );
}

/** 查询全部安全通知模板。 */
export function getNotificationTemplates() {
  return request<NotificationTemplate[]>('/notifications/templates');
}

/** 查询平台固定模板变量目录。 */
export function getNotificationTemplateVariables() {
  return request<string[]>('/notifications/templates/variables');
}

/** 创建安全通知模板。 */
export function createNotificationTemplate(body: NotificationTemplateRequest) {
  return request<NotificationTemplate>('/notifications/templates', jsonBody('POST', body));
}

/** 使用乐观锁修改安全通知模板。 */
export function updateNotificationTemplate(templateId: string, body: NotificationTemplateRequest) {
  return request<NotificationTemplate>(`/notifications/templates/${segment(templateId)}`, jsonBody('PUT', body));
}

/** 查询全部通知升级策略。 */
export function getNotificationPolicies() {
  return request<NotificationPolicy[]>('/notifications/policies');
}

/** 创建通知升级策略及第一版草稿。 */
export function createNotificationPolicy(body: NotificationPolicyRequest) {
  return request<NotificationPolicy>('/notifications/policies', jsonBody('POST', body));
}

/** 整体修改当前通知升级策略草稿。 */
export function updateNotificationPolicyDraft(policyId: string, body: NotificationPolicyRequest) {
  return request<NotificationPolicy>(`/notifications/policies/${segment(policyId)}/draft`, jsonBody('PUT', body));
}

/** 从当前已发布快照派生新草稿。 */
export function createNotificationPolicyDraft(policyId: string, body: NotificationPolicyActionRequest) {
  return request<NotificationPolicy>(`/notifications/policies/${segment(policyId)}/drafts`, jsonBody('POST', body));
}

/** 发布不可变通知升级策略快照。 */
export function publishNotificationPolicy(policyId: string, body: NotificationPolicyPublishRequest) {
  return request<NotificationPolicy>(`/notifications/policies/${segment(policyId)}/publish`, jsonBody('POST', body));
}

/** 归档通知升级策略。 */
export function archiveNotificationPolicy(policyId: string, body: NotificationPolicyActionRequest) {
  return request<NotificationPolicy>(`/notifications/policies/${segment(policyId)}/archive`, jsonBody('POST', body));
}

function segment(value: string) {
  return encodeURIComponent(value);
}

function jsonBody(method: 'POST' | 'PUT', body: unknown) {
  return { method, body: JSON.stringify(body) };
}
