import type { TraceEvent } from '../../api/types';

export type TraceFilter = 'all' | 'memory' | 'tool' | 'knowledge' | 'llm' | 'handoff' | 'failed';

export function matchesTraceFilter(event: TraceEvent, filter: TraceFilter) {
  if (filter === 'all') return true;
  const type = event.eventType.toLowerCase();
  const message = event.message.toLowerCase();
  if (filter === 'failed') return type.includes('fail') || type.includes('error');
  return type.includes(filter) || message.includes(filter);
}

export function deriveTraceMetrics(trace: TraceEvent[]) {
  const failed = trace.filter((event) => matchesTraceFilter(event, 'failed')).length;
  const tools = trace.filter((event) => matchesTraceFilter(event, 'tool')).length;
  const actions = new Set(trace.filter((event) => event.step > 0).map((event) => event.step)).size;
  return { events: trace.length, failed, tools, actions };
}
