import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { avatarUrl } from '../../api/profile';
import { impersonation } from '../../api/impersonation';
import { Avatar } from '../../components/Avatar';
import {
  IconChart, IconClose, IconDashboard, IconFolder,
  IconLogout, IconMembers, IconTasks, IconBuilding,
  IconDatabase, IconKey,
} from '../../components/Icons';
import { PreferencesToggle } from '../../components/PreferencesToggle';
import { ProfileModal } from '../../components/ProfileModal';
import { useAuth } from '../../context/AuthContext';
import { useT } from '../../i18n';

/**
 * Super-admin shell. Deliberately mirrors the main {@code Layout.tsx} class-for-class so
 * the surface reads as part of the same Neurix design system — no bespoke dark-navy chrome
 * or Tailwind gradients. Only meaningful difference from a team-admin sidebar is the extra
 * "cross-team views" section that deep-links into /admin/*.
 *
 * <p>Any leftover impersonation state from a previous session is cleared on mount — the
 * super surface itself never impersonates, and forgetting to clear leaves the top-bar name
 * confusingly showing "Impersonating X" while the sidebar says "Super Admin".
 */
export function SuperLayout() {
  const { user, logout, refresh } = useAuth();
  const { t, lang } = useT();
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

  useEffect(() => {
    if (impersonation.current()) {
      impersonation.exit();
      void refresh();
    }
  }, [refresh]);

  const roleLabel = t('super.roleLabel') || 'Super Admin';

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
          <span className="brand-lines">
            <span>{t('brand')}</span>
            <span className="super-tag">{roleLabel}</span>
          </span>
          <button
            type="button"
            className="sidebar-close-btn"
            onClick={() => setSidebarOpen(false)}
            aria-label={t('common.close')}
          >
            <IconClose size={16} />
          </button>
        </div>

        <NavLink to="/super" end className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
          <span className="side-icon"><IconDashboard /></span>
          {t('super.overview') || 'Overview'}
        </NavLink>
        <NavLink to="/super/teams" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
          <span className="side-icon"><IconBuilding /></span>
          {t('super.teams') || 'Teams'}
        </NavLink>
        <NavLink to="/super/projects" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
          <span className="side-icon"><IconChart /></span>
          {t('super.projectsNav') || 'Project analytics'}
        </NavLink>
        <NavLink to="/super/data" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
          <span className="side-icon"><IconDatabase /></span>
          {t('super.data.nav') || 'Data explorer'}
        </NavLink>
        <NavLink to="/super/dataset" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
          <span className="side-icon"><IconDatabase /></span>
          {lang === 'ar' ? 'بيانات السيرفر' : 'Server dataset'}
        </NavLink>
        <NavLink to="/super/api-tokens" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
          <span className="side-icon"><IconKey /></span>
          {t('super.tokens.nav') || 'API tokens'}
        </NavLink>
        <NavLink to="/super/admins" className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}>
          <span className="side-icon"><IconMembers /></span>
          {t('super.admins') || 'Super admins'}
        </NavLink>

        {/* Cross-team section header — visually distinct without being loud. */}
        <div style={{
          marginTop: 20, padding: '10px 16px 6px',
          fontSize: 10, fontWeight: 700, letterSpacing: 1.2, textTransform: 'uppercase',
          color: 'var(--text-tertiary)',
        }}>
          {t('super.crossTeam') || 'Cross-team views'}
        </div>

        <button type="button" className="side-link side-link-btn" onClick={() => navigate('/admin')}>
          <span className="side-icon"><IconDashboard /></span>
          {t('nav.dashboard')}
        </button>
        <button type="button" className="side-link side-link-btn" onClick={() => navigate('/admin/users')}>
          <span className="side-icon"><IconMembers /></span>
          {t('nav.users')}
        </button>
        <button type="button" className="side-link side-link-btn" onClick={() => navigate('/admin/projects')}>
          <span className="side-icon"><IconFolder /></span>
          {t('nav.projects')}
        </button>
        <button type="button" className="side-link side-link-btn" onClick={() => navigate('/admin/departments')}>
          <span className="side-icon"><IconBuilding /></span>
          {t('nav.departments')}
        </button>
        <button type="button" className="side-link side-link-btn" onClick={() => navigate('/admin/tickets')}>
          <span className="side-icon"><IconTasks /></span>
          {t('nav.tasks')}
        </button>
        <button type="button" className="side-link side-link-btn" onClick={() => navigate('/admin/reports')}>
          <span className="side-icon"><IconChart /></span>
          {t('nav.reports')}
        </button>

        <div className="sidebar-footer-links">
          <button type="button" className="side-link side-link-btn" onClick={logout}>
            <span className="side-icon"><IconLogout /></span>
            {t('common.signOut')}
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
          <Outlet />
        </main>
      </div>

      {profileOpen && <ProfileModal onClose={() => setProfileOpen(false)} />}
    </div>
  );
}
