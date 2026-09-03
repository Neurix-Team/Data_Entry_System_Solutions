import { useCallback, useEffect, useRef, useState } from 'react';
import { extractError } from '../../api/client';
import {
  ChunkedUploadUnsupportedError,
  DEFAULT_CHUNK_PARALLELISM,
  SpeedMeter,
  etaFor,
  uploadFileChunked,
  type UploadProgress,
} from '../../api/chunkedUpload';
import { departmentsApi, projectFoldersApi } from '../../api/resources';
import type { Department } from '../../api/types';
import { IconClose, IconPlus } from '../../components/Icons';
import { Modal } from '../../components/Modal';
import { ProgressRing } from '../../components/ProgressRing';
import { UploadHud } from '../../components/UploadHud';
import { useToast } from '../../components/toast/ToastContext';
import { useT } from '../../i18n';
import { pickLocalized } from '../../i18n/localized';
import { extractTitleFromFile, titleFromFilename } from '../../utils/titleFromFile';
import { formatBytes, formatDuration, formatEta, formatSpeed } from '../../utils/uploadFormat';

interface Props {
  open: boolean;
  projectId: number;
  onClose: () => void;
  /** Fired after the server processed the batch. Passes the numeric result so the parent
   *  can show a summary (e.g. "3 of 4 uploaded"); parent decides whether to close. */
  onCreated: (result: { created: number; failed: number }) => void;
}

interface Row {
  id: number;
  file: File;
  /** Editable title — auto-filled from the file on drop. */
  title: string;
}

/**
 * Files travelling at once. Each one fans out into {@link DEFAULT_CHUNK_PARALLELISM}
 * chunk requests, so two files keep eight connections busy — enough to fill a fast pipe
 * without starving the rest of the app behind the browser's per-host cap.
 */
const FILE_CONCURRENCY = 2;

/** Soft tints cycled across the per-file icon chips, same rotation the dept cards use. */
const CHIP_TINTS = [
  { bg: 'var(--brand-soft)', fg: 'var(--brand-soft-text)' },
  { bg: 'var(--accent-cyan-soft)', fg: 'var(--accent-cyan-soft-text)' },
  { bg: 'var(--dept-mini-3)', fg: '#4a2fa8' },
  { bg: 'var(--success-soft)', fg: 'var(--success-soft-text)' },
];

type RowStatus = 'uploading' | 'finalizing' | 'done' | 'failed';

/** Whole-batch meter shown above the file list while a batch is in flight (and after). */
interface BatchStats {
  count: number;
  loaded: number;
  total: number;
  bytesPerSecond: number;
  etaSeconds: number | null;
  startedAt: number;
  finishedAt: number | null;
  created: number;
  failed: number;
}

/**
 * Multi-file quick-upload. Every file becomes its own ticket in the current project
 * with the title auto-extracted from the file. Titles are still editable so a
 * mis-guessed name can be corrected inline before send.
 *
 * <p>Each file goes up through the chunked uploader ({@link uploadFileChunked}): fixed-size
 * chunks, several in flight at once, per-chunk retry, then a server-side finalize that
 * creates the ticket and attaches the file in one transaction. {@link FILE_CONCURRENCY}
 * files travel at a time. A backend without the session endpoints is detected on the
 * first request and the modal falls back to the one-request-per-file multipart path.
 */
export function QuickUploadModal({ open, projectId, onClose, onCreated }: Props) {
  const { lang, t } = useT();
  const toast = useToast();
  const ar = lang === 'ar';
  const [rows, setRows] = useState<Row[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [failures, setFailures] = useState<Array<{ filename: string; reason: string }>>([]);
  /** Per-row live progress, keyed by Row.id. Only rows that started have entries. */
  const [progress, setProgress] = useState<Record<number, UploadProgress>>({});
  const [rowStatus, setRowStatus] = useState<Record<number, RowStatus>>({});
  /** Wall-clock milliseconds each finished row took, for the "done in 4.2 s" line. */
  const [rowTime, setRowTime] = useState<Record<number, number>>({});
  const [batch, setBatch] = useState<BatchStats | null>(null);
  const [dragActive, setDragActive] = useState(false);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [departmentsLoading, setDepartmentsLoading] = useState(false);
  const [departmentId, setDepartmentId] = useState<number | ''>('');
  const inputRef = useRef<HTMLInputElement | null>(null);
  const nextId = useRef(1);
  // Live per-row byte counts sit in a ref so every progress tick sums plain numbers
  // instead of re-deriving the batch total from React state.
  const rowLoaded = useRef<Record<number, number>>({});
  const batchMeter = useRef(new SpeedMeter());

  useEffect(() => {
    if (!open) {
      setRows([]);
      setError(null);
      setFailures([]);
      setSubmitting(false);
      setProgress({});
      setRowStatus({});
      setRowTime({});
      setBatch(null);
      setDepartments([]);
      setDepartmentId('');
      rowLoaded.current = {};
      batchMeter.current = new SpeedMeter();
      return;
    }
    // Pull the department list the moment the modal opens. Scoped per-project by the
    // server (USER only sees their member-project departments; ADMIN sees all). No
    // auto-select — we force the caller to pick explicitly so batches don't silently
    // land in the wrong section, which was the whole point of adding this picker.
    const ctrl = new AbortController();
    setDepartmentsLoading(true);
    departmentsApi.userList(projectId, ctrl.signal)
      .then((list) => {
        setDepartments(list);
      })
      .catch((err) => {
        if (err?.name !== 'CanceledError' && err?.code !== 'ERR_CANCELED') {
          setError(extractError(err));
        }
      })
      .finally(() => setDepartmentsLoading(false));
    return () => ctrl.abort();
  }, [open, projectId]);

  const addFiles = useCallback((files: FileList | File[]) => {
    const list = Array.from(files);
    if (list.length === 0) return;
    // Filename first, instantly. The PDF-metadata pass below is async and only replaces
    // a scanner-default title ("scan0001") once it has read the document's header.
    const fresh: Row[] = list.map((f) => ({
      id: nextId.current++,
      file: f,
      title: titleFromFilename(f.name),
    }));
    setRows((prev) => [...prev, ...fresh]);
    for (const row of fresh) {
      void extractTitleFromFile(row.file).then((better) => {
        if (!better || better === row.title) return;
        // Only swap if the user hasn't already edited the auto-filled title.
        setRows((prev) => prev.map((r) => (
          r.id === row.id && r.title === row.title ? { ...r, title: better } : r
        )));
      });
    }
  }, []);

  function onFilePick(e: React.ChangeEvent<HTMLInputElement>) {
    if (e.target.files) addFiles(e.target.files);
    e.target.value = '';
  }

  function onDrop(e: React.DragEvent<HTMLDivElement>) {
    e.preventDefault();
    setDragActive(false);
    if (e.dataTransfer.files) addFiles(e.dataTransfer.files);
  }

  function removeRow(id: number) {
    setRows((prev) => prev.filter((r) => r.id !== id));
  }

  function updateTitle(id: number, title: string) {
    setRows((prev) => prev.map((r) => r.id === id ? { ...r, title } : r));
  }

  function pushBatchProgress(totalBytes: number) {
    let loaded = 0;
    for (const v of Object.values(rowLoaded.current)) loaded += v;
    loaded = Math.min(loaded, totalBytes);
    const speed = batchMeter.current.push(loaded);
    setBatch((b) => b
      ? { ...b, loaded, bytesPerSecond: speed, etaSeconds: etaFor(loaded, totalBytes, speed) }
      : b);
  }

  async function onSubmit() {
    setError(null);
    setFailures([]);
    if (rows.length === 0) {
      setError(ar ? 'اختر ملف واحد على الأقل' : 'Pick at least one file');
      return;
    }
    // Force explicit picking whenever the project actually has departments to choose
    // from. The empty-project case (server auto-creates a default) is the only path
    // where we let this stay unset.
    if (departments.length > 0 && departmentId === '') {
      setError(ar ? 'اختر القسم أولاً' : 'Pick a department first');
      return;
    }

    setSubmitting(true);
    setProgress({});
    setRowStatus({});
    setRowTime({});
    rowLoaded.current = {};
    batchMeter.current = new SpeedMeter();
    const totalBytes = rows.reduce((n, r) => n + r.file.size, 0);
    setBatch({
      count: rows.length, loaded: 0, total: totalBytes, bytesPerSecond: 0, etaSeconds: null,
      startedAt: performance.now(), finishedAt: null, created: 0, failed: 0,
    });

    // The queue is a shared array the workers shift() from — single-threaded JS makes
    // that race-free between awaits.
    const queue = [...rows];
    const dept = departmentId === '' ? null : departmentId;
    let created = 0;
    const newFailures: Array<{ filename: string; reason: string }> = [];
    const failedRowIds = new Set<number>();

    async function uploadOne(row: Row) {
      const t0 = performance.now();
      setRowStatus((s) => ({ ...s, [row.id]: 'uploading' }));
      const onProgress = (p: UploadProgress) => {
        rowLoaded.current[row.id] = p.loaded;
        setProgress((s) => ({ ...s, [row.id]: p }));
        if (p.phase === 'finalizing') {
          setRowStatus((s) => (s[row.id] === 'uploading' ? { ...s, [row.id]: 'finalizing' } : s));
        }
        pushBatchProgress(totalBytes);
      };
      try {
        try {
          await uploadFileChunked({
            file: row.file,
            target: { kind: 'QUICK_UPLOAD', projectId, departmentId: dept, title: row.title.trim() },
            parallel: DEFAULT_CHUNK_PARALLELISM,
            onProgress,
          });
        } catch (err) {
          if (!(err instanceof ChunkedUploadUnsupportedError)) throw err;
          // Older backend without session endpoints: one multipart request per file.
          const result = await projectFoldersApi.quickUpload(
            projectId,
            [{ file: row.file, title: row.title.trim() }],
            dept,
            undefined,
            (fraction) => onProgress({
              phase: 'uploading',
              loaded: Math.round(fraction * row.file.size),
              total: row.file.size,
              fraction,
              bytesPerSecond: 0,
              etaSeconds: null,
            }),
          );
          if (result.failed > 0) {
            throw new Error(result.failures[0]?.reason || (ar ? 'فشل الرفع' : 'Upload failed'));
          }
        }
        created += 1;
        rowLoaded.current[row.id] = row.file.size;
        setRowTime((s) => ({ ...s, [row.id]: performance.now() - t0 }));
        setRowStatus((s) => ({ ...s, [row.id]: 'done' }));
      } catch (err) {
        const fallback = err instanceof Error && err.message
          ? err.message
          : (ar ? 'فشل الرفع' : 'Upload failed');
        newFailures.push({ filename: row.file.name, reason: extractError(err, fallback) });
        failedRowIds.add(row.id);
        setRowStatus((s) => ({ ...s, [row.id]: 'failed' }));
      }
      pushBatchProgress(totalBytes);
    }

    const workers = Array.from(
      { length: Math.min(FILE_CONCURRENCY, queue.length) },
      async () => {
        for (let row = queue.shift(); row; row = queue.shift()) {
          await uploadOne(row);
        }
      },
    );
    await Promise.all(workers);

    setBatch((b) => b
      ? { ...b, finishedAt: performance.now(), created, failed: newFailures.length, etaSeconds: 0 }
      : b);

    if (created > 0) {
      toast.success(
        ar
          ? `تم رفع ${created} ملف بنجاح`
          : `Uploaded ${created} file${created === 1 ? '' : 's'}`,
      );
    }

    if (newFailures.length > 0) {
      setFailures(newFailures);
      // Also toast so the user notices even if they miss the inline list.
      toast.warning(
        ar
          ? `فشل رفع ${newFailures.length} ملف — راجع القائمة`
          : `${newFailures.length} file${newFailures.length === 1 ? '' : 's'} failed — check the list below`,
      );
      // Keep the modal open when there were failures so the user can retry only the
      // ones that failed (currently by re-picking them; a per-row retry could be added).
      // Trim successful rows so the visible list matches what still needs attention.
      setRows((prev) => prev.filter((r) => failedRowIds.has(r.id)));
    }

    setSubmitting(false);
    onCreated({ created, failed: newFailures.length });
  }

  const finishedCount = Object.values(rowStatus).filter((s) => s === 'done' || s === 'failed').length;

  function batchHud(b: BatchStats) {
    const elapsed = (b.finishedAt ?? performance.now()) - b.startedAt;
    const state = submitting
      ? (b.total > 0 && b.loaded >= b.total ? 'finalizing' : 'uploading')
      : (b.created > 0 ? 'done' : 'failed');
    const title = submitting
      ? (b.count === 1
        ? (ar ? 'جاري رفع الملف…' : 'Uploading file…')
        : (ar
          ? `جاري رفع ${b.count} ملفات · اكتمل ${finishedCount}`
          : `Uploading ${b.count} files · ${finishedCount} done`))
      : (b.created > 0
        ? (ar
          ? `تم رفع ${b.created} ${b.created === 1 ? 'ملف' : 'ملفات'} في ${formatDuration(elapsed, lang)}`
          : `Uploaded ${b.created} file${b.created === 1 ? '' : 's'} in ${formatDuration(elapsed, lang)}`)
        : (ar ? 'لم يتم رفع أي ملف' : 'Nothing was uploaded'));
    const note = !submitting && b.created > 0 && b.total > 0 && elapsed > 0
      ? (ar
        ? `${formatBytes(b.total)} بمتوسط ${formatSpeed(b.total / (elapsed / 1000), lang)}`
        : `${formatBytes(b.total)} at ${formatSpeed(b.total / (elapsed / 1000), lang)} average`)
      : undefined;
    return (
      <UploadHud
        lang={lang}
        size={72}
        state={state}
        title={title}
        note={note}
        progress={{
          phase: submitting ? 'uploading' : 'done',
          loaded: b.loaded,
          total: b.total,
          fraction: b.total > 0 ? Math.min(1, b.loaded / b.total) : 0,
          bytesPerSecond: b.bytesPerSecond,
          etaSeconds: b.etaSeconds,
        }}
      />
    );
  }

  function rowMeta(r: Row): { text: string; tone: '' | 'is-done' | 'is-failed' } | null {
    const status = rowStatus[r.id];
    const p = progress[r.id];
    if (!status) return null;
    if (status === 'uploading') {
      if (!p) return { text: ar ? 'جارٍ البدء…' : 'Starting…', tone: '' };
      const parts = [`${formatBytes(p.loaded)} / ${formatBytes(p.total)}`];
      if (p.bytesPerSecond > 0) parts.push(formatSpeed(p.bytesPerSecond, lang));
      if (p.etaSeconds != null) parts.push(formatEta(p.etaSeconds, lang));
      return { text: parts.join(' · '), tone: '' };
    }
    if (status === 'finalizing') {
      return { text: ar ? 'وصل كله — جارٍ المعالجة…' : 'All bytes landed — finalizing…', tone: '' };
    }
    if (status === 'done') {
      const ms = rowTime[r.id] ?? 0;
      return {
        text: ar ? `تم في ${formatDuration(ms, lang)}` : `Done in ${formatDuration(ms, lang)}`,
        tone: 'is-done',
      };
    }
    return { text: ar ? 'فشل' : 'Failed', tone: 'is-failed' };
  }

  return (
    <Modal
      open={open}
      title={ar ? 'رفع ملفات دفعة واحدة' : 'Upload multiple files'}
      onClose={submitting ? () => undefined : onClose}
      footer={
        <div className="row gap-2" style={{ justifyContent: 'flex-end', width: '100%' }}>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={onClose}
            disabled={submitting}
          >
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className={`btn btn-primary${submitting ? ' is-uploading' : ''}`}
            onClick={onSubmit}
            disabled={submitting || rows.length === 0}
          >
            {submitting
              ? (ar
                ? `جاري الرفع… ${finishedCount}/${batch?.count ?? rows.length}`
                : `Uploading… ${finishedCount}/${batch?.count ?? rows.length}`)
              : (ar
                ? `رفع ${rows.length} ${rows.length === 1 ? 'ملف' : 'ملفات'}`
                : `Upload ${rows.length} file${rows.length === 1 ? '' : 's'}`)}
          </button>
        </div>
      }
    >
      <p className="muted" style={{ marginTop: 0 }}>
        {ar
          ? 'اختار كل الملفات دفعة واحدة. هيتعمل تذكرة لكل ملف والعنوان هيتعبى تلقائياً من الملف — وتقدر تعدله قبل الرفع.'
          : 'Pick every file in one go. Each file becomes its own ticket, with the title auto-filled from the file — you can edit each title before sending.'}
      </p>

      <div className="field" style={{ marginBottom: '1rem' }}>
        <label className="field-label" htmlFor="quick-upload-dept" style={{ marginBottom: '0.4rem' }}>
          {ar ? 'القسم' : 'Department'}
          {departments.length > 0 && (
            <span style={{ color: 'var(--danger)', marginInlineStart: '0.25rem' }}>*</span>
          )}
        </label>
        {departmentsLoading ? (
          <div className="muted small">
            {ar ? 'جارٍ تحميل الأقسام…' : 'Loading departments…'}
          </div>
        ) : departments.length === 0 ? (
          <div className="muted small">
            {ar
              ? 'المشروع مالوش أقسام لسه — هيتعمل قسم افتراضي تلقائياً.'
              : 'This project has no departments yet — a default one will be created automatically.'}
          </div>
        ) : (
          <select
            id="quick-upload-dept"
            className="input"
            value={departmentId}
            onChange={(e) => setDepartmentId(e.target.value === '' ? '' : Number(e.target.value))}
            disabled={submitting}
          >
            <option value="">
              {ar ? '— اختر قسم —' : '— Pick a department —'}
            </option>
            {departments.map((d) => (
              <option key={d.id} value={d.id}>
                {pickLocalized(d, 'name', lang) || d.name}
              </option>
            ))}
          </select>
        )}
        <p className="muted small" style={{ marginTop: '0.35rem' }}>
          {ar
            ? 'كل الملفات في الدفعة دي هتترفع تحت القسم ده.'
            : 'Every file in this batch will be filed under this department.'}
        </p>
      </div>

      {batch ? batchHud(batch) : (
        <div
          className="quick-upload-drop"
          onDragOver={(e) => { e.preventDefault(); setDragActive(true); }}
          onDragEnter={(e) => { e.preventDefault(); setDragActive(true); }}
          onDragLeave={() => setDragActive(false)}
          onDrop={onDrop}
          onClick={() => inputRef.current?.click()}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault();
              inputRef.current?.click();
            }
          }}
          style={{
            border: `2px dashed ${dragActive ? 'var(--brand)' : 'var(--border)'}`,
            borderRadius: 'var(--radius)',
            padding: '1.5rem',
            textAlign: 'center',
            cursor: 'pointer',
            transition: 'border-color 0.15s ease, background 0.15s ease',
            background: dragActive ? 'var(--bg-sunken)' : 'transparent',
            marginBottom: '1rem',
          }}
        >
          <IconPlus size={22} />
          <div style={{ marginTop: '0.4rem', fontWeight: 500 }}>
            {ar ? 'اسحب الملفات هنا أو اضغط للاختيار' : 'Drop files here or click to browse'}
          </div>
          <div className="muted small" style={{ marginTop: '0.25rem' }}>
            {ar ? 'يمكنك اختيار أكثر من ملف مرة واحدة' : 'You can pick many files at once'}
          </div>
          <input
            ref={inputRef}
            type="file"
            multiple
            onChange={onFilePick}
            style={{ display: 'none' }}
          />
        </div>
      )}

      {error && <div className="alert alert-error" style={{ marginBottom: '0.75rem' }}>{error}</div>}

      {failures.length > 0 && (
        <div className="alert alert-warning" style={{ marginBottom: '0.75rem' }}>
          <div style={{ fontWeight: 600, marginBottom: '0.35rem' }}>
            {ar ? 'الملفات دي فشل رفعها:' : 'These files could not be uploaded:'}
          </div>
          <ul style={{ margin: 0, paddingInlineStart: '1.25rem' }}>
            {failures.map((f, i) => (
              <li key={i}>
                <code>{f.filename}</code> — <span className="muted">{f.reason}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {rows.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '0.6rem' }}>
          {rows.map((r, idx) => {
            const status = rowStatus[r.id];
            const meta = rowMeta(r);
            return (
              <div
                key={r.id}
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'auto minmax(0, 1fr) auto',
                  gap: '0.65rem',
                  alignItems: 'flex-start',
                  padding: '0.6rem 0.75rem',
                  border: '1px solid var(--border)',
                  borderRadius: 'var(--radius)',
                  background: 'var(--bg-surface)',
                }}
              >
                {status ? (
                  <ProgressRing
                    value={progress[r.id]?.fraction ?? 0}
                    size={44}
                    stroke={4}
                    state={status}
                    ariaLabel={r.file.name}
                  />
                ) : (
                  <span
                    aria-hidden="true"
                    style={{
                      width: 38,
                      height: 38,
                      borderRadius: 10,
                      background: CHIP_TINTS[idx % CHIP_TINTS.length].bg,
                      color: CHIP_TINTS[idx % CHIP_TINTS.length].fg,
                      display: 'inline-flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      flexShrink: 0,
                      marginTop: 2,
                    }}
                  >
                    <svg width="18" height="18" viewBox="0 0 20 20" fill="none">
                      <path d="M5 3h7l4 4v10a1 1 0 01-1 1H5a1 1 0 01-1-1V4a1 1 0 011-1z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
                      <path d="M12 3v4h4" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
                    </svg>
                  </span>
                )}
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.35rem' }}>
                  <label className="field-label" style={{ margin: 0, fontSize: 'var(--fs-xs)' }}>
                    {ar
                      ? `العنوان (السطر ${idx + 1})`
                      : `Title (row ${idx + 1})`}
                  </label>
                  <input
                    className="input"
                    value={r.title}
                    onChange={(e) => updateTitle(r.id, e.target.value)}
                    disabled={submitting}
                    placeholder={ar ? 'عنوان التذكرة' : 'Ticket title'}
                  />
                  <div className="muted small" style={{ wordBreak: 'break-all' }}>
                    {r.file.name} · {formatBytes(r.file.size)}
                  </div>
                  {meta && (
                    <div className={`upload-row-meta ${meta.tone}`.trim()} aria-live="polite">
                      {meta.text}
                    </div>
                  )}
                </div>
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() => removeRow(r.id)}
                  disabled={submitting}
                  aria-label={ar ? 'إزالة' : 'Remove'}
                  title={ar ? 'إزالة' : 'Remove'}
                >
                  <IconClose size={14} />
                </button>
              </div>
            );
          })}
        </div>
      )}
    </Modal>
  );
}
