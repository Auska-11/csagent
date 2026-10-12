import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authApi } from '../../api/auth';
import { ApiError } from '../../api/client';
import { AuthProvider, useAuth } from './AuthProvider';

describe('seller login', () => {
  let root: Root;
  let container: HTMLDivElement;
  let auth: ReturnType<typeof useAuth>;
  const seller = () => ({ userId: 'seller', name: '卖家', roles: ['ADMIN' as const],
    tenantId: 'xianyu', channel: 'xianyu', expiresAt: Date.now() + 3600_000 });
  function Probe() { auth = useAuth(); return null; }

  beforeEach(() => {
    Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    vi.spyOn(authApi, 'issueDemoTokens').mockRejectedValue(new ApiError('disabled', 404));
    vi.spyOn(authApi, 'me').mockImplementation(async () => seller());
  });
  afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    vi.restoreAllMocks();
  });

  it('supports a verified seller token with the demo issuer disabled and aborts the old request scope', async () => {
    await act(async () => root.render(<AuthProvider><Probe /></AuthProvider>));
    expect(auth.identity).toBeNull();
    const oldScope = auth.requestSignal;
    let accepted = false;
    await act(async () => { accepted = await auth.loginWithToken(' seller-token '); });
    expect(accepted).toBe(true);
    expect(oldScope.aborted).toBe(true);
    expect(auth.identity?.channel).toBe('xianyu');
    expect(auth.identity?.token).toBe('seller-token');
    expect(auth.hasRole('ADMIN')).toBe(true);
    expect(localStorage.length).toBe(0);
  });

  it('clears an invalid seller session without falling back to a demo identity', async () => {
    await act(async () => root.render(<AuthProvider><Probe /></AuthProvider>));
    await act(async () => { await auth.loginWithToken('seller-token'); });
    const sellerScope = auth.requestSignal;
    vi.mocked(authApi.me).mockRejectedValue(new ApiError('expired', 401));
    await act(async () => { await auth.refresh(); });
    expect(auth.identity).toBeNull();
    expect(sellerScope.aborted).toBe(true);
    expect(authApi.issueDemoTokens).toHaveBeenCalledTimes(1);
  });
});
