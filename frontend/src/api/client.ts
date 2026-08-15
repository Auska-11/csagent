import type { ApiErrorPayload } from './types';

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code = 'HTTP_ERROR',
    readonly timestamp = Date.now(),
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

interface RequestOptions extends Omit<RequestInit, 'body'> {
  token?: string;
  body?: unknown;
}

async function readPayload(response: Response): Promise<unknown> {
  const contentType = response.headers.get('content-type') ?? '';
  if (response.status === 204) return undefined;
  if (contentType.includes('application/json')) return response.json();
  const text = await response.text();
  return text || undefined;
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { token, body, headers: customHeaders, ...requestInit } = options;
  const headers = new Headers(customHeaders);

  headers.set('Accept', 'application/json');
  if (body !== undefined) headers.set('Content-Type', 'application/json');
  if (token) headers.set('Authorization', `Bearer ${token}`);

  let response: Response;
  try {
    response = await fetch(path, {
      ...requestInit,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (cause) {
    if (cause instanceof Error && cause.name === 'AbortError') throw cause;
    throw new ApiError(cause instanceof Error ? cause.message : '无法连接服务', 0, 'NETWORK_ERROR');
  }

  const payload = await readPayload(response);
  if (!response.ok) {
    const error = payload as Partial<ApiErrorPayload> | undefined;
    const apiError = new ApiError(
      error?.message || `请求失败 (${response.status})`,
      response.status,
      error?.errorCode,
      error?.timestamp,
    );
    if (response.status === 401) {
      window.dispatchEvent(new CustomEvent('smartcs:unauthorized'));
    }
    throw apiError;
  }

  return payload as T;
}

export function toErrorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback;
}
