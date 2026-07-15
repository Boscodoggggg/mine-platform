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

export interface ProductQuery {
  keyword?: string;
  status?: ProductStatus | '';
  page?: number;
  size?: number;
}

export interface DeviceQuery {
  productId?: string;
  keyword?: string;
  status?: DeviceStatus | '';
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
