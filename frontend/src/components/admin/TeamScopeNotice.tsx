import { useNavigate } from 'react-router-dom';
import { useT } from '../../i18n';

interface Props {
  /** What the page could not do without a team — e.g. "weekly report" or "announcement". */
  what: string;
}

/**
 * Shown in place of a team-scoped admin page when the viewer is a super admin who is not
 * currently impersonating a team. These pages need exactly one team to act on, and a super
 * admin's session carries none — pointing them at the team picker is the fix, not a raw
 * "team_id was null" error from the backend, which is what this replaces.
 */
export function TeamScopeNotice({ what }: Props) {
  const { t } = useT();
  const navigate = useNavigate();

  return (
    <div className="card" style={{ textAlign: 'center', padding: '2rem 1.5rem' }}>
      <p style={{ marginBottom: 12 }}>{t('teamScope.notice', { what })}</p>
      <button type="button" className="btn btn-primary" onClick={() => navigate('/super')}>
        {t('teamScope.pickTeam')}
      </button>
    </div>
  );
}
