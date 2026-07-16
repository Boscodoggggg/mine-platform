import { request } from '@/api/client';
import type { components } from '@/generated/mine-api';

export type DictionaryStatus = components['schemas']['DictionaryType']['status'];
export type DictionarySemanticType = components['schemas']['SemanticType'];
export type DictionaryType = components['schemas']['DictionaryType'];
export type DictionaryItem = components['schemas']['DictionaryItem'];
export type DictionaryTypeCreatePayload = components['schemas']['DictionaryTypeCreateRequest'];
export type DictionaryItemCreatePayload = components['schemas']['DictionaryItemCreateRequest'];

/** 查询全部通用字典类型。 */
export function getDictionaryTypes() {
  return request<DictionaryType[]>('/system/dictionaries');
}

/** 创建通用字典类型。 */
export function createDictionaryType(payload: DictionaryTypeCreatePayload) {
  return request<DictionaryType>('/system/dictionaries', json('POST', payload));
}

/** 修改通用字典类型，稳定编码不可修改。 */
export function updateDictionaryType(typeId: string, payload: components['schemas']['DictionaryTypeUpdateRequest']) {
  return request<DictionaryType>(`/system/dictionaries/${typeId}`, json('PUT', payload));
}

/** 删除空的非内置字典类型。 */
export function deleteDictionaryType(typeId: string) {
  return request<void>(`/system/dictionaries/${typeId}`, { method: 'DELETE' });
}

/** 查询一个类型下的全部字典数据项。 */
export function getDictionaryItems(typeId: string) {
  return request<DictionaryItem[]>(`/system/dictionaries/${typeId}/items`);
}

/** 创建字典数据项。 */
export function createDictionaryItem(typeId: string, payload: DictionaryItemCreatePayload) {
  return request<DictionaryItem>(`/system/dictionaries/${typeId}/items`, json('POST', payload));
}

/** 修改字典数据项，稳定值不可修改。 */
export function updateDictionaryItem(itemId: string, payload: components['schemas']['DictionaryItemUpdateRequest']) {
  return request<DictionaryItem>(`/system/dictionaries/items/${itemId}`, json('PUT', payload));
}

/** 删除字典数据项。 */
export function deleteDictionaryItem(itemId: string) {
  return request<void>(`/system/dictionaries/items/${itemId}`, { method: 'DELETE' });
}

function json(method: string, body: unknown): RequestInit {
  return { method, body: JSON.stringify(body) };
}
