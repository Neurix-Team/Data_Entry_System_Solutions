import { FormEvent, useEffect, useMemo, useState } from 'react';
import { extractError } from '../../api/client';
import {
  superApi, type ApiTokenRow, type CreateApiTokenResponse,
} from '../../api/super';
import { Modal } from '../../components/Modal';
import { useConfirm } from '../../components/ConfirmDialog';
import {
  IconKey, IconCopy, IconTrash, IconSearch, IconAlert,
} from '../../components/Icons';
import { useT } from '../../i18n';

type ExpiryPreset = 7 | 30 | 90 | 365 | 0 | -1;

const PRESETS: { value: ExpiryPreset; labelKey: string; fallback: string }[] = [
  { value: 7, labelKey: 'super.tokens.expiry7', fallback: '7 days' },
  { value: 30, labelKey: 'super.tokens.expiry30', fallback: '30 days' },
  { value: 90, labelKey: 'super.tokens.expiry90', fallback: '90 days' },
  { value: 365, labelKey: 'super.tokens.expiry365', fallback: '1 year' },
  { value: 0, labelKey: 'super.tokens.expiryNever', fallback: 'Never expires' },
  { value: -1, labelKey: 'super.tokens.expiryCustom', fallback: 'Custom…' },
];

/**
 * Personal-access tokens for the {@code /api/v1/export/*} data-pull API. Every token here
 * is read-only and cross-team by design — the intended consumer is an external Neurix
 * project (typically an AI ingest pipeline) that needs to hydrate a downstream database
 * from this system.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Plaintext is shown once — the reveal modal is the only chance to copy it. The
 *       backend stores only the SHA-256 hash.</li>
 *   <li>Rows carry a coloured status pill so operators can spot an expiring or revoked
 *       token at a glance without having to read the timestamps.</li>
 * </ul>
 */
export function SuperApiTokensPage() {
  const { t } = useT();
  const [rows, setRows] = useState<ApiTokenRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [revealed, setRevealed] = useState<CreateApiTokenResponse | null>(null);
  const [q, setQ] = useState('');
  const [statusFilter, setStatusFilter] = useState<'all' | 'active' | 'expired' | 'revoked'>('all');
  const confirm = useConfirm();

  const filtered = useMemo(() => {
    if (!rows) return [];
    const needle = q.trim().toLowerCase();
    return rows.filter((r) => {
      if (statusFilter === 'active' && !r.active) return false;
      if (statusFilter === 'revoked' && !r.revokedAt) return false;
      if (statusFilter === 'expired') {
        const expired = r.expiresAt && new Date(r.expiresAt).getTime() <= Date.now();
        if (!expired || r.revokedAt) return false;
      }
      if (!needle) return true;
      return (
        r.name + ' ' + r.prefix + ' ' + (r.createdByUsername ?? '')
      ).toLowerCase().includes(needle);
    });
  }, [rows, q, statusFilter]);

  useEffect(() => { void load(); }, []);

  async function load() {
    try {
      setRows(await superApi.apiTokens());
      setError(null);
    } catch (e) {
      setError(extractError(e));
    }
  }

  async function askRevoke(row: ApiTokenRow) {
    const ok = await confirm({
      title: t('super.tokens.revoke') || 'Revoke token',
      message: `${t('super.tokens.revokeWarn')
        || 'Any external system using this token will immediately lose access.'} "${row.name}"`,
      confirmLabel: t('super.tokens.revoke') || 'Revoke',
      destructive: true,
    });
    if (!ok) return;
    try {
      await superApi.revokeApiToken(row.id);
      await load();
    } catch (e) {
      setError(extractError(e));
    }
  }

  async function askDelete(row: ApiTokenRow) {
    const ok = await confirm({
      title: t('super.tokens.delete') || 'Delete token',
      message: `${t('super.tokens.deleteWarn')
        || 'Permanently removes the token record.'} "${row.name}"`,
      confirmLabel: t('common.delete'),
      destructive: true,
    });
    if (!ok) return;
    try {
      await superApi.deleteApiToken(row.id);
      await load();
    } catch (e) {
      setError(extractError(e));
    }
  }

  return (
    <div className="page super-page">
      <div className="page-header">
        <div>
          <h1>{t('super.tokens.title') || 'API tokens'}</h1>
          <p className="subtitle">
            {t('super.tokens.subtitle')
              || 'Personal-access tokens for the external data-pull API. Read-only, cross-team.'}
          </p>
        </div>
        <button type="button" className="btn btn-primary" onClick={() => setCreating(true)}>
          <IconKey size={16} /> {t('super.tokens.create') || 'Create token'}
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
            placeholder={t('super.tokens.search') || 'Search by name, prefix or creator…'}
            style={{ paddingInlineStart: 40 }}
          />
        </div>
        <div className="filter-pills">
          {(['all', 'active', 'expired', 'revoked'] as const).map((s) => (
            <button
              key={s}
              type="button"
              className={`filter-pill${statusFilter === s ? ' active' : ''}`}
              onClick={() => setStatusFilter(s)}
            >
              {s === 'all'
                ? (t('super.all') || 'All')
                : t(`super.tokens.filter.${s}`) || s}
            </button>
          ))}
        </div>
        {rows && <span className="result-count">{filtered.length} / {rows.length}</span>}
      </div>

      {!rows && <div className="muted" style={{ padding: 16 }}>{t('common.loading')}</div>}
      {rows && rows.length === 0 && (
        <div className="empty-state">
          {t('super.tokens.empty')
            || 'No tokens yet. Create one to let an external project pull data from this system.'}
        </div>
      )}
      {rows && rows.length > 0 && filtered.length === 0 && (
        <div className="empty-state">{t('super.noMatch') || 'No matches.'}</div>
      )}

      {rows && filtered.length > 0 && (
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th style={{ textAlign: 'start' }}>{t('super.tokens.name') || 'Name'}</th>
                <th style={{ textAlign: 'start' }}>{t('super.tokens.prefix') || 'Prefix'}</th>
                <th style={{ textAlign: 'start' }}>{t('super.tokens.createdBy') || 'Created by'}</th>
                <th style={{ textAlign: 'start' }}>{t('super.tokens.createdAt') || 'Created'}</th>
                <th style={{ textAlign: 'start' }}>{t('super.tokens.expiresAt') || 'Expires'}</th>
                <th style={{ textAlign: 'start' }}>{t('super.tokens.lastUsed') || 'Last used'}</th>
                <th style={{ textAlign: 'center' }}>{t('common.status')}</th>
                <th style={{ textAlign: 'end' }}></th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((r) => (
                <tr key={r.id}>
                  <td style={{ fontWeight: 600 }}>{r.name}</td>
                  <td><code style={{ fontSize: 12 }}>{r.prefix}…</code></td>
                  <td>{r.createdByUsername || <span className="muted">—</span>}</td>
                  <td>{formatDate(r.createdAt)}</td>
                  <td>{r.expiresAt
                    ? formatDate(r.expiresAt)
                    : <span className="muted">{t('super.tokens.expiryNever') || 'Never'}</span>}</td>
                  <td>{r.lastUsedAt
                    ? formatDate(r.lastUsedAt)
                    : <span className="muted">—</span>}</td>
                  <td style={{ textAlign: 'center' }}>
                    <StatusPill row={r} t={t} />
                  </td>
                  <td style={{ textAlign: 'end', whiteSpace: 'nowrap' }}>
                    {r.active && (
                      <button
                        type="button"
                        className="btn btn-ghost btn-sm"
                        onClick={() => askRevoke(r)}
                        title={t('super.tokens.revoke') || 'Revoke'}
                      >
                        {t('super.tokens.revoke') || 'Revoke'}
                      </button>
                    )}
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm"
                      onClick={() => askDelete(r)}
                      title={t('super.tokens.delete') || 'Delete'}
                      style={{ color: 'var(--danger)' }}
                    >
                      <IconTrash size={14} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {creating && (
        <CreateTokenModal
          onClose={() => setCreating(false)}
          onCreated={async (resp) => {
            setCreating(false);
            setRevealed(resp);
            await load();
          }}
        />
      )}

      {revealed && (
        <RevealTokenModal
          response={revealed}
          onClose={() => setRevealed(null)}
        />
      )}
    </div>
  );
}

function StatusPill({ row, t }: { row: ApiTokenRow; t: (k: string) => string }) {
  let label = t('common.active');
  let bg = 'var(--success-soft)';
  let color = 'var(--success-soft-text)';
  let dot = 'var(--success)';
  if (row.revokedAt) {
    label = t('super.tokens.status.revoked') || 'Revoked';
    bg = 'var(--danger-soft)';
    color = 'var(--danger-soft-text)';
    dot = 'var(--danger)';
  } else if (row.expiresAt && new Date(row.expiresAt).getTime() <= Date.now()) {
    label = t('super.tokens.status.expired') || 'Expired';
    bg = 'var(--status-completed-soft)';
    color = 'var(--status-completed-text)';
    dot = 'var(--status-completed)';
  }
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      padding: '3px 10px', borderRadius: 999,
      fontSize: 11, fontWeight: 600, background: bg, color,
    }}>
      <span aria-hidden="true" style={{
        width: 6, height: 6, borderRadius: 999, background: dot,
      }} />
      {label}
    </span>
  );
}

function CreateTokenModal({ onClose, onCreated }: {
  onClose: () => void;
  onCreated: (resp: CreateApiTokenResponse) => Promise<void>;
}) {
  const { t } = useT();
  const [name, setName] = useState('');
  const [preset, setPreset] = useState<ExpiryPreset>(30);
  const [customDays, setCustomDays] = useState<string>('60');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    setSubmitting(true);
    try {
      const expiresInDays = preset === -1
        ? Math.max(0, parseInt(customDays, 10) || 0)
        : (preset === 0 ? 0 : preset);
      const resp = await superApi.createApiToken({ name: name.trim(), expiresInDays });
      await onCreated(resp);
    } catch (e2) {
      setErr(extractError(e2));
      setSubmitting(false);
    }
  }

  return (
    <Modal open onClose={onClose} title={t('super.tokens.create') || 'Create API token'}>
      <form onSubmit={submit} className="super-modal-form">
        {err && <div className="alert alert-error">{err}</div>}
        <div className="super-modal-hint">
          <IconAlert size={16} />
          <span>
            {t('super.tokens.createHint')
              || 'You will see the full token exactly once after creation — copy it immediately.'}
          </span>
        </div>
        <div className="field">
          <label className="field-label">{t('super.tokens.name') || 'Name'}</label>
          <input
            className="input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            maxLength={150}
            placeholder={t('super.tokens.namePlaceholder') || 'e.g. AI ingest job'}
          />
        </div>
        <div className="field">
          <label className="field-label">{t('super.tokens.expiry') || 'Expiration'}</label>
          <div className="filter-pills" style={{ flexWrap: 'wrap', gap: 6 }}>
            {PRESETS.map((p) => (
              <button
                key={p.value}
                type="button"
                className={`filter-pill${preset === p.value ? ' active' : ''}`}
                onClick={() => setPreset(p.value)}
              >
                {t(p.labelKey) || p.fallback}
              </button>
            ))}
          </div>
          {preset === -1 && (
            <div style={{ marginTop: 8, display: 'flex', gap: 8, alignItems: 'center' }}>
              <input
                type="number"
                min={1}
                max={3650}
                className="input"
                value={customDays}
                onChange={(e) => setCustomDays(e.target.value)}
                style={{ maxWidth: 120 }}
              />
              <span className="muted">{t('super.tokens.days') || 'days'}</span>
            </div>
          )}
        </div>
        <div className="super-modal-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={submitting}>
            {t('common.cancel')}
          </button>
          <button type="submit" className="btn btn-primary" disabled={submitting || !name.trim()}>
            {submitting ? t('common.loading') : t('super.tokens.create') || 'Create token'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function RevealTokenModal({ response, onClose }: {
  response: CreateApiTokenResponse;
  onClose: () => void;
}) {
  const { t } = useT();
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(response.plaintext);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Older browsers / no clipboard API — fall back to selection.
      const el = document.getElementById('api-token-plaintext') as HTMLInputElement | null;
      el?.select();
    }
  }

  return (
    <Modal open onClose={onClose} title={t('super.tokens.reveal') || 'Copy your new token'}>
      <div className="super-modal-form">
        <div className="super-modal-hint is-warning">
          <IconAlert size={16} />
          <span>
            {t('super.tokens.revealWarn')
              || 'This is the only time the full token will be shown. Store it somewhere safe.'}
          </span>
        </div>
        <div className="field">
          <label className="field-label">{response.token.name}</label>
          <div style={{ display: 'flex', gap: 8 }}>
            <input
              id="api-token-plaintext"
              className="input"
              readOnly
              value={response.plaintext}
              onFocus={(e) => e.currentTarget.select()}
              style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 13 }}
            />
            <button type="button" className="btn btn-primary" onClick={copy}>
              <IconCopy size={14} /> {copied
                ? (t('super.tokens.copied') || 'Copied!')
                : (t('super.tokens.copy') || 'Copy')}
            </button>
          </div>
        </div>
        <div style={{ fontSize: 12, color: 'var(--text-tertiary)' }}>
          {t('super.tokens.useHint') || 'Use as a Bearer token:'}
          <pre style={{
            marginTop: 6, padding: 12, background: 'var(--bg-sunken)',
            borderRadius: 6, fontSize: 12, overflow: 'auto',
          }}>{`curl -H "Authorization: Bearer ${response.plaintext}" \\
  ${apiOrigin()}/api/v1/export/tickets`}</pre>
        </div>
        <div className="super-modal-actions">
          <button type="button" className="btn btn-primary" onClick={onClose}>
            {t('common.close') || 'Close'}
          </button>
        </div>
      </div>
    </Modal>
  );
}

function apiOrigin(): string {
  try {
    return window.location.origin;
  } catch {
    return 'https://your-domain';
  }
}

function formatDate(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}
