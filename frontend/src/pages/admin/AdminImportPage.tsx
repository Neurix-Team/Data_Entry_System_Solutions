import { useRef, useState } from 'react';
import { extractError } from '../../api/client';
import { adminFeaturesApi } from '../../api/resources';
import type { ImportResult } from '../../api/types';
import { useToast } from '../../components/toast/ToastContext';
import { useT } from '../../i18n';

/** C1: bulk-create entries from a CSV (Excel "Save As CSV"). Row-level errors report back. */
/** Quotes a cell only when RFC 4180 requires it: a comma, quote or newline inside it. */
function csvCell(value: string): string {
  return /[",\r\n]/.test(value) ? '"' + value.replace(/"/g, '""') + '"' : value;
}

export function AdminImportPage() {
  const { t, lang } = useT();
  const toast = useToast();
  const fileRef = useRef<HTMLInputElement | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  /**
   * A CSV with the right header row, one example row, and nothing else. Handed out so the
   * required columns are never a guess — the file this page produces is the file it accepts.
   */
  function downloadTemplate() {
    const header = ['title', 'content', 'websiteName', 'websiteLink',
      'departmentId', 'departmentName', 'projectId', 'projectName', 'subcategoryId'];
    const example = ['Example entry title', 'Example entry content goes here.',
      'Example Site', 'https://example.com', '', 'Department name here', '', '', ''];
    const csv = [header, example]
      .map((row) => row.map(csvCell).join(','))
      .join('\r\n') + '\r\n';
    // The BOM keeps Excel from mangling non-ASCII text if someone edits the example in place.
    const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'import-template.csv';
    a.click();
    URL.revokeObjectURL(url);
  }

  async function start() {
    if (!file || busy) return;
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const r = await adminFeaturesApi.importCsv(file);
      setResult(r);
      if (r.failed === 0) toast.success(t('import.allDone', { n: r.created }));
      else toast.warning(t('import.partial', { n: r.created, bad: r.failed }));
    } catch (e) {
      setError(extractError(e, t('import.failed')));
    } finally {
      setBusy(false);
      if (fileRef.current) fileRef.current.value = '';
      setFile(null);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('import.title')}</h1>
          <p className="subtitle">{t('import.subtitle')}</p>
        </div>
      </div>

      <div className="card" style={{ marginBottom: 16 }}>
        {error && <div className="alert alert-error">{error}</div>}
        <div className="field">
          <label className="field-label">{t('import.fileLabel')}</label>
          <input
            ref={fileRef}
            className="input"
            type="file"
            accept=".csv,text/csv"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
        </div>
        <button
          type="button"
          className="btn btn-primary"
          disabled={!file || busy}
          onClick={start}
        >
          ⬆️ {busy ? t('common.loading') : t('import.start')}
        </button>

        <div className="muted small" style={{ marginTop: 14, lineHeight: 1.7 }}>
          <strong>{t('import.colsTitle')}</strong>
          <div><code>title</code>*, <code>content</code>*, <code>websiteName</code>, <code>websiteLink</code>,
            {' '}<code>departmentId</code>*, <code>departmentName</code>, <code>projectId</code>,
            {' '}<code>projectName</code>, <code>subcategoryId</code>
          </div>
          <div style={{ marginTop: 4 }}>{t('import.colsHint')}</div>
          <button type="button" className="btn btn-ghost btn-sm" style={{ marginTop: 8 }}
                  onClick={downloadTemplate}>
            ⬇️ {t('import.downloadTemplate')}
          </button>
        </div>
      </div>

      {result && (
        <div className="card">
          <div style={{ display: 'flex', gap: 16, marginBottom: 10 }}>
            <span style={{ fontWeight: 700, color: 'var(--success-soft-text)' }}>
              ✅ {t('import.created', { n: result.created })}
            </span>
            <span style={{ fontWeight: 700, color: 'var(--danger-soft-text)' }}>
              ❌ {t('import.skipped', { n: result.failed })}
            </span>
          </div>
          {result.errors.length > 0 && (
            <div className="table-wrap">
              <table className="data">
                <thead>
                  <tr>
                    <th>{t('import.row')}</th>
                    <th>{t('import.reason')}</th>
                  </tr>
                </thead>
                <tbody>
                  {result.errors.slice(0, 100).map((e) => (
                    <tr key={e.row}>
                      <td><span className="small">#{e.row}</span></td>
                      <td><span className="small muted">{e.reason}</span></td>
                    </tr>
                  ))}
                  {result.errors.length > 100 && (
                    <tr><td colSpan={2} className="muted small">
                      {t('import.moreErrors', { n: result.errors.length - 100 })}
                    </td></tr>
                  )}
                </tbody>
              </table>
            </div>
          )}
          {lang === 'ar' && result.created > 0 && (
            <p className="muted small" style={{ marginTop: 8 }}>
              المدخلات الجديدة تظهر في "مهام إدخال البيانات".
            </p>
          )}
        </div>
      )}
    </div>
  );
}