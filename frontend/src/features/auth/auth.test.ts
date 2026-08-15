import { describe, expect, it } from 'vitest';
import type { DemoIdentity } from '../../api/types';
import { isIdentityExpired } from './AuthProvider';
import { canAccessRole } from './RoleGuard';

const identity: DemoIdentity = {
  userId: 'USER-001', name: 'Demo', token: 'secret', roles: ['CUSTOMER'], expiresAt: 130_000,
};

describe('auth boundaries', () => {
  it('treats a token as expired 30 seconds early', () => {
    expect(isIdentityExpired(identity, 99_999)).toBe(false);
    expect(isIdentityExpired(identity, 100_000)).toBe(true);
  });

  it('uses exact HTTP roles for client-side navigation', () => {
    expect(canAccessRole(['CUSTOMER'], 'CUSTOMER')).toBe(true);
    expect(canAccessRole(['CUSTOMER'], 'ADMIN')).toBe(false);
    expect(canAccessRole(['CUSTOMER', 'ADMIN'], 'ADMIN')).toBe(true);
    expect(canAccessRole(['MCP_CLIENT'], 'ADMIN')).toBe(false);
  });
});
