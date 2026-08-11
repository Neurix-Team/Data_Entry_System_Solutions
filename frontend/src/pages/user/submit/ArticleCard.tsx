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

export interface ArticleRow {
  id: number;
  title: string;
  content: string;
  resources: ResourceRow[];
  documents: DocumentRow[];
}

interface Props {
  article: ArticleRow;
  index: number;
  showRemove: boolean;
  submitting: boolean;
  errors: Record<string, string>;
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

/** One editable article row (title + content + resources list + documents list + actions). */
export function ArticleCard({
  article, index, showRemove, submitting, errors,
  onChange, onRemove, onUploadDoc, onAiCheck,
  onAddResource, onRemoveResource, onUpdateResource,
  onAddDocument, onRemoveDocument, onUpdateDocument,
}: Props) {
  const { t } = useT();
  const err = (field: string) => errors[articleErrorKey(article.id, field)];

  return (
    <div className="article-card">
      <div className="article-card-head">
        <strong>{t('user.submit.articleN', { n: index + 1 })}</strong>
        <div className="article-card-actions">
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

      <DocumentsSection
        articleId={article.id}
        rows={article.documents}
        submitting={submitting}
        errors={errors}
        onAdd={onAddDocument}
        onRemove={onRemoveDocument}
        onUpdate={onUpdateDocument}
      />
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
