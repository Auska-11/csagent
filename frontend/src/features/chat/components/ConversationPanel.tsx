import { ArrowUp, Bot, LoaderCircle, PanelRightOpen, ShieldCheck, Sparkles } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import type { FeedbackRating } from '../../../api/types';
import type { TraceEvent } from '../../../api/types';
import { Button } from '../../../components/ui/Button';
import type { ChatMessage } from '../hooks/useChatWorkspace';
import { quickPrompts } from '../data/scenarios';
import { MessageBubble } from './MessageBubble';

interface ConversationPanelProps {
  userName: string;
  conversationId: string;
  messages: ChatMessage[];
  trace: TraceEvent[];
  sending: boolean;
  error: string | null;
  onSend: (message: string) => void;
  onFeedback: (messageId: string, rating: FeedbackRating) => void;
  onOpenInspector: () => void;
  onRetry: () => void;
  inspectorOpen: boolean;
}

export function latestAssistantMessageId(messages: ChatMessage[]) {
  return [...messages].reverse().find((message) => message.role === 'assistant')?.id;
}

export function ConversationPanel({
  userName,
  conversationId,
  messages,
  trace,
  sending,
  error,
  onSend,
  onFeedback,
  onOpenInspector,
  onRetry,
  inspectorOpen,
}: ConversationPanelProps) {
  const [draft, setDraft] = useState('');
  const scrollAnchor = useRef<HTMLDivElement>(null);
  const inspectorTriggerRef = useRef<HTMLButtonElement>(null);
  const wasInspectorOpen = useRef(false);
  const composing = useRef(false);

  useEffect(() => {
    scrollAnchor.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [messages, sending]);

  useEffect(() => {
    if (wasInspectorOpen.current && !inspectorOpen) inspectorTriggerRef.current?.focus();
    wasInspectorOpen.current = inspectorOpen;
  }, [inspectorOpen]);

  const submit = () => {
    const value = draft.trim();
    if (!value || sending) return;
    setDraft('');
    onSend(value);
  };

  const lastAssistantId = latestAssistantMessageId(messages);
  const degradation = trace.some((event) => event.eventType.toLowerCase().includes('llm_failed'))
    ? 'llm'
    : trace.some((event) => event.eventType.toLowerCase().includes('vector_retrieval_degraded'))
      ? 'vector'
      : null;

  return (
    <main className="conversation-panel" id="main-content">
      <header className="conversation-header">
        <div className="agent-identity">
          <span className="agent-orb"><Bot size={20} /></span>
          <div><strong>智能客服 Agent</strong><span>当前服务 {userName}</span></div>
        </div>
        <div className="conversation-header__meta">
          <span className="conversation-id" title={conversationId}>{conversationId}</span>
          <span><ShieldCheck size={15} />JWT 安全会话</span>
          <Button ref={inspectorTriggerRef} variant="ghost" className="inspector-trigger" icon={<PanelRightOpen size={17} />} onClick={onOpenInspector} aria-expanded={inspectorOpen} aria-controls="execution-inspector">
            执行详情
          </Button>
        </div>
      </header>

      <section className="message-scroll" aria-live="polite">
        {!messages.length ? (
          <div className="chat-empty">
            <span className="empty-orb"><Sparkles size={28} /></span>
            <span className="eyebrow">Agent ready</span>
            <h1>今天想处理什么问题？</h1>
            <p>从一次真实客服对话开始。每轮结果都会同步展示执行轨迹、工具调用和记忆状态。</p>
            <div className="empty-prompts">
              {quickPrompts.slice(0, 3).map((prompt) => (
                <button key={prompt} onClick={() => onSend(prompt)}>{prompt}<ArrowUp size={15} /></button>
              ))}
            </div>
          </div>
        ) : (
          <div className="message-list">
            {messages.map((message) => (
              <MessageBubble
                key={message.id}
                message={message}
                feedbackEnabled={message.id === lastAssistantId}
                onFeedback={onFeedback}
                degradation={message.id === lastAssistantId ? degradation : null}
              />
            ))}
            {sending ? (
              <div className="typing-row">
                <span className="message-avatar message-avatar--assistant"><Bot size={17} /></span>
                <div><i /><i /><i /><span>Agent 正在处理</span></div>
              </div>
            ) : null}
          </div>
        )}
        <div ref={scrollAnchor} />
      </section>

      <footer className="composer-area">
        {error ? <div className="inline-error composer-error" role="alert"><span>{error}</span><button onClick={onRetry}>重试上一条</button></div> : null}
        <div className={`composer ${sending ? 'composer--busy' : ''}`}>
          <textarea
            value={draft}
            onChange={(event) => {
              setDraft(event.target.value.slice(0, 1000));
              event.currentTarget.style.height = 'auto';
              event.currentTarget.style.height = `${Math.min(event.currentTarget.scrollHeight, 110)}px`;
            }}
            onCompositionStart={() => { composing.current = true; }}
            onCompositionEnd={() => { composing.current = false; }}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey && !composing.current && !event.nativeEvent.isComposing) {
                event.preventDefault();
                submit();
              }
            }}
            placeholder="输入客户问题，Enter 发送，Shift + Enter 换行"
            aria-label="输入客户消息"
            rows={1}
            disabled={sending}
          />
          <div className="composer-footer">
            <span>{draft.length}/1000</span>
            <button className="send-button" disabled={!draft.trim() || sending} onClick={submit} aria-label="发送消息">
              {sending ? <LoaderCircle size={18} className="spin" /> : <ArrowUp size={18} />}
            </button>
          </div>
        </div>
        <p className="composer-note">回复由 Agent 与业务工具协同生成，请在关键业务操作前核验。</p>
      </footer>
    </main>
  );
}
