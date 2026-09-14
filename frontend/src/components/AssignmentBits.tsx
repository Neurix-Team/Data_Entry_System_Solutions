import type { Assignment, AssignmentPerson, AssignmentStatus } from '../api/types';
import { useT, type Lang } from '../i18n';
import { pickLocalized } from '../i18n/localized';

/** Local calendar date as yyyy-mm-dd, matching the server's LocalDate. */
export function todayIso(): string {
  const d = new Date();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${m}-${day}`;
}

export function isOverdue(a: Pick<Assignment, 'status' | 'dueDate'>): boolean {
  return a.status === 'OPEN' && !!a.dueDate && a.dueDate < todayIso();
}

export function personName(p: AssignmentPerson | null | undefined, lang: Lang): string {
  if (!p) return '';
  return pickLocalized(p, 'displayName', lang) || p.username;
}

export function formatDateTime(iso: string, lang: Lang): string {
  return new Date(iso).toLocaleString(lang === 'ar' ? 'ar-EG' : undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
}

/** `yyyy-mm-dd` → local date string without the UTC-midnight shift. */
export function formatDue(due: string, lang: Lang): string {
  return new Date(`${due}T00:00:00`).toLocaleDateString(lang === 'ar' ? 'ar-EG' : undefined, {
    dateStyle: 'medium',
  });
}

export function AssignmentStatusPill({ status }: { status: AssignmentStatus }) {
  const { t } = useT();
  const cls = status === 'DONE' ? 'status-completed' : 'status-in-progress';
  return (
    <span className={`status-pill ${cls}`}>
      {status === 'DONE' ? t('assignments.statusDone') : t('assignments.statusOpen')}
    </span>
  );
}

export function DueBadge({ a }: { a: Pick<Assignment, 'status' | 'dueDate'> }) {
  const { t, lang } = useT();
  if (!a.dueDate) return <span className="muted">{t('assignments.noDue')}</span>;
  const late = isOverdue(a);
  return (
    <span
      className={late ? 'status-pill status-overdue' : undefined}
      title={late ? t('assignments.overdue') : undefined}
    >
      {formatDue(a.dueDate, lang)}
      {late && <span className="small"> · {t('assignments.overdue')}</span>}
    </span>
  );
}
