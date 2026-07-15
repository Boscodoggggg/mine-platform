import { beforeEach, describe, expect, it, vi } from 'vitest';
import { localStg } from '@/utils/storage';
import { ApiError, request } from './client';

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

  it('应把二零四响应解析为无返回值', async () => {
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 204 }));

    await expect(request<void>('/system/roles/role-1', { method: 'DELETE' })).resolves.toBeUndefined();
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
});
