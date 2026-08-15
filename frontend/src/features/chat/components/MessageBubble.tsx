import { Bot, Check, Copy, ThumbsDown, ThumbsUp, UserRound } from 'lucide-react';
import { useState } from 'react';
import type { FeedbackRating } from '../../../api/types';
import type { ChatMessage } from '../hooks/useChatWorkspace';

interface MessageBubbleProps {
  message: ChatMessage;
  feedbackEnabled: boolean;
  onFeedback: (messageId: string, rating: FeedbackRating) => void;
  degradation?: 'llm' | 'vector' | null;
}

function formatTime(timestamp: number) {
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(timestamp);
}

export function MessageBubble({ message, feedbackEnabled, onFeedback, degradation = null }: MessageBubbleProps) {
  const [copied, setCopied] = useState(false);

  if (message.role === 'system') {
    return <div className="system-message" role="alert">{message.content}</div>;
  }

  const copy = async () => {
    await navigator.clipboard.writeText(message.content);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1200);
  };

  return (
    <article className={`message message--${message.role}`}>
      <span className={`message-avatar message-avatar--${message.role}`}>
        {message.role === 'assistant' ? <Bot size={17} /> : <UserRound size={17} />}
      </span>
      <div className="message-body">
        <div className="message-meta">
          <strong>{message.role === 'assistant' ? 'Smart CS' : '访客'}</strong>
          <span>{formatTime(message.timestamp)}</span>
        </div>
        <div className="message-content">{message.content}</div>
        {degradation === 'llm' ? <div className="degradation-note">模型调用失败，已使用结构化降级回复</div> : null}
        {degradation === 'vector' ? <div className="degradation-note">向量检索不可用，已使用关键词结果</div> : null}
        {message.feedbackError && message.feedbackRetryRating ? (
          <div className="feedback-error" role="alert">
            <span>{message.feedbackError}</span>
            <button onClick={() => onFeedback(message.id, message.feedbackRetryRating!)}>重试反馈</button>
          </div>
        ) : null}
        {message.role === 'assistant' ? (
          <div className="message-actions">
            <button onClick={() => void copy()} aria-label="复制回复">
              {copied ? <Check size={14} /> : <Copy size={14} />}{copied ? '已复制' : '复制'}
            </button>
            {feedbackEnabled || message.feedback ? (
              <>
                <span className="action-divider" />
                <button
                  className={message.feedback === 'HELPFUL' ? 'selected' : ''}
                  disabled={message.feedbackPending || Boolean(message.feedback)}
                  onClick={() => onFeedback(message.id, 'HELPFUL')}
                  aria-label="回复有帮助"
                >
                  <ThumbsUp size={14} />有用
                </button>
                <button
                  className={message.feedback === 'UNHELPFUL' ? 'selected negative' : ''}
                  disabled={message.feedbackPending || Boolean(message.feedback)}
                  onClick={() => onFeedback(message.id, 'UNHELPFUL')}
                  aria-label="回复没有帮助"
                >
                  <ThumbsDown size={14} />无用
                </button>
              </>
            ) : null}
          </div>
        ) : null}
      </div>
    </article>
  );
}
