import { useEffect, useState } from 'react';
import { extractError } from '../../api/client';
import { departmentsApi, ticketsApi } from '../../api/resources';
import type { AdminStats, Department, ReportData, Ticket } from '../../api/types';
import { Avatar } from '../../components/Avatar';
import { IconCheck, IconClock, IconTrendUp } from '../../components/Icons';
import { useT } from '../../i18n';

export function AdminReportsPage() {
  const { t } = useT();
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [report, setReport] = useState<ReportData | null>(null);
  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([
      ticketsApi.stats(),
      ticketsApi.reports(),
      ticketsApi.listAll(0, 500),
      departmentsApi.adminList(),
    ])
      .then(([s, r, tk, d]) => {
        setStats(s); setReport(r); setTickets(tk.items); setDepartments(d);
      })
      .catch((e) => setError(extractError(e)));
  }, []);

  const maxCount = report ? Math.max(1, ...Object.values(report.byDay)) : 1;
  const barColors = [2, 1, 2, 6, 3];

  // department fill rates
  const deptStats = departments.map((d) => {
    const count = tickets.filter(tk => tk.departmentId === d.id).length;
    return { name: d.name, count };
  });
  const maxDept = Math.max(1, ...deptStats.map(x => x.count));

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('admin.reports.title')}</h1>
          <p className="subtitle">{t('admin.reports.subtitle')}</p>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(2, minmax(0, 1fr))' }}>
        <div className="stat-card">
          <div className="stat-card-header">
            <span className="stat-card-label">{t('admin.reports.completed')}</span>
            <span className="stat-card-icon" style={{ background: 'var(--success-soft)', color: 'var(--success-soft-text)' }}>
              <IconCheck size={16} />
            </span>
          </div>
          <div className="stat-card-value">{stats?.completed ?? 0}</div>
          <div className="stat-card-trend up">
            <IconTrendUp size={14} /> +{report?.completedThisWeek ?? 0} this week
          </div>
        </div>
        <div className="stat-card">
          <div className="stat-card-header">
            <span className="stat-card-label">{t('admin.reports.pending')}</span>
            <span className="stat-card-icon" style={{ background: 'var(--status-progress-soft)', color: 'var(--status-progress-text)' }}>
              <IconClock size={16} />
            </span>
          </div>
          <div className="stat-card-value">{(stats?.inProgress ?? 0) + (stats?.review ?? 0)}</div>
          <div className="stat-card-trend muted">→ Steady {t('admin.reports.vsLastWeek')}</div>
        </div>
      </div>

      <div className="two-col">
        <div className="card">
          <div className="row-between mb-2">
            <h3 style={{ margin: 0 }}>{t('admin.reports.taskDistribution')}</h3>
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
          <h3 style={{ marginTop: 0, marginBottom: '1rem' }}>{t('admin.reports.topPerformers')}</h3>
          {report && report.topPerformers.length === 0 && (
            <div className="empty-state">No completed entries yet.</div>
          )}
          {report?.topPerformers.map((p) => (
            <div key={p.userId} style={{
              display: 'flex', alignItems: 'center', gap: '0.75rem',
              padding: '0.75rem 0', borderBottom: '1px solid var(--border)',
            }}>
              <Avatar name={p.displayName} size="md" />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 600 }}>{p.displayName}</div>
                <div className="small muted">@{p.username}</div>
              </div>
              <div style={{ textAlign: 'end' }}>
                <div style={{ fontWeight: 700, color: 'var(--brand-soft-text)' }}>{p.completed}</div>
                <div className="small muted">{t('admin.reports.tasksCompleted')}</div>
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="card mt-2">
        <h3 style={{ marginTop: 0, marginBottom: '1rem' }}>{t('admin.reports.projectProgress')}</h3>
        {deptStats.length === 0 ? (
          <div className="empty-state">No departments yet.</div>
        ) : deptStats.map((d, i) => {
          const pct = Math.round((d.count / maxDept) * 100);
          const tones: ('amber' | 'blue' | 'green')[] = ['amber', 'blue', 'green'];
          return (
            <div key={d.name} style={{ marginBottom: '1.15rem' }}>
              <div className="row-between" style={{ marginBottom: '0.4rem' }}>
                <span style={{ fontWeight: 500 }}>{d.name}</span>
                <span className="muted small">{d.count}</span>
              </div>
              <div className="progress-bar">
                <div className="progress-bar-fill" data-tone={tones[i % 3]} style={{ width: `${pct}%` }} />
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
