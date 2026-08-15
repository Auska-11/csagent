import type { FeedbackRating } from './types';

export type RiskLevel = 'READ_ONLY' | 'LOW' | 'MEDIUM' | 'HIGH';

export interface CapabilityDefinition {
  id: string;
  version: string;
  description: string;
  inputSchema: Record<string, unknown>;
  outputSchema: Record<string, unknown>;
  requiresIdentity: boolean;
  requiresAuthentication: boolean;
  sideEffect: boolean;
  idempotent: boolean;
  defaultTimeoutMs: number;
  riskLevel: RiskLevel;
}

export interface ToolsResponse {
  count: number;
  tools: CapabilityDefinition[];
}

export type TurnOutcome = 'SUCCESS' | 'BUSINESS_REJECTED' | 'TECHNICAL_FAILURE' | 'GUARDRAIL_REJECTED' | 'USER_CANCELLED';

export interface FeedbackRecord {
  feedbackId: string;
  conversationId: string;
  userId: string;
  rating: FeedbackRating;
  reason: string;
  assistantReply: string;
  timestamp: number;
}

export interface EvalCandidate {
  candidateId: string;
  feedbackId: string;
  conversationId: string;
  userId: string;
  rating: FeedbackRating;
  reason: string;
  assistantReply: string;
  suggestedEvalType: string;
  issueType: string;
  status: 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED';
  reviewNote: string;
  reviewedAt: number;
  timestamp: number;
}

export interface MonitorSummary {
  totalTurns: number;
  successfulTurnCount: number;
  businessRejectedTurnCount: number;
  technicalFailureTurnCount: number;
  guardrailRejectedTurnCount: number;
  userCancelledTurnCount: number;
  turnSuccessRate: number;
  toolCallCount: number;
  successfulToolCallCount: number;
  failedToolCallCount: number;
  toolSuccessRate: number;
  llmCallCount: number;
  llmFailureCount: number;
  llmFailureRate: number;
  handoffCount: number;
  feedbackCount: number;
  helpfulFeedbackCount: number;
  unhelpfulFeedbackCount: number;
  helpfulFeedbackRate: number;
  averageToolDurationMs: number;
  averageLlmDurationMs: number;
  lastUpdatedAt: number;
  recentTurns: Array<{
    conversationId: string;
    userId: string;
    timestamp: number;
    outcome: TurnOutcome;
    errorCode: string | null;
    toolCalls: number;
    failedToolCalls: number;
    llmCalls: number;
    llmFailures: number;
    handoffCount: number;
    totalDurationMs: number;
  }>;
  toolStats: Array<{
    toolName: string;
    callCount: number;
    successfulCallCount: number;
    failedCallCount: number;
    successRate: number;
    averageDurationMs: number;
  }>;
  recentFeedbacks: FeedbackRecord[];
  evalCandidateCount: number;
  pendingEvalCandidateCount: number;
  approvedEvalCandidateCount: number;
  rejectedEvalCandidateCount: number;
  evalCandidateIssueCounts: Record<string, number>;
  recentEvalCandidates: EvalCandidate[];
  alerts: Array<{
    severity: 'INFO' | 'WARN' | 'CRITICAL';
    code: string;
    title: string;
    message: string;
    suggestion: string;
  }>;
}

export interface EvalReportSection {
  generatedAt: unknown;
  reportCount: number;
  reports: Array<Record<string, unknown>>;
}

export interface EvalReportsResponse {
  available: boolean;
  reportDir: string;
  summary: EvalReportSection;
  trend: EvalReportSection;
}

export interface EvalCaseMergeItem {
  sourceCandidateId: string;
  id?: string;
  candidateReply?: string;
  requiredKeywords: string[];
  forbiddenKeywords: string[];
  expectedPass: boolean;
}

export interface EvalCaseMergeResponse {
  success: boolean;
  message: string;
  result: {
    filePath: string;
    beforeCount: number;
    afterCount: number;
    mergedCount: number;
    skippedDuplicateIds: string[];
  };
}

export type PendingHandoffStatus =
  | 'PREPARED'
  | 'RESULT_UNKNOWN'
  | 'PROCESSING'
  | 'CONFIRMED'
  | 'RETRY_SCHEDULED'
  | 'NEEDS_MANUAL_REVIEW'
  | 'MANUALLY_CLOSED';

export interface PendingHandoffListItem {
  outboxId: string;
  status: PendingHandoffStatus;
  handoffReasonCode: string;
  failureCode: string;
  attemptCount: number;
  scheduledAt: number | null;
  version: number;
  createdAt: number;
  updatedAt: number;
}

export interface PendingHandoffListResponse {
  items: PendingHandoffListItem[];
  nextCursor: string | null;
  hasMore: boolean;
}

export interface PendingHandoffDetail extends PendingHandoffListItem {
  summaryPreview: string;
  productLineCode: string;
  issueTypeCode: string;
  priorityCode: string;
  confirmedTicket: { ticketId: string; status: string; createdAt: string } | null;
}

export interface PendingHandoffAuditEvent {
  auditId: string;
  action: string;
  actorId: string;
  authorityUsed: string;
  reasonCode: string;
  decisionNote: string;
  evidenceSource: string;
  fromStatus: PendingHandoffStatus;
  toStatus: PendingHandoffStatus;
  fromVersion: number;
  toVersion: number;
  reviewFailureCode: string;
  attemptCountAtDecision: number;
  scheduledAt: number | null;
  occurredAt: number;
}

export interface PendingHandoffAuditPage {
  events: PendingHandoffAuditEvent[];
  nextCursor: string | null;
  hasMore: boolean;
}

export interface HandoffDecisionRequest {
  expectedVersion: number;
  reasonCode: string;
  decisionNote?: string;
}

export interface HandoffConfirmRequest extends HandoffDecisionRequest {
  result: { ticketId: string; status: string; createdAt: string };
}

export interface PendingHandoffDecisionResponse {
  record: PendingHandoffDetail;
  auditEvent: PendingHandoffAuditEvent;
}
