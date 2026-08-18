import { FormEvent, useEffect, useMemo, useState } from 'react';
import { extractError } from '../../api/client';
import { superApi, type SuperAdminRow } from '../../api/super';
import { Modal } from '../../components/Modal';
import { PasswordInput } from '../../components/PasswordInput';
import { IconMembers, IconSearch } from '../../components/Icons';
import { useT } from '../../i18n';

/**
 * Cross-team operators. Same {@code page / card / table} shell as every other admin page —
 * no bespoke hero — so the surface reads as part of the wider Neurix design system rather
 * than a one-off surface.
 */
export function SuperAdminsPage() {
  const { t } = useT();
  const [rows, setRows] = useState<SuperAdminRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [q, setQ] = useState('');
  const [statusFilter, setStatusFilter] = useState<'all' | 'active' | 'inactive'>('all');

  const filtered = useMemo(() => {
    if (!rows) return [];
    const needle = q.trim().toLowerCase();
    return rows.filter((u) => {
      if (statusFilter === 'active' && !u.active) return false;
      if (statusFilter === 'inactive' && u.active) return false;
      if (!needle) return true;
      return (u.username + ' ' + (u.displayName ?? '') + ' ' + (u.email ?? '')).toLowerCase().includes(needle);
    });
  }, [rows, q, statusFilter]);

  useEffect(() => { void load(); }, []);

  async function load() {
    try {
      setRows(await superApi.admins());
      setError(null);
    } catch (e) {
      setError(extractError(e));
    }
  }

  return (
    <div className="page super-page">
      <div className="page-header">
        <div>
          <h1>{t('super.admins') || 'Super admins'}</h1>
          <p className="subtitle">
            {t('super.adminsSubtitle')
              || 'Cross-team operators. Keep this list small — every super admin can access every team.'}
          </p>
        </div>
        <button type="button" className="btn btn-primary" onClick={() => setCreating(true)}>
          <IconMembers size={16} /> {t('super.createAdmin') || 'Create super admin'}
        </button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="super-toolbar">
        <div style={{ position: 'relative', flex: 1, minWidth: 240 }}>
          <span style={{
            position: 'absolute', top: '50%', insetInlineStart: 12,
            transform: 'translateY(-50%)', color: 'var(--text-tertiary)',
            pointerEvents: 'none', display: 'inline-flex',
          }}>
            <IconSearch size={16} />
          </span>
          <input
            type="search"
            className="input"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder={t('super.searchAdmins') || 'Search by username, name or email…'}
            style={{ paddingInlineStart: 40 }}
          />
        </div>
        <div className="filter-pills">
          {(['all', 'active', 'inactive'] as const).map((s) => (
            <button
              key={s}
              type="button"
              className={`filter-pill${statusFilter === s ? ' active' : ''}`}
              onClick={() => setStatusFilter(s)}
            >
              {s === 'all'
                ? (t('super.all') || 'All')
                : s === 'active' ? t('common.active') : t('common.inactive')}
            </button>
          ))}
        </div>
        {rows && (
          <span className="result-count">{filtered.length} / {rows.length}</span>
        )}
      </div>

      {!rows && <div className="muted" style={{ padding: 16 }}>{t('common.loading')}</div>}
      {rows && rows.length === 0 && (
        <div className="empty-state">
          {t('super.noAdmins') || 'Only the seeded super admin exists so far.'}
        </div>
      )}
      {rows && rows.length > 0 && filtered.length === 0 && (
        <div className="empty-state">
          {t('super.noMatch') || 'No matches.'}
        </div>
      )}
      {rows && filtered.length > 0 && (
        <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th style={{ textAlign: 'start' }}>{t('super.username') || 'Username'}</th>
                  <th style={{ textAlign: 'start' }}>{t('super.displayName') || 'Display name'}</th>
                  <th style={{ textAlign: 'start' }}>{t('super.email') || 'Email'}</th>
                  <th style={{ textAlign: 'center' }}>{t('common.status')}</th>
                  <th style={{ textAlign: 'start' }}>{t('super.createdAt') || 'Created'}</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((u) => (
                  <tr key={u.id}>
                    <td><code style={{ fontSize: 12 }}>{u.username}</code></td>
                    <td>{u.displayName || '—'}</td>
                    <td>{u.email || <span className="muted">—</span>}</td>
                    <td style={{ textAlign: 'center' }}>
                      <span style={{
                        display: 'inline-flex', alignItems: 'center', gap: 6,
                        padding: '3px 10px', borderRadius: 999,
                        fontSize: 11, fontWeight: 600,
                        background: u.active ? 'var(--success-soft)' : 'var(--status-completed-soft)',
                        color:      u.active ? 'var(--success-soft-text)' : 'var(--status-completed-text)',
                      }}>
                        <span aria-hidden="true" style={{
                          width: 6, height: 6, borderRadius: 999,
                          background: u.active ? 'var(--success)' : 'var(--status-completed)',
                        }} />
                        {u.active ? t('common.active') : t('common.inactive')}
                      </span>
                    </td>
                    <td>{new Date(u.createdAt).toLocaleDateString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
        </div>
      )}

      {creating && (
        <CreateAdminModal
          onClose={() => setCreating(false)}
          onSaved={async () => { setCreating(false); await load(); }}
        />
      )}
    </div>
  );
}

function CreateAdminModal({ onClose, onSaved }: { onClose: () => void; onSaved: () => Promise<void> }) {
  const { t } = useT();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    setSubmitting(true);
    try {
      await superApi.createAdmin({ username, password, displayName, email });
      await onSaved();
    } catch (e2) {
      setErr(extractError(e2));
      setSubmitting(false);
    }
  }

  return (
    <Modal open onClose={onClose} title={t('super.createAdmin') || 'Create super admin'}>
      <form onSubmit={submit} className="super-modal-form">
        {err && <div className="alert alert-error">{err}</div>}
        <div className="super-modal-hint" style={{
          background: '#fff7ed', borderColor: '#fed7aa', color: '#9a3412',
        }}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 9v4" /><path d="M12 17h.01" /><path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
          </svg>
          <span>
            {t('super.createAdminWarn')
              || 'Super admins can access every team and every action. Keep this list small.'}
          </span>
        </div>
        <div className="field">
          <label className="field-label">{t('super.username') || 'Username'}</label>
          <input className="input" value={username} onChange={(e) => setUsername(e.target.value)} required maxLength={100} />
        </div>
        <div className="field">
          <label className="field-label">{t('auth.password')}</label>
          <PasswordInput value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" />
          <div style={{ fontSize: 12, color: 'var(--text-tertiary)', marginTop: 4 }}>
            {t('super.passwordHint') || 'Minimum 8 characters.'}
          </div>
        </div>
        <div className="field">
          <label className="field-label">{t('super.displayName') || 'Display name'}</label>
          <input className="input" value={displayName} onChange={(e) => setDisplayName(e.target.value)} maxLength={150} />
        </div>
        <div className="field">
          <label className="field-label">{t('super.email') || 'Email'}</label>
          <input className="input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} maxLength={200} />
        </div>
        <div className="super-modal-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={submitting}>
            {t('common.cancel')}
          </button>
          <button type="submit" className="btn btn-primary" disabled={submitting}>
            {submitting ? t('common.loading') : t('common.save')}
          </button>
        </div>
      </form>
    </Modal>
  );
}
