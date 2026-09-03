import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { extractError } from '../../api/client';
import { superApi, type ExplorerManifest, type ExplorerQuery } from '../../api/super';
import { IconAlert, IconCheck, IconClose, IconDatabase, IconDownload, IconFolder } from '../../components/Icons';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/toast/ToastContext';
import { useT } from '../../i18n';
import {
  downloadToDirectory, folderSegments, formatBytes, formatSeconds, initialProgress, pickDirectory,
  supportsDirectoryPicker, type DownloadOptions, type DownloadProgress,
} from './folderDownload';
import '../../styles/download-center.css';

const RING_R = 104;
const RING_C = 2 * Math.PI * RING_R;
const TREE_LIMIT = 40;

interface Props {
  open: boolean;
  onClose: () => void;
  /** The explorer's current filters — the download mirrors exactly what the table shows. */
  query: ExplorerQuery;
  /** Human-readable labels of the active filters, shown as chips in the dialog. */
  filterLabels: string[];
}

/**
 * "Download files" for the super-admin data explorer.
 *
 * Step 1 is a small options dialog (folder preview, sub-folder / text / resume toggles and
 * the destination). Step 2 is a full-screen progress overlay with an animated ring,
 * throughput, ETA and the file currently being written. Chromium browsers write straight
 * into a folder the operator picks; everything else falls back to a server-built ZIP.
 */
export function DownloadCenter({ open, onClose, query, filterLabels }: Props) {
  const { t } = useT();
  const toast = useToast();
  const [manifest, setManifest] = useState<ExplorerManifest | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  // Defaults: the files exactly as uploaded — original names, no sidecar notes.
  const [opts, setOpts] = useState<DownloadOptions>({ subcategoryFolders: false, prefixNames: false, includeText: false, skipExisting: true });
  const [progress, setProgress] = useState<DownloadProgress | null>(null);
  const [zipNotice, setZipNotice] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const canPick = supportsDirectoryPicker();

  const cleanQuery = useMemo<ExplorerQuery>(() => {
    const q: ExplorerQuery = {};
    if (query.teamId) q.teamId = query.teamId;
    if (query.projectId) q.projectId = query.projectId;
    if (query.userId) q.userId = query.userId;
    if (query.from) q.from = query.from;
    if (query.to) q.to = query.to;
    if (query.search) q.search = query.search;
    return q;
  }, [query.teamId, query.projectId, query.userId, query.from, query.to, query.search]);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setManifest(null);
    setLoadError(null);
    superApi.explorerManifest(cleanQuery, false)
      .then((m) => { if (!cancelled) setManifest(m); })
      .catch((e) => { if (!cancelled) setLoadError(extractError(e)); });
    return () => { cancelled = true; };
  }, [open, cleanQuery]);

  // Folder preview: Project → Department [→ Subcategory] with file counts and sizes.
  const tree = useMemo(() => {
    if (!manifest) return [];
    const map = new Map<string, { segs: string[]; files: number; bytes: number }>();
    for (const f of manifest.files) {
      const segs = folderSegments(f.projectName, f.departmentName, f.subcategoryName, opts.subcategoryFolders);
      for (let depth = 1; depth <= segs.length; depth++) {
        const key = segs.slice(0, depth).join('/');
        const node = map.get(key) ?? { segs: segs.slice(0, depth), files: 0, bytes: 0 };
        node.files++; node.bytes += f.sizeBytes || 0;
        map.set(key, node);
      }
    }
    return [...map.values()].sort((a, b) => a.segs.join('/').localeCompare(b.segs.join('/')));
  }, [manifest, opts.subcategoryFolders]);

  async function startFolder() {
    if (!manifest) return;
    let root;
    try {
      root = await pickDirectory();
    } catch (e) {
      toast.error(extractError(e, t('super.data.download.pickerDenied')));
      return;
    }
    if (!root) return;
    const ac = new AbortController();
    abortRef.current = ac;
    setProgress(initialProgress(root.name, manifest));
    try {
      const full = opts.includeText ? await superApi.explorerManifest(cleanQuery, true) : manifest;
      const result = await downloadToDirectory(root, full, opts, setProgress, ac.signal);
      if (result.phase === 'done') {
        toast.success(t('super.data.download.successToast', {
          files: result.filesDone - result.filesFailed, name: root.name,
        }));
      }
    } catch (e) {
      setProgress((p) => p ? { ...p, phase: 'error', currentFile: null, failures: [...p.failures, { path: '—', reason: extractError(e) }] } : p);
    }
  }

  function startZip() {
    const url = superApi.explorerArchiveUrl(cleanQuery, opts);
    const a = document.createElement('a');
    a.href = url;
    a.setAttribute('download', '');
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    a.remove();
    setZipNotice(true);
    window.setTimeout(() => { setZipNotice(false); onClose(); }, 2800);
  }

  function cancel() { abortRef.current?.abort(); }
  function finish() { setProgress(null); abortRef.current = null; onClose(); }

  const running = !!progress && (progress.phase === 'preparing' || progress.phase === 'downloading' || progress.phase === 'finishing');

  // Escape cancels a running download, or dismisses the finished overlay.
  useEffect(() => {
    if (!progress) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      if (running) cancel(); else finish();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [progress, running]);

  return (
    <>
      <Modal
        open={open && !progress}
        onClose={onClose}
        title={t('super.data.download.title')}
      >
        <p className="muted" style={{ marginTop: 0, fontSize: 13, lineHeight: 1.5 }}>
          {t('super.data.download.subtitle')}
        </p>

        {filterLabels.length > 0 && (
          <div className="dlc-filters">
            {filterLabels.map((l) => <span key={l} className="dlc-filter">{l}</span>)}
          </div>
        )}

        {loadError && <div className="alert alert-error">{loadError}</div>}

        <div className="dlc-summary">
          <div className="dlc-summary-icon"><IconDatabase size={20} /></div>
          <div style={{ minWidth: 0 }}>
            <div className="dlc-summary-title">
              {manifest
                ? (manifest.totalFiles === 0
                  ? t('super.data.download.noFiles')
                  : t('super.data.download.summary', {
                    files: manifest.totalFiles.toLocaleString(),
                    size: formatBytes(manifest.totalBytes),
                    tickets: manifest.totalTickets.toLocaleString(),
                  }))
                : t('super.data.download.loadingSummary')}
            </div>
            <div className="dlc-summary-sub">{t('super.data.download.layout')}</div>
          </div>
        </div>

        {tree.length > 0 && (
          <>
            <div className="dlc-section">{t('super.data.download.preview')}</div>
            <div className="dlc-tree">
              {tree.slice(0, TREE_LIMIT).map((n) => (
                <div
                  key={n.segs.join('/')}
                  className={`dlc-tree-row ${n.segs.length === 1 ? 'is-project' : n.segs.length === 2 ? 'is-dept' : 'is-sub'}`}
                >
                  <IconFolder size={14} />
                  <span className="dlc-tree-name">{n.segs[n.segs.length - 1]}</span>
                  <span className="dlc-tree-meta">{n.files.toLocaleString()} · {formatBytes(n.bytes)}</span>
                </div>
              ))}
              {tree.length > TREE_LIMIT && (
                <div className="dlc-tree-more">{t('super.data.download.moreFolders', { n: tree.length - TREE_LIMIT })}</div>
              )}
            </div>
          </>
        )}

        <div className="dlc-section">{t('super.data.download.options')}</div>
        <div className="dlc-opts">
          <Toggle
            checked={opts.subcategoryFolders}
            onChange={(v) => setOpts((o) => ({ ...o, subcategoryFolders: v }))}
            label={t('super.data.download.optSubcategory')}
          />
          <Toggle
            checked={opts.prefixNames}
            onChange={(v) => setOpts((o) => ({ ...o, prefixNames: v }))}
            label={t('super.data.download.optPrefix')}
            hint={t('super.data.download.optPrefixHint')}
          />
          <Toggle
            checked={opts.includeText}
            onChange={(v) => setOpts((o) => ({ ...o, includeText: v }))}
            label={t('super.data.download.optText')}
            hint={t('super.data.download.optTextHint')}
          />
          <Toggle
            checked={opts.skipExisting}
            onChange={(v) => setOpts((o) => ({ ...o, skipExisting: v }))}
            label={t('super.data.download.optSkip')}
            hint={t('super.data.download.optSkipHint')}
            disabled={!canPick}
          />
        </div>

        <div className="dlc-dests">
          <button
            type="button"
            className="dlc-dest is-primary"
            onClick={startFolder}
            disabled={!manifest || manifest.totalFiles === 0 || !canPick}
            title={canPick ? undefined : t('super.data.download.unsupported')}
          >
            <span className="dlc-dest-title"><IconFolder size={16} /> {t('super.data.download.chooseFolder')}</span>
            <span className="dlc-dest-hint">
              {canPick ? t('super.data.download.chooseFolderHint') : t('super.data.download.unsupported')}
            </span>
          </button>
          <button
            type="button"
            className="dlc-dest"
            onClick={startZip}
            disabled={!manifest || manifest.totalFiles === 0}
          >
            <span className="dlc-dest-title"><IconDownload size={16} /> {t('super.data.download.zip')}</span>
            <span className="dlc-dest-hint">{t('super.data.download.zipHint')}</span>
          </button>
        </div>

        {zipNotice && (
          <div className="dlc-zip-note" style={{ marginTop: 14 }}>
            <span className="spinner dark" aria-hidden="true" />
            {t('super.data.download.zipStarted')}
          </div>
        )}
      </Modal>

      {progress && createPortal(
        <ProgressOverlay progress={progress} running={running} onCancel={cancel} onClose={finish} t={t} />,
        document.body,
      )}
    </>
  );
}

function Toggle({ checked, onChange, label, hint, disabled }: {
  checked: boolean; onChange: (v: boolean) => void; label: string; hint?: string; disabled?: boolean;
}) {
  return (
    <label className="dlc-opt" style={disabled ? { opacity: .55, cursor: 'not-allowed' } : undefined}>
      <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      <span className="dlc-knob" aria-hidden="true" />
      <span className="dlc-opt-text">
        {label}
        {hint && <span className="dlc-opt-hint">{hint}</span>}
      </span>
    </label>
  );
}

function ProgressOverlay({ progress, running, onCancel, onClose, t }: {
  progress: DownloadProgress;
  running: boolean;
  onCancel: () => void;
  onClose: () => void;
  t: (key: string, params?: Record<string, string | number>) => string;
}) {
  const p = progress;
  const pct = p.bytesTotal > 0
    ? Math.min(1, p.bytesDone / p.bytesTotal)
    : (p.filesTotal > 0 ? Math.min(1, p.filesDone / p.filesTotal) : 0);
  const shown = p.phase === 'done' ? 1 : pct;
  const filePct = p.currentFileTotal > 0 ? Math.min(1, p.currentFileBytes / p.currentFileTotal) : 0;
  const elapsed = (performance.now() - p.startedAt) / 1000;

  const statusKey = {
    preparing: 'preparing', downloading: 'downloading', finishing: 'finishing',
    done: 'done', cancelled: 'cancelled', error: 'failed',
  }[p.phase];

  const okFiles = p.filesDone - p.filesFailed;

  return (
    <div className="dlc-overlay" role="dialog" aria-modal="true" aria-live="polite" aria-label={t('super.data.download.title')}>
      <div className={`dlc-card dlc-${p.phase}`}>
        <div className="dlc-ring-wrap">
          <svg className="dlc-ring" viewBox="0 0 240 240" aria-hidden="true">
            <defs>
              <linearGradient id="dlcGrad" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stopColor="#0a3f9c" />
                <stop offset=".55" stopColor="#0f5fd1" />
                <stop offset="1" stopColor="#22c3d9" />
              </linearGradient>
              <linearGradient id="dlcGradOk" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stopColor="#0e9f7c" />
                <stop offset="1" stopColor="#22c3d9" />
              </linearGradient>
              <linearGradient id="dlcGradWarn" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0" stopColor="#f0a020" />
                <stop offset="1" stopColor="#e04562" />
              </linearGradient>
            </defs>
            <circle className="dlc-track" cx="120" cy="120" r={RING_R} />
            <circle
              className="dlc-bar"
              cx="120" cy="120" r={RING_R}
              strokeDasharray={RING_C}
              strokeDashoffset={RING_C * (1 - shown)}
            />
          </svg>
          <div className="dlc-halo" aria-hidden="true" />
          <div className="dlc-center">
            {p.phase === 'done' && <div className="dlc-check"><IconCheck size={48} /></div>}
            {p.phase === 'error' && <div className="dlc-check is-warn"><IconAlert size={44} /></div>}
            {p.phase === 'cancelled' && <div className="dlc-check is-muted"><IconClose size={44} /></div>}
            {running && (
              <>
                <div className="dlc-pct">{Math.floor(shown * 100)}<span>%</span></div>
                <div className="dlc-sub">{p.filesDone.toLocaleString()} / {p.filesTotal.toLocaleString()}</div>
              </>
            )}
          </div>
        </div>

        <div className="dlc-status">
          {t(`super.data.download.${statusKey}`)}
          <small>
            {p.phase === 'done' && t('super.data.download.savedTo', { name: p.rootName })}
            {p.phase === 'error' && t('super.data.download.failedSummary', { ok: okFiles, failed: p.failures.length })}
            {p.phase === 'cancelled' && t('super.data.download.cancelledSummary', { ok: okFiles })}
            {running && t('super.data.download.into', { name: p.rootName })}
          </small>
        </div>

        <div className="dlc-stats">
          <Stat label={t('super.data.download.files')} value={`${okFiles.toLocaleString()} / ${p.filesTotal.toLocaleString()}`} />
          <Stat label={t('super.data.download.data')} value={`${formatBytes(p.bytesDone)} / ${formatBytes(p.bytesTotal)}`} />
          <Stat label={t('super.data.download.speed')} value={running && p.speedBps > 0 ? formatBytes(p.speedBps) + '/s' : (running ? '…' : formatBytes(elapsed > 0 ? p.bytesDone / elapsed : 0) + '/s')} />
          <Stat label={running ? t('super.data.download.timeLeft') : t('super.data.download.elapsed')} value={running ? formatSeconds(p.etaSeconds) : formatSeconds(elapsed)} />
        </div>

        {running && (
          <div className="dlc-current">
            <div className="dlc-current-label">
              {p.phase === 'finishing' ? t('super.data.download.finishing') : t('super.data.download.current')}
              {p.filesSkipped > 0 && ` · ${t('super.data.download.skipped', { n: p.filesSkipped })}`}
              {p.filesFailed > 0 && ` · ${t('super.data.download.failedCount', { n: p.filesFailed })}`}
            </div>
            <div className="dlc-file">{p.currentFile ?? '…'}</div>
            <div className="dlc-mini"><i style={{ transform: `scaleX(${filePct.toFixed(3)})` }} /></div>
            {p.recent.length > 0 && (
              <ul className="dlc-recent" aria-label={t('super.data.download.recent')}>
                {p.recent.map((r) => (
                  <li key={r.path} className={r.skipped ? 'is-skipped' : ''}>
                    <span className="dlc-recent-icon"><IconCheck size={12} /></span>
                    <span className="dlc-recent-path">{r.path}</span>
                    <span className="dlc-recent-meta">{r.skipped ? t('super.data.download.skippedOne') : formatBytes(r.bytes)}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        {!running && p.failures.length > 0 && (
          <div className="dlc-failures">
            {p.failures.slice(0, 8).map((f, i) => <div key={i}>{f.path} — {f.reason}</div>)}
            {p.failures.length > 8 && <div>+{p.failures.length - 8}</div>}
          </div>
        )}

        <div className="dlc-actions">
          {running
            ? <button type="button" className="btn btn-ghost" onClick={onCancel}>{t('super.data.download.cancel')}</button>
            : <button type="button" className="btn btn-primary" onClick={onClose}>{t('super.data.download.close')}</button>}
        </div>
      </div>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="dlc-stat">
      <div className="dlc-stat-label">{label}</div>
      <div className="dlc-stat-value" title={value}>{value}</div>
    </div>
  );
}
