import { request } from '@/api/client';
import type { components } from '@/generated/mine-api';

export type Command = components['schemas']['Command'];
export type CommandStatus = components['schemas']['CommandStatus'];
export type CommandTemplate = components['schemas']['CommandTemplate'];
export type CommandTarget = components['schemas']['CommandTarget'];
export type CommandTargetPage = components['schemas']['CommandTargetPage'];
export type CommandPage = components['schemas']['CommandPage'];
export type CommandCreateRequest = components['schemas']['CommandCreateRequest'];
export type RawHexCommandRequest = components['schemas']['RawHexCommandRequest'];
export type CommandTemplateDefinition = components['schemas']['CommandTemplateDefinition'];
export type CommandTemplateDefinitionRequest = components['schemas']['CommandTemplateDefinitionRequest'];
export type CommandTemplateProduct = components['schemas']['CommandTemplateProduct'];

export interface CommandQuery {
  deviceId?: string | null;
  status?: CommandStatus | null;
  templateKey?: string | null;
  page?: number;
  size?: number;
}

export interface CommandTargetQuery {
  keyword?: string;
  page?: number;
  size?: number;
}

/** 查询已发布的命令模板。 */
export function getCommandTemplates(deviceId?: string | null) {
  const query = deviceId ? `?deviceId=${encodeURIComponent(deviceId)}` : '';
  return request<CommandTemplate[]>(`/command-templates${query}`);
}

/** 查询当前操作员可选择的启用命令目标设备。 */
export function getCommandTargets(query: CommandTargetQuery = {}) {
  return request<CommandTargetPage>(`/command-targets?${queryString(query)}`);
}

/** 分页查询命令实例。 */
export function getCommands(query: CommandQuery = {}) {
  return request<CommandPage>(`/commands?${queryString(query)}`);
}

/** 查询命令详情和时间线。 */
export function getCommand(commandId: string) {
  return request<Command>(`/commands/${encodeURIComponent(commandId)}`);
}

/** 使用幂等键创建并下发命令。 */
export function createCommand(body: CommandCreateRequest, idempotencyKey: string) {
  return request<Command>('/commands', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body)
  });
}

/** 使用独立权限幂等下发临时原始十六进制命令。 */
export function createRawHexCommand(body: RawHexCommandRequest, idempotencyKey: string) {
  return request<Command>('/commands/raw-hex', {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body)
  });
}

/** 查询全部自定义命令模板版本。 */
export function getCommandTemplateDefinitions() {
  return request<CommandTemplateDefinition[]>('/command-template-definitions');
}

/** 查询模板管理权限范围内可绑定的活动产品。 */
export function getCommandTemplateProducts() {
  return request<CommandTemplateProduct[]>('/command-template-definitions/products');
}

/** 创建自定义命令模板草稿。 */
export function createCommandTemplateDefinition(body: CommandTemplateDefinitionRequest) {
  return request<CommandTemplateDefinition>('/command-template-definitions', {
    method: 'POST',
    body: JSON.stringify(body)
  });
}

/** 修改尚未发布的自定义命令模板草稿。 */
export function updateCommandTemplateDefinition(versionId: string, body: CommandTemplateDefinitionRequest) {
  return request<CommandTemplateDefinition>(`/command-template-definitions/${encodeURIComponent(versionId)}`, {
    method: 'PUT',
    body: JSON.stringify(body)
  });
}

/** 从已发布版本复制下一版可编辑草稿。 */
export function createNextCommandTemplateDraft(versionId: string) {
  return request<CommandTemplateDefinition>(`/command-template-definitions/${encodeURIComponent(versionId)}/draft`, {
    method: 'POST'
  });
}

/** 将模板草稿发布为不可变版本。 */
export function publishCommandTemplateDefinition(versionId: string, version: number) {
  return request<CommandTemplateDefinition>(`/command-template-definitions/${encodeURIComponent(versionId)}/publish`, {
    method: 'POST',
    body: JSON.stringify({ version })
  });
}

/** 取消尚未开始发送的排队命令。 */
export function cancelCommand(commandId: string, version: number, reason: string) {
  return request<Command>(`/commands/${encodeURIComponent(commandId)}/cancel`, {
    method: 'POST',
    body: JSON.stringify({ version, reason })
  });
}

function queryString(query: object) {
  const parameters = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') parameters.set(key, String(value));
  });
  return parameters.toString();
}
