import { useCallback, useEffect, useRef, useState } from 'react';
import { extractError } from '../../api/client';
import { departmentsApi, projectFoldersApi } from '../../api/resources';
import type { Department } from '../../api/types';
import { IconClose, IconPlus } from '../../components/Icons';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/toast/ToastContext';
import { useT } from '../../i18n';
import { pickLocalized } from '../../i18n/localized';

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
  /** Editable title — auto-filled from the filename (extension stripped) on drop. */
  title: string;
}

function extractTitleFromFilename(name: string): string {
  const withoutExt = name.replace(/\.[^./\\]+$/, '');
  const cleaned = withoutExt.replace(/[_\-.]+/g, ' ').replace(/\s+/g, ' ').trim();
  return cleaned || name;
}

/** How many files travel in parallel. Enough to fill the pipe on high-latency links
 *  without starving the rest of the app's requests behind the browser's per-host cap. */
const UPLOAD_CONCURRENCY = 3;

type RowStatus = 'uploading' | 'done' | 'failed';

/**
 * Multi-file quick-upload. Every file becomes its own ticket in the current project
 * with the title auto-extracted from the filename. Titles are still editable so a
 * mis-guessed name can be corrected inline before send.
 *
 * <p>Each file goes up as its own request ({@link UPLOAD_CONCURRENCY} in flight at a
 * time), so a batch of scanned books shows live per-file progress, one failed or
 * oversized file can't sink the rest, and no single request has to carry the whole
 * batch under the server's request-size cap. Per-file atomicity is unchanged — the
 * server still creates the ticket and attaches the file in one transaction.
 */
export function QuickUploadModal({ open, projectId, onClose, onCreated }: Props) {
  const { lang, t } = useT();
  const toast = useToast();
  const [rows, setRows] = useState<Row[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [failures, setFailures] = useState<Array<{ filename: string; reason: string }>>([]);
  /** Per-row upload fraction (0..1), keyed by Row.id. Only rows in flight have entries. */
  const [progress, setProgress] = useState<Record<number, number>>({});
  const [rowStatus, setRowStatus] = useState<Record<number, RowStatus>>({});
  const [dragActive, setDragActive] = useState(false);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [departmentsLoading, setDepartmentsLoading] = useState(false);
  const [departmentId, setDepartmentId] = useState<number | ''>('');
  const inputRef = useRef<HTMLInputElement | null>(null);
  const nextId = useRef(1);

  useEffect(() => {
    if (!open) {
      setRows([]);
      setError(null);
      setFailures([]);
      setSubmitting(false);
      setProgress({});
      setRowStatus({});
      setDepartments([]);
      setDepartmentId('');
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
    setRows((prev) => {
      const next: Row[] = [...prev];
      for (const f of list) {
        next.push({
          id: nextId.current++,
          file: f,
          title: extractTitleFromFilename(f.name),
        });
      }
      return next;
    });
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

  async function onSubmit() {
    setError(null);
    setFailures([]);
    if (rows.length === 0) {
      setError(lang === 'ar' ? 'اختر ملف واحد على الأقل' : 'Pick at least one file');
      return;
    }
    // Force explicit picking whenever the project actually has departments to choose
    // from. The empty-project case (server auto-creates a default) is the only path
    // where we let this stay unset.
    if (departments.length > 0 && departmentId === '') {
      setError(lang === 'ar' ? 'اختر القسم أولاً' : 'Pick a department first');
      return;
    }

    setSubmitting(true);
    setProgress({});
    setRowStatus({});

    // One request per file, UPLOAD_CONCURRENCY in flight. The queue is a shared array the
    // workers shift() from — single-threaded JS makes that race-free between awaits.
    const queue = [...rows];
    const dept = departmentId === '' ? null : departmentId;
    let created = 0;
    const newFailures: Array<{ filename: string; reason: string }> = [];
    const failedRowIds = new Set<number>();

    async function uploadOne(row: Row) {
      setRowStatus((s) => ({ ...s, [row.id]: 'uploading' }));
      try {
        const result = await projectFoldersApi.quickUpload(
          projectId,
          [{ file: row.file, title: row.title.trim() }],
          dept,
          undefined,
          (fraction) => setProgress((p) => ({ ...p, [row.id]: fraction })),
        );
        created += result.created;
        if (result.failed > 0) {
          newFailures.push(...result.failures);
          failedRowIds.add(row.id);
          setRowStatus((s) => ({ ...s, [row.id]: 'failed' }));
        } else {
          setRowStatus((s) => ({ ...s, [row.id]: 'done' }));
        }
      } catch (err) {
        newFailures.push({
          filename: row.file.name,
          reason: extractError(err, lang === 'ar' ? 'فشل الرفع' : 'Upload failed'),
        });
        failedRowIds.add(row.id);
        setRowStatus((s) => ({ ...s, [row.id]: 'failed' }));
      }
    }

    const workers = Array.from(
      { length: Math.min(UPLOAD_CONCURRENCY, queue.length) },
      async () => {
        for (let row = queue.shift(); row; row = queue.shift()) {
          await uploadOne(row);
        }
      },
    );
    await Promise.all(workers);

    if (created > 0) {
      toast.success(
        lang === 'ar'
          ? `تم رفع ${created} ملف بنجاح`
          : `Uploaded ${created} file${created === 1 ? '' : 's'}`,
      );
    }

    if (newFailures.length > 0) {
      setFailures(newFailures);
      // Also toast so the user notices even if they miss the inline list.
      toast.warning(
        lang === 'ar'
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

  return (
    <Modal
      open={open}
      title={lang === 'ar' ? 'رفع ملفات دفعة واحدة' : 'Upload multiple files'}
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
            className="btn btn-primary"
            onClick={onSubmit}
            disabled={submitting || rows.length === 0}
          >
            {submitting
              ? (lang === 'ar'
                ? `جاري الرفع… ${Object.values(rowStatus).filter((s) => s !== 'uploading').length}/${rows.length}`
                : `Uploading… ${Object.values(rowStatus).filter((s) => s !== 'uploading').length}/${rows.length}`)
              : (lang === 'ar'
                ? `رفع ${rows.length} ${rows.length === 1 ? 'ملف' : 'ملفات'}`
                : `Upload ${rows.length} file${rows.length === 1 ? '' : 's'}`)}
          </button>
        </div>
      }
    >
      <p className="muted" style={{ marginTop: 0 }}>
        {lang === 'ar'
          ? 'اختار كل الملفات دفعة واحدة. هيتعمل تذكرة لكل ملف والعنوان هيتعبى تلقائياً من اسم الملف — وتقدر تعدله قبل الرفع.'
          : 'Pick every file in one go. Each file becomes its own ticket, with the title auto-filled from the filename — you can edit each title before sending.'}
      </p>

      <div className="field" style={{ marginBottom: '1rem' }}>
        <label className="field-label" htmlFor="quick-upload-dept" style={{ marginBottom: '0.4rem' }}>
          {lang === 'ar' ? 'القسم' : 'Department'}
          {departments.length > 0 && (
            <span style={{ color: 'var(--danger)', marginInlineStart: '0.25rem' }}>*</span>
          )}
        </label>
        {departmentsLoading ? (
          <div className="muted small">
            {lang === 'ar' ? 'جارٍ تحميل الأقسام…' : 'Loading departments…'}
          </div>
        ) : departments.length === 0 ? (
          <div className="muted small">
            {lang === 'ar'
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
              {lang === 'ar' ? '— اختر قسم —' : '— Pick a department —'}
            </option>
            {departments.map((d) => (
              <option key={d.id} value={d.id}>
                {pickLocalized(d, 'name', lang) || d.name}
              </option>
            ))}
          </select>
        )}
        <p className="muted small" style={{ marginTop: '0.35rem' }}>
          {lang === 'ar'
            ? 'كل الملفات في الدفعة دي هتترفع تحت القسم ده.'
            : 'Every file in this batch will be filed under this department.'}
        </p>
      </div>

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
          {lang === 'ar' ? 'اسحب الملفات هنا أو اضغط للاختيار' : 'Drop files here or click to browse'}
        </div>
        <div className="muted small" style={{ marginTop: '0.25rem' }}>
          {lang === 'ar' ? 'يمكنك اختيار أكثر من ملف مرة واحدة' : 'You can pick many files at once'}
        </div>
        <input
          ref={inputRef}
          type="file"
          multiple
          onChange={onFilePick}
          style={{ display: 'none' }}
        />
      </div>

      {error && <div className="alert alert-error" style={{ marginBottom: '0.75rem' }}>{error}</div>}

      {failures.length > 0 && (
        <div className="alert alert-warning" style={{ marginBottom: '0.75rem' }}>
          <div style={{ fontWeight: 600, marginBottom: '0.35rem' }}>
            {lang === 'ar' ? 'الملفات دي فشل رفعها:' : 'These files could not be uploaded:'}
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
          {rows.map((r, idx) => (
            <div
              key={r.id}
              style={{
                display: 'grid',
                gridTemplateColumns: 'minmax(0, 1fr) auto',
                gap: '0.5rem',
                alignItems: 'flex-start',
                padding: '0.6rem 0.75rem',
                border: '1px solid var(--border)',
                borderRadius: 'var(--radius)',
                background: 'var(--bg-surface)',
              }}
            >
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.35rem' }}>
                <label className="field-label" style={{ margin: 0, fontSize: 'var(--fs-xs)' }}>
                  {lang === 'ar'
                    ? `العنوان (السطر ${idx + 1})`
                    : `Title (row ${idx + 1})`}
                </label>
                <input
                  className="input"
                  value={r.title}
                  onChange={(e) => updateTitle(r.id, e.target.value)}
                  disabled={submitting}
                  placeholder={lang === 'ar' ? 'عنوان التذكرة' : 'Ticket title'}
                />
                <div className="muted small" style={{ wordBreak: 'break-all' }}>
                  {r.file.name} · {formatBytes(r.file.size)}
                </div>
                {rowStatus[r.id] && (
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                    <div
                      role="progressbar"
                      aria-valuemin={0}
                      aria-valuemax={100}
                      aria-valuenow={Math.round((progress[r.id] ?? 0) * 100)}
                      style={{
                        flex: 1,
                        position: 'relative',
                        height: 4,
                        borderRadius: 2,
                        background: 'var(--border)',
                        overflow: 'hidden',
                      }}
                    >
                      <div
                        style={{
                          height: '100%',
                          width: '100%',
                          borderRadius: 2,
                          background: rowStatus[r.id] === 'failed'
                            ? 'var(--danger)'
                            : rowStatus[r.id] === 'done'
                              ? 'var(--success)'
                              : `linear-gradient(${lang === 'ar' ? '270deg' : '90deg'}, var(--brand), var(--accent-cyan))`,
                          // Fill via transform, not width — keeps progress updates off the
                          // layout path. Origin follows text direction so RTL fills from
                          // the right.
                          transform: `scaleX(${rowStatus[r.id] === 'done' ? 1 : progress[r.id] ?? 0})`,
                          transformOrigin: lang === 'ar' ? 'right' : 'left',
                          transition: 'transform 0.2s ease, background 0.2s ease',
                        }}
                      />
                      {rowStatus[r.id] === 'uploading' && <span className="upload-shimmer" />}
                    </div>
                    <span
                      className="small"
                      style={{
                        minWidth: '3.5rem',
                        display: 'inline-flex',
                        alignItems: 'center',
                        justifyContent: 'flex-end',
                        gap: '0.3rem',
                        color: rowStatus[r.id] === 'failed'
                          ? 'var(--danger)'
                          : rowStatus[r.id] === 'done' ? 'var(--success)' : 'var(--text-tertiary)',
                      }}
                    >
                      {rowStatus[r.id] === 'done' ? (
                        <>
                          <svg className="upload-check" width="14" height="14" viewBox="0 0 14 14" fill="none" aria-hidden="true">
                            <circle cx="7" cy="7" r="6.4" fill="var(--success)" />
                            <path d="M4 7.3l2.1 2.1L10.2 5" stroke="var(--bg-surface)" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
                          </svg>
                          {lang === 'ar' ? 'تم' : 'Done'}
                        </>
                      ) : rowStatus[r.id] === 'failed'
                        ? (lang === 'ar' ? 'فشل' : 'Failed')
                        : `${Math.round((progress[r.id] ?? 0) * 100)}%`}
                    </span>
                  </div>
                )}
              </div>
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                onClick={() => removeRow(r.id)}
                disabled={submitting}
                aria-label={lang === 'ar' ? 'إزالة' : 'Remove'}
                title={lang === 'ar' ? 'إزالة' : 'Remove'}
              >
                <IconClose size={14} />
              </button>
            </div>
          ))}
        </div>
      )}
    </Modal>
  );
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}
