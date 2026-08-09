import { FormEvent, useEffect, useState } from 'react';
import { extractError } from '../../api/client';
import { ticketsApi, usersApi } from '../../api/resources';
import type { AdminStats, AdminUser, Role, Ticket } from '../../api/types';
import { Avatar } from '../../components/Avatar';
import { IconCamera, IconChart, IconFolder, IconMembers } from '../../components/Icons';
import { SidePanel } from '../../components/SidePanel';
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
      } else {
        await usersApi.create({
          username: form.username.trim(),
          password: form.password,
          displayName: form.displayName || undefined,
          email: form.email || undefined,
          phone: form.phone || undefined,
          role: form.role,
        });
      }
      setPanelOpen(false);
      refresh();
    } catch (err) {
      setFormError(extractError(err));
    } finally {
      setSaving(false);
    }
  }

  async function onDelete(u: AdminUser) {
    if (!confirm(t('common.confirmDelete', { name: u.username }))) return;
    try { await usersApi.remove(u.id); refresh(); }
    catch (e) { alert(extractError(e)); }
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

      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr>
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
              <tr><td colSpan={7} className="empty-state">{t('common.loading')}</td></tr>
            ) : users.length === 0 ? (
              <tr><td colSpan={7} className="empty-state">{t('admin.users.empty')}</td></tr>
            ) : users.map((u) => (
              <tr key={u.id}>
                <td>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
                    <Avatar name={u.displayName || u.username} />
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
            <input
              type="password"
              className="input"
              value={form.password}
              onChange={(e) => setForm({ ...form, password: e.target.value })}
              placeholder={isEdit ? '••••••' : t('admin.users.passwordHint')}
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
