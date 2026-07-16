import { request } from '@/api/client';
import type { components } from '@/generated/mine-api';

export type Command = components['schemas']['Command'];
export type CommandStatus = components['schemas']['CommandStatus'];
export type CommandTemplate = components['schemas']['CommandTemplate'];
export type CommandTarget = components['schemas']['CommandTarget'];
export type CommandTargetPage = components['schemas']['CommandTargetPage'];
export type CommandPage = components['schemas']['CommandPage'];
export type CommandCreateRequest = components['schemas']['CommandCreateRequest'];

export interface CommandQuery {
  deviceId?: string;
  status?: CommandStatus | '';
  templateKey?: string;
  page?: number;
  size?: number;
}

export interface CommandTargetQuery {
  keyword?: string;
  page?: number;
  size?: number;
}

/** 查询已发布的命令模板。 */
export function getCommandTemplates() {
  return request<CommandTemplate[]>('/command-templates');
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
