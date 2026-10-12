import { useState } from 'react';
import { xianyuApi, type ManualView } from '../../api/xianyu';
import { toErrorMessage } from '../../api/client';
import { useAuth } from '../auth/AuthProvider';
import { Button } from '../../components/ui/Button';

export function SellerConversationControl() {
  const { identity, requestSignal } = useAuth();
  const [buyerId, setBuyerId] = useState('');
  const [itemId, setItemId] = useState('');
  const [mode, setMode] = useState<ManualView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function request(enabled?: boolean) {
    setBusy(true); setError('');
    try { setMode(enabled === undefined ? await xianyuApi.manualMode(identity!.token, buyerId, itemId, requestSignal)
      : await xianyuApi.setManualMode(identity!.token, buyerId, itemId, enabled, requestSignal)); }
    catch (failure) { if (!requestSignal.aborted) setError(toErrorMessage(failure, '操作失败')); }
    finally { setBusy(false); }
  }
  return <section className="admin-section">
    <div className="admin-section-heading"><div><h2>人工接管</h2><p>输入平台买家 ID 和商品 ID，查看已建立会话的状态。人工回复在闲鱼客户端发送。</p></div></div>
    <form className="seller-actions" onSubmit={(event) => { event.preventDefault(); void request(); }}>
      <label className="form-field"><span>买家 ID</span><input disabled={busy} value={buyerId} pattern="[0-9]{1,64}" required onChange={(event) => { setBuyerId(event.target.value); setMode(null); }} /></label>
      <label className="form-field"><span>商品 ID</span><input disabled={busy} value={itemId} pattern="[0-9]{1,64}" required onChange={(event) => { setItemId(event.target.value); setMode(null); }} /></label>
      <Button type="submit" disabled={busy}>查询</Button>
    </form>
    {mode ? <div className="seller-actions"><p>{mode.manualMode ? '机器人已暂停，等待卖家处理' : '机器人自动回复中'}</p><Button disabled={busy} onClick={() => void request(!mode.manualMode)}>{mode.manualMode ? '恢复自动回复' : '暂停机器人'}</Button></div> : null}
    {error ? <p className="seller-error" role="alert">{error}</p> : null}
  </section>;
}
