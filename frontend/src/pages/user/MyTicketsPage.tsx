import { useEffect, useMemo, useState } from 'react';
import { extractError } from '../../api/client';
import { ticketsApi } from '../../api/resources';
import { SkeletonRows } from '../../components/SkeletonRows';
import type { Ticket, TicketPage } from '../../api/types';
import { IconClose } from '../../components/Icons';
import { Modal } from '../../components/Modal';
import { StatusPill } from '../../components/StatusPill';
import { useToast } from '../../components/toast/ToastContext';
import { useConfirm } from '../../components/ConfirmDialog';
import { useT } from '../../i18n';
import { pickLocalized } from '../../i18n/localized';

export function MyTicketsPage() {
  const { t, lang } = useT();
  const toast = useToast();
  const confirm = useConfirm();
  const [data, setData] = useState<TicketPage | null>(null);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Ticket | null>(null);
  const [q, setQ] = useState('');
  const [deletingDocId, setDeletingDocId] = useState<number | null>(null);

  async function onDeleteDocument(ticketId: number, docId: number) {
    const ok = await confirm({
      message: t('ticket.confirmDeleteDocument'),
      destructive: true,
    });
    if (!ok) return;
    setDeletingDocId(docId);
    try {
      await ticketsApi.removeDocument(ticketId, docId);
      setSelected((cur) => (cur && cur.id === ticketId && cur.documents
        ? { ...cur, documents: cur.documents.filter((d) => d.id !== docId) }
        : cur));
      setData((cur) => cur ? {
        ...cur,
        items: cur.items.map((tk) => tk.id === ticketId && tk.documents
          ? { ...tk, documents: tk.documents.filter((d) => d.id !== docId) }
          : tk),
      } : cur);
      toast.success(t('ticket.documentDeleted'));
    } catch (e) {
      toast.error(extractError(e));
    } finally {
      setDeletingDocId(null);
    }
  }

  useEffect(() => {
    setLoading(true);
    ticketsApi.listMine(page, 50)
      .then(setData)
      .catch((e) => setError(extractError(e)))
      .finally(() => setLoading(false));
  }, [page]);

  const filtered = useMemo(() => {
    if (!data) return [];
    const query = q.trim().toLowerCase();
    if (!query) return data.items;
    return data.items.filter((tk) => {
      const parts = [
        tk.title ?? '', tk.content,
        tk.websiteName ?? '', tk.websiteLink ?? '',
        tk.departmentName,
      ];
      for (const r of tk.resources ?? []) parts.push(r.name ?? '', r.url);
      for (const d of tk.documents ?? []) parts.push(d.name, d.originalFilename);
      return parts.join(' ').toLowerCase().includes(query);
    });
  }, [data, q]);

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('user.myTickets.title')}</h1>
          <p className="subtitle">{t('user.myTickets.subtitle')}</p>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="toolbar">
        <input
          className="input grow"
          type="search"
          placeholder={t('user.myTickets.searchPlaceholder')}
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />
        {q && <button className="btn btn-ghost btn-sm" onClick={() => setQ('')}>{t('common.clear')}</button>}
        {data && (
          <span className="muted small" style={{ marginInlineStart: 'auto' }}>
            {t('admin.tickets.resultsCount', { count: filtered.length, total: data.totalItems })}
          </span>
        )}
      </div>

      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr>
              <th>{t('admin.tickets.colContent')}</th>
              <th>{t('admin.tickets.colDepartment')}</th>
              <th>{t('common.status')}</th>
              <th>{t('admin.tickets.colWhen')}</th>
              <th style={{ textAlign: 'end' }}>{t('common.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <SkeletonRows cols={5} />
            ) : filtered.length === 0 ? (
              <tr><td colSpan={5} className="empty-state">
                {q ? t('admin.tickets.noMatches') : t('user.myTickets.empty')}
              </td></tr>
            ) : filtered.map((tk) => (
              <tr key={tk.id}>
                <td>
                  <div style={{ fontWeight: 500 }}>#{tk.id} · {tk.title || tk.websiteName || t('admin.tickets.untitled')}</div>
                  <div className="small muted truncate">{tk.content}</div>
                </td>
                <td>{pickLocalized(tk, 'departmentName', lang)}</td>
                <td>
                  <StatusPill status={tk.status} />
                </td>
                <td className="muted small">
                  {new Date(tk.submittedAt).toLocaleString(lang === 'ar' ? 'ar-EG' : undefined)}
                </td>
                <td className="actions-cell">
                  <button className="btn btn-secondary btn-sm" onClick={() => setSelected(tk)}>{t('common.view')}</button>
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
            <button className="btn btn-secondary btn-sm" disabled={page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}>{t('common.prev')}</button>
            <button className="btn btn-secondary btn-sm"
              disabled={page + 1 >= data.totalPages}
              onClick={() => setPage((p) => p + 1)}>{t('common.next')}</button>
          </div>
        </div>
      )}

      <Modal
        open={!!selected}
        title={selected ? t('ticket.header', { id: selected.id }) : ''}
        onClose={() => setSelected(null)}
        footer={<button className="btn btn-secondary" onClick={() => setSelected(null)}>{t('common.close')}</button>}
      >
        {selected && (
          <div>
            <div style={{ marginBottom: '1rem' }}>
              <StatusPill status={selected.status} />
            </div>
            <Detail label={t('ticket.department')} value={pickLocalized(selected, 'departmentName', lang)} />
            {selected.subcategoryName && (
              <Detail label={t('user.submit.subcategory')} value={pickLocalized(selected, 'subcategoryName', lang)} />
            )}
            <Detail label={t('ticket.submittedAt')} value={new Date(selected.submittedAt).toLocaleString(lang === 'ar' ? 'ar-EG' : undefined)} />
            {selected.title && <Detail label={t('ticket.titleLabel')} value={selected.title} />}
            {selected.resources && selected.resources.length > 0 ? (
              <Detail
                label={t('ticket.resources')}
                value={
                  <ul className="inline-list-flush">
                    {selected.resources.map((r) => (
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
                {selected.websiteName && <Detail label={t('ticket.website')} value={selected.websiteName} />}
                {selected.websiteLink && (
                  <Detail label={t('ticket.link')} value={<a href={selected.websiteLink} target="_blank" rel="noreferrer">{selected.websiteLink}</a>} />
                )}
              </>
            )}
            {selected.documents && selected.documents.length > 0 && (
              <Detail
                label={t('ticket.documents')}
                value={
                  <ul className="inline-list-flush">
                    {selected.documents.map((d) => (
                      <li key={d.id} style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                        <a href={ticketsApi.documentDownloadUrl(selected.id, d.id)} target="_blank" rel="noreferrer">
                          {d.name || d.originalFilename}
                        </a>
                        <span className="muted small">· {formatBytes(d.sizeBytes)}</span>
                        <button
                          type="button"
                          className="btn btn-ghost btn-sm"
                          onClick={() => onDeleteDocument(selected.id, d.id)}
                          disabled={deletingDocId === d.id}
                          title={t('ticket.deleteDocument')}
                          aria-label={t('ticket.deleteDocument')}
                        >
                          <IconClose size={12} />
                        </button>
                      </li>
                    ))}
                  </ul>
                }
              />
            )}
            {selected.customValues.map((cv) => (
              <Detail key={cv.fieldId} label={cv.label} value={cv.value || <span className="muted">—</span>} />
            ))}
            <div className="field">
              <label className="field-label">{t('ticket.content')}</label>
              <div style={{
                padding: '0.75rem 1rem', background: 'var(--bg-sunken)',
                borderRadius: 'var(--radius)', whiteSpace: 'pre-wrap', fontSize: '0.9rem',
              }}>{selected.content}</div>
            </div>
          </div>
        )}
      </Modal>
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
