import { useEffect, useState } from 'react';
import { extractError } from '../../api/client';
import { adminFeaturesApi } from '../../api/resources';
import type { WorkloadData, WorkloadRow } from '../../api/types';
import { useT } from '../../i18n';

/** C3: who is loaded, who is free — reassign in seconds instead of guessing. */
export function AdminWorkloadPage() {
  const { t, lang } = useT();
  const [data, setData] = useState<WorkloadData | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    adminFeaturesApi.workload()
      .then((d) => { setData(d); setError(null); })
      .catch((e) => setError(extractError(e)));
  }, []);

  const maxLoad = Math.max(1, ...(data?.rows ?? []).map((r) => r.loadScore));

  function bar(row: WorkloadRow) {
    const width = Math.max(3, Math.round((row.loadScore / maxLoad) * 100));
    return (
      <div style={{ background: 'var(--bg-muted)', borderRadius: 999, height: 8, minWidth: 90 }}>
        <div style={{
          width: `${width}%`, height: '100%', borderRadius: 999,
          background: row.loadScore > 0 ? 'var(--brand)' : 'var(--status-completed)',
        }} />
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('workload.title')}</h1>
          <p className="subtitle">{t('workload.subtitle')}</p>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr>
              <th>{t('workload.member')}</th>
              <th>{t('workload.openAssignments')}</th>
              <th>{t('workload.weekEntries')}</th>
              <th>{t('workload.weekCompleted')}</th>
              <th style={{ minWidth: 140 }}>{t('workload.load')}</th>
              <th>{t('workload.flag')}</th>
            </tr>
          </thead>
          <tbody>
            {data === null ? (
              <tr><td colSpan={6} className="muted">{t('common.loading')}</td></tr>
            ) : data.rows.length === 0 ? (
              <tr><td colSpan={6} className="muted" style={{ textAlign: 'center', padding: '1.5rem' }}>
                {t('workload.empty')}
              </td></tr>
            ) : data.rows.map((row) => (
              <tr key={row.userId}>
                <td>
                  <div style={{ fontWeight: 600 }}>{row.displayName}</div>
                  <div className="muted small">{row.username}</div>
                </td>
                <td><span className="small">{row.openAssignments}</span></td>
                <td><span className="small">{row.weekEntries}</span></td>
                <td><span className="small">{row.weekCompleted}</span></td>
                <td>{bar(row)}</td>
                <td>
                  {data.busiestUserId === row.userId && (
                    <span className="status-pill status-overdue">{t('workload.busiest')}</span>
                  )}
                  {data.freestUserId === row.userId && (
                    <span className="status-pill status-review">{t('workload.freest')}</span>
                  )}
                  {data.busiestUserId !== row.userId && data.freestUserId !== row.userId && (
                    <span className="muted small">—</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {data && data.rows.length > 0 && (
        <p className="muted small" style={{ marginTop: 10 }}>
          {lang === 'ar'
            ? 'درجة الحِمل = (التكليفات المفتوحة × 3) + مدخلات الأسبوع. الأعلى = محمّل، الأدنى = فاضي.'
            : 'Load score = (open assignments × 3) + week entries. Highest = loaded, lowest = free.'}
        </p>
      )}
    </div>
  );
}