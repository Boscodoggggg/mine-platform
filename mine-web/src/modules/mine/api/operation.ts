import type { components } from '@/generated/mine-api';
import { request } from '@/api/client';

export type ShiftDefinition = components['schemas']['ShiftDefinitionResponse'];
export type ShiftDefinitionRequest = components['schemas']['ShiftDefinitionRequest'];
export type ShiftInstance = components['schemas']['ShiftInstanceResponse'];
export type ShiftInstanceRequest = components['schemas']['ShiftInstanceRequest'];
export type WorkOrder = components['schemas']['WorkOrderResponse'];
export type WorkOrderRequest = components['schemas']['WorkOrderRequest'];
export type WorkOrderAssignmentRequest = components['schemas']['WorkOrderAssignmentRequest'];
export type WorkOrderActionRequest = components['schemas']['WorkOrderActionRequest'];
export type OperationCandidate = components['schemas']['OperationCandidateResponse'];
export type OperationCandidateResolutionRequest = components['schemas']['OperationCandidateResolutionRequest'];
export type OperationRecord = components['schemas']['OperationRecordResponse'];
export type OperationRecordPage = components['schemas']['OperationRecordPageResponse'];
export type OperationRecordItem = components['schemas']['OperationRecordPageItem'];
export type OperationCorrectionRequest = components['schemas']['OperationCorrectionRequest'];
export type OperationSummary = components['schemas']['OperationSummaryResponse'];

export interface ShiftInstanceQuery {
  fromDate: string;
  toDate: string;
  status?: ShiftInstance['status'] | null;
  offset?: number;
  limit?: number;
}

export interface WorkOrderQuery {
  shiftInstanceId?: string | null;
  status?: WorkOrder['status'] | null;
  offset?: number;
  limit?: number;
}

export interface OperationCandidateQuery {
  status?: OperationCandidate['status'] | null;
  offset?: number;
  limit?: number;
}

export interface OperationRecordQuery {
  fromDate: string;
  toDate: string;
  watermark?: number | null;
  cursor?: string | null;
  shiftInstanceId?: string | null;
  workOrderId?: string | null;
  vehicleId?: string | null;
  driverPersonId?: string | null;
  recordStatus?: OperationRecordItem['recordStatus'] | null;
  pageSize?: number;
}

export interface OperationSummaryQuery {
  fromDate: string;
  toDate: string;
}

/** 查询活动班次模板。 */
export function getShiftDefinitions(siteId: string) {
  return request<ShiftDefinition[]>(withQuery(sitePath(siteId, 'shift-definitions'), { status: 'ACTIVE', limit: 100 }));
}

/** 查询营业日期窗口内的班次实例。 */
export function getShiftInstances(siteId: string, query: ShiftInstanceQuery, signal?: AbortSignal) {
  const path = withQuery(sitePath(siteId, 'shift-instances'), query);
  return signal ? request<ShiftInstance[]>(path, { signal }) : request<ShiftInstance[]>(path);
}

/** 根据有效模板创建计划班次实例。 */
export function createShiftInstance(siteId: string, body: ShiftInstanceRequest) {
  return request<ShiftInstance>(sitePath(siteId, 'shift-instances'), {
    method: 'POST',
    body: JSON.stringify(body)
  });
}

/** 幂等开启班次。 */
export function openShiftInstance(siteId: string, shiftId: string, version: number) {
  return shiftAction(siteId, shiftId, 'open', version);
}

/** 幂等关闭班次并完成关账。 */
export function closeShiftInstance(siteId: string, shiftId: string, version: number) {
  return shiftAction(siteId, shiftId, 'close', version);
}

/** 查询运输作业单和关系化调度分配。 */
export function getWorkOrders(siteId: string, query: WorkOrderQuery = {}, signal?: AbortSignal) {
  const path = withQuery(sitePath(siteId, 'work-orders'), { ...query, limit: query.limit ?? 100 });
  return signal ? request<WorkOrder[]>(path, { signal }) : request<WorkOrder[]>(path);
}

/** 创建一张可编辑的运输作业单草稿。 */
export function createWorkOrder(siteId: string, body: WorkOrderRequest) {
  return request<WorkOrder>(sitePath(siteId, 'work-orders'), {
    method: 'POST',
    body: JSON.stringify(body)
  });
}

/** 向作业单追加经资质和时间冲突校验的调度分配。 */
export function assignWorkOrder(
  siteId: string,
  workOrderId: string,
  body: WorkOrderAssignmentRequest
) {
  return request<WorkOrder>(`${sitePath(siteId, 'work-orders')}/${segment(workOrderId)}/assignments`, {
    method: 'POST',
    body: JSON.stringify(body)
  });
}

/** 幂等发布并冻结空间版本。 */
export function releaseWorkOrder(siteId: string, workOrderId: string, body: WorkOrderActionRequest) {
  return workOrderAction(siteId, workOrderId, 'release', body);
}

/** 幂等启动已发布作业。 */
export function startWorkOrder(siteId: string, workOrderId: string, version: number) {
  return workOrderAction(siteId, workOrderId, 'start', { version });
}

/** 幂等暂停运行作业。 */
export function pauseWorkOrder(siteId: string, workOrderId: string, version: number) {
  return workOrderAction(siteId, workOrderId, 'pause', { version });
}

/** 幂等恢复暂停作业。 */
export function resumeWorkOrder(siteId: string, workOrderId: string, version: number) {
  return workOrderAction(siteId, workOrderId, 'resume', { version });
}

/** 幂等完成空闲边界上的作业。 */
export function completeWorkOrder(siteId: string, workOrderId: string, version: number) {
  return workOrderAction(siteId, workOrderId, 'complete', { version });
}

/** 幂等异常终止作业并固化未完成循环。 */
export function abortWorkOrder(siteId: string, workOrderId: string, version: number, reason: string) {
  return workOrderAction(siteId, workOrderId, 'abort', { version, reason });
}

/** 查询运营候选处置队列。 */
export function getOperationCandidates(siteId: string, query: OperationCandidateQuery = {}) {
  return request<OperationCandidate[]>(
    withQuery(sitePath(siteId, 'operation-candidates'), { status: query.status ?? 'PENDING', ...query, limit: query.limit ?? 100 })
  );
}

/** 幂等处置一条运营候选。 */
export function resolveOperationCandidate(
  siteId: string,
  candidateId: string,
  body: OperationCandidateResolutionRequest
) {
  return request<OperationCandidate>(
    `${sitePath(siteId, 'operation-candidates')}/${segment(candidateId)}/resolve`,
    mutation('resolve-candidate', candidateId, body)
  );
}

/** 使用固定水位和不透明游标查询最新有效记录。 */
export function getOperationRecords(siteId: string, query: OperationRecordQuery) {
  return request<OperationRecordPage>(withQuery(sitePath(siteId, 'operation-records'), query));
}

/** 查询执行基础事实、冻结快照和修正历史。 */
export function getOperationRecord(siteId: string, recordId: string) {
  return request<OperationRecord>(`${sitePath(siteId, 'operation-records')}/${segment(recordId)}`);
}

/** 追加修正、作废或恢复事实。 */
export function correctOperationRecord(
  siteId: string,
  recordId: string,
  body: OperationCorrectionRequest
) {
  return request<OperationRecord>(
    `${sitePath(siteId, 'operation-records')}/${segment(recordId)}/corrections`,
    mutation('correct-record', recordId, body)
  );
}

/** 查询有界营业日期窗口的运营汇总。 */
export function getOperationSummary(siteId: string, query: OperationSummaryQuery) {
  return request<OperationSummary>(withQuery(sitePath(siteId, 'operation-summary'), query));
}

function shiftAction(siteId: string, shiftId: string, action: 'open' | 'close', version: number) {
  return request<ShiftInstance>(
    `${sitePath(siteId, 'shift-instances')}/${segment(shiftId)}/${action}`,
    mutation(`${action}-shift`, shiftId, { version })
  );
}

function workOrderAction(
  siteId: string,
  workOrderId: string,
  action: 'release' | 'start' | 'pause' | 'resume' | 'complete' | 'abort',
  body: WorkOrderActionRequest
) {
  return request<WorkOrder>(
    `${sitePath(siteId, 'work-orders')}/${segment(workOrderId)}/${action}`,
    mutation(`${action}-work-order`, workOrderId, body)
  );
}

function mutation(action: string, aggregateId: string, body: object): RequestInit {
  return {
    method: 'POST',
    headers: { 'Idempotency-Key': createIdempotencyKey(action, aggregateId) },
    body: JSON.stringify(body)
  };
}

function createIdempotencyKey(action: string, aggregateId: string) {
  return `web-${action}-${aggregateId}-${crypto.randomUUID()}`;
}

function sitePath(siteId: string, resource: string) {
  return `/mine/sites/${segment(siteId)}/${resource}`;
}

function segment(value: string) {
  return encodeURIComponent(value);
}

function withQuery(path: string, query: object) {
  const parameters = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') parameters.set(key, String(value));
  });
  const queryString = parameters.toString();
  return queryString ? `${path}?${queryString}` : path;
}
