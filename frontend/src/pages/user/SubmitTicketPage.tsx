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
  Subcategory,
} from '../../api/types';
import { IconPlus } from '../../components/Icons';
import { useT } from '../../i18n';
import { AiCheckDialog, type AiResult } from './submit/AiCheckDialog';
import { ArticleCard, articleErrorKey, type ArticleRow } from './submit/ArticleCard';
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
  const { articles, add: addArticle, remove: removeArticle, update: updateArticle, reset: resetArticles } = useArticles();

  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  // -- AI check dialog --
  const [aiOpen, setAiOpen] = useState(false);
  const [aiTargetId, setAiTargetId] = useState<number | null>(null);
  const [aiLoading, setAiLoading] = useState(false);
  const [aiResult, setAiResult] = useState<AiResult | null>(null);
  const [aiError, setAiError] = useState<string | null>(null);

  // -- document upload dialog --
  const [docOpen, setDocOpen] = useState(false);
  const [docTargetId, setDocTargetId] = useState<number | null>(null);
  const [docLoading, setDocLoading] = useState(false);
  const [docResult, setDocResult] = useState<ExtractedPdf | null>(null);
  const [docError, setDocError] = useState<string | null>(null);

  // ---- data loading effects (split into single-purpose effects) ----

  useEffect(() => {
    // Projects are optional on a ticket — silently drop the list if the request fails
    // (e.g. the /api/projects endpoint isn't deployed yet on an older backend).
    projectsApi.userList().then(setProjects).catch(() => setProjects([]));
  }, []);

  useEffect(() => {
    departmentsApi.userList()
      .then(setDepartments)
      .catch((e) => setLoadError(extractError(e)))
      .finally(() => setLoading(false));
  }, []);

  // Fetch subcategories whenever department changes
  useEffect(() => {
    if (!core.departmentId) {
      setSubcategories([]);
      return;
    }
    subcategoriesApi.userList(Number(core.departmentId))
      .then(setSubcategories)
      .catch((e) => setLoadError(extractError(e)));
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
    fieldsApi.activeList(Number(core.subcategoryId))
      .then(setFields)
      .catch((e) => setLoadError(extractError(e)));
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
      if (!a.title.trim()) errs[articleErrorKey(a.id, 'title')] = t('user.submit.errTitle');
      if (!a.content.trim()) errs[articleErrorKey(a.id, 'content')] = t('user.submit.errContent');
      if (a.websiteLink.trim() && !isValidUrl(a.websiteLink.trim())) {
        errs[articleErrorKey(a.id, 'websiteLink')] = t('user.submit.errUrlInvalid');
      }
    }

    setErrors(errs);
    return Object.keys(errs).length === 0;
  }

  // ---- submit ----

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

      const payloadArticles: ArticleInput[] = articles.map((a) => ({
        title: a.title.trim(),
        content: a.content.trim(),
        websiteName: a.websiteName.trim() || undefined,
        websiteLink: a.websiteLink.trim() || undefined,
      }));

      const res = await ticketsApi.submitBulk({
        departmentId: Number(core.departmentId),
        subcategoryId: Number(core.subcategoryId),
        projectId: core.projectId ? Number(core.projectId) : null,
        articles: payloadArticles,
        customValues: trimmedCustom,
      });
      const msg = t('user.submit.bulkSuccess', { count: res.created });
      setSuccess(msg);
      toast.success(msg);
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
                {t('user.submit.department')} <span className="req">*</span>
              </label>
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
                  <option key={s.id} value={s.id}>{s.name}</option>
                ))}
              </select>
              {errors.subcategoryId && <span className="field-error">{errors.subcategoryId}</span>}
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
                  <option key={p.id} value={p.id}>{p.name}</option>
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
            <button
              type="button"
              className="btn btn-primary btn-sm"
              onClick={addArticle}
              disabled={submitting}
            >
              <IconPlus size={14} /> {t('user.submit.addArticle')}
            </button>
          </div>

          {articles.map((a: ArticleRow, idx: number) => (
            <ArticleCard
              key={a.id}
              article={a}
              index={idx}
              showRemove={articles.length > 1}
              submitting={submitting}
              errors={errors}
              onChange={(patch) => updateArticle(a.id, patch)}
              onRemove={() => removeArticle(a.id)}
              onUploadDoc={() => openDocModalFor(a.id)}
              onAiCheck={() => openAiCheck(a.id)}
            />
          ))}

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
