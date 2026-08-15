import { AlertTriangle, ArrowLeft, CheckCircle2, Clock3, RefreshCw } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { adminApi } from '../../api/admin';
import type { HandoffConfirmRequest, PendingHandoffAuditEvent, PendingHandoffDetail } from '../../api/adminTypes';
import { ApiError, toErrorMessage } from '../../api/client';
import { Button } from '../../components/ui/Button';
import { Dialog } from '../../components/ui/Dialog';
import { StatePanel } from '../../components/ui/StatePanel';
import { useAuth } from '../../features/auth/AuthProvider';
import { AdminPageHeader, StatusBadge, dateTime } from '../../features/admin/AdminPrimitives';

type Action = 'retry' | 'reconcile' | 'confirm' | 'close';

export function HandoffDetailPage() {
  const { outboxId = '' } = useParams();
  const navigate = useNavigate();
  const { identity, generation, requestSignal } = useAuth();
  const [detail, setDetail] = useState<PendingHandoffDetail | null>(null);
  const [audit, setAudit] = useState<PendingHandoffAuditEvent[]>([]);
  const [auditCursor, setAuditCursor] = useState<string | null>(null);
  const [auditHasMore, setAuditHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [auditError, setAuditError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const [action, setAction] = useState<Action | null>(null);
  const [reasonCode, setReasonCode] = useState('ADMIN_REVIEW');
  const [decisionNote, setDecisionNote] = useState('');
  const [ticketId, setTicketId] = useState('');
  const [ticketStatus, setTicketStatus] = useState('CREATED');
  const [ticketCreatedAt, setTicketCreatedAt] = useState(new Date().toISOString());
  const [mutating, setMutating] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    setAuditError(null);
    setDetail(null);
    const [recordResult, auditResult] = await Promise.allSettled([
      adminApi.getHandoff(identity!.token, outboxId, requestSignal),
      adminApi.getHandoffAudit(identity!.token, outboxId, undefined, requestSignal),
    ]);
    if (requestSignal.aborted) return;
    if (recordResult.status === 'fulfilled') {
      setDetail(recordResult.value);
    } else {
      const requestError = recordResult.reason;
      if (requestError instanceof ApiError && requestError.status === 404) {
        setError('记录不存在或当前租户不可见。服务端使用相同 404 防止跨租户探测。');
      } else {
        setError(toErrorMessage(requestError, '详情加载失败'));
      }
    }
    if (auditResult.status === 'fulfilled') {
      setAudit(auditResult.value.events);
      setAuditCursor(auditResult.value.nextCursor);
      setAuditHasMore(auditResult.value.hasMore);
    } else {
      setAuditError(toErrorMessage(auditResult.reason, '审计记录加载失败；详情仍可继续查看和处置'));
    }
    setLoading(false);
  }, [identity, outboxId, requestSignal]);

  useEffect(() => { void load(); }, [generation, load, reloadKey]);

  useEffect(() => {
    setAction(null);
    setActionError(null);
    setMutating(false);
    setNotice(null);
  }, [generation]);

  const openAction = (next: Action) => {
    setAction(next);
    setReasonCode(next === 'close' ? 'ADMIN_CLOSE' : next === 'confirm' ? 'ADMIN_CONFIRMED' : next === 'reconcile' ? 'ADMIN_RECONCILE' : 'ADMIN_RETRY');
    setDecisionNote('');
    setNotice(null);
    setActionError(null);
  };

  const submit = async () => {
    if (!action || !detail) return;
    if (!/^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*$/.test(reasonCode)) {
      setActionError('Reason code 仅允许大写字母、数字和单下划线');
      return;
    }
    if (action === 'confirm') {
      if (!/^[A-Za-z0-9][A-Za-z0-9_.:-]*$/.test(ticketId)) {
        setActionError('Ticket ID 必须以字母或数字开头，且只能包含字母、数字、点、下划线、冒号或连字符');
        return;
      }
      if (!/^[A-Za-z][A-Za-z0-9_.-]*$/.test(ticketStatus)) {
        setActionError('Ticket status 必须以字母开头，且只能包含字母、数字、点、下划线或连字符');
        return;
      }
      const validInstant = ticketCreatedAt.length <= 128
        && /^\d{4}-\d{2}-\d{2}T.+(?:Z|[+-]\d{2}:\d{2})$/.test(ticketCreatedAt)
        && !Number.isNaN(Date.parse(ticketCreatedAt));
      if (!validInstant) {
        setActionError('Created at 必须是带时区的 ISO-8601 时间，例如 2026-08-16T10:30:00Z');
        return;
      }
    }
    setMutating(true);
    setError(null);
    setActionError(null);
    try {
      const base = { expectedVersion: detail.version, reasonCode, decisionNote };
      const response = action === 'confirm'
        ? await adminApi.confirmHandoff(identity!.token, outboxId, {
            ...base,
            result: { ticketId, status: ticketStatus, createdAt: ticketCreatedAt },
          } satisfies HandoffConfirmRequest, requestSignal)
        : await adminApi.decideHandoff(identity!.token, outboxId, action, base, requestSignal);
      setDetail(response.record);
      setAudit((current) => [response.auditEvent, ...current.filter((event) => event.auditId !== response.auditEvent.auditId)]);
      setAction(null);
      setNotice('处置已保存，详情与审计事件已原子更新。');
    } catch (requestError) {
      if (requestSignal.aborted) return;
      if (requestError instanceof ApiError && requestError.code === 'PENDING_HANDOFF_VERSION_CONFLICT') {
        setAction(null);
        await load();
        setNotice('记录版本已发生变化，已刷新最新版本，请重新确认。');
      } else if (requestError instanceof ApiError && requestError.code === 'PENDING_HANDOFF_STATE_CONFLICT') {
        setAction(null);
        await load();
        setNotice('记录当前状态已不允许该操作，已刷新最新详情。');
      } else if (requestError instanceof ApiError && requestError.code === 'PENDING_HANDOFF_RESULT_CONFLICT') {
        setAction(null);
        await load();
        setError('提交的工单结果与已有权威证据冲突，请核对外部系统后再处理。');
      } else if (requestError instanceof ApiError && requestError.status === 503 && action === 'reconcile') {
        setAction(null);
        setNotice('结果查询不可用，记录状态未改变；这表示“结果未知”，不是“工单不存在”。');
      } else if (requestError instanceof ApiError && requestError.code === 'PENDING_HANDOFF_REMOTE_TICKET_NOT_FOUND') {
        setAction(null);
        setNotice('权威查询已完成：远端没有对应工单；本地复核记录保持不变。');
      } else if (requestError instanceof ApiError && requestError.code === 'PENDING_HANDOFF_NOT_FOUND') {
        navigate('/admin/handoffs', { replace: true });
      } else {
        setActionError(toErrorMessage(requestError, '处置失败；请求不会自动重放'));
      }
    } finally {
      if (!requestSignal.aborted) setMutating(false);
    }
  };

  const loadMoreAudit = async () => {
    if (!auditCursor) return;
    try {
      const page = await adminApi.getHandoffAudit(identity!.token, outboxId, auditCursor, requestSignal);
      setAuditError(null);
      setAudit((current) => [...current, ...page.events]);
      setAuditCursor(page.nextCursor);
      setAuditHasMore(page.hasMore);
    } catch (requestError) {
      if (!requestSignal.aborted) setAuditError(toErrorMessage(requestError, '审计记录加载失败'));
    }
  };

  return (
    <main className="admin-page" id="main-content">
      <Link className="back-link" to="/admin/handoffs"><ArrowLeft size={16} />返回复核队列</Link>
      <AdminPageHeader eyebrow="Handoff detail" title={detail?.outboxId ?? '人工复核详情'} description="查看安全摘要、已确认工单、处置入口和追加式审计时间线。expectedVersion 始终取自当前详情且不可编辑。" updatedAt={detail?.updatedAt} actions={<Button variant="secondary" icon={<RefreshCw size={16} className={loading ? 'spin' : ''} />} onClick={() => setReloadKey((current) => current + 1)}>刷新</Button>} />
      {notice ? <div className="notice-banner" role="status"><CheckCircle2 size={17} />{notice}</div> : null}
      {error ? <div className="inline-error" role="alert">{error}</div> : null}
      {loading ? <div className="admin-skeleton"><i /><i /><i /></div> : null}
      {!loading && !detail && error ? <StatePanel icon={AlertTriangle} title="无法显示该记录" description={error} actionLabel="返回队列" onAction={() => navigate('/admin/handoffs')} /> : null}
      {detail ? <div className="handoff-detail-grid">
        <div className="admin-stack">
          <section className="admin-section handoff-summary">
            <div className="admin-section-heading"><div><h2>安全摘要</h2><p>不包含 tenantId、userId、conversationId、customerId 或 idempotencyKey。</p></div><StatusBadge tone={detail.status === 'NEEDS_MANUAL_REVIEW' || detail.status === 'RESULT_UNKNOWN' ? 'warning' : detail.status === 'CONFIRMED' ? 'success' : 'neutral'}>{detail.status}</StatusBadge></div>
            <p className="summary-preview">{detail.summaryPreview || '无摘要预览'}</p>
            <dl className="detail-kv"><div><dt>Handoff reason</dt><dd>{detail.handoffReasonCode || '—'}</dd></div><div><dt>Failure code</dt><dd>{detail.failureCode || '—'}</dd></div><div><dt>Product line</dt><dd>{detail.productLineCode || '—'}</dd></div><div><dt>Issue type</dt><dd>{detail.issueTypeCode || '—'}</dd></div><div><dt>Priority</dt><dd>{detail.priorityCode || '—'}</dd></div><div><dt>Attempt / Version</dt><dd>{detail.attemptCount} / {detail.version}</dd></div><div><dt>Scheduled</dt><dd>{dateTime(detail.scheduledAt)}</dd></div><div><dt>Created</dt><dd>{dateTime(detail.createdAt)}</dd></div></dl>
          </section>
          <section className="admin-section">
            <div className="admin-section-heading"><div><h2>已确认工单</h2><p>仅展示服务端允许返回的最小投影。</p></div></div>
            {detail.confirmedTicket ? <dl className="detail-kv"><div><dt>Ticket ID</dt><dd>{detail.confirmedTicket.ticketId}</dd></div><div><dt>Status</dt><dd>{detail.confirmedTicket.status}</dd></div><div><dt>Created at</dt><dd>{detail.confirmedTicket.createdAt}</dd></div></dl> : <div className="positive-empty">尚无确认结果；不要把它解释为远端工单不存在</div>}
          </section>
          {detail.status === 'NEEDS_MANUAL_REVIEW' ? <section className="admin-section"><div className="admin-section-heading"><div><h2>处置操作</h2><p>所有操作使用当前 version {detail.version}；mutation 不会自动重放。</p></div></div><div className="decision-actions"><Button variant="secondary" onClick={() => openAction('retry')}>Retry</Button><Button variant="secondary" onClick={() => openAction('reconcile')}>Reconcile</Button><Button variant="primary" onClick={() => openAction('confirm')}>Confirm</Button><Button variant="danger" onClick={() => openAction('close')}>Close</Button></div></section> : null}
        </div>
        <section className="admin-section audit-section">
          <div className="admin-section-heading"><div><h2>审计时间线</h2><p>追加式人工复核事件。</p></div></div>
          {auditError ? <div className="inline-error" role="alert">{auditError} <button className="inline-link-button" onClick={() => void load()}>重新加载</button></div> : null}
          {!auditError && !audit.length ? <div className="positive-empty">尚无人工审计事件</div> : null}
          {audit.length ? <div className="audit-timeline">{audit.map((event) => <article key={event.auditId}><span className="audit-dot" /><div><header><strong>{event.action}</strong><time>{dateTime(event.occurredAt)}</time></header><p>{event.reasonCode}{event.decisionNote ? ` · ${event.decisionNote}` : ''}</p><dl><div><dt>Actor</dt><dd>{event.actorId} / {event.authorityUsed}</dd></div><div><dt>Status</dt><dd>{event.fromStatus} → {event.toStatus}</dd></div><div><dt>Version</dt><dd>{event.fromVersion} → {event.toVersion}</dd></div><div><dt>Evidence</dt><dd>{event.evidenceSource}</dd></div></dl></div></article>)}</div> : null}
          {auditHasMore ? <Button onClick={() => void loadMoreAudit()}>加载更多审计</Button> : null}
        </section>
      </div> : null}

      <Dialog open={Boolean(action)} title={action === 'confirm' ? '确认外部工单结果' : action === 'close' ? '关闭复核记录' : action === 'retry' ? '安排重试' : '核对远端结果'} description={action === 'close' ? 'Close 会进入终态，Worker 将不再扫描这条记录。' : action === 'retry' ? 'Retry 只安排进入重试队列，不代表页面请求会立即创建远端工单。' : action === 'reconcile' ? '服务端结果查询不可用时会返回 503，并保持记录不变。' : '证据来源由服务端固定为管理员外部核对。'} danger={action === 'close'} onClose={() => !mutating && setAction(null)} footer={<><Button onClick={() => setAction(null)} disabled={mutating}>取消</Button><Button variant={action === 'close' ? 'danger' : 'primary'} onClick={() => void submit()} disabled={mutating}>{mutating ? '提交中…' : `确认 ${action ?? ''}`}</Button></>}>
        <div className="form-stack"><label className="form-field"><span>Expected version</span><input value={detail?.version ?? ''} readOnly /></label><label className="form-field"><span>Reason code</span><input value={reasonCode} maxLength={64} onChange={(event) => setReasonCode(event.target.value.toUpperCase())} /></label><label className="form-field"><span>Decision note</span><textarea rows={3} maxLength={1000} value={decisionNote} onChange={(event) => setDecisionNote(event.target.value)} /></label>{action === 'confirm' ? <><label className="form-field"><span>Ticket ID</span><input value={ticketId} maxLength={128} onChange={(event) => setTicketId(event.target.value)} /></label><label className="form-field"><span>Ticket status</span><input value={ticketStatus} maxLength={64} onChange={(event) => setTicketStatus(event.target.value)} /></label><label className="form-field"><span>Created at (ISO-8601)</span><input value={ticketCreatedAt} maxLength={128} onChange={(event) => setTicketCreatedAt(event.target.value)} /></label></> : null}{actionError ? <div className="inline-error" role="alert">{actionError}</div> : null}</div>
      </Dialog>
    </main>
  );
}
