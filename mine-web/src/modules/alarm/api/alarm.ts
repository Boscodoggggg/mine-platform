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
export function getAlarms(query: AlarmQuery = {}) {
  return request<AlarmPage>(`/alarms?${queryString(query)}`);
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
