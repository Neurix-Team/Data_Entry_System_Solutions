import { FormEvent, useEffect, useMemo, useState } from 'react';
import { extractError } from '../../api/client';
import { avatarUrl } from '../../api/profile';
import { assignmentsApi, usersApi } from '../../api/resources';
import type { AdminUser, Assignment, AssignmentList } from '../../api/types';
import {
  AssignmentStatusPill, DueBadge, formatDateTime, isOverdue, personName,
} from '../../components/AssignmentBits';
import { Avatar } from '../../components/Avatar';
import { useConfirm } from '../../components/ConfirmDialog';
import { IconCheck, IconClock, IconPlus, IconTasks } from '../../components/Icons';
import { SidePanel } from '../../components/SidePanel';
import { SkeletonRows } from '../../components/SkeletonRows';
import { useToast } from '../../components/toast/ToastContext';
import { useT } from '../../i18n';

type Filter = 'ALL' | 'OPEN' | 'DONE';

interface FormState {
  id?: number;
  assigneeId: string;
  title: string;
  description: string;
  dueDate: string;
}

const empty: FormState = { assigneeId: '', title: '', description: '', dueDate: '' };

export function AdminAssignmentsPage() {
  const { t, lang } = useT();
  const toast = useToast();
  const confirm = useConfirm();
  const [data, setData] = useState<AssignmentList | null>(null);
  const [members, setMembers] = useState<AdminUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<Filter>('ALL');
  const [q, setQ] = useState('');
  const [panelOpen, setPanelOpen] = useState(false);
  const [form, setForm] = useState<FormState>(empty);
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);

  const isEdit = form.id !== undefined;

  async function refresh() {
    setLoading(true);
    try {
      const [list, users] = await Promise.all([assignmentsApi.listAll(), usersApi.list()]);
      setData(list);
      setMembers(users);
      setError(null);
    } catch (e) {
      setError(extractError(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { refresh(); }, []);

  const assignable = useMemo(() => {
    const label = (u: AdminUser) => (u.displayName || u.username).toLowerCase();
    return members
      .filter((u) => u.active && u.role !== 'SUPER_ADMIN')
      .sort((a, b) => {
        if (a.role !== b.role) return a.role === 'USER' ? -1 : 1;
        return label(a).localeCompare(label(b));
      });
  }, [members]);

  const filtered = useMemo(() => {
    if (!data) return [];
    const query = q.trim().toLowerCase();
    return data.items.filter((a) => {
      if (filter !== 'ALL' && a.status !== filter) return false;
      if (!query) return true;
      const parts = [
        a.title, a.description ?? '',
        a.assignee.username, a.assignee.displayName ?? '',
        a.assignee.displayNameEn ?? '', a.assignee.displayNameAr ?? '',
      ];
      return parts.join(' ').toLowerCase().includes(query);
    });
  }, [data, filter, q]);

  function openCreate() {
    setForm(empty);
    setFormError(null);
    setPanelOpen(true);
  }

  function openEdit(a: Assignment) {
    setForm({
      id: a.id,
      assigneeId: String(a.assignee.id),
      title: a.title,
      description: a.description ?? '',
      dueDate: a.dueDate ?? '',
    });
    setFormError(null);
    setPanelOpen(true);
  }

  async function onSave(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    const title = form.title.trim();
    if (!form.assigneeId) { setFormError(t('assignments.admin.assigneeRequired')); return; }
    if (!title) { setFormError(t('assignments.admin.titleRequired')); return; }

    setSaving(true);
    try {
      if (isEdit) {
        await assignmentsApi.update(form.id!, {
          assigneeId: Number(form.assigneeId),
          title,
          description: form.description,
          dueDate: form.dueDate || null,
          clearDueDate: !form.dueDate,
        });
        toast.success(t('assignments.admin.updatedToast'));
      } else {
        const created = await assignmentsApi.create({
          assigneeId: Number(form.assigneeId),
          title,
          description: form.description.trim() || null,
          dueDate: form.dueDate || null,
        });
        toast.success(t('assignments.admin.createdToast', { name: personName(created.assignee, lang) }));
      }
      setPanelOpen(false);
      refresh();
    } catch (err) {
      const msg = extractError(err);
      setFormError(msg);
      toast.error(msg);
    } finally {
      setSaving(false);
    }
  }

  async function onReopen(a: Assignment) {
    setBusyId(a.id);
    try {
      const updated = await assignmentsApi.reopen(a.id);
      patch(updated);
      toast.success(t('assignments.admin.reopenedToast'));
    } catch (e) {
      toast.error(extractError(e));
    } finally {
      setBusyId(null);
    }
  }

  async function onDelete(a: Assignment) {
    const ok = await confirm({
      message: t('assignments.admin.confirmDelete', { title: a.title }),
      destructive: true,
    });
    if (!ok) return;
    setBusyId(a.id);
    try {
      await assignmentsApi.remove(a.id);
      setData((cur) => cur ? {
        items: cur.items.filter((x) => x.id !== a.id),
        summary: {
          open: cur.summary.open - (a.status === 'OPEN' ? 1 : 0),
          done: cur.summary.done - (a.status === 'DONE' ? 1 : 0),
        },
      } : cur);
      toast.success(t('assignments.admin.deletedToast'));
    } catch (e) {
      toast.error(extractError(e));
    } finally {
      setBusyId(null);
    }
  }

  function patch(updated: Assignment) {
    setData((cur) => {
      if (!cur) return cur;
      const items = cur.items.map((x) => (x.id === updated.id ? updated : x));
      const open = items.filter((x) => x.status === 'OPEN').length;
      return { items, summary: { open, done: items.length - open } };
    });
  }

  const total = data ? data.summary.open + data.summary.done : 0;

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('assignments.admin.title')}</h1>
          <p className="subtitle">{t('assignments.admin.subtitle')}</p>
        </div>
        <button className="btn btn-primary" onClick={openCreate}>
          <IconPlus size={16} /> {t('assignments.admin.newBtn')}
        </button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(3, minmax(0, 1fr))' }}>
        <div className="stat-card">
          <div className="stat-card-header">
            <span className="stat-card-label">{t('assignments.admin.statOpen')}</span>
            <span className="stat-card-icon" style={{ background: 'var(--status-progress-soft)', color: 'var(--status-progress-text)' }}>
              <IconClock size={16} />
            </span>
          </div>
          <div className="stat-card-value">{data?.summary.open ?? 0}</div>
        </div>
        <div className="stat-card">
          <div className="stat-card-header">
            <span className="stat-card-label">{t('assignments.admin.statDone')}</span>
            <span className="stat-card-icon" style={{ background: 'var(--success-soft)', color: 'var(--success-soft-text)' }}>
              <IconCheck size={16} />
            </span>
          </div>
          <div className="stat-card-value">{data?.summary.done ?? 0}</div>
        </div>
        <div className="stat-card">
          <div className="stat-card-header">
            <span className="stat-card-label">{t('assignments.admin.statTotal')}</span>
            <span className="stat-card-icon" style={{ background: 'var(--brand-soft)', color: 'var(--brand-soft-text)' }}>
              <IconTasks size={16} />
            </span>
          </div>
          <div className="stat-card-value">{total}</div>
        </div>
      </div>

      <div className="toolbar">
        <input
          className="input grow"
          type="search"
          placeholder={t('assignments.admin.searchPlaceholder')}
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />
        <div className="view-toggle" role="group" aria-label={t('common.filter')}>
          {(['ALL', 'OPEN', 'DONE'] as Filter[]).map((f) => (
            <button
              key={f}
              type="button"
              className={filter === f ? 'active' : undefined}
              aria-pressed={filter === f}
              onClick={() => setFilter(f)}
            >
              {f === 'ALL' ? t('assignments.filterAll') : f === 'OPEN' ? t('assignments.filterOpen') : t('assignments.filterDone')}
            </button>
          ))}
        </div>
        {data && (
          <span className="muted small" style={{ marginInlineStart: 'auto' }}>
            {t('admin.tickets.resultsCount', { count: filtered.length, total: data.items.length })}
          </span>
        )}
      </div>

      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr>
              <th>{t('assignments.admin.colTask')}</th>
              <th>{t('assignments.admin.colAssignee')}</th>
              <th>{t('assignments.admin.colDue')}</th>
              <th>{t('common.status')}</th>
              <th>{t('assignments.admin.colCreated')}</th>
              <th style={{ textAlign: 'end' }}>{t('common.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <SkeletonRows cols={6} />
            ) : filtered.length === 0 ? (
              <tr><td colSpan={6} className="empty-state">
                {data && data.items.length > 0 ? t('assignments.admin.noMatches') : t('assignments.admin.empty')}
              </td></tr>
            ) : filtered.map((a) => (
              <tr key={a.id} style={isOverdue(a) ? { boxShadow: 'inset 3px 0 0 var(--danger)' } : undefined}>
                <td style={{ maxWidth: 420 }}>
                  <div style={{ fontWeight: 600 }}>{a.title}</div>
                  {a.description && <div className="small muted truncate">{a.description}</div>}
                </td>
                <td>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
                    <Avatar
                      name={personName(a.assignee, lang)}
                      size="sm"
                      src={avatarUrl(a.assignee.id, a.assignee.avatarUpdatedAt)}
                    />
                    <div>
                      <div style={{ fontWeight: 500 }}>{personName(a.assignee, lang)}</div>
                      <div className="small muted">@{a.assignee.username}</div>
                    </div>
                  </div>
                </td>
                <td><DueBadge a={a} /></td>
                <td>
                  <AssignmentStatusPill status={a.status} />
                  {a.completedAt && (
                    <div className="small muted" style={{ marginTop: 4 }}>
                      {t('assignments.admin.doneOn', { when: formatDateTime(a.completedAt, lang) })}
                    </div>
                  )}
                </td>
                <td className="muted small">{formatDateTime(a.createdAt, lang)}</td>
                <td className="actions-cell">
                  {a.status === 'DONE' && (
                    <button
                      className="btn btn-secondary btn-sm"
                      onClick={() => onReopen(a)}
                      disabled={busyId === a.id}
                    >{t('assignments.admin.reopen')}</button>
                  )}
                  <button className="btn btn-secondary btn-sm" onClick={() => openEdit(a)}>{t('common.edit')}</button>
                  <button
                    className="btn btn-danger btn-sm"
                    onClick={() => onDelete(a)}
                    disabled={busyId === a.id}
                  >{t('common.delete')}</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <SidePanel
        open={panelOpen}
        title={isEdit ? t('assignments.admin.editTitle') : t('assignments.admin.createTitle')}
        onClose={() => setPanelOpen(false)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setPanelOpen(false)}>{t('common.cancel')}</button>
            <button className="btn btn-primary" onClick={onSave} disabled={saving}>
              {saving ? <span className="spinner" /> : (isEdit ? t('common.saveChanges') : t('assignments.admin.createBtn'))}
            </button>
          </>
        }
      >
        {formError && <div className="alert alert-error">{formError}</div>}
        {!loading && assignable.length === 0 && (
          <div className="alert alert-warning">{t('assignments.admin.noMembers')}</div>
        )}

        <form onSubmit={onSave}>
          <div className="field">
            <label className="field-label" htmlFor="assignment-assignee">
              {t('assignments.admin.assignee')} <span className="req">*</span>
            </label>
            <select
              id="assignment-assignee"
              className="select"
              value={form.assigneeId}
              onChange={(e) => setForm({ ...form, assigneeId: e.target.value })}
            >
              <option value="">{t('assignments.admin.pickAssignee')}</option>
              {assignable.map((u) => (
                <option key={u.id} value={u.id}>
                  {u.displayName || u.username}
                  {u.role === 'ADMIN' ? ` · ${t('common.teamLeader')}` : ''}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label className="field-label" htmlFor="assignment-title">
              {t('assignments.admin.titleLabel')} <span className="req">*</span>
            </label>
            <input
              id="assignment-title"
              className="input"
              value={form.title}
              maxLength={200}
              onChange={(e) => setForm({ ...form, title: e.target.value })}
              placeholder={t('assignments.admin.titlePlaceholder')}
              autoFocus
            />
          </div>
          <div className="field">
            <label className="field-label" htmlFor="assignment-description">{t('assignments.admin.descriptionLabel')}</label>
            <textarea
              id="assignment-description"
              className="textarea"
              value={form.description}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
              placeholder={t('assignments.admin.descriptionPlaceholder')}
            />
          </div>
          <div className="field">
            <label className="field-label" htmlFor="assignment-due">{t('assignments.admin.dueDate')}</label>
            <input
              id="assignment-due"
              className="input"
              type="date"
              value={form.dueDate}
              onChange={(e) => setForm({ ...form, dueDate: e.target.value })}
              dir="ltr"
            />
          </div>
          <button type="submit" hidden aria-hidden="true" />
        </form>
      </SidePanel>
    </div>
  );
}
