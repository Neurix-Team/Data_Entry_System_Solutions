import { FormEvent, useEffect, useState } from 'react';
import { extractError } from '../../api/client';
import {
  superApi, type CreateTeamAdminRequest, type CreateTeamRequest,
  type TeamAdminRow, type TeamSummary, type UpdateTeamRequest,
} from '../../api/super';
import { Modal } from '../../components/Modal';
import { PasswordInput } from '../../components/PasswordInput';
import { useConfirm } from '../../components/ConfirmDialog';
import { useT } from '../../i18n';

/** Full CRUD for teams. Only reachable by SUPER_ADMIN — the URL is protected by the router. */
export function SuperTeamsPage() {
  const { t } = useT();
  const [teams, setTeams] = useState<TeamSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<TeamSummary | null>(null);
  const [addingAdminTo, setAddingAdminTo] = useState<TeamSummary | null>(null);
  const [viewingMembersOf, setViewingMembersOf] = useState<TeamSummary | null>(null);
  const confirm = useConfirm();

  useEffect(() => { void load(); }, []);

  async function load() {
    setLoading(true);
    try {
      setTeams(await superApi.teams());
      setError(null);
    } catch (e) {
      setError(extractError(e));
    } finally {
      setLoading(false);
    }
  }

  async function askDelete(team: TeamSummary) {
    const ok = await confirm({
      title: t('super.deleteTeam') || 'Delete team',
      message: `${t('super.deleteWarn') || 'Deleting removes the team permanently.'} "${team.name}"`,
      confirmLabel: t('common.delete'),
      destructive: true,
    });
    if (!ok) return;
    try {
      await superApi.deleteTeam(team.id);
      await load();
    } catch (e) {
      setError(extractError(e));
    }
  }

  return (
    <div style={{ maxWidth: 1100, margin: '0 auto', padding: '8px 4px 32px' }}>
      <header style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20,
      }}>
        <div>
          <h1 style={{ margin: 0, fontSize: 24, fontWeight: 700 }}>
            {t('super.teams') || 'Teams'}
          </h1>
          <p style={{ margin: '4px 0 0', color: 'var(--text-muted, #6b7280)', fontSize: 14 }}>
            {t('super.teamsSubtitle')
              || 'Each team is a fully isolated workspace with its own users, projects, and tickets.'}
          </p>
        </div>
        <button className="btn btn-primary" onClick={() => setCreating(true)}>
          + {t('super.createTeam') || 'Create team'}
        </button>
      </header>

      {error && <div className="alert alert-error" role="alert">{error}</div>}
      {loading && !teams.length && <div className="muted">{t('common.loading')}</div>}

      <div className="card" style={{ padding: 0, borderRadius: 14, overflow: 'hidden' }}>
        <table className="table" style={{ width: '100%' }}>
          <thead>
            <tr>
              <th style={{ textAlign: 'start' }}>{t('super.name') || 'Team'}</th>
              <th style={{ textAlign: 'start' }}>{t('super.slug') || 'Slug'}</th>
              <th style={{ textAlign: 'end' }}>{t('super.users') || 'Users'}</th>
              <th style={{ textAlign: 'end' }}>{t('super.projects') || 'Projects'}</th>
              <th style={{ textAlign: 'end' }}>{t('super.tickets') || 'Tickets'}</th>
              <th style={{ textAlign: 'center' }}>{t('common.status')}</th>
              <th style={{ textAlign: 'end' }}>{t('common.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {teams.map((tm) => (
              <tr key={tm.id}>
                <td>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                    <span
                      aria-hidden="true"
                      style={{
                        width: 28, height: 28, borderRadius: 8,
                        background: tm.color || '#6366f1', color: '#fff',
                        display: 'grid', placeItems: 'center', fontSize: 13, fontWeight: 700,
                      }}
                    >
                      {tm.name.charAt(0).toUpperCase()}
                    </span>
                    <div>
                      <div style={{ fontWeight: 600 }}>{tm.name}</div>
                      {tm.description && (
                        <div style={{ fontSize: 12, color: 'var(--text-muted, #6b7280)' }}>
                          {tm.description}
                        </div>
                      )}
                    </div>
                  </div>
                </td>
                <td><code style={{ fontSize: 12 }}>{tm.slug}</code></td>
                <td style={{ textAlign: 'end' }}>{tm.userCount}</td>
                <td style={{ textAlign: 'end' }}>{tm.projectCount}</td>
                <td style={{ textAlign: 'end' }}>{tm.ticketCount}</td>
                <td style={{ textAlign: 'center' }}>
                  <span
                    className={`status-pill ${tm.active ? 'ok' : 'muted'}`}
                    style={{
                      fontSize: 11,
                      padding: '3px 10px',
                      borderRadius: 999,
                      background: tm.active ? 'rgba(16,185,129,0.12)' : 'rgba(107,114,128,0.14)',
                      color: tm.active ? '#059669' : '#4b5563',
                      fontWeight: 600,
                    }}
                  >
                    {tm.active ? t('common.active') : t('common.inactive')}
                  </span>
                </td>
                <td style={{ textAlign: 'end' }}>
                  <div style={{ display: 'inline-flex', gap: 6, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
                    <button
                      className="btn btn-primary btn-sm"
                      onClick={() => setAddingAdminTo(tm)}
                      title={t('super.addAdminHint') || 'Create an admin who will manage this team'}
                    >
                      + {t('super.addAdmin') || 'Add admin'}
                    </button>
                    <button
                      className="btn btn-ghost btn-sm"
                      onClick={() => setViewingMembersOf(tm)}
                    >
                      {t('super.members') || 'Members'}
                    </button>
                    <button
                      className="btn btn-ghost btn-sm"
                      onClick={() => setEditing(tm)}
                    >
                      {t('common.edit')}
                    </button>
                    <button
                      className="btn btn-ghost btn-sm"
                      onClick={() => askDelete(tm)}
                      style={{ color: '#dc2626' }}
                    >
                      {t('common.delete')}
                    </button>
                  </div>
                </td>
              </tr>
            ))}
            {teams.length === 0 && !loading && (
              <tr>
                <td colSpan={7} style={{ textAlign: 'center', padding: 24 }} className="muted">
                  {t('super.noTeams') || 'No teams yet. Create one to start onboarding admins.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {creating && (
        <TeamFormModal
          title={t('super.createTeam') || 'Create team'}
          onClose={() => setCreating(false)}
          onSaved={async () => { setCreating(false); await load(); }}
        />
      )}

      {editing && (
        <TeamFormModal
          title={t('super.editTeam') || 'Edit team'}
          team={editing}
          onClose={() => setEditing(null)}
          onSaved={async () => { setEditing(null); await load(); }}
        />
      )}

      {addingAdminTo && (
        <CreateTeamAdminModal
          team={addingAdminTo}
          onClose={() => setAddingAdminTo(null)}
          onSaved={async () => { setAddingAdminTo(null); await load(); }}
        />
      )}

      {viewingMembersOf && (
        <TeamMembersModal
          team={viewingMembersOf}
          onClose={() => setViewingMembersOf(null)}
        />
      )}
    </div>
  );
}

interface FormProps {
  title: string;
  team?: TeamSummary;
  onClose: () => void;
  onSaved: () => void | Promise<void>;
}

function TeamFormModal({ title, team, onClose, onSaved }: FormProps) {
  const { t } = useT();
  const isEdit = !!team;

  const [slug, setSlug] = useState(team?.slug || '');
  const [name, setName] = useState(team?.name || '');
  const [description, setDescription] = useState(team?.description || '');
  const [color, setColor] = useState(team?.color || '#6366f1');
  const [active, setActive] = useState(team?.active ?? true);
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    setSubmitting(true);
    try {
      if (isEdit && team) {
        const payload: UpdateTeamRequest = { name, description, color, active };
        await superApi.updateTeam(team.id, payload);
      } else {
        const payload: CreateTeamRequest = { slug, name, description, color };
        await superApi.createTeam(payload);
      }
      await onSaved();
    } catch (e2) {
      setErr(extractError(e2));
      setSubmitting(false);
    }
  }

  return (
    <Modal open onClose={onClose} title={title}>
      <form onSubmit={submit} style={{ display: 'grid', gap: 14 }}>
        {err && <div className="alert alert-error">{err}</div>}

        {!isEdit && (
          <div className="field">
            <label className="field-label">{t('super.slug') || 'Slug'}</label>
            <input
              className="input"
              value={slug}
              onChange={(e) => setSlug(e.target.value.toLowerCase())}
              pattern="^[a-z0-9][a-z0-9-]{1,58}[a-z0-9]$"
              required
              placeholder="medical"
            />
            <div style={{ fontSize: 12, color: 'var(--text-muted, #6b7280)', marginTop: 4 }}>
              {t('super.slugHint')
                || 'Lowercase letters, numbers, and dashes. Immutable after creation.'}
            </div>
          </div>
        )}

        <div className="field">
          <label className="field-label">{t('super.name') || 'Name'}</label>
          <input
            className="input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            maxLength={150}
          />
        </div>

        <div className="field">
          <label className="field-label">{t('super.description') || 'Description'}</label>
          <textarea
            className="input"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            rows={2}
            maxLength={300}
          />
        </div>

        <div className="field" style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <label className="field-label" style={{ margin: 0 }}>
            {t('super.color') || 'Team color'}
          </label>
          <input
            type="color"
            value={color}
            onChange={(e) => setColor(e.target.value)}
            style={{ width: 44, height: 32, border: 'none', background: 'transparent', cursor: 'pointer' }}
          />
          <code style={{ fontSize: 12 }}>{color}</code>
        </div>

        {isEdit && (
          <label className="field" style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <input
              type="checkbox"
              checked={active}
              onChange={(e) => setActive(e.target.checked)}
            />
            {t('common.active')}
          </label>
        )}

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

/**
 * One-shot form for seeding an ADMIN into a specific team without needing to enter the team
 * via impersonation first. The super admin picks credentials here; the backend stamps the
 * team on write. After success the caller reloads the team list so the "1 admins" counter
 * on the row bumps immediately.
 */
function CreateTeamAdminModal({
  team, onClose, onSaved,
}: { team: TeamSummary; onClose: () => void; onSaved: () => Promise<void> }) {
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
      const req: CreateTeamAdminRequest = { username, password, displayName, email };
      await superApi.createTeamAdmin(team.id, req);
      await onSaved();
    } catch (e2) {
      setErr(extractError(e2));
      setSubmitting(false);
    }
  }

  return (
    <Modal open onClose={onClose} title={`${t('super.addAdmin') || 'Add admin'} — ${team.name}`}>
      <form onSubmit={submit} style={{ display: 'grid', gap: 14 }}>
        {err && <div className="alert alert-error">{err}</div>}
        <p style={{ margin: 0, fontSize: 13, color: 'var(--text-muted, #6b7280)' }}>
          {t('super.addAdminExplain')
            || 'The new admin will manage users, projects, departments and tickets inside this team only.'}
        </p>
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

/** Read-only roster of a team's members — quick "who's in this team?" peek from the super surface. */
function TeamMembersModal({ team, onClose }: { team: TeamSummary; onClose: () => void }) {
  const { t } = useT();
  const [rows, setRows] = useState<TeamAdminRow[] | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    superApi.teamMembers(team.id)
      .then(setRows)
      .catch((e) => setErr(extractError(e)));
  }, [team.id]);

  return (
    <Modal open onClose={onClose} title={`${t('super.members') || 'Members'} — ${team.name}`}>
      {err && <div className="alert alert-error">{err}</div>}
      {!rows && !err && <div className="muted">{t('common.loading')}</div>}
      {rows && rows.length === 0 && (
        <div className="muted" style={{ padding: '12px 0' }}>
          {t('super.noMembers') || 'No members yet — add an admin to start.'}
        </div>
      )}
      {rows && rows.length > 0 && (
        <table className="table" style={{ width: '100%', marginTop: 8 }}>
          <thead>
            <tr>
              <th style={{ textAlign: 'start' }}>{t('super.username') || 'Username'}</th>
              <th style={{ textAlign: 'start' }}>{t('super.displayName') || 'Display'}</th>
              <th style={{ textAlign: 'center' }}>{t('common.status')}</th>
              <th style={{ textAlign: 'center' }}>Role</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((u) => (
              <tr key={u.id}>
                <td><code style={{ fontSize: 12 }}>{u.username}</code></td>
                <td>{u.displayName || '—'}</td>
                <td style={{ textAlign: 'center' }}>
                  {u.active ? t('common.active') : t('common.inactive')}
                </td>
                <td style={{ textAlign: 'center' }}>
                  <span style={{
                    fontSize: 11, padding: '3px 10px', borderRadius: 999,
                    background: u.role === 'ADMIN' ? 'rgba(245,158,11,0.14)' : 'rgba(99,102,241,0.14)',
                    color: u.role === 'ADMIN' ? '#b45309' : '#4338ca', fontWeight: 600,
                  }}>
                    {u.role}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 12 }}>
        <button type="button" className="btn btn-ghost" onClick={onClose}>
          {t('common.close')}
        </button>
      </div>
    </Modal>
  );
}
