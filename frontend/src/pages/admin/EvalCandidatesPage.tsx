import { Check, ClipboardCheck, Copy, FileJson, ThumbsDown, ThumbsUp } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { adminApi } from '../../api/admin';
import type { EvalCandidate, EvalCaseMergeItem } from '../../api/adminTypes';
import { toErrorMessage } from '../../api/client';
import { Button } from '../../components/ui/Button';
import { Dialog } from '../../components/ui/Dialog';
import { useAuth } from '../../features/auth/AuthProvider';
import { AdminError, AdminLoading, AdminPageHeader, RefreshButton, StatusBadge, dateTime } from '../../features/admin/AdminPrimitives';
import { useAdminResource } from '../../features/admin/useAdminResource';

type ReviewState = { candidate: EvalCandidate; status: 'APPROVED' | 'REJECTED' } | null;

export function EvalCandidatesPage() {
  const { identity, generation, requestSignal } = useAuth();
  const load = useCallback(() => adminApi.getEvalCandidates(identity!.token, requestSignal), [identity, requestSignal]);
  const resource = useAdminResource(load, generation);
  const [review, setReview] = useState<ReviewState>(null);
  const [reviewNote, setReviewNote] = useState('');
  const [reviewBusy, setReviewBusy] = useState(false);
  const [mutationError, setMutationError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [mergeOpen, setMergeOpen] = useState(false);
  const [mergeJson, setMergeJson] = useState('');
  const [mergeBusy, setMergeBusy] = useState(false);

  useEffect(() => {
    setReview(null);
    setReviewNote('');
    setReviewBusy(false);
    setMergeOpen(false);
    setMergeBusy(false);
    setMutationError(null);
    setSuccess(null);
  }, [generation]);

  const submitReview = async () => {
    if (!review) return;
    setReviewBusy(true);
    setMutationError(null);
    try {
      const response = await adminApi.reviewEvalCandidate(identity!.token, review.candidate.candidateId, review.status, reviewNote.trim(), requestSignal);
      if (requestSignal.aborted) return;
      resource.setData((current) => current?.map((item) => item.candidateId === response.candidate.candidateId ? response.candidate : item) ?? null);
      setReview(null);
      setReviewNote('');
      setSuccess('候选样本审核结果已保存');
    } catch (error) {
      if (!requestSignal.aborted) setMutationError(toErrorMessage(error, '审核失败'));
    } finally {
      setReviewBusy(false);
    }
  };

  const exportDrafts = async () => {
    setMutationError(null);
    try {
      const drafts = await adminApi.exportEvalCandidates(identity!.token, requestSignal);
      if (requestSignal.aborted) return;
      await navigator.clipboard.writeText(JSON.stringify(drafts, null, 2));
      setSuccess(`已复制 ${drafts.length} 条已审核草稿`);
    } catch (error) {
      if (!requestSignal.aborted) setMutationError(toErrorMessage(error, '草稿导出失败'));
    }
  };

  const prepareMerge = async () => {
    setMutationError(null);
    try {
      const drafts = await adminApi.exportEvalCandidates(identity!.token, requestSignal);
      if (requestSignal.aborted) return;
      if (!drafts.length) throw new Error('当前没有 APPROVED 草稿可合并');
      const cases = drafts.map((draft) => ({
        sourceCandidateId: String(draft.sourceCandidateId ?? ''),
        id: String(draft.id ?? ''),
        candidateReply: String(draft.candidateReply ?? ''),
        requiredKeywords: Array.isArray(draft.requiredKeywords) ? draft.requiredKeywords : [],
        forbiddenKeywords: Array.isArray(draft.forbiddenKeywords) ? draft.forbiddenKeywords : [],
        expectedPass: draft.expectedPass === true,
      }));
      setMergeJson(JSON.stringify({ cases }, null, 2));
      setMergeOpen(true);
    } catch (error) {
      if (!requestSignal.aborted) setMutationError(toErrorMessage(error, '草稿加载失败'));
    }
  };

  const merge = async () => {
    setMergeBusy(true);
    setMutationError(null);
    try {
      const parsed = JSON.parse(mergeJson) as { cases?: unknown };
      if (!Array.isArray(parsed.cases) || !parsed.cases.length) throw new Error('cases 必须是非空数组');
      const cases = parsed.cases as EvalCaseMergeItem[];
      for (const item of cases) {
        if (!item.sourceCandidateId || !Array.isArray(item.requiredKeywords) || !Array.isArray(item.forbiddenKeywords)) {
          throw new Error('每条 case 必须包含 sourceCandidateId、requiredKeywords 和 forbiddenKeywords');
        }
      }
      const response = await adminApi.mergeEvalCases(identity!.token, cases, requestSignal);
      if (requestSignal.aborted) return;
      setMergeOpen(false);
      setSuccess(`合并完成：新增 ${response.result.mergedCount} 条，重复跳过 ${response.result.skippedDuplicateIds.length} 条`);
    } catch (error) {
      if (!requestSignal.aborted) setMutationError(toErrorMessage(error, '合并失败'));
    } finally {
      setMergeBusy(false);
    }
  };

  return (
    <main className="admin-page" id="main-content">
      <AdminPageHeader eyebrow="Eval candidates" title="候选样本" description="审核由无用反馈形成的候选样本，导出 JSON 草稿，并通过受控 JSON 对话框补齐断言后合并。" actions={<><Button variant="secondary" icon={<Copy size={16} />} onClick={() => void exportDrafts()}>导出草稿</Button><Button variant="primary" icon={<FileJson size={16} />} onClick={() => void prepareMerge()}>准备合并</Button><RefreshButton loading={resource.loading} onClick={resource.reload} /></>} />
      {success ? <div className="toast-inline" role="status"><Check size={16} />{success}<button onClick={() => setSuccess(null)}>关闭</button></div> : null}
      {mutationError ? <div className="inline-error" role="alert">{mutationError}</div> : null}
      {resource.loading ? <AdminLoading /> : null}
      {resource.error ? <AdminError message={resource.error} onRetry={resource.reload} /> : null}
      {resource.data ? <section className="admin-section">
        <div className="admin-section-heading"><div><h2>最近候选</h2><p>接口只返回最近保留的候选，不支持分页；不要将状态数量解释为全量历史。</p></div><span>{resource.data.length} 条</span></div>
        {!resource.data.length ? <div className="positive-empty"><ClipboardCheck size={20} />当前没有待审核候选</div> : <div className="candidate-list">{resource.data.map((candidate) => <article className="candidate-row" key={candidate.candidateId}><div className="candidate-meta"><StatusBadge tone={candidate.status === 'APPROVED' ? 'success' : candidate.status === 'REJECTED' ? 'danger' : 'warning'}>{candidate.status}</StatusBadge><code>{candidate.candidateId}</code><span>{dateTime(candidate.timestamp)}</span></div><h3>{candidate.issueType}</h3><p>{candidate.assistantReply}</p><dl><div><dt>反馈原因</dt><dd>{candidate.reason || '—'}</dd></div><div><dt>用户 / 会话</dt><dd>{candidate.userId} · {candidate.conversationId}</dd></div>{candidate.reviewNote ? <div><dt>审核备注</dt><dd>{candidate.reviewNote}</dd></div> : null}</dl><div className="candidate-actions"><Button variant="secondary" icon={<ThumbsUp size={15} />} disabled={candidate.status !== 'PENDING_REVIEW'} onClick={() => { setReview({ candidate, status: 'APPROVED' }); setReviewNote(''); }}>通过</Button><Button variant="ghost" icon={<ThumbsDown size={15} />} disabled={candidate.status !== 'PENDING_REVIEW'} onClick={() => { setReview({ candidate, status: 'REJECTED' }); setReviewNote(''); }}>拒绝</Button></div></article>)}</div>}
      </section> : null}

      <Dialog open={Boolean(review)} title={review?.status === 'APPROVED' ? '通过候选样本' : '拒绝候选样本'} description="审核备注将随候选状态保存。该 mutation 不会因网络或 401 自动重放。" onClose={() => !reviewBusy && setReview(null)} footer={<><Button onClick={() => setReview(null)} disabled={reviewBusy}>取消</Button><Button variant={review?.status === 'REJECTED' ? 'danger' : 'primary'} onClick={() => void submitReview()} disabled={reviewBusy}>{reviewBusy ? '保存中…' : '保存审核'}</Button></>}><label className="form-field"><span>审核备注</span><textarea rows={4} maxLength={300} value={reviewNote} onChange={(event) => setReviewNote(event.target.value)} placeholder="说明判断依据（可选，最多 300 字）" /></label></Dialog>
      <Dialog open={mergeOpen} title="补齐并合并 Eval Case" description="这是本地开发文件写操作。请核对 requiredKeywords、forbiddenKeywords 与 expectedPass；生产环境应由部署配置禁用对应后端能力。" onClose={() => !mergeBusy && setMergeOpen(false)} danger footer={<><Button onClick={() => setMergeOpen(false)} disabled={mergeBusy}>取消</Button><Button variant="danger" onClick={() => void merge()} disabled={mergeBusy}>{mergeBusy ? '合并中…' : '确认写入评测集'}</Button></>}><label className="form-field"><span>Merge Request JSON</span><textarea className="json-editor" rows={18} value={mergeJson} onChange={(event) => setMergeJson(event.target.value)} spellCheck={false} /></label>{mutationError ? <div className="inline-error" role="alert">{mutationError}</div> : null}</Dialog>
    </main>
  );
}
