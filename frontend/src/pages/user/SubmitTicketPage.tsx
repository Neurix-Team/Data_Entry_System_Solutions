import { FormEvent, useCallback, useEffect, useRef, useState } from 'react';
import { extractError } from '../../api/client';
import {
  ChunkedUploadUnsupportedError,
  DEFAULT_CHUNK_PARALLELISM,
  uploadFileChunked,
  type UploadProgress,
} from '../../api/chunkedUpload';
import { useToast } from '../../components/toast/ToastContext';
import {
  aiApi,
  documentsApi,
  ticketsApi,
} from '../../api/resources';
import type {
  ArticleInput,
  ExtractedPdf,
  ResourceInput,
} from '../../api/types';
import { useAuth } from '../../context/AuthContext';
import { IconFolder, IconPlus, IconTasks } from '../../components/Icons';
import { UploadHud } from '../../components/UploadHud';
import { useT } from '../../i18n';
import { pickLocalized } from '../../i18n/localized';
import { extractTitleFromFile } from '../../utils/titleFromFile';
import { AiCheckDialog, type AiResult } from './submit/AiCheckDialog';
import {
  ArticleCard,
  articleErrorKey,
  documentErrorKey,
  resourceErrorKey,
  type ArticleMode,
  type ArticleRow,
  type DocumentRow,
  type ResourceRow,
} from './submit/ArticleCard';
import { CustomFieldsSection } from './submit/CustomFieldsSection';
import { DocumentUploadDialog } from './submit/DocumentUploadDialog';
import { ShortcutsHelp } from './submit/ShortcutsHelp';
import { useSubmitDraft, type SubmitDraft } from './submit/useDraft';
import { useArticles } from './submit/useArticles';
import { useFastEntry } from './submit/useFastEntry';
import { useSubmitTicketData } from './submit/useSubmitTicketData';

function LiveDateInput() {
  const { lang } = useT();
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(id);
  }, []);
  const label = now.toLocaleString(lang === 'ar' ? 'ar-EG' : undefined);
  return <input className="input" value={label} readOnly />;
}

function isValidUrl(s: string): boolean {
  try {
    const u = new URL(s);
    return (u.protocol === 'http:' || u.protocol === 'https:') && !!u.host;
  } catch {
    return false;
  }
}

export function SubmitTicketPage() {
  const { t, lang } = useT();
  const toast = useToast();
  const { user } = useAuth();
  const draft = useSubmitDraft(user?.id);

  const {
    projects, departments, subcategories, fields,
    loading, loadError,
    projectId, departmentId, subcategoryId,
    setProjectId, setDepartmentId, setSubcategoryId,
  } = useSubmitTicketData();

  const [customValues, setCustomValues] = useState<Record<string, string>>({});

  useEffect(() => {
    setCustomValues({});
  }, [subcategoryId]);
  const {
    articles,
    add: addArticle, remove: removeArticle, update: updateArticle,
    addResource, removeResource, updateResource,
    addDocument, removeDocument, updateDocument,
    appendExtractedImages, removeExtractedImage, updateExtractedImage,
    addArticlesFromFiles,
    reset: resetArticles,
  } = useArticles();

  const bulkFilesInputRef = useRef<HTMLInputElement | null>(null);
  const formRef = useRef<HTMLFormElement | null>(null);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);

  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const [uploadHud, setUploadHud] = useState<{
    name: string; index: number; count: number; progress: UploadProgress;
  } | null>(null);

  const autoTitles = useRef<Map<number, string>>(new Map());
  const articlesRef = useRef(articles);
  articlesRef.current = articles;

  const [articleMode, setArticleMode] = useState<ArticleMode | null>(null);

  const [aiOpen, setAiOpen] = useState(false);
  const [aiTargetId, setAiTargetId] = useState<number | null>(null);
  const [aiLoading, setAiLoading] = useState(false);
  const [aiResult, setAiResult] = useState<AiResult | null>(null);
  const [aiError, setAiError] = useState<string | null>(null);

  const [docOpen, setDocOpen] = useState(false);
  const [docTargetId, setDocTargetId] = useState<number | null>(null);
  const [docLoading, setDocLoading] = useState(false);
  const [docResult, setDocResult] = useState<ExtractedPdf | null>(null);
  const [docError, setDocError] = useState<string | null>(null);

  // ── Draft autosave (B1) ────────────────────────────────────────────────────
  const [draftAvailable, setDraftAvailable] = useState<SubmitDraft | null>(() => null);
  const [draftReady, setDraftReady] = useState(false);
  const skipDraftSave = useRef(true);

  // Read the saved draft once the user is known (localStorage is keyed by user).
  useEffect(() => {
    if (draftReady) return;
    setDraftAvailable(user?.id ? draft.read() : null);
    setDraftReady(true);
    skipDraftSave.current = true; // never autosave the untouched mount state
  }, [draftReady, user?.id, draft]);

  function serializeDraft(): Omit<SubmitDraft, 'savedAt'> {
    return {
      projectId,
      departmentId,
      subcategoryId,
      customValues,
      articles: articles.map((a) => ({
        title: a.title,
        content: a.content,
        resources: a.resources.map((r) => ({ name: r.name, link: r.link })),
      })),
    };
  }

  // Debounced autosave on every meaningful change.
  useEffect(() => {
    if (!draftReady || skipDraftSave.current) return;
    const hasContent = articles.some((a) => a.title.trim() || a.content.trim())
      || Object.values(customValues).some((v) => v && v.trim());
    if (!hasContent) return;
    const id = window.setTimeout(() => { draft.save(serializeDraft()); }, 800);
    return () => window.clearTimeout(id);
    // serializeDraft closes over the same values listed below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draftReady, draft, projectId, departmentId, subcategoryId, customValues, articles]);

  // Enter walks the fields, Ctrl+Enter sends, Alt+N opens a fresh card. Entry here is
  // repetitive by nature, and a hand that never leaves the keyboard is a faster hand.
  const showShortcuts = useCallback(() => setShortcutsOpen(true), []);
  useFastEntry(formRef, {
    onAddArticle: addArticle,
    onShowShortcuts: showShortcuts,
    disabled: submitting,
  });

  // Ctrl+S saves the draft right away; Ctrl+K is handled globally by the palette.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault();
        if (skipDraftSave.current) skipDraftSave.current = false;
        const at = draft.save(serializeDraft());
        toast.info(at ? t('user.submit.draftSaved') : t('user.submit.draftSaveFailed'));
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft, projectId, departmentId, subcategoryId, customValues, articles, t]);

  function restoreDraft(d: SubmitDraft) {
    skipDraftSave.current = true;
    setProjectId(d.projectId as typeof projectId);
    setDepartmentId(d.departmentId as typeof departmentId);
    setSubcategoryId(d.subcategoryId as typeof subcategoryId);
    setCustomValues(d.customValues ?? {});
    resetArticles();
    // Deterministic replay of useArticles' id counters (articles from 1, extra
    // resources from 2 — see useArticles.ts), so every update hits the right row.
    let resCounter = 2;
    d.articles.forEach((a, i) => {
      const rowId = i; // add() assigns ids 1,2,3… in order
      if (i > 0) addArticle();
      const firstResId = i === 0 ? 0 : resCounter++;
      a.resources.forEach((r, j) => {
        if (j > 0) addResource(rowId);
        const rid = j === 0 ? firstResId : resCounter++;
        updateResource(rowId, rid, { name: r.name ?? '', link: r.link ?? '' });
      });
      updateArticle(rowId, { title: a.title ?? '', content: a.content ?? '' });
    });
    setDraftAvailable(null);
    draft.clear();
    toast.info(t('user.submit.draftRestored'));
  }

  function discardDraft() {
    draft.clear();
    setDraftAvailable(null);
  }


  function validate(): boolean {
    const errs: Record<string, string> = {};

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
      if (articleMode === 'content') {
        if (!a.title.trim()) errs[articleErrorKey(a.id, 'title')] = t('user.submit.errTitle');
        if (!a.content.trim()) errs[articleErrorKey(a.id, 'content')] = t('user.submit.errContent');
      }
      for (const r of a.resources) {
        const link = r.link.trim();
        if (link && !isValidUrl(link)) {
          errs[resourceErrorKey(a.id, r.id, 'link')] = t('user.submit.errUrlInvalid');
        }
      }
      if (articleMode === 'attachments') {
        const hasAttachments =
          a.documents.some((d) => d.file != null) || a.extractedImages.length > 0;
        const hasResource = a.resources.some((r) => r.link.trim().length > 0);
        if (!hasAttachments && !hasResource) {
          const firstDocId = a.documents[0]?.id;
          if (firstDocId != null) {
            errs[documentErrorKey(a.id, firstDocId, 'file')] = t('user.submit.errAttachmentRequired');
          }
        }
      }
    }

    setErrors(errs);
    return Object.keys(errs).length === 0;
  }

  function hiddenErrorFor(_: ArticleRow): boolean {
    return false;
  }


  function buildResources(row: ArticleRow): ResourceInput[] {
    return row.resources
      .map((r) => ({ name: r.name.trim() || undefined, url: r.link.trim() }))
      .filter((r) => r.url.length > 0);
  }

  function pickPrimaryResource(row: ArticleRow): { name?: string; url?: string } {
    const first = row.resources.find((r) => r.link.trim().length > 0);
    return {
      name: first?.name.trim() || undefined,
      url: first?.link.trim() || undefined,
    };
  }

  async function uploadArticleDocumentsAllOrNothing(
    ticketId: number, docs: DocumentRow[], counter: { index: number; count: number },
  ): Promise<number> {
    let ok = 0;
    for (const d of docs) {
      if (!d.file) continue;
      const file = d.file;
      const name = d.name.trim() || file.name;
      counter.index += 1;
      const show = (progress: UploadProgress) =>
        setUploadHud({ name, index: counter.index, count: counter.count, progress });
      show({ phase: 'starting', loaded: 0, total: file.size, fraction: 0, bytesPerSecond: 0, etaSeconds: null });
      try {
        await uploadFileChunked({
          file,
          target: { kind: 'TICKET_DOCUMENT', ticketId, name },
          parallel: DEFAULT_CHUNK_PARALLELISM,
          onProgress: show,
        });
      } catch (err) {
        if (!(err instanceof ChunkedUploadUnsupportedError)) throw err;
        await ticketsApi.uploadDocument(ticketId, name, file);
      }
      ok += 1;
    }
    return ok;
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setSubmitError(null);
    setSuccess(null);
    if (!validate()) return;

    setSubmitting(true);
    try {
      const trimmedCustom: Record<string, string> = {};
      Object.entries(customValues).forEach(([k, v]) => {
        trimmedCustom[k] = (v ?? '').trim();
      });

      const payloadArticles: ArticleInput[] = articles.map((a) => {
        const primary = pickPrimaryResource(a);
        return {
          title: a.title.trim(),
          content: a.content.trim(),
          websiteName: primary.name,
          websiteLink: primary.url,
          resources: buildResources(a),
          extractedImages: a.extractedImages.map((img) => ({
            name: img.name.trim() || img.filename,
            extractionId: img.extractionId,
            filename: img.filename,
          })),
        };
      });

      const res = await ticketsApi.submitBulk({
        departmentId: departmentId ? Number(departmentId) : null,
        subcategoryId: subcategoryId ? Number(subcategoryId) : null,
        projectId: projectId ? Number(projectId) : null,
        articles: payloadArticles,
        customValues: trimmedCustom,
      });

      let uploaded = 0;
      const counter = {
        index: 0,
        count: articles.reduce((n, a) => n + a.documents.filter((d) => d.file != null).length, 0),
      };
      try {
        for (let i = 0; i < res.tickets.length && i < articles.length; i++) {
          uploaded += await uploadArticleDocumentsAllOrNothing(
            res.tickets[i].id, articles[i].documents, counter,
          );
        }
      } catch (uploadErr) {
        await Promise.allSettled(res.tickets.map((tk) => ticketsApi.removeMine(tk.id)));
        throw uploadErr;
      }

      const msg = t('user.submit.bulkSuccess', { count: res.created });
      setSuccess(msg);
      toast.success(msg);
      if (uploaded > 0) {
        toast.success(t('user.submit.documentUploadedCount', { count: uploaded }));
      }
      resetArticles();
      autoTitles.current.clear();
      setErrors({});
      draft.clear(); // submitted — the draft served its purpose
      setDraftAvailable(null);
    } catch (err) {
      setSubmitError(extractError(err, t('user.submit.submitFailed')));
    } finally {
      setSubmitting(false);
      setUploadHud(null);
    }
  }

  async function autoTitleFromAttachment(articleId: number, documentId: number, file: File) {
    const title = await extractTitleFromFile(file);
    if (!title) return;
    const article = articlesRef.current.find((a) => a.id === articleId);
    if (!article) return;
    const current = article.title.trim();
    const previousAuto = autoTitles.current.get(articleId);
    if (current === '' || current === previousAuto) {
      autoTitles.current.set(articleId, title);
      updateArticle(articleId, { title });
    }
    const doc = article.documents.find((d) => d.id === documentId);
    if (doc && !doc.name.trim()) {
      updateDocument(articleId, documentId, { name: title });
    }
  }


  function openAiCheck(articleId: number) {
    const target = articles.find((a) => a.id === articleId);
    if (!target || !target.content.trim()) return;
    setAiTargetId(articleId);
    setAiError(null);
    setAiResult(null);
    setAiOpen(true);
    setAiLoading(true);
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
    const target = articles.find((a) => a.id === docTargetId);
    if (!target) return;
    updateArticle(docTargetId, {
      title: target.title.trim() ? target.title : suggestedTitle,
      content: target.content.trim() ? `${target.content}\n\n${docResult.text}` : docResult.text,
    });
    if (docResult.extractionId && docResult.images.length > 0) {
      appendExtractedImages(
        docTargetId,
        docResult.extractionId,
        docResult.images,
        suggestedTitle || t('user.submit.extractedImageDefaultName'),
      );
    }
    setDocOpen(false);
  }


  if (loading) {
    return (
      <div className="page">
        <div className="card">
          <span className="muted">{t('user.submit.loadingForm')}</span>
        </div>
      </div>
    );
  }

  const docTargetIndex = docTargetId != null
    ? articles.findIndex((a) => a.id === docTargetId)
    : -1;

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1>{t('user.submit.title')}</h1>
          <p className="subtitle">{t('user.submit.subtitleBulk')}</p>
        </div>
      </div>

      {draftAvailable && (
        <div
          role="status"
          style={{
            display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap',
            background: 'var(--brand-soft)', border: '1px solid var(--brand-border)',
            borderRadius: 'var(--radius)', padding: '0.6rem 0.9rem', marginBottom: 12,
          }}
        >
          <span style={{ fontSize: 15 }}>📩</span>
          <span style={{ flex: 1, minWidth: 200, fontSize: 13 }}>
            {t('user.submit.draftFound', {
              time: new Date(draftAvailable.savedAt).toLocaleString(lang === 'ar' ? 'ar-EG' : undefined),
            })}
            <span className="muted small" style={{ display: 'block' }}>
              {t('user.submit.draftHint')}
            </span>
          </span>
          <button type="button" className="btn btn-sm btn-primary" onClick={() => restoreDraft(draftAvailable)}>
            {t('user.submit.draftRestore')}
          </button>
          <button type="button" className="btn btn-sm btn-ghost" onClick={discardDraft}>
            {t('user.submit.draftDiscard')}
          </button>
        </div>
      )}

      {loadError && <div className="alert alert-error">{loadError}</div>}
      {submitError && <div className="alert alert-error">{submitError}</div>}
      {success && <div className="alert alert-success">{success}</div>}

      <div className="card">
        <form onSubmit={onSubmit} noValidate ref={formRef}>
          <div className="form-row">
            <div className="field field-grow-sm">
              <label className="field-label">{t('user.submit.date')}</label>
              <LiveDateInput />
            </div>
            <div className="field field-grow">
              <label className="field-label">
                {lang === 'ar' ? 'المشروع' : 'Project'}
                {projects.length > 0 && <span className="req">*</span>}
              </label>
              <select
                className="select"
                value={projectId}
                onChange={(e) => setProjectId(e.target.value)}
                disabled={projects.length <= 1}
              >
                {projects.length === 0 && (
                  <option value="">
                    {lang === 'ar' ? 'لم يخصص لك مشروع بعد' : 'No project assigned yet'}
                  </option>
                )}
                {projects.length > 1 && (
                  <option value="">
                    {lang === 'ar' ? 'اختار المشروع' : 'Choose a project'}
                  </option>
                )}
                {projects.map((p) => (
                  <option key={p.id} value={p.id}>{pickLocalized(p, 'name', lang)}</option>
                ))}
              </select>
            </div>
            <div className="field field-grow">
              <label className="field-label">
                {t('user.submit.department')}{' '}
                <span className="muted small">({t('common.optional')})</span>
              </label>
              <select
                className="select"
                value={departmentId}
                onChange={(e) => setDepartmentId(e.target.value)}
                disabled={departments.length === 0}
              >
                <option value="">
                  {lang === 'ar' ? 'كل الأقسام في المشروع' : 'All departments in project'}
                </option>
                {departments.map((d) => (
                  <option key={d.id} value={d.id}>{pickLocalized(d, 'name', lang)}</option>
                ))}
              </select>
            </div>
            <div className="field field-grow">
              <label className="field-label">
                {t('user.submit.subcategory')}{' '}
                <span className="muted small">({t('common.optional')})</span>
              </label>
              <select
                className="select"
                value={subcategoryId}
                onChange={(e) => setSubcategoryId(e.target.value)}
                disabled={subcategories.length === 0}
              >
                <option value="">
                  {lang === 'ar' ? 'بدون تصنيف' : 'No subcategory'}
                </option>
                {subcategories.map((s) => (
                  <option key={s.id} value={s.id}>{pickLocalized(s, 'name', lang)}</option>
                ))}
              </select>
            </div>
          </div>

          <CustomFieldsSection
            fields={fields}
            values={customValues}
            errors={errors}
            onChange={setCustomValues}
          />

          <div className="row-between form-row-mt">
            <div className="form-section-heading" style={{ margin: 0 }}>
              {t('user.submit.articles')} ({articles.length})
            </div>
            <div className="row gap-2">
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={() => bulkFilesInputRef.current?.click()}
                disabled={submitting}
                title={lang === 'ar'
                  ? 'اختر أكثر من ملف مرة واحدة — كل ملف هيبقى تذكرة والعنوان هيتعبى من اسم الملف'
                  : 'Pick many files at once — each becomes its own ticket with the title auto-filled from the filename'}
              >
                <IconPlus size={14} />{' '}
                {lang === 'ar' ? 'رفع أكثر من ملف' : 'Upload multiple files'}
              </button>
              <button
                type="button"
                className="btn btn-primary btn-sm"
                onClick={addArticle}
                disabled={submitting}
              >
                <IconPlus size={14} /> {t('user.submit.addArticle')}
              </button>
            </div>
            <input
              ref={bulkFilesInputRef}
              type="file"
              multiple
              style={{ display: 'none' }}
              onChange={(e) => {
                if (e.target.files && e.target.files.length > 0) {
                  addArticlesFromFiles(Array.from(e.target.files));
                  setArticleMode('attachments');
                }
                e.target.value = '';
              }}
            />
          </div>

          <div
            className="article-mode-picker"
            role="tablist"
            aria-label={t('user.submit.tabAriaLabel')}
          >
            <button
              type="button"
              role="tab"
              aria-selected={articleMode === 'content'}
              className={`article-mode-card article-mode-card-content ${articleMode === 'content' ? 'is-active' : ''} ${articleMode === 'attachments' ? 'is-dim' : ''}`}
              onClick={() => setArticleMode('content')}
            >
              <span className="article-mode-card-icon" aria-hidden="true">
                <IconTasks size={22} />
              </span>
              <span className="article-mode-card-text">
                <span className="article-mode-card-title">{t('user.submit.tabContent')}</span>
                <span className="article-mode-card-desc">{t('user.submit.tabContentDesc')}</span>
              </span>
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={articleMode === 'attachments'}
              className={`article-mode-card article-mode-card-attachments ${articleMode === 'attachments' ? 'is-active' : ''} ${articleMode === 'content' ? 'is-dim' : ''}`}
              onClick={() => setArticleMode('attachments')}
            >
              <span className="article-mode-card-icon" aria-hidden="true">
                <IconFolder size={22} />
              </span>
              <span className="article-mode-card-text">
                <span className="article-mode-card-title">{t('user.submit.tabAttachments')}</span>
                <span className="article-mode-card-desc">{t('user.submit.tabAttachmentsDesc')}</span>
              </span>
            </button>
          </div>

          {articleMode !== null && (
            <div className="article-mode-panel" data-mode={articleMode} key={articleMode}>
              {articles.map((a: ArticleRow, idx: number) => (
                <ArticleCard
                  key={a.id}
                  article={a}
                  index={idx}
                  mode={articleMode}
                  showRemove={articles.length > 1}
                  submitting={submitting}
                  errors={errors}
                  hasHiddenError={hiddenErrorFor(a)}
                  onChange={(patch) => updateArticle(a.id, patch)}
                  onRemove={() => removeArticle(a.id)}
                  onUploadDoc={() => openDocModalFor(a.id)}
                  onAiCheck={() => openAiCheck(a.id)}
                  onAddResource={() => addResource(a.id)}
                  onRemoveResource={(rid: number) => removeResource(a.id, rid)}
                  onUpdateResource={(rid: number, patch: Partial<ResourceRow>) => updateResource(a.id, rid, patch)}
                  onAddDocument={() => addDocument(a.id)}
                  onRemoveDocument={(did: number) => removeDocument(a.id, did)}
                  onUpdateDocument={(did: number, patch: Partial<DocumentRow>) => {
                    updateDocument(a.id, did, patch);
                    if (patch.file) void autoTitleFromAttachment(a.id, did, patch.file);
                  }}
                  onRemoveExtractedImage={(iid: number) => removeExtractedImage(a.id, iid)}
                  onUpdateExtractedImage={(iid, patch) => updateExtractedImage(a.id, iid, patch)}
                />
              ))}
            </div>
          )}

          {uploadHud && (
            <UploadHud
              lang={lang}
              size={56}
              title={t('user.submit.uploadingFileOf', { n: uploadHud.index, count: uploadHud.count })}
              subtitle={uploadHud.name}
              progress={uploadHud.progress}
              state={uploadHud.progress.phase === 'finalizing'
                ? 'finalizing'
                : uploadHud.progress.phase === 'done' ? 'done' : 'uploading'}
            />
          )}

          <div className="form-row-end">
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              onClick={showShortcuts}
              title={t('user.submit.shortcuts.open')}
            >
              <kbd>?</kbd> {t('user.submit.shortcuts.open')}
            </button>
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

      <ShortcutsHelp open={shortcutsOpen} onClose={() => setShortcutsOpen(false)} />

      <AiCheckDialog
        open={aiOpen}
        loading={aiLoading}
        result={aiResult}
        error={aiError}
        onClose={() => setAiOpen(false)}
        onApply={applyAiSuggestion}
      />

      <DocumentUploadDialog
        open={docOpen}
        loading={docLoading}
        result={docResult}
        error={docError}
        articleIndex={docTargetIndex >= 0 ? docTargetIndex : null}
        onClose={() => setDocOpen(false)}
        onFileChosen={onDocChosen}
        onInsert={insertDocIntoArticle}
      />
    </div>
  );
}
