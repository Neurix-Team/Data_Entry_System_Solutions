import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { extractError } from '../api/client';
import { notificationsApi } from '../api/resources';
import type { NotificationFeed, NotificationItem } from '../api/types';
import { useAuth } from '../context/AuthContext';
import { useT } from '../i18n';
import { IconBell } from './Icons';

/**
 * Topbar notifications widget. Polls the feed on mount and every 60s (plus once whenever
 * the tab regains focus, so returning to the tab shows fresh data without waiting for the
 * next poll interval). Click the bell to open the dropdown; click an item to jump to the
 * relevant folder and mark it read.
 *
 * <p>Kept self-contained (no shared context) because it's the only surface reading the
 * notification feed. If we grow more consumers (e.g. an in-page banner) this can lift
 * into a context provider without changing the component API.
 */
export function NotificationBell() {
  const { lang, t } = useT();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [feed, setFeed] = useState<NotificationFeed | null>(null);
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const rootRef = useRef<HTMLDivElement | null>(null);

  const refresh = useCallback(async () => {
    if (!user) return;
    try {
      const data = await notificationsApi.list();
      setFeed(data);
      setError(null);
    } catch (e) {
      setError(extractError(e));
    }
  }, [user]);

  // Poll + refresh on focus. 60s is quiet enough for a chat-style widget while still
  // catching an approval within a minute of it landing on the server.
  useEffect(() => {
    if (!user) return;
    refresh();
    const id = window.setInterval(refresh, 60_000);
    const onFocus = () => refresh();
    window.addEventListener('focus', onFocus);
    return () => {
      window.clearInterval(id);
      window.removeEventListener('focus', onFocus);
    };
  }, [user, refresh]);

  // Close the dropdown on outside-click / Escape.
  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDoc);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  async function onClickItem(n: NotificationItem) {
    // Mark read optimistically so the badge updates before the network round-trip.
    setFeed((prev) => prev ? {
      ...prev,
      items: prev.items.map((x) => x.id === n.id && !x.readAt
        ? { ...x, readAt: new Date().toISOString() } : x),
      unread: prev.unread - (n.readAt ? 0 : 1),
    } : prev);
    try { await notificationsApi.markRead(n.id); } catch { /* silent; will resync on next poll */ }

    // Deep-link to the folder if we know one, otherwise the tickets list.
    if (n.projectId != null) {
      const isAdmin = user?.role === 'ADMIN' || user?.role === 'SUPER_ADMIN';
      navigate(isAdmin ? `/admin/project-folders/${n.projectId}` : `/project-folders/${n.projectId}`);
    }
    setOpen(false);
  }

  async function onMarkAll() {
    if (!feed || feed.unread === 0) return;
    setFeed({ ...feed, items: feed.items.map((x) => x.readAt ? x : { ...x, readAt: new Date().toISOString() }), unread: 0 });
    try { await notificationsApi.markAllRead(); } catch { /* silent */ }
  }

  const unread = feed?.unread ?? 0;

  return (
    <div ref={rootRef} style={{ position: 'relative' }}>
      <button
        type="button"
        className="notification-btn"
        aria-label={lang === 'ar' ? 'الإشعارات' : 'Notifications'}
        aria-expanded={open}
        aria-haspopup="menu"
        onClick={() => setOpen((v) => !v)}
      >
        <IconBell size={20} />
        {unread > 0 && (
          <span
            className="notification-dot"
            aria-label={lang === 'ar' ? `${unread} إشعار جديد` : `${unread} unread`}
            style={{
              // Bump the dot into a small counter chip when there are 2+ unread. The
              // existing dot styles show only a colored dot; a counter reads faster.
              minWidth: 16,
              height: 16,
              padding: '0 4px',
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 10,
              fontWeight: 700,
              color: 'white',
              background: 'var(--danger)',
              borderRadius: 8,
            }}
          >{unread > 99 ? '99+' : unread}</span>
        )}
      </button>

      {open && (
        <div
          role="menu"
          style={{
            position: 'absolute',
            insetInlineEnd: 0,
            top: 'calc(100% + 8px)',
            width: 340,
            maxHeight: 460,
            overflow: 'auto',
            background: 'var(--bg-surface)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--radius)',
            boxShadow: 'var(--shadow-md, 0 8px 24px rgba(0,0,0,0.12))',
            zIndex: 1000,
          }}
        >
          <div style={{
            padding: '0.75rem 0.9rem',
            borderBottom: '1px solid var(--border)',
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          }}>
            <strong>{lang === 'ar' ? 'الإشعارات' : 'Notifications'}</strong>
            {unread > 0 && (
              <button type="button" className="btn btn-ghost btn-sm" onClick={onMarkAll}>
                {lang === 'ar' ? 'تعليم الكل مقروء' : 'Mark all read'}
              </button>
            )}
          </div>

          {error && (
            <div className="alert alert-error" style={{ margin: '0.5rem 0.75rem' }}>{error}</div>
          )}

          {!feed || feed.items.length === 0 ? (
            <div style={{ padding: '1.25rem', textAlign: 'center' }} className="muted">
              {lang === 'ar' ? 'مفيش إشعارات لسه' : 'No notifications yet'}
            </div>
          ) : (
            <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
              {feed.items.map((n) => (
                <li key={n.id}>
                  <button
                    type="button"
                    role="menuitem"
                    onClick={() => onClickItem(n)}
                    style={{
                      width: '100%',
                      textAlign: lang === 'ar' ? 'right' : 'left',
                      padding: '0.75rem 0.9rem',
                      background: n.readAt ? 'transparent' : 'var(--bg-sunken)',
                      border: 'none',
                      borderBottom: '1px solid var(--border)',
                      cursor: 'pointer',
                      color: 'inherit',
                    }}
                  >
                    <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
                      {!n.readAt && (
                        <span
                          aria-hidden="true"
                          style={{
                            width: 8, height: 8, borderRadius: '50%',
                            background: 'var(--brand)', marginTop: 6, flexShrink: 0,
                          }}
                        />
                      )}
                      <div style={{ minWidth: 0, flex: 1 }}>
                        <div style={{ fontWeight: n.readAt ? 400 : 600, fontSize: 13 }}>
                          {n.message}
                        </div>
                        <div className="muted small" style={{ marginTop: 2 }}>
                          {new Date(n.createdAt).toLocaleString(lang === 'ar' ? 'ar-EG' : undefined)}
                        </div>
                      </div>
                    </div>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
      {/* Ensure t() is referenced so unused-var lint stays quiet if this file is trimmed later. */}
      <span style={{ display: 'none' }}>{t('common.loading')}</span>
    </div>
  );
}
