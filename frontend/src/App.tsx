import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { ProtectedRoute } from './components/ProtectedRoute';
import { useAuth } from './context/AuthContext';

const Layout = lazy(() => import('./components/Layout').then((m) => ({ default: m.Layout })));
const LoginRoute = lazy(() => import('./pages/LoginGate').then((m) => ({ default: m.LoginGate })));
const LandingPage = lazy(() => import('./pages/landing/LandingPage').then((m) => ({ default: m.LandingPage })));
const ProfilePage = lazy(() => import('./pages/ProfilePage').then((m) => ({ default: m.ProfilePage })));
const ProjectFoldersPage = lazy(() => import('./pages/project-folders/ProjectFoldersPage').then((m) => ({ default: m.ProjectFoldersPage })));
const ProjectFolderDetailPage = lazy(() => import('./pages/project-folders/ProjectFolderDetailPage').then((m) => ({ default: m.ProjectFolderDetailPage })));

const AdminDashboardPage = lazy(() => import('./pages/admin/AdminDashboardPage').then((m) => ({ default: m.AdminDashboardPage })));
const AdminUsersPage = lazy(() => import('./pages/admin/AdminUsersPage').then((m) => ({ default: m.AdminUsersPage })));
const AdminDepartmentsPage = lazy(() => import('./pages/admin/AdminDepartmentsPage').then((m) => ({ default: m.AdminDepartmentsPage })));
const AdminSubcategoriesPage = lazy(() => import('./pages/admin/AdminSubcategoriesPage').then((m) => ({ default: m.AdminSubcategoriesPage })));
const AdminTicketsPage = lazy(() => import('./pages/admin/AdminTicketsPage').then((m) => ({ default: m.AdminTicketsPage })));
const AdminProjectsPage = lazy(() => import('./pages/admin/AdminProjectsPage').then((m) => ({ default: m.AdminProjectsPage })));
const AdminReportsPage = lazy(() => import('./pages/admin/AdminReportsPage').then((m) => ({ default: m.AdminReportsPage })));
const AdminUserActivityPage = lazy(() => import('./pages/admin/AdminUserActivityPage').then((m) => ({ default: m.AdminUserActivityPage })));
const AdminAssignmentsPage = lazy(() => import('./pages/admin/AdminAssignmentsPage').then((m) => ({ default: m.AdminAssignmentsPage })));
const AdminImportPage = lazy(() => import('./pages/admin/AdminImportPage').then((m) => ({ default: m.AdminImportPage })));
const AdminAnnouncementsPage = lazy(() => import('./pages/admin/AdminAnnouncementsPage').then((m) => ({ default: m.AdminAnnouncementsPage })));
const AdminWorkloadPage = lazy(() => import('./pages/admin/AdminWorkloadPage').then((m) => ({ default: m.AdminWorkloadPage })));
const AdminQualityPage = lazy(() => import('./pages/admin/AdminQualityPage').then((m) => ({ default: m.AdminQualityPage })));
const AdminWeeklyReportPage = lazy(() => import('./pages/admin/AdminWeeklyReportPage').then((m) => ({ default: m.AdminWeeklyReportPage })));

const SubmitTicketPage = lazy(() => import('./pages/user/SubmitTicketPage').then((m) => ({ default: m.SubmitTicketPage })));
const MyTicketsPage = lazy(() => import('./pages/user/MyTicketsPage').then((m) => ({ default: m.MyTicketsPage })));
const UserDashboardPage = lazy(() => import('./pages/user/UserDashboardPage').then((m) => ({ default: m.UserDashboardPage })));
const MyAssignmentsPage = lazy(() => import('./pages/user/MyAssignmentsPage').then((m) => ({ default: m.MyAssignmentsPage })));
const RecycleBinPage = lazy(() => import('./pages/RecycleBinPage').then((m) => ({ default: m.RecycleBinPage })));
const ChatPage = lazy(() => import('./pages/chat/ChatPage'));

const SuperLayout = lazy(() => import('./pages/super/SuperLayout').then((m) => ({ default: m.SuperLayout })));
const SuperOverviewPage = lazy(() => import('./pages/super/SuperOverviewPage').then((m) => ({ default: m.SuperOverviewPage })));
const SuperTeamsPage = lazy(() => import('./pages/super/SuperTeamsPage').then((m) => ({ default: m.SuperTeamsPage })));
const SuperAdminsPage = lazy(() => import('./pages/super/SuperAdminsPage').then((m) => ({ default: m.SuperAdminsPage })));
const SuperProjectsPage = lazy(() => import('./pages/super/SuperProjectsPage').then((m) => ({ default: m.SuperProjectsPage })));
const SuperDataPage = lazy(() => import('./pages/super/SuperDataPage').then((m) => ({ default: m.SuperDataPage })));
const SuperDatasetPage = lazy(() => import('./pages/super/SuperDatasetPage').then((m) => ({ default: m.SuperDatasetPage })));
const SuperApiTokensPage = lazy(() => import('./pages/super/SuperApiTokensPage').then((m) => ({ default: m.SuperApiTokensPage })));

function RouteLoading() {
  return (
    <div className="route-loading" role="status" aria-label="Loading">
      <img src="/neurix-mark.png" alt="" width={42} height={42} aria-hidden="true" />
      <span className="spinner dark" aria-hidden="true" />
    </div>
  );
}

function RootRedirect() {
  const { user, loading } = useAuth();
  if (loading) return <RouteLoading />;
  if (!user) return <Navigate to="/login" replace />;
  if (user.role === 'SUPER_ADMIN') return <Navigate to="/super" replace />;
  return <Navigate to={user.role === 'ADMIN' ? '/admin' : '/dashboard'} replace />;
}

/** Signed-out visitors get the public landing page; everyone else goes to their workspace. */
function RootEntry() {
  const { user, loading } = useAuth();
  if (loading) return <RouteLoading />;
  if (!user) return <LandingPage />;
  return <RootRedirect />;
}

export default function App() {
  return (
    <Suspense fallback={<RouteLoading />}>
      <Routes>
        <Route path="/login" element={<LoginRoute />} />

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
        <Route path="/super/dataset" element={<SuperDatasetPage />} />
        <Route path="/super/api-tokens" element={<SuperApiTokensPage />} />
        <Route path="/super/admins" element={<SuperAdminsPage />} />
      </Route>

      <Route
        element={
          <ProtectedRoute>
            <Layout />
          </ProtectedRoute>
        }
      >
        <Route path="/admin" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminDashboardPage /></ProtectedRoute>} />
        <Route path="/admin/tickets" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminTicketsPage /></ProtectedRoute>} />
        <Route path="/admin/users" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminUsersPage /></ProtectedRoute>} />
        <Route path="/admin/departments" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminDepartmentsPage /></ProtectedRoute>} />
        <Route path="/admin/subcategories" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminSubcategoriesPage /></ProtectedRoute>} />
        <Route path="/admin/projects" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminProjectsPage /></ProtectedRoute>} />
        <Route path="/admin/reports" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminReportsPage /></ProtectedRoute>} />
        <Route path="/admin/users/:id/activity" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminUserActivityPage /></ProtectedRoute>} />
        <Route path="/admin/assignments" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminAssignmentsPage /></ProtectedRoute>} />
        <Route path="/admin/import" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminImportPage /></ProtectedRoute>} />
        <Route path="/admin/announcements" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminAnnouncementsPage /></ProtectedRoute>} />
        <Route path="/admin/workload" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminWorkloadPage /></ProtectedRoute>} />
        <Route path="/admin/quality" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminQualityPage /></ProtectedRoute>} />
        <Route path="/admin/weekly" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><AdminWeeklyReportPage /></ProtectedRoute>} />
        <Route path="/admin/project-folders" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><ProjectFoldersPage /></ProtectedRoute>} />
        <Route path="/admin/project-folders/:projectId" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><ProjectFolderDetailPage /></ProtectedRoute>} />
        <Route path="/admin/recycle-bin" element={<ProtectedRoute roles={['ADMIN', 'SUPER_ADMIN']}><RecycleBinPage /></ProtectedRoute>} />

        <Route path="/dashboard" element={<UserDashboardPage />} />
        <Route path="/submit" element={<SubmitTicketPage />} />
        <Route path="/my-tickets" element={<MyTicketsPage />} />
        <Route path="/assignments" element={<MyAssignmentsPage />} />
        <Route path="/project-folders" element={<ProjectFoldersPage />} />
        <Route path="/project-folders/:projectId" element={<ProjectFolderDetailPage />} />
        <Route path="/recycle-bin" element={<RecycleBinPage />} />
        <Route path="/chat" element={<ChatPage />} />

        <Route path="/profile" element={<ProfilePage />} />
      </Route>

        <Route path="/" element={<RootEntry />} />
        <Route path="*" element={<RootRedirect />} />
      </Routes>
    </Suspense>
  );
}
