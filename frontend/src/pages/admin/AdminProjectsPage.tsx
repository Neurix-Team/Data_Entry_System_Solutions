import { FormEvent, useEffect, useMemo, useState } from 'react';
import { extractError } from '../../api/client';
import { departmentsApi, projectsApi, usersApi } from '../../api/resources';
import type {
  AdminUser, Department, Project, ProjectStatus,
} from '../../api/types';
import { Avatar } from '../../components/Avatar';
import { IconFilter, IconFolder, IconSearch } from '../../components/Icons';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/toast/ToastContext';
import { useT } from '../../i18n';

interface FormState {
  id?: number;
  name: string;
  subtitle: string;
  departmentIds: number[];
  memberIds: number[];
  startDate: string;
  endDate: string;
  progress: number;
  status: ProjectStatus;
}

const emptyForm: FormState = {
  name: '', subtitle: '', departmentIds: [], memberIds: [],
  startDate: '', endDate: '', progress: 0, status: 'ON_TRACK',
};

function hashHue(name: string): number {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) | 0;
  return (Math.abs(h) % 6) + 1;
}

export function AdminProjectsPage() {
  const { t, lang } = useT();
  const toast = useToast();
  const isAr = lang === 'ar';
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [bulkDeleting, setBulkDeleting] = useState(false);
  const [items, setItems] = useState<Project[]>([]);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [modalOpen, setModalOpen] = useState(false);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const [q, setQ] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [deptFilter, setDeptFilter] = useState('');

  const isEdit = form.id !== undefined;

  async function refresh() {
    setLoading(true);
    try {
      const [p, d, u] = await Promise.all([
        projectsApi.list(),
        departmentsApi.adminList(),
        usersApi.list(),
      ]);
      setItems(p); setDepartments(d); setUsers(u);
    } catch (e) {
      setError(extractError(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { refresh(); }, []);

  const filtered = useMemo(() => {
    const query = q.trim().toLowerCase();
    return items.filter((p) => {
      if (statusFilter && p.status !== statusFilter) return false;
      if (deptFilter && !p.departments.some((d) => String(d.id) === deptFilter)) return false;
      if (query) {
        const deptText = p.departments.map((d) => d.name).join(' ');
        const hay = [p.name, p.subtitle ?? '', deptText].join(' ').toLowerCase();
        if (!hay.includes(query)) return false;
      }
      return true;
    });
  }, [items, q, statusFilter, deptFilter]);

  function openCreate() {
    setForm({ ...emptyForm });
    setFormError(null); setModalOpen(true);
  }

  function openEdit(p: Project) {
    setForm({
      id: p.id, name: p.name, subtitle: p.subtitle ?? '',
      departmentIds: p.departments.map((d) => d.id),
      memberIds: p.members.map((m) => m.id),
      startDate: p.startDate ?? '', endDate: p.endDate ?? '',
      progress: p.progress, status: p.status,
    });
    setFormError(null); setModalOpen(true);
  }

  async function onSave(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    if (!form.name.trim()) { setFormError(t('projects.nameRequired')); return; }
    if (form.departmentIds.length === 0) {
      setFormError(lang === 'ar' ? 'اختار على الأقل قسم واحد للمشروع.' : 'Pick at least one department for this project.');
      return;
    }

    setSaving(true);
    try {
      const payload = {
        name: form.name.trim(),
        subtitle: form.subtitle.trim() || undefined,
        departmentIds: form.departmentIds,
        memberIds: form.memberIds,
        startDate: form.startDate || null,
        endDate: form.endDate || null,
        progress: form.progress,
        status: form.status,
      };
      if (isEdit) await projectsApi.update(form.id!, payload);
      else await projectsApi.create(payload);
      setModalOpen(false);
      refresh();
    } catch (err) {
      setFormError(extractError(err));
    } finally {
      setSaving(false);
    }
  }

  function toggleDept(id: number) {
    setForm((f) => f.departmentIds.includes(id)
      ? { ...f, departmentIds: f.departmentIds.filter((x) => x !== id) }
      : { ...f, departmentIds: [...f.departmentIds, id] });
  }

  async function onDelete(p: Project) {
    if (!confirm(t('common.confirmDelete', { name: p.name }))) return;
    try {
      await projectsApi.remove(p.id);
      toast.success(isAr ? 'تم حذف المشروع' : 'Project deleted');
      refresh();
    } catch (e) {
      toast.error(extractError(e));
    }
  }

  function toggleProjSelected(id: number) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  async function onBulkDeleteProjects() {
    const ids = Array.from(selected);
    if (ids.length === 0) return;
    if (!confirm(isAr ? `تأكيد حذف ${ids.length} مشروع؟` : `Delete ${ids.length} project${ids.length === 1 ? '' : 's'}?`)) return;
    setBulkDeleting(true);
    let ok = 0; let fail = 0;
    for (const id of ids) {
      try { await projectsApi.remove(id); ok++; } catch { fail++; }
    }
    setBulkDeleting(false);
    if (fail === 0) toast.success(isAr ? `تم حذف ${ok} مشروع` : `Deleted ${ok}`);
    else toast.warning(isAr ? `تم حذف ${ok}، فشل ${fail}` : `Deleted ${ok}, ${fail} failed`);
    setSelected(new Set());
    refresh();
  }

  function toggleMember(id: number) {
    setForm((f) => ({
      ...f,
      memberIds: f.memberIds.includes(id)
        ? f.memberIds.filter((x) => x !== id)
        : [...f.memberIds, id],
    }));
  }

  const fmtDate = (d?: string | null) => d
    ? new Date(d).toLocaleDateString(lang === 'ar' ? 'ar-EG' : undefined,
        { month: 'short', day: 'numeric' })
    : null;

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('projects.title')}</h1>
          <p className="subtitle">{t('projects.subtitle')}</p>
        </div>
        <button className="btn btn-primary" onClick={openCreate}>
          <IconFolder size={16} /> {t('projects.newBtn')}
        </button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="toolbar">
        <div style={{ position: 'relative', flex: '1 1 240px', minWidth: 220 }}>
          <span style={{
            position: 'absolute', top: '50%', insetInlineStart: 12, transform: 'translateY(-50%)',
            color: 'var(--text-tertiary)', pointerEvents: 'none',
          }}>
            <IconSearch size={16} />
          </span>
          <input
            className="input"
            type="search"
            placeholder={t('projects.searchPlaceholder')}
            value={q}
            onChange={(e) => setQ(e.target.value)}
            style={{ paddingInlineStart: 38 }}
          />
        </div>
        <select
          className="select"
          style={{ width: 'auto', minWidth: 160 }}
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value)}
        >
          <option value="">{t('projects.filterStatus')}</option>
          <option value="ON_TRACK">{t('status_project.ON_TRACK')}</option>
          <option value="DELAYED">{t('status_project.DELAYED')}</option>
          <option value="COMPLETED">{t('status_project.COMPLETED')}</option>
        </select>
        <select
          className="select"
          style={{ width: 'auto', minWidth: 180 }}
          value={deptFilter}
          onChange={(e) => setDeptFilter(e.target.value)}
        >
          <option value="">{t('projects.filterDepartment')}</option>
          {departments.map((d) => (
            <option key={d.id} value={d.id}>{d.name}</option>
          ))}
        </select>
        <div style={{ marginInlineStart: 'auto', display: 'flex', gap: 6 }}>
          <button className="btn btn-secondary btn-sm" title="Filter">
            <IconFilter size={16} />
          </button>
        </div>
      </div>

      {selected.size > 0 && (
        <div className="bulk-action-bar">
          <label className="bulk-action-select-all">
            {isAr ? `${selected.size} مختار` : `${selected.size} selected`}
          </label>
          <button className="btn btn-danger btn-sm" onClick={onBulkDeleteProjects} disabled={bulkDeleting}>
            {bulkDeleting ? (isAr ? 'جارٍ الحذف…' : 'Deleting…') : (isAr ? `حذف المختار (${selected.size})` : `Delete selected (${selected.size})`)}
          </button>
          <button className="btn btn-ghost btn-sm" onClick={() => setSelected(new Set())}>
            {isAr ? 'إلغاء التحديد' : 'Clear'}
          </button>
        </div>
      )}

      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr>
              <th className="row-check">
                <input
                  type="checkbox"
                  checked={selected.size > 0 && selected.size === filtered.length}
                  onChange={() => {
                    if (selected.size === filtered.length) setSelected(new Set());
                    else setSelected(new Set(filtered.map(p => p.id)));
                  }}
                  aria-label={isAr ? 'تحديد الكل' : 'Select all'}
                />
              </th>
              <th>{t('projects.colName')}</th>
              <th>{t('projects.colDepartment')}</th>
              <th>{t('projects.colMembers')}</th>
              <th>{t('projects.colTimeline')}</th>
              <th>{t('projects.colProgress')}</th>
              <th>{t('common.status')}</th>
              <th style={{ textAlign: 'end' }}>{t('common.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={8} className="empty-state">{t('common.loading')}</td></tr>
            ) : filtered.length === 0 ? (
              <tr><td colSpan={8} className="empty-state">{t('projects.empty')}</td></tr>
            ) : filtered.map((p) => {
              const hue = hashHue(p.name);
              const initial = p.name.trim().charAt(0).toUpperCase();
              const overdue = p.status !== 'COMPLETED' && p.daysLeft != null && p.daysLeft < 0;
              const barTone: 'blue' | 'amber' | 'green' =
                p.status === 'COMPLETED' ? 'green' :
                overdue ? 'amber' : 'blue';
              return (
                <tr key={p.id}>
                  <td className="row-check">
                    <input
                      type="checkbox"
                      checked={selected.has(p.id)}
                      onChange={() => toggleProjSelected(p.id)}
                      aria-label={isAr ? 'تحديد' : 'Select'}
                    />
                  </td>
                  <td>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.85rem' }}>
                      <span className="project-avatar" data-hue={hue}>{initial}</span>
                      <div>
                        <div style={{ fontWeight: 700, color: 'var(--text-primary)' }}>{p.name}</div>
                        {p.subtitle && <div className="small muted">{p.subtitle}</div>}
                      </div>
                    </div>
                  </td>
                  <td>
                    <div className="dept-chip-row">
                      {p.departments.length === 0
                        ? <span className="muted small">—</span>
                        : p.departments.map((d) => (
                            <span key={d.id} className="dept-chip">{d.name}</span>
                          ))}
                    </div>
                  </td>
                  <td>
                    <MemberStack members={p.members} />
                  </td>
                  <td className="timeline-cell">
                    {p.startDate || p.endDate ? (
                      <>
                        <div className="dates small">
                          {fmtDate(p.startDate) ?? '—'} - {fmtDate(p.endDate) ?? '—'}
                        </div>
                        {p.status === 'COMPLETED' ? (
                          <div className="meta completed">{t('projects.completed')}</div>
                        ) : overdue ? (
                          <div className="meta overdue">
                            {t('projects.overdueBy', { days: Math.abs(p.daysLeft!) })}
                          </div>
                        ) : p.daysLeft != null ? (
                          <div className="meta">{t('projects.daysLeft', { days: p.daysLeft })}</div>
                        ) : null}
                      </>
                    ) : <span className="muted">—</span>}
                  </td>
                  <td>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem', minWidth: 130 }}>
                      <div className="progress-bar" style={{ flex: 1, maxWidth: 120 }}>
                        <div className="progress-bar-fill" data-tone={barTone} style={{ width: `${p.progress}%` }} />
                      </div>
                      <span style={{ fontSize: 'var(--fs-xs)', fontWeight: 600, color: 'var(--text-secondary)', minWidth: 32 }}>
                        {p.progress}%
                      </span>
                    </div>
                  </td>
                  <td>
                    <ProjectStatusPill status={p.status} overdue={overdue} />
                  </td>
                  <td className="actions-cell">
                    <button className="btn btn-secondary btn-sm" onClick={() => openEdit(p)}>{t('common.edit')}</button>
                    <button className="btn btn-danger btn-sm" onClick={() => onDelete(p)}>{t('common.delete')}</button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <Modal
        open={modalOpen}
        title={isEdit ? t('projects.editTitle') : t('projects.createTitle')}
        onClose={() => setModalOpen(false)}
        footer={
          <>
            <button className="btn btn-secondary" onClick={() => setModalOpen(false)}>{t('common.cancel')}</button>
            <button className="btn btn-primary" onClick={onSave} disabled={saving}>
              {saving ? <span className="spinner" /> : t('common.save')}
            </button>
          </>
        }
      >
        {formError && <div className="alert alert-error">{formError}</div>}
        <form onSubmit={onSave}>
          <div className="field">
            <label className="field-label">{t('projects.nameLabel')} <span className="req">*</span></label>
            <input
              className="input"
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder={t('projects.namePlaceholder')}
              autoFocus
            />
          </div>
          <div className="field">
            <label className="field-label">{t('projects.subtitleLabel')}</label>
            <input
              className="input"
              value={form.subtitle}
              onChange={(e) => setForm({ ...form, subtitle: e.target.value })}
              placeholder={t('projects.subtitlePlaceholder')}
            />
          </div>
          <div className="field">
            <label className="field-label">
              {lang === 'ar' ? 'الأقسام داخل المشروع' : 'Departments in this project'}{' '}
              <span className="req">*</span>
            </label>
            <div className="chip-picker">
              {departments.length === 0 && (
                <span className="muted small">
                  {lang === 'ar' ? 'لا توجد أقسام. أضف قسم أولاً.' : 'No departments yet. Create one first.'}
                </span>
              )}
              {departments.map((d) => {
                const on = form.departmentIds.includes(d.id);
                return (
                  <button
                    type="button"
                    key={d.id}
                    className={`chip-toggle${on ? ' is-on' : ''}`}
                    onClick={() => toggleDept(d.id)}
                    aria-pressed={on}
                  >
                    {on && <span className="chip-toggle-check" aria-hidden="true">✓</span>}
                    {d.name}
                  </button>
                );
              })}
            </div>
            {form.departmentIds.length > 0 && (
              <span className="small muted">
                {lang === 'ar'
                  ? `${form.departmentIds.length} قسم مختار`
                  : `${form.departmentIds.length} department${form.departmentIds.length === 1 ? '' : 's'} selected`}
              </span>
            )}
          </div>
          <div className="row" style={{ gap: '1rem' }}>
            <div className="field" style={{ flex: 1 }}>
              <label className="field-label">{t('projects.startDate')}</label>
              <input
                type="date"
                className="input"
                value={form.startDate}
                onChange={(e) => setForm({ ...form, startDate: e.target.value })}
              />
            </div>
            <div className="field" style={{ flex: 1 }}>
              <label className="field-label">{t('projects.endDate')}</label>
              <input
                type="date"
                className="input"
                value={form.endDate}
                onChange={(e) => setForm({ ...form, endDate: e.target.value })}
              />
            </div>
          </div>
          <div className="field">
            <label className="field-label">{t('projects.progress')}</label>
            <input
              type="range"
              min={0} max={100}
              value={form.progress}
              onChange={(e) => setForm({ ...form, progress: parseInt(e.target.value) || 0 })}
              style={{ width: '100%' }}
            />
            <div className="small muted">{form.progress}%</div>
          </div>
          <div className="field">
            <label className="field-label">{t('common.status')}</label>
            <select
              className="select"
              value={form.status}
              onChange={(e) => setForm({ ...form, status: e.target.value as ProjectStatus })}
            >
              <option value="ON_TRACK">{t('status_project.ON_TRACK')}</option>
              <option value="DELAYED">{t('status_project.DELAYED')}</option>
              <option value="COMPLETED">{t('status_project.COMPLETED')}</option>
            </select>
          </div>
          <div className="field">
            <label className="field-label">{t('projects.membersLabel')}</label>
            <div className="multi-select">
              {users.length === 0 ? (
                <span className="muted small">No team members available.</span>
              ) : users.map((u) => (
                <label key={u.id} className={form.memberIds.includes(u.id) ? 'selected' : ''}>
                  <input
                    type="checkbox"
                    checked={form.memberIds.includes(u.id)}
                    onChange={() => toggleMember(u.id)}
                  />
                  {u.displayName || u.username}
                </label>
              ))}
            </div>
            <span className="small muted" style={{ textTransform: 'none', letterSpacing: 0 }}>
              {t('projects.membersHint')}
            </span>
          </div>
        </form>
      </Modal>
    </div>
  );
}

function MemberStack({ members }: { members: Project['members'] }) {
  if (members.length === 0) return <span className="muted small">—</span>;
  const shown = members.slice(0, 3);
  const rest = members.length - shown.length;
  return (
    <div className="member-stack">
      {shown.map((m) => (
        <Avatar key={m.id} name={m.displayName || m.username} size="sm" />
      ))}
      {rest > 0 && <span className="member-stack-count">+{rest}</span>}
    </div>
  );
}

function ProjectStatusPill({ status, overdue }: { status: ProjectStatus; overdue: boolean }) {
  const { t } = useT();
  const effective: ProjectStatus = overdue && status !== 'COMPLETED' ? 'DELAYED' : status;
  const cls =
    effective === 'ON_TRACK' ? 'status-on-track' :
    effective === 'COMPLETED' ? 'status-completed' :
    'status-delayed';
  return (
    <span className={`status-pill ${cls}`}>
      {t(`status_project.${effective}`)}
    </span>
  );
}
