import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import { extractError } from '../../api/client';
import { aiApi, departmentsApi, documentsApi, fieldsApi, subcategoriesApi, ticketsApi } from '../../api/resources';
import type { ArticleInput, CustomField, Department, ExtractedPdf, Subcategory } from '../../api/types';
import { IconClose, IconPlus } from '../../components/Icons';
import { Modal } from '../../components/Modal';
import { useT } from '../../i18n';

interface CoreForm {
  departmentId: string;
  subcategoryId: string;
}

interface ArticleRow {
  id: number;
  title: string;
  content: string;
  websiteName: string;
  websiteLink: string;
}

const initialCore: CoreForm = { departmentId: '', subcategoryId: '' };

function newRow(id: number): ArticleRow {
  return { id, title: '', content: '', websiteName: '', websiteLink: '' };
}

function useNowTick() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(id);
  }, []);
  return now;
}

function isValidUrl(s: string): boolean {
  try {
    const u = new URL(s);
    return (u.protocol === 'http:' || u.protocol === 'https:') && !!u.host;
  } catch { return false; }
}

export function SubmitTicketPage() {
  const { t, lang } = useT();
  const now = useNowTick();
  const [departments, setDepartments] = useState<Department[]>([]);
  const [subcategories, setSubcategories] = useState<Subcategory[]>([]);
  const [fields, setFields] = useState<CustomField[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [core, setCore] = useState<CoreForm>(initialCore);
  const [customValues, setCustomValues] = useState<Record<string, string>>({});

  const nextIdRef = useRef(1);
  const [articles, setArticles] = useState<ArticleRow[]>(() => [newRow(0)]);

  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  // AI check state (targets a specific article)
  const [aiOpen, setAiOpen] = useState(false);
  const [aiTargetId, setAiTargetId] = useState<number | null>(null);
  const [aiLoading, setAiLoading] = useState(false);
  const [aiResult, setAiResult] = useState<{ original: string; corrected: string; notes: string[] } | null>(null);
  const [aiError, setAiError] = useState<string | null>(null);

  // Document upload state (targets a specific article)
  const [docOpen, setDocOpen] = useState(false);
  const [docTargetId, setDocTargetId] = useState<number | null>(null);
  const [docLoading, setDocLoading] = useState(false);
  const [docResult, setDocResult] = useState<ExtractedPdf | null>(null);
  const [docError, setDocError] = useState<string | null>(null);

  useEffect(() => {
    departmentsApi.userList()
      .then(setDepartments)
      .catch((e) => setLoadError(extractError(e)))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    if (!core.departmentId) {
      setSubcategories([]);
      setFields([]);
      return;
    }
    subcategoriesApi.userList(Number(core.departmentId))
      .then(setSubcategories)
      .catch((e) => setLoadError(extractError(e)));
    setCore(c => ({ ...c, subcategoryId: '' }));
    setFields([]);
    setCustomValues({});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [core.departmentId]);

  useEffect(() => {
    if (!core.subcategoryId) {
      setFields([]);
      return;
    }
    fieldsApi.activeList(Number(core.subcategoryId))
      .then(setFields)
      .catch((e) => setLoadError(extractError(e)));
    setCustomValues({});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [core.subcategoryId]);

  const dateLabel = useMemo(
    () => now.toLocaleString(lang === 'ar' ? 'ar-EG' : undefined),
    [now, lang]
  );

  function addArticle() {
    const id = nextIdRef.current++;
    setArticles((prev) => [...prev, newRow(id)]);
  }

  function removeArticle(id: number) {
    setArticles((prev) => prev.length === 1 ? prev : prev.filter((a) => a.id !== id));
  }

  function updateArticle(id: number, patch: Partial<ArticleRow>) {
    setArticles((prev) => prev.map((a) => a.id === id ? { ...a, ...patch } : a));
  }

  function articleErrorKey(id: number, field: keyof ArticleRow) {
    return `a${id}.${field}`;
  }

  function validate(): boolean {
    const errs: Record<string, string> = {};
    if (!core.departmentId) errs.departmentId = t('user.submit.errDepartment');
    if (!core.subcategoryId) errs.subcategoryId = t('user.submit.errSubcategory');

    for (const f of fields) {
      const v = (customValues[f.fieldKey] ?? '').trim();
      if (f.required && !v) {
        errs[f.fieldKey] = t('user.submit.errFieldRequired', { label: f.label });
        continue;
      }
      if (!v) continue;
      if (f.type === 'NUMBER' && Number.isNaN(Number(v))) {
        errs[f.fieldKey] = t('user.submit.errFieldNumber', { label: f.label });
      } else if (f.type === 'URL' && !isValidUrl(v)) {
        errs[f.fieldKey] = t('user.submit.errFieldUrl', { label: f.label });
      } else if (f.type === 'EMAIL' && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v)) {
        errs[f.fieldKey] = t('user.submit.errFieldEmail', { label: f.label });
      }
    }

    for (const a of articles) {
      if (!a.title.trim()) errs[articleErrorKey(a.id, 'title')] = t('user.submit.errTitle');
      if (!a.content.trim()) errs[articleErrorKey(a.id, 'content')] = t('user.submit.errContent');
      if (a.websiteLink.trim() && !isValidUrl(a.websiteLink.trim())) {
        errs[articleErrorKey(a.id, 'websiteLink')] = t('user.submit.errUrlInvalid');
      }
    }

    setErrors(errs);
    return Object.keys(errs).length === 0;
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setSubmitError(null); setSuccess(null);
    if (!validate()) return;

    setSubmitting(true);
    try {
      const trimmedCustom: Record<string, string> = {};
      Object.entries(customValues).forEach(([k, v]) => { trimmedCustom[k] = (v ?? '').trim(); });

      const payloadArticles: ArticleInput[] = articles.map((a) => ({
        title: a.title.trim(),
        content: a.content.trim(),
        websiteName: a.websiteName.trim() || undefined,
        websiteLink: a.websiteLink.trim() || undefined,
      }));

      const res = await ticketsApi.submitBulk({
        departmentId: Number(core.departmentId),
        subcategoryId: Number(core.subcategoryId),
        articles: payloadArticles,
        customValues: trimmedCustom,
      });
      setSuccess(t('user.submit.bulkSuccess', { count: res.created }));
      // Reset — keep dept/subcat/custom fields so next batch is faster
      nextIdRef.current = 1;
      setArticles([newRow(0)]);
      setErrors({});
    } catch (err) {
      setSubmitError(extractError(err, t('user.submit.submitFailed')));
    } finally {
      setSubmitting(false);
    }
  }

  function openAiCheck(articleId: number) {
    const target = articles.find((a) => a.id === articleId);
    if (!target || !target.content.trim()) return;
    setAiTargetId(articleId);
    setAiError(null); setAiResult(null); setAiOpen(true); setAiLoading(true);
    aiApi.check(target.content)
      .then(setAiResult)
      .catch((e) => setAiError(extractError(e, t('user.submit.checkFailed'))))
      .finally(() => setAiLoading(false));
  }

  function applyAiSuggestion() {
    if (aiResult && aiTargetId != null) {
      updateArticle(aiTargetId, { content: aiResult.corrected });
    }
    setAiOpen(false);
  }

  function openDocModalFor(articleId: number) {
    setDocTargetId(articleId);
    setDocResult(null);
    setDocError(null);
    setDocOpen(true);
  }

  async function onDocChosen(file: File) {
    setDocError(null);
    setDocLoading(true);
    setDocResult(null);
    try {
      const res = await documentsApi.extract(file);
      setDocResult(res);
    } catch (e) {
      setDocError(extractError(e, t('user.submit.pdfFailed')));
    } finally {
      setDocLoading(false);
    }
  }

  function insertDocIntoArticle() {
    if (!docResult || docTargetId == null) return;
    const suggestedTitle = docResult.filename.replace(/\.[^.]+$/, '').slice(0, 200).trim();
    setArticles((prev) => prev.map((a) => {
      if (a.id !== docTargetId) return a;
      return {
        ...a,
        title: a.title.trim() ? a.title : suggestedTitle,
        content: a.content.trim() ? `${a.content}\n\n${docResult.text}` : docResult.text,
      };
    }));
    setDocOpen(false);
  }

  if (loading) {
    return <div className="page"><div className="card"><span className="muted">{t('user.submit.loadingForm')}</span></div></div>;
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('user.submit.title')}</h1>
          <p className="subtitle">{t('user.submit.subtitleBulk')}</p>
        </div>
      </div>

      {loadError && <div className="alert alert-error">{loadError}</div>}
      {submitError && <div className="alert alert-error">{submitError}</div>}
      {success && <div className="alert alert-success">{success}</div>}

      <div className="card">
        <form onSubmit={onSubmit} noValidate>
          {/* Shared header */}
          <div className="row" style={{ gap: '1rem', flexWrap: 'wrap' }}>
            <div className="field" style={{ flex: '1 1 220px' }}>
              <label className="field-label">{t('user.submit.date')}</label>
              <input className="input" value={dateLabel} readOnly />
            </div>
            <div className="field" style={{ flex: '1 1 240px' }}>
              <label className="field-label">{t('user.submit.department')} <span className="req">*</span></label>
              <select
                className={`select ${errors.departmentId ? 'has-error' : ''}`}
                value={core.departmentId}
                onChange={(e) => setCore({ ...core, departmentId: e.target.value })}
              >
                <option value="">{t('user.submit.chooseDepartment')}</option>
                {departments.map((d) => (
                  <option key={d.id} value={d.id}>{d.name}</option>
                ))}
              </select>
              {errors.departmentId && <span className="field-error">{errors.departmentId}</span>}
            </div>
            <div className="field" style={{ flex: '1 1 240px' }}>
              <label className="field-label">{t('user.submit.subcategory')} <span className="req">*</span></label>
              <select
                className={`select ${errors.subcategoryId ? 'has-error' : ''}`}
                value={core.subcategoryId}
                onChange={(e) => setCore({ ...core, subcategoryId: e.target.value })}
                disabled={!core.departmentId}
              >
                <option value="">{t('user.submit.chooseSubcategory')}</option>
                {subcategories.map((s) => (
                  <option key={s.id} value={s.id}>{s.name}</option>
                ))}
              </select>
              {errors.subcategoryId && <span className="field-error">{errors.subcategoryId}</span>}
            </div>
          </div>

          {/* Shared custom fields (per subcategory) */}
          {fields.length > 0 && (
            <>
              <div className="section-divider" style={{ margin: '1.25rem 0 0.5rem', fontWeight: 600, color: 'var(--text-secondary)', fontSize: 'var(--fs-sm)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                {t('user.submit.sharedFields')}
              </div>
              {fields.map((f) => (
                <DynamicField
                  key={f.id}
                  field={f}
                  value={customValues[f.fieldKey] ?? ''}
                  onChange={(v) => setCustomValues({ ...customValues, [f.fieldKey]: v })}
                  error={errors[f.fieldKey]}
                />
              ))}
            </>
          )}

          {/* Articles list */}
          <div className="row-between" style={{ margin: '1.5rem 0 0.75rem', alignItems: 'center' }}>
            <div style={{ fontWeight: 600, color: 'var(--text-secondary)', fontSize: 'var(--fs-sm)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
              {t('user.submit.articles')} ({articles.length})
            </div>
            <button
              type="button"
              className="btn btn-primary btn-sm"
              onClick={addArticle}
              disabled={submitting}
            >
              <IconPlus size={14} /> {t('user.submit.addArticle')}
            </button>
          </div>

          {articles.map((a, idx) => (
            <div
              key={a.id}
              className="card"
              style={{
                padding: '1rem 1.25rem',
                marginBottom: '0.75rem',
                background: 'var(--bg-sunken)',
                border: '1px solid var(--border)',
              }}
            >
              <div className="row-between" style={{ marginBottom: '0.75rem', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
                <span style={{ fontWeight: 600 }}>
                  {t('user.submit.articleN', { n: idx + 1 })}
                </span>
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  <button
                    type="button"
                    className="btn btn-secondary btn-sm"
                    onClick={() => openDocModalFor(a.id)}
                    disabled={submitting}
                    title={t('user.submit.uploadDoc')}
                  >
                    {t('user.submit.uploadDoc')}
                  </button>
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm"
                    onClick={() => openAiCheck(a.id)}
                    disabled={!a.content.trim() || submitting}
                    title={t('user.submit.checkContent')}
                  >
                    {t('user.submit.checkContent')}
                  </button>
                  {articles.length > 1 && (
                    <button
                      type="button"
                      className="btn btn-danger btn-sm"
                      onClick={() => removeArticle(a.id)}
                      title={t('user.submit.removeArticle')}
                    >
                      <IconClose size={14} />
                    </button>
                  )}
                </div>
              </div>
              <div className="field">
                <label className="field-label">{t('user.submit.articleTitle')} <span className="req">*</span></label>
                <input
                  className={`input ${errors[articleErrorKey(a.id, 'title')] ? 'has-error' : ''}`}
                  value={a.title}
                  onChange={(e) => updateArticle(a.id, { title: e.target.value })}
                  placeholder={t('user.submit.articleTitlePlaceholder')}
                />
                {errors[articleErrorKey(a.id, 'title')] && (
                  <span className="field-error">{errors[articleErrorKey(a.id, 'title')]}</span>
                )}
              </div>
              <div className="field">
                <label className="field-label">{t('user.submit.content')} <span className="req">*</span></label>
                <textarea
                  className={`textarea ${errors[articleErrorKey(a.id, 'content')] ? 'has-error' : ''}`}
                  value={a.content}
                  onChange={(e) => updateArticle(a.id, { content: e.target.value })}
                  placeholder={t('user.submit.contentPlaceholder')}
                  style={{ minHeight: 160 }}
                />
                {errors[articleErrorKey(a.id, 'content')] && (
                  <span className="field-error">{errors[articleErrorKey(a.id, 'content')]}</span>
                )}
              </div>
              <div className="row" style={{ gap: '1rem', flexWrap: 'wrap' }}>
                <div className="field" style={{ flex: '1 1 220px' }}>
                  <label className="field-label">
                    {t('user.submit.websiteName')} <span className="muted small">({t('common.optional')})</span>
                  </label>
                  <input
                    className="input"
                    value={a.websiteName}
                    onChange={(e) => updateArticle(a.id, { websiteName: e.target.value })}
                    placeholder={t('user.submit.websiteNamePlaceholder')}
                  />
                </div>
                <div className="field" style={{ flex: '2 1 280px' }}>
                  <label className="field-label">
                    {t('user.submit.websiteLink')} <span className="muted small">({t('common.optional')})</span>
                  </label>
                  <input
                    className={`input ${errors[articleErrorKey(a.id, 'websiteLink')] ? 'has-error' : ''}`}
                    value={a.websiteLink}
                    onChange={(e) => updateArticle(a.id, { websiteLink: e.target.value })}
                    placeholder={t('user.submit.websiteLinkPlaceholder')}
                    dir="ltr"
                  />
                  {errors[articleErrorKey(a.id, 'websiteLink')] && (
                    <span className="field-error">{errors[articleErrorKey(a.id, 'websiteLink')]}</span>
                  )}
                </div>
              </div>
            </div>
          ))}

          <div className="row" style={{ justifyContent: 'flex-end', gap: '0.6rem', marginTop: '1rem' }}>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={addArticle}
              disabled={submitting}
            >
              <IconPlus size={14} /> {t('user.submit.addArticle')}
            </button>
            <button type="submit" className="btn btn-primary" disabled={submitting}>
              {submitting
                ? <span className="spinner" />
                : t('user.submit.submitAll', { count: articles.length })}
            </button>
          </div>
        </form>
      </div>

      <Modal
        open={aiOpen}
        title={t('user.submit.checkTitle')}
        onClose={() => setAiOpen(false)}
        footer={
          <>
            <button className="btn btn-secondary" onClick={() => setAiOpen(false)}>{t('common.close')}</button>
            <button
              className="btn btn-primary"
              onClick={applyAiSuggestion}
              disabled={aiLoading || !aiResult || aiResult.original === aiResult.corrected}
            >
              {t('user.submit.applySuggestion')}
            </button>
          </>
        }
      >
        {aiLoading && <div className="alert alert-info">{t('user.submit.checking')}</div>}
        {aiError && <div className="alert alert-error">{aiError}</div>}
        {aiResult && !aiLoading && (
          <>
            {aiResult.notes.length > 0 && (
              <div className="alert alert-info">
                <strong>{t('user.submit.notes')}</strong>
                <ul style={{ margin: '0.4rem 0 0 1.25rem', padding: 0 }}>
                  {aiResult.notes.map((n, i) => <li key={i}>{n}</li>)}
                </ul>
              </div>
            )}
            <div className="field">
              <label className="field-label">{t('user.submit.original')}</label>
              <div style={{
                padding: '0.65rem 0.85rem', background: 'var(--bg-sunken)',
                borderRadius: 'var(--radius)', whiteSpace: 'pre-wrap',
              }}>{aiResult.original || <span className="muted">{t('user.submit.emptyText')}</span>}</div>
            </div>
            <div className="field">
              <label className="field-label">{t('user.submit.suggested')}</label>
              <div style={{
                padding: '0.65rem 0.85rem', background: 'var(--success-soft)',
                border: '1px solid var(--success-soft-border)', borderRadius: 'var(--radius)',
                whiteSpace: 'pre-wrap', color: 'var(--text-primary)',
              }}>{aiResult.corrected}</div>
            </div>
          </>
        )}
      </Modal>

      <Modal
        open={docOpen}
        title={t('user.submit.docTitle')}
        onClose={() => setDocOpen(false)}
        footer={
          <>
            <button className="btn btn-secondary" onClick={() => setDocOpen(false)}>{t('common.close')}</button>
            <button
              className="btn btn-primary"
              onClick={insertDocIntoArticle}
              disabled={!docResult || docLoading}
            >
              {docTargetId != null
                ? t('user.submit.insertIntoArticle', { n: (articles.findIndex(a => a.id === docTargetId) + 1) || 1 })
                : t('user.submit.pdfUseAsContent')}
            </button>
          </>
        }
      >
        <div className="alert alert-info" style={{ marginBottom: '0.75rem' }}>
          {t('user.submit.docHint')}
        </div>
        <div className="field">
          <label className="field-label">{t('user.submit.docFile')}</label>
          <input
            type="file"
            accept=".pdf,.doc,.docx,.docm,.xls,.xlsx,.xlsm,.xlsb,.csv,.ppt,.pptx,.pptm,.odt,.ods,.odp,.rtf,.epub,.txt,.md,.html,.htm,.xml,.json,.jpg,.jpeg,.png,.tif,.tiff,.bmp,.gif,.webp,application/pdf,image/*"
            className="input"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) onDocChosen(file);
            }}
          />
        </div>
        {docLoading && <div className="alert alert-info">{t('user.submit.pdfExtracting')}</div>}
        {docError && <div className="alert alert-error">{docError}</div>}
        {docResult && !docLoading && (
          <>
            <div className="small muted" style={{ marginBottom: 6 }}>
              {t('user.submit.pdfExtractedFrom', { name: docResult.filename })} · {docResult.characters.toLocaleString()} {t('user.submit.pdfChars')}
              {docResult.truncated ? ` · ${t('user.submit.pdfTruncated')}` : ''}
            </div>
            <textarea
              className="textarea"
              readOnly
              value={docResult.text}
              style={{ minHeight: 220 }}
            />
            {docResult.warnings.length > 0 && (
              <div className="alert alert-info" style={{ marginTop: '0.75rem' }}>
                <ul style={{ margin: 0, paddingInlineStart: '1.25rem' }}>
                  {docResult.warnings.map((w, i) => <li key={i}>{w}</li>)}
                </ul>
              </div>
            )}
          </>
        )}
      </Modal>
    </div>
  );
}

function DynamicField({
  field, value, onChange, error,
}: {
  field: CustomField; value: string; onChange: (v: string) => void; error?: string;
}) {
  const { t } = useT();
  const cls = `${error ? 'has-error' : ''}`;
  const commonProps = {
    value,
    onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
      onChange(e.target.value),
    placeholder: field.placeholder ?? '',
  };

  let control: React.ReactNode;
  switch (field.type) {
    case 'TEXTAREA':
      control = <textarea className={`textarea ${cls}`} {...commonProps} />;
      break;
    case 'NUMBER':
      control = <input type="number" className={`input ${cls}`} {...commonProps} />;
      break;
    case 'URL':
      control = <input type="url" className={`input ${cls}`} {...commonProps} placeholder={field.placeholder ?? 'https://…'} dir="ltr" />;
      break;
    case 'EMAIL':
      control = <input type="email" className={`input ${cls}`} {...commonProps} dir="ltr" />;
      break;
    case 'DATE':
      control = <input type="date" className={`input ${cls}`} {...commonProps} />;
      break;
    case 'SELECT': {
      const opts = (field.options ?? '').split(',').map((s) => s.trim()).filter(Boolean);
      control = (
        <select className={`select ${cls}`} value={value} onChange={(e) => onChange(e.target.value)}>
          <option value="">{field.placeholder || t('user.submit.chooseDepartment')}</option>
          {opts.map((o) => <option key={o} value={o}>{o}</option>)}
        </select>
      );
      break;
    }
    default:
      control = <input type="text" className={`input ${cls}`} {...commonProps} />;
  }

  return (
    <div className="field">
      <label className="field-label">
        {field.label}{field.required && <span className="req">*</span>}
      </label>
      {control}
      {error && <span className="field-error">{error}</span>}
    </div>
  );
}
