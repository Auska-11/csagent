import { AlertTriangle, ArrowRight, ShieldAlert } from 'lucide-react';
import { useCallback } from 'react';
import { Link } from 'react-router-dom';
import { adminApi } from '../../api/admin';
import { useAuth } from '../../features/auth/AuthProvider';
import { AdminError, AdminLoading, AdminPageHeader, RefreshButton, StatusBadge, dateTime, rate } from '../../features/admin/AdminPrimitives';
import { useAdminResource } from '../../features/admin/useAdminResource';

function outcomeTone(outcome: string) {
  if (outcome === 'SUCCESS') return 'success' as const;
  if (outcome === 'TECHNICAL_FAILURE' || outcome === 'GUARDRAIL_REJECTED') return 'danger' as const;
  return 'warning' as const;
}

export function MonitorPage() {
  const { identity, generation, requestSignal } = useAuth();
  const load = useCallback(
    () => adminApi.getMonitorSummary(identity!.token, requestSignal),
    [identity, requestSignal],
  );
  const resource = useAdminResource(load, generation);

  return (
    <main className="admin-page" id="main-content">
      <AdminPageHeader
        eyebrow="Operations"
        title="运行监控"
        description="查看累计运行质量、风险告警和最近执行记录。当前接口不提供时间序列，因此这里不绘制趋势图。"
        updatedAt={resource.data?.lastUpdatedAt}
        actions={<RefreshButton loading={resource.loading} onClick={resource.reload} />}
      />
      {resource.loading ? <AdminLoading /> : null}
      {resource.error ? <AdminError message={resource.error} onRetry={resource.reload} /> : null}
      {resource.data ? (
        <div className="admin-stack">
          <section className="metric-strip" aria-label="核心指标">
            <div><span>Turn Success</span><strong>{rate(resource.data.turnSuccessRate)}</strong><small>{resource.data.successfulTurnCount}/{resource.data.totalTurns} 成功</small></div>
            <div><span>Tool Success</span><strong>{rate(resource.data.toolSuccessRate)}</strong><small>{resource.data.toolCallCount} 次调用</small></div>
            <div><span>LLM Failure</span><strong>{rate(resource.data.llmFailureRate)}</strong><small>{resource.data.llmFailureCount}/{resource.data.llmCallCount}</small></div>
            <div><span>Helpful</span><strong>{rate(resource.data.helpfulFeedbackRate)}</strong><small>{resource.data.feedbackCount} 条反馈</small></div>
            <div><span>Handoff</span><strong>{resource.data.handoffCount}</strong><small>累计转人工</small></div>
            <div><span>Updated</span><strong className="metric-date">{dateTime(resource.data.lastUpdatedAt)}</strong><small>服务端快照</small></div>
          </section>

          <section className="admin-section">
            <div className="admin-section-heading"><div><h2>风险告警</h2><p>按服务端返回顺序展示当前累计风险信号。</p></div></div>
            {!resource.data.alerts.length ? <div className="positive-empty">当前没有运行风险告警</div> : (
              <div className="alert-list">
                {resource.data.alerts.map((alert) => (
                  <article key={alert.code} className={`admin-alert admin-alert--${alert.severity.toLowerCase()}`}>
                    {alert.severity === 'CRITICAL' ? <ShieldAlert size={20} /> : <AlertTriangle size={20} />}
                    <div><div><StatusBadge tone={alert.severity === 'CRITICAL' ? 'danger' : alert.severity === 'WARN' ? 'warning' : 'info'}>{alert.severity}</StatusBadge><code>{alert.code}</code></div><h3>{alert.title}</h3><p>{alert.message}</p><small>{alert.suggestion}</small></div>
                  </article>
                ))}
              </div>
            )}
          </section>

          <section className="admin-section">
            <div className="admin-section-heading"><div><h2>Turn 结果分布</h2><p>累计统计，不代表特定时间范围。</p></div></div>
            <div className="outcome-strip">
              <div><span>成功</span><strong>{resource.data.successfulTurnCount}</strong></div>
              <div><span>业务拒绝</span><strong>{resource.data.businessRejectedTurnCount}</strong></div>
              <div><span>技术失败</span><strong>{resource.data.technicalFailureTurnCount}</strong></div>
              <div><span>Guardrail</span><strong>{resource.data.guardrailRejectedTurnCount}</strong></div>
              <div><span>用户取消</span><strong>{resource.data.userCancelledTurnCount}</strong></div>
            </div>
          </section>

          <section className="admin-section">
            <div className="admin-section-heading"><div><h2>最近反馈</h2><p>反馈会进入质量治理链路；这里只展示服务端保留的最近记录。</p></div></div>
            {!resource.data.recentFeedbacks.length ? <div className="positive-empty">尚无用户反馈</div> : <div className="table-scroll" tabIndex={0} aria-label="最近反馈表，可横向滚动"><table><caption className="sr-only">最近反馈</caption><thead><tr><th>时间</th><th>评分</th><th>用户 / 会话</th><th>原因</th><th>回复摘要</th></tr></thead><tbody>{resource.data.recentFeedbacks.map((feedback) => <tr key={feedback.feedbackId}><td>{dateTime(feedback.timestamp)}</td><td><StatusBadge tone={feedback.rating === 'HELPFUL' ? 'success' : 'danger'}>{feedback.rating}</StatusBadge></td><td><code>{feedback.userId}</code><small className="cell-subtext">{feedback.conversationId}</small></td><td>{feedback.reason || '—'}</td><td>{feedback.assistantReply.slice(0, 120)}{feedback.assistantReply.length > 120 ? '…' : ''}</td></tr>)}</tbody></table></div>}
          </section>

          <section className="admin-section">
            <div className="admin-section-heading"><div><h2>Eval 候选</h2><p>无用反馈会形成候选样本，审核后才能进入受控评测集合。</p></div><Link to="/admin/evals/candidates">打开候选管理<ArrowRight size={15} /></Link></div>
            <div className="metric-strip metric-strip--compact"><div><span>全部候选</span><strong>{resource.data.evalCandidateCount}</strong></div><div><span>待审核</span><strong>{resource.data.pendingEvalCandidateCount}</strong></div><div><span>已通过</span><strong>{resource.data.approvedEvalCandidateCount}</strong></div></div>
            {resource.data.recentEvalCandidates.length ? <div className="candidate-mini-list">{resource.data.recentEvalCandidates.slice(0, 5).map((candidate) => <div key={candidate.candidateId}><div><StatusBadge tone={candidate.status === 'APPROVED' ? 'success' : candidate.status === 'REJECTED' ? 'danger' : 'warning'}>{candidate.status}</StatusBadge><strong>{candidate.issueType || candidate.suggestedEvalType || '未分类'}</strong></div><p>{candidate.assistantReply.slice(0, 140)}{candidate.assistantReply.length > 140 ? '…' : ''}</p><small>{dateTime(candidate.timestamp)} · {candidate.userId}</small></div>)}</div> : <div className="positive-empty">尚无 Eval 候选样本</div>}
          </section>

          <section className="admin-section">
            <div className="admin-section-heading"><div><h2>工具表现</h2><p>Capability 调用的累计成功率与耗时。</p></div><Link to="/admin/capabilities">查看能力目录<ArrowRight size={15} /></Link></div>
            <div className="table-scroll" tabIndex={0} aria-label="工具表现表，可横向滚动"><table><caption className="sr-only">工具表现</caption><thead><tr><th>能力</th><th>调用</th><th>成功率</th><th>失败</th><th>平均耗时</th></tr></thead><tbody>
              {resource.data.toolStats.length ? resource.data.toolStats.map((tool) => <tr key={tool.toolName}><td><code>{tool.toolName}</code></td><td>{tool.callCount}</td><td>{rate(tool.successRate)}</td><td>{tool.failedCallCount}</td><td>{tool.averageDurationMs}ms</td></tr>) : <tr><td colSpan={5} className="table-empty">尚无工具调用</td></tr>}
            </tbody></table></div>
          </section>

          <section className="admin-section">
            <div className="admin-section-heading"><div><h2>最近 Turn</h2><p>仅展示服务端保留的最近记录。</p></div></div>
            <div className="table-scroll" tabIndex={0} aria-label="最近 Turn 表，可横向滚动"><table><caption className="sr-only">最近 Turn</caption><thead><tr><th>时间</th><th>会话 / 用户</th><th>Outcome</th><th>Tool</th><th>LLM</th><th>Handoff</th><th>总耗时</th></tr></thead><tbody>
              {resource.data.recentTurns.length ? resource.data.recentTurns.map((turn) => <tr key={`${turn.conversationId}-${turn.timestamp}`}><td>{dateTime(turn.timestamp)}</td><td><code>{turn.conversationId}</code><small className="cell-subtext">{turn.userId}</small></td><td><StatusBadge tone={outcomeTone(turn.outcome)}>{turn.outcome}</StatusBadge>{turn.errorCode ? <small className="cell-subtext danger-text">{turn.errorCode}</small> : null}</td><td>{turn.toolCalls} / {turn.failedToolCalls} 失败</td><td>{turn.llmCalls} / {turn.llmFailures} 失败</td><td>{turn.handoffCount}</td><td>{turn.totalDurationMs}ms</td></tr>) : <tr><td colSpan={7} className="table-empty">尚无最近 Turn</td></tr>}
            </tbody></table></div>
          </section>
        </div>
      ) : null}
    </main>
  );
}
