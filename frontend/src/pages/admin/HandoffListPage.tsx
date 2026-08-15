import { ArrowRight, Inbox, RefreshCw } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { adminApi } from '../../api/admin';
import type { PendingHandoffListItem, PendingHandoffStatus } from '../../api/adminTypes';
import { ApiError, toErrorMessage } from '../../api/client';
import { Button } from '../../components/ui/Button';
import { StatePanel } from '../../components/ui/StatePanel';
import { useAuth } from '../../features/auth/AuthProvider';
import { AdminPageHeader, StatusBadge, dateTime } from '../../features/admin/AdminPrimitives';

const statuses: PendingHandoffStatus[] = ['NEEDS_MANUAL_REVIEW', 'RESULT_UNKNOWN', 'RETRY_SCHEDULED', 'PROCESSING', 'CONFIRMED', 'MANUALLY_CLOSED', 'PREPARED'];

function statusTone(status: PendingHandoffStatus) {
  if (status === 'CONFIRMED') return 'success' as const;
  if (status === 'MANUALLY_CLOSED') return 'neutral' as const;
  if (status === 'RESULT_UNKNOWN' || status === 'NEEDS_MANUAL_REVIEW') return 'warning' as const;
  return 'info' as const;
}

export function HandoffListPage() {
  const { identity, generation, requestSignal } = useAuth();
  const [status, setStatus] = useState<PendingHandoffStatus>('NEEDS_MANUAL_REVIEW');
  const [items, setItems] = useState<PendingHandoffListItem[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  const loadFirstPage = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const page = await adminApi.getHandoffs(identity!.token, status, undefined, requestSignal);
      setItems(page.items);
      setCursor(page.nextCursor);
      setHasMore(page.hasMore);
    } catch (requestError) {
      if (!requestSignal.aborted) setError(toErrorMessage(requestError, '复核队列加载失败'));
    } finally {
      if (!requestSignal.aborted) setLoading(false);
    }
  }, [identity, requestSignal, status]);

  useEffect(() => {
    setItems([]);
    setCursor(null);
    setHasMore(false);
    void loadFirstPage();
  }, [generation, loadFirstPage, reloadKey]);

  const loadMore = async () => {
    if (!cursor || loadingMore) return;
    setLoadingMore(true);
    try {
      const page = await adminApi.getHandoffs(identity!.token, status, cursor, requestSignal);
      setItems((current) => [...current, ...page.items]);
      setCursor(page.nextCursor);
      setHasMore(page.hasMore);
    } catch (requestError) {
      if (requestSignal.aborted) return;
      if (requestError instanceof ApiError && requestError.code === 'INVALID_PENDING_HANDOFF_CURSOR') {
        setCursor(null);
        setHasMore(false);
        await loadFirstPage();
        if (!requestSignal.aborted) setError('分页游标已失效，已自动从第一页重新加载。');
      } else {
        setError(toErrorMessage(requestError, '加载更多失败，请稍后重试'));
      }
    } finally {
      if (!requestSignal.aborted) setLoadingMore(false);
    }
  };

  return (
    <main className="admin-page" id="main-content">
      <AdminPageHeader eyebrow="Human review" title="人工复核" description="处理结果未知或需要人工判断的 Handoff Outbox。列表按 JWT tenant 隔离，前端不会提交 tenant、actor 或证据来源。" actions={<Button variant="secondary" icon={<RefreshCw size={16} className={loading ? 'spin' : ''} />} onClick={() => setReloadKey((current) => current + 1)} disabled={loading}>刷新</Button>} />
      <div className="admin-filterbar"><label><span>状态</span><select value={status} onChange={(event) => setStatus(event.target.value as PendingHandoffStatus)}>{statuses.map((item) => <option key={item} value={item}>{item}</option>)}</select></label><span>每次加载 20 条 · opaque cursor</span></div>
      {error ? <div className="inline-error" role="alert">{error}</div> : null}
      {loading ? <div className="admin-skeleton"><i /><i /><i /></div> : null}
      {!loading && !items.length ? <StatePanel icon={Inbox} title="当前没有需要人工处理的记录" description={`状态 ${status} 下没有可见记录。租户边界与对象可见性由服务端强制执行。`} /> : null}
      {items.length ? <section className="admin-section handoff-table"><div className="table-scroll" tabIndex={0} aria-label="Handoff 复核队列，可横向滚动"><table><caption className="sr-only">Handoff 复核队列</caption><thead><tr><th>更新时间</th><th>Outbox ID</th><th>状态</th><th>转接原因</th><th>Failure code</th><th>尝试</th><th>下次计划</th><th>Version</th><th><span className="sr-only">操作</span></th></tr></thead><tbody>{items.map((item) => <tr key={item.outboxId}><td>{dateTime(item.updatedAt)}</td><td><code>{item.outboxId}</code></td><td><StatusBadge tone={statusTone(item.status)}>{item.status}</StatusBadge></td><td>{item.handoffReasonCode || '—'}</td><td><code>{item.failureCode || '—'}</code></td><td>{item.attemptCount}</td><td>{dateTime(item.scheduledAt)}</td><td>{item.version}</td><td><Link className="row-link" to={`/admin/handoffs/${encodeURIComponent(item.outboxId)}`}>详情<ArrowRight size={15} /></Link></td></tr>)}</tbody></table></div>{hasMore ? <div className="load-more"><Button onClick={() => void loadMore()} disabled={loadingMore}>{loadingMore ? '加载中…' : '加载更多'}</Button></div> : null}</section> : null}
    </main>
  );
}
