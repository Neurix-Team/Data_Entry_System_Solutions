import { FormEvent, useEffect, useMemo, useState } from 'react';
import { extractError } from '../../api/client';
import { useToast } from '../../components/toast/ToastContext';
import {
  aiApi,
  departmentsApi,
  documentsApi,
  fieldsApi,
  projectsApi,
  subcategoriesApi,
  ticketsApi,
} from '../../api/resources';
import type {
  ArticleInput,
  CustomField,
  Department,
  ExtractedPdf,
  Project,
  ResourceInput,
  Subcategory,
} from '../../api/types';
import { IconFolder, IconPlus, IconTasks } from '../../components/Icons';
import { useT } from '../../i18n';
import { pickLocalized } from '../../i18n/localized';
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
import { useArticles } from './submit/useArticles';

interface CoreForm {
  departmentId: string;
  subcategoryId: string;
  projectId: string;
}

const initialCore: CoreForm = { departmentId: '', subcategoryId: '', projectId: '' };

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
  } catch {
    return false;
  }
}

export function SubmitTicketPage() {
  const { t, lang } = useT();
  const toast = useToast();
  const now = useNowTick();

  // -- reference data --
  const [departments, setDepartments] = useState<Department[]>([]);
  const [subcategories, setSubcategories] = useState<Subcategory[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [fields, setFields] = useState<CustomField[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  // -- form state --
  const [core, setCore] = useState<CoreForm>(initialCore);
  const [customValues, setCustomValues] = useState<Record<string, string>>({});
  const {
    articles,
    add: addArticle, remove: removeArticle, update: updateArticle,
    addResource, removeResource, updateResource,
    addDocument, removeDocument, updateDocument,
    appendExtractedImages, removeExtractedImage, updateExtractedImage,
    reset: resetArticles,
  } = useArticles();

  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  // Which half of the article form is visible. Null on first render — nothing shows
  // until the user picks a mode from the two big picker cards. Once picked they can
  // switch freely; there is no "back to unpicked" state, only a swap.
  const [articleMode, setArticleMode] = useState<ArticleMode | null>(null);

  // -- AI check dialog --
  const [aiOpen, setAiOpen] = useState(false);
  const [aiTargetId, setAiTargetId] = useState<number | null>(null);
  const [aiLoading, setAiLoading] = useState(false);
  const [aiResult, setAiResult] = useState<AiResult | null>(null);
  const [aiError, setAiError] = useState<string | null>(null);

  // -- document extract dialog (legacy — extracts text into an article) --
  const [docOpen, setDocOpen] = useState(false);
  const [docTargetId, setDocTargetId] = useState<number | null>(null);
  const [docLoading, setDocLoading] = useState(false);
  const [docResult, setDocResult] = useState<ExtractedPdf | null>(null);
  const [docError, setDocError] = useState<string | null>(null);

  // ---- data loading effects (split into single-purpose effects) ----

  useEffect(() => {
    // Projects are optional on a ticket — silently drop the list if the request fails
    // (e.g. the /api/projects endpoint isn't deployed yet on an older backend).
    const ctrl = new AbortController();
    projectsApi.userList(ctrl.signal).then(setProjects).catch(() => setProjects([]));
    return () => ctrl.abort();
  }, []);

  // Fetch departments whenever project scope changes. AbortController stops a stale
  // response from overwriting a fresher one if the user changes the dropdown quickly.
  useEffect(() => {
    const ctrl = new AbortController();
    const pid = core.projectId ? Number(core.projectId) : undefined;
    departmentsApi.userList(pid, ctrl.signal)
      .then(setDepartments)
      .catch((e) => { if (!ctrl.signal.aborted) setLoadError(extractError(e)); })
      .finally(() => { if (!ctrl.signal.aborted) setLoading(false); });
    return () => ctrl.abort();
  }, [core.projectId]);

  // Reset department & downstream state whenever the project scope changes.
  useEffect(() => {
    setCore((c) => (c.departmentId || c.subcategoryId ? { ...c, departmentId: '', subcategoryId: '' } : c));
    setSubcategories([]);
    setFields([]);
    setCustomValues({});
  }, [core.projectId]);

  // Fetch subcategories whenever department changes
  useEffect(() => {
    if (!core.departmentId) {
      setSubcategories([]);
      return;
    }
    const ctrl = new AbortController();
    subcategoriesApi.userList(Number(core.departmentId), ctrl.signal)
      .then(setSubcategories)
      .catch((e) => { if (!ctrl.signal.aborted) setLoadError(extractError(e)); });
    return () => ctrl.abort();
  }, [core.departmentId]);

  // Reset subcategory selection and downstream state when department changes
  useEffect(() => {
    setCore((c) => (c.subcategoryId ? { ...c, subcategoryId: '' } : c));
    setFields([]);
    setCustomValues({});
  }, [core.departmentId]);

  // Fetch custom fields whenever subcategory changes
  useEffect(() => {
    if (!core.subcategoryId) {
      setFields([]);
      return;
    }
    const ctrl = new AbortController();
    fieldsApi.activeList(Number(core.subcategoryId), ctrl.signal)
      .then(setFields)
      .catch((e) => { if (!ctrl.signal.aborted) setLoadError(extractError(e)); });
    return () => ctrl.abort();
  }, [core.subcategoryId]);

  // Reset field values when subcategory changes
  useEffect(() => {
    setCustomValues({});
  }, [core.subcategoryId]);

  const dateLabel = useMemo(
    () => now.toLocaleString(lang === 'ar' ? 'ar-EG' : undefined),
    [now, lang]
  );

  // ---- validation ----

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
      // The two tabs are independent submission surfaces:
      //   • 'content' tab       → the article is written text; require title + content.
      //   • 'attachments' tab   → the article is a file bundle; the text fields are
      //                            invisible on this tab and are NOT enforced.
      // The user explicitly asked that submitting from one tab never trigger errors in
      // the other tab. We still block a totally empty article as a last-line sanity check.
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
      // In attachments mode a completely empty article (no file, no image, no resource)
      // would create a ghost ticket — flag it on the first document slot so the message
      // lands where the user is looking.
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

  /** Validation is now mode-scoped: the tab the user is looking at is the ONLY tab that
   *  can generate errors, so there is never anything hidden in the other half to warn about. */
  function hiddenErrorFor(_: ArticleRow): boolean {
    return false;
  }

  // ---- submit ----

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

  /**
   * Uploads every document for one ticket. Throws on the first failure so the caller can
   * roll the ticket back — partial success would leave orphan tickets with no attachments,
   * which the user cannot distinguish from a working ticket.
   */
  async function uploadArticleDocumentsAllOrNothing(
    ticketId: number, docs: DocumentRow[],
  ): Promise<number> {
    let ok = 0;
    for (const d of docs) {
      if (!d.file) continue;
      const name = d.name.trim() || d.file.name;
      await ticketsApi.uploadDocument(ticketId, name, d.file);
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
        departmentId: Number(core.departmentId),
        subcategoryId: Number(core.subcategoryId),
        projectId: core.projectId ? Number(core.projectId) : null,
        articles: payloadArticles,
        customValues: trimmedCustom,
      });

      // Upload documents per article. If any upload fails, roll back every ticket in this
      // batch so the user isn't left with orphan rows they think succeeded.
      let uploaded = 0;
      try {
        for (let i = 0; i < res.tickets.length && i < articles.length; i++) {
          uploaded += await uploadArticleDocumentsAllOrNothing(
            res.tickets[i].id, articles[i].documents,
          );
        }
      } catch (uploadErr) {
        // Best-effort rollback; if one delete also fails, keep trying the rest so we
        // clean up as much as we can before surfacing the original failure to the user.
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
      setErrors({});
    } catch (err) {
      setSubmitError(extractError(err, t('user.submit.submitFailed')));
    } finally {
      setSubmitting(false);
    }
  }

  // ---- AI dialog ----

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

  // ---- doc dialog ----

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
    // Fold the extracted images into the article so they travel with the submission.
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

  // ---- render ----

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

      {loadError && <div className="alert alert-error">{loadError}</div>}
      {submitError && <div className="alert alert-error">{submitError}</div>}
      {success && <div className="alert alert-success">{success}</div>}

      <div className="card">
        <form onSubmit={onSubmit} noValidate>
          <div className="form-row">
            <div className="field field-grow-sm">
              <label className="field-label">{t('user.submit.date')}</label>
              <input className="input" value={dateLabel} readOnly />
            </div>
            <div className="field field-grow">
              <label className="field-label">
                {lang === 'ar' ? 'المشروع' : 'Project'}
              </label>
              <select
                className="select"
                value={core.projectId}
                onChange={(e) => setCore({ ...core, projectId: e.target.value })}
                disabled={projects.length === 0}
              >
                <option value="">
                  {lang === 'ar' ? 'بدون مشروع (اختياري)' : 'No project (optional)'}
                </option>
                {projects.map((p) => (
                  <option key={p.id} value={p.id}>{pickLocalized(p, 'name', lang)}</option>
                ))}
              </select>
            </div>
            <div className="field field-grow">
              <label className="field-label">
                {t('user.submit.department')} <span className="req">*</span>
              </label>
              <select
                className={`select ${errors.departmentId ? 'has-error' : ''}`}
                value={core.departmentId}
                onChange={(e) => setCore({ ...core, departmentId: e.target.value })}
                disabled={departments.length === 0}
              >
                <option value="">{t('user.submit.chooseDepartment')}</option>
                {departments.map((d) => (
                  <option key={d.id} value={d.id}>{pickLocalized(d, 'name', lang)}</option>
                ))}
              </select>
              {errors.departmentId && <span className="field-error">{errors.departmentId}</span>}
            </div>
            <div className="field field-grow">
              <label className="field-label">
                {t('user.submit.subcategory')} <span className="req">*</span>
              </label>
              <select
                className={`select ${errors.subcategoryId ? 'has-error' : ''}`}
                value={core.subcategoryId}
                onChange={(e) => setCore({ ...core, subcategoryId: e.target.value })}
                disabled={!core.departmentId}
              >
                <option value="">{t('user.submit.chooseSubcategory')}</option>
                {subcategories.map((s) => (
                  <option key={s.id} value={s.id}>{pickLocalized(s, 'name', lang)}</option>
                ))}
              </select>
              {errors.subcategoryId && <span className="field-error">{errors.subcategoryId}</span>}
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
            <button
              type="button"
              className="btn btn-primary btn-sm"
              onClick={addArticle}
              disabled={submitting}
            >
              <IconPlus size={14} /> {t('user.submit.addArticle')}
            </button>
          </div>

          {/* Two big picker cards — one per view. On first load neither is selected
              and the article form stays collapsed, so the page opens quiet and the
              user's first decision is which surface they want to work on. Once
              picked, the cards stay visible as an active/inactive pair for switching. */}
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

          {/* The article list only mounts after the user has picked a mode.
              key={articleMode} makes the reveal animation replay on tab swap too. */}
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
                  onUpdateDocument={(did: number, patch: Partial<DocumentRow>) => updateDocument(a.id, did, patch)}
                  onRemoveExtractedImage={(iid: number) => removeExtractedImage(a.id, iid)}
                  onUpdateExtractedImage={(iid, patch) => updateExtractedImage(a.id, iid, patch)}
                />
              ))}
            </div>
          )}

          <div className="form-row-end">
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
