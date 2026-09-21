import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { extractError } from '../api/client';
import { searchApi } from '../api/resources';
import type { SearchHits } from '../api/types';
import { useAuth } from '../context/AuthContext';
import { useT } from '../i18n';
import { pickLocalized } from '../i18n/localized';
import { Modal } from './Modal';

interface Props {
  open: boolean;
  onClose: () => void;
}

interface Hit {
  key: string;
  icon: string;
  label: string;
  sub?: string;
  to: string;
}

/**
 * Ctrl+K palette (B4): one box that reaches every corner of the workspace —
 * entries, projects, departments, subcategories, teammates (admins) — plus a set
 * of quick links so it doubles as a command menu when the query is empty.
 */
export function CommandPalette({ open, onClose }: Props) {
  const { t, lang } = useT();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [hits, setHits] = useState<SearchHits | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [cursor, setCursor] = useState(0);
  const listRef = useRef<HTMLDivElement | null>(null);
  const isAdmin = user?.role === 'ADMIN' || user?.role === 'SUPER_ADMIN';

  useEffect(() => {
    if (!open) {
      setQuery('');
      setHits(null);
      setError(null);
      setCursor(0);
    }
  }, [open]);

  // Debounced server search — a palette, not a report: bounded results.
  useEffect(() => {
    if (!open) return;
    const q = query.trim();
    if (q.length < 2) {
      setHits(null);
      setLoading(false);
      setError(null);
      return;
    }
    const ctl = new AbortController();
    setLoading(true);
    const id = window.setTimeout(() => {
      searchApi.query(q, ctl.signal)
        .then((r) => { setHits(r); setError(null); })
        .catch((e) => {
          if ((e as { name?: string })?.name === 'CanceledError') return;
          setError(extractError(e));
        })
        .finally(() => setLoading(false));
    }, 250);
    return () => { window.clearTimeout(id); ctl.abort(); };
  }, [query, open]);

  const quickLinks = useMemo<Hit[]>(() => {
    const links: Hit[] = [
      { key: 'q-dashboard', icon: '🏠', label: t('nav.dashboard'), to: isAdmin ? '/admin' : '/dashboard' },
      { key: 'q-submit', icon: '📝', label: t('nav.submit'), to: '/submit' },
      { key: 'q-mytasks', icon: '🗂', label: t('nav.myTasks'), to: '/my-tickets' },
      { key: 'q-assignments', icon: '✅', label: t('nav.myAssignments'), to: '/assignments' },
      { key: 'q-chat', icon: '💬', label: t('nav.chat'), to: '/chat' },
      { key: 'q-bin', icon: '🗑️', label: t('nav.recycleBin'), to: isAdmin ? '/admin/recycle-bin' : '/recycle-bin' },
    ];
    if (isAdmin) {
      links.push(
        { key: 'q-users', icon: '👥', label: t('nav.users'), to: '/admin/users' },
        { key: 'q-projects', icon: '📁', label: t('nav.projects'), to: '/admin/projects' },
        { key: 'q-reports', icon: '📊', label: t('nav.reports'), to: '/admin/reports' },
        { key: 'q-import', icon: '⬆️', label: t('nav.import'), to: '/admin/import' },
        { key: 'q-announcements', icon: '📣', label: t('nav.announcements'), to: '/admin/announcements' },
        { key: 'q-workload', icon: '⚖️', label: t('nav.workload'), to: '/admin/workload' },
        { key: 'q-quality', icon: '🎯', label: t('nav.quality'), to: '/admin/quality' },
        { key: 'q-weekly', icon: '🗓️', label: t('nav.weeklyReport'), to: '/admin/weekly' },
      );
    }
    return links;
  }, [t, isAdmin]);

  const results = useMemo<Hit[]>(() => {
    const q = query.trim();
    if (q.length < 2 || !hits) return quickLinks;
    const out: Hit[] = hits.tickets.map((tk) => ({
      key: `t-${tk.id}`,
      icon: '📄',
      label: pickLocalized({ title: tk.title, titleEn: tk.titleEn, titleAr: tk.titleAr }, 'title', lang) || '—',
      sub: `${t(`status.${tk.status}`)} · ${tk.submittedByUsername ?? ''}`,
      to: isAdmin ? '/admin/tickets' : '/my-tickets',
    }));
    out.push(...hits.projects.map((p) => ({
      key: `p-${p.id}`,
      icon: '📁',
      label: p.name,
      to: (isAdmin ? '/admin/project-folders/' : '/project-folders/') + p.id,
    })));
    out.push(...hits.departments.map((d) => ({
      key: `d-${d.id}`,
      icon: '🏢',
      label: d.name,
      to: isAdmin ? '/admin/subcategories' : '/submit',
    })));
    out.push(...hits.subcategories.map((s) => ({
      key: `s-${s.id}`,
      icon: '🏷️',
      label: s.name,
      to: isAdmin ? '/admin/subcategories' : '/submit',
    })));
    out.push(...hits.users.map((u) => ({
      key: `u-${u.id}`,
      icon: '👤',
      label: u.displayName || u.username,
      sub: u.username,
      to: '/admin/users',
    })));
    return out;
  }, [hits, query, quickLinks, t, lang, isAdmin]);

  const go = useCallback((hit: Hit) => {
    onClose();
    navigate(hit.to);
  }, [navigate, onClose]);

  useEffect(() => { setCursor(0); }, [hits, query]);

  const onKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setCursor((c) => Math.min(c + 1, results.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setCursor((c) => Math.max(c - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const hit = results[cursor];
      if (hit) go(hit);
    }
  }, [results, cursor, go]);

  useEffect(() => {
    const el = listRef.current?.querySelector<HTMLElement>(`[data-idx="${cursor}"]`);
    el?.scrollIntoView({ block: 'nearest' });
  }, [cursor]);

  // ── RENDER_SECTION ──

  return (
    <Modal open={open} title={t('search.title')} onClose={onClose}>
      <div onKeyDown={onKeyDown}>
        <input
          autoFocus
          type="search"
          className="input"
          placeholder={t('common.searchGlobal')}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={{ width: '100%', marginBottom: 8 }}
        />
        {error && <div className="alert alert-error" style={{ margin: '0 0 8px' }}>{error}</div>}
        <div ref={listRef} style={{ maxHeight: 340, overflow: 'auto' }} role="listbox">
          {results.length === 0 && !loading && (
            <div className="muted" style={{ padding: '1.25rem', textAlign: 'center' }}>
              {t('search.empty')}
            </div>
          )}
          {results.map((hit, idx) => (
            <button
              key={hit.key}
              type="button"
              data-idx={idx}
              role="option"
              aria-selected={idx === cursor}
              onMouseEnter={() => setCursor(idx)}
              onClick={() => go(hit)}
              style={{
                width: '100%',
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                padding: '0.55rem 0.7rem',
                textAlign: lang === 'ar' ? 'right' : 'left',
                background: idx === cursor ? 'var(--bg-hover)' : 'transparent',
                border: 'none',
                borderRadius: 8,
                cursor: 'pointer',
                color: 'inherit',
              }}
            >
              <span aria-hidden="true" style={{ flexShrink: 0 }}>{hit.icon}</span>
              <span style={{ flex: 1, minWidth: 0 }}>
                <span style={{ display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {hit.label}
                </span>
                {hit.sub && (
                  <span className="muted small" style={{ display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {hit.sub}
                  </span>
                )}
              </span>
            </button>
          ))}
          {loading && <div className="muted small" style={{ padding: '0.4rem 0.7rem' }}>{t('common.loading')}</div>}
        </div>
        <div className="muted small" style={{ borderTop: '1px solid var(--border)', paddingTop: 8, marginTop: 6 }}>
          ↑ ↓ {t('search.navHint')} · Enter {t('search.openHint')} · Esc {t('search.closeHint')}
        </div>
      </div>
    </Modal>
  );
}