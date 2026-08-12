import { IconClose, IconPlus } from '../../../components/Icons';
import { useT } from '../../../i18n';

export interface ResourceRow {
  id: number;
  name: string;
  link: string;
}

export interface DocumentRow {
  id: number;
  name: string;
  file: File | null;
}

/** An image the backend pulled out of an uploaded PDF and parked in the extractions
 *  staging area. The {url} renders directly in an <img>/<a> tag; the {extractionId} +
 *  {filename} pair travels back to the server on submit for promotion to attachments. */
export interface ExtractedImageRow {
  id: number;
  name: string;
  url: string;
  extractionId: string;
  filename: string;
  page: number;
  width: number;
  height: number;
}

export interface ArticleRow {
  id: number;
  title: string;
  content: string;
  resources: ResourceRow[];
  documents: DocumentRow[];
  extractedImages: ExtractedImageRow[];
}

/** Which half of the article is currently visible.
 *  - 'content'     → title, content, extract-from-file & AI-check buttons
 *  - 'attachments' → resources, documents, extracted images */
export type ArticleMode = 'content' | 'attachments';

interface Props {
  article: ArticleRow;
  index: number;
  mode: ArticleMode;
  showRemove: boolean;
  submitting: boolean;
  errors: Record<string, string>;
  /** True when at least one field in this article's HIDDEN half has an error —
   *  the card shows a badge on the mode-switcher hint so a validation error
   *  in a collapsed section is still surfaced. */
  hasHiddenError: boolean;
  onChange: (patch: Partial<ArticleRow>) => void;
  onRemove: () => void;
  onUploadDoc: () => void;
  onAiCheck: () => void;
  onAddResource: () => void;
  onRemoveResource: (rowId: number) => void;
  onUpdateResource: (rowId: number, patch: Partial<ResourceRow>) => void;
  onAddDocument: () => void;
  onRemoveDocument: (rowId: number) => void;
  onUpdateDocument: (rowId: number, patch: Partial<DocumentRow>) => void;
  onRemoveExtractedImage: (rowId: number) => void;
  onUpdateExtractedImage: (rowId: number, patch: Partial<ExtractedImageRow>) => void;
}

export function articleErrorKey(id: number, field: string) {
  return `a${id}.${field}`;
}

export function resourceErrorKey(articleId: number, resourceId: number, field: string) {
  return `a${articleId}.r${resourceId}.${field}`;
}

export function documentErrorKey(articleId: number, documentId: number, field: string) {
  return `a${articleId}.d${documentId}.${field}`;
}

/**
 * One editable article. Renders only the half of the form matching {mode} so the page can
 * focus on one job at a time (content authoring vs. attaching source material). Every
 * field stays mounted regardless of mode via CSS visibility toggles so uncontrolled state
 * (file inputs, focus) survives a tab switch and hidden-tab validation errors still fire.
 */
export function ArticleCard({
  article, index, mode, showRemove, submitting, errors, hasHiddenError,
  onChange, onRemove, onUploadDoc, onAiCheck,
  onAddResource, onRemoveResource, onUpdateResource,
  onAddDocument, onRemoveDocument, onUpdateDocument,
  onRemoveExtractedImage, onUpdateExtractedImage,
}: Props) {
  const { t } = useT();
  const err = (field: string) => errors[articleErrorKey(article.id, field)];
  const titlePreview = article.title.trim() || t('user.submit.articleUntitled');

  return (
    <div className={`article-card article-card-mode-${mode}`} data-mode={mode}>
      <div className="article-card-head">
        <div className="article-card-head-title">
          <strong>{t('user.submit.articleN', { n: index + 1 })}</strong>
          {mode === 'attachments' && (
            <span className="article-card-head-preview" title={titlePreview}>
              — {titlePreview}
            </span>
          )}
          {hasHiddenError && (
            <span
              className="article-card-hidden-badge"
              title={t('user.submit.hiddenErrorHint', {
                tab: mode === 'content'
                  ? t('user.submit.tabAttachments')
                  : t('user.submit.tabContent'),
              })}
              aria-label={t('user.submit.hiddenErrorHint', {
                tab: mode === 'content'
                  ? t('user.submit.tabAttachments')
                  : t('user.submit.tabContent'),
              })}
            >
              !
            </span>
          )}
        </div>
        <div className="article-card-actions">
          {mode === 'content' && (
            <>
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={onUploadDoc}
                disabled={submitting}
                title={t('user.submit.uploadDoc')}
              >
                {t('user.submit.uploadDoc')}
              </button>
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                onClick={onAiCheck}
                disabled={!article.content.trim() || submitting}
                title={t('user.submit.checkContent')}
              >
                {t('user.submit.checkContent')}
              </button>
            </>
          )}
          {showRemove && (
            <button
              type="button"
              className="btn btn-danger btn-sm"
              onClick={onRemove}
              title={t('user.submit.removeArticle')}
            >
              <IconClose size={14} />
            </button>
          )}
        </div>
      </div>

      {/* CONTENT SECTION — title + body + resources. Resources live here because they
          are authored text/links that belong with the writing flow, not binary uploads.
          Both sections stay mounted (hidden via CSS) so React state — including the
          uncontrolled file input selections in attachments — survives a tab switch. */}
      <div className="article-card-section" data-section="content" hidden={mode !== 'content'}>
        <div className="field">
          <label className="field-label">
            {t('user.submit.articleTitle')} <span className="req">*</span>
          </label>
          <input
            className={`input ${err('title') ? 'has-error' : ''}`}
            value={article.title}
            onChange={(e) => onChange({ title: e.target.value })}
            placeholder={t('user.submit.articleTitlePlaceholder')}
          />
          {err('title') && <span className="field-error">{err('title')}</span>}
        </div>

        <div className="field">
          <label className="field-label">
            {t('user.submit.content')} <span className="req">*</span>
          </label>
          <textarea
            className={`textarea textarea-md ${err('content') ? 'has-error' : ''}`}
            value={article.content}
            onChange={(e) => onChange({ content: e.target.value })}
            placeholder={t('user.submit.contentPlaceholder')}
          />
          {err('content') && <span className="field-error">{err('content')}</span>}
        </div>

        <ResourcesSection
          articleId={article.id}
          rows={article.resources}
          submitting={submitting}
          errors={errors}
          onAdd={onAddResource}
          onRemove={onRemoveResource}
          onUpdate={onUpdateResource}
        />
      </div>

      {/* ATTACHMENTS SECTION — documents, extracted images, and Resources again.
          Resources appear in both tabs on purpose: they belong with authored content,
          but the user also expects to reach them from the "attach files" surface
          (same mental bucket as documents). Both instances bind to the same
          article.resources state, so edits stay in sync automatically. */}
      <div className="article-card-section" data-section="attachments" hidden={mode !== 'attachments'}>
        <DocumentsSection
          articleId={article.id}
          rows={article.documents}
          submitting={submitting}
          errors={errors}
          onAdd={onAddDocument}
          onRemove={onRemoveDocument}
          onUpdate={onUpdateDocument}
        />

        <ResourcesSection
          articleId={article.id}
          rows={article.resources}
          submitting={submitting}
          errors={errors}
          onAdd={onAddResource}
          onRemove={onRemoveResource}
          onUpdate={onUpdateResource}
        />

        <ExtractedImagesSection
          rows={article.extractedImages}
          submitting={submitting}
          onRemove={onRemoveExtractedImage}
          onUpdate={onUpdateExtractedImage}
        />
      </div>
    </div>
  );
}

interface ExtractedImagesProps {
  rows: ExtractedImageRow[];
  submitting: boolean;
  onRemove: (rowId: number) => void;
  onUpdate: (rowId: number, patch: Partial<ExtractedImageRow>) => void;
}

/**
 * "Extracted images" — the images the backend pulled out of the uploaded PDF. Each row
 * shows a thumbnail (clickable, opens the image in a new tab) plus an editable label the
 * user can rename before submit. The section hides itself when there are no images so
 * articles that never uploaded a PDF stay uncluttered.
 */
function ExtractedImagesSection({ rows, submitting, onRemove, onUpdate }: ExtractedImagesProps) {
  const { t } = useT();
  if (rows.length === 0) return null;

  return (
    <div className="field-group">
      <div className="row-between">
        <label className="field-label">
          {t('user.submit.extractedImages')}{' '}
          <span className="muted small">({rows.length})</span>
        </label>
        <span className="muted small">{t('user.submit.extractedImagesHint')}</span>
      </div>
      <div className="extracted-images-grid">
        {rows.map((row) => (
          <div className="extracted-image-card" key={row.id}>
            <a
              className="extracted-image-thumb"
              href={row.url}
              target="_blank"
              rel="noopener noreferrer"
              title={t('user.submit.extractedImageOpen')}
            >
              <img src={row.url} alt={row.name} loading="lazy" />
              <span className="extracted-image-page">
                {t('user.submit.extractedImagePage', { page: row.page })}
              </span>
            </a>
            <div className="extracted-image-body">
              <input
                className="input input-sm"
                value={row.name}
                onChange={(e) => onUpdate(row.id, { name: e.target.value })}
                placeholder={t('user.submit.extractedImageNamePlaceholder')}
                aria-label={t('user.submit.extractedImageName')}
                disabled={submitting}
              />
              <div className="extracted-image-meta">
                <span className="muted small">{row.width}×{row.height}</span>
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() => onRemove(row.id)}
                  disabled={submitting}
                  aria-label={t('user.submit.extractedImageRemove')}
                  title={t('user.submit.extractedImageRemove')}
                >
                  <IconClose size={14} />
                </button>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

interface ResourcesProps {
  articleId: number;
  rows: ResourceRow[];
  submitting: boolean;
  errors: Record<string, string>;
  onAdd: () => void;
  onRemove: (rowId: number) => void;
  onUpdate: (rowId: number, patch: Partial<ResourceRow>) => void;
}

function ResourcesSection({ articleId, rows, submitting, errors, onAdd, onRemove, onUpdate }: ResourcesProps) {
  const { t } = useT();
  return (
    <div className="field-group">
      <div className="row-between">
        <label className="field-label">
          {t('user.submit.resources')}{' '}
          <span className="muted small">({t('common.optional')})</span>
        </label>
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          onClick={onAdd}
          disabled={submitting}
          title={t('user.submit.addResource')}
          aria-label={t('user.submit.addResource')}
        >
          <IconPlus size={14} /> {t('user.submit.addResource')}
        </button>
      </div>
      {rows.map((row, idx) => {
        const linkErr = errors[resourceErrorKey(articleId, row.id, 'link')];
        return (
          <div className="form-row" key={row.id}>
            <div className="field field-grow-sm" style={{ marginBottom: 0 }}>
              <input
                className="input"
                value={row.name}
                onChange={(e) => onUpdate(row.id, { name: e.target.value })}
                placeholder={t('user.submit.websiteNamePlaceholder')}
                aria-label={t('user.submit.resourceN', { n: idx + 1 }) + ' — ' + t('user.submit.websiteName')}
              />
            </div>
            <div className="field field-grow-lg" style={{ marginBottom: 0 }}>
              <input
                className={`input ${linkErr ? 'has-error' : ''}`}
                value={row.link}
                onChange={(e) => onUpdate(row.id, { link: e.target.value })}
                placeholder={t('user.submit.websiteLinkPlaceholder')}
                dir="ltr"
                aria-label={t('user.submit.resourceN', { n: idx + 1 }) + ' — ' + t('user.submit.websiteLink')}
              />
              {linkErr && <span className="field-error">{linkErr}</span>}
            </div>
            <div className="field" style={{ marginBottom: 0, flex: '0 0 auto' }}>
              <button
                type="button"
                className="btn btn-danger btn-sm"
                onClick={() => onRemove(row.id)}
                disabled={submitting}
                title={t('user.submit.removeResource')}
                aria-label={t('user.submit.removeResource')}
              >
                <IconClose size={14} />
              </button>
            </div>
          </div>
        );
      })}
    </div>
  );
}

interface DocumentsProps {
  articleId: number;
  rows: DocumentRow[];
  submitting: boolean;
  errors: Record<string, string>;
  onAdd: () => void;
  onRemove: (rowId: number) => void;
  onUpdate: (rowId: number, patch: Partial<DocumentRow>) => void;
}

function DocumentsSection({ articleId, rows, submitting, errors, onAdd, onRemove, onUpdate }: DocumentsProps) {
  const { t } = useT();
  return (
    <div className="field-group">
      <div className="row-between">
        <label className="field-label">
          {t('user.submit.documents')}{' '}
          <span className="muted small">({t('common.optional')})</span>
        </label>
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          onClick={onAdd}
          disabled={submitting}
          title={t('user.submit.addDocument')}
          aria-label={t('user.submit.addDocument')}
        >
          <IconPlus size={14} /> {t('user.submit.addDocument')}
        </button>
      </div>
      {rows.map((row, idx) => {
        const fileErr = errors[documentErrorKey(articleId, row.id, 'file')];
        return (
          <div className="form-row" key={row.id}>
            <div className="field field-grow-sm" style={{ marginBottom: 0 }}>
              <input
                className="input"
                value={row.name}
                onChange={(e) => onUpdate(row.id, { name: e.target.value })}
                placeholder={t('user.submit.documentNamePlaceholder')}
                aria-label={t('user.submit.documentN', { n: idx + 1 }) + ' — ' + t('user.submit.documentName')}
              />
            </div>
            <div className="field field-grow-lg" style={{ marginBottom: 0 }}>
              <input
                type="file"
                className={`input ${fileErr ? 'has-error' : ''}`}
                onChange={(e) => onUpdate(row.id, { file: e.target.files?.[0] ?? null })}
                aria-label={t('user.submit.documentN', { n: idx + 1 }) + ' — ' + t('user.submit.documentFile')}
              />
              {fileErr && <span className="field-error">{fileErr}</span>}
            </div>
            <div className="field" style={{ marginBottom: 0, flex: '0 0 auto' }}>
              <button
                type="button"
                className="btn btn-danger btn-sm"
                onClick={() => onRemove(row.id)}
                disabled={submitting}
                title={t('user.submit.removeDocument')}
                aria-label={t('user.submit.removeDocument')}
              >
                <IconClose size={14} />
              </button>
            </div>
          </div>
        );
      })}
    </div>
  );
}
