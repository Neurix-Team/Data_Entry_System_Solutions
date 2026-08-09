import { IconClose } from '../../../components/Icons';
import { useT } from '../../../i18n';

export interface ArticleRow {
  id: number;
  title: string;
  content: string;
  websiteName: string;
  websiteLink: string;
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
}

export function articleErrorKey(id: number, field: keyof ArticleRow) {
  return `a${id}.${field}`;
}

/** One editable article row (title + content + optional website + per-article actions). */
export function ArticleCard({
  article, index, showRemove, submitting, errors,
  onChange, onRemove, onUploadDoc, onAiCheck,
}: Props) {
  const { t } = useT();
  const err = (field: keyof ArticleRow) => errors[articleErrorKey(article.id, field)];

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

      <div className="form-row">
        <div className="field field-grow-sm">
          <label className="field-label">
            {t('user.submit.websiteName')}{' '}
            <span className="muted small">({t('common.optional')})</span>
          </label>
          <input
            className="input"
            value={article.websiteName}
            onChange={(e) => onChange({ websiteName: e.target.value })}
            placeholder={t('user.submit.websiteNamePlaceholder')}
          />
        </div>
        <div className="field field-grow-lg">
          <label className="field-label">
            {t('user.submit.websiteLink')}{' '}
            <span className="muted small">({t('common.optional')})</span>
          </label>
          <input
            className={`input ${err('websiteLink') ? 'has-error' : ''}`}
            value={article.websiteLink}
            onChange={(e) => onChange({ websiteLink: e.target.value })}
            placeholder={t('user.submit.websiteLinkPlaceholder')}
            dir="ltr"
          />
          {err('websiteLink') && <span className="field-error">{err('websiteLink')}</span>}
        </div>
      </div>
    </div>
  );
}
