import { X } from 'lucide-react';
import { useEffect, useRef, type ReactNode } from 'react';

interface DialogProps {
  open: boolean;
  title: string;
  description?: string;
  children: ReactNode;
  footer?: ReactNode;
  onClose: () => void;
  danger?: boolean;
}

export function Dialog({ open, title, description, children, footer, onClose, danger = false }: DialogProps) {
  const panelRef = useRef<HTMLDivElement>(null);
  const restoreFocus = useRef<HTMLElement | null>(null);
  const onCloseRef = useRef(onClose);

  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    if (!open) return;
    restoreFocus.current = document.activeElement as HTMLElement | null;
    const panel = panelRef.current;
    const focusable = panel?.querySelector<HTMLElement>('button, input, textarea, select, [tabindex]:not([tabindex="-1"])');
    focusable?.focus();

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
    return () => {
      document.removeEventListener('keydown', keydown);
      restoreFocus.current?.focus();
    };
  }, [open]);

  if (!open) return null;
  return (
    <div className="dialog-layer" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <div ref={panelRef} className={`dialog-panel ${danger ? 'dialog-panel--danger' : ''}`} role="dialog" aria-modal="true" aria-labelledby="dialog-title" aria-describedby={description ? 'dialog-description' : undefined}>
        <header>
          <div><h2 id="dialog-title">{title}</h2>{description ? <p id="dialog-description">{description}</p> : null}</div>
          <button onClick={onClose} aria-label="关闭对话框"><X size={20} /></button>
        </header>
        <div className="dialog-body">{children}</div>
        {footer ? <footer>{footer}</footer> : null}
      </div>
    </div>
  );
}

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  description: string;
  confirmLabel?: string;
  danger?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onClose: () => void;
}

export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel = '确认',
  danger = false,
  busy = false,
  onConfirm,
  onClose,
}: ConfirmDialogProps) {
  return (
    <Dialog
      open={open}
      title={title}
      description={description}
      danger={danger}
      onClose={onClose}
      footer={(
        <>
          <button className="button button--secondary" onClick={onClose} disabled={busy}>取消</button>
          <button className={`button ${danger ? 'button--danger' : 'button--primary'}`} onClick={onConfirm} disabled={busy}>{busy ? '处理中…' : confirmLabel}</button>
        </>
      )}
    >
      <p className="dialog-confirm-copy">请确认操作范围与影响后再继续。</p>
    </Dialog>
  );
}
