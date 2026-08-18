import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { avatarUrl } from '../../api/profile';
import { impersonation } from '../../api/impersonation';
import { Avatar } from '../../components/Avatar';
import {
  IconChart, IconClose, IconDashboard, IconFolder,
  IconLogout, IconMembers, IconSettings, IconTasks, IconBuilding,
} from '../../components/Icons';
import { PreferencesToggle } from '../../components/PreferencesToggle';
import { ProfileModal } from '../../components/ProfileModal';
import { useAuth } from '../../context/AuthContext';
import { useT } from '../../i18n';

/**
 * Dedicated shell for /super/* pages. Renders a distinct sidebar (with the "Super Admin"
 * branding) plus a "Cross-team views" section that deep-links into the existing /admin/*
 * pages — a SUPER_ADMIN can use those pages either in aggregate mode (no impersonation) or
 * scoped to a specific team via the impersonation store.
 */
export function SuperLayout() {
  const { user, logout, refresh } = useAuth();
  const { t } = useT();
  const location = useLocation();
  const navigate = useNavigate();
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const myAvatar = user ? avatarUrl(user.id, user.avatarUpdatedAt) : null;

  useEffect(() => { setSidebarOpen(false); }, [location.pathname]);

  useEffect(() => {
    if (!sidebarOpen) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setSidebarOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [sidebarOpen]);

  // Landing on /super while an impersonation flag is still set from a previous session
  // would be confusing — every super endpoint ignores the header anyway, but the top-nav
  // would still show "Impersonating X". Clear it as soon as we enter the super shell.
  useEffect(() => {
    if (impersonation.current()) {
      impersonation.exit();
      void refresh();
    }
  }, [refresh]);

  const label = {
    overview: t('super.overview') || 'Overview',
    teams: t('super.teams') || 'Teams',
    admins: t('super.admins') || 'Super admins',
    cross: t('super.crossTeam') || 'Cross-team views',
    dashboard: t('nav.dashboard'),
    users: t('nav.users'),
    projects: t('nav.projects'),
    departments: t('nav.departments'),
    subcategories: t('nav.subcategories'),
    tasks: t('nav.tasks'),
    reports: t('nav.reports'),
    close: t('common.close'),
    signOut: t('common.signOut'),
    superAdminRole: t('super.roleLabel') || 'Super Admin',
  };

  const goEnter = (path: string) => () => navigate(path);

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
          <span style={{ display: 'flex', flexDirection: 'column', gap: 2, lineHeight: 1.1 }}>
            <span>{t('brand')}</span>
            <span style={{ fontSize: 10, color: '#f97316', fontWeight: 700, letterSpacing: 1 }}>
              SUPER ADMIN
            </span>
          </span>
          <button
            type="button"
            className="sidebar-close-btn"
            onClick={() => setSidebarOpen(false)}
            aria-label={label.close}
          >
            <IconClose size={16} />
          </button>
        </div>

        <NavLink to="/super" end className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
          <span className="side-icon"><IconDashboard /></span>
          {label.overview}
        </NavLink>
        <NavLink to="/super/teams" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
          <span className="side-icon"><IconBuilding /></span>
          {label.teams}
        </NavLink>
        <NavLink to="/super/admins" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
          <span className="side-icon"><IconMembers /></span>
          {label.admins}
        </NavLink>

        <div style={{
          marginTop: 24, padding: '8px 16px', fontSize: 10, fontWeight: 700,
          letterSpacing: 1.2, textTransform: 'uppercase', opacity: 0.55,
        }}>
          {label.cross}
        </div>

        <button className="side-link side-link-btn" onClick={goEnter('/admin')} type="button">
          <span className="side-icon"><IconDashboard /></span>
          {label.dashboard}
        </button>
        <button className="side-link side-link-btn" onClick={goEnter('/admin/users')} type="button">
          <span className="side-icon"><IconMembers /></span>
          {label.users}
        </button>
        <button className="side-link side-link-btn" onClick={goEnter('/admin/projects')} type="button">
          <span className="side-icon"><IconFolder /></span>
          {label.projects}
        </button>
        <button className="side-link side-link-btn" onClick={goEnter('/admin/departments')} type="button">
          <span className="side-icon"><IconBuilding /></span>
          {label.departments}
        </button>
        <button className="side-link side-link-btn" onClick={goEnter('/admin/tickets')} type="button">
          <span className="side-icon"><IconTasks /></span>
          {label.tasks}
        </button>
        <button className="side-link side-link-btn" onClick={goEnter('/admin/reports')} type="button">
          <span className="side-icon"><IconChart /></span>
          {label.reports}
        </button>

        <div className="sidebar-footer-links">
          <button type="button" className="side-link side-link-btn" onClick={logout}>
            <span className="side-icon"><IconLogout /></span>
            {label.signOut}
          </button>
        </div>
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

          <div className="topbar-search" />

          <div className="topbar-right">
            <PreferencesToggle />
            <div className="topbar-divider" />
            <button
              type="button"
              className="user-chip is-button"
              onClick={() => setProfileOpen(true)}
              aria-label={t('common.profile') || 'Profile'}
            >
              <div className="user-chip-info">
                <span className="user-chip-name">{user?.displayName || user?.username}</span>
                <span className="user-chip-role" style={{ color: '#f97316', fontWeight: 700 }}>
                  {label.superAdminRole}
                </span>
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
          <Outlet />
        </main>
      </div>

      {profileOpen && <ProfileModal onClose={() => setProfileOpen(false)} />}

      <button
        type="button"
        onClick={() => { void logout(); }}
        style={{ display: 'none' }}
        aria-hidden="true"
      >
        {/* placeholder to satisfy React key */}
      </button>
    </div>
  );
}
