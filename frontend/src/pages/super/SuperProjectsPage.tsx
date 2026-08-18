import { useEffect, useMemo, useState } from 'react';
import { extractError } from '../../api/client';
import { superApi, type PersonRef, type ProjectBreakdown } from '../../api/super';
import { IconChart, IconFolder, IconSearch } from '../../components/Icons';
import { useT } from '../../i18n';

/**
 * Cross-team project analytics — one row per project with owning team, every admin of
 * that team, the project's own member roster, and ticket counts. Uses the same
 * {@code page / card / table} shell as the rest of the admin surface so the operator
 * doesn't context-switch visual language just because they're one level up.
 *
 * <p>Clicking a row expands it in-place to show the full admin + member lists.
 */
export function SuperProjectsPage() {
  const { t } = useT();
  const [rows, setRows] = useState<ProjectBreakdown[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [teamFilter, setTeamFilter] = useState<string>('all');
  const [q, setQ] = useState('');

  useEffect(() => {
    superApi.projectsBreakdown()
      .then(setRows)
      .catch((e) => setError(extractError(e)));
  }, []);

  const teams = useMemo(() => {
    const m = new Map<number, { id: number; name: string; color?: string | null }>();
    rows?.forEach((r) => {
      if (r.teamId != null && !m.has(r.teamId)) {
        m.set(r.teamId, { id: r.teamId, name: r.teamName ?? '—', color: r.teamColor });
      }
    });
    return Array.from(m.values());
  }, [rows]);

  const filtered = useMemo(() => {
    if (!rows) return [];
    const needle = q.trim().toLowerCase();
    return rows.filter((r) => {
      if (teamFilter !== 'all' && String(r.teamId ?? '') !== teamFilter) return false;
      if (!needle) return true;
      const hay = [
        r.projectName, r.projectNameEn, r.projectNameAr, r.teamName,
        ...r.teamAdmins.map((a) => a.username + ' ' + (a.displayName ?? '')),
        ...r.projectMembers.map((a) => a.username + ' ' + (a.displayName ?? '')),
      ].join(' ').toLowerCase();
      return hay.includes(needle);
    });
  }, [rows, q, teamFilter]);

  const totals = useMemo(() => rows ? {
    tickets: rows.reduce((a, r) => a + r.ticketCount, 0),
    week:    rows.reduce((a, r) => a + r.ticketsThisWeek, 0),
  } : { tickets: 0, week: 0 }, [rows]);

  function toggle(id: number) {
    const next = new Set(expanded);
    if (next.has(id)) next.delete(id); else next.add(id);
    setExpanded(next);
  }

  return (
    <div className="page super-page">
      <div className="page-header">
        <div>
          <h1>{t('super.projectsTitle') || 'Project analytics'}</h1>
          <p className="subtitle">
            {t('super.projectsSubtitle')
              || 'Every project in every team, with its owning admins and assigned users.'}
          </p>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(3, minmax(0, 1fr))' }}>
        <div className="stat-card super-stat tint-brand">
          <div className="stat-card-header">
            <span className="stat-card-label">{t('super.projects') || 'Projects'}</span>
            <span className="stat-card-icon"><IconFolder size={16} /></span>
          </div>
          <div className="stat-card-value">{rows?.length ?? '—'}</div>
        </div>
        <div className="stat-card super-stat tint-coral">
          <div className="stat-card-header">
            <span className="stat-card-label">{t('super.tickets') || 'Tickets'}</span>
            <span className="stat-card-icon"><IconChart size={16} /></span>
          </div>
          <div className="stat-card-value">{rows ? totals.tickets : '—'}</div>
        </div>
        <div className="stat-card super-stat tint-green">
          <div className="stat-card-header">
            <span className="stat-card-label">{t('super.thisWeek') || 'This week'}</span>
            <span className="stat-card-icon"><IconChart size={16} /></span>
          </div>
          <div className="stat-card-value">{rows ? totals.week : '—'}</div>
        </div>
      </div>

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
            placeholder={t('super.searchProjects') || 'Search projects, admins, members…'}
            style={{ paddingInlineStart: 40 }}
          />
        </div>
        <select className="input"
          style={{ width: 220, minHeight: 40 }}
          value={teamFilter} onChange={(e) => setTeamFilter(e.target.value)}
        >
          <option value="all">{t('super.allTeams') || 'All teams'}</option>
          {teams.map((tm) => (
            <option key={tm.id} value={String(tm.id)}>{tm.name}</option>
          ))}
        </select>
        <span className="result-count">
          {filtered.length} / {rows?.length ?? 0}
        </span>
      </div>

      {!rows && <div className="muted" style={{ padding: 16 }}>{t('common.loading')}</div>}
      {rows && filtered.length === 0 && (
        <div className="empty-state">
          {t('super.noProjects') || 'No projects match the current filter.'}
        </div>
      )}
      {rows && filtered.length > 0 && (
        <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th style={{ width: 32 }} />
                  <th style={{ textAlign: 'start' }}>{t('super.projectCol') || 'Project'}</th>
                  <th style={{ textAlign: 'start' }}>{t('super.team') || 'Team'}</th>
                  <th style={{ textAlign: 'start' }}>{t('super.admins') || 'Admins'}</th>
                  <th style={{ textAlign: 'start' }}>{t('super.members') || 'Members'}</th>
                  <th style={{ textAlign: 'end' }}>{t('super.tickets') || 'Tickets'}</th>
                  <th style={{ textAlign: 'end' }}>{t('super.thisWeek') || 'This week'}</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((r) => {
                  const isOpen = expanded.has(r.projectId);
                  return (
                    <>
                      <tr
                        key={r.projectId}
                        onClick={() => toggle(r.projectId)}
                        style={{ cursor: 'pointer' }}
                      >
                        <td style={{ textAlign: 'center', color: 'var(--text-tertiary)' }}>
                          {isOpen ? '▾' : '▸'}
                        </td>
                        <td>
                          <div style={{ fontWeight: 600 }}>{r.projectName}</div>
                          <div style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>
                            #{r.projectId} · {r.status}
                          </div>
                        </td>
                        <td>
                          {r.teamName ? (
                            <span style={{
                              display: 'inline-flex', alignItems: 'center', gap: 6,
                              fontSize: 12, fontWeight: 600,
                            }}>
                              <span aria-hidden="true" style={{
                                width: 10, height: 10, borderRadius: 3,
                                background: r.teamColor || 'var(--brand)',
                              }} />
                              {r.teamName}
                            </span>
                          ) : <span className="muted">—</span>}
                        </td>
                        <td><Chips people={r.teamAdmins} tint="brand" max={2} /></td>
                        <td><Chips people={r.projectMembers} tint="cyan" max={3} /></td>
                        <td style={{ textAlign: 'end', fontWeight: 600 }}>{r.ticketCount}</td>
                        <td style={{ textAlign: 'end' }}>{r.ticketsThisWeek}</td>
                      </tr>
                      {isOpen && (
                        <tr key={r.projectId + '_x'}>
                          <td />
                          <td colSpan={6} style={{ background: 'var(--bg-sunken)', padding: 16 }}>
                            <div style={{
                              display: 'grid',
                              gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))',
                              gap: 20,
                            }}>
                              <PeopleList
                                title={`${t('super.teamAdminsFull') || 'Team admins who can manage this project'} (${r.teamAdmins.length})`}
                                people={r.teamAdmins}
                                tint="brand"
                                empty={t('super.noAdminsInTeam') || 'No admins in this team yet.'}
                              />
                              <PeopleList
                                title={`${t('super.projectMembersFull') || 'Users assigned to this project'} (${r.projectMembers.length})`}
                                people={r.projectMembers}
                                tint="cyan"
                                empty={t('super.noMembersInProject') || 'No users assigned yet.'}
                              />
                            </div>
                          </td>
                        </tr>
                      )}
                    </>
                  );
                })}
              </tbody>
            </table>
        </div>
      )}
    </div>
  );
}

function Chips({ people, tint, max }: { people: PersonRef[]; tint: 'brand' | 'cyan'; max: number }) {
  if (people.length === 0) return <span className="muted">—</span>;
  const shown = people.slice(0, max);
  const extra = people.length - shown.length;
  const bg = tint === 'brand' ? 'var(--brand-soft)' : 'var(--accent-cyan-soft)';
  const fg = tint === 'brand' ? 'var(--brand-soft-text)' : 'var(--accent-cyan-soft-text)';
  return (
    <div style={{ display: 'inline-flex', gap: 4, flexWrap: 'wrap' }}>
      {shown.map((p) => (
        <span key={p.id} title={p.username}
          style={{
            fontSize: 11, padding: '3px 8px', borderRadius: 999,
            background: bg, color: fg, fontWeight: 600,
          }}
        >{p.displayName || p.username}</span>
      ))}
      {extra > 0 && (
        <span style={{
          fontSize: 11, padding: '3px 8px', borderRadius: 999,
          background: 'var(--bg-muted)', color: 'var(--text-secondary)', fontWeight: 600,
        }}>+{extra}</span>
      )}
    </div>
  );
}

function PeopleList({
  title, people, tint, empty,
}: { title: string; people: PersonRef[]; tint: 'brand' | 'cyan'; empty: string }) {
  const bg = tint === 'brand' ? 'var(--brand)' : 'var(--accent-cyan)';
  return (
    <div>
      <div style={{
        fontSize: 11, fontWeight: 700, letterSpacing: 0.6,
        textTransform: 'uppercase', color: 'var(--text-tertiary)', marginBottom: 8,
      }}>{title}</div>
      {people.length === 0 ? (
        <div className="muted" style={{ fontSize: 13 }}>{empty}</div>
      ) : (
        <ul style={{ listStyle: 'none', padding: 0, margin: 0, display: 'grid', gap: 6 }}>
          {people.map((p) => (
            <li key={p.id} style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 13 }}>
              <span aria-hidden="true" style={{
                width: 28, height: 28, borderRadius: 999,
                background: bg, color: '#fff',
                display: 'grid', placeItems: 'center', fontSize: 12, fontWeight: 700,
              }}>
                {(p.displayName || p.username).charAt(0).toUpperCase()}
              </span>
              <div>
                <div style={{ fontWeight: 600 }}>{p.displayName || p.username}</div>
                <div style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>@{p.username}</div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
