import { localStg } from '@/utils/storage';

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '');

export interface ApiErrorPayload {
  code?: string;
  message?: string;
  traceId?: string;
  details?: Record<string, unknown>;
}

export interface UploadProgress {
  loaded: number;
  total: number;
  percent: number | null;
}

export interface UploadOptions {
  signal?: AbortSignal;
  onProgress?: (progress: UploadProgress) => void;
}

/** 平台 API 返回的稳定错误。 */
export class ApiError extends Error {
  readonly code: string;
  readonly status: number;
  readonly traceId?: string;
  readonly details: Record<string, unknown>;

  constructor(payload: ApiErrorPayload, status = 0) {
    super(payload.message || '请求处理失败');
    this.name = 'ApiError';
    this.code = payload.code || 'SYS-UNKNOWN-001';
    this.status = status;
    this.traceId = payload.traceId;
    this.details = payload.details || {};
  }
}

/**
 * 请求平台 REST API。
 *
 * 成功响应直接返回 DTO，204 返回 undefined，失败统一抛出 ApiError。
 */
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (typeof init.body === 'string') {
    headers.set('Content-Type', 'application/json');
  }
  appendToken(headers);

  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      ...init,
      headers,
      cache: 'no-store',
      credentials: 'same-origin'
    });
  } catch (error) {
    if (isAbortError(error) || init.signal?.aborted) throw cancelledError();
    throw new ApiError(
      {
        code: 'SYS-NETWORK-001',
        message: '无法连接矿区服务，请检查网络后重试'
      },
      0
    );
  }

  if (!response.ok) {
    if (response.status === 401) {
      clearInvalidSession();
    }
    throw new ApiError(await readErrorPayload(response), response.status);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/** 使用浏览器上传通道提交表单，并报告真实字节进度。 */
export function upload<T>(path: string, body: FormData, options: UploadOptions = {}): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const transport = new XMLHttpRequest();
    const headers = new Headers({ Accept: 'application/json' });
    appendToken(headers);
    transport.open('POST', `${API_BASE_URL}${path}`, true);
    transport.withCredentials = true;
    transport.responseType = 'json';
    headers.forEach((value, name) => transport.setRequestHeader(name, value));

    const removeAbortListener = () => options.signal?.removeEventListener('abort', abortUpload);
    const abortUpload = () => transport.abort();
    transport.upload.addEventListener('progress', event => {
      const total = event.lengthComputable ? event.total : 0;
      options.onProgress?.({
        loaded: event.loaded,
        total,
        percent: total > 0 ? Math.min(100, Math.round((event.loaded / total) * 100)) : null
      });
    });
    transport.addEventListener('load', () => {
      removeAbortListener();
      if (transport.status >= 200 && transport.status < 300) {
        if (transport.status !== 204 && transport.response === null) {
          reject(new ApiError({ code: 'SYS-RESPONSE-INVALID', message: '服务返回了无法识别的响应' }, transport.status));
          return;
        }
        resolve((transport.status === 204 ? undefined : transport.response) as T);
        return;
      }
      if (transport.status === 401) clearInvalidSession();
      reject(new ApiError(readUploadErrorPayload(transport), transport.status));
    });
    transport.addEventListener('error', () => {
      removeAbortListener();
      reject(networkError());
    });
    transport.addEventListener('abort', () => {
      removeAbortListener();
      reject(cancelledError());
    });

    if (options.signal?.aborted) {
      transport.abort();
      return;
    }
    options.signal?.addEventListener('abort', abortUpload, { once: true });
    transport.send(body);
  });
}

function appendToken(headers: Headers) {
  const token = localStg.get('token');
  const tokenName = localStg.get('tokenName');
  if (token && tokenName && /^[A-Za-z0-9-]{1,64}$/.test(tokenName)) {
    headers.set(tokenName, token);
  }
}

function clearInvalidSession() {
  localStg.remove('token');
  localStg.remove('tokenName');
  localStg.remove('userProfile');
  localStg.remove('refreshToken');
}

async function readErrorPayload(response: Response): Promise<ApiErrorPayload> {
  try {
    return (await response.json()) as ApiErrorPayload;
  } catch {
    return {
      code: `HTTP-${response.status}`,
      message: '服务返回了无法识别的响应'
    };
  }
}

function readUploadErrorPayload(transport: XMLHttpRequest): ApiErrorPayload {
  if (transport.response && typeof transport.response === 'object') {
    return transport.response as ApiErrorPayload;
  }
  try {
    const responseText = transport.responseText;
    return JSON.parse(responseText) as ApiErrorPayload;
  } catch {
    return {
      code: `HTTP-${transport.status}`,
      message: '服务返回了无法识别的响应'
    };
  }
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError';
}

function cancelledError() {
  return new ApiError({ code: 'SYS-REQUEST-CANCELLED', message: '请求已取消' }, 0);
}

function networkError() {
  return new ApiError({ code: 'SYS-NETWORK-001', message: '无法连接矿区服务，请检查网络后重试' }, 0);
}
