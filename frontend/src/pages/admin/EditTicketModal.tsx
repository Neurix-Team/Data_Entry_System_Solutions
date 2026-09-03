import { useEffect, useRef, useState } from 'react';
import { extractError } from '../../api/client';
import {
  ChunkedUploadUnsupportedError,
  DEFAULT_CHUNK_PARALLELISM,
  uploadFileChunked,
  type UploadProgress,
} from '../../api/chunkedUpload';
import { ticketsApi } from '../../api/resources';
import type { Ticket, TicketDocument } from '../../api/types';
import { IconClose, IconPlus } from '../../components/Icons';
import { Modal } from '../../components/Modal';
import { UploadHud } from '../../components/UploadHud';
import { useToast } from '../../components/toast/ToastContext';
import { useT } from '../../i18n';
import { titleFromFilename } from '../../utils/titleFromFile';
import { formatBytes } from '../../utils/uploadFormat';

interface Props {
  /** The entry being edited; null keeps the modal closed. */
  ticket: Ticket | null;
  onClose: () => void;
  /** Receives the server's updated entry (with any newly attached documents merged in). */
  onSaved: (updated: Ticket) => void;
}

interface ResourceRowState { id: number; name: string; url: string; }
interface NewFile { id: number; file: File; name: string; }

/**
 * "Update" for the Data Entry Tasks page. Edits the authored fields of an entry and can
 * attach more files in the same go — the text is saved first, then each new file goes up
 * through the chunked uploader with the progress ring, so a fixed title and a missing
 * scan can be sorted out in one visit.
 */
export function EditTicketModal({ ticket, onClose, onSaved }: Props) {
  const { t, lang } = useT();
  const toast = useToast();
  const ar = lang === 'ar';
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [websiteName, setWebsiteName] = useState('');
  const [websiteLink, setWebsiteLink] = useState('');
  const [resources, setResources] = useState<ResourceRowState[]>([]);
  const [newFiles, setNewFiles] = useState<NewFile[]>([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hud, setHud] = useState<{
    name: string; index: number; count: number; progress: UploadProgress;
  } | null>(null);
  const nextId = useRef(1);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (!ticket) return;
    setTitle(ticket.title ?? '');
    setContent(ticket.content ?? '');
    setWebsiteName(ticket.websiteName ?? '');
    setWebsiteLink(ticket.websiteLink ?? '');
    setResources((ticket.resources ?? []).map((r) => ({
      id: nextId.current++, name: r.name ?? '', url: r.url,
    })));
    setNewFiles([]);
    setError(null);
    setHud(null);
    setSaving(false);
  }, [ticket]);

  function addFiles(list: FileList | null) {
    if (!list || list.length === 0) return;
    const fresh: NewFile[] = Array.from(list).map((file) => ({
      id: nextId.current++, file, name: titleFromFilename(file.name),
    }));
    setNewFiles((prev) => [...prev, ...fresh]);
  }

  async function onSave() {
    if (!ticket) return;
    setError(null);
    setSaving(true);
    try {
      const updated = await ticketsApi.updateAdmin(ticket.id, {
        title: title.trim(),
        content: content.trim(),
        websiteName: websiteName.trim() || undefined,
        websiteLink: websiteLink.trim() || undefined,
        resources: resources
          .map((r) => ({ name: r.name.trim() || undefined, url: r.url.trim() }))
          .filter((r) => r.url.length > 0),
      });

      // Text is saved; now the files. Each one leaves the pending list as soon as it lands,
      // so a failure part-way through can be retried without re-uploading (and tripping the
      // duplicate check on) the ones that already made it.
      const uploadedDocs: TicketDocument[] = [];
      const pending = [...newFiles];
      for (let i = 0; i < pending.length; i++) {
        const nf = pending[i];
        const name = nf.name.trim() || nf.file.name;
        const show = (progress: UploadProgress) =>
          setHud({ name, index: i + 1, count: pending.length, progress });
        show({ phase: 'starting', loaded: 0, total: nf.file.size, fraction: 0, bytesPerSecond: 0, etaSeconds: null });
        let doc: TicketDocument | null;
        try {
          const res = await uploadFileChunked({
            file: nf.file,
            target: { kind: 'TICKET_DOCUMENT', ticketId: ticket.id, name },
            parallel: DEFAULT_CHUNK_PARALLELISM,
            onProgress: show,
          });
          doc = res.document;
        } catch (err) {
          if (!(err instanceof ChunkedUploadUnsupportedError)) throw err;
          doc = await ticketsApi.uploadDocument(ticket.id, name, nf.file);
        }
        if (doc) uploadedDocs.push(doc);
        setNewFiles((prev) => prev.filter((f) => f.id !== nf.id));
      }

      toast.success(t('ticket.updated'));
      onSaved({ ...updated, documents: [...(updated.documents ?? []), ...uploadedDocs] });
    } catch (err) {
      setError(extractError(err, ar ? 'تعذّر الحفظ' : 'Could not save'));
    } finally {
      setSaving(false);
      setHud(null);
    }
  }

  return (
    <Modal
      open={!!ticket}
      title={ticket ? t('ticket.editTitle', { id: ticket.id }) : ''}
      onClose={saving ? () => undefined : onClose}
      footer={
        <div className="row gap-2" style={{ justifyContent: 'flex-end', width: '100%' }}>
          <button type="button" className="btn btn-secondary" onClick={onClose} disabled={saving}>
            {t('common.cancel')}
          </button>
          <button
            type="button"
            className={`btn btn-primary${saving && hud ? ' is-uploading' : ''}`}
            onClick={onSave}
            disabled={saving}
          >
            {saving ? t('ticket.saving') : t('ticket.saveChanges')}
          </button>
        </div>
      }
    >
      {ticket && (
        <div>
          {error && <div className="alert alert-error" style={{ marginBottom: '0.75rem' }}>{error}</div>}

          <div className="field">
            <label className="field-label" htmlFor="edit-ticket-title">{t('ticket.titleLabel')}</label>
            <input
              id="edit-ticket-title"
              className="input"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              disabled={saving}
              maxLength={500}
            />
          </div>

          <div className="field">
            <label className="field-label" htmlFor="edit-ticket-content">{t('ticket.content')}</label>
            <textarea
              id="edit-ticket-content"
              className="textarea textarea-md"
              value={content}
              onChange={(e) => setContent(e.target.value)}
              disabled={saving}
            />
          </div>

          <div className="form-row">
            <div className="field field-grow-sm" style={{ marginBottom: 0 }}>
              <label className="field-label" htmlFor="edit-ticket-website">{t('ticket.website')}</label>
              <input
                id="edit-ticket-website"
                className="input"
                value={websiteName}
                onChange={(e) => setWebsiteName(e.target.value)}
                disabled={saving}
                maxLength={250}
              />
            </div>
            <div className="field field-grow-lg" style={{ marginBottom: 0 }}>
              <label className="field-label" htmlFor="edit-ticket-link">{t('ticket.link')}</label>
              <input
                id="edit-ticket-link"
                className="input"
                value={websiteLink}
                onChange={(e) => setWebsiteLink(e.target.value)}
                disabled={saving}
                dir="ltr"
                placeholder="https://"
                maxLength={500}
              />
            </div>
          </div>

          <div className="field-group" style={{ marginTop: '1rem' }}>
            <div className="row-between">
              <label className="field-label">{t('ticket.resources')}</label>
              <button
                type="button"
                className="btn btn-ghost btn-sm"
                onClick={() => setResources((prev) => [...prev, { id: nextId.current++, name: '', url: '' }])}
                disabled={saving}
              >
                <IconPlus size={14} /> {t('user.submit.addResource')}
              </button>
            </div>
            {resources.map((r, idx) => (
              <div className="form-row" key={r.id}>
                <div className="field field-grow-sm" style={{ marginBottom: 0 }}>
                  <input
                    className="input"
                    value={r.name}
                    onChange={(e) => setResources((prev) => prev.map((x) => x.id === r.id ? { ...x, name: e.target.value } : x))}
                    placeholder={t('user.submit.resourceN', { n: idx + 1 })}
                    disabled={saving}
                    maxLength={250}
                  />
                </div>
                <div className="field field-grow-lg" style={{ marginBottom: 0 }}>
                  <input
                    className="input"
                    value={r.url}
                    onChange={(e) => setResources((prev) => prev.map((x) => x.id === r.id ? { ...x, url: e.target.value } : x))}
                    placeholder="https://"
                    dir="ltr"
                    disabled={saving}
                    maxLength={500}
                  />
                </div>
                <div className="field" style={{ marginBottom: 0, flex: '0 0 auto' }}>
                  <button
                    type="button"
                    className="btn btn-danger btn-sm"
                    onClick={() => setResources((prev) => prev.filter((x) => x.id !== r.id))}
                    disabled={saving}
                    title={t('user.submit.removeResource')}
                    aria-label={t('user.submit.removeResource')}
                  >
                    <IconClose size={14} />
                  </button>
                </div>
              </div>
            ))}
          </div>

          <div className="field-group" style={{ marginTop: '1rem' }}>
            <div className="row-between">
              <label className="field-label">
                {t('ticket.documents')}{' '}
                <span className="muted small">— {t('ticket.addFilesHint')}</span>
              </label>
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={() => fileInputRef.current?.click()}
                disabled={saving}
              >
                <IconPlus size={14} /> {t('ticket.addFiles')}
              </button>
              <input
                ref={fileInputRef}
                type="file"
                multiple
                style={{ display: 'none' }}
                onChange={(e) => { addFiles(e.target.files); e.target.value = ''; }}
              />
            </div>

            {ticket.documents && ticket.documents.length > 0 && (
              <ul className="inline-list-flush muted small" style={{ marginBottom: '0.5rem' }}>
                {ticket.documents.map((d) => (
                  <li key={d.id}>
                    {d.name || d.originalFilename} · {formatBytes(d.sizeBytes)}
                  </li>
                ))}
              </ul>
            )}

            {newFiles.map((nf) => (
              <div className="form-row" key={nf.id}>
                <div className="field field-grow-sm" style={{ marginBottom: 0 }}>
                  <input
                    className="input"
                    value={nf.name}
                    onChange={(e) => setNewFiles((prev) => prev.map((x) => x.id === nf.id ? { ...x, name: e.target.value } : x))}
                    placeholder={t('ticket.newFileName')}
                    disabled={saving}
                    maxLength={250}
                  />
                </div>
                <div className="field field-grow-lg muted small" style={{ marginBottom: 0, alignSelf: 'center', wordBreak: 'break-all' }}>
                  {nf.file.name} · {formatBytes(nf.file.size)}
                </div>
                <div className="field" style={{ marginBottom: 0, flex: '0 0 auto' }}>
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm"
                    onClick={() => setNewFiles((prev) => prev.filter((x) => x.id !== nf.id))}
                    disabled={saving}
                    aria-label={ar ? 'إزالة' : 'Remove'}
                    title={ar ? 'إزالة' : 'Remove'}
                  >
                    <IconClose size={14} />
                  </button>
                </div>
              </div>
            ))}

            {hud && (
              <UploadHud
                lang={lang}
                size={56}
                title={t('user.submit.uploadingFileOf', { n: hud.index, count: hud.count })}
                subtitle={hud.name}
                progress={hud.progress}
                state={hud.progress.phase === 'finalizing'
                  ? 'finalizing'
                  : hud.progress.phase === 'done' ? 'done' : 'uploading'}
              />
            )}
          </div>
        </div>
      )}
    </Modal>
  );
}
