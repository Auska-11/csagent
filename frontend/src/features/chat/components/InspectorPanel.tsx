import {
  Braces,
  BrainCircuit,
  Check,
  Clock3,
  Copy,
  Database,
  RefreshCw,
  RotateCcw,
  Trash2,
  X,
} from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { ConversationMemory, TraceEvent, UserMemory } from '../../../api/types';
import { Button } from '../../../components/ui/Button';
import { ConfirmDialog } from '../../../components/ui/Dialog';
import { StatePanel } from '../../../components/ui/StatePanel';
import { deriveTraceMetrics, matchesTraceFilter, type TraceFilter } from '../trace';

interface InspectorPanelProps {
  trace: TraceEvent[];
  conversationMemory: ConversationMemory | null;
  userMemory: UserMemory | null;
  memoryLoading: boolean;
  memoryError: string | null;
  open: boolean;
  onClose: () => void;
  onRefreshMemory: () => void;
  onClearConversationMemory: () => void;
  onClearUserMemory: () => void;
}

const filters: Array<{ id: TraceFilter; label: string }> = [
  { id: 'all', label: '全部' },
  { id: 'tool', label: '工具' },
  { id: 'memory', label: '记忆' },
  { id: 'knowledge', label: '知识' },
  { id: 'llm', label: '模型' },
  { id: 'failed', label: '异常' },
];

function formatTime(timestamp: number) {
  if (!timestamp) return '—';
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  }).format(timestamp);
}

function DataList({ data }: { data: Record<string, unknown> }) {
  const entries = Object.entries(data);
  if (!entries.length) return null;
  return (
    <dl className="data-list">
      {entries.slice(0, 8).map(([key, value]) => (
        <div key={key}><dt>{key}</dt><dd>{typeof value === 'string' ? value : JSON.stringify(value)}</dd></div>
      ))}
    </dl>
  );
}

export function InspectorPanel({
  trace,
  conversationMemory,
  userMemory,
  memoryLoading,
  memoryError,
  open,
  onClose,
  onRefreshMemory,
  onClearConversationMemory,
  onClearUserMemory,
}: InspectorPanelProps) {
  const [tab, setTab] = useState<'trace' | 'memory'>('trace');
  const [filter, setFilter] = useState<TraceFilter>('all');
  const [copied, setCopied] = useState(false);
  const [confirmAction, setConfirmAction] = useState<'conversation' | 'user' | null>(null);
  const panelRef = useRef<HTMLElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const traceTabRef = useRef<HTMLButtonElement>(null);
  const memoryTabRef = useRef<HTMLButtonElement>(null);
  const onCloseRef = useRef(onClose);
  const metrics = useMemo(() => deriveTraceMetrics(trace), [trace]);
  const visibleTrace = useMemo(() => trace.filter((event) => matchesTraceFilter(event, filter)), [filter, trace]);

  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    if (!open) return;
    closeRef.current?.focus();
    const panel = panelRef.current;
    const keydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCloseRef.current();
      if (event.key !== 'Tab' || !panel) return;
      const items = [...panel.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled), [tabindex]:not([tabindex="-1"])')];
      if (!items.length) return;
      const first = items[0]!;
      const last = items[items.length - 1]!;
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', keydown);
    return () => document.removeEventListener('keydown', keydown);
  }, [open]);

  const copyTrace = async () => {
    await navigator.clipboard.writeText(JSON.stringify(trace, null, 2));
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1200);
  };

  return (
    <aside ref={panelRef} id="execution-inspector" className={`inspector-panel ${open ? 'inspector-panel--open' : ''}`} role={open ? 'dialog' : undefined} aria-modal={open || undefined} aria-label="执行详情">
      <header className="inspector-header">
        <div><span className="eyebrow">可观测性</span><h2>执行详情</h2></div>
        <button ref={closeRef} className="inspector-close" onClick={onClose} aria-label="关闭执行详情"><X size={19} /></button>
      </header>
      <div className="inspector-tabs" role="tablist" aria-label="执行详情">
        <button ref={traceTabRef} id="trace-tab" role="tab" aria-controls="trace-panel" aria-selected={tab === 'trace'} tabIndex={tab === 'trace' ? 0 : -1} className={tab === 'trace' ? 'active' : ''} onClick={() => setTab('trace')} onKeyDown={(event) => { if (event.key === 'ArrowRight' || event.key === 'End') { event.preventDefault(); setTab('memory'); memoryTabRef.current?.focus(); } }}>
          <Braces size={16} />本轮轨迹 <span>{trace.length}</span>
        </button>
        <button ref={memoryTabRef} id="memory-tab" role="tab" aria-controls="memory-panel" aria-selected={tab === 'memory'} tabIndex={tab === 'memory' ? 0 : -1} className={tab === 'memory' ? 'active' : ''} onClick={() => setTab('memory')} onKeyDown={(event) => { if (event.key === 'ArrowLeft' || event.key === 'Home') { event.preventDefault(); setTab('trace'); traceTabRef.current?.focus(); } }}>
          <BrainCircuit size={16} />Memory
        </button>
      </div>

      {tab === 'trace' ? (
        <div id="trace-panel" className="inspector-content" role="tabpanel" aria-labelledby="trace-tab">
          <div className="metric-grid">
            <div><span>事件</span><strong>{metrics.events}</strong></div>
            <div><span>Action</span><strong>{metrics.actions}</strong></div>
            <div><span>Capability</span><strong>{metrics.tools}</strong></div>
            <div><span>异常</span><strong className={metrics.failed ? 'danger-text' : ''}>{metrics.failed}</strong></div>
          </div>
          <div className="filter-row">
            <div className="filter-scroll">
              {filters.map((item) => (
                <button key={item.id} className={filter === item.id ? 'active' : ''} onClick={() => setFilter(item.id)}>
                  {item.label}
                </button>
              ))}
            </div>
            <button className="copy-icon" onClick={() => void copyTrace()} disabled={!trace.length} aria-label="复制 Trace">
              {copied ? <Check size={15} /> : <Copy size={15} />}
            </button>
          </div>
          {!visibleTrace.length ? (
            <StatePanel
              compact
              icon={Braces}
              title={trace.length ? '该分类暂无事件' : '等待第一轮执行'}
              description={trace.length ? '选择其他筛选条件查看执行事件。' : '发送消息后，这里会展示 Agent 的完整决策链路。'}
            />
          ) : (
            <div className="trace-timeline">
              {visibleTrace.map((event, index) => (
                <article className="trace-event" key={`${event.timestamp}-${event.eventType}-${index}`}>
                  <div className="trace-dot"><span /></div>
                  <div className="trace-event__body">
                    <div className="trace-event__meta">
                      <span>{event.eventType}</span><small>Step {event.step} · {formatTime(event.timestamp)}</small><button onClick={() => void navigator.clipboard.writeText(JSON.stringify(event, null, 2))} aria-label={`复制 ${event.eventType} 事件`}><Copy size={14} /></button>
                    </div>
                    <p>{event.message}</p>
                    {Object.keys(event.data).length ? (
                      <details><summary>结构化数据</summary><DataList data={event.data} /></details>
                    ) : null}
                  </div>
                </article>
              ))}
            </div>
          )}
        </div>
      ) : (
        <div id="memory-panel" className="inspector-content memory-content" role="tabpanel" aria-labelledby="memory-tab">
          <div className="memory-toolbar">
            <Button variant="ghost" icon={<RefreshCw size={15} className={memoryLoading ? 'spin' : ''} />} onClick={onRefreshMemory} disabled={memoryLoading}>
              刷新
            </Button>
          </div>
          {memoryError ? <div className="inline-error">{memoryError}</div> : null}
          {memoryLoading && !conversationMemory && !userMemory ? (
            <div className="memory-skeleton"><i /><i /><i /></div>
          ) : null}
          {!memoryLoading && !conversationMemory && !userMemory ? (
            <StatePanel compact icon={Database} title="暂无记忆" description="发送第一条消息后，会话与用户记忆会在这里出现。" actionLabel="检查记忆" onAction={onRefreshMemory} />
          ) : null}
          {conversationMemory ? (
            <section className="memory-card">
              <div className="memory-card__heading">
                <span><BrainCircuit size={17} /></span>
                <div><strong>会话记忆</strong><small>短期上下文与工作流状态</small></div>
                <button
                  title="清空会话记忆"
                  onClick={() => setConfirmAction('conversation')}
                ><RotateCcw size={15} /></button>
              </div>
              <div className="memory-facts">
                <div><span>当前意图</span><strong>{conversationMemory.activeIntent || '—'}</strong></div>
                <div><span>等待字段</span><strong>{conversationMemory.pendingSlot || '—'}</strong></div>
                <div><span>工作流</span><strong>{conversationMemory.activeWorkflowId || '—'}</strong></div>
                <div><span>状态</span><strong>{conversationMemory.workflowStatus || '—'}</strong></div>
              </div>
              <div className="memory-section">
                <span className="memory-section__label">任务状态</span>
                {Object.keys(conversationMemory.taskMemory).length
                  ? <DataList data={conversationMemory.taskMemory} />
                  : <p className="muted">尚未收集任务字段</p>}
              </div>
              <div className="memory-section">
                <span className="memory-section__label">摘要 · v{conversationMemory.summaryVersion}</span>
                <p>{conversationMemory.summaryMemory || '长对话压缩尚未触发'}</p>
                {conversationMemory.shortTermMessages.length ? <details className="memory-recent"><summary>最近消息（{Math.min(5, conversationMemory.shortTermMessages.length)} 条）</summary><ul>{conversationMemory.shortTermMessages.slice(-5).map((message, index) => <li key={`${String(message.timestamp ?? index)}-${index}`}><strong>{message.role === 'assistant' ? 'Agent' : '访客'}</strong><span>{message.content ?? JSON.stringify(message)}</span></li>)}</ul></details> : null}
              </div>
              <div className="memory-card__footer"><Clock3 size={13} />短期消息 {conversationMemory.shortTermMessages.length}/{conversationMemory.shortTermMessageLimit} · 摘要更新 {formatTime(conversationMemory.summaryUpdatedAt)}</div>
            </section>
          ) : null}
          {userMemory ? (
            <section className="memory-card">
              <div className="memory-card__heading">
                <span className="accent-violet"><Database size={17} /></span>
                <div><strong>长期偏好</strong><small>跨会话用户记忆 · v{userMemory.version}</small></div>
                <button
                  title="清空长期记忆"
                  onClick={() => setConfirmAction('user')}
                ><Trash2 size={15} /></button>
              </div>
              {userMemory.productPreferences.length ? (
                <div className="preference-list">
                  {userMemory.productPreferences.map((preference) => <span key={preference}>{preference}</span>)}
                </div>
              ) : <p className="muted">还没有记录商品偏好</p>}
              <div className="memory-card__footer"><Clock3 size={13} />偏好更新 {formatTime(userMemory.updatedAt)}</div>
            </section>
          ) : null}
        </div>
      )}
      <ConfirmDialog
        open={confirmAction === 'conversation'}
        title="清空当前会话记忆"
        description="将删除当前会话的任务状态、摘要和短期上下文。此操作无法撤销，下一轮会重新创建状态。"
        confirmLabel="清空会话记忆"
        danger
        busy={memoryLoading}
        onClose={() => setConfirmAction(null)}
        onConfirm={() => { onClearConversationMemory(); setConfirmAction(null); }}
      />
      <ConfirmDialog
        open={confirmAction === 'user'}
        title="清空长期偏好"
        description="将删除当前用户跨会话保存的商品偏好，不影响本轮聊天消息。此操作无法撤销。"
        confirmLabel="清空长期偏好"
        danger
        busy={memoryLoading}
        onClose={() => setConfirmAction(null)}
        onConfirm={() => { onClearUserMemory(); setConfirmAction(null); }}
      />
    </aside>
  );
}
