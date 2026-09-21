import { useEffect, useState } from 'react';
import { extractError } from '../../api/client';
import { adminFeaturesApi } from '../../api/resources';
import type { QualityRow } from '../../api/types';
import { useT } from '../../i18n';

function rateBar(pct: number, color: string) {
  return (
    <div style={{ background: 'var(--bg-muted)', borderRadius: 999, height: 8, minWidth: 80 }}>
      <div style={{
        width: `${Math.max(0, Math.min(100, pct))}%`,
        height: '100%', borderRadius: 999, background: color,
      }} />
    </div>
  );
}

/** C5: who sends clean work and who sends rework — sorted worst-first, coaching-friendly. */
export function AdminQualityPage() {
  const { t } = useT();
  const [rows, setRows] = useState<QualityRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    adminFeaturesApi.quality()
      .then((d) => { setRows(d.rows); setError(null); })
      .catch((e) => setError(extractError(e)));
  }, []);

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('quality.title')}</h1>
          <p className="subtitle">{t('quality.subtitle')}</p>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr>
              <th>{t('quality.agent')}</th>
              <th>{t('quality.total')}</th>
              <th style={{ minWidth: 130 }}>{t('quality.completedRate')}</th>
              <th style={{ minWidth: 130 }}>{t('quality.reviewRate')}</th>
              <th>{t('quality.weekTotal')}</th>
            </tr>
          </thead>
          <tbody>
            {rows === null ? (
              <tr><td colSpan={5} className="muted">{t('common.loading')}</td></tr>
            ) : rows.length === 0 ? (
              <tr><td colSpan={5} className="muted" style={{ textAlign: 'center', padding: '1.5rem' }}>
                {t('quality.empty')}
              </td></tr>
            ) : rows.map((r) => (
              <tr key={r.userId}>
                <td>
                  <div style={{ fontWeight: 600 }}>{r.displayName}</div>
                  <div className="muted small">{r.username}</div>
                </td>
                <td><span className="small">{r.total}</span></td>
                <td>
                  {rateBar(r.completedRate, 'var(--success)')}
                  <span className="muted small" style={{ display: 'block', marginTop: 3 }}>
                    {r.completedRate.toFixed(1)}% · {r.completed}
                  </span>
                </td>
                <td>
                  {rateBar(r.reviewRate, r.reviewRate > 40 ? 'var(--warning)' : 'var(--status-review)')}
                  <span className="muted small" style={{ display: 'block', marginTop: 3 }}>
                    {r.reviewRate.toFixed(1)}% · {r.review}
                  </span>
                </td>
                <td><span className="small">{r.weekTotal}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <p className="muted small" style={{ marginTop: 10 }}>
        {t('quality.legend')}
      </p>
    </div>
  );
}