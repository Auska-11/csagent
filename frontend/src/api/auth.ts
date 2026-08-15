import { apiRequest } from './client';
import type { DemoIdentity } from './types';

export const authApi = {
  issueDemoTokens: () => apiRequest<DemoIdentity[]>('/api/auth/demo/tokens', { method: 'POST' }),
};
