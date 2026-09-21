import { useCallback, useEffect, useState } from 'react';
import { extractError } from '../../api/client';
import { adminFeaturesApi } from '../../api/resources';
import type { WeeklyReport } from '../../api/types';
import { useToast } from '../../components/toast/ToastContext';
import { useT } from '../../i18n';

/**
 * C4: the weekly digest. The backend also pushes it to every team leader every
 * Monday at 06:00 — this page shows it on demand and has a "send now" button.
 */
export function AdminWeeklyReportPage() {
  const { t, lang } = useT();
  const toast = useToast();
  const [report, setReport] = useState<WeeklyReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dispatching, setDispatching] = useState(false);

  const load = useCallback(() => {
    adminFeaturesApi.weekly()
      .then((r) => { setReport(r); setError(null); })
      .catch((e) => setError(extractError(e)));
  }, []);

  useEffect(() => { load(); }, [load]);

  async function dispatch() {
    setDispatching(true);
    try {
      const r = await adminFeaturesApi.dispatchWeekly();
      toast.success(t('weekly.dispatched', { n: r.notified }));
    } catch (e) {
      toast.error(extractError(e));
    } finally {
      setDispatching(false);
    }
  }

  const maxTotal = Math.max(1, ...(report?.byDay ?? []).map((d) => d.total));

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('weekly.title')}</h1>
          <p className="subtitle">{t('weekly.subtitle')}</p>
        </div>
        <button type="button" className="btn btn-primary" disabled={dispatching} onClick={dispatch}>
          📤 {dispatching ? t('common.loading') : t('weekly.sendNow')}
        </button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {!report && !error && (
        <div className="card"><span className="muted">{t('common.loading')}</span></div>
      )}

      {report && (
        <>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 14 }}>
            <div className="card" style={{ flex: 1, minWidth: 160, margin: 0 }}>
              <div className="muted small">{t('weekly.total')}</div>
              <div style={{ fontSize: 26, fontWeight: 700 }}>{report.totalEntries}</div>
            </div>
            <div className="card" style={{ flex: 1, minWidth: 160, margin: 0 }}>
              <div className="muted small">{t('weekly.completed')}</div>
              <div style={{ fontSize: 26, fontWeight: 700 }}>{report.completedEntries}</div>
            </div>
            <div className="card" style={{ flex: 1, minWidth: 160, margin: 0 }}>
              <div className="muted small">{t('weekly.rate')}</div>
              <div style={{ fontSize: 26, fontWeight: 700 }}>{report.completionRatePct}%</div>
            </div>
          </div>

          <div className="card" style={{ marginBottom: 14 }}>
            <div className="udash-panel-title" style={{ marginBottom: 10 }}>{t('weekly.byDay')}</div>
            <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, height: 120 }}>
              {report.byDay.map((d) => (
                <div key={d.day} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
                  <span className="muted small">{d.total}</span>
                  <div style={{
                    width: '100%',
                    height: `${Math.max(4, (d.total / maxTotal) * 80)}px`,
                    background: 'linear-gradient(180deg, var(--brand), var(--accent-cyan))',
                    borderRadius: '6px 6px 0 0',
                  }} />
                  <span className="muted small">
                    {new Date(`${d.day}T12:00:00`).toLocaleDateString(lang === 'ar' ? 'ar-EG' : undefined, { weekday: 'short' })}
                  </span>
                </div>
              ))}
            </div>
          </div>

          <div className="card">
            <div className="udash-panel-title" style={{ marginBottom: 10 }}>{t('weekly.top')}</div>
            {report.topPerformers.length === 0 ? (
              <div className="muted">{t('weekly.empty')}</div>
            ) : (
              <ol style={{ margin: 0, paddingLeft: 20 }}>
                {report.topPerformers.map((p) => (
                  <li key={p.userId} style={{ marginBottom: 6 }}>
                    <strong>{p.displayName}</strong>
                    <span className="muted small"> — {t('weekly.entries', { n: p.total })}</span>
                  </li>
                ))}
              </ol>
            )}
          </div>

          <p className="muted small" style={{ marginTop: 10 }}>
            {t('weekly.scheduleNote')}
          </p>
        </>
      )}
    </div>
  );
}