import { request } from '@/api/client';
import type { components } from '@/generated/mine-api';

export type ProductStatus = components['schemas']['ProductStatus'];
export type DeviceStatus = components['schemas']['DeviceStatus'];
export type Product = components['schemas']['Product'];
export type Device = components['schemas']['Device'];
export type ProductPage = components['schemas']['ProductPage'];
export type DevicePage = components['schemas']['DevicePage'];
export type ProductCreateRequest = components['schemas']['ProductCreateRequest'];
export type ProductUpdateRequest = components['schemas']['ProductUpdateRequest'];
export type DeviceCreateRequest = components['schemas']['DeviceCreateRequest'];
export type DeviceUpdateRequest = components['schemas']['DeviceUpdateRequest'];
export type DeviceLatest = components['schemas']['DeviceLatest'];
export type TelemetryLatestItem = components['schemas']['TelemetryLatestItem'];
export type DeviceConnectionProfile = components['schemas']['DeviceConnectionProfile'];
export type DeviceConnectionProfileRequest = components['schemas']['DeviceConnectionProfileRequest'];
export type DeviceConnectionTestResult = components['schemas']['DeviceConnectionTestResult'];
export type DeviceAccessStatus = components['schemas']['DeviceAccessStatus'];
export type DeviceAccessTestResult = components['schemas']['DeviceAccessTestResult'];

export interface ProductQuery {
  keyword?: string;
  status?: ProductStatus | null;
  page?: number;
  size?: number;
}

export interface DeviceQuery {
  productId?: string | null;
  keyword?: string;
  status?: DeviceStatus | null;
  page?: number;
  size?: number;
}

/** 分页查询产品。 */
export function getProducts(query: ProductQuery = {}) {
  return request<ProductPage>(`/products?${queryString(query)}`);
}

/** 创建产品。 */
export function createProduct(body: ProductCreateRequest) {
  return request<Product>('/products', { method: 'POST', body: JSON.stringify(body) });
}

/** 修改产品。 */
export function updateProduct(productId: string, body: ProductUpdateRequest) {
  return request<Product>(`/products/${encodeURIComponent(productId)}`, {
    method: 'PUT',
    body: JSON.stringify(body)
  });
}

/** 归档产品。 */
export function archiveProduct(productId: string, version: number) {
  return request<void>(`/products/${encodeURIComponent(productId)}/archive`, {
    method: 'POST',
    body: JSON.stringify({ version })
  });
}

/** 分页查询设备。 */
export function getDevices(query: DeviceQuery = {}) {
  return request<DevicePage>(`/devices?${queryString(query)}`);
}

/** 创建设备。 */
export function createDevice(body: DeviceCreateRequest) {
  return request<Device>('/devices', { method: 'POST', body: JSON.stringify(body) });
}

/** 修改设备。 */
export function updateDevice(deviceId: string, body: DeviceUpdateRequest) {
  return request<Device>(`/devices/${encodeURIComponent(deviceId)}`, {
    method: 'PUT',
    body: JSON.stringify(body)
  });
}

/** 归档设备。 */
export function archiveDevice(deviceId: string, version: number) {
  return request<void>(`/devices/${encodeURIComponent(deviceId)}/archive`, {
    method: 'POST',
    body: JSON.stringify({ version })
  });
}

/** 查询设备网络接入配置和最近一次诊断结果。 */
export function getDeviceConnectionProfile(deviceId: string) {
  return request<DeviceConnectionProfile>(`/devices/${encodeURIComponent(deviceId)}/connection-profile`);
}

/** 创建或使用乐观锁修改设备网络接入配置。 */
export function saveDeviceConnectionProfile(deviceId: string, body: DeviceConnectionProfileRequest) {
  return request<DeviceConnectionProfile>(`/devices/${encodeURIComponent(deviceId)}/connection-profile`, {
    method: 'PUT',
    body: JSON.stringify(body)
  });
}

/** 执行配置、DNS、TCP、TLS 和设备确认分层诊断。 */
export function testDeviceConnectionProfile(deviceId: string) {
  return request<DeviceConnectionTestResult>(`/devices/${encodeURIComponent(deviceId)}/connection-profile/test`, {
    method: 'POST'
  });
}

/** 批量查询设备持续接入状态。 */
export function getDeviceAccessStatuses(deviceIds: readonly string[]) {
  const parameters = new URLSearchParams();
  deviceIds.forEach(deviceId => parameters.append('deviceIds', deviceId));
  return request<DeviceAccessStatus[]>(`/devices/access-status?${parameters}`);
}

/** 手动执行端点、传输、活动和数据四层接入检查。 */
export function testDeviceAccessStatus(deviceId: string) {
  return request<DeviceAccessTestResult>(`/devices/${encodeURIComponent(deviceId)}/access-status/test`, {
    method: 'POST'
  });
}

/** 查询设备指定属性的最新遥测快照。 */
export function getDeviceLatest(deviceId: string, propertyIds: readonly string[]) {
  const parameters = new URLSearchParams();
  propertyIds.forEach(propertyId => parameters.append('propertyIds', propertyId));
  return request<DeviceLatest>(`/devices/${encodeURIComponent(deviceId)}/latest?${parameters}`);
}

function queryString(query: object) {
  const parameters = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') parameters.set(key, String(value));
  });
  return parameters.toString();
}
