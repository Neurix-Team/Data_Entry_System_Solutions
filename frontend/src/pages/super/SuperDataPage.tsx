import { safeExternalUrl } from '../../utils/safeUrl';
import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { API_BASE, extractError } from '../../api/client';
import { SkeletonRows } from '../../components/SkeletonRows';
import {
  superApi, type ExplorerFacets, type ExplorerPage, type ExplorerQuery, type ExplorerRow, type ExplorerStats,
} from '../../api/super';
import {
  IconDatabase, IconDownload, IconSearch,
} from '../../components/Icons';
import { useT } from '../../i18n';
import { ExplorerAnalytics } from './ExplorerAnalytics';
import { DownloadCenter } from './DownloadCenter';

export function SuperDataPage() {
  const { t } = useT();
  const [facets, setFacets] = useState<ExplorerFacets | null>(null);
  const [stats, setStats] = useState<ExplorerStats | null>(null);
  const [page, setPage] = useState<ExplorerPage | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());

  const [teamId, setTeamId] = useState<string>('');
  const [projectId, setProjectId] = useState<string>('');
  const [departmentId, setDepartmentId] = useState<string>('');
  const [userId, setUserId] = useState<string>('');
  const [from, setFrom] = useState<string>('');
  const [to, setTo] = useState<string>('');
  const [search, setSearch] = useState<string>('');

  const [items, setItems] = useState<ExplorerRow[]>([]);
  const [cursor, setCursor] = useState<number | null>(null);
  const [reloading, setReloading] = useState(false);
  const [downloadOpen, setDownloadOpen] = useState(false);

  useEffect(() => {
    superApi.explorerFacets().then(setFacets).catch((e) => setError(extractError(e)));
  }, []);

  const query = useMemo<ExplorerQuery>(() => {
    const q: ExplorerQuery = {};
    if (teamId) q.teamId = Number(teamId);
    if (projectId) q.projectId = Number(projectId);
    if (departmentId) q.departmentId = Number(departmentId);
    if (userId) q.userId = Number(userId);
    if (from) q.from = new Date(`${from}T00:00:00`).toISOString();
    if (to) {
      const end = new Date(`${to}T00:00:00`);
      end.setDate(end.getDate() + 1); // Selected end date is inclusive; API upper bound is exclusive.
      q.to = end.toISOString();
    }
    if (search.trim()) q.search = search.trim();
    return q;
  }, [teamId, projectId, departmentId, userId, from, to, search]);

  const currentQuery = useRef(query);
  currentQuery.current = query;
  const requestSequence = useRef(0);

  const load = useCallback(async (append: boolean) => {
    const sequence = ++requestSequence.current;
    if (append) setLoading(true); else { setReloading(true); setStats(null); }
    const isCurrent = () => sequence === requestSequence.current && currentQuery.current === query;
    try {
      const q: ExplorerQuery = { ...query, size: 50 };
      if (append && cursor != null) q.cursor = cursor;
      let summaryError: string | null = null;
      const [p, summary] = await Promise.all([
        superApi.explorerTickets(q), append ? Promise.resolve(null) : superApi.explorerStats(query).catch((e) => {
          summaryError = extractError(e);
          return null;
        }),
      ]);
      if (!isCurrent()) return;
      if (summary) setStats(summary);
      setPage(p);
      setCursor(p.nextCursor);
      setItems((prev) => append ? [...prev, ...p.items] : p.items);
      setError(summaryError);
    } catch (e) {
      if (!isCurrent()) return;
      if (!append) { setItems([]); setPage(null); setStats(null); }
      setError(extractError(e));
    } finally {
      if (isCurrent()) { setLoading(false); setReloading(false); }
    }
  }, [query, cursor]);

  useEffect(() => {
    setExpanded(new Set());
    setCursor(null);
    setReloading(true);
    setLoading(false);
    setStats(null);
    ++requestSequence.current;
    const timer = window.setTimeout(() => void load(false), 180);
    return () => { window.clearTimeout(timer); ++requestSequence.current; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [teamId, projectId, departmentId, userId, from, to, search]);

  function reset() {
    setTeamId(''); setProjectId(''); setDepartmentId(''); setUserId('');
    setFrom(''); setTo(''); setSearch('');
  }

  function toggle(id: number) {
    const next = new Set(expanded);
    if (next.has(id)) next.delete(id); else next.add(id);
    setExpanded(next);
  }

  const filterLabels = useMemo(() => {
    const labels: string[] = [];
    const name = (list: { id: number; name: string }[] | undefined, id: string) =>
      list?.find((x) => String(x.id) === id)?.name;
    if (teamId) labels.push(`${t('super.data.team') || 'Team'}: ${name(facets?.teams, teamId) ?? teamId}`);
    if (projectId) labels.push(`${t('super.data.project') || 'Project'}: ${name(facets?.projects, projectId) ?? projectId}`);
    if (departmentId) labels.push(`${t('super.data.department')}: ${name(facets?.departments, departmentId) ?? departmentId}`);
    if (userId) labels.push(`${t('super.data.user') || 'Submitted by'}: ${name(facets?.users, userId) ?? userId}`);
    if (from) labels.push(`${t('super.data.from') || 'From'}: ${from}`);
    if (to) labels.push(`${t('super.data.to') || 'To'}: ${to}`);
    if (search.trim()) labels.push(`“${search.trim()}”`);
    return labels;
  }, [facets, teamId, projectId, departmentId, userId, from, to, search, t]);

  const departments = useMemo(() => (facets?.departments ?? []).filter((department) =>
    (!teamId || department.teamId === Number(teamId))
    && (!projectId || department.projectId == null || department.projectId === Number(projectId))),
  [facets, teamId, projectId]);

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
        <button
          type="button"
          className="btn btn-primary"
          onClick={() => setDownloadOpen(true)}
          disabled={reloading || !page || page.total === 0}
          title={t('super.data.download.subtitle')}
        >
          <IconDownload size={16} /> {t('super.data.download.button')}
        </button>
      </div>

      <DownloadCenter
        open={downloadOpen}
        onClose={() => setDownloadOpen(false)}
        query={query}
        filterLabels={filterLabels}
        departments={departments}
        onDepartmentChange={setDepartmentId}
      />

      {error && <div className="alert alert-error">{error}</div>}

      <div className="card" style={{ padding: 16, marginBottom: 16 }}>
        <div style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
          gap: 12,
        }}>
          <div>
            <label className="field-label">{t('super.data.team') || 'Team'}</label>
            <select className="input" value={teamId} onChange={(e) => { setTeamId(e.target.value); setDepartmentId(''); }}>
              <option value="">{t('super.all') || 'All'}</option>
              {facets?.teams.map((x) => (
                <option key={x.id} value={x.id}>{x.name}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="field-label">{t('super.data.project') || 'Project'}</label>
            <select className="input" value={projectId} onChange={(e) => { setProjectId(e.target.value); setDepartmentId(''); }}>
              <option value="">{t('super.all') || 'All'}</option>
              {facets?.projects.map((x) => (
                <option key={x.id} value={x.id}>{x.name}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="field-label" htmlFor="explorer-department">{t('super.data.department')}</label>
            <select id="explorer-department" className="input" value={departmentId} onChange={(e) => setDepartmentId(e.target.value)}>
              <option value="">{t('super.all')}</option>
              {departments.map((department) => (
                <option key={department.id} value={department.id}>
                  {department.name}{!projectId && department.projectId != null ? ` — ${facets?.projects.find((project) => project.id === department.projectId)?.name ?? department.projectId}` : ''}
                </option>
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

      <ExplorerAnalytics stats={stats} loading={reloading} filtered={filterLabels.length > 0} />

      {reloading && (
        <div className="table-wrap">
          <table className="data">
            <tbody>
              <SkeletonRows cols={8} rows={8} />
            </tbody>
          </table>
        </div>
      )}
      {!reloading && items.length === 0 && (
        <div className="empty-state">
          {t('super.data.empty') || 'No tickets match these filters.'}
        </div>
      )}

      {!reloading && items.length > 0 && (
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
                        <td colSpan={8} style={{ background: 'var(--bg-sunken)', padding: 16 }}>
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
            disabled={loading || reloading}
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
    <div className="split-2-1">
      <div>
        <SectionTitle label={t('super.data.content') || 'Content'} />
        <div style={{
          whiteSpace: 'pre-wrap', fontSize: 13, lineHeight: 1.55,
          padding: 12, background: 'var(--bg-surface)', border: '1px solid var(--border)',
          borderRadius: 6, maxHeight: 320, overflow: 'auto',
        }}>
          {row.content || <span className="muted">—</span>}
        </div>

        {row.customFields.length > 0 && (
          <>
            <SectionTitle label={t('super.data.fields') || 'Custom fields'} style={{ marginTop: 16 }} />
            <div style={{
              display: 'grid', gap: 6, gridTemplateColumns: 'auto 1fr',
              fontSize: 13, padding: 12, background: 'var(--bg-surface)',
              border: '1px solid var(--border)', borderRadius: 6,
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
          margin: 0, padding: 12, background: 'var(--bg-surface)',
          border: '1px solid var(--border)', borderRadius: 6, fontSize: 13,
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
              ? <a href={safeExternalUrl(row.websiteLink)} target="_blank" rel="noreferrer">{row.websiteName || row.websiteLink}</a>
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
