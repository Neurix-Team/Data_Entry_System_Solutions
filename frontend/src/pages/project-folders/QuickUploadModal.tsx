import { useCallback, useEffect, useRef, useState } from 'react';
import { extractError } from '../../api/client';
import { projectFoldersApi } from '../../api/resources';
import { IconClose, IconPlus } from '../../components/Icons';
import { Modal } from '../../components/Modal';
import { useToast } from '../../components/toast/ToastContext';
import { useT } from '../../i18n';

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

/**
 * Multi-file quick-upload. Every file becomes its own ticket in the current project
 * with the title auto-extracted from the filename. Titles are still editable so a
 * mis-guessed name can be corrected inline before send.
 *
 * <p>Uses a single atomic backend call — the server creates the ticket and attaches
 * the file in one transaction, rolls back any half-written ticket if the attach fails,
 * and returns a per-file success/failure list so partial success is visible instead of
 * being hidden behind a generic error.
 */
export function QuickUploadModal({ open, projectId, onClose, onCreated }: Props) {
  const { lang, t } = useT();
  const toast = useToast();
  const [rows, setRows] = useState<Row[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [failures, setFailures] = useState<Array<{ filename: string; reason: string }>>([]);
  const [dragActive, setDragActive] = useState(false);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const nextId = useRef(1);

  useEffect(() => {
    if (!open) {
      setRows([]);
      setError(null);
      setFailures([]);
      setSubmitting(false);
    }
  }, [open]);

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

    setSubmitting(true);
    try {
      const result = await projectFoldersApi.quickUpload(
        projectId,
        rows.map((r) => ({ file: r.file, title: r.title.trim() })),
      );

      if (result.created > 0) {
        toast.success(
          lang === 'ar'
            ? `تم رفع ${result.created} ملف بنجاح`
            : `Uploaded ${result.created} file${result.created === 1 ? '' : 's'}`,
        );
      }

      if (result.failed > 0) {
        setFailures(result.failures);
        // Also toast so the user notices even if they miss the inline list.
        toast.warning(
          lang === 'ar'
            ? `فشل رفع ${result.failed} ملف — راجع القائمة`
            : `${result.failed} file${result.failed === 1 ? '' : 's'} failed — check the list below`,
        );
        // Keep the modal open when there were failures so the user can retry only the
        // ones that failed (currently by re-picking them; a per-row retry could be added).
        // Trim successful rows so the visible list matches what still needs attention.
        const failedFilenames = new Set(result.failures.map((f) => f.filename));
        setRows((prev) => prev.filter((r) => failedFilenames.has(r.file.name)));
      }

      onCreated({ created: result.created, failed: result.failed });
    } catch (err) {
      setError(extractError(err, lang === 'ar' ? 'فشل الرفع' : 'Upload failed'));
    } finally {
      setSubmitting(false);
    }
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
              ? (lang === 'ar' ? 'جاري الرفع…' : 'Uploading…')
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
          border: `2px dashed ${dragActive ? 'var(--accent, #2563eb)' : 'var(--border)'}`,
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
