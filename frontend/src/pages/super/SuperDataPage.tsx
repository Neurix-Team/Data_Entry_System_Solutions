import { Fragment, useCallback, useEffect, useMemo, useState } from 'react';
import { API_BASE, extractError } from '../../api/client';
import {
  superApi, type ExplorerFacets, type ExplorerPage, type ExplorerQuery, type ExplorerRow,
} from '../../api/super';
import {
  IconDatabase, IconDownload, IconSearch,
} from '../../components/Icons';
import { useT } from '../../i18n';

/**
 * Cross-team ticket explorer for SUPER_ADMIN. One table shows every ticket in the
 * install with the uploader, team, project, custom fields, and attachments joined in.
 * Row-click expands the row for details + one-click file downloads.
 *
 * <p>Downloads go through the existing session-authenticated
 * {@code /api/tickets/{ticketId}/documents/{docId}} route — no token required from the
 * UI. External systems use {@code /api/v1/export/*} with a personal-access token.
 */
export function SuperDataPage() {
  const { t } = useT();
  const [facets, setFacets] = useState<ExplorerFacets | null>(null);
  const [page, setPage] = useState<ExplorerPage | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());

  // Filters — controlled locally, applied on submit / Enter so a slow query doesn't
  // fire on every keystroke.
  const [teamId, setTeamId] = useState<string>('');
  const [projectId, setProjectId] = useState<string>('');
  const [userId, setUserId] = useState<string>('');
  const [from, setFrom] = useState<string>('');
  const [to, setTo] = useState<string>('');
  const [search, setSearch] = useState<string>('');

  // Cursor pagination — every "load more" appends to accumulated items so the operator
  // can scan without losing prior rows.
  const [items, setItems] = useState<ExplorerRow[]>([]);
  const [cursor, setCursor] = useState<number | null>(null);
  const [reloading, setReloading] = useState(false);

  useEffect(() => {
    superApi.explorerFacets().then(setFacets).catch((e) => setError(extractError(e)));
  }, []);

  const query = useMemo<ExplorerQuery>(() => {
    const q: ExplorerQuery = {};
    if (teamId) q.teamId = Number(teamId);
    if (projectId) q.projectId = Number(projectId);
    if (userId) q.userId = Number(userId);
    if (from) q.from = new Date(from).toISOString();
    if (to) q.to = new Date(to).toISOString();
    if (search.trim()) q.search = search.trim();
    return q;
  }, [teamId, projectId, userId, from, to, search]);

  const load = useCallback(async (append: boolean) => {
    if (append) setLoading(true); else setReloading(true);
    try {
      const q: ExplorerQuery = { ...query, size: 50 };
      if (append && cursor != null) q.cursor = cursor;
      const p = await superApi.explorerTickets(q);
      setPage(p);
      setCursor(p.nextCursor);
      setItems((prev) => append ? [...prev, ...p.items] : p.items);
      setError(null);
    } catch (e) {
      setError(extractError(e));
    } finally {
      setLoading(false);
      setReloading(false);
    }
  }, [query, cursor]);

  // First load + refresh whenever filters change.
  useEffect(() => {
    setExpanded(new Set());
    setCursor(null);
    void load(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [teamId, projectId, userId, from, to, search]);

  function reset() {
    setTeamId(''); setProjectId(''); setUserId('');
    setFrom(''); setTo(''); setSearch('');
  }

  function toggle(id: number) {
    const next = new Set(expanded);
    if (next.has(id)) next.delete(id); else next.add(id);
    setExpanded(next);
  }

  return (
    <div className="page super-page">
      <div className="page-header">
        <div>
          <h1>{t('super.data.title') || 'Data explorer'}</h1>
          <p className="subtitle">
            {t('super.data.subtitle')
              || 'Every ticket in every team, with its uploads, custom fields, and submitter.'}
          </p>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {/* Filter bar */}
      <div className="card" style={{ padding: 16, marginBottom: 16 }}>
        <div style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
          gap: 12,
        }}>
          <div>
            <label className="field-label">{t('super.data.team') || 'Team'}</label>
            <select className="input" value={teamId} onChange={(e) => setTeamId(e.target.value)}>
              <option value="">{t('super.all') || 'All'}</option>
              {facets?.teams.map((x) => (
                <option key={x.id} value={x.id}>{x.name}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="field-label">{t('super.data.project') || 'Project'}</label>
            <select className="input" value={projectId} onChange={(e) => setProjectId(e.target.value)}>
              <option value="">{t('super.all') || 'All'}</option>
              {facets?.projects.map((x) => (
                <option key={x.id} value={x.id}>{x.name}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="field-label">{t('super.data.user') || 'Submitted by'}</label>
            <select className="input" value={userId} onChange={(e) => setUserId(e.target.value)}>
              <option value="">{t('super.all') || 'All'}</option>
              {facets?.users.map((x) => (
                <option key={x.id} value={x.id}>{x.name}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="field-label">{t('super.data.from') || 'From'}</label>
            <input
              type="date"
              className="input"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
            />
          </div>
          <div>
            <label className="field-label">{t('super.data.to') || 'To'}</label>
            <input
              type="date"
              className="input"
              value={to}
              onChange={(e) => setTo(e.target.value)}
            />
          </div>
          <div style={{ gridColumn: '1 / -1' }}>
            <label className="field-label">{t('super.data.search') || 'Search'}</label>
            <div style={{ position: 'relative' }}>
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
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder={t('super.data.searchPlaceholder') || 'Search title, content or website…'}
                style={{ paddingInlineStart: 40 }}
              />
            </div>
          </div>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 12, alignItems: 'center' }}>
          <div className="muted" style={{ fontSize: 13 }}>
            {page && (
              <>
                <span style={{ verticalAlign: 'middle', marginInlineEnd: 4, display: 'inline-flex' }}>
                  <IconDatabase size={14} />
                </span>
                {t('super.data.showing') || 'Showing'} <strong>{items.length}</strong>
                {' '} {t('super.data.of') || 'of'} <strong>{page.total.toLocaleString()}</strong>
                {' '} {t('super.data.tickets') || 'tickets'}
              </>
            )}
          </div>
          <button type="button" className="btn btn-ghost btn-sm" onClick={reset}>
            {t('super.data.reset') || 'Reset filters'}
          </button>
        </div>
      </div>

      {reloading && <div className="muted" style={{ padding: 16 }}>{t('common.loading')}</div>}
      {!reloading && items.length === 0 && (
        <div className="empty-state">
          {t('super.data.empty') || 'No tickets match these filters.'}
        </div>
      )}

      {items.length > 0 && (
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th style={{ width: 60, textAlign: 'start' }}>#</th>
                <th style={{ textAlign: 'start' }}>{t('super.data.title2') || 'Title'}</th>
                <th style={{ textAlign: 'start' }}>{t('super.data.team') || 'Team'}</th>
                <th style={{ textAlign: 'start' }}>{t('super.data.project') || 'Project'}</th>
                <th style={{ textAlign: 'start' }}>{t('super.data.department') || 'Department'}</th>
                <th style={{ textAlign: 'start' }}>{t('super.data.submitter') || 'Submitted by'}</th>
                <th style={{ textAlign: 'center' }}>{t('super.data.files') || 'Files'}</th>
                <th style={{ textAlign: 'start' }}>{t('super.data.when') || 'When'}</th>
              </tr>
            </thead>
            <tbody>
              {items.map((row) => {
                const isOpen = expanded.has(row.id);
                return (
                  <Fragment key={row.id}>
                    <tr
                      style={{ cursor: 'pointer' }}
                      onClick={() => toggle(row.id)}
                    >
                      <td><code style={{ fontSize: 12 }}>#{row.id}</code></td>
                      <td style={{ fontWeight: 600, maxWidth: 340, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                        {row.title || <span className="muted">—</span>}
                      </td>
                      <td>{row.teamName || <span className="muted">—</span>}</td>
                      <td>{row.projectName || <span className="muted">—</span>}</td>
                      <td>{row.departmentName || <span className="muted">—</span>}</td>
                      <td>{row.submittedByDisplayName || row.submittedByUsername || <span className="muted">—</span>}</td>
                      <td style={{ textAlign: 'center' }}>
                        {row.documents.length > 0
                          ? <span className="chip">{row.documents.length}</span>
                          : <span className="muted">—</span>}
                      </td>
                      <td>{formatDate(row.submittedAt)}</td>
                    </tr>
                    {isOpen && (
                      <tr>
                        <td colSpan={8} style={{ background: 'var(--bg-subtle, #f9fafb)', padding: 16 }}>
                          <TicketDetails row={row} t={t} />
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {page?.hasMore && (
        <div style={{ display: 'flex', justifyContent: 'center', margin: 16 }}>
          <button
            type="button"
            className="btn btn-ghost"
            disabled={loading}
            onClick={() => load(true)}
          >
            {loading ? t('common.loading') : (t('super.data.loadMore') || 'Load more')}
          </button>
        </div>
      )}
    </div>
  );
}

function TicketDetails({ row, t }: { row: ExplorerRow; t: (k: string) => string }) {
  return (
    <div style={{ display: 'grid', gap: 16, gridTemplateColumns: '2fr 1fr' }}>
      <div>
        <SectionTitle label={t('super.data.content') || 'Content'} />
        <div style={{
          whiteSpace: 'pre-wrap', fontSize: 13, lineHeight: 1.55,
          padding: 12, background: 'white', border: '1px solid var(--border-subtle, #e5e7eb)',
          borderRadius: 6, maxHeight: 320, overflow: 'auto',
        }}>
          {row.content || <span className="muted">—</span>}
        </div>

        {row.customFields.length > 0 && (
          <>
            <SectionTitle label={t('super.data.fields') || 'Custom fields'} style={{ marginTop: 16 }} />
            <div style={{
              display: 'grid', gap: 6, gridTemplateColumns: 'auto 1fr',
              fontSize: 13, padding: 12, background: 'white',
              border: '1px solid var(--border-subtle, #e5e7eb)', borderRadius: 6,
            }}>
              {row.customFields.map((f, i) => (
                <Fragment key={i}>
                  <div style={{ fontWeight: 600, color: 'var(--text-secondary)' }}>
                    {f.fieldName || '—'}
                  </div>
                  <div>{f.value || <span className="muted">—</span>}</div>
                </Fragment>
              ))}
            </div>
          </>
        )}
      </div>
      <div>
        <SectionTitle label={t('super.data.metadata') || 'Metadata'} />
        <dl style={{
          margin: 0, padding: 12, background: 'white',
          border: '1px solid var(--border-subtle, #e5e7eb)', borderRadius: 6, fontSize: 13,
          display: 'grid', gap: 6, gridTemplateColumns: 'auto 1fr',
        }}>
          <dt style={{ color: 'var(--text-secondary)' }}>{t('super.data.department') || 'Department'}</dt>
          <dd style={{ margin: 0 }}>{row.departmentName || '—'}</dd>
          <dt style={{ color: 'var(--text-secondary)' }}>{t('super.data.subcategory') || 'Subcategory'}</dt>
          <dd style={{ margin: 0 }}>{row.subcategoryName || '—'}</dd>
          <dt style={{ color: 'var(--text-secondary)' }}>{t('super.data.status') || 'Status'}</dt>
          <dd style={{ margin: 0 }}>{row.status || '—'}</dd>
          <dt style={{ color: 'var(--text-secondary)' }}>{t('super.data.website') || 'Website'}</dt>
          <dd style={{ margin: 0 }}>
            {row.websiteLink
              ? <a href={row.websiteLink} target="_blank" rel="noreferrer">{row.websiteName || row.websiteLink}</a>
              : (row.websiteName || '—')}
          </dd>
        </dl>

        <SectionTitle label={t('super.data.attachments') || 'Attachments'} style={{ marginTop: 16 }} />
        {row.documents.length === 0 ? (
          <div className="muted" style={{ fontSize: 13 }}>{t('super.data.noFiles') || 'No files attached.'}</div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {row.documents.map((d) => (
              <a
                key={d.id}
                href={`${API_BASE}/tickets/${row.id}/documents/${d.id}`}
                className="btn btn-ghost btn-sm"
                style={{ justifyContent: 'flex-start', gap: 8 }}
              >
                <IconDownload size={14} />
                <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                  {d.name || d.originalFilename}
                </span>
                <span className="muted" style={{ fontSize: 11 }}>{formatBytes(d.sizeBytes)}</span>
              </a>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function SectionTitle({ label, style }: { label: string; style?: React.CSSProperties }) {
  return (
    <div style={{
      fontSize: 11, fontWeight: 700, letterSpacing: 1.1, textTransform: 'uppercase',
      color: 'var(--text-tertiary)', marginBottom: 6, ...style,
    }}>{label}</div>
  );
}

function formatDate(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}
