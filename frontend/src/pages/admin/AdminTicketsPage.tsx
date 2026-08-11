import { useEffect, useMemo, useState } from 'react';
import { extractError } from '../../api/client';
import { departmentsApi, ticketsApi } from '../../api/resources';
import type { AdminStats, Department, Ticket, TicketPage, TicketStatus } from '../../api/types';
import { Avatar } from '../../components/Avatar';
import { IconAlert, IconCheck, IconClock, IconPlus } from '../../components/Icons';
import { Modal } from '../../components/Modal';
import { StatusPill } from '../../components/StatusPill';
import { useToast } from '../../components/toast/ToastContext';
import { useT } from '../../i18n';

interface Filters {
  q: string;
  departmentId: string;
  status: string;
  from: string;
  to: string;
}

const emptyFilters: Filters = { q: '', departmentId: '', status: '', from: '', to: '' };

export function AdminTicketsPage() {
  const { t, lang } = useT();
  const toast = useToast();
  const isAr = lang === 'ar';
  const [bulkSel, setBulkSel] = useState<Set<number>>(new Set());
  const [bulkDeleting, setBulkDeleting] = useState(false);
  const [data, setData] = useState<TicketPage | null>(null);
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [page, setPage] = useState(0);
  const [size] = useState(50);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Ticket | null>(null);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [filters, setFilters] = useState<Filters>(emptyFilters);

  async function refresh() {
    setLoading(true);
    try {
      const [p, s] = await Promise.all([
        ticketsApi.listAll(page, size),
        ticketsApi.stats(),
      ]);
      setData(p);
      setStats(s);
    } catch (e) {
      setError(extractError(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { refresh(); }, [page]);
  useEffect(() => { departmentsApi.adminList().then(setDepartments).catch(() => {}); }, []);

  const filtered = useMemo(() => {
    if (!data) return [];
    const q = filters.q.trim().toLowerCase();
    const deptId = filters.departmentId ? Number(filters.departmentId) : null;
    const from = filters.from ? new Date(filters.from).getTime() : null;
    const to = filters.to ? new Date(filters.to + 'T23:59:59').getTime() : null;

    return data.items.filter((tk) => {
      if (deptId != null && tk.departmentId !== deptId) return false;
      if (filters.status && tk.status !== filters.status) return false;
      const ts = new Date(tk.submittedAt).getTime();
      if (from != null && ts < from) return false;
      if (to != null && ts > to) return false;
      if (q) {
        const hay = [
          tk.title ?? '', tk.content, tk.websiteName ?? '', tk.websiteLink ?? '',
          tk.submittedByUsername, tk.submittedByDisplayName ?? '',
          tk.departmentName,
        ].join(' ').toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    });
  }, [data, filters]);

  async function onStatusChange(tk: Ticket, next: TicketStatus) {
    try {
      const updated = await ticketsApi.updateStatus(tk.id, next);
      setData((d) => d ? { ...d, items: d.items.map(x => x.id === tk.id ? updated : x) } : d);
      toast.success(isAr ? 'تم تحديث الحالة' : 'Status updated');
    } catch (e) { toast.error(extractError(e)); }
  }

  async function onDelete(tk: Ticket) {
    if (!confirm(t('common.confirmDelete', { name: `#${tk.id}` }))) return;
    try {
      await ticketsApi.remove(tk.id);
      toast.success(isAr ? 'تم حذف التذكرة' : 'Ticket deleted');
      refresh();
    } catch (e) { toast.error(extractError(e)); }
  }

  function toggleTicketSelected(id: number) {
    setBulkSel((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  async function onBulkDeleteTickets() {
    const ids = Array.from(bulkSel);
    if (ids.length === 0) return;
    if (!confirm(isAr ? `تأكيد حذف ${ids.length} تذكرة؟` : `Delete ${ids.length} ticket${ids.length === 1 ? '' : 's'}?`)) return;
    setBulkDeleting(true);
    let ok = 0; let fail = 0;
    for (const id of ids) {
      try { await ticketsApi.remove(id); ok++; } catch { fail++; }
    }
    setBulkDeleting(false);
    if (fail === 0) toast.success(isAr ? `تم حذف ${ok} تذكرة` : `Deleted ${ok}`);
    else toast.warning(isAr ? `تم حذف ${ok}، فشل ${fail}` : `Deleted ${ok}, ${fail} failed`);
    setBulkSel(new Set());
    refresh();
  }

  const hasFilters = filters.q || filters.departmentId || filters.status || filters.from || filters.to;

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('admin.tickets.title')}</h1>
          <p className="subtitle">{t('admin.tickets.subtitle')}</p>
        </div>
        <div className="row gap-2">
          <button className="btn btn-secondary btn-sm">
            <IconAlert size={16} /> {t('common.filters')}
          </button>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(3, minmax(0, 1fr))' }}>
        <StatCard
          label={t('admin.tickets.inProgress')}
          value={stats?.inProgress ?? 0}
          suffix={t('admin.tickets.tasksLabel')}
          icon={<IconClock size={16} />}
          hero="yellow"
        />
        <StatCard
          label={t('admin.tickets.needsReview')}
          value={stats?.review ?? 0}
          suffix={t('admin.tickets.tasksLabel')}
          icon={<IconAlert size={16} />}
          hero="blue"
          suffixTone="danger"
        />
        <StatCard
          label={t('admin.tickets.completedToday')}
          value={stats?.completedToday ?? 0}
          suffix={t('admin.tickets.tasksLabel')}
          icon={<IconCheck size={16} />}
          hero="amber"
        />
      </div>

      <div className="toolbar">
        <input
          className="input grow"
          type="search"
          placeholder={t('admin.tickets.searchPlaceholder')}
          value={filters.q}
          onChange={(e) => setFilters({ ...filters, q: e.target.value })}
        />
        <select
          className="select"
          style={{ width: 'auto', minWidth: 180 }}
          value={filters.departmentId}
          onChange={(e) => setFilters({ ...filters, departmentId: e.target.value })}
        >
          <option value="">{t('admin.tickets.filterAllDepartments')}</option>
          {departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
        </select>
        <select
          className="select"
          style={{ width: 'auto', minWidth: 160 }}
          value={filters.status}
          onChange={(e) => setFilters({ ...filters, status: e.target.value })}
        >
          <option value="">{t('admin.tickets.filterAllStatuses')}</option>
          <option value="IN_PROGRESS">{t('status.IN_PROGRESS')}</option>
          <option value="REVIEW">{t('status.REVIEW')}</option>
          <option value="COMPLETED">{t('status.COMPLETED')}</option>
        </select>
        <label className="field-inline">
          {t('common.from')}
          <input
            type="date"
            className="input"
            style={{ width: 'auto' }}
            value={filters.from}
            onChange={(e) => setFilters({ ...filters, from: e.target.value })}
          />
        </label>
        <label className="field-inline">
          {t('common.to')}
          <input
            type="date"
            className="input"
            style={{ width: 'auto' }}
            value={filters.to}
            onChange={(e) => setFilters({ ...filters, to: e.target.value })}
          />
        </label>
        {hasFilters && (
          <button className="btn btn-ghost btn-sm" onClick={() => setFilters(emptyFilters)}>
            {t('admin.tickets.clearFilters')}
          </button>
        )}
        {data && (
          <span className="muted small" style={{ marginInlineStart: 'auto' }}>
            {t('admin.tickets.resultsCount', { count: filtered.length, total: data.totalItems })}
          </span>
        )}
      </div>

      {bulkSel.size > 0 && (
        <div className="bulk-action-bar">
          <label className="bulk-action-select-all">
            {isAr ? `${bulkSel.size} مختار` : `${bulkSel.size} selected`}
          </label>
          <button className="btn btn-danger btn-sm" onClick={onBulkDeleteTickets} disabled={bulkDeleting}>
            {bulkDeleting ? (isAr ? 'جارٍ الحذف…' : 'Deleting…') : (isAr ? `حذف المختار (${bulkSel.size})` : `Delete selected (${bulkSel.size})`)}
          </button>
          <button className="btn btn-ghost btn-sm" onClick={() => setBulkSel(new Set())}>
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
                  checked={bulkSel.size > 0 && bulkSel.size === filtered.length}
                  onChange={() => {
                    if (bulkSel.size === filtered.length) setBulkSel(new Set());
                    else setBulkSel(new Set(filtered.map(t => t.id)));
                  }}
                  aria-label={isAr ? 'تحديد الكل' : 'Select all'}
                />
              </th>
              <th>{t('admin.tickets.colContent')}</th>
              <th>{t('admin.tickets.colSubmittedBy')}</th>
              <th>{t('admin.tickets.colDepartment')}</th>
              <th>{t('user.submit.subcategory')}</th>
              <th>{lang === 'ar' ? 'المشروع' : 'Project'}</th>
              <th>{t('common.status')}</th>
              <th className="actions-col-header">{t('common.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={8} className="empty-state">{t('common.loading')}</td></tr>
            ) : filtered.length === 0 ? (
              <tr><td colSpan={8} className="empty-state">
                {hasFilters ? t('admin.tickets.noMatches') : t('admin.tickets.empty')}
              </td></tr>
            ) : filtered.map((tk) => (
              <tr key={tk.id}>
                <td className="row-check">
                  <input
                    type="checkbox"
                    checked={bulkSel.has(tk.id)}
                    onChange={() => toggleTicketSelected(tk.id)}
                    aria-label={isAr ? 'تحديد' : 'Select'}
                  />
                </td>
                <td>
                  <div style={{ fontWeight: 500 }}>
                    #{tk.id} · {tk.title || tk.websiteName || t('admin.tickets.untitled')}
                  </div>
                  <div className="small muted truncate">{tk.content}</div>
                </td>
                <td>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
                    <Avatar name={tk.submittedByDisplayName || tk.submittedByUsername} size="sm" />
                    <span>{tk.submittedByDisplayName || tk.submittedByUsername}</span>
                  </div>
                </td>
                <td>{tk.departmentName}</td>
                <td>
                  {tk.subcategoryName
                    ? <span className="chip">{tk.subcategoryName}</span>
                    : <span className="muted small">—</span>}
                </td>
                <td>
                  {tk.projectName
                    ? <span className="chip">{tk.projectName}</span>
                    : <span className="muted small">—</span>}
                </td>
                <td>
                  <StatusPill status={tk.status} />
                </td>
                <td className="actions-cell">
                  <div className="actions-cell-inner">
                    <select
                      className="select actions-select"
                      value={tk.status}
                      onChange={(e) => onStatusChange(tk, e.target.value as TicketStatus)}
                      title={t('common.status')}
                    >
                      <option value="IN_PROGRESS">{t('status.IN_PROGRESS')}</option>
                      <option value="REVIEW">{t('status.REVIEW')}</option>
                      <option value="COMPLETED">{t('status.COMPLETED')}</option>
                    </select>
                    <button className="btn btn-secondary btn-sm" onClick={() => setSelected(tk)}>
                      {t('common.view')}
                    </button>
                    <button className="btn btn-danger btn-sm" onClick={() => onDelete(tk)}>
                      {t('common.delete')}
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {data && data.totalPages > 1 && (
        <div className="row-between mt-2">
          <span className="muted small">
            {t('common.pageOf', { page: data.page + 1, total: data.totalPages, count: data.totalItems })}
          </span>
          <div className="row gap-2">
            <button
              className="btn btn-secondary btn-sm"
              disabled={page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >{t('common.prev')}</button>
            <button
              className="btn btn-secondary btn-sm"
              disabled={page + 1 >= data.totalPages}
              onClick={() => setPage((p) => p + 1)}
            >{t('common.next')}</button>
          </div>
        </div>
      )}

      <Modal
        open={!!selected}
        title={selected ? t('ticket.header', { id: selected.id }) : ''}
        onClose={() => setSelected(null)}
        footer={<button className="btn btn-secondary" onClick={() => setSelected(null)}>{t('common.close')}</button>}
      >
        {selected && <TicketDetails ticket={selected} />}
      </Modal>
    </div>
  );
}

function StatCard({
  label, value, suffix, icon, hero, suffixTone,
}: {
  label: string; value: number; suffix?: string; icon: React.ReactNode;
  hero: 'yellow' | 'blue' | 'amber';
  suffixTone?: 'danger';
}) {
  return (
    <div className={`stat-card hero-${hero}`}>
      <div className="stat-card-header">
        <span className="stat-card-label">{label}</span>
        <span className="stat-card-icon">{icon}</span>
      </div>
      <div className="stat-card-value" style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
        {value}
        {suffix && (
          <span style={{
            fontSize: 'var(--fs-sm)', fontWeight: 500,
            color: suffixTone === 'danger' ? 'var(--danger)' : undefined,
            opacity: 0.85,
          }}>{suffix}</span>
        )}
      </div>
    </div>
  );
}

function TicketDetails({ ticket }: { ticket: Ticket }) {
  const { t, lang } = useT();
  return (
    <div>
      <div style={{ marginBottom: '1rem' }}>
        <StatusPill status={ticket.status} />
      </div>
      <Detail label={t('ticket.submittedBy')} value={
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
          <Avatar name={ticket.submittedByDisplayName || ticket.submittedByUsername} size="sm" />
          <span>{ticket.submittedByDisplayName || ticket.submittedByUsername}</span>
        </div>
      } />
      <Detail label={t('ticket.department')} value={ticket.departmentName} />
      {ticket.subcategoryName && (
        <Detail label={t('user.submit.subcategory')} value={ticket.subcategoryName} />
      )}
      <Detail label={t('ticket.submittedAt')} value={new Date(ticket.submittedAt).toLocaleString(lang === 'ar' ? 'ar-EG' : undefined)} />
      {ticket.title && <Detail label={t('ticket.titleLabel')} value={ticket.title} />}
      {ticket.resources && ticket.resources.length > 0 ? (
        <Detail
          label={t('ticket.resources')}
          value={
            <ul className="inline-list-flush">
              {ticket.resources.map((r) => (
                <li key={r.id}>
                  {r.name && <span>{r.name}: </span>}
                  <a href={r.url} target="_blank" rel="noreferrer" dir="ltr">{r.url}</a>
                </li>
              ))}
            </ul>
          }
        />
      ) : (
        <>
          {ticket.websiteName && <Detail label={t('ticket.website')} value={ticket.websiteName} />}
          {ticket.websiteLink && (
            <Detail label={t('ticket.link')} value={<a href={ticket.websiteLink} target="_blank" rel="noreferrer">{ticket.websiteLink}</a>} />
          )}
        </>
      )}
      {ticket.documents && ticket.documents.length > 0 && (
        <Detail
          label={t('ticket.documents')}
          value={
            <ul className="inline-list-flush">
              {ticket.documents.map((d) => (
                <li key={d.id}>
                  <a href={ticketsApi.documentDownloadUrl(ticket.id, d.id)} target="_blank" rel="noreferrer">
                    {d.name || d.originalFilename}
                  </a>
                  <span className="muted small"> · {formatBytes(d.sizeBytes)}</span>
                </li>
              ))}
            </ul>
          }
        />
      )}
      {ticket.customValues.map((cv) => (
        <Detail key={cv.fieldId} label={cv.label} value={cv.value || <span className="muted">—</span>} />
      ))}
      <div className="field">
        <label className="field-label">{t('ticket.content')}</label>
        <div style={{
          padding: '0.85rem 1rem', background: 'var(--bg-sunken)',
          borderRadius: 'var(--radius)', whiteSpace: 'pre-wrap', fontSize: '0.9rem',
        }}>{ticket.content}</div>
      </div>
    </div>
  );
}

function Detail({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="field" style={{ marginBottom: '0.65rem' }}>
      <label className="field-label" style={{ marginBottom: 2 }}>{label}</label>
      <div>{value}</div>
    </div>
  );
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}
