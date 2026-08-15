import {
  Activity,
  Bot,
  Boxes,
  ChevronDown,
  ClipboardCheck,
  Headphones,
  LayoutDashboard,
  LogOut,
  RefreshCw,
  Sparkles,
  Menu,
  X,
} from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../../features/auth/AuthProvider';
import { Button } from '../ui/Button';
import { StatePanel } from '../ui/StatePanel';

const adminLinks = [
  { to: '/admin/monitor', label: '运行监控', icon: LayoutDashboard },
  { to: '/admin/handoffs', label: '人工复核', icon: Headphones },
  { to: '/admin/evals', label: '评测', icon: ClipboardCheck },
  { to: '/admin/capabilities', label: '能力目录', icon: Boxes },
];

export function AppShell() {
  const { identities, identity, loading, error, refresh, selectIdentity, hasRole } = useAuth();
  const [navOpen, setNavOpen] = useState(false);
  const navTriggerRef = useRef<HTMLButtonElement>(null);
  const navCloseRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!navOpen) return;
    navCloseRef.current?.focus();
    const close = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setNavOpen(false);
    };
    document.addEventListener('keydown', close);
    return () => {
      document.removeEventListener('keydown', close);
      navTriggerRef.current?.focus();
    };
  }, [navOpen]);

  if (loading) {
    return (
      <div className="app-shell" aria-label="正在建立安全会话">
        <aside className="app-rail"><span className="brand-mark"><Bot size={22} /></span></aside>
        <section className="app-main"><header className="app-header"><div className="app-header__title"><span className="eyebrow">Smart CS Agent</span><strong>智能客服控制台</strong></div><div className="loading-line"><span /></div></header><main className="admin-page"><div className="admin-skeleton"><i /><i /><i /></div></main></section>
      </div>
    );
  }

  if (!identity) {
    return (
      <main className="auth-state">
        <StatePanel
          title="无法登录 Demo 工作台"
          description={`${error ?? '未找到可用身份'}。请确认 Spring Boot 已在 8080 端口运行且 Demo issuer 已开启。`}
          icon={Activity}
          actionLabel="重新连接"
          onAction={() => void refresh()}
        />
      </main>
    );
  }

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">跳到主内容</a>
      <aside id="primary-navigation" className={`app-rail ${navOpen ? 'app-rail--open' : ''}`} aria-label="主导航">
        <button ref={navCloseRef} className="mobile-nav-close" aria-label="关闭导航" onClick={() => setNavOpen(false)}><X size={20} /></button>
        <NavLink to="/workspace" className="brand-mark" title="Smart CS">
          <Sparkles size={21} />
        </NavLink>
        <nav className="rail-nav">
          <NavLink to="/workspace" onClick={() => setNavOpen(false)} className={({ isActive }) => `rail-link ${isActive ? 'active' : ''}`}>
            <Bot size={20} /><span>工作台</span>
          </NavLink>
          {hasRole('ADMIN')
            ? adminLinks.map(({ to, label, icon: Icon }) => (
                <NavLink key={to} to={to} onClick={() => setNavOpen(false)} className={({ isActive }) => `rail-link ${isActive ? 'active' : ''}`}>
                  <Icon size={20} /><span>{label}</span>
                </NavLink>
              ))
            : null}
        </nav>
        <div className="rail-status" title="当前 Demo 凭据已签发"><span />会话</div>
      </aside>

      <section className="app-main">
        <header className="app-header">
          <button ref={navTriggerRef} className="mobile-nav-trigger" aria-label="打开导航" aria-expanded={navOpen} aria-controls="primary-navigation" onClick={() => setNavOpen(true)}><Menu size={21} /></button>
          <div className="app-header__title">
            <span className="eyebrow">Smart CS Agent</span>
            <strong>智能客服控制台</strong>
          </div>
          <div className="identity-switcher">
            <span className="avatar avatar--small">{identity.name.slice(0, 1).toUpperCase()}</span>
            <div>
              <span>{identity.name}</span>
              <small>{identity.roles.includes('ADMIN') ? '管理员 · Demo' : '客服访客 · Demo'}</small>
            </div>
            <ChevronDown size={15} />
            <select
              aria-label="切换 Demo 用户"
              value={identity.userId}
              onChange={(event) => selectIdentity(event.target.value)}
            >
              {identities.map((item) => <option key={item.userId} value={item.userId}>{item.name}</option>)}
            </select>
          </div>
          {error ? <span className="credential-warning" role="status" title={error}>凭据刷新失败，正在使用当前会话</span> : null}
          <Button variant="ghost" className="refresh-token" icon={<RefreshCw size={15} />} onClick={() => void refresh()}>
            刷新凭据
          </Button>
          <span className="token-expiry"><LogOut size={14} />内存会话</span>
        </header>
        <Outlet />
      </section>
      {navOpen ? <button className="nav-backdrop" aria-label="关闭导航" onClick={() => setNavOpen(false)} /> : null}
    </div>
  );
}
