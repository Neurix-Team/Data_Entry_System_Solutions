import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { extractError } from '../../api/client';
import { dashboardApi, ticketsApi } from '../../api/resources';
import type {
  AdminStats, AgentLeaderboardRow, DomainStats, LeaderboardResponse,
  ReportData, SubcategoryStats, Ticket,
} from '../../api/types';
import { Avatar } from '../../components/Avatar';
import {
  IconBuilding, IconCheck, IconClock, IconFolder,
  IconMembers,
} from '../../components/Icons';
import { useT } from '../../i18n';

type Range = 'day' | 'week' | 'month';

export function AdminDashboardPage() {
  const { t } = useT();
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [report, setReport] = useState<ReportData | null>(null);
  const [recent, setRecent] = useState<Ticket[]>([]);
  const [domains, setDomains] = useState<DomainStats[]>([]);
  const [leaderboard, setLeaderboard] = useState<LeaderboardResponse | null>(null);
  const [range, setRange] = useState<Range>('week');
  const [expandedDept, setExpandedDept] = useState<number | null>(null);
  const [subs, setSubs] = useState<SubcategoryStats[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([
      ticketsApi.stats(),
      ticketsApi.reports(),
      ticketsApi.listAll(0, 5),
      dashboardApi.domains(),
    ])
      .then(([s, r, page, dList]) => {
        setStats(s);
        setReport(r);
        setRecent(page.items);
        setDomains(dList);
      })
      .catch((e) => setError(extractError(e)));
  }, []);

  useEffect(() => {
    dashboardApi.users(range)
      .then(setLeaderboard)
      .catch((e) => setError(extractError(e)));
  }, [range]);

  useEffect(() => {
    if (expandedDept == null) { setSubs([]); return; }
    dashboardApi.subcategories(expandedDept).then(setSubs).catch(() => setSubs([]));
  }, [expandedDept]);

  const maxCount = report ? Math.max(1, ...Object.values(report.byDay)) : 1;
  const barColors = [1, 2, 3, 4, 5, 6, 1];

  return (
    <div className="page">
      {error && <div className="alert alert-error">{error}</div>}

      <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(5, minmax(0, 1fr))' }}>
        <StatCard label={t('admin.dashboard.teamMembers')} value={stats?.totalUsers} icon={<IconMembers size={16} />} hero="yellow" />
        <StatCard label={t('admin.dashboard.activeProjects')} value={stats?.activeFields} icon={<IconFolder size={16} />} hero="yellow" />
        <StatCard label={t('admin.dashboard.departments')} value={stats?.totalDepartments} icon={<IconBuilding size={16} />} hero="yellow" />
        <StatCard label={t('admin.dashboard.pending')} value={(stats?.inProgress ?? 0) + (stats?.review ?? 0)} icon={<IconClock size={16} />} hero="yellow" />
        <StatCard label={t('admin.dashboard.completedToday')} value={stats?.completed ?? 0} icon={<IconCheck size={16} />} hero="amber" />
      </div>

      <div className="two-col">
        <div className="card" style={{ padding: '1.5rem' }}>
          <div className="row-between mb-2">
            <h3 style={{ margin: 0 }}>{t('admin.dashboard.taskProgress')}</h3>
          </div>
          <div className="bar-chart">
            {report && Object.entries(report.byDay).map(([day, val], i) => {
              const height = (val / maxCount) * 100;
              const d = new Date(day);
              const label = d.toLocaleDateString(undefined, { weekday: 'short' });
              return (
                <div key={day} className="bar-chart-item">
                  <div
                    className="bar-chart-bar"
                    data-color={barColors[i % barColors.length]}
                    style={{ height: `${Math.max(4, height)}%` }}
                    title={`${label}: ${val}`}
                  />
                  <span className="bar-chart-label">{label}</span>
                </div>
              );
            })}
          </div>
        </div>

        <div className="card">
          <div className="row-between mb-2">
            <h3 style={{ margin: 0 }}>{t('admin.dashboard.topPerformers')}</h3>
          </div>
          {report && report.topPerformers.length === 0 && (
            <div className="empty-state">{t('admin.dashboard.noCompletedYet')}</div>
          )}
          {report?.topPerformers.map((p) => (
            <Link to={`/admin/users/${p.userId}/activity`} key={p.userId} style={{
              display: 'flex', alignItems: 'center', gap: '0.75rem',
              padding: '0.75rem 0', borderBottom: '1px solid var(--border)',
              textDecoration: 'none', color: 'inherit',
            }}>
              <Avatar name={p.displayName} size="md" />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{p.displayName}</div>
                <div className="small muted">@{p.username}</div>
              </div>
              <div style={{ textAlign: 'end' }}>
                <div style={{ fontWeight: 700, color: 'var(--brand-soft-text)' }}>{p.completed}</div>
                <div className="small muted">{t('admin.reports.tasksCompleted')}</div>
              </div>
            </Link>
          ))}
        </div>
      </div>

      {/* Domain grid */}
      <div className="card mt-2" style={{ padding: '1.5rem' }}>
        <div className="row-between mb-2">
          <h3 style={{ margin: 0 }}>{t('admin.dashboard.domainOverview')}</h3>
          <Link to="/admin/departments" className="btn btn-ghost btn-sm">{t('admin.dashboard.viewAll')}</Link>
        </div>
        {domains.length === 0 ? (
          <div className="empty-state">{t('admin.dashboard.noDomains')}</div>
        ) : (
          <div className="dept-grid">
            {domains.map((d, idx) => {
              const color = ((idx % 6) + 1);
              const dayMax = Math.max(1, ...d.last7Days.map(x => x.count));
              return (
                <div key={d.departmentId} className="dept-card" data-color={color}>
                  <div className="dept-card-head">
                    <div className="dept-card-name">{d.departmentName}</div>
                    <span className="badge badge-active">{d.totalTickets}</span>
                  </div>
                  <div className="dept-card-mini-grid">
                    <div className="dept-mini-stat tint-a">
                      <div className="dept-mini-stat-label">{t('admin.dashboard.subcategories')}</div>
                      <div className="dept-mini-stat-value">{d.subcategoryCount}</div>
                    </div>
                    <div className="dept-mini-stat tint-a">
                      <div className="dept-mini-stat-label">{t('admin.dashboard.activeAgents')}</div>
                      <div className="dept-mini-stat-value">{d.activeAgents}</div>
                    </div>
                  </div>
                  <div className="bar-chart" style={{ height: 60, marginTop: 12 }}>
                    {d.last7Days.map((row, i) => {
                      const h = (row.count / dayMax) * 100;
                      return (
                        <div key={row.date} className="bar-chart-item">
                          <div
                            className="bar-chart-bar"
                            data-color={((i + idx) % 6) + 1}
                            style={{ height: `${Math.max(4, h)}%` }}
                            title={`${row.date}: ${row.count}`}
                          />
                        </div>
                      );
                    })}
                  </div>
                  <div className="dept-card-actions">
                    <button
                      className="btn btn-secondary btn-sm"
                      onClick={() => setExpandedDept(expandedDept === d.departmentId ? null : d.departmentId)}
                    >
                      {expandedDept === d.departmentId
                        ? t('admin.dashboard.hideSubcategories')
                        : t('admin.dashboard.showSubcategories')}
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}

        {expandedDept != null && subs.length > 0 && (
          <div className="table-wrap mt-2">
            <table className="data">
              <thead>
                <tr>
                  <th>{t('admin.dashboard.subcategoryName')}</th>
                  <th>{t('admin.dashboard.totalTickets')}</th>
                  <th>{t('status.IN_PROGRESS')}</th>
                  <th>{t('status.REVIEW')}</th>
                  <th>{t('status.COMPLETED')}</th>
                  <th>{t('admin.dashboard.last7')}</th>
                </tr>
              </thead>
              <tbody>
                {subs.map(s => {
                  const dayMax = Math.max(1, ...s.last7Days.map(x => x.count));
                  return (
                    <tr key={s.subcategoryId}>
                      <td style={{ fontWeight: 500 }}>{s.subcategoryName}</td>
                      <td>{s.totalTickets}</td>
                      <td>{s.byStatus.IN_PROGRESS ?? 0}</td>
                      <td>{s.byStatus.REVIEW ?? 0}</td>
                      <td>{s.byStatus.COMPLETED ?? 0}</td>
                      <td style={{ width: 180 }}>
                        <div className="bar-chart" style={{ height: 40 }}>
                          {s.last7Days.map((r, i) => (
                            <div key={r.date} className="bar-chart-item">
                              <div
                                className="bar-chart-bar"
                                data-color={(i % 6) + 1}
                                style={{ height: `${Math.max(4, (r.count / dayMax) * 100)}%` }}
                                title={`${r.date}: ${r.count}`}
                              />
                            </div>
                          ))}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Agent leaderboard */}
      <div className="card mt-2">
        <div className="row-between mb-2">
          <h3 style={{ margin: 0 }}>{t('admin.dashboard.agentLeaderboard')}</h3>
          <div style={{ display: 'flex', gap: 8 }}>
            {(['day', 'week', 'month'] as Range[]).map(r => (
              <button
                key={r}
                className={`btn btn-sm ${range === r ? 'btn-primary' : 'btn-secondary'}`}
                onClick={() => setRange(r)}
              >
                {t(`admin.dashboard.range.${r}`)}
              </button>
            ))}
          </div>
        </div>
        {leaderboard == null ? (
          <div className="empty-state">{t('common.loading')}</div>
        ) : leaderboard.rows.length === 0 ? (
          <div className="empty-state">{t('admin.dashboard.noActivity')}</div>
        ) : (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th>{t('admin.dashboard.agent')}</th>
                  <th>{t('admin.dashboard.today')}</th>
                  <th>{t('admin.dashboard.last7')}</th>
                  <th>{t('admin.dashboard.inRange')}</th>
                  <th>{t('admin.dashboard.avgPerDay')}</th>
                  <th style={{ textAlign: 'end' }}>{t('common.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {leaderboard.rows.map((row: AgentLeaderboardRow) => (
                  <tr key={row.userId}>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <Avatar name={row.displayName} size="sm" />
                        <div>
                          <div style={{ fontWeight: 500 }}>{row.displayName}</div>
                          <div className="small muted">@{row.username}</div>
                        </div>
                      </div>
                    </td>
                    <td>{row.todayCount}</td>
                    <td>{row.last7DaysCount}</td>
                    <td>{row.totalTickets}</td>
                    <td>{row.avgPerDay}</td>
                    <td className="actions-cell">
                      <Link to={`/admin/users/${row.userId}/activity`} className="btn btn-secondary btn-sm">
                        {t('admin.dashboard.viewActivity')}
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="card mt-2">
        <div className="row-between mb-2">
          <h3 style={{ margin: 0 }}>{t('admin.dashboard.activeItems')}</h3>
          <Link to="/admin/tickets" className="btn btn-ghost btn-sm">{t('admin.dashboard.viewAll')}</Link>
        </div>
        {recent.length === 0 ? (
          <div className="empty-state">{t('admin.dashboard.empty')}</div>
        ) : (
          <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
            {recent.map((r) => (
              <li key={r.id} style={{
                display: 'flex', alignItems: 'center', gap: '0.75rem',
                padding: '0.75rem 0', borderBottom: '1px solid var(--border)',
              }}>
                <Avatar name={r.submittedByDisplayName || r.submittedByUsername} size="sm" />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontWeight: 500 }}>
                    #{r.id} — {r.websiteName}
                  </div>
                  <div className="small muted">
                    {r.departmentName}{r.subcategoryName ? ` · ${r.subcategoryName}` : ''}
                  </div>
                </div>
                <span className="small muted">
                  {new Date(r.submittedAt).toLocaleString()}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

function StatCard({
  label, value, icon, hero,
}: {
  label: string;
  value?: number | string;
  icon: React.ReactNode;
  hero?: 'yellow' | 'amber' | 'blue';
}) {
  return (
    <div className={`stat-card${hero ? ` hero-${hero}` : ''}`}>
      <div className="stat-card-header">
        <span className="stat-card-label">{label}</span>
        <span className="stat-card-icon">{icon}</span>
      </div>
      <div className="stat-card-value">{value ?? '—'}</div>
    </div>
  );
}
