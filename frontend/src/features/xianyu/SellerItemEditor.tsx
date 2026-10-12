import { useState } from 'react';
import { xianyuApi, type ItemProfile, type ItemView, type SellerPolicy } from '../../api/xianyu';
import { toErrorMessage } from '../../api/client';
import { useAuth } from '../auth/AuthProvider';
import { Button } from '../../components/ui/Button';

export function SellerItemEditor({ item, onSaved }: { item: ItemView; onSaved: () => void }) {
  const { identity, requestSignal } = useAuth();
  const [profile, setProfile] = useState<ItemProfile>(() => item.profile?.draft ?? {
    title: item.facts?.title ?? '', productCategory: item.facts?.productCategory ?? '', productModel: item.facts?.productModel ?? '',
    description: item.facts?.description ?? '', condition: item.facts?.condition ?? '', accessories: item.facts?.accessories ?? '',
    sellerPolicy: { bargainAccepted: false, floorPrice: null, allowedDiscount: null, freeShippingCondition: '', shippingArrangement: '' },
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [dirty, setDirty] = useState(false);
  const version = item.profile?.version ?? 0;
  function field(key: keyof Omit<ItemProfile, 'sellerPolicy'>, value: string) {
    setProfile((current) => ({ ...current, [key]: value })); setDirty(true);
  }
  function policy(key: keyof SellerPolicy, value: string | boolean | null) {
    setProfile((current) => ({ ...current, sellerPolicy: { ...current.sellerPolicy, [key]: value } })); setDirty(true);
  }
  async function save(publish: boolean) {
    setBusy(true); setError('');
    try {
      if (publish) await xianyuApi.publish(identity!.token, item.itemId, version, requestSignal);
      else await xianyuApi.saveDraft(identity!.token, item.itemId, version, profile, requestSignal);
      onSaved();
    } catch (failure) { if (!requestSignal.aborted) setError(toErrorMessage(failure, '保存失败，请重新加载后核对')); }
    finally { setBusy(false); }
  }
  return <section className="admin-section seller-editor">
    <div className="admin-section-heading"><div><h2>商品档案 · {item.itemId}</h2><p>{item.profile?.published ? '已有发布版本，编辑草稿期间仍按发布内容回答' : '核对商品描述和卖家授权后发布'} · 版本 {version}</p></div></div>
    <p className="seller-facts">当前价格：{item.facts?.price.min == null ? '未知' : `¥${item.facts.price.min}${item.facts.price.max !== item.facts.price.min ? `～${item.facts.price.max}` : ''}`}　库存：{item.facts == null || item.facts.stock < 0 ? '未知' : item.facts.stock}。价格和库存由平台同步。</p>
    <form className="form-stack" onSubmit={(event) => { event.preventDefault(); void save(false); }}>
      <div className="seller-form-grid">
        <label className="form-field"><span>标题</span><input required maxLength={200} value={profile.title} onChange={(event) => field('title', event.target.value)} /></label>
        <label className="form-field"><span>品类（如 headphones）</span><input maxLength={64} value={profile.productCategory} onChange={(event) => field('productCategory', event.target.value)} /></label>
        <label className="form-field"><span>型号</span><input maxLength={100} value={profile.productModel} onChange={(event) => field('productModel', event.target.value)} /></label>
      </div>
      <label className="form-field"><span>商品描述</span><textarea rows={4} maxLength={3000} value={profile.description} onChange={(event) => field('description', event.target.value)} /></label>
      <label className="form-field"><span>这件商品的成色和实测状态</span><textarea rows={3} maxLength={1000} value={profile.condition} onChange={(event) => field('condition', event.target.value)} /></label>
      <label className="form-field"><span>配件、原盒情况</span><textarea rows={2} maxLength={1000} value={profile.accessories} onChange={(event) => field('accessories', event.target.value)} /></label>
      <h3>卖家授权</h3>
      <label><input type="checkbox" checked={profile.sellerPolicy.bargainAccepted} onChange={(event) => policy('bargainAccepted', event.target.checked)} /> 允许机器人按以下规则议价</label>
      <div className="seller-form-grid">
        <label className="form-field"><span>最低可接受价（仅卖家可见）</span><input inputMode="decimal" value={profile.sellerPolicy.floorPrice ?? ''} onChange={(event) => policy('floorPrice', event.target.value || null)} /></label>
        <label className="form-field"><span>每次允许让价</span><input inputMode="decimal" value={profile.sellerPolicy.allowedDiscount ?? ''} onChange={(event) => policy('allowedDiscount', event.target.value || null)} /></label>
      </div>
      <label className="form-field"><span>包邮口径（留空则交卖家确认）</span><input maxLength={500} value={profile.sellerPolicy.freeShippingCondition} onChange={(event) => policy('freeShippingCondition', event.target.value)} /></label>
      <label className="form-field"><span>发货安排（留空则交卖家确认）</span><input maxLength={500} value={profile.sellerPolicy.shippingArrangement} onChange={(event) => policy('shippingArrangement', event.target.value)} /></label>
      {error ? <p className="seller-error" role="alert">{error}</p> : null}
      <div className="seller-actions"><Button type="submit" disabled={busy || !dirty}>保存草稿</Button><Button type="button" variant="primary" disabled={busy || dirty || !item.profile?.draft} onClick={() => void save(true)}>审核并发布当前草稿</Button></div>
    </form>
  </section>;
}
