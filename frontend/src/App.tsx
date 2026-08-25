import { Navigate, Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { ProtectedRoute } from './components/ProtectedRoute';
import { useAuth } from './context/AuthContext';
import { LoginPage } from './pages/LoginPage';
import { AdminDashboardPage } from './pages/admin/AdminDashboardPage';
import { AdminUsersPage } from './pages/admin/AdminUsersPage';
import { AdminDepartmentsPage } from './pages/admin/AdminDepartmentsPage';
import { AdminSubcategoriesPage } from './pages/admin/AdminSubcategoriesPage';
import { AdminTicketsPage } from './pages/admin/AdminTicketsPage';
import { AdminProjectsPage } from './pages/admin/AdminProjectsPage';
import { AdminReportsPage } from './pages/admin/AdminReportsPage';
import { AdminUserActivityPage } from './pages/admin/AdminUserActivityPage';
import { SubmitTicketPage } from './pages/user/SubmitTicketPage';
import { MyTicketsPage } from './pages/user/MyTicketsPage';
import { UserDashboardPage } from './pages/user/UserDashboardPage';
import { SuperLayout } from './pages/super/SuperLayout';
import { SuperOverviewPage } from './pages/super/SuperOverviewPage';
import { SuperTeamsPage } from './pages/super/SuperTeamsPage';
import { SuperAdminsPage } from './pages/super/SuperAdminsPage';
import { SuperProjectsPage } from './pages/super/SuperProjectsPage';
import { SuperDataPage } from './pages/super/SuperDataPage';
import { SuperApiTokensPage } from './pages/super/SuperApiTokensPage';

function RootRedirect() {
  const { user, loading } = useAuth();
  if (loading) return null;
  if (!user) return <Navigate to="/login" replace />;
  if (user.role === 'SUPER_ADMIN') return <Navigate to="/super" replace />;
  return <Navigate to={user.role === 'ADMIN' ? '/admin' : '/dashboard'} replace />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      {/* Super admin shell — its own layout so the sidebar clearly signals cross-team scope. */}
      <Route
        element={
          <ProtectedRoute roles={['SUPER_ADMIN']}>
            <SuperLayout />
          </ProtectedRoute>
        }
      >
        <Route path="/super" element={<SuperOverviewPage />} />
        <Route path="/super/teams" element={<SuperTeamsPage />} />
        <Route path="/super/projects" element={<SuperProjectsPage />} />
        <Route path="/super/data" element={<SuperDataPage />} />
        <Route path="/super/api-tokens" element={<SuperApiTokensPage />} />
        <Route path="/super/admins" element={<SuperAdminsPage />} />
      </Route>

      {/* Admin + user shell — SUPER_ADMIN is also allowed in (they see either cross-team
          aggregates or scoped data when the impersonation banner is active). */}
      <Route
        element={
          <ProtectedRoute>
            <Layout />
          </ProtectedRoute>
        }
      >
        {/* Admin */}
        <Route path="/admin" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminDashboardPage /></ProtectedRoute>} />
        <Route path="/admin/tickets" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminTicketsPage /></ProtectedRoute>} />
        <Route path="/admin/users" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminUsersPage /></ProtectedRoute>} />
        <Route path="/admin/departments" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminDepartmentsPage /></ProtectedRoute>} />
        <Route path="/admin/subcategories" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminSubcategoriesPage /></ProtectedRoute>} />
        <Route path="/admin/projects" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminProjectsPage /></ProtectedRoute>} />
        <Route path="/admin/reports" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminReportsPage /></ProtectedRoute>} />
        <Route path="/admin/users/:id/activity" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminUserActivityPage /></ProtectedRoute>} />

        {/* User (admins can access too) */}
        <Route path="/dashboard" element={<UserDashboardPage />} />
        <Route path="/submit" element={<SubmitTicketPage />} />
        <Route path="/my-tickets" element={<MyTicketsPage />} />
      </Route>

      <Route path="/" element={<RootRedirect />} />
      <Route path="*" element={<RootRedirect />} />
    </Routes>
  );
}
