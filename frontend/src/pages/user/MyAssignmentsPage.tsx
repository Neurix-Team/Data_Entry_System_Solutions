import { useCallback, useEffect, useMemo, useState } from 'react';
import { extractError } from '../../api/client';
import { assignmentsApi } from '../../api/resources';
import type { Assignment, AssignmentList } from '../../api/types';
import {
  DueBadge, formatDateTime, isOverdue, personName,
} from '../../components/AssignmentBits';
import { IconCheck } from '../../components/Icons';
import { useToast } from '../../components/toast/ToastContext';
import { useT } from '../../i18n';

export function MyAssignmentsPage() {
  const { t, lang } = useT();
  const toast = useToast();
  const [data, setData] = useState<AssignmentList | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [showDone, setShowDone] = useState(false);

  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      const list = await assignmentsApi.listMine(signal);
      setData(list);
      setError(null);
    } catch (e) {
      if (signal?.aborted) return;
      setError(extractError(e));
    } finally {
      if (!signal?.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    const ctrl = new AbortController();
    load(ctrl.signal);
    // A leader may hand out work while the agent is away: refresh when they come back.
    const onFocus = () => load();
    window.addEventListener('focus', onFocus);
    return () => {
      ctrl.abort();
      window.removeEventListener('focus', onFocus);
    };
  }, [load]);

  const { open, done } = useMemo(() => {
    const items = data?.items ?? [];
    const open = items
      .filter((a) => a.status === 'OPEN')
      .sort((a, b) => {
        const da = a.dueDate ?? '9999-12-31';
        const db = b.dueDate ?? '9999-12-31';
        if (da !== db) return da < db ? -1 : 1;
        return b.createdAt.localeCompare(a.createdAt);
      });
    const done = items
      .filter((a) => a.status === 'DONE')
      .sort((a, b) => (b.completedAt ?? '').localeCompare(a.completedAt ?? ''));
    return { open, done };
  }, [data]);

  function patch(updated: Assignment) {
    setData((cur) => {
      if (!cur) return cur;
      const items = cur.items.map((x) => (x.id === updated.id ? updated : x));
      const openCount = items.filter((x) => x.status === 'OPEN').length;
      return { items, summary: { open: openCount, done: items.length - openCount } };
    });
  }

  async function markDone(a: Assignment) {
    setBusyId(a.id);
    try {
      patch(await assignmentsApi.markDone(a.id));
      toast.success(t('assignments.my.doneToast'));
    } catch (e) {
      toast.error(extractError(e));
    } finally {
      setBusyId(null);
    }
  }

  async function undo(a: Assignment) {
    setBusyId(a.id);
    try {
      patch(await assignmentsApi.reopenMine(a.id));
      toast.success(t('assignments.my.reopenedToast'));
    } catch (e) {
      toast.error(extractError(e));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('assignments.my.title')}</h1>
          <p className="subtitle">{t('assignments.my.subtitle')}</p>
        </div>
        {data && (
          <div className="row gap-2" style={{ flexWrap: 'wrap', justifyContent: 'flex-end' }}>
            <span className="status-pill status-in-progress">{t('assignments.my.openCount', { count: data.summary.open })}</span>
            <span className="status-pill status-completed">{t('assignments.my.doneCount', { count: data.summary.done })}</span>
          </div>
        )}
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <section aria-labelledby="assignments-open-heading">
        <h2 id="assignments-open-heading" style={{ fontSize: 'var(--fs-lg)', margin: '0 0 0.75rem' }}>
          {t('assignments.my.openSection')}
        </h2>

        {loading ? (
          <div className="card" aria-busy="true">
            <span className="skel-band" style={{ width: '46%' }} />
            <div style={{ marginTop: 10 }}><span className="skel-band" style={{ width: '82%' }} /></div>
          </div>
        ) : open.length === 0 ? (
          <div className="card" style={{ textAlign: 'center', color: 'var(--text-tertiary)' }}>
            {t('assignments.my.empty')}
          </div>
        ) : (
          <div style={{ display: 'grid', gap: '0.85rem' }}>
            {open.map((a) => (
              <AssignmentCard
                key={a.id}
                a={a}
                busy={busyId === a.id}
                onPrimary={() => markDone(a)}
                primaryLabel={t('assignments.my.markDone')}
              />
            ))}
          </div>
        )}
      </section>

      <section aria-labelledby="assignments-done-heading" style={{ marginTop: '2rem' }}>
        <div className="row-between" style={{ marginBottom: '0.75rem' }}>
          <h2 id="assignments-done-heading" style={{ fontSize: 'var(--fs-lg)', margin: 0 }}>
            {t('assignments.my.doneSection')}
            {data && <span className="muted small" style={{ marginInlineStart: 8 }}>({data.summary.done})</span>}
          </h2>
          {done.length > 0 && (
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              aria-expanded={showDone}
              onClick={() => setShowDone((v) => !v)}
            >
              {showDone ? t('common.close') : t('common.view')}
            </button>
          )}
        </div>

        {!loading && done.length === 0 && (
          <div className="muted small">{t('assignments.my.emptyDone')}</div>
        )}
        {showDone && done.length > 0 && (
          <div style={{ display: 'grid', gap: '0.85rem' }}>
            {done.map((a) => (
              <AssignmentCard
                key={a.id}
                a={a}
                busy={busyId === a.id}
                onSecondary={() => undo(a)}
                secondaryLabel={t('assignments.my.undo')}
              />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

interface CardProps {
  a: Assignment;
  busy: boolean;
  primaryLabel?: string;
  onPrimary?: () => void;
  secondaryLabel?: string;
  onSecondary?: () => void;
}

function AssignmentCard({ a, busy, primaryLabel, onPrimary, secondaryLabel, onSecondary }: CardProps) {
  const { t, lang } = useT();
  const late = isOverdue(a);
  const isDone = a.status === 'DONE';
  return (
    <article
      className="card"
      style={{
        padding: '1.1rem 1.35rem',
        borderInlineStart: late ? '3px solid var(--danger)' : undefined,
        opacity: isDone ? 0.85 : 1,
      }}
    >
      <div style={{ display: 'flex', gap: '1rem', alignItems: 'flex-start', flexWrap: 'wrap' }}>
        <div style={{ flex: '1 1 320px', minWidth: 0 }}>
          <h3 style={{
            margin: 0,
            fontSize: 'var(--fs-md)',
            fontWeight: 600,
            textDecoration: isDone ? 'line-through' : undefined,
          }}>{a.title}</h3>
          <div
            className="small"
            style={{
              marginTop: 8,
              whiteSpace: 'pre-wrap',
              color: a.description ? 'var(--text-secondary)' : 'var(--text-tertiary)',
            }}
          >
            {a.description || t('assignments.noDescription')}
          </div>
          <div className="small muted" style={{ marginTop: 10, display: 'flex', gap: '0.75rem', flexWrap: 'wrap' }}>
            {a.assignedBy && (
              <span>{t('assignments.assignedBy')}: <strong style={{ fontWeight: 600 }}>{personName(a.assignedBy, lang)}</strong></span>
            )}
            <span>{t('assignments.createdAt')}: {formatDateTime(a.createdAt, lang)}</span>
            {isDone && a.completedAt && (
              <span>{t('assignments.completedAt')}: {formatDateTime(a.completedAt, lang)}</span>
            )}
          </div>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: '0.6rem' }}>
          <span className="small">
            <span className="muted">{t('assignments.dueLabel')}: </span>
            <DueBadge a={a} />
          </span>
          {onPrimary && (
            <button type="button" className="btn btn-primary" onClick={onPrimary} disabled={busy}>
              {busy ? <span className="spinner" /> : <><IconCheck size={16} /> {primaryLabel}</>}
            </button>
          )}
          {onSecondary && (
            <button type="button" className="btn btn-ghost btn-sm" onClick={onSecondary} disabled={busy}>
              {secondaryLabel}
            </button>
          )}
        </div>
      </div>
    </article>
  );
}
