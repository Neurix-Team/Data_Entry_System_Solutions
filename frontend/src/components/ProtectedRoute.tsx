import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import type { Role } from '../api/types';

interface Props {
  children: React.ReactNode;
  roles?: Role[];
}

/** Home route for each role after login / after an unauthorised redirect. */
function homeFor(role: Role): string {
  if (role === 'SUPER_ADMIN') return '/super';
  if (role === 'ADMIN') return '/admin';
  return '/submit';
}

export function ProtectedRoute({ children, roles }: Props) {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    return (
      <div style={{ display: 'grid', placeItems: 'center', height: '100vh' }}>
        <span className="muted">Loading…</span>
      </div>
    );
  }

  if (!user) {
    return <Navigate to="/login" state={{ from: location }} replace />;
  }

  if (roles && !roles.includes(user.role)) {
    return <Navigate to={homeFor(user.role)} replace />;
  }

  return <>{children}</>;
}
