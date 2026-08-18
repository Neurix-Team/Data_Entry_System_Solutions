import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { impersonation, type ImpersonationState } from '../api/impersonation';
import { useAuth } from '../context/AuthContext';
import { useT } from '../i18n';

/**
 * Persistent banner rendered whenever a SUPER_ADMIN has "entered" a specific team from the
 * super-admin surface. Kept visually loud — orange background, "Exit" call to action — so
 * the operator never forgets they're acting inside someone else's data.
 *
 * <p>Hides itself entirely if the user isn't a SUPER_ADMIN or no team has been entered,
 * so pages can render it unconditionally without needing to wrap it in role checks.
 */
export function ImpersonationBanner() {
  const { user, refresh } = useAuth();
  const { t } = useT();
  const navigate = useNavigate();
  const [state, setState] = useState<ImpersonationState | null>(() => impersonation.current());

  useEffect(() => {
    return impersonation.subscribe(setState);
  }, []);

  if (!user || user.role !== 'SUPER_ADMIN' || !state) return null;

  const exit = async () => {
    impersonation.exit();
    // Re-fetch /auth/me so the top-nav name/role reverts back to Super Admin immediately.
    await refresh();
    navigate('/super', { replace: true });
  };

  const label = t('super.impersonating') || 'You are viewing';
  const asSuper = t('super.asSuperAdmin') || 'as Super Admin';
  const exitLabel = t('super.exit') || 'Exit team';

  return (
    <div
      role="status"
      aria-live="polite"
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        padding: '10px 16px',
        background: 'linear-gradient(90deg, #f97316 0%, #dc2626 100%)',
        color: '#fff',
        fontWeight: 500,
        fontSize: 14,
        borderRadius: 10,
        margin: '0 0 16px',
        boxShadow: '0 6px 20px rgba(220, 38, 38, 0.25)',
      }}
    >
      <span
        aria-hidden="true"
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          width: 28,
          height: 28,
          borderRadius: 999,
          background: 'rgba(255,255,255,0.18)',
          fontSize: 15,
        }}
      >
        {/* eye icon */}
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7z"/><circle cx="12" cy="12" r="3"/></svg>
      </span>
      <span style={{ flex: 1 }}>
        {label} <strong>{state.team.name}</strong> {asSuper}
      </span>
      <button
        type="button"
        onClick={exit}
        style={{
          appearance: 'none',
          border: '1px solid rgba(255,255,255,0.5)',
          background: 'rgba(255,255,255,0.14)',
          color: '#fff',
          padding: '6px 12px',
          borderRadius: 8,
          fontSize: 13,
          fontWeight: 600,
          cursor: 'pointer',
        }}
      >
        {exitLabel}
      </button>
    </div>
  );
}
