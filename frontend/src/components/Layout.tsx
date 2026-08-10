import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useT } from '../i18n';
import { Avatar } from './Avatar';
import {
  IconBell, IconBuilding, IconChart, IconClose, IconDashboard, IconFolder,
  IconLogout, IconMembers, IconSearch, IconSettings, IconTasks,
} from './Icons';
import { PreferencesToggle } from './PreferencesToggle';

export function Layout() {
  const { user, logout } = useAuth();
  const { t } = useT();
  const location = useLocation();
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const isAdmin = user?.role === 'ADMIN';
  const roleLabel = isAdmin ? t('common.teamLeader') : t('common.dataEntryAgent');

  // Close mobile sidebar on route change
  useEffect(() => { setSidebarOpen(false); }, [location.pathname]);

  // Close on Escape
  useEffect(() => {
    if (!sidebarOpen) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setSidebarOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [sidebarOpen]);

  return (
    <div className={`app-shell${sidebarOpen ? ' sidebar-open' : ''}`}>
      {sidebarOpen && (
        <div className="sidebar-backdrop" onClick={() => setSidebarOpen(false)} aria-hidden="true" />
      )}

      <aside className={`sidebar${sidebarOpen ? ' open' : ''}`}>
        <div className="sidebar-brand">
          <div className="sidebar-brand-mark" />
          <span>{t('brand')}</span>
          <button
            type="button"
            className="sidebar-close-btn"
            onClick={() => setSidebarOpen(false)}
            aria-label={t('common.close')}
          >
            <IconClose size={16} />
          </button>
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
            <div className="sidebar-footer-links">
              <NavLink to="/submit" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
                <span className="side-icon"><IconSettings /></span>
                {t('nav.submit')}
              </NavLink>
              <button type="button" className="side-link side-link-btn" onClick={logout}>
                <span className="side-icon"><IconLogout /></span>
                {t('common.signOut')}
              </button>
            </div>
          </>
        ) : (
          <>
            <NavLink to="/dashboard" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <span className="side-icon"><IconDashboard /></span>
              {t('nav.dashboard')}
            </NavLink>
            <NavLink to="/submit" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <span className="side-icon"><IconTasks /></span>
              {t('nav.submit')}
            </NavLink>
            <NavLink to="/my-tickets" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <span className="side-icon"><IconFolder /></span>
              {t('nav.myTasks')}
            </NavLink>
            <div className="sidebar-footer-links">
              <button type="button" className="side-link side-link-btn" onClick={logout}>
                <span className="side-icon"><IconLogout /></span>
                {t('common.signOut')}
              </button>
            </div>
          </>
        )}
      </aside>

      <div className="main-content">
        <header className="topbar">
          <button
            type="button"
            className="topbar-menu-btn"
            onClick={() => setSidebarOpen(true)}
            aria-label={t('common.searchGlobal')}
          >
            <span className="hamburger-icon">
              <span /><span /><span />
            </span>
          </button>

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
              className="btn btn-ghost btn-sm topbar-signout"
              onClick={logout}
              title={t('common.signOut')}
            >
              {t('common.signOut')}
            </button>
          </div>
        </header>

        <main className="app-main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
