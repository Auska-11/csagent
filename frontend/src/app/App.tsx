import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from '../components/layout/AppShell';
import { RoleGuard } from '../features/auth/RoleGuard';
import { ChatWorkspacePage } from '../pages/ChatWorkspacePage';
import { NotFoundPage } from '../pages/NotFoundPage';
import { AccessDeniedPage } from '../pages/AccessDeniedPage';
import { useAuth } from '../features/auth/AuthProvider';

const MonitorPage = lazy(async () => ({ default: (await import('../pages/admin/MonitorPage')).MonitorPage }));
const CapabilitiesPage = lazy(async () => ({ default: (await import('../pages/admin/CapabilitiesPage')).CapabilitiesPage }));
const EvalOverviewPage = lazy(async () => ({ default: (await import('../pages/admin/EvalOverviewPage')).EvalOverviewPage }));
const EvalCandidatesPage = lazy(async () => ({ default: (await import('../pages/admin/EvalCandidatesPage')).EvalCandidatesPage }));
const HandoffListPage = lazy(async () => ({ default: (await import('../pages/admin/HandoffListPage')).HandoffListPage }));
const HandoffDetailPage = lazy(async () => ({ default: (await import('../pages/admin/HandoffDetailPage')).HandoffDetailPage }));
const XianyuSellerPage = lazy(async () => ({ default: (await import('../pages/admin/XianyuSellerPage')).XianyuSellerPage }));

function AdminFallback() {
  return <main className="admin-page"><div className="admin-skeleton"><i /><i /><i /></div></main>;
}

export function App() {
  const { identity } = useAuth();
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<Navigate to={identity?.channel === 'xianyu' && identity.roles.includes('ADMIN') ? '/admin/xianyu' : '/workspace'} replace />} />
        <Route path="workspace" element={<ChatWorkspacePage />} />
        <Route path="access-denied" element={<AccessDeniedPage />} />
        <Route element={<RoleGuard role="ADMIN" />}>
          <Route path="admin/xianyu" element={<Suspense fallback={<AdminFallback />}><XianyuSellerPage /></Suspense>} />
          <Route path="admin/overview" element={<Navigate to="/admin/monitor" replace />} />
          <Route path="admin/monitor" element={<Suspense fallback={<AdminFallback />}><MonitorPage /></Suspense>} />
          <Route path="admin/tools" element={<Navigate to="/admin/capabilities" replace />} />
          <Route path="admin/capabilities" element={<Suspense fallback={<AdminFallback />}><CapabilitiesPage /></Suspense>} />
          <Route path="admin/evals" element={<Suspense fallback={<AdminFallback />}><EvalOverviewPage /></Suspense>} />
          <Route path="admin/evals/candidates" element={<Suspense fallback={<AdminFallback />}><EvalCandidatesPage /></Suspense>} />
          <Route path="admin/handoffs" element={<Suspense fallback={<AdminFallback />}><HandoffListPage /></Suspense>} />
          <Route path="admin/handoffs/:outboxId" element={<Suspense fallback={<AdminFallback />}><HandoffDetailPage /></Suspense>} />
        </Route>
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
