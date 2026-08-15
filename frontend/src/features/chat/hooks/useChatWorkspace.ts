import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { chatApi } from '../../../api/chat';
import { ApiError, toErrorMessage } from '../../../api/client';
import type {
  ConversationMemory,
  DemoIdentity,
  FeedbackRating,
  TraceEvent,
  UserMemory,
} from '../../../api/types';
import type { DemoScenario } from '../data/scenarios';
import { useAuth } from '../../auth/AuthProvider';

export type MessageRole = 'user' | 'assistant' | 'system';

export interface ChatMessage {
  id: string;
  role: MessageRole;
  content: string;
  timestamp: number;
  feedback?: FeedbackRating;
  feedbackPending?: boolean;
  feedbackError?: string;
  feedbackRetryRating?: FeedbackRating;
}

export interface ConversationWorkspace {
  conversationId: string;
  messages: ChatMessage[];
  trace: TraceEvent[];
  conversationMemory: ConversationMemory | null;
  userMemory: UserMemory | null;
  sending: boolean;
  memoryLoading: boolean;
  error: string | null;
  memoryError: string | null;
  failedMessage: string | null;
}

interface DemoProgress {
  scenarioName: string;
  current: number;
  total: number;
  status: 'running' | 'done' | 'failed';
}

function createConversationId(userId: string) {
  return `conv-${userId}-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
}

function createWorkspace(userId: string, conversationId = createConversationId(userId)): ConversationWorkspace {
  return {
    conversationId,
    messages: [],
    trace: [],
    conversationMemory: null,
    userMemory: null,
    sending: false,
    memoryLoading: false,
    error: null,
    memoryError: null,
    failedMessage: null,
  };
}

function makeMessage(role: MessageRole, content: string): ChatMessage {
  const timestamp = Date.now();
  return {
    id: `msg-${timestamp}-${Math.random().toString(36).slice(2, 8)}`,
    role,
    content,
    timestamp,
  };
}

export function useChatWorkspace(identity: DemoIdentity) {
  const { generation, requestSignal } = useAuth();
  const generationRef = useRef(generation);
  const [workspaces, setWorkspaces] = useState<Record<string, ConversationWorkspace>>(() => ({
    [identity.userId]: createWorkspace(identity.userId),
  }));
  const [demoProgress, setDemoProgress] = useState<DemoProgress | null>(null);

  const updateWorkspace = useCallback((
    userId: string,
    updater: (workspace: ConversationWorkspace) => ConversationWorkspace,
  ) => {
    setWorkspaces((current) => {
      const workspace = current[userId] ?? createWorkspace(userId);
      return { ...current, [userId]: updater(workspace) };
    });
  }, []);

  useEffect(() => {
    generationRef.current = generation;
    setWorkspaces({ [identity.userId]: createWorkspace(identity.userId) });
    setDemoProgress(null);
  }, [generation, identity.userId]);

  const workspace = useMemo(
    () => workspaces[identity.userId] ?? createWorkspace(identity.userId),
    [identity.userId, workspaces],
  );

  const loadMemoryFor = useCallback(async (
    targetIdentity: DemoIdentity,
    conversationId: string,
    requestGeneration: number,
  ) => {
    updateWorkspace(targetIdentity.userId, (current) => ({
      ...current,
      memoryLoading: true,
      memoryError: null,
    }));

    const [conversationResult, userResult] = await Promise.allSettled([
      chatApi.getConversationMemory(targetIdentity.token, conversationId, requestSignal),
      chatApi.getUserMemory(targetIdentity.token, targetIdentity.userId, requestSignal),
    ]);

    if (requestGeneration !== generationRef.current) return;

    updateWorkspace(targetIdentity.userId, (current) => {
      if (current.conversationId !== conversationId) return current;
      const conversationMemory = conversationResult.status === 'fulfilled'
        ? conversationResult.value
        : null;
      const userMemory = userResult.status === 'fulfilled' ? userResult.value : null;
      const errors: string[] = [];
      if (conversationResult.status === 'rejected') {
        const notCreated = conversationResult.reason instanceof ApiError
          && conversationResult.reason.status === 404;
        if (!notCreated) errors.push(toErrorMessage(conversationResult.reason, '会话记忆加载失败'));
      }
      if (userResult.status === 'rejected') {
        errors.push(toErrorMessage(userResult.reason, '长期记忆加载失败'));
      }
      return {
        ...current,
        conversationMemory,
        userMemory,
        memoryLoading: false,
        memoryError: errors.length ? errors.join('；') : null,
      };
    });
  }, [requestSignal, updateWorkspace]);

  const sendOne = useCallback(async (
    targetIdentity: DemoIdentity,
    conversationId: string,
    content: string,
  ) => {
    const requestGeneration = generationRef.current;
    const userMessage = makeMessage('user', content);
    updateWorkspace(targetIdentity.userId, (current) => ({
      ...current,
      conversationId,
      messages: [...current.messages, userMessage],
      sending: true,
      error: null,
      failedMessage: null,
    }));

    try {
      const response = await chatApi.send(
        targetIdentity.token,
        { message: content, conversationId },
        requestSignal,
      );
      if (requestGeneration !== generationRef.current) return false;
      updateWorkspace(targetIdentity.userId, (current) => ({
        ...current,
        conversationId: response.conversationId,
        messages: [...current.messages, {
          ...makeMessage('assistant', response.reply),
          timestamp: response.timestamp,
        }],
        trace: response.trace ?? [],
        sending: false,
      }));
      await loadMemoryFor(targetIdentity, response.conversationId, requestGeneration);
      return true;
    } catch (requestError) {
      if (requestSignal.aborted || requestGeneration !== generationRef.current) return false;
      const message = toErrorMessage(requestError, '消息发送失败');
      updateWorkspace(targetIdentity.userId, (current) => ({
        ...current,
        sending: false,
        error: message,
        failedMessage: content,
      }));
      return false;
    }
  }, [loadMemoryFor, requestSignal, updateWorkspace]);

  const sendMessage = useCallback((content: string) => {
    const normalized = content.trim();
    if (!normalized || workspace.sending || demoProgress?.status === 'running') {
      return Promise.resolve(false);
    }
    return sendOne(identity, workspace.conversationId, normalized);
  }, [demoProgress?.status, identity, sendOne, workspace.conversationId, workspace.sending]);

  const retryLastMessage = useCallback(() => {
    if (!workspace.failedMessage || workspace.sending) return Promise.resolve(false);
    updateWorkspace(identity.userId, (current) => {
      const last = current.messages[current.messages.length - 1];
      return last?.role === 'user' && last.content === current.failedMessage
        ? { ...current, messages: current.messages.slice(0, -1) }
        : current;
    });
    return sendOne(identity, workspace.conversationId, workspace.failedMessage);
  }, [identity, sendOne, updateWorkspace, workspace.conversationId, workspace.failedMessage, workspace.sending]);

  const newConversation = useCallback(() => {
    const next = createWorkspace(identity.userId);
    updateWorkspace(identity.userId, () => next);
    setDemoProgress(null);
    return next.conversationId;
  }, [identity.userId, updateWorkspace]);

  const runScenario = useCallback(async (scenario: DemoScenario) => {
    if (workspace.sending || demoProgress?.status === 'running') return;
    const conversationId = createConversationId(identity.userId);
    updateWorkspace(identity.userId, () => createWorkspace(identity.userId, conversationId));
    setDemoProgress({ scenarioName: scenario.name, current: 0, total: scenario.steps.length, status: 'running' });

    for (let index = 0; index < scenario.steps.length; index += 1) {
      const step = scenario.steps[index];
      if (!step) continue;
      setDemoProgress({
        scenarioName: scenario.name,
        current: index + 1,
        total: scenario.steps.length,
        status: 'running',
      });
      const ok = await sendOne(identity, conversationId, step);
      if (!ok) {
        setDemoProgress({
          scenarioName: scenario.name,
          current: index + 1,
          total: scenario.steps.length,
          status: 'failed',
        });
        return;
      }
    }

    setDemoProgress({
      scenarioName: scenario.name,
      current: scenario.steps.length,
      total: scenario.steps.length,
      status: 'done',
    });
  }, [demoProgress?.status, identity, sendOne, updateWorkspace, workspace.sending]);

  const refreshMemory = useCallback(
    () => loadMemoryFor(identity, workspace.conversationId, generationRef.current),
    [identity, loadMemoryFor, workspace.conversationId],
  );

  const clearConversationMemory = useCallback(async () => {
    const requestGeneration = generationRef.current;
    updateWorkspace(identity.userId, (current) => ({ ...current, memoryLoading: true, memoryError: null }));
    try {
      await chatApi.clearConversationMemory(identity.token, workspace.conversationId, requestSignal);
      if (requestSignal.aborted || requestGeneration !== generationRef.current) return;
      updateWorkspace(identity.userId, (current) => ({
        ...current,
        conversationMemory: null,
        memoryLoading: false,
      }));
    } catch (requestError) {
      if (requestSignal.aborted || requestGeneration !== generationRef.current) return;
      updateWorkspace(identity.userId, (current) => ({
        ...current,
        memoryLoading: false,
        memoryError: toErrorMessage(requestError, '会话记忆清除失败'),
      }));
    }
  }, [identity, requestSignal, updateWorkspace, workspace.conversationId]);

  const clearUserMemory = useCallback(async () => {
    const requestGeneration = generationRef.current;
    updateWorkspace(identity.userId, (current) => ({ ...current, memoryLoading: true, memoryError: null }));
    try {
      await chatApi.clearUserMemory(identity.token, identity.userId, requestSignal);
      if (requestSignal.aborted || requestGeneration !== generationRef.current) return;
      await loadMemoryFor(identity, workspace.conversationId, generationRef.current);
    } catch (requestError) {
      if (requestSignal.aborted || requestGeneration !== generationRef.current) return;
      updateWorkspace(identity.userId, (current) => ({
        ...current,
        memoryLoading: false,
        memoryError: toErrorMessage(requestError, '长期记忆清除失败'),
      }));
    }
  }, [identity, loadMemoryFor, requestSignal, updateWorkspace, workspace.conversationId]);

  const submitFeedback = useCallback(async (messageId: string, rating: FeedbackRating) => {
    const message = workspace.messages.find((item) => item.id === messageId && item.role === 'assistant');
    if (!message || message.feedback || message.feedbackPending) return;
    const requestGeneration = generationRef.current;

    updateWorkspace(identity.userId, (current) => ({
      ...current,
        messages: current.messages.map((item) => item.id === messageId
          ? { ...item, feedbackPending: true, feedbackError: undefined, feedbackRetryRating: undefined }
          : item),
    }));

    try {
      await chatApi.submitFeedback(identity.token, {
        conversationId: workspace.conversationId,
        rating,
        reason: rating === 'HELPFUL' ? 'React 工作台快捷反馈：有用' : 'React 工作台快捷反馈：无用',
        assistantReply: message.content,
      }, requestSignal);
      updateWorkspace(identity.userId, (current) => ({
        ...current,
        messages: current.messages.map((item) => item.id === messageId
          ? { ...item, feedback: rating, feedbackPending: false, feedbackError: undefined, feedbackRetryRating: undefined }
          : item),
      }));
    } catch (requestError) {
      if (requestSignal.aborted || requestGeneration !== generationRef.current) return;
      updateWorkspace(identity.userId, (current) => ({
        ...current,
        messages: current.messages.map((item) => item.id === messageId
          ? { ...item, feedbackPending: false, feedbackError: toErrorMessage(requestError, '反馈提交失败'), feedbackRetryRating: rating }
          : item),
      }));
    }
  }, [identity, requestSignal, updateWorkspace, workspace.conversationId, workspace.messages]);

  return {
    workspace,
    demoProgress,
    sendMessage,
    retryLastMessage,
    newConversation,
    runScenario,
    refreshMemory,
    clearConversationMemory,
    clearUserMemory,
    submitFeedback,
  };
}
