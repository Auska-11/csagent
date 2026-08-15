import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CapabilityDefinition } from './adminTypes';
import { adminApi } from './admin';
import { requiredParameters } from '../pages/admin/CapabilitiesPage';

describe('admin contracts', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('uses capability id and derives required parameters from JSON Schema', () => {
    const tool = {
      id: 'order.lookup',
      inputSchema: { type: 'object', required: ['order_id', 42] },
    } as unknown as CapabilityDefinition;
    expect(tool.id).toBe('order.lookup');
    expect(requiredParameters(tool)).toEqual(['order_id']);
  });

  it('uses limit and preserves the opaque handoff cursor', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [], nextCursor: null, hasMore: false }), {
      status: 200, headers: { 'content-type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);
    await adminApi.getHandoffs('admin-token', 'NEEDS_MANUAL_REVIEW', 'opaque+/cursor==');
    const url = String(fetchMock.mock.calls[0]![0]);
    expect(url).toContain('limit=20');
    expect(new URL(url, 'http://localhost').searchParams.get('cursor')).toBe('opaque+/cursor==');
    expect(url).not.toContain('size=');
  });

  it('sends expectedVersion for handoff mutations exactly once', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ record: {}, auditEvent: {} }), {
      status: 200, headers: { 'content-type': 'application/json' },
    }));
    vi.stubGlobal('fetch', fetchMock);
    await adminApi.decideHandoff('admin-token', 'outbox-1', 'retry', {
      expectedVersion: 7, reasonCode: 'ADMIN_RETRY', decisionNote: 'checked',
    });
    const init = fetchMock.mock.calls[0]![1] as RequestInit;
    expect(JSON.parse(String(init.body))).toEqual({ expectedVersion: 7, reasonCode: 'ADMIN_RETRY', decisionNote: 'checked' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
