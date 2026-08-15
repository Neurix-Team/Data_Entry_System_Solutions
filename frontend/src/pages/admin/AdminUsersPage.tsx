import { FormEvent, useEffect, useState } from 'react';
import { extractError } from '../../api/client';
import { ticketsApi, usersApi } from '../../api/resources';
import type { AdminStats, AdminUser, Role, Ticket } from '../../api/types';
import { Avatar } from '../../components/Avatar';
import { IconCamera, IconChart, IconFolder, IconMembers } from '../../components/Icons';
import { PasswordInput } from '../../components/PasswordInput';
import { SidePanel } from '../../components/SidePanel';
import { useToast } from '../../components/toast/ToastContext';
import { useConfirm } from '../../components/ConfirmDialog';
import { avatarUrl } from '../../api/profile';
import { useAuth } from '../../context/AuthContext';
import { useT } from '../../i18n';

interface FormState {
  id?: number;
  username: string;
  displayName: string;
  email: string;
  phone: string;
  password: string;
  role: Role;
  active: boolean;
}

const empty: FormState = {
  username: '', displayName: '', email: '', phone: '',
  password: '', role: 'USER', active: true,
};

export function AdminUsersPage() {
  const { user: current } = useAuth();
  const { t, lang } = useT();
  const toast = useToast();
  const confirm = useConfirm();
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [panelOpen, setPanelOpen] = useState(false);
  const [form, setForm] = useState<FormState>(empty);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const isEdit = form.id !== undefined;

  async function refresh() {
    setLoading(true);
    try {
      const [u, tk, s] = await Promise.all([
        usersApi.list(),
        ticketsApi.listAll(0, 500),
        ticketsApi.stats(),
      ]);
      setUsers(u); setTickets(tk.items); setStats(s);
    } catch (e) {
      setError(extractError(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { refresh(); }, []);

  function openCreate() {
    setForm(empty); setFormError(null); setPanelOpen(true);
  }
  function openEdit(u: AdminUser) {
    setForm({
      id: u.id, username: u.username,
      displayName: u.displayName ?? '', email: u.email ?? '', phone: u.phone ?? '',
      password: '', role: u.role, active: u.active,
    });
    setFormError(null); setPanelOpen(true);
  }

  async function onSave(e: FormEvent) {
    e.preventDefault();
    setFormError(null);
    if (!isEdit) {
      if (!form.username.trim() || form.username.trim().length < 3) {
        setFormError(t('admin.users.usernameTooShort')); return;
      }
      if (form.password.length < 6) {
        setFormError(t('admin.users.passwordTooShort')); return;
      }
    } else if (form.password && form.password.length < 6) {
      setFormError(t('admin.users.newPasswordTooShort')); return;
    }

    setSaving(true);
    try {
      if (isEdit) {
        await usersApi.update(form.id!, {
          displayName: form.displayName || undefined,
          email: form.email || undefined,
          phone: form.phone || undefined,
          password: form.password || undefined,
          active: form.active,
        });
        toast.success(t('admin.users.updatedToast') || 'User updated');
      } else {
        await usersApi.create({
          username: form.username.trim(),
          password: form.password,
          displayName: form.displayName || undefined,
          email: form.email || undefined,
          phone: form.phone || undefined,
          role: form.role,
        });
        toast.success(t('admin.users.createdToast') || 'User created');
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

  const isAr = lang === 'ar';
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [bulkDeleting, setBulkDeleting] = useState(false);

  async function onDelete(u: AdminUser) {
    const ok = await confirm({
      message: t('common.confirmDelete', { name: u.username }),
      destructive: true,
    });
    if (!ok) return;
    try {
      await usersApi.remove(u.id);
      toast.success(isAr ? 'تم حذف المستخدم' : 'User deleted');
      refresh();
    } catch (e) {
      toast.error(extractError(e));
    }
  }

  function toggleUserSelected(id: number) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  async function onBulkDeleteUsers() {
    const ids = Array.from(selected).filter((id) => id !== current?.id);
    if (ids.length === 0) return;
    const proceed = await confirm({
      message: isAr ? `تأكيد حذف ${ids.length} مستخدم؟` : `Delete ${ids.length} user${ids.length === 1 ? '' : 's'}?`,
      destructive: true,
    });
    if (!proceed) return;
    setBulkDeleting(true);
    let ok = 0; let fail = 0;
    const results = await Promise.allSettled(ids.map((id) => usersApi.remove(id)));
    for (const r of results) { if (r.status === 'fulfilled') ok++; else fail++; }
    setBulkDeleting(false);
    if (fail === 0) toast.success(isAr ? `تم حذف ${ok} مستخدم` : `Deleted ${ok} user${ok === 1 ? '' : 's'}`);
    else toast.warning(isAr ? `تم حذف ${ok}، فشل ${fail}` : `Deleted ${ok}, ${fail} failed`);
    setSelected(new Set());
    refresh();
  }

  const tasksByUser = (userId: number) => tickets.filter(x => x.submittedById === userId).length;
  const activeAgents = users.filter(u => u.active && u.role === 'USER').length;

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('admin.users.title')}</h1>
          <p className="subtitle">{t('admin.users.subtitle')}</p>
        </div>
        <button className="btn btn-primary" onClick={openCreate}>
          <IconMembers size={16} /> {t('admin.users.newBtn')}
        </button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(3, minmax(0, 1fr))' }}>
        <div className="stat-card">
          <div className="stat-card-header">
            <span className="stat-card-label">{t('admin.users.totalHeadcount')}</span>
            <span className="stat-card-icon" style={{ background: 'var(--brand-soft)', color: 'var(--brand-soft-text)' }}><IconMembers size={16} /></span>
          </div>
          <div className="stat-card-value">{users.length}</div>
        </div>
        <div className="stat-card">
          <div className="stat-card-header">
            <span className="stat-card-label">{t('admin.users.activeAgents')}</span>
            <span className="stat-card-icon" style={{ background: 'var(--status-progress-soft)', color: 'var(--status-progress-text)' }}><IconFolder size={16} /></span>
          </div>
          <div className="stat-card-value">{activeAgents}</div>
        </div>
        <div className="stat-card">
          <div className="stat-card-header">
            <span className="stat-card-label">{t('admin.users.tasksThisWeek')}</span>
            <span className="stat-card-icon" style={{ background: 'var(--success-soft)', color: 'var(--success-soft-text)' }}><IconChart size={16} /></span>
          </div>
          <div className="stat-card-value">{stats?.completedToday ?? 0}</div>
        </div>
      </div>

      {selected.size > 0 && (
        <div className="bulk-action-bar">
          <label className="bulk-action-select-all">
            {isAr ? `${selected.size} مختار` : `${selected.size} selected`}
          </label>
          <button className="btn btn-danger btn-sm" onClick={onBulkDeleteUsers} disabled={bulkDeleting}>
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
                  checked={selected.size > 0 && selected.size === users.filter(u => u.id !== current?.id).length}
                  onChange={() => {
                    const others = users.filter(u => u.id !== current?.id).map(u => u.id);
                    if (selected.size === others.length) setSelected(new Set());
                    else setSelected(new Set(others));
                  }}
                  aria-label={isAr ? 'تحديد الكل' : 'Select all'}
                />
              </th>
              <th>{t('admin.users.colDisplayName')}</th>
              <th>{t('admin.users.colEmail')}</th>
              <th>{t('admin.users.colRole')}</th>
              <th>Tasks</th>
              <th>{t('common.status')}</th>
              <th>{t('admin.users.colCreated')}</th>
              <th style={{ textAlign: 'end' }}>{t('common.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={8} className="empty-state">{t('common.loading')}</td></tr>
            ) : users.length === 0 ? (
              <tr><td colSpan={8} className="empty-state">{t('admin.users.empty')}</td></tr>
            ) : users.map((u) => (
              <tr key={u.id}>
                <td className="row-check">
                  {u.id !== current?.id && (
                    <input
                      type="checkbox"
                      checked={selected.has(u.id)}
                      onChange={() => toggleUserSelected(u.id)}
                      aria-label={isAr ? 'تحديد' : 'Select'}
                    />
                  )}
                </td>
                <td>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
                    <Avatar name={u.displayName || u.username} src={avatarUrl(u.id, u.avatarUpdatedAt)} />
                    <div>
                      <div style={{ fontWeight: 600 }}>
                        {u.displayName || u.username}
                        {current?.id === u.id && <span className="muted small"> {t('common.you')}</span>}
                      </div>
                      <div className="small muted">@{u.username}</div>
                    </div>
                  </div>
                </td>
                <td className="muted">{u.email || <span className="muted">—</span>}</td>
                <td>
                  <span className={`role-badge ${u.role === 'USER' ? 'role-user' : ''}`}>
                    {u.role === 'ADMIN' ? t('admin.users.roleAdmin') : t('common.dataEntryAgent')}
                  </span>
                </td>
                <td style={{ fontWeight: 600 }}>{tasksByUser(u.id)}</td>
                <td>
                  <span className={`badge ${u.active ? 'badge-active' : 'badge-inactive'}`}>
                    {u.active ? t('common.active') : t('common.disabled')}
                  </span>
                </td>
                <td className="muted small">
                  {new Date(u.createdAt).toLocaleDateString(lang === 'ar' ? 'ar-EG' : undefined)}
                </td>
                <td className="actions-cell">
                  <button className="btn btn-secondary btn-sm" onClick={() => openEdit(u)}>{t('common.edit')}</button>
                  <button
                    className="btn btn-danger btn-sm"
                    onClick={() => onDelete(u)}
                    disabled={current?.id === u.id}
                    title={current?.id === u.id ? t('admin.users.cannotDeleteSelf') : ''}
                  >{t('common.delete')}</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <SidePanel
        open={panelOpen}
        title={isEdit ? t('admin.users.editTitle') : t('admin.users.createTitle')}
        onClose={() => setPanelOpen(false)}
        footer={
          <>
            <button className="btn btn-ghost" onClick={() => setPanelOpen(false)}>{t('common.cancel')}</button>
            <button className="btn btn-primary" onClick={onSave} disabled={saving}>
              {saving ? <span className="spinner" /> : (isEdit ? t('common.saveChanges') : t('admin.users.createBtn'))}
            </button>
          </>
        }
      >
        {formError && <div className="alert alert-error">{formError}</div>}

        <div style={{ textAlign: 'center', marginBottom: '1.75rem' }}>
          <div style={{
            width: 88, height: 88, borderRadius: '50%',
            background: 'var(--bg-muted)', border: '2px dashed var(--border-strong)',
            display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
            color: 'var(--text-tertiary)',
          }}>
            <IconCamera size={32} />
          </div>
          <div className="small muted mt-1">Upload photo (soon)</div>
        </div>

        <form onSubmit={onSave}>
          <div className="field">
            <label className="field-label">{t('admin.users.colDisplayName')} <span className="req">*</span></label>
            <input
              className="input"
              value={form.displayName}
              onChange={(e) => setForm({ ...form, displayName: e.target.value })}
              placeholder="e.g. Jane Doe"
            />
          </div>
          {!isEdit && (
            <div className="field">
              <label className="field-label">{t('admin.users.colUsername')} <span className="req">*</span></label>
              <input
                className="input"
                value={form.username}
                onChange={(e) => setForm({ ...form, username: e.target.value })}
                placeholder="jane.doe"
              />
            </div>
          )}
          <div className="field">
            <label className="field-label">{t('admin.users.colEmail')}</label>
            <input
              className="input"
              type="email"
              value={form.email}
              onChange={(e) => setForm({ ...form, email: e.target.value })}
              placeholder="jane.doe@company.com"
              dir="ltr"
            />
          </div>
          <div className="field">
            <label className="field-label">{t('admin.users.colPhone')}</label>
            <input
              className="input"
              value={form.phone}
              onChange={(e) => setForm({ ...form, phone: e.target.value })}
              placeholder="+20 100 000 0000"
              dir="ltr"
            />
          </div>
          <div className="field">
            <label className="field-label">
              {isEdit ? t('admin.users.newPasswordLabel') : <>{t('auth.password')} <span className="req">*</span></>}
            </label>
            <PasswordInput
              value={form.password}
              onChange={(e) => setForm({ ...form, password: e.target.value })}
              placeholder={isEdit ? '••••••' : t('admin.users.passwordHint')}
              showLabel={lang === 'ar' ? 'إظهار كلمة المرور' : 'Show password'}
              hideLabel={lang === 'ar' ? 'إخفاء كلمة المرور' : 'Hide password'}
            />
          </div>
          {!isEdit && (
            <div className="field">
              <label className="field-label">{t('admin.users.colRole')}</label>
              <select
                className="select"
                value={form.role}
                onChange={(e) => setForm({ ...form, role: e.target.value as Role })}
              >
                <option value="USER">{t('admin.users.roleUser')}</option>
                <option value="ADMIN">{t('admin.users.roleAdmin')}</option>
              </select>
            </div>
          )}
          <div style={{
            padding: '0.85rem 1rem', background: 'var(--bg-sunken)',
            borderRadius: 'var(--radius)', display: 'flex',
            alignItems: 'center', justifyContent: 'space-between', gap: '1rem',
            marginTop: '0.5rem',
          }}>
            <div>
              <div style={{ fontWeight: 600 }}>{t('admin.users.accountActive')}</div>
              <div className="small muted">{t('admin.users.accountActiveDesc')}</div>
            </div>
            <label className="switch">
              <input
                type="checkbox"
                checked={form.active}
                onChange={(e) => setForm({ ...form, active: e.target.checked })}
              />
              <span className="slider" />
            </label>
          </div>
        </form>
      </SidePanel>
    </div>
  );
}
