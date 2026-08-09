import { Modal } from '../../../components/Modal';
import { useT } from '../../../i18n';

export interface AiResult {
  original: string;
  corrected: string;
  notes: string[];
}

interface Props {
  open: boolean;
  loading: boolean;
  result: AiResult | null;
  error: string | null;
  onClose: () => void;
  onApply: () => void;
}

/** Modal that displays AI-suggested corrections and lets the user apply them. */
export function AiCheckDialog({ open, loading, result, error, onClose, onApply }: Props) {
  const { t } = useT();
  const canApply = !loading && result != null && result.original !== result.corrected;

  return (
    <Modal
      open={open}
      title={t('user.submit.checkTitle')}
      onClose={onClose}
      footer={
        <>
          <button className="btn btn-secondary" onClick={onClose}>{t('common.close')}</button>
          <button className="btn btn-primary" onClick={onApply} disabled={!canApply}>
            {t('user.submit.applySuggestion')}
          </button>
        </>
      }
    >
      {loading && <div className="alert alert-info">{t('user.submit.checking')}</div>}
      {error && <div className="alert alert-error">{error}</div>}
      {result && !loading && (
        <>
          {result.notes.length > 0 && (
            <div className="alert alert-info">
              <strong>{t('user.submit.notes')}</strong>
              <ul className="inline-list">
                {result.notes.map((n, i) => <li key={i}>{n}</li>)}
              </ul>
            </div>
          )}
          <div className="field">
            <label className="field-label">{t('user.submit.original')}</label>
            <div className="pre-block">
              {result.original || <span className="muted">{t('user.submit.emptyText')}</span>}
            </div>
          </div>
          <div className="field">
            <label className="field-label">{t('user.submit.suggested')}</label>
            <div className="pre-block-success">{result.corrected}</div>
          </div>
        </>
      )}
    </Modal>
  );
}
