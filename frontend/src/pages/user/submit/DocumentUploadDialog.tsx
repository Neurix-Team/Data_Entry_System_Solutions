import { Modal } from '../../../components/Modal';
import type { ExtractedPdf } from '../../../api/types';
import { useT } from '../../../i18n';

const ACCEPTED_TYPES =
  '.pdf,.doc,.docx,.docm,.xls,.xlsx,.xlsm,.xlsb,.csv,' +
  '.ppt,.pptx,.pptm,.odt,.ods,.odp,.rtf,.epub,.txt,.md,' +
  '.html,.htm,.xml,.json,.jpg,.jpeg,.png,.tif,.tiff,.bmp,.gif,.webp,' +
  'application/pdf,image/*';

interface Props {
  open: boolean;
  loading: boolean;
  result: ExtractedPdf | null;
  error: string | null;
  articleIndex: number | null;
  onClose: () => void;
  onFileChosen: (file: File) => void;
  onInsert: () => void;
}

/** Upload a PDF / Word / Excel / image and extract its text for insertion into an article. */
export function DocumentUploadDialog({
  open, loading, result, error, articleIndex,
  onClose, onFileChosen, onInsert,
}: Props) {
  const { t } = useT();

  const insertLabel = articleIndex != null
    ? t('user.submit.insertIntoArticle', { n: articleIndex + 1 })
    : t('user.submit.pdfUseAsContent');

  return (
    <Modal
      open={open}
      title={t('user.submit.docTitle')}
      onClose={onClose}
      footer={
        <>
          <button className="btn btn-secondary" onClick={onClose}>{t('common.close')}</button>
          <button className="btn btn-primary" onClick={onInsert} disabled={!result || loading}>
            {insertLabel}
          </button>
        </>
      }
    >
      <div className="alert alert-info mb-half">{t('user.submit.docHint')}</div>
      <div className="field">
        <label className="field-label">{t('user.submit.docFile')}</label>
        <input
          type="file"
          accept={ACCEPTED_TYPES}
          className="input"
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) onFileChosen(file);
          }}
        />
      </div>
      {loading && <div className="alert alert-info">{t('user.submit.pdfExtracting')}</div>}
      {error && <div className="alert alert-error">{error}</div>}
      {result && !loading && (
        <>
          <div className="small muted mb-tight">
            {t('user.submit.pdfExtractedFrom', { name: result.filename })} · {result.characters.toLocaleString()} {t('user.submit.pdfChars')}
            {result.truncated ? ` · ${t('user.submit.pdfTruncated')}` : ''}
            {result.images.length > 0 && (
              <> · {t('user.submit.pdfImagesFound', { count: result.images.length })}</>
            )}
          </div>
          <textarea className="textarea textarea-lg" readOnly value={result.text} />
          {result.images.length > 0 && (
            <div className="field-group mt-tight">
              <div className="field-label">{t('user.submit.pdfImagesPreview')}</div>
              <div className="extracted-images-preview">
                {result.images.map((img) => (
                  <a
                    key={img.filename}
                    className="extracted-images-preview-item"
                    href={img.url}
                    target="_blank"
                    rel="noopener noreferrer"
                    title={t('user.submit.extractedImageOpen')}
                  >
                    <img src={img.url} alt={img.filename} loading="lazy" />
                    {img.page > 0 && (
                      <span>{t('user.submit.extractedImagePage', { page: img.page })}</span>
                    )}
                  </a>
                ))}
              </div>
            </div>
          )}
          {result.warnings.length > 0 && (
            <div className="alert alert-info mt-tight">
              <ul className="inline-list-flush">
                {result.warnings.map((w, i) => <li key={i}>{w}</li>)}
              </ul>
            </div>
          )}
        </>
      )}
    </Modal>
  );
}
