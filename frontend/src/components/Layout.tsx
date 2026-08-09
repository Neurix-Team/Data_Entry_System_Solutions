import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useT } from '../i18n';
import { Avatar } from './Avatar';
import {
  IconBell, IconBuilding, IconChart, IconDashboard, IconFolder,
  IconMembers, IconSearch, IconSettings, IconTasks,
} from './Icons';
import { PreferencesToggle } from './PreferencesToggle';

export function Layout() {
  const { user, logout } = useAuth();
  const { t } = useT();
  const isAdmin = user?.role === 'ADMIN';
  const roleLabel = isAdmin ? t('common.teamLeader') : t('common.dataEntryAgent');

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="sidebar-brand">
          <div className="sidebar-brand-mark" />
          <span>{t('brand')}</span>
        </div>

        {isAdmin ? (
          <>
            <NavLink to="/admin" end className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <span className="side-icon"><IconDashboard /></span>
              {t('nav.dashboard')}
            </NavLink>
            <NavLink to="/admin/users" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <span className="side-icon"><IconMembers /></span>
              {t('nav.users')}
            </NavLink>
            <NavLink to="/admin/projects" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <span className="side-icon"><IconFolder /></span>
              {t('nav.projects')}
            </NavLink>
            <NavLink to="/admin/departments" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <span className="side-icon"><IconBuilding /></span>
              {t('nav.departments')}
            </NavLink>
            <NavLink to="/admin/subcategories" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <span className="side-icon"><IconFolder /></span>
              {t('nav.subcategories')}
            </NavLink>
            <NavLink to="/admin/fields" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <span className="side-icon"><IconSettings /></span>
              {t('nav.fields')}
            </NavLink>
            <NavLink to="/admin/tickets" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <span className="side-icon"><IconTasks /></span>
              {t('nav.tasks')}
            </NavLink>
            <NavLink to="/admin/reports" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <span className="side-icon"><IconChart /></span>
              {t('nav.reports')}
            </NavLink>
            <div style={{ marginTop: 'auto', paddingTop: '1rem' }}>
              <NavLink to="/submit" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
                <span className="side-icon"><IconSettings /></span>
                {t('nav.submit')}
              </NavLink>
            </div>
          </>
        ) : (
          <>
            <NavLink to="/submit" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <span className="side-icon"><IconTasks /></span>
              {t('nav.submit')}
            </NavLink>
            <NavLink to="/my-tickets" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <span className="side-icon"><IconFolder /></span>
              {t('nav.myTasks')}
            </NavLink>
          </>
        )}
      </aside>

      <div className="main-content">
        <header className="topbar">
          <div className="topbar-search">
            <span className="search-icon"><IconSearch size={18} /></span>
            <input type="search" placeholder={t('common.searchGlobal')} />
          </div>

          <div className="topbar-right">
            <PreferencesToggle />
            <button className="notification-btn" aria-label="Notifications">
              <IconBell size={20} />
              <span className="notification-dot" />
            </button>
            <div className="topbar-divider" />
            <div className="user-chip">
              <div className="user-chip-info">
                <span className="user-chip-name">{user?.displayName || user?.username}</span>
                <span className="user-chip-role">{roleLabel}</span>
              </div>
              <Avatar name={user?.displayName || user?.username} size="md" />
            </div>
            <button
              className="btn btn-ghost btn-sm"
              onClick={logout}
              title={t('common.signOut')}
            >
              {t('common.signOut')}
            </button>
          </div>
        </header>

        <main style={{ flex: 1, minWidth: 0 }}>
          <Outlet />
        </main>
      </div>
    </div>
  );
}
