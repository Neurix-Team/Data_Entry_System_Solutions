import { FormEvent, useEffect, useState } from 'react';
import { extractError } from '../../api/client';
import { chatApi, chatGroupApi, type ChatContact, type ChatGroupItem } from '../../api/chatMessaging';
import { useT } from '../../i18n';
import { IconClose } from '../Icons';
import { ContactPicker } from './ContactPicker';

interface Props {
  open: boolean;
  onClose: () => void;
  onCreated: (group: ChatGroupItem) => void;
}

/** Name + pick people. The creator becomes the group's admin on the server. */
export function CreateGroupModal({ open, onClose, onCreated }: Props) {
  const { t } = useT();
  const [name, setName] = useState('');
  const [contacts, setContacts] = useState<ChatContact[] | null>(null);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setName('');
    setSelected(new Set());
    setError(null);
    setContacts(null);
    chatApi.contacts()
      .then((r) => setContacts(r.items))
      .catch((e) => setError(extractError(e)));
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  function toggle(id: number) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy || !name.trim() || selected.size === 0) return;
    setBusy(true);
    setError(null);
    try {
      const group = await chatGroupApi.create(name.trim(), Array.from(selected));
      onCreated(group);
    } catch (err) {
      setError(extractError(err, t('common.somethingWrong')));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="chat-contacts-backdrop" onClick={onClose}>
      <form
        className="chat-contacts chat-modal"
        role="dialog"
        aria-modal="true"
        aria-label={t('chat.group.createTitle')}
        onClick={(e) => e.stopPropagation()}
        onSubmit={submit}
      >
        <div className="chat-contacts-head">
          <h3>{t('chat.group.createTitle')}</h3>
          <button type="button" className="icon-btn" onClick={onClose} aria-label={t('common.close')}>
            <IconClose size={18} />
          </button>
        </div>

        <label className="chat-modal-field">
          <span>{t('chat.group.nameLabel')}</span>
          <input
            className="input"
            value={name}
            maxLength={150}
            autoFocus
            placeholder={t('chat.group.namePlaceholder')}
            onChange={(e) => setName(e.target.value)}
          />
        </label>

        <div className="chat-modal-label">
          {t('chat.group.pickMembers')}
          {selected.size > 0 && (
            <span className="chat-modal-count">{t('chat.group.selectedCount', { n: selected.size })}</span>
          )}
        </div>
        <ContactPicker contacts={contacts} selected={selected} onToggle={toggle} />

        {error && <div className="chat-error" role="alert">{error}</div>}

        <div className="chat-modal-actions">
          <button type="button" className="btn" onClick={onClose}>{t('common.cancel')}</button>
          <button
            type="submit"
            className="btn btn-primary"
            disabled={busy || !name.trim() || selected.size === 0}
          >
            {busy ? t('common.loading') : t('chat.group.create')}
          </button>
        </div>
      </form>
    </div>
  );
}
