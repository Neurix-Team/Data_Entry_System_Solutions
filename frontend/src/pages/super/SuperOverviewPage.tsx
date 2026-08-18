import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { extractError } from '../../api/client';
import { impersonation } from '../../api/impersonation';
import { superApi, type OverviewStats, type TeamSummary } from '../../api/super';
import { useAuth } from '../../context/AuthContext';
import { useT } from '../../i18n';

/** KPI cards + team roster. First screen a super admin sees after logging in. */
export function SuperOverviewPage() {
  const { t } = useT();
  const navigate = useNavigate();
  const { refresh } = useAuth();
  const [data, setData] = useState<OverviewStats | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyTeamId, setBusyTeamId] = useState<number | null>(null);

  useEffect(() => {
    superApi.overview()
      .then(setData)
      .catch((e) => setError(extractError(e)));
  }, []);

  const kpi = useMemo(() => {
    if (!data) return [];
    return [
      { label: t('super.totalTeams') || 'Teams', value: data.totalTeams, color: '#6366f1' },
      { label: t('super.totalUsers') || 'Users', value: data.totalUsers, color: '#10b981' },
      { label: t('super.totalAdmins') || 'Admins', value: data.totalAdmins, color: '#f59e0b' },
      { label: t('super.totalProjects') || 'Projects', value: data.totalProjects, color: '#8b5cf6' },
      { label: t('super.totalDepartments') || 'Departments', value: data.totalDepartments, color: '#0ea5e9' },
      { label: t('super.totalTickets') || 'Tickets', value: data.totalTickets, color: '#ec4899' },
      { label: t('super.today') || 'Today', value: data.ticketsToday, color: '#22c55e' },
      { label: t('super.thisWeek') || 'This week', value: data.ticketsThisWeek, color: '#14b8a6' },
    ];
  }, [data, t]);

  async function enter(team: TeamSummary) {
    setBusyTeamId(team.id);
    try {
      const res = await superApi.enterTeam(team.id);
      impersonation.enter({
        id: res.teamId,
        slug: res.teamSlug,
        name: res.teamName,
        color: team.color ?? null,
      });
      await refresh();
      navigate('/admin');
    } catch (e) {
      setError(extractError(e));
      setBusyTeamId(null);
    }
  }

  return (
    <div style={{ maxWidth: 1200, margin: '0 auto', padding: '8px 4px 32px' }}>
      <header style={{ marginBottom: 20 }}>
        <h1 style={{ margin: 0, fontSize: 26, fontWeight: 700 }}>
          {t('super.overviewTitle') || 'Global overview'}
        </h1>
        <p style={{ margin: '6px 0 0', color: 'var(--text-muted, #6b7280)', fontSize: 14 }}>
          {t('super.overviewSubtitle')
            || 'A read across every team in the system. Enter any team to work inside its data.'}
        </p>
      </header>

      {error && <div className="alert alert-error" role="alert">{error}</div>}

      <section
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))',
          gap: 16,
          marginBottom: 28,
        }}
      >
        {kpi.map((k) => (
          <div
            key={k.label}
            className="card"
            style={{
              padding: '18px 20px',
              borderRadius: 14,
              background: 'var(--surface, #fff)',
              boxShadow: '0 1px 3px rgba(15,23,42,0.06)',
              borderInlineStart: `4px solid ${k.color}`,
            }}
          >
            <div style={{
              fontSize: 12, fontWeight: 600, letterSpacing: 0.4,
              textTransform: 'uppercase', color: 'var(--text-muted, #6b7280)',
            }}>
              {k.label}
            </div>
            <div style={{ fontSize: 28, fontWeight: 700, marginTop: 6, color: 'var(--text)' }}>
              {k.value.toLocaleString()}
            </div>
          </div>
        ))}
      </section>

      <section>
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12,
        }}>
          <h2 style={{ fontSize: 18, fontWeight: 700, margin: 0 }}>
            {t('super.teamsHeading') || 'Teams'}
          </h2>
          <button
            type="button"
            className="btn btn-primary btn-sm"
            onClick={() => navigate('/super/teams')}
          >
            {t('super.manageTeams') || 'Manage teams'}
          </button>
        </div>

        {!data && !error && <div className="muted">{t('common.loading')}</div>}

        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
            gap: 16,
          }}
        >
          {data?.teams.map((team) => (
            <article
              key={team.id}
              className="card"
              style={{
                borderRadius: 14,
                padding: 16,
                background: 'var(--surface, #fff)',
                boxShadow: '0 1px 3px rgba(15,23,42,0.06)',
                display: 'flex',
                flexDirection: 'column',
                gap: 12,
                opacity: team.active ? 1 : 0.6,
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <div
                  style={{
                    width: 44,
                    height: 44,
                    borderRadius: 12,
                    background: team.color || '#6366f1',
                    color: '#fff',
                    display: 'grid',
                    placeItems: 'center',
                    fontSize: 18,
                    fontWeight: 700,
                  }}
                >
                  {team.name.charAt(0).toUpperCase()}
                </div>
                <div style={{ minWidth: 0, flex: 1 }}>
                  <div style={{ fontWeight: 700, fontSize: 15 }}>{team.name}</div>
                  <div style={{ fontSize: 12, color: 'var(--text-muted, #6b7280)' }}>
                    {team.slug}{!team.active && ' · deactivated'}
                  </div>
                </div>
              </div>

              {team.description && (
                <p style={{
                  margin: 0, fontSize: 13, color: 'var(--text-muted, #6b7280)',
                  overflow: 'hidden', textOverflow: 'ellipsis',
                  display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical',
                }}>
                  {team.description}
                </p>
              )}

              <dl style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
                gap: '6px 16px',
                fontSize: 13,
                margin: 0,
              }}>
                <div><dt style={dtStyle}>{t('super.users') || 'Users'}</dt><dd style={ddStyle}>{team.userCount}</dd></div>
                <div><dt style={dtStyle}>{t('super.admins') || 'Admins'}</dt><dd style={ddStyle}>{team.adminCount}</dd></div>
                <div><dt style={dtStyle}>{t('super.projects') || 'Projects'}</dt><dd style={ddStyle}>{team.projectCount}</dd></div>
                <div><dt style={dtStyle}>{t('super.tickets') || 'Tickets'}</dt><dd style={ddStyle}>{team.ticketCount}</dd></div>
              </dl>

              <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
                <button
                  type="button"
                  className="btn btn-primary btn-sm"
                  disabled={!team.active || busyTeamId === team.id}
                  onClick={() => enter(team)}
                  style={{ flex: 1 }}
                >
                  {busyTeamId === team.id
                    ? (t('super.entering') || 'Entering…')
                    : (t('super.enterTeam') || 'Enter team')}
                </button>
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() => navigate('/super/teams')}
                >
                  {t('common.edit')}
                </button>
              </div>
            </article>
          ))}

          {data && data.teams.length === 0 && (
            <div className="muted" style={{ gridColumn: '1 / -1', padding: 24 }}>
              {t('super.noTeams') || 'No teams yet. Create one to start onboarding admins.'}
            </div>
          )}
        </div>
      </section>
    </div>
  );
}

const dtStyle: React.CSSProperties = {
  fontSize: 11, textTransform: 'uppercase', letterSpacing: 0.6,
  color: 'var(--text-muted, #6b7280)', fontWeight: 600,
};
const ddStyle: React.CSSProperties = {
  margin: 0, fontSize: 15, fontWeight: 600, color: 'var(--text)',
};
