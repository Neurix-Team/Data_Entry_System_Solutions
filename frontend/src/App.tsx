import { Navigate, Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { ProtectedRoute } from './components/ProtectedRoute';
import { useAuth } from './context/AuthContext';
import { LoginPage } from './pages/LoginPage';
import { AdminDashboardPage } from './pages/admin/AdminDashboardPage';
import { AdminUsersPage } from './pages/admin/AdminUsersPage';
import { AdminDepartmentsPage } from './pages/admin/AdminDepartmentsPage';
import { AdminSubcategoriesPage } from './pages/admin/AdminSubcategoriesPage';
import { AdminFieldsPage } from './pages/admin/AdminFieldsPage';
import { AdminTicketsPage } from './pages/admin/AdminTicketsPage';
import { AdminProjectsPage } from './pages/admin/AdminProjectsPage';
import { AdminReportsPage } from './pages/admin/AdminReportsPage';
import { AdminUserActivityPage } from './pages/admin/AdminUserActivityPage';
import { SubmitTicketPage } from './pages/user/SubmitTicketPage';
import { MyTicketsPage } from './pages/user/MyTicketsPage';
import { UserDashboardPage } from './pages/user/UserDashboardPage';

function RootRedirect() {
  const { user, loading } = useAuth();
  if (loading) return null;
  if (!user) return <Navigate to="/login" replace />;
  return <Navigate to={user.role === 'ADMIN' ? '/admin' : '/dashboard'} replace />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route
        element={
          <ProtectedRoute>
            <Layout />
          </ProtectedRoute>
        }
      >
        {/* Admin */}
        <Route path="/admin" element={<ProtectedRoute roles={['ADMIN']}><AdminDashboardPage /></ProtectedRoute>} />
        <Route path="/admin/tickets" element={<ProtectedRoute roles={['ADMIN']}><AdminTicketsPage /></ProtectedRoute>} />
        <Route path="/admin/users" element={<ProtectedRoute roles={['ADMIN']}><AdminUsersPage /></ProtectedRoute>} />
        <Route path="/admin/departments" element={<ProtectedRoute roles={['ADMIN']}><AdminDepartmentsPage /></ProtectedRoute>} />
        <Route path="/admin/subcategories" element={<ProtectedRoute roles={['ADMIN']}><AdminSubcategoriesPage /></ProtectedRoute>} />
        <Route path="/admin/projects" element={<ProtectedRoute roles={['ADMIN']}><AdminProjectsPage /></ProtectedRoute>} />
        <Route path="/admin/fields" element={<ProtectedRoute roles={['ADMIN']}><AdminFieldsPage /></ProtectedRoute>} />
        <Route path="/admin/reports" element={<ProtectedRoute roles={['ADMIN']}><AdminReportsPage /></ProtectedRoute>} />
        <Route path="/admin/users/:id/activity" element={<ProtectedRoute roles={['ADMIN']}><AdminUserActivityPage /></ProtectedRoute>} />

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
