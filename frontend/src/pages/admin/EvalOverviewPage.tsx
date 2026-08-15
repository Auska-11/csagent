import { ArrowRight, FileJson, RefreshCw } from 'lucide-react';
import { useCallback } from 'react';
import { Link } from 'react-router-dom';
import { adminApi } from '../../api/admin';
import { useAuth } from '../../features/auth/AuthProvider';
import { AdminError, AdminLoading, AdminPageHeader, RefreshButton, dateTime } from '../../features/admin/AdminPrimitives';
import { useAdminResource } from '../../features/admin/useAdminResource';

function reportName(report: Record<string, unknown>, index: number) {
  for (const key of ['name', 'reportName', 'evalType', 'type']) {
    if (typeof report[key] === 'string') return report[key] as string;
  }
  return `Report ${index + 1}`;
}

function reportMetric(report: Record<string, unknown>) {
  const nested = report.summary && typeof report.summary === 'object' && !Array.isArray(report.summary)
    ? report.summary as Record<string, unknown>
    : null;
  const entries = [...Object.entries(nested ?? {}), ...Object.entries(report)]
    .filter(([, value]) => typeof value === 'number');
  const preferred = entries.find(([key]) => /rate|score|accuracy|pass/i.test(key)) ?? entries[0];
  if (!preferred) return '查看原始 JSON';
  const [key, value] = preferred as [string, number];
  const displayValue = /rate|accuracy|pass/i.test(key) && value >= 0 && value <= 1
    ? `${(value * 100).toFixed(1)}%`
    : String(value);
  return `${key}: ${displayValue}`;
}

export function EvalOverviewPage() {
  const { identity, generation, requestSignal } = useAuth();
  const load = useCallback(() => adminApi.getEvalReports(identity!.token, requestSignal), [identity, requestSignal]);
  const resource = useAdminResource(load, generation);

  return (
    <main className="admin-page" id="main-content">
      <AdminPageHeader
        eyebrow="Quality governance"
        title="评测中心"
        description="展示最近生成的离线评测报告，以及服务端提供的 Latest / Previous / Delta。当前契约没有完整历史，不绘制趋势曲线。"
        updatedAt={resource.data?.summary.generatedAt ? String(resource.data.summary.generatedAt) : null}
        actions={<><Link className="button button--primary" to="/admin/evals/candidates">候选样本<ArrowRight size={16} /></Link><RefreshButton loading={resource.loading} onClick={resource.reload} /></>}
      />
      {resource.loading ? <AdminLoading /> : null}
      {resource.error ? <AdminError message={resource.error} onRetry={resource.reload} /> : null}
      {resource.data && !resource.data.available ? (
        <div className="eval-unavailable"><FileJson size={24} /><div><h2>尚未生成评测报告</h2><p>运行后端评测测试后，报告会写入 <code>{resource.data.reportDir}</code>。这也可能表示报告文件无法解析，当前接口无法区分两者。</p></div><button onClick={resource.reload}><RefreshCw size={16} />重新检查</button></div>
      ) : null}
      {resource.data?.available ? (
        <div className="admin-stack">
          <section className="metric-strip metric-strip--compact">
            <div><span>Reports</span><strong>{resource.data.summary.reportCount}</strong><small>最新概览</small></div>
            <div><span>Trend snapshots</span><strong>{resource.data.trend.reportCount}</strong><small>仅 latest / previous / delta</small></div>
            <div><span>Generated</span><strong className="metric-date">{String(resource.data.summary.generatedAt ?? '—')}</strong><small>报告生成时间</small></div>
          </section>
          <section className="admin-section">
            <div className="admin-section-heading"><div><h2>最新报告</h2><p>没有服务端阈值时，指标统一使用中性展示。</p></div></div>
            <div className="report-list">
              {resource.data.summary.reports.map((report, index) => (
                <details className="report-row" key={`${reportName(report, index)}-${index}`}>
                  <summary><span><strong>{reportName(report, index)}</strong><small>{reportMetric(report)}</small></span><code>JSON</code></summary>
                  <pre tabIndex={0}>{JSON.stringify(report, null, 2)}</pre>
                </details>
              ))}
            </div>
          </section>
          <section className="admin-section">
            <div className="admin-section-heading"><div><h2>最近对比</h2><p>仅展示接口返回的对比快照，不推断连续时间序列。</p></div><small>{dateTime(resource.data.trend.generatedAt)}</small></div>
            <div className="report-list">
              {resource.data.trend.reports.map((report, index) => (
                <details className="report-row" key={`trend-${index}`}><summary><span><strong>{reportName(report, index)}</strong><small>Latest / Previous / Delta</small></span><code>JSON</code></summary><pre tabIndex={0}>{JSON.stringify(report, null, 2)}</pre></details>
              ))}
              {!resource.data.trend.reports.length ? <div className="positive-empty">暂无可比较的前一份报告</div> : null}
            </div>
          </section>
        </div>
      ) : null}
    </main>
  );
}
