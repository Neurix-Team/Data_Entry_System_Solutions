import { useCallback, useEffect, useState } from 'react';
import { useAuth } from '../context/AuthContext';
import { extractError } from '../api/client';
import { recycleBinApi } from '../api/resources';
import type { RecycleBinItem, RecycleBinPage } from '../api/types';
import { useConfirm } from '../components/ConfirmDialog';
import { SkeletonRows } from '../components/SkeletonRows';
import { useToast } from '../components/toast/ToastContext';
import { useT } from '../i18n';

const PAGE_SIZE = 20;

/**
 * Recycle bin: soft-deleted entries (and, for admins, projects) with restore and
 * (admin) permanent delete. Users see only their own entries — the personal safety
 * net — while admins get the team-wide view including who deleted what.
 */
export function RecycleBinPage() {
  const { user } = useAuth();
  const { t, lang } = useT();
  const toast = useToast();
  const confirm = useConfirm();
  const isAdmin = user?.role === 'ADMIN' || user?.role === 'SUPER_ADMIN';
  const [tab, setTab] = useState<'tickets' | 'projects'>('tickets');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<RecycleBinPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true);
    setError(null);
    try {
      const result = isAdmin
        ? await recycleBinApi.adminList(tab, page, PAGE_SIZE, signal)
        : await recycleBinApi.myList(page, PAGE_SIZE, signal);
      setData(result);
    } catch (e) {
      if ((e as { name?: string })?.name === 'CanceledError') return;
      setError(extractError(e));
    } finally {
      setLoading(false);
    }
  }, [isAdmin, tab, page]);

  useEffect(() => {
    const ctl = new AbortController();
    load(ctl.signal);
    return () => ctl.abort();
  }, [load]);

  useEffect(() => { setPage(0); }, [tab, isAdmin]);

  function pickTitle(item: RecycleBinItem): string {
    const localized = lang === 'ar' ? (item.titleAr || item.title) : (item.titleEn || item.title);
    return localized || '—';
  }

  function statusLabel(item: RecycleBinItem): string {
    return item.kind === 'TICKET' ? t(`status.${item.status}`) : item.status;
  }

  async function onRestore(item: RecycleBinItem) {
    setBusyId(item.id);
    try {
      if (item.kind === 'PROJECT') await recycleBinApi.restoreProject(item.id);
      else await recycleBinApi.restoreTicket(item.id, isAdmin);
      toast.success(t('recycleBin.restored'));
      await load();
    } catch (e) {
      toast.error(extractError(e));
    } finally {
      setBusyId(null);
    }
  }

  async function onPurge(item: RecycleBinItem) {
    const ok = await confirm({
      title: t('recycleBin.purgeConfirmTitle'),
      message: t('recycleBin.purgeConfirmMsg', { name: pickTitle(item) }),
      confirmLabel: t('recycleBin.deleteForever'),
      destructive: true,
    });
    if (!ok) return;
    setBusyId(item.id);
    try {
      if (item.kind === 'PROJECT') await recycleBinApi.purgeProject(item.id);
      else await recycleBinApi.purgeTicket(item.id);
      toast.success(t('recycleBin.purged'));
      await load();
    } catch (e) {
      toast.error(extractError(e, t('recycleBin.purgeBlocked')));
    } finally {
      setBusyId(null);
    }
  }

  // ── RENDER_SECTION ──

  const items = data?.items ?? [];
  const totalPages = Math.max(1, data?.totalPages ?? 1);

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>{isAdmin ? t('recycleBin.titleAdmin') : t('recycleBin.titleMine')}</h1>
          <p className="muted">
            {isAdmin
              ? t('recycleBin.subtitleAdmin', { days: t('recycleBin.retentionDays') })
              : t('recycleBin.subtitleMine', { days: t('recycleBin.retentionDays') })}
          </p>
        </div>
      </div>

      {isAdmin && (
        <div className="tab-row" style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
          {(['tickets', 'projects'] as const).map((key) => (
            <button
              key={key}
              type="button"
              className={`btn btn-sm ${tab === key ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => setTab(key)}
            >
              {t(`recycleBin.tabs.${key}`)}
            </button>
          ))}
        </div>
      )}

      {error && <div className="alert alert-error" style={{ margin: '0 0 12px' }}>{error}</div>}

      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr>
              <th>{t('recycleBin.item')}</th>
              <th>{t('recycleBin.status')}</th>
              {isAdmin && tab === 'tickets' && <th>{t('recycleBin.owner')}</th>}
              <th>{t('recycleBin.deletedBy')}</th>
              <th>{t('recycleBin.deletedAt')}</th>
              <th>{t('recycleBin.remaining')}</th>
              <th style={{ textAlign: 'center' }}>{t('common.actions')}</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <SkeletonRows cols={isAdmin && tab === 'tickets' ? 7 : 6} />
            ) : items.length === 0 ? (
              <tr>
                <td colSpan={isAdmin && tab === 'tickets' ? 7 : 6}>
                  <div className="empty-state" style={{ padding: '2.5rem 1rem', textAlign: 'center' }}>
                    <div style={{ fontSize: 28 }}>🗑️</div>
                    <div className="muted" style={{ marginTop: 6 }}>
                      {t('recycleBin.empty')}
                    </div>
                  </div>
                </td>
              </tr>
            ) : (
              items.map((item) => (
                <tr key={`${item.kind}-${item.id}`}>
                  <td>
                    <div style={{ fontWeight: 600 }}>{pickTitle(item)}</div>
                    <div className="muted small">
                      {item.kind === 'PROJECT' ? t('recycleBin.kindProject') : t('recycleBin.kindTicket')}
                      {' · #'}{item.id}
                    </div>
                  </td>
                  <td><span className="muted small">{statusLabel(item)}</span></td>
                  {isAdmin && tab === 'tickets' && (
                    <td><span className="small">{item.ownerUsername || '—'}</span></td>
                  )}
                  <td><span className="small">{item.deletedByName || '—'}</span></td>
                  <td>
                    <span className="muted small">
                      {new Date(item.deletedAt).toLocaleString(lang === 'ar' ? 'ar-EG' : undefined)}
                    </span>
                  </td>
                  <td>
                    <span
                      className="small"
                      style={{
                        display: 'inline-block',
                        padding: '2px 10px',
                        borderRadius: 999,
                        fontWeight: 600,
                        background: item.daysLeft <= 3 ? 'var(--danger-soft)' : 'var(--bg-muted)',
                        color: item.daysLeft <= 3 ? 'var(--danger-soft-text)' : 'var(--text-secondary)',
                      }}
                    >
                      {t('recycleBin.daysLeft', { n: item.daysLeft })}
                    </span>
                  </td>
                  <td style={{ textAlign: 'center', whiteSpace: 'nowrap' }}>
                    <button
                      type="button"
                      className="btn btn-sm btn-secondary"
                      disabled={busyId === item.id}
                      onClick={() => onRestore(item)}
                    >
                      ↺ {t('recycleBin.restore')}
                    </button>
                    {isAdmin && (
                      <button
                        type="button"
                        className="btn btn-sm btn-danger"
                        style={{ marginInlineStart: 6 }}
                        disabled={busyId === item.id}
                        onClick={() => onPurge(item)}
                      >
                        {t('recycleBin.deleteForever')}
                      </button>
                    )}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {(data?.totalPages ?? 0) > 1 && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 14 }}>
          <button
            type="button"
            className="btn btn-sm btn-secondary"
            disabled={page <= 0 || loading}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
          >
            {t('common.prev')}
          </button>
          <span className="muted small">
            {t('common.pageOf', { page: page + 1, total: totalPages, count: data?.totalItems ?? 0 })}
          </span>
          <button
            type="button"
            className="btn btn-sm btn-secondary"
            disabled={page + 1 >= totalPages || loading}
            onClick={() => setPage((p) => p + 1)}
          >
            {t('common.next')}
          </button>
        </div>
      )}
    </div>
  );
}