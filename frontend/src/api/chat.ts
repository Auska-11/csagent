import { apiRequest } from './client';
import type {
  ChatRequest,
  ChatResponse,
  ConversationMemory,
  FeedbackRequest,
  FeedbackResponse,
  MemoryClearResponse,
  UserMemory,
} from './types';

export const chatApi = {
  send: (token: string, request: ChatRequest, signal?: AbortSignal) =>
    apiRequest<ChatResponse>('/api/chat/send', { method: 'POST', token, body: request, signal }),

  getConversationMemory: (token: string, conversationId: string, signal?: AbortSignal) =>
    apiRequest<ConversationMemory>(
      `/api/chat/conversations/${encodeURIComponent(conversationId)}/memory`,
      { token, signal },
    ),

  clearConversationMemory: (token: string, conversationId: string, signal?: AbortSignal) =>
    apiRequest<MemoryClearResponse>(
      `/api/chat/conversations/${encodeURIComponent(conversationId)}/memory`,
      { method: 'DELETE', token, signal },
    ),

  getUserMemory: (token: string, userId: string, signal?: AbortSignal) =>
    apiRequest<UserMemory>(`/api/chat/users/${encodeURIComponent(userId)}/memory`, { token, signal }),

  clearUserMemory: (token: string, userId: string, signal?: AbortSignal) =>
    apiRequest<MemoryClearResponse>(`/api/chat/users/${encodeURIComponent(userId)}/memory`, {
      method: 'DELETE',
      token,
      signal,
    }),

  submitFeedback: (token: string, request: FeedbackRequest, signal?: AbortSignal) =>
    apiRequest<FeedbackResponse>('/api/monitor/feedback', {
      method: 'POST',
      token,
      body: request,
      signal,
    }),
};
