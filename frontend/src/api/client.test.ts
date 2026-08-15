import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiRequest } from './client';

describe('apiRequest', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('injects bearer token and JSON body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(apiRequest('/api/test', { method: 'POST', token: 'token-1', body: { value: 2 } })).resolves.toEqual({ ok: true });
    const init = fetchMock.mock.calls[0]![1] as RequestInit;
    const headers = init.headers as Headers;
    expect(headers.get('Authorization')).toBe('Bearer token-1');
    expect(init.body).toBe('{"value":2}');
  });

  it('maps structured errors and emits unauthorized without replaying', async () => {
    const unauthorized = vi.fn();
    window.addEventListener('smartcs:unauthorized', unauthorized);
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      errorCode: 'AUTHENTICATION_FAILED', message: 'expired', timestamp: 123,
    }), { status: 401, headers: { 'content-type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(apiRequest('/api/test', { method: 'POST' })).rejects.toMatchObject({
      status: 401, code: 'AUTHENTICATION_FAILED', message: 'expired', timestamp: 123,
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(unauthorized).toHaveBeenCalledTimes(1);
    window.removeEventListener('smartcs:unauthorized', unauthorized);
  });

  it('handles empty successful responses', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    await expect(apiRequest('/api/empty')).resolves.toBeUndefined();
  });
});
