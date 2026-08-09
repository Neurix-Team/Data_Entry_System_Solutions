import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { extractError } from '../../api/client';
import { dashboardApi } from '../../api/resources';
import type { UserActivity } from '../../api/types';
import { Avatar } from '../../components/Avatar';
import { useT } from '../../i18n';

export function AdminUserActivityPage() {
  const { id } = useParams<{ id: string }>();
  const { t } = useT();
  const [days, setDays] = useState(30);
  const [data, setData] = useState<UserActivity | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    dashboardApi.user(Number(id), days)
      .then(setData)
      .catch(e => setError(extractError(e)))
      .finally(() => setLoading(false));
  }, [id, days]);

  const max = data ? Math.max(1, ...data.daily.map(d => d.count)) : 1;

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('admin.userActivity.title')}</h1>
          <p className="subtitle">{t('admin.userActivity.subtitle')}</p>
        </div>
        <Link to="/admin/users" className="btn btn-ghost btn-sm">{t('admin.userActivity.backToTeam')}</Link>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {loading || !data ? (
        <div className="card empty-state">{t('common.loading')}</div>
      ) : (
        <>
          <div className="card" style={{ padding: '1.25rem', display: 'flex', gap: '1rem', alignItems: 'center', marginBottom: '1rem' }}>
            <Avatar name={data.displayName} size="lg" />
            <div style={{ flex: 1 }}>
              <div style={{ fontWeight: 700, fontSize: 'var(--fs-lg)' }}>{data.displayName}</div>
              <div className="small muted">@{data.username}</div>
            </div>
            <div style={{ textAlign: 'end' }}>
              <div style={{ fontSize: 'var(--fs-xl)', fontWeight: 700 }}>{data.totalTickets}</div>
              <div className="small muted">{t('admin.userActivity.totalInWindow', { days: data.daysWindow })}</div>
            </div>
          </div>

          <div className="card" style={{ padding: '1.5rem' }}>
            <div className="row-between mb-2">
              <h3 style={{ margin: 0 }}>{t('admin.userActivity.daily')}</h3>
              <div style={{ display: 'flex', gap: 8 }}>
                {[7, 30, 90].map(d => (
                  <button
                    key={d}
                    className={`btn btn-sm ${days === d ? 'btn-primary' : 'btn-secondary'}`}
                    onClick={() => setDays(d)}
                  >
                    {t('admin.userActivity.lastNDays', { days: d })}
                  </button>
                ))}
              </div>
            </div>
            <div className="bar-chart" style={{ height: 160 }}>
              {data.daily.map((row, i) => {
                const h = (row.count / max) * 100;
                const label = new Date(row.date).getDate();
                const showLabel = i % Math.ceil(data.daily.length / 10) === 0;
                return (
                  <div key={row.date} className="bar-chart-item">
                    <div
                      className="bar-chart-bar"
                      data-color={(i % 6) + 1}
                      style={{ height: `${Math.max(4, h)}%` }}
                      title={`${row.date}: ${row.count}`}
                    />
                    {showLabel && <span className="bar-chart-label">{label}</span>}
                  </div>
                );
              })}
            </div>
          </div>

          <div className="two-col">
            <div className="card">
              <h3 style={{ margin: '0 0 0.75rem 0' }}>{t('admin.userActivity.byDepartment')}</h3>
              {data.byDepartment.length === 0 ? (
                <div className="empty-state">{t('admin.userActivity.noBreakdown')}</div>
              ) : (
                <BreakdownList rows={data.byDepartment} />
              )}
            </div>
            <div className="card">
              <h3 style={{ margin: '0 0 0.75rem 0' }}>{t('admin.userActivity.bySubcategory')}</h3>
              {data.bySubcategory.length === 0 ? (
                <div className="empty-state">{t('admin.userActivity.noBreakdown')}</div>
              ) : (
                <BreakdownList rows={data.bySubcategory} />
              )}
            </div>
          </div>

          <div className="card mt-2">
            <h3 style={{ margin: '0 0 0.75rem 0' }}>{t('admin.userActivity.byStatus')}</h3>
            <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
              {(['IN_PROGRESS', 'REVIEW', 'COMPLETED'] as const).map(s => (
                <div key={s} className="stat-card" style={{ flex: 1, minWidth: 140 }}>
                  <div className="stat-card-header">
                    <span className="stat-card-label">{t(`status.${s}`)}</span>
                  </div>
                  <div className="stat-card-value">{data.byStatus[s] ?? 0}</div>
                </div>
              ))}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function BreakdownList({ rows }: { rows: { id: number; name: string; count: number }[] }) {
  const max = Math.max(1, ...rows.map(r => r.count));
  return (
    <div>
      {rows.map(r => {
        const pct = Math.round((r.count / max) * 100);
        return (
          <div key={r.id} style={{ padding: '0.5rem 0' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
              <span style={{ fontWeight: 500 }}>{r.name}</span>
              <span className="muted small">{r.count}</span>
            </div>
            <div className="progress-bar">
              <div className="progress-bar-fill" data-tone="ok" style={{ width: `${pct}%` }} />
            </div>
          </div>
        );
      })}
    </div>
  );
}
