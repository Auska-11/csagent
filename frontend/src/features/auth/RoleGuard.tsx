import { Navigate, Outlet } from 'react-router-dom';
import type { Role } from '../../api/types';
import { useAuth } from './AuthProvider';

export function canAccessRole(roles: Role[], required: Role) {
  return roles.includes(required);
}

export function RoleGuard({ role }: { role: Role }) {
  const { hasRole } = useAuth();
  if (!hasRole(role)) {
    return <Navigate to="/access-denied" replace />;
  }

  return <Outlet />;
}
