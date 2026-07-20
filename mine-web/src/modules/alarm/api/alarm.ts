import { request } from '@/api/client';
import type { components } from '@/generated/mine-api';

export type Alarm = components['schemas']['Alarm'];
export type AlarmPage = components['schemas']['AlarmPage'];
export type AlarmStatus = components['schemas']['AlarmStatus'];
export type AlarmSeverity = components['schemas']['AlarmSeverity'];
export type AlarmDispositionRequest = components['schemas']['AlarmDispositionRequest'];

export interface AlarmQuery {
  active?: boolean | null;
  closed?: boolean | null;
  status?: AlarmStatus | null;
  severity?: AlarmSeverity | null;
  deviceId?: string | null;
  deviceKeyword?: string | null;
  ruleKey?: string | null;
  from?: string | null;
  to?: string | null;
  page?: number;
  size?: number;
}

/** 组合筛选并分页查询告警周期。 */
export function getAlarms(query: AlarmQuery = {}, signal?: AbortSignal) {
  const path = `/alarms?${queryString(query)}`;
  return signal ? request<AlarmPage>(path, { signal }) : request<AlarmPage>(path);
}

/** 查询告警触发时归属指定矿区的车载设备告警。 */
export function getMineSiteAlarms(siteId: string, query: AlarmQuery = {}, signal?: AbortSignal) {
  const path = `/mine/sites/${encodeURIComponent(siteId)}/alarms?${queryString(query)}`;
  return signal ? request<AlarmPage>(path, { signal }) : request<AlarmPage>(path);
}

/** 按固定页大小连续读取矿区车载设备告警，直到达到图层上限或服务端总数。 */
export async function getMineSiteAlarmsUpTo(
  siteId: string,
  query: AlarmQuery,
  limit: number,
  signal?: AbortSignal
) {
  const normalizedLimit = Number.isInteger(limit) && limit > 0 ? Math.min(limit, 10_000) : 0;
  if (normalizedLimit === 0) return { items: [], page: 1, size: 0, total: 0 } satisfies AlarmPage;
  const { page: _ignoredPage, size: _ignoredSize, ...filters } = query;
  const pageSize = Math.min(normalizedLimit, 200);
  const items: Alarm[] = [];
  let page = 1;
  let total = 0;
  while (items.length < normalizedLimit) {
    const response = await getMineSiteAlarms(siteId, { ...filters, page, size: pageSize }, signal);
    total = response.total;
    items.push(...response.items.slice(0, normalizedLimit - items.length));
    if (response.items.length === 0 || items.length >= total || items.length >= normalizedLimit) break;
    page += 1;
  }
  return { items, page: 1, size: items.length, total } satisfies AlarmPage;
}

/** 查询告警的完整触发、恢复和处置事实。 */
export function getAlarm(alarmId: string) {
  return request<Alarm>(`/alarms/${encodeURIComponent(alarmId)}`);
}

/** 使用稳定幂等键记录首次确认事实。 */
export function acknowledgeAlarm(alarmId: string, body: AlarmDispositionRequest, idempotencyKey: string) {
  return request<Alarm>(`/alarms/${encodeURIComponent(alarmId)}/acknowledgements`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body)
  });
}

/** 使用稳定幂等键关闭已经恢复的告警。 */
export function closeAlarm(alarmId: string, body: AlarmDispositionRequest, idempotencyKey: string) {
  return request<Alarm>(`/alarms/${encodeURIComponent(alarmId)}/close`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body)
  });
}

function queryString(query: object) {
  const parameters = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') parameters.set(key, String(value));
  });
  return parameters.toString();
}
