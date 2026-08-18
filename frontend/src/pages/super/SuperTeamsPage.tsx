import { FormEvent, useEffect, useMemo, useState } from 'react';
import { extractError } from '../../api/client';
import {
  superApi, type CreateTeamAdminRequest, type CreateTeamRequest,
  type TeamAdminRow, type TeamSummary, type UpdateTeamRequest,
} from '../../api/super';
import { Modal } from '../../components/Modal';
import { PasswordInput } from '../../components/PasswordInput';
import { useConfirm } from '../../components/ConfirmDialog';
import { IconBuilding, IconMembers, IconSearch } from '../../components/Icons';
import { useT } from '../../i18n';

/**
 * Team CRUD in the app's classical admin style — page shell + table, no bespoke hero.
 * Row actions inline: add an admin to the team, view its member roster, edit, delete.
 * Deliberately mirrors AdminUsersPage's layout so a super admin doesn't have to relearn
 * a whole new visual language just because they're one level up.
 */
export function SuperTeamsPage() {
  const { t } = useT();
  const [teams, setTeams] = useState<TeamSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<TeamSummary | null>(null);
  const [addingAdminTo, setAddingAdminTo] = useState<TeamSummary | null>(null);
  const [viewingMembersOf, setViewingMembersOf] = useState<TeamSummary | null>(null);
  const [q, setQ] = useState('');
  const [statusFilter, setStatusFilter] = useState<'all' | 'active' | 'inactive'>('all');
  const confirm = useConfirm();

  const filtered = useMemo(() => {
    if (!teams) return [];
    const needle = q.trim().toLowerCase();
    return teams.filter((tm) => {
      if (statusFilter === 'active' && !tm.active) return false;
      if (statusFilter === 'inactive' && tm.active) return false;
      if (!needle) return true;
      return (tm.name + ' ' + tm.slug + ' ' + (tm.description ?? '')).toLowerCase().includes(needle);
    });
  }, [teams, q, statusFilter]);

  useEffect(() => { void load(); }, []);

  async function load() {
    try {
      setTeams(await superApi.teams());
      setError(null);
    } catch (e) {
      setError(extractError(e));
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
    <div className="page super-page">
      <div className="page-header">
        <div>
          <h1>{t('super.teams') || 'Teams'}</h1>
          <p className="subtitle">
            {t('super.teamsSubtitle')
              || 'Each team is a fully isolated workspace with its own users, projects, and tickets.'}
          </p>
        </div>
        <button type="button" className="btn btn-primary" onClick={() => setCreating(true)}>
          <IconBuilding size={16} /> {t('super.createTeam') || 'Create team'}
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
            placeholder={t('super.searchTeams') || 'Search teams…'}
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
        {teams && (
          <span className="result-count">{filtered.length} / {teams.length}</span>
        )}
      </div>

      {!teams && <div className="muted" style={{ padding: 16 }}>{t('common.loading')}</div>}
      {teams && teams.length === 0 && (
        <div className="empty-state">
          {t('super.noTeams') || 'No teams yet. Create one to start onboarding admins.'}
        </div>
      )}
      {teams && teams.length > 0 && filtered.length === 0 && (
        <div className="empty-state">
          {t('super.noMatch') || 'No teams match the current filter.'}
        </div>
      )}
      {teams && filtered.length > 0 && (
        <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th style={{ textAlign: 'start' }}>{t('super.name') || 'Team'}</th>
                  <th style={{ textAlign: 'start' }}>{t('super.slug') || 'Slug'}</th>
                  <th style={{ textAlign: 'end' }}>{t('super.users') || 'Users'}</th>
                  <th style={{ textAlign: 'end' }}>{t('super.admins') || 'Admins'}</th>
                  <th style={{ textAlign: 'end' }}>{t('super.projects') || 'Projects'}</th>
                  <th style={{ textAlign: 'end' }}>{t('super.tickets') || 'Tickets'}</th>
                  <th style={{ textAlign: 'center' }}>{t('common.status')}</th>
                  <th style={{ textAlign: 'end' }}>{t('common.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((team) => (
                  <tr key={team.id}>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                        <span
                          aria-hidden="true"
                          style={{
                            width: 28, height: 28, borderRadius: 8,
                            background: team.color || 'var(--brand)', color: '#fff',
                            display: 'grid', placeItems: 'center',
                            fontSize: 13, fontWeight: 700,
                          }}
                        >
                          {team.name.charAt(0).toUpperCase()}
                        </span>
                        <div>
                          <div style={{ fontWeight: 600 }}>{team.name}</div>
                          {team.description && (
                            <div style={{ fontSize: 12, color: 'var(--text-tertiary)' }}>
                              {team.description}
                            </div>
                          )}
                        </div>
                      </div>
                    </td>
                    <td><code style={{ fontSize: 12 }}>{team.slug}</code></td>
                    <td style={{ textAlign: 'end' }}>{team.userCount}</td>
                    <td style={{ textAlign: 'end' }}>{team.adminCount}</td>
                    <td style={{ textAlign: 'end' }}>{team.projectCount}</td>
                    <td style={{ textAlign: 'end' }}>{team.ticketCount}</td>
                    <td style={{ textAlign: 'center' }}>
                      <StatusPill active={team.active}
                        labelActive={t('common.active')} labelInactive={t('common.inactive')} />
                    </td>
                    <td style={{ textAlign: 'end' }}>
                      <div style={{ display: 'inline-flex', gap: 6, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
                        <button type="button" className="btn btn-primary btn-sm"
                          onClick={() => setAddingAdminTo(team)}
                          title={t('super.addAdminHint') || 'Create an admin who will manage this team'}
                        >
                          <IconMembers size={12} /> {t('super.addAdmin') || 'Add admin'}
                        </button>
                        <button type="button" className="btn btn-ghost btn-sm"
                          onClick={() => setViewingMembersOf(team)}
                        >
                          {t('super.members') || 'Members'}
                        </button>
                        <button type="button" className="btn btn-ghost btn-sm"
                          onClick={() => setEditing(team)}
                        >
                          {t('common.edit')}
                        </button>
                        <button type="button" className="btn btn-ghost btn-sm"
                          onClick={() => askDelete(team)}
                          style={{ color: 'var(--danger)' }}
                        >
                          {t('common.delete')}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
        </div>
      )}

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
        <TeamMembersModal team={viewingMembersOf} onClose={() => setViewingMembersOf(null)} />
      )}
    </div>
  );
}

function StatusPill({ active, labelActive, labelInactive }: { active: boolean; labelActive: string; labelInactive: string }) {
  return (
    <span
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 6,
        padding: '3px 10px', borderRadius: 999, fontSize: 11, fontWeight: 600,
        background: active ? 'var(--success-soft)' : 'var(--status-completed-soft)',
        color:      active ? 'var(--success-soft-text)' : 'var(--status-completed-text)',
      }}
    >
      <span aria-hidden="true"
        style={{
          width: 6, height: 6, borderRadius: 999,
          background: active ? 'var(--success)' : 'var(--status-completed)',
        }}
      />
      {active ? labelActive : labelInactive}
    </span>
  );
}

// ---------- Modals ----------

function TeamFormModal({
  title, team, onClose, onSaved,
}: { title: string; team?: TeamSummary; onClose: () => void; onSaved: () => void | Promise<void> }) {
  const { t } = useT();
  const isEdit = !!team;
  const [slug, setSlug] = useState(team?.slug || '');
  const [name, setName] = useState(team?.name || '');
  const [description, setDescription] = useState(team?.description || '');
  const [color, setColor] = useState(team?.color || '#0f5fd1');
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
      <form onSubmit={submit} className="super-modal-form">
        {err && <div className="alert alert-error">{err}</div>}

        {!isEdit && (
          <div className="field">
            <label className="field-label">{t('super.slug') || 'Slug'}</label>
            <input className="input"
              value={slug} onChange={(e) => setSlug(e.target.value.toLowerCase())}
              pattern="^[a-z0-9][a-z0-9-]{1,58}[a-z0-9]$" required placeholder="medical"
            />
            <div style={{ fontSize: 12, color: 'var(--text-tertiary)', marginTop: 4 }}>
              {t('super.slugHint') || 'Lowercase letters, numbers, and dashes. Immutable after creation.'}
            </div>
          </div>
        )}

        <div className="field">
          <label className="field-label">{t('super.name') || 'Name'}</label>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} required maxLength={150} />
        </div>

        <div className="field">
          <label className="field-label">{t('super.description') || 'Description'}</label>
          <textarea className="input"
            value={description} onChange={(e) => setDescription(e.target.value)}
            rows={2} maxLength={300}
          />
        </div>

        <div className="field" style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <label className="field-label" style={{ margin: 0 }}>{t('super.color') || 'Team color'}</label>
          <input type="color" value={color} onChange={(e) => setColor(e.target.value)}
            style={{ width: 44, height: 32, border: 'none', background: 'transparent', cursor: 'pointer', padding: 0 }}
          />
          <code style={{
            fontSize: 12, padding: '3px 8px', borderRadius: 6,
            background: 'var(--bg-muted)', color: 'var(--text-secondary)',
          }}>{color}</code>
        </div>

        {isEdit && (
          <label className="field" style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
            {t('common.active')}
          </label>
        )}

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
      <form onSubmit={submit} className="super-modal-form">
        {err && <div className="alert alert-error">{err}</div>}
        <div className="super-modal-hint">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="12" cy="12" r="10" /><line x1="12" y1="16" x2="12" y2="12" /><line x1="12" y1="8" x2="12.01" y2="8" />
          </svg>
          <span>
            {t('super.addAdminExplain')
              || 'The new admin will manage users, projects, departments and tickets inside this team only.'}
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

function TeamMembersModal({ team, onClose }: { team: TeamSummary; onClose: () => void }) {
  const { t } = useT();
  const [rows, setRows] = useState<TeamAdminRow[] | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    superApi.teamMembers(team.id).then(setRows).catch((e) => setErr(extractError(e)));
  }, [team.id]);

  return (
    <Modal open onClose={onClose} title={`${t('super.members') || 'Members'} — ${team.name}`}>
      <div style={{ display: 'grid', gap: 12 }}>
        {err && <div className="alert alert-error">{err}</div>}
        {!rows && !err && <div className="muted">{t('common.loading')}</div>}
        {rows && rows.length === 0 && (
          <div className="muted" style={{ padding: 16, textAlign: 'center' }}>
            {t('super.noMembers') || 'No members yet — add an admin to start.'}
          </div>
        )}
        {rows && rows.length > 0 && (
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th style={{ textAlign: 'start' }}>{t('super.username') || 'Username'}</th>
                  <th style={{ textAlign: 'start' }}>{t('super.displayName') || 'Display'}</th>
                  <th style={{ textAlign: 'center' }}>Role</th>
                  <th style={{ textAlign: 'center' }}>{t('common.status')}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((u) => (
                  <tr key={u.id}>
                    <td><code style={{ fontSize: 12 }}>{u.username}</code></td>
                    <td>{u.displayName || '—'}</td>
                    <td style={{ textAlign: 'center' }}>
                      <span style={{
                        display: 'inline-block', padding: '2px 8px', borderRadius: 999,
                        fontSize: 11, fontWeight: 600,
                        background: u.role === 'ADMIN' ? 'var(--brand-soft)' : 'var(--accent-cyan-soft)',
                        color:      u.role === 'ADMIN' ? 'var(--brand-soft-text)' : 'var(--accent-cyan-soft-text)',
                      }}>{u.role}</span>
                    </td>
                    <td style={{ textAlign: 'center' }}>
                      <span style={{ fontSize: 11, color: u.active ? 'var(--success-soft-text)' : 'var(--text-tertiary)' }}>
                        {u.active ? t('common.active') : t('common.inactive')}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            {t('common.close')}
          </button>
        </div>
      </div>
    </Modal>
  );
}
