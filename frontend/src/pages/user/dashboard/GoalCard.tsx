import { useCallback, useEffect, useState } from 'react';
import { extractError } from '../../../api/client';
import { goalsApi } from '../../../api/resources';
import type { GoalsData } from '../../../api/types';
import { useT } from '../../../i18n';

/**
 * Daily-goal card (B5): today vs the personal target, the current streak and the
 * 7-day mini-bars. The target is editable inline — informational, never enforced.
 */
export function GoalCard() {
  const { t } = useT();
  const [data, setData] = useState<GoalsData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState(false);
  const [draftGoal, setDraftGoal] = useState('');

  const load = useCallback(() => {
    goalsApi.get()
      .then((d) => { setData(d); setError(null); })
      .catch((e) => setError(extractError(e)));
  }, []);

  useEffect(() => { load(); }, [load]);

  async function saveGoal() {
    const n = Number(draftGoal);
    if (!Number.isFinite(n) || n < 1 || n > 1000) return;
    try {
      setData(await goalsApi.update(n));
      setEditing(false);
    } catch (e) {
      setError(extractError(e));
    }
  }

  if (error) {
    return (
      <div className="udash-panel">
        <div className="alert alert-error" style={{ margin: 0 }}>{error}</div>
      </div>
    );
  }
  if (!data) {
    return <div className="udash-panel"><div className="muted">{t('common.loading')}</div></div>;
  }

  const pct = data.dailyGoal > 0
    ? Math.min(100, Math.round((data.todayCount / data.dailyGoal) * 100))
    : 0;
  const done = data.todayCount >= data.dailyGoal;
  const maxDay = Math.max(1, ...data.last7Days.map((d) => d.count));

  return (
    <div className="udash-panel">
      <div className="udash-panel-head">
        <div>
          <div className="udash-panel-title">{t('goals.title')}</div>
          <div className="udash-panel-sub">{t('goals.subtitle')}</div>
        </div>
        {!editing && (
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={() => { setDraftGoal(String(data.dailyGoal)); setEditing(true); }}
          >
            {t('goals.editTarget')}
          </button>
        )}
        {editing && (
          <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
            <input
              className="input"
              type="number"
              min={1}
              max={1000}
              value={draftGoal}
              onChange={(e) => setDraftGoal(e.target.value)}
              style={{ width: 84 }}
            />
            <button type="button" className="btn btn-sm btn-primary" onClick={saveGoal}>
              {t('common.save')}
            </button>
            <button type="button" className="btn btn-sm btn-ghost" onClick={() => setEditing(false)}>
              {t('common.cancel')}
            </button>
          </span>
        )}
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 18, flexWrap: 'wrap' }}>
        <div
          role="img"
          aria-label={t('goals.progressOf', { today: data.todayCount, goal: data.dailyGoal })}
          style={{
            width: 96, height: 96, borderRadius: '50%',
            background: `conic-gradient(var(--brand) ${pct * 3.6}deg, var(--bg-muted) 0deg)`,
            display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
          }}
        >
          <div style={{
            width: 76, height: 76, borderRadius: '50%', background: 'var(--bg-surface)',
            display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
          }}>
            <strong style={{ fontSize: 20 }}>{data.todayCount}</strong>
            <span className="muted small">/ {data.dailyGoal}</span>
          </div>
        </div>

        <div style={{ flex: 1, minWidth: 220 }}>
          <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', marginBottom: 10 }}>
            <span className="small">
              {done ? '🎉 ' : '📌 '}
              {done ? t('goals.doneToday', { pct }) : t('goals.progressOf', { today: data.todayCount, goal: data.dailyGoal })}
            </span>
            <span className="small">🔥 {t('goals.streak', { n: data.currentStreak })}</span>
            <span className="small">🏆 {t('goals.best', { n: data.bestStreak })}</span>
            <span className="small muted">{t('goals.week', { n: data.weekCount })}</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'flex-end', gap: 5, height: 44 }}>
            {data.last7Days.map((d) => (
              <div
                key={d.day}
                title={`${d.day}: ${d.count}`}
                style={{
                  flex: 1, minWidth: 18,
                  height: `${Math.max(4, (d.count / maxDay) * 100)}%`,
                  background: d.count > 0 ? 'var(--brand)' : 'var(--bg-muted)',
                  borderRadius: '4px 4px 0 0',
                  transition: 'height 0.3s ease',
                }}
              />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}