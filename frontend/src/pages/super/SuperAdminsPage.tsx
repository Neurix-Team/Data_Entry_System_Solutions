import { FormEvent, useEffect, useState } from 'react';
import { extractError } from '../../api/client';
import { superApi, type SuperAdminRow } from '../../api/super';
import { Modal } from '../../components/Modal';
import { PasswordInput } from '../../components/PasswordInput';
import { useT } from '../../i18n';

/** Manage other SUPER_ADMIN accounts. Kept minimal — this is a rarely-used surface. */
export function SuperAdminsPage() {
  const { t } = useT();
  const [rows, setRows] = useState<SuperAdminRow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);

  useEffect(() => { void load(); }, []);

  async function load() {
    setLoading(true);
    try {
      setRows(await superApi.admins());
      setError(null);
    } catch (e) {
      setError(extractError(e));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={{ maxWidth: 900, margin: '0 auto', padding: '8px 4px 32px' }}>
      <header style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20,
      }}>
        <div>
          <h1 style={{ margin: 0, fontSize: 24, fontWeight: 700 }}>
            {t('super.admins') || 'Super admins'}
          </h1>
          <p style={{ margin: '4px 0 0', color: 'var(--text-muted, #6b7280)', fontSize: 14 }}>
            {t('super.adminsSubtitle')
              || 'Cross-team operators. Keep this list small — every super admin can access every team.'}
          </p>
        </div>
        <button className="btn btn-primary" onClick={() => setCreating(true)}>
          + {t('super.createAdmin') || 'Create super admin'}
        </button>
      </header>

      {error && <div className="alert alert-error" role="alert">{error}</div>}
      {loading && !rows.length && <div className="muted">{t('common.loading')}</div>}

      <div className="card" style={{ padding: 0, borderRadius: 14, overflow: 'hidden' }}>
        <table className="table" style={{ width: '100%' }}>
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
            {rows.map((u) => (
              <tr key={u.id}>
                <td><code style={{ fontSize: 12 }}>{u.username}</code></td>
                <td>{u.displayName || '—'}</td>
                <td>{u.email || '—'}</td>
                <td style={{ textAlign: 'center' }}>
                  <span style={{
                    fontSize: 11, padding: '3px 10px', borderRadius: 999,
                    background: u.active ? 'rgba(16,185,129,0.12)' : 'rgba(107,114,128,0.14)',
                    color: u.active ? '#059669' : '#4b5563', fontWeight: 600,
                  }}>
                    {u.active ? t('common.active') : t('common.inactive')}
                  </span>
                </td>
                <td>{new Date(u.createdAt).toLocaleDateString()}</td>
              </tr>
            ))}
            {rows.length === 0 && !loading && (
              <tr>
                <td colSpan={5} style={{ textAlign: 'center', padding: 24 }} className="muted">
                  {t('super.noAdmins') || 'Only the seeded super admin exists so far.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

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
      <form onSubmit={submit} style={{ display: 'grid', gap: 14 }}>
        {err && <div className="alert alert-error">{err}</div>}
        <div className="field">
          <label className="field-label">{t('super.username') || 'Username'}</label>
          <input className="input" value={username} onChange={(e) => setUsername(e.target.value)} required maxLength={100} />
        </div>
        <div className="field">
          <label className="field-label">{t('auth.password')}</label>
          <PasswordInput value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" />
          <div style={{ fontSize: 12, color: 'var(--text-muted, #6b7280)', marginTop: 4 }}>
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
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 4 }}>
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
