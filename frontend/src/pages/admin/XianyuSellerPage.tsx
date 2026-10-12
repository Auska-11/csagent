import { useCallback, useState } from 'react';
import { xianyuApi } from '../../api/xianyu';
import { useAuth } from '../../features/auth/AuthProvider';
import { useAdminResource } from '../../features/admin/useAdminResource';
import { AdminError, AdminLoading, AdminPageHeader, RefreshButton } from '../../features/admin/AdminPrimitives';
import { SellerItemEditor } from '../../features/xianyu/SellerItemEditor';
import { SellerConversationControl } from '../../features/xianyu/SellerConversationControl';

export function XianyuSellerPage() {
  const { identity, generation, requestSignal } = useAuth();
  const load = useCallback(() => xianyuApi.items(identity!.token, requestSignal), [identity, requestSignal]);
  const resource = useAdminResource(load, generation);
  const [selected, setSelected] = useState('');
  const current = resource.data?.find((item) => item.itemId === selected) ?? resource.data?.[0];
  return <main className="admin-page" id="main-content">
    <AdminPageHeader eyebrow="Xianyu" title="闲鱼卖家工作台" description="审核商品资料、设置议价授权，必要时暂停机器人接管会话。" actions={<RefreshButton loading={resource.loading} onClick={resource.reload} />} />
    {resource.loading ? <AdminLoading /> : null}
    {resource.error ? <AdminError message={resource.error} onRetry={resource.reload} /> : null}
    {resource.data ? <div className="admin-stack">
      <SellerConversationControl key={generation} />
      {current ? <><div className="admin-filterbar"><label>商品<select value={current.itemId} onChange={(event) => setSelected(event.target.value)}>{resource.data.map((item) => <option key={item.itemId} value={item.itemId}>{item.profile?.draft.title || item.facts?.title || item.itemId} · {item.itemId}</option>)}</select></label></div>
        <SellerItemEditor key={`${generation}:${current.itemId}:${current.profile?.version ?? 0}`} item={current} onSaved={resource.reload} /></>
        : <section className="admin-section"><p>商品首次接入后会出现在这里，平台描述会生成待审核草稿。</p></section>}
    </div> : null}
  </main>;
}
