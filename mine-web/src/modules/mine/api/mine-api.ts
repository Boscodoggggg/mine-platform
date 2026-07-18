import type { components } from '@/generated/mine-api';
import { request } from '@/api/client';

export type MineContextResponse = components['schemas']['MineContextResponse'];
export type MineOrganization = components['schemas']['MineOrganizationResponse'];
export type MineSite = components['schemas']['MineSiteResponse'];
export type MineOrganizationPage = components['schemas']['MineOrganizationPage'];
export type MineSitePage = components['schemas']['MineSitePage'];
export type MineOrganizationRequest = components['schemas']['MineOrganizationRequest'];
export type MineOrganizationUpdateRequest = components['schemas']['MineOrganizationUpdateRequest'];
export type MineSiteRequest = components['schemas']['MineSiteRequest'];
export type MineSiteUpdateRequest = components['schemas']['MineSiteUpdateRequest'];
export type MineScopeGrantRequest = components['schemas']['MineScopeGrantRequest'];
export type MineScopeGrant = components['schemas']['MineScopeGrantResponse'];
export type Vehicle = components['schemas']['VehicleResponse'];
export type VehiclePage = components['schemas']['VehiclePage'];
export type VehicleRequest = components['schemas']['VehicleRequest'];
export type VehicleUpdateRequest = components['schemas']['VehicleUpdateRequest'];
export type VehicleStatus = components['schemas']['VehicleStatus'];
export type LocationTag = components['schemas']['LocationTagResponse'];
export type LocationTagPage = components['schemas']['LocationTagPage'];
export type LocationTagRequest = components['schemas']['LocationTagRequest'];
export type LocationTagUpdateRequest = components['schemas']['LocationTagUpdateRequest'];
export type LocationTagStatus = components['schemas']['LocationTagStatus'];
export type CoordinateSystem = components['schemas']['CoordinateSystemResponse'];
export type CoordinateSystemPage = components['schemas']['CoordinateSystemPage'];
export type CoordinateSystemRequest = components['schemas']['CoordinateSystemRequest'];
export type CoordinateSystemUpdateRequest = components['schemas']['CoordinateSystemUpdateRequest'];
export type CoordinateTransform = components['schemas']['CoordinateTransformResponse'];
export type CoordinateTransformPage = components['schemas']['CoordinateTransformPage'];
export type CoordinateTransformRequest = components['schemas']['CoordinateTransformRequest'];
export type CoordinateTransformUpdateRequest = components['schemas']['CoordinateTransformUpdateRequest'];
export type Person = components['schemas']['PersonResponse'];
export type PersonPage = components['schemas']['PersonPage'];
export type PersonCreateRequest = components['schemas']['PersonCreateRequest'];
export type PersonUpdateRequest = components['schemas']['PersonUpdateRequest'];
export type PersonSensitive = components['schemas']['PersonSensitiveResponse'];
export type PersonSensitiveUpdateRequest = components['schemas']['PersonSensitiveUpdateRequest'];
export type DriverQualification = components['schemas']['DriverQualificationResponse'];
export type DriverQualificationRequest = components['schemas']['DriverQualificationRequest'];
export type BindingSwitchRequest = components['schemas']['BindingSwitchRequest'];
export type BindingPreview = components['schemas']['BindingPreviewResponse'];
export type CurrentLocation = components['schemas']['CurrentLocationResponse'];
export type LocationTrajectory = components['schemas']['LocationTrajectoryResponse'];
export type LocationSubjectType = components['schemas']['LocationSubjectType'];

export interface PageQuery<TStatus extends string = string> {
  keyword?: string;
  status?: TStatus | null;
  page?: number;
  size?: number;
}

export interface LocationTrajectoryQuery {
  subjectType: LocationSubjectType;
  subjectId: string;
  from: string;
  to: string;
  watermark?: number | null;
  cursor?: string | null;
  maxPoints?: number;
}

/** 从服务端获取当前会话实时授权的组织和矿区。 */
export function getMineContext() {
  return request<MineContextResponse>('/mine/context');
}

/** 分页查询当前用户可见组织。 */
export function getMineOrganizations(query: PageQuery<'ACTIVE' | 'ARCHIVED'> = {}) {
  return request<MineOrganizationPage>(withQuery('/mine/organizations', query));
}

/** 创建组织档案。 */
export function createMineOrganization(body: MineOrganizationRequest) {
  return request<MineOrganization>('/mine/organizations', { method: 'POST', body: JSON.stringify(body) });
}

/** 使用乐观锁修改组织档案。 */
export function updateMineOrganization(organizationId: string, body: MineOrganizationUpdateRequest) {
  return request<MineOrganization>(`/mine/organizations/${segment(organizationId)}`, {
    method: 'PATCH',
    body: JSON.stringify(body)
  });
}

/** 归档组织。 */
export function archiveMineOrganization(organizationId: string, version: number) {
  return request<void>(`/mine/organizations/${segment(organizationId)}/archive`, {
    method: 'POST',
    body: JSON.stringify({ version })
  });
}

/** 分页查询组织下的矿区。 */
export function getMineSites(organizationId: string, query: PageQuery<'ACTIVE' | 'ARCHIVED'> = {}) {
  return request<MineSitePage>(withQuery(`/mine/organizations/${segment(organizationId)}/sites`, query));
}

/** 创建矿区档案。 */
export function createMineSite(organizationId: string, body: MineSiteRequest) {
  return request<MineSite>(`/mine/organizations/${segment(organizationId)}/sites`, {
    method: 'POST',
    body: JSON.stringify(body)
  });
}

/** 使用乐观锁修改矿区档案。 */
export function updateMineSite(siteId: string, body: MineSiteUpdateRequest) {
  return request<MineSite>(`/mine/sites/${segment(siteId)}`, { method: 'PATCH', body: JSON.stringify(body) });
}

/** 归档矿区。 */
export function archiveMineSite(siteId: string, version: number) {
  return request<void>(`/mine/sites/${segment(siteId)}/archive`, {
    method: 'POST',
    body: JSON.stringify({ version })
  });
}

/** 查询组织数据范围授权。 */
export function getMineScopeGrants(organizationId: string) {
  return request<MineScopeGrant[]>(withQuery('/mine/scope-grants', { organizationId }));
}

/** 幂等新增数据范围授权。 */
export function grantMineScope(body: MineScopeGrantRequest) {
  return request<void>('/mine/scope-grants', { method: 'PUT', body: JSON.stringify(body) });
}

/** 幂等撤销数据范围授权。 */
export function revokeMineScope(body: MineScopeGrantRequest) {
  return request<void>('/mine/scope-grants', { method: 'DELETE', body: JSON.stringify(body) });
}

/** 分页查询矿区车辆。 */
export function getMineVehicles(siteId: string, query: PageQuery<VehicleStatus> = {}) {
  return request<VehiclePage>(withQuery(sitePath(siteId, 'vehicles'), query));
}

/** 创建车辆。 */
export function createMineVehicle(siteId: string, body: VehicleRequest) {
  return request<Vehicle>(sitePath(siteId, 'vehicles'), { method: 'POST', body: JSON.stringify(body) });
}

/** 使用乐观锁修改车辆。 */
export function updateMineVehicle(siteId: string, vehicleId: string, body: VehicleUpdateRequest) {
  return request<Vehicle>(`${sitePath(siteId, 'vehicles')}/${segment(vehicleId)}`, {
    method: 'PATCH',
    body: JSON.stringify(body)
  });
}

/** 归档车辆。 */
export function archiveMineVehicle(siteId: string, vehicleId: string, version: number) {
  return archiveResource(`${sitePath(siteId, 'vehicles')}/${segment(vehicleId)}`, version);
}

/** 分页查询定位标签。 */
export function getMineLocationTags(siteId: string, query: PageQuery<LocationTagStatus> = {}) {
  return request<LocationTagPage>(withQuery(sitePath(siteId, 'location-tags'), query));
}

/** 创建定位标签。 */
export function createMineLocationTag(siteId: string, body: LocationTagRequest) {
  return request<LocationTag>(sitePath(siteId, 'location-tags'), { method: 'POST', body: JSON.stringify(body) });
}

/** 使用乐观锁修改定位标签。 */
export function updateMineLocationTag(siteId: string, tagId: string, body: LocationTagUpdateRequest) {
  return request<LocationTag>(`${sitePath(siteId, 'location-tags')}/${segment(tagId)}`, {
    method: 'PATCH',
    body: JSON.stringify(body)
  });
}

/** 归档定位标签。 */
export function archiveMineLocationTag(siteId: string, tagId: string, version: number) {
  return archiveResource(`${sitePath(siteId, 'location-tags')}/${segment(tagId)}`, version);
}

/** 分页查询坐标系。 */
export function getMineCoordinateSystems(siteId: string, query: PageQuery<'ACTIVE' | 'ARCHIVED'> = {}) {
  return request<CoordinateSystemPage>(withQuery(sitePath(siteId, 'coordinate-systems'), query));
}

/** 创建坐标系。 */
export function createMineCoordinateSystem(siteId: string, body: CoordinateSystemRequest) {
  return request<CoordinateSystem>(sitePath(siteId, 'coordinate-systems'), {
    method: 'POST',
    body: JSON.stringify(body)
  });
}

/** 使用乐观锁修改坐标系。 */
export function updateMineCoordinateSystem(siteId: string, systemId: string, body: CoordinateSystemUpdateRequest) {
  return request<CoordinateSystem>(`${sitePath(siteId, 'coordinate-systems')}/${segment(systemId)}`, {
    method: 'PATCH',
    body: JSON.stringify(body)
  });
}

/** 归档坐标系但保留历史引用。 */
export function archiveMineCoordinateSystem(siteId: string, systemId: string, version: number) {
  return archiveResource(`${sitePath(siteId, 'coordinate-systems')}/${segment(systemId)}`, version);
}

/** 分页查询坐标转换版本。 */
export function getMineCoordinateTransforms(
  siteId: string,
  query: Omit<PageQuery<'DRAFT' | 'PUBLISHED'>, 'keyword'> = {}
) {
  return request<CoordinateTransformPage>(withQuery(sitePath(siteId, 'coordinate-transforms'), query));
}

/** 创建坐标转换草稿。 */
export function createMineCoordinateTransform(siteId: string, body: CoordinateTransformRequest) {
  return request<CoordinateTransform>(sitePath(siteId, 'coordinate-transforms'), {
    method: 'POST',
    body: JSON.stringify(body)
  });
}

/** 使用乐观锁修改坐标转换草稿。 */
export function updateMineCoordinateTransform(
  siteId: string,
  transformId: string,
  body: CoordinateTransformUpdateRequest
) {
  return request<CoordinateTransform>(`${sitePath(siteId, 'coordinate-transforms')}/${segment(transformId)}`, {
    method: 'PATCH',
    body: JSON.stringify(body)
  });
}

/** 发布并永久锁定坐标转换版本。 */
export function publishMineCoordinateTransform(siteId: string, transformId: string, version: number) {
  return request<CoordinateTransform>(
    `${sitePath(siteId, 'coordinate-transforms')}/${segment(transformId)}/publish`,
    { method: 'POST', body: JSON.stringify({ version }) }
  );
}

/** 分页查询脱敏人员档案。 */
export function getMinePeople(siteId: string, query: PageQuery<'ACTIVE' | 'INACTIVE' | 'ARCHIVED'> = {}) {
  return request<PersonPage>(withQuery(sitePath(siteId, 'people'), query));
}

/** 创建人员档案，敏感字段只在请求体中传输。 */
export function createMinePerson(siteId: string, body: PersonCreateRequest) {
  return request<Person>(sitePath(siteId, 'people'), { method: 'POST', body: JSON.stringify(body) });
}

/** 查询脱敏人员详情。 */
export function getMinePerson(siteId: string, personId: string) {
  return request<Person>(`${sitePath(siteId, 'people')}/${segment(personId)}`);
}

/** 使用乐观锁修改人员档案。 */
export function updateMinePerson(siteId: string, personId: string, body: PersonUpdateRequest) {
  return request<Person>(`${sitePath(siteId, 'people')}/${segment(personId)}`, {
    method: 'PATCH',
    body: JSON.stringify(body)
  });
}

/** 归档人员档案。 */
export function archiveMinePerson(siteId: string, personId: string, version: number) {
  return archiveResource(`${sitePath(siteId, 'people')}/${segment(personId)}`, version);
}

/** 使用独立权限读取人员敏感信息。 */
export function getMinePersonSensitive(siteId: string, personId: string) {
  return request<PersonSensitive>(`${sitePath(siteId, 'people')}/${segment(personId)}/sensitive`);
}

/** 使用独立权限修改人员敏感信息。 */
export function updateMinePersonSensitive(siteId: string, personId: string, body: PersonSensitiveUpdateRequest) {
  return request<Person>(`${sitePath(siteId, 'people')}/${segment(personId)}/sensitive`, {
    method: 'PATCH',
    body: JSON.stringify(body)
  });
}

/** 查询人员司机资质。 */
export function getMineDriverQualifications(siteId: string, personId: string) {
  return request<DriverQualification[]>(`${sitePath(siteId, 'people')}/${segment(personId)}/qualifications`);
}

/** 新增人员司机资质。 */
export function createMineDriverQualification(siteId: string, personId: string, body: DriverQualificationRequest) {
  return request<DriverQualification>(`${sitePath(siteId, 'people')}/${segment(personId)}/qualifications`, {
    method: 'POST',
    body: JSON.stringify(body)
  });
}

/** 使用乐观锁吊销人员司机资质。 */
export function revokeMineDriverQualification(
  siteId: string,
  personId: string,
  qualificationId: string,
  version: number
) {
  return request<void>(
    `${sitePath(siteId, 'people')}/${segment(personId)}/qualifications/${segment(qualificationId)}/revoke`,
    { method: 'POST', body: JSON.stringify({ version }) }
  );
}

/** 预览换绑的旧关系、资质和影响对象。 */
export function previewMineBinding(siteId: string, body: BindingSwitchRequest) {
  return request<BindingPreview>(`${sitePath(siteId, 'bindings')}/preview`, {
    method: 'POST',
    body: JSON.stringify(body)
  });
}

/** 使用稳定幂等键原子执行换绑。 */
export function switchMineBinding(siteId: string, body: BindingSwitchRequest, idempotencyKey: string) {
  return request<BindingPreview>(`${sitePath(siteId, 'bindings')}/switch`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body)
  });
}

/** 查询矿区当前位置快照。 */
export function getMineCurrentLocations(siteId: string, limit = 5000) {
  return request<CurrentLocation>(withQuery(`${sitePath(siteId, 'locations')}/current`, { limit }));
}

/** 查询标签、车辆或人员的有界历史轨迹。 */
export function getMineLocationTrajectory(siteId: string, query: LocationTrajectoryQuery) {
  return request<LocationTrajectory>(withQuery(`${sitePath(siteId, 'locations')}/trajectory`, query));
}

function archiveResource(path: string, version: number) {
  return request<void>(`${path}/archive`, { method: 'POST', body: JSON.stringify({ version }) });
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
