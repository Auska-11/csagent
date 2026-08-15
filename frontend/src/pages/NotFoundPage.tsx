import { ArrowLeft } from 'lucide-react';
import { Link } from 'react-router-dom';

export function NotFoundPage() {
  return (
    <main className="placeholder-page" id="main-content">
      <div className="placeholder-card"><span className="eyebrow">404</span><h1>页面不存在</h1><p>该地址尚未配置，或已经移动到新的工作区。</p><Link to="/workspace"><ArrowLeft size={16} />返回客服工作台</Link></div>
    </main>
  );
}
