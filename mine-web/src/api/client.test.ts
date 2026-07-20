import { beforeEach, describe, expect, it, vi } from 'vitest';
import { localStg } from '@/utils/storage';
import { ApiError, getApiAuthHeaders, request, upload } from './client';

describe('平台 API 客户端', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
  });

  it('应直接返回成功 DTO 并携带 Sa-Token 请求头', async () => {
    localStg.set('tokenName', 'X-Mine-Token');
    localStg.set('token', 'token-value');
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ id: 'user-1', username: 'dispatcher' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    await expect(request<{ id: string }>('/system/users/user-1')).resolves.toEqual({
      id: 'user-1',
      username: 'dispatcher'
    });

    const [, init] = vi.mocked(fetch).mock.calls[0];
    expect(new Headers(init?.headers).get('X-Mine-Token')).toBe('token-value');
  });

  it('应向同源流式资源提供与 REST 一致的受控认证请求头', () => {
    localStg.set('tokenName', 'X-Mine-Token');
    localStg.set('token', 'scene-token');
    expect(getApiAuthHeaders()).toEqual({ 'X-Mine-Token': 'scene-token' });

    localStg.set('tokenName', 'Invalid Header');
    expect(getApiAuthHeaders()).toEqual({});
  });

  it('应把二零四响应解析为无返回值', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }));

    await expect(request<void>('/system/roles/role-1', { method: 'DELETE' })).resolves.toBeUndefined();
  });

  it('FormData 请求应由浏览器生成 multipart 边界而不是写死 JSON 类型', async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ id: 'asset-1' }), {
        status: 201,
        headers: { 'Content-Type': 'application/json' }
      })
    );
    const formData = new FormData();
    formData.set('file', new File(['glb'], 'mine.glb', { type: 'model/gltf-binary' }));

    await request('/mine/sites/site-1/scene-assets', { method: 'POST', body: formData });

    const [, init] = vi.mocked(fetch).mock.calls[0];
    expect(new Headers(init?.headers).has('Content-Type')).toBe(false);
  });

  it('应保留统一错误码链路号和状态码', async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response(
        JSON.stringify({
          code: 'SYSTEM-409-003',
          message: '数据已被其他管理员修改，请刷新后重试',
          traceId: 'trace-conflict-001',
          details: { roleId: 'role-1' }
        }),
        { status: 409, headers: { 'Content-Type': 'application/json' } }
      )
    );

    await expect(request('/system/roles/role-1')).rejects.toMatchObject({
      name: 'ApiError',
      code: 'SYSTEM-409-003',
      status: 409,
      traceId: 'trace-conflict-001',
      details: { roleId: 'role-1' }
    });
  });

  it('遇到四零一时应清除失效会话', async () => {
    localStg.set('tokenName', 'X-Mine-Token');
    localStg.set('token', 'expired-token');
    localStg.set('userProfile', {
      id: 'user-1',
      username: 'dispatcher',
      displayName: '调度员',
      passwordChangeRequired: false
    });
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify({ code: 'AUTH-401-002', message: '登录状态无效' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' }
      })
    );

    await expect(request('/auth/me')).rejects.toBeInstanceOf(ApiError);
    expect(localStg.get('token')).toBeNull();
    expect(localStg.get('tokenName')).toBeNull();
    expect(localStg.get('userProfile')).toBeNull();
  });

  it('应将非 JSON 错误和弱网断连转换为稳定错误', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(new Response('bad gateway', { status: 502 }));

    await expect(request('/system/users')).rejects.toMatchObject({
      code: 'HTTP-502',
      status: 502,
      message: '服务返回了无法识别的响应'
    });

    vi.mocked(fetch).mockRejectedValueOnce(new TypeError('Failed to fetch'));
    await expect(request('/system/users')).rejects.toMatchObject({
      code: 'SYS-NETWORK-001',
      status: 0,
      message: '无法连接矿区服务，请检查网络后重试'
    });
  });

  it('主动取消请求应返回稳定取消错误而不是伪装成弱网故障', async () => {
    vi.mocked(fetch).mockRejectedValueOnce(new DOMException('aborted', 'AbortError'));

    await expect(request('/mine/sites/site-1/scenes', { signal: AbortSignal.abort() })).rejects.toMatchObject({
      code: 'SYS-REQUEST-CANCELLED',
      status: 0
    });
  });

  it('上传应保留认证、报告真实进度并支持取消', async () => {
    const transport = new FakeXmlHttpRequest();
    vi.stubGlobal('XMLHttpRequest', function XMLHttpRequestMock() {
      return transport;
    });
    localStg.set('tokenName', 'X-Mine-Token');
    localStg.set('token', 'token-value');
    const progress = vi.fn();
    const controller = new AbortController();
    const body = new FormData();
    body.set('file', new File(['glb'], 'mine.glb'));

    const task = upload<{ id: string }>('/mine/sites/site-1/scene-assets', body, {
      signal: controller.signal,
      onProgress: progress
    });
    transport.emitProgress(512, 1024);
    transport.succeed({ id: 'asset-1' });

    await expect(task).resolves.toEqual({ id: 'asset-1' });
    expect(transport.open).toHaveBeenCalledWith('POST', '/api/v1/mine/sites/site-1/scene-assets', true);
    expect(transport.headers.get('X-Mine-Token')).toBe('token-value');
    expect(transport.headers.has('Content-Type')).toBe(false);
    expect(transport.withCredentials).toBe(true);
    expect(progress).toHaveBeenCalledWith({ loaded: 512, total: 1024, percent: 50 });
    expect(transport.body).toBe(body);

    const cancelledTransport = new FakeXmlHttpRequest();
    vi.stubGlobal('XMLHttpRequest', function XMLHttpRequestMock() {
      return cancelledTransport;
    });
    const cancelledController = new AbortController();
    const cancelled = upload('/mine/sites/site-1/scene-assets', body, { signal: cancelledController.signal });
    cancelledController.abort();
    await expect(cancelled).rejects.toMatchObject({ code: 'SYS-REQUEST-CANCELLED' });
    expect(cancelledTransport.abort).toHaveBeenCalledOnce();
  });
});

class FakeXmlHttpRequest {
  readonly headers = new Headers();
  readonly upload = {
    addEventListener: (_type: string, listener: EventListenerOrEventListenerObject) => {
      this.progressListener = eventListener(listener);
    }
  };
  readonly open = vi.fn();
  readonly abort = vi.fn(() => this.emit('abort'));
  withCredentials = false;
  responseType: XMLHttpRequestResponseType = '';
  status = 0;
  response: unknown = null;
  responseText = '';
  body: Document | XMLHttpRequestBodyInit | null = null;
  private readonly listeners = new Map<string, EventListener>();
  private progressListener: EventListener | null = null;

  addEventListener(type: string, listener: EventListenerOrEventListenerObject) {
    this.listeners.set(type, eventListener(listener));
  }

  setRequestHeader(name: string, value: string) {
    this.headers.set(name, value);
  }

  send(body: Document | XMLHttpRequestBodyInit | null) {
    this.body = body;
  }

  emitProgress(loaded: number, total: number) {
    this.progressListener?.({ loaded, total, lengthComputable: true } as ProgressEvent);
  }

  succeed(response: unknown) {
    this.status = 201;
    this.response = response;
    this.emit('load');
  }

  private emit(type: string) {
    this.listeners.get(type)?.(new ProgressEvent(type));
  }
}

function eventListener(listener: EventListenerOrEventListenerObject): EventListener {
  return typeof listener === 'function' ? listener : event => listener.handleEvent(event);
}
