import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useT } from '../i18n';
import { Avatar } from './Avatar';
import {
  IconBuilding, IconChart, IconClose, IconDashboard, IconFolder,
  IconLogout, IconMembers, IconSearch, IconSettings, IconTasks,
} from './Icons';
import { NotificationBell } from './NotificationBell';
import { PreferencesToggle } from './PreferencesToggle';
import { ProfileModal } from './ProfileModal';
import { avatarUrl } from '../api/profile';
import { ChatWidget } from './chat/ChatWidget';
import { ImpersonationBanner } from './ImpersonationBanner';

export function Layout() {
  const { user, logout } = useAuth();
  const { t } = useT();
  const location = useLocation();
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const myAvatar = user ? avatarUrl(user.id, user.avatarUpdatedAt) : null;
  const isSuperAdmin = user?.role === 'SUPER_ADMIN';
  // Super admin drives admin views (both cross-team and scoped-via-impersonation), so treat
  // them like an admin for the shell/nav — the ImpersonationBanner makes the difference obvious.
  const isAdmin = user?.role === 'ADMIN' || isSuperAdmin;
  const roleLabel = isSuperAdmin
    ? (t('super.roleLabel') || 'Super Admin')
    : isAdmin ? t('common.teamLeader') : t('common.dataEntryAgent');

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
          <img
            className="sidebar-brand-img"
            src="/neurix-mark.png"
            alt=""
            width={28}
            height={28}
            aria-hidden="true"
            onError={(e) => { (e.currentTarget as HTMLImageElement).style.display = 'none'; }}
          />
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

        {isSuperAdmin && (
          <NavLink
            to="/super"
            className="side-link"
            style={{ color: '#f97316', fontWeight: 700 }}
          >
            <span className="side-icon">←</span>
            {t('super.backToSuper') || 'Super Admin'}
          </NavLink>
        )}
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
            <NavLink to="/admin/project-folders" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <span className="side-icon"><IconFolder /></span>
              {t('nav.projectFolders')}
            </NavLink>
            <NavLink to="/admin/departments" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <span className="side-icon"><IconBuilding /></span>
              {t('nav.departments')}
            </NavLink>
            <NavLink to="/admin/subcategories" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <span className="side-icon"><IconFolder /></span>
              {t('nav.subcategories')}
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
            <NavLink to="/project-folders" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
              <span className="side-icon"><IconFolder /></span>
              {t('nav.projectFolders')}
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
            <NotificationBell />
            <div className="topbar-divider" />
            <button
              type="button"
              className="user-chip is-button"
              onClick={() => setProfileOpen(true)}
              aria-label={t('common.profile') || 'Profile'}
            >
              <div className="user-chip-info">
                <span className="user-chip-name">{user?.displayName || user?.username}</span>
                <span className="user-chip-role">{roleLabel}</span>
              </div>
              <Avatar name={user?.displayName || user?.username} size="md" src={myAvatar} />
            </button>
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
          <ImpersonationBanner />
          <Outlet />
        </main>
      </div>

      <ChatWidget />

      {profileOpen && <ProfileModal onClose={() => setProfileOpen(false)} />}
    </div>
  );
}
