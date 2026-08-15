import { apiRequest } from './client';
import type {
  EvalCandidate,
  EvalCaseMergeItem,
  EvalCaseMergeResponse,
  EvalReportsResponse,
  HandoffConfirmRequest,
  HandoffDecisionRequest,
  MonitorSummary,
  PendingHandoffAuditPage,
  PendingHandoffDecisionResponse,
  PendingHandoffDetail,
  PendingHandoffListResponse,
  PendingHandoffStatus,
  ToolsResponse,
} from './adminTypes';

const auth = (token: string, signal?: AbortSignal) => ({ token, signal });

export const adminApi = {
  getMonitorSummary: (token: string, signal?: AbortSignal) =>
    apiRequest<MonitorSummary>('/api/monitor/summary', auth(token, signal)),

  getTools: (token: string, signal?: AbortSignal) =>
    apiRequest<ToolsResponse>('/api/tools', auth(token, signal)),

  getEvalReports: (token: string, signal?: AbortSignal) =>
    apiRequest<EvalReportsResponse>('/api/eval/reports', auth(token, signal)),

  getEvalCandidates: (token: string, signal?: AbortSignal) =>
    apiRequest<EvalCandidate[]>('/api/monitor/feedback/candidates', auth(token, signal)),

  reviewEvalCandidate: (
    token: string,
    candidateId: string,
    status: 'APPROVED' | 'REJECTED',
    reviewNote: string,
    signal?: AbortSignal,
  ) => apiRequest<{ success: boolean; message: string; candidate: EvalCandidate }>(
    `/api/monitor/feedback/candidates/${encodeURIComponent(candidateId)}/review`,
    { method: 'POST', token, signal, body: { status, reviewNote } },
  ),

  exportEvalCandidates: (token: string, signal?: AbortSignal) =>
    apiRequest<Array<Record<string, unknown>>>('/api/monitor/feedback/candidates/export', auth(token, signal)),

  mergeEvalCases: (token: string, cases: EvalCaseMergeItem[], signal?: AbortSignal) =>
    apiRequest<EvalCaseMergeResponse>('/api/monitor/feedback/candidates/merge', {
      method: 'POST', token, signal, body: { cases },
    }),

  getHandoffs: (
    token: string,
    status: PendingHandoffStatus,
    cursor?: string,
    signal?: AbortSignal,
  ) => {
    const params = new URLSearchParams({ status, limit: '20' });
    if (cursor) params.set('cursor', cursor);
    return apiRequest<PendingHandoffListResponse>(
      `/api/admin/pending-handoffs?${params.toString()}`,
      auth(token, signal),
    );
  },

  getHandoff: (token: string, outboxId: string, signal?: AbortSignal) =>
    apiRequest<PendingHandoffDetail>(
      `/api/admin/pending-handoffs/${encodeURIComponent(outboxId)}`,
      auth(token, signal),
    ),

  getHandoffAudit: (token: string, outboxId: string, cursor?: string, signal?: AbortSignal) => {
    const params = new URLSearchParams({ limit: '20' });
    if (cursor) params.set('cursor', cursor);
    return apiRequest<PendingHandoffAuditPage>(
      `/api/admin/pending-handoffs/${encodeURIComponent(outboxId)}/audit-events?${params.toString()}`,
      auth(token, signal),
    );
  },

  decideHandoff: (
    token: string,
    outboxId: string,
    action: 'retry' | 'reconcile' | 'close',
    request: HandoffDecisionRequest,
    signal?: AbortSignal,
  ) => apiRequest<PendingHandoffDecisionResponse>(
    `/api/admin/pending-handoffs/${encodeURIComponent(outboxId)}/${action}`,
    { method: 'POST', token, signal, body: request },
  ),

  confirmHandoff: (
    token: string,
    outboxId: string,
    request: HandoffConfirmRequest,
    signal?: AbortSignal,
  ) => apiRequest<PendingHandoffDecisionResponse>(
    `/api/admin/pending-handoffs/${encodeURIComponent(outboxId)}/confirm`,
    { method: 'POST', token, signal, body: request },
  ),
};
