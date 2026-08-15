import { describe, expect, it } from 'vitest';
import type { ChatMessage } from './hooks/useChatWorkspace';
import { latestAssistantMessageId } from './components/ConversationPanel';
import { deriveTraceMetrics, matchesTraceFilter } from './trace';

describe('chat presentation rules', () => {
  it('only selects the latest assistant reply for feedback', () => {
    const messages: ChatMessage[] = [
      { id: 'a1', role: 'assistant', content: 'first', timestamp: 1 },
      { id: 'u1', role: 'user', content: 'next', timestamp: 2 },
      { id: 'a2', role: 'assistant', content: 'latest', timestamp: 3 },
    ];
    expect(latestAssistantMessageId(messages)).toBe('a2');
  });

  it('derives trace counts without inventing end-to-end duration', () => {
    const trace = [
      { eventType: 'tool_called', step: 1, message: 'tool', data: { durationMs: 10 }, timestamp: 1 },
      { eventType: 'tool_failed', step: 1, message: 'failed', data: { durationMs: 12 }, timestamp: 2 },
      { eventType: 'final_answer', step: 2, message: 'done', data: {}, timestamp: 3 },
    ];
    expect(deriveTraceMetrics(trace)).toEqual({ events: 3, failed: 1, tools: 2, actions: 2 });
    expect(matchesTraceFilter(trace[1]!, 'failed')).toBe(true);
    expect(deriveTraceMetrics(trace)).not.toHaveProperty('duration');
  });
});
