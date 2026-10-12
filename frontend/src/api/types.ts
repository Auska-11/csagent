export type Role = 'CUSTOMER' | 'ADMIN' | 'MCP_CLIENT';

export interface DemoIdentity {
  tenantId?: string;
  channel?: string;
  userId: string;
  name: string;
  token: string;
  roles: Role[];
  expiresAt: number;
}

export interface ApiErrorPayload {
  errorCode: string;
  message: string;
  timestamp: number;
}

export interface ChatRequest {
  message: string;
  conversationId: string;
}

export interface TraceEvent {
  eventType: string;
  step: number;
  message: string;
  data: Record<string, unknown>;
  timestamp: number;
}

export interface ChatResponse {
  conversationId: string;
  userId: string;
  reply: string;
  timestamp: number;
  trace: TraceEvent[];
}

export interface ConversationMemory {
  conversationId: string;
  userId: string;
  activeIntent: string | null;
  pendingSlot: string | null;
  activeWorkflowId: string | null;
  workflowVersion: string | null;
  workflowStatus: string | null;
  workflowState: Record<string, unknown>;
  workflowStartedAt: number;
  workflowUpdatedAt: number;
  taskMemory: Record<string, unknown>;
  shortTermMessages: Array<Record<string, string>>;
  summaryMemory: string;
  totalMessageCount: number;
  shortTermMessageLimit: number;
  summaryVersion: number;
  summaryUpdatedAt: number;
}

export interface UserMemory {
  userId: string;
  productPreferences: string[];
  version: number;
  updatedAt: number;
  hasProductPreferences: boolean;
}

export interface MemoryClearResponse {
  scope: 'conversation' | 'user';
  targetId: string;
  cleared: boolean;
  message: string;
  timestamp: number;
}

export type FeedbackRating = 'HELPFUL' | 'UNHELPFUL';

export interface FeedbackRequest {
  conversationId: string;
  rating: FeedbackRating;
  reason: string;
  assistantReply: string;
}

export interface FeedbackResponse {
  success: boolean;
  message: string;
  feedback: {
    feedbackId: string;
    conversationId: string;
    userId: string;
      rating: FeedbackRating;
      reason: string;
      assistantReply: string;
      timestamp: number;
  };
}
