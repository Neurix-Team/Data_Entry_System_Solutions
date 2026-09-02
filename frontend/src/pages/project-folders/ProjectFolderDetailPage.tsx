import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { extractError } from '../../api/client';
import { projectFoldersApi, ticketsApi } from '../../api/resources';
import type { ProjectFolderDetail, Ticket } from '../../api/types';
import { Avatar } from '../../components/Avatar';
import { useConfirm } from '../../components/ConfirmDialog';
import { IconCheck, IconClose, IconFolder, IconPlus } from '../../components/Icons';
import { Modal } from '../../components/Modal';
import { StatusPill } from '../../components/StatusPill';
import { useToast } from '../../components/toast/ToastContext';
import { useAuth } from '../../context/AuthContext';
import { useT } from '../../i18n';
import { pickLocalized } from '../../i18n/localized';
import { QuickUploadModal } from './QuickUploadModal';

interface AuthorGroup {
  userId: number;
  username: string;
  displayName: string | null;
  tickets: Ticket[];
}

/**
 * Contents of one folder — every ticket branched off the project. Admins can approve
 * pending tickets in place; users see status pills that flip to "Approved" once the
 * admin acts, so the same page tells both audiences the same story.
 */
export function ProjectFolderDetailPage() {
  const params = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const projectId = Number(params.projectId);
  const { t, lang } = useT();
  const { user } = useAuth();
  const toast = useToast();
  const isAdmin = user?.role === 'ADMIN' || user?.role === 'SUPER_ADMIN';

  const [detail, setDetail] = useState<ProjectFolderDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [approving, setApproving] = useState<number | null>(null);
  const [bulkApproving, setBulkApproving] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [selected, setSelected] = useState<Ticket | null>(null);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [freshlyApproved, setFreshlyApproved] = useState<Set<number>>(new Set());
  const [deletingDocId, setDeletingDocId] = useState<number | null>(null);
  const confirm = useConfirm();

  const backHref = isAdmin ? '/admin/project-folders' : '/project-folders';

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const data = await projectFoldersApi.detail(projectId);
      setDetail(data);
    } catch (e) {
      setError(extractError(e));
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    if (!Number.isFinite(projectId) || projectId <= 0) {
      navigate(backHref, { replace: true });
      return;
    }
    refresh();
  }, [projectId, refresh, navigate, backHref]);

  // Group tickets by submitter so admins scan by person rather than by row. Users only
  // ever see their own tickets, so the grouping collapses to a single group for them.
  const groups: AuthorGroup[] = useMemo(() => {
    if (!detail) return [];
    const byUser = new Map<number, AuthorGroup>();
    for (const tk of detail.tickets) {
      const g = byUser.get(tk.submittedById);
      if (g) {
        g.tickets.push(tk);
      } else {
        byUser.set(tk.submittedById, {
          userId: tk.submittedById,
          username: tk.submittedByUsername,
          displayName: pickLocalized(tk, 'submittedByDisplayName', lang) || tk.submittedByUsername,
          tickets: [tk],
        });
      }
    }
    return Array.from(byUser.values());
  }, [detail, lang]);

  const summary = useMemo(() => {
    if (!detail) return { total: 0, pending: 0, approved: 0 };
    let pending = 0, approved = 0;
    for (const tk of detail.tickets) {
      if (tk.status === 'COMPLETED') approved++; else pending++;
    }
    return { total: detail.tickets.length, pending, approved };
  }, [detail]);

  const pendingIds = useMemo(
    () => detail?.tickets.filter((tk) => tk.status !== 'COMPLETED').map((tk) => tk.id) ?? [],
    [detail],
  );
  const allPendingSelected = pendingIds.length > 0 && pendingIds.every((id) => selectedIds.has(id));

  function toggleTicket(id: number, checked: boolean) {
    setSelectedIds((old) => {
      const next = new Set(old);
      if (checked) next.add(id); else next.delete(id);
      return next;
    });
  }

  function toggleMany(ids: number[], checked: boolean) {
    setSelectedIds((old) => {
      const next = new Set(old);
      ids.forEach((id) => checked ? next.add(id) : next.delete(id));
      return next;
    });
  }

  async function onApprove(ticket: Ticket) {
    setApproving(ticket.id);
    try {
      const updated = await ticketsApi.approve(ticket.id);
      setDetail((cur) => cur ? {
        ...cur,
        tickets: cur.tickets.map((t) => t.id === ticket.id ? updated : t),
      } : cur);
      setFreshlyApproved((prev) => {
        const next = new Set(prev);
        next.add(ticket.id);
        return next;
      });
      toggleTicket(ticket.id, false);
      toast.success(
        lang === 'ar'
          ? `تم اعتماد التذكرة #${ticket.id}`
          : `Ticket #${ticket.id} approved`,
      );
    } catch (e) {
      toast.error(extractError(e));
    } finally {
      setApproving(null);
    }
  }

  async function onBulkApprove() {
    const ids = pendingIds.filter((id) => selectedIds.has(id));
    if (ids.length === 0) return;
    const ok = await confirm({
      message: lang === 'ar'
        ? `اعتماد ${ids.length} عنصر مرة واحدة؟`
        : `Approve ${ids.length} selected item(s) at once?`,
    });
    if (!ok) return;
    setBulkApproving(true);
    try {
      const result = await ticketsApi.approveMany(ids);
      const byId = new Map(result.tickets.map((ticket) => [ticket.id, ticket]));
      setDetail((cur) => cur ? {
        ...cur,
        tickets: cur.tickets.map((ticket) => byId.get(ticket.id) ?? ticket),
      } : cur);
      setSelected((cur) => cur ? (byId.get(cur.id) ?? cur) : cur);
      setFreshlyApproved((old) => new Set([...old, ...ids]));
      setSelectedIds(new Set());
      toast.success(lang === 'ar'
        ? `تم اعتماد ${result.approved} عنصر بنجاح`
        : `${result.approved} item(s) approved`);
    } catch (e) {
      toast.error(extractError(e));
    } finally {
      setBulkApproving(false);
    }
  }

  /**
   * Delete one attachment from a project-folder ticket. In this view, each ticket is
   * usually just a wrapper around one uploaded file (the quick-upload flow creates one
   * ticket per file), so removing that last file should also delete the wrapper ticket
   * — otherwise the folder keeps a titleless empty row that the user has no way to
   * interact with. Multi-file tickets (rare here, common in the submit flow) keep the
   * partial-delete behaviour: only the picked doc goes.
   *
   * <p>Optimistic UI: we strip the doc (and, if applicable, the whole ticket) locally
   * before the network round-trip returns. On failure we roll back with a refresh so
   * the on-screen state can't diverge from the DB.
   */
  async function onDeleteDocument(ticketId: number, docId: number) {
    const ok = await confirm({ message: t('ticket.confirmDeleteDocument'), destructive: true });
    if (!ok) return;
    const target = detail?.tickets.find((tk) => tk.id === ticketId);
    const isLastFile = !!target && (target.documents?.length ?? 0) <= 1;
    setDeletingDocId(docId);
    try {
      if (isLastFile) {
        // Delete the whole ticket in one call — the server cascades docs + disk.
        // Prefer the admin endpoint when the caller is an admin (works for any ticket);
        // fall back to /user/tickets/{id} which the server permits only for the ticket owner.
        if (isAdmin) {
          await ticketsApi.remove(ticketId);
        } else {
          await ticketsApi.removeMine(ticketId);
        }
        setDetail((cur) => cur ? {
          ...cur,
          tickets: cur.tickets.filter((tk) => tk.id !== ticketId),
        } : cur);
        setSelected((cur) => (cur && cur.id === ticketId ? null : cur));
      } else {
        await ticketsApi.removeDocument(ticketId, docId);
        setDetail((cur) => cur ? {
          ...cur,
          tickets: cur.tickets.map((tk) => tk.id === ticketId && tk.documents
            ? { ...tk, documents: tk.documents.filter((d) => d.id !== docId) }
            : tk),
        } : cur);
        setSelected((cur) => (cur && cur.id === ticketId && cur.documents
          ? { ...cur, documents: cur.documents.filter((d) => d.id !== docId) }
          : cur));
      }
      toast.success(t('ticket.documentDeleted'));
    } catch (e) {
      toast.error(extractError(e));
      // On failure, sync back with the server so the optimistic state doesn't linger wrong.
      refresh();
    } finally {
      setDeletingDocId(null);
    }
  }

  const projectName = detail ? pickLocalized(detail, 'projectName', lang) : '';

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', marginBottom: '0.35rem' }}>
            <Link to={backHref} className="btn btn-ghost btn-sm">
              {lang === 'ar' ? '← رجوع للمجلدات' : '← All folders'}
            </Link>
          </div>
          <h1 style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
            <IconFolder size={26} /> {projectName || '…'}
          </h1>
          <p className="subtitle">
            {lang === 'ar'
              ? `${summary.total} تذكرة · ${summary.pending} بانتظار الموافقة · ${summary.approved} موافَق عليها`
              : `${summary.total} entries · ${summary.pending} pending · ${summary.approved} approved`}
          </p>
        </div>
        <div className="row gap-2">
          <button
            className="btn btn-primary"
            type="button"
            onClick={() => setUploadOpen(true)}
            disabled={loading}
          >
            <IconPlus size={14} />{' '}
            {lang === 'ar' ? 'رفع ملفات دفعة واحدة' : 'Upload multiple files'}
          </button>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {loading ? (
        <div className="card" style={{ padding: '1.5rem' }}>
          <span className="muted">{t('common.loading')}</span>
        </div>
      ) : !detail || detail.tickets.length === 0 ? (
        <div className="card" style={{ padding: '2.5rem', textAlign: 'center' }}>
          <IconFolder size={44} />
          <p className="muted" style={{ marginTop: '0.75rem' }}>
            {lang === 'ar'
              ? 'المجلد فاضي لسه. اضغط "رفع ملفات دفعة واحدة" علشان تبدأ.'
              : 'This folder is empty. Click "Upload multiple files" to get started.'}
          </p>
        </div>
      ) : (
        <div className="stack" style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
          {isAdmin && pendingIds.length > 0 && (
            <div className="card row-between" style={{ padding: '0.85rem 1.25rem', gap: 12, flexWrap: 'wrap' }}>
              <label className="row gap-2" style={{ cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={allPendingSelected}
                  onChange={(e) => toggleMany(pendingIds, e.target.checked)}
                  disabled={bulkApproving || approving != null}
                />
                <span>{lang === 'ar' ? 'تحديد كل العناصر المعلّقة' : 'Select all pending items'}</span>
              </label>
              <button
                type="button"
                className="btn btn-primary"
                onClick={onBulkApprove}
                disabled={bulkApproving || approving != null || selectedIds.size === 0}
              >
                <IconCheck size={14} />{' '}
                {bulkApproving
                  ? (lang === 'ar' ? 'جاري الاعتماد…' : 'Approving…')
                  : (lang === 'ar' ? `اعتماد المحدد (${selectedIds.size})` : `Approve selected (${selectedIds.size})`)}
              </button>
            </div>
          )}
          {groups.map((g) => (
            <AuthorSection
              key={g.userId}
              group={g}
              isAdmin={isAdmin}
              approving={approving}
              freshlyApproved={freshlyApproved}
              onApprove={onApprove}
              onView={setSelected}
              deletingDocId={deletingDocId}
              onDeleteDocument={onDeleteDocument}
              selectedIds={selectedIds}
              selectionDisabled={bulkApproving || approving != null}
              onToggle={toggleTicket}
              onToggleGroup={toggleMany}
            />
          ))}
        </div>
      )}

      <Modal
        open={!!selected}
        title={selected ? t('ticket.header', { id: selected.id }) : ''}
        onClose={() => setSelected(null)}
        footer={
          <div className="row gap-2" style={{ justifyContent: 'space-between', width: '100%' }}>
            {isAdmin && selected && selected.status !== 'COMPLETED' && (
              <button
                type="button"
                className="btn btn-primary"
                disabled={approving === selected.id}
                onClick={() => onApprove(selected)}
              >
                <IconCheck size={14} />{' '}
                {approving === selected.id
                  ? (lang === 'ar' ? 'جاري الاعتماد…' : 'Approving…')
                  : (lang === 'ar' ? 'اعتماد التذكرة' : 'Approve ticket')}
              </button>
            )}
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => setSelected(null)}
              style={{ marginInlineStart: 'auto' }}
            >
              {t('common.close')}
            </button>
          </div>
        }
      >
        {selected && (
          <TicketBody
            ticket={selected}
            deletingDocId={deletingDocId}
            onDeleteDocument={onDeleteDocument}
          />
        )}
      </Modal>

      <QuickUploadModal
        open={uploadOpen}
        projectId={projectId}
        onClose={() => setUploadOpen(false)}
        onCreated={({ created, failed }) => {
          // Close the modal only on a clean run — if any file failed, keep it open so the
          // user can see which ones and retry without losing context.
          if (failed === 0) setUploadOpen(false);
          if (created > 0) refresh();
        }}
      />
    </div>
  );
}

function AuthorSection({
  group, isAdmin, approving, freshlyApproved, onApprove, onView,
  deletingDocId, onDeleteDocument, selectedIds, selectionDisabled, onToggle, onToggleGroup,
}: {
  group: AuthorGroup;
  isAdmin: boolean;
  approving: number | null;
  freshlyApproved: Set<number>;
  onApprove: (ticket: Ticket) => void;
  onView: (ticket: Ticket) => void;
  deletingDocId: number | null;
  onDeleteDocument: (ticketId: number, docId: number) => void;
  selectedIds: Set<number>;
  selectionDisabled: boolean;
  onToggle: (id: number, checked: boolean) => void;
  onToggleGroup: (ids: number[], checked: boolean) => void;
}) {
  const { lang, t } = useT();
  const pendingCount = group.tickets.filter((tk) => tk.status !== 'COMPLETED').length;
  const approvedCount = group.tickets.length - pendingCount;
  const groupPendingIds = group.tickets.filter((tk) => tk.status !== 'COMPLETED').map((tk) => tk.id);
  const groupSelected = groupPendingIds.length > 0 && groupPendingIds.every((id) => selectedIds.has(id));

  return (
    <div className="card" style={{ padding: '1.25rem 1.5rem' }}>
      <div className="row-between" style={{ marginBottom: '0.85rem' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
          <Avatar name={group.displayName || group.username} size="md" />
          <div>
            <div style={{ fontWeight: 600 }}>{group.displayName || group.username}</div>
            <div className="muted small">@{group.username}</div>
          </div>
        </div>
        <div className="row gap-2">
          {isAdmin && groupPendingIds.length > 0 && (
            <label className="row gap-2 small" style={{ cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={groupSelected}
                onChange={(e) => onToggleGroup(groupPendingIds, e.target.checked)}
                disabled={selectionDisabled}
              />
              {lang === 'ar' ? 'تحديد المجموعة' : 'Select group'}
            </label>
          )}
          <span className="chip">
            {lang === 'ar' ? `${pendingCount} بانتظار` : `${pendingCount} pending`}
          </span>
          <span className="chip">
            {lang === 'ar' ? `${approvedCount} موافَق` : `${approvedCount} approved`}
          </span>
        </div>
      </div>

      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr>
              {isAdmin && <th style={{ width: 44 }}>{lang === 'ar' ? 'حدد' : 'Pick'}</th>}
              <th>{t('admin.tickets.colContent')}</th>
              <th style={{ width: 320 }}>{lang === 'ar' ? 'ملفات' : 'Files'}</th>
              <th style={{ width: 120 }}>{t('common.status')}</th>
              <th style={{ width: 170 }}>{t('admin.tickets.colWhen')}</th>
              <th style={{ textAlign: 'end', width: 180 }}>{t('common.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {group.tickets.map((tk) => {
              const justApproved = freshlyApproved.has(tk.id);
              const docs = tk.documents ?? [];
              return (
                <tr key={tk.id} style={justApproved ? { background: 'rgba(34, 197, 94, 0.06)' } : undefined}>
                  {isAdmin && (
                    <td>
                      <input
                        type="checkbox"
                        checked={selectedIds.has(tk.id)}
                        disabled={selectionDisabled || tk.status === 'COMPLETED'}
                        onChange={(e) => onToggle(tk.id, e.target.checked)}
                        aria-label={lang === 'ar' ? `تحديد العنصر ${tk.id}` : `Select item ${tk.id}`}
                      />
                    </td>
                  )}
                  <td>
                    <div style={{ fontWeight: 500 }}>
                      #{tk.id} · {tk.title || tk.websiteName || t('admin.tickets.untitled')}
                    </div>
                    {tk.content && (
                      <div className="small muted truncate">{tk.content}</div>
                    )}
                  </td>
                  <td>
                    {docs.length === 0 ? (
                      <span className="muted small">
                        {lang === 'ar' ? 'مفيش ملفات' : 'No files'}
                      </span>
                    ) : (
                      <ul className="inline-list-flush" style={{ margin: 0, padding: 0 }}>
                        {docs.map((d) => (
                          <li
                            key={d.id}
                            style={{
                              display: 'flex',
                              alignItems: 'center',
                              gap: '0.4rem',
                              padding: '0.2rem 0',
                            }}
                          >
                            <a
                              href={ticketsApi.documentDownloadUrl(tk.id, d.id)}
                              target="_blank"
                              rel="noreferrer"
                              className="truncate"
                              style={{ flex: 1, minWidth: 0 }}
                              title={d.name || d.originalFilename}
                            >
                              {d.name || d.originalFilename}
                            </a>
                            <span className="muted small" style={{ flexShrink: 0 }}>
                              {formatBytes(d.sizeBytes)}
                            </span>
                            <button
                              type="button"
                              className="btn btn-danger btn-sm"
                              onClick={() => onDeleteDocument(tk.id, d.id)}
                              disabled={deletingDocId === d.id}
                              title={t('ticket.deleteDocument')}
                              aria-label={t('ticket.deleteDocument')}
                              style={{ flexShrink: 0 }}
                            >
                              <IconClose size={12} />
                            </button>
                          </li>
                        ))}
                      </ul>
                    )}
                  </td>
                  <td>
                    <StatusPill status={tk.status} />
                    {justApproved && (
                      <div className="small" style={{ color: 'var(--success)', marginTop: 2 }}>
                        {lang === 'ar' ? '✓ اتحفظت في الداتا بيز' : '✓ Saved to database'}
                      </div>
                    )}
                  </td>
                  <td className="muted small">
                    {new Date(tk.submittedAt).toLocaleString(lang === 'ar' ? 'ar-EG' : undefined)}
                  </td>
                  <td className="actions-cell">
                    <div className="actions-cell-inner">
                      <button
                        type="button"
                        className="btn btn-secondary btn-sm"
                        onClick={() => onView(tk)}
                      >
                        {t('common.view')}
                      </button>
                      {isAdmin && tk.status !== 'COMPLETED' && (
                        <button
                          type="button"
                          className="btn btn-primary btn-sm"
                          disabled={approving === tk.id}
                          onClick={() => onApprove(tk)}
                        >
                          <IconCheck size={12} />{' '}
                          {approving === tk.id
                            ? (lang === 'ar' ? '…' : '…')
                            : (lang === 'ar' ? 'اعتماد' : 'Approve')}
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function TicketBody({
  ticket,
  deletingDocId,
  onDeleteDocument,
}: {
  ticket: Ticket;
  deletingDocId: number | null;
  onDeleteDocument: (ticketId: number, docId: number) => void;
}) {
  const { lang, t } = useT();
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
      <Detail label={t('ticket.department')} value={pickLocalized(ticket, 'departmentName', lang)} />
      {ticket.subcategoryName && (
        <Detail label={t('user.submit.subcategory')} value={pickLocalized(ticket, 'subcategoryName', lang)} />
      )}
      <Detail
        label={t('ticket.submittedAt')}
        value={new Date(ticket.submittedAt).toLocaleString(lang === 'ar' ? 'ar-EG' : undefined)}
      />
      {ticket.title && <Detail label={t('ticket.titleLabel')} value={ticket.title} />}
      {ticket.documents && ticket.documents.length > 0 && (
        <Detail
          label={t('ticket.documents')}
          value={
            <ul className="inline-list-flush">
              {ticket.documents.map((d) => (
                <li key={d.id} style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                  <a href={ticketsApi.documentDownloadUrl(ticket.id, d.id)} target="_blank" rel="noreferrer">
                    {d.name || d.originalFilename}
                  </a>
                  <span className="muted small">· {formatBytes(d.sizeBytes)}</span>
                  <button
                    type="button"
                    className="btn btn-danger btn-sm"
                    onClick={() => onDeleteDocument(ticket.id, d.id)}
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
      {ticket.content && (
        <div className="field">
          <label className="field-label">{t('ticket.content')}</label>
          <div style={{
            padding: '0.85rem 1rem', background: 'var(--bg-sunken)',
            borderRadius: 'var(--radius)', whiteSpace: 'pre-wrap', fontSize: '0.9rem',
          }}>{ticket.content}</div>
        </div>
      )}
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
