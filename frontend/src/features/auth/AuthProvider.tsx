import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { authApi } from '../../api/auth';
import { ApiError, toErrorMessage } from '../../api/client';
import type { DemoIdentity, Role } from '../../api/types';

interface AuthContextValue {
  identities: DemoIdentity[];
  identity: DemoIdentity | null;
  loading: boolean;
  error: string | null;
  generation: number;
  requestSignal: AbortSignal;
  selectIdentity: (userId: string) => void;
  refresh: () => Promise<void>;
  loginWithToken: (token: string) => Promise<boolean>;
  hasRole: (role: Role) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function isIdentityExpired(identity: DemoIdentity, now = Date.now()) {
  return identity.expiresAt <= now + 30_000;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [identities, setIdentities] = useState<DemoIdentity[]>([]);
  const [selectedUserId, setSelectedUserId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshRetryAt, setRefreshRetryAt] = useState(0);
  const [generation, setGeneration] = useState(0);
  const requestController = useRef(new AbortController());
  const identitiesRef = useRef<DemoIdentity[]>([]);
  const selectedUserIdRef = useRef<string | null>(null);
  const externalToken = useRef<string | null>(null);
  const refreshPromise = useRef<Promise<void> | null>(null);

  const resetRequestScope = useCallback(() => {
    requestController.current.abort();
    requestController.current = new AbortController();
    setGeneration((current) => current + 1);
  }, []);

  const refresh = useCallback(async () => {
    if (refreshPromise.current) return refreshPromise.current;

    const hadSession = Boolean(selectedUserIdRef.current && identitiesRef.current.length);
    if (!hadSession) setLoading(true);
    setError(null);
    const request = (async () => {
      try {
        const token = externalToken.current;
        const nextIdentities = token ? [{ ...(await authApi.me(token)), token }] : await authApi.issueDemoTokens();
        if (!nextIdentities.length) throw new Error('可用身份列表为空');
        if (token && nextIdentities[0]!.expiresAt <= Date.now()) throw new Error('凭据已过期，请重新登录');
        const currentUserId = selectedUserIdRef.current;
        const nextUserId = currentUserId && nextIdentities.some((item) => item.userId === currentUserId)
          ? currentUserId
          : nextIdentities[0]!.userId;
        const identityChanged = nextUserId !== currentUserId;
        if (identityChanged) resetRequestScope();
        identitiesRef.current = nextIdentities;
        selectedUserIdRef.current = nextUserId;
        setIdentities(nextIdentities);
        setSelectedUserId(nextUserId);
        setRefreshRetryAt(0);
      } catch (requestError) {
        if (!hadSession || externalToken.current) {
          resetRequestScope();
          identitiesRef.current = [];
          selectedUserIdRef.current = null;
          setIdentities([]);
          setSelectedUserId(null);
        }
        setError(externalToken.current ? toErrorMessage(requestError, '凭据无效或已过期，请重新登录') : requestError instanceof ApiError && requestError.status === 404
          ? '本地 Demo 登录未启用，需要接入真实身份系统'
          : toErrorMessage(requestError, '无法取得 Demo 登录凭据'));
        if (hadSession && !externalToken.current && !(requestError instanceof ApiError && requestError.status === 404)) {
          setRefreshRetryAt(Date.now() + 10_000);
        }
      } finally {
        setLoading(false);
      }
    })();
    refreshPromise.current = request;
    try {
      await request;
    } finally {
      if (refreshPromise.current === request) refreshPromise.current = null;
    }
  }, [resetRequestScope]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const identity = identities.find((item) => item.userId === selectedUserId) ?? null;

  useEffect(() => {
    if (!identity) return;
    const refreshIn = externalToken.current ? Math.max(0, identity.expiresAt - Date.now()) : isIdentityExpired(identity)
      ? 0
      : identity.expiresAt - Date.now() - 30_000;
    const timer = window.setTimeout(() => void refresh(), refreshIn);
    return () => window.clearTimeout(timer);
  }, [identity, refresh]);

  useEffect(() => {
    if (!refreshRetryAt) return;
    const timer = window.setTimeout(() => void refresh(), Math.max(0, refreshRetryAt - Date.now()));
    return () => window.clearTimeout(timer);
  }, [refresh, refreshRetryAt]);

  useEffect(() => {
    const handleUnauthorized = () => void refresh();
    window.addEventListener('smartcs:unauthorized', handleUnauthorized);
    return () => window.removeEventListener('smartcs:unauthorized', handleUnauthorized);
  }, [refresh]);

  const loginWithToken = useCallback(async (token: string) => {
    if (refreshPromise.current) await refreshPromise.current;
    externalToken.current = token.trim();
    resetRequestScope();
    await refresh();
    return identitiesRef.current.some((item) => item.token === externalToken.current);
  }, [refresh, resetRequestScope]);

  const selectIdentity = useCallback((userId: string) => {
    if (userId === selectedUserIdRef.current) return;
    resetRequestScope();
    selectedUserIdRef.current = userId;
    setSelectedUserId(userId);
  }, [resetRequestScope]);
  const value = useMemo<AuthContextValue>(
    () => ({
      identities,
      identity,
      loading,
      error,
      generation,
      requestSignal: requestController.current.signal,
      selectIdentity,
      refresh,
      loginWithToken,
      hasRole: (role) => Boolean(identity?.roles.includes(role)),
    }),
    [error, generation, identities, identity, loading, refresh, loginWithToken, selectIdentity],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside AuthProvider');
  return context;
}
