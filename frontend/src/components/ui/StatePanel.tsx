import type { LucideIcon } from 'lucide-react';
import { Inbox } from 'lucide-react';
import { Button } from './Button';

interface StatePanelProps {
  title: string;
  description: string;
  icon?: LucideIcon;
  actionLabel?: string;
  onAction?: () => void;
  compact?: boolean;
}

export function StatePanel({
  title,
  description,
  icon: Icon = Inbox,
  actionLabel,
  onAction,
  compact = false,
}: StatePanelProps) {
  return (
    <div className={`state-panel ${compact ? 'state-panel--compact' : ''}`}>
      <span className="state-panel__icon"><Icon size={20} /></span>
      <div>
        <strong>{title}</strong>
        <p>{description}</p>
      </div>
      {actionLabel && onAction ? <Button onClick={onAction}>{actionLabel}</Button> : null}
    </div>
  );
}
