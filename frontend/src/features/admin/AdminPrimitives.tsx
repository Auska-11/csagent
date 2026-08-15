import { AlertCircle, RefreshCw } from 'lucide-react';
import type { ReactNode } from 'react';
import { Button } from '../../components/ui/Button';
import { StatePanel } from '../../components/ui/StatePanel';

export function AdminPageHeader({
  eyebrow,
  title,
  description,
  updatedAt,
  actions,
}: {
  eyebrow: string;
  title: string;
  description: string;
  updatedAt?: number | string | null;
  actions?: ReactNode;
}) {
  const updated = typeof updatedAt === 'number'
    ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(updatedAt)
    : updatedAt;
  return (
    <header className="admin-page-header">
      <div><span className="eyebrow">{eyebrow}</span><h1>{title}</h1><p>{description}</p>{updated ? <small>最近更新：{updated}</small> : null}</div>
      {actions ? <div className="admin-page-actions">{actions}</div> : null}
    </header>
  );
}

export function AdminLoading() {
  return <div className="admin-skeleton" aria-label="正在加载"><i /><i /><i /><i /></div>;
}

export function AdminError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return <StatePanel icon={AlertCircle} title="数据加载失败" description={message} actionLabel="重新加载" onAction={onRetry} />;
}

export function RefreshButton({ loading, onClick }: { loading: boolean; onClick: () => void }) {
  return <Button variant="secondary" icon={<RefreshCw size={16} className={loading ? 'spin' : ''} />} disabled={loading} onClick={onClick}>刷新</Button>;
}

export function StatusBadge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'success' | 'warning' | 'danger' | 'info' | 'neutral' }) {
  return <span className={`status-badge status-badge--${tone}`}>{children}</span>;
}

export function rate(value: number) {
  return `${(value * 100).toFixed(1)}%`;
}

export function dateTime(value: unknown) {
  if (!value) return '—';
  const date = value instanceof Date ? value : new Date(value as string | number);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' }).format(date);
}
