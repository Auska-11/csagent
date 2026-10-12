import { apiRequest } from './client';
import type { DemoIdentity } from './types';

export const authApi = {
  me: (token: string) => apiRequest<Omit<DemoIdentity, 'token'>>('/api/auth/me', { token }),
  issueDemoTokens: () => apiRequest<DemoIdentity[]>('/api/auth/demo/tokens', { method: 'POST' }),
};
