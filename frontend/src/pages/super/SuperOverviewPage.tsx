import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { extractError } from '../../api/client';
import { impersonation } from '../../api/impersonation';
import { superApi, type OverviewStats, type TeamSummary } from '../../api/super';
import { IconBuilding, IconChart, IconFolder, IconMembers, IconSearch, IconTasks } from '../../components/Icons';
import { useAuth } from '../../context/AuthContext';
import { useT } from '../../i18n';

/**
 * Super-admin landing page. Uses the same {@code page / stat-card / card} shell as every
 * other admin page — no bespoke hero or gradient chrome — so the surface reads as "this is
 * still Neurix, you're just one level up".
 */
export function SuperOverviewPage() {
  const { t } = useT();
  const navigate = useNavigate();
  const { refresh } = useAuth();
  const [data, setData] = useState<OverviewStats | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<number | null>(null);
  const [q, setQ] = useState('');
  const [statusFilter, setStatusFilter] = useState<'all' | 'active' | 'inactive'>('all');

  const filteredTeams = useMemo(() => {
    if (!data) return [];
    const needle = q.trim().toLowerCase();
    return data.teams.filter((tm) => {
      if (statusFilter === 'active' && !tm.active) return false;
      if (statusFilter === 'inactive' && tm.active) return false;
      if (!needle) return true;
      return (tm.name + ' ' + tm.slug + ' ' + (tm.description ?? '')).toLowerCase().includes(needle);
    });
  }, [data, q, statusFilter]);

  useEffect(() => {
    superApi.overview()
      .then(setData)
      .catch((e) => setError(extractError(e)));
  }, []);

  async function enter(team: TeamSummary) {
    setBusy(team.id);
    try {
      const res = await superApi.enterTeam(team.id);
      impersonation.enter({
        id: res.teamId, slug: res.teamSlug, name: res.teamName, color: team.color ?? null,
      });
      await refresh();
      navigate('/admin');
    } catch (e) {
      setError(extractError(e));
      setBusy(null);
    }
  }

  return (
    <div className="page super-page">
      <div className="page-header">
        <div>
          <h1>{t('super.overviewTitle') || 'Global overview'}</h1>
          <p className="subtitle">
            {t('super.overviewSubtitle')
              || 'A read across every team in the system. Enter any team to work inside its data.'}
          </p>
        </div>
        <Link to="/super/teams" className="btn btn-primary">
          <IconBuilding size={16} /> {t('super.manageTeams') || 'Manage teams'}
        </Link>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))' }}>
        <StatCard label={t('super.totalTeams') || 'Teams'}             value={data?.totalTeams}       tint="brand"  icon={<IconBuilding size={16} />} />
        <StatCard label={t('super.totalUsers') || 'Users'}             value={data?.totalUsers}       tint="cyan"   icon={<IconMembers size={16} />} />
        <StatCard label={t('super.totalAdmins') || 'Admins'}           value={data?.totalAdmins}      tint="violet" icon={<IconMembers size={16} />} />
        <StatCard label={t('super.totalProjects') || 'Projects'}       value={data?.totalProjects}    tint="amber"  icon={<IconFolder size={16} />} />
        <StatCard label={t('super.totalDepartments') || 'Departments'} value={data?.totalDepartments} tint="navy"   icon={<IconBuilding size={16} />} />
        <StatCard label={t('super.totalTickets') || 'Tickets'}         value={data?.totalTickets}     tint="coral"  icon={<IconTasks size={16} />} />
        <StatCard label={t('super.today') || 'Today'}                  value={data?.ticketsToday}     tint="green"  icon={<IconChart size={16} />} />
        <StatCard label={t('super.thisWeek') || 'This week'}           value={data?.ticketsThisWeek}  tint="cyan"   icon={<IconChart size={16} />} />
      </div>

      <div style={{ marginTop: 24 }}>
        <div className="row-between" style={{ marginBottom: 8 }}>
          <div>
            <h2 style={{ margin: 0, fontSize: 18 }}>{t('super.teamsHeading') || 'Teams'}</h2>
            <p className="subtitle" style={{ margin: '4px 0 0' }}>
              {t('super.teamsSubtitle') || 'Each team is a fully isolated workspace.'}
            </p>
          </div>
        </div>

        <div className="super-toolbar">
          <div style={{ position: 'relative', flex: 1, minWidth: 240 }}>
            <span style={{
              position: 'absolute', top: '50%', insetInlineStart: 12,
              transform: 'translateY(-50%)', color: 'var(--text-tertiary)',
              pointerEvents: 'none', display: 'inline-flex',
            }}>
              <IconSearch size={16} />
            </span>
            <input
              type="search"
              className="input"
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder={t('super.searchTeams') || 'Search teams…'}
              style={{ paddingInlineStart: 40 }}
            />
          </div>
          <div className="filter-pills">
            {(['all', 'active', 'inactive'] as const).map((s) => (
              <button
                key={s}
                type="button"
                className={`filter-pill${statusFilter === s ? ' active' : ''}`}
                onClick={() => setStatusFilter(s)}
              >
                {s === 'all'
                  ? (t('super.all') || 'All')
                  : s === 'active' ? t('common.active') : t('common.inactive')}
              </button>
            ))}
          </div>
          {data && (
            <span className="result-count">
              {filteredTeams.length} / {data.teams.length}
            </span>
          )}
        </div>

        {!data && <div className="muted" style={{ padding: 16 }}>{t('common.loading')}</div>}

        {data && data.teams.length === 0 && (
          <div className="empty-state">
            {t('super.noTeams') || 'No teams yet. Create one to start onboarding admins.'}
          </div>
        )}

        {data && data.teams.length > 0 && filteredTeams.length === 0 && (
          <div className="empty-state">
            {t('super.noMatch') || 'No teams match the current filter.'}
          </div>
        )}

        {data && filteredTeams.length > 0 && (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th style={{ textAlign: 'start' }}>{t('super.name') || 'Team'}</th>
                  <th style={{ textAlign: 'end' }}>{t('super.users') || 'Users'}</th>
                  <th style={{ textAlign: 'end' }}>{t('super.admins') || 'Admins'}</th>
                  <th style={{ textAlign: 'end' }}>{t('super.projects') || 'Projects'}</th>
                  <th style={{ textAlign: 'end' }}>{t('super.tickets') || 'Tickets'}</th>
                  <th style={{ textAlign: 'center' }}>{t('common.status')}</th>
                  <th style={{ textAlign: 'end' }}>{t('common.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {filteredTeams.map((team) => (
                  <tr key={team.id}>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <span
                          aria-hidden="true"
                          style={{
                            width: 28, height: 28, borderRadius: 8,
                            background: team.color || 'var(--brand)', color: '#fff',
                            display: 'grid', placeItems: 'center',
                            fontSize: 13, fontWeight: 700,
                          }}
                        >
                          {team.name.charAt(0).toUpperCase()}
                        </span>
                        <div>
                          <div style={{ fontWeight: 600 }}>{team.name}</div>
                          <code style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>{team.slug}</code>
                        </div>
                      </div>
                    </td>
                    <td style={{ textAlign: 'end' }}>{team.userCount}</td>
                    <td style={{ textAlign: 'end' }}>{team.adminCount}</td>
                    <td style={{ textAlign: 'end' }}>{team.projectCount}</td>
                    <td style={{ textAlign: 'end' }}>{team.ticketCount}</td>
                    <td style={{ textAlign: 'center' }}>
                      <StatusPill active={team.active} labelActive={t('common.active')} labelInactive={t('common.inactive')} />
                    </td>
                    <td style={{ textAlign: 'end' }}>
                      <button
                        type="button"
                        className="btn btn-primary btn-sm"
                        disabled={!team.active || busy === team.id}
                        onClick={() => enter(team)}
                      >
                        {busy === team.id
                          ? (t('super.entering') || 'Entering…')
                          : (t('super.enterTeam') || 'Enter')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

type Tint = 'brand' | 'cyan' | 'violet' | 'amber' | 'navy' | 'coral' | 'green';

function StatCard({
  label, value, tint, icon,
}: { label: string; value: number | undefined; tint: Tint; icon: React.ReactNode }) {
  return (
    <div className={`stat-card super-stat tint-${tint}`}>
      <div className="stat-card-header">
        <span className="stat-card-label">{label}</span>
        <span className="stat-card-icon">{icon}</span>
      </div>
      <div className="stat-card-value">{value == null ? '—' : value.toLocaleString()}</div>
    </div>
  );
}

function StatusPill({ active, labelActive, labelInactive }: { active: boolean; labelActive: string; labelInactive: string }) {
  return (
    <span
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 6,
        padding: '3px 10px', borderRadius: 999, fontSize: 11, fontWeight: 600,
        background: active ? 'var(--success-soft)' : 'var(--status-completed-soft)',
        color:      active ? 'var(--success-soft-text)' : 'var(--status-completed-text)',
      }}
    >
      <span
        aria-hidden="true"
        style={{
          width: 6, height: 6, borderRadius: 999,
          background: active ? 'var(--success)' : 'var(--status-completed)',
        }}
      />
      {active ? labelActive : labelInactive}
    </span>
  );
}
