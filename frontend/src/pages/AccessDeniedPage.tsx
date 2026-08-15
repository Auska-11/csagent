import { ArrowLeft, ShieldX } from 'lucide-react';
import { Link } from 'react-router-dom';

export function AccessDeniedPage() {
  return (
    <main className="placeholder-page" id="main-content">
      <div className="placeholder-card">
        <span className="placeholder-icon"><ShieldX size={25} /></span>
        <span className="eyebrow">403 · Access denied</span>
        <h1>当前身份没有访问权限</h1>
        <p>管理页面仅向 ADMIN 开放。前端不会展示或保留上一个身份加载的管理数据，服务端仍会独立执行角色、租户和对象归属校验。</p>
        <Link to="/workspace"><ArrowLeft size={16} />返回客服工作台</Link>
      </div>
    </main>
  );
}
