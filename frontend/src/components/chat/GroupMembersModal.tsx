import { FormEvent, useCallback, useEffect, useState } from 'react';
import { extractError } from '../../api/client';
import { avatarUrl } from '../../api/profile';
import {
  chatApi, chatGroupApi,
  type ChatContact, type ChatGroupDetail, type ChatGroupMember,
} from '../../api/chatMessaging';
import { useAuth } from '../../context/AuthContext';
import { useT } from '../../i18n';
import { Avatar } from '../Avatar';
import { useConfirm } from '../ConfirmDialog';
import { IconClose } from '../Icons';
import { ContactPicker } from './ContactPicker';

interface Props {
  groupId: number | null;
  onClose: () => void;
  /** The group changed (renamed / members changed) — the parent refreshes its list. */
  onChanged: () => void;
  /** The caller is no longer in the group (they left) — the parent closes the chat. */
  onLeft: () => void;
}

/**
 * Everything about who is in a group. An admin can rename, add, remove, promote and
 * demote; anyone can see the list and leave. The server enforces every one of those —
 * this only decides which buttons are worth showing.
 */
export function GroupMembersModal({ groupId, onClose, onChanged, onLeft }: Props) {
  const { user } = useAuth();
  const { lang, t } = useT();
  const confirm = useConfirm();
  const isAr = lang === 'ar';
  const [detail, setDetail] = useState<ChatGroupDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [renaming, setRenaming] = useState('');
  const [adding, setAdding] = useState(false);
  const [contacts, setContacts] = useState<ChatContact[] | null>(null);
  const [picked, setPicked] = useState<Set<number>>(new Set());

  const reload = useCallback(async () => {
    if (groupId == null) return;
    try {
      const d = await chatGroupApi.detail(groupId);
      setDetail(d);
      setRenaming(d.name);
    } catch (e) {
      setError(extractError(e));
    }
  }, [groupId]);

  useEffect(() => {
    if (groupId == null) return;
    setDetail(null);
    setError(null);
    setAdding(false);
    setPicked(new Set());
    reload();
  }, [groupId, reload]);

  useEffect(() => {
    if (groupId == null) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [groupId, onClose]);

  if (groupId == null) return null;

  const nameOf = (m: ChatGroupMember) => isAr
    ? (m.displayNameAr || m.displayName || m.username)
    : (m.displayNameEn || m.displayName || m.username);

  async function run(action: () => Promise<void>) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await action();
      await reload();
      onChanged();
    } catch (e) {
      setError(extractError(e, t('common.somethingWrong')));
    } finally {
      setBusy(false);
    }
  }

  async function onRename(e: FormEvent) {
    e.preventDefault();
    if (!detail || !renaming.trim() || renaming.trim() === detail.name) return;
    await run(() => chatGroupApi.rename(detail.id, renaming.trim()));
  }

  async function startAdding() {
    setAdding(true);
    if (!contacts) {
      try { setContacts((await chatApi.contacts()).items); }
      catch (e) { setError(extractError(e)); }
    }
  }

  async function confirmAdd() {
    if (!detail || picked.size === 0) return;
    await run(async () => {
      await chatGroupApi.addMembers(detail.id, Array.from(picked));
      setPicked(new Set());
      setAdding(false);
    });
  }

  async function onRemove(m: ChatGroupMember) {
    if (!detail) return;
    const ok = await confirm({
      message: t('chat.group.confirmRemove', { name: nameOf(m) }),
      destructive: true,
    });
    if (!ok) return;
    await run(() => chatGroupApi.removeMember(detail.id, m.userId));
  }

  async function onLeave() {
    if (!detail || !user) return;
    const ok = await confirm({ message: t('chat.group.confirmLeave'), destructive: true });
    if (!ok) return;
    setBusy(true);
    try {
      await chatGroupApi.removeMember(detail.id, user.id);
      onChanged();
      onLeft();
    } catch (e) {
      setError(extractError(e, t('common.somethingWrong')));
      setBusy(false);
    }
  }

  const existingIds = new Set((detail?.members ?? []).map((m) => m.userId));

  return (
    <div className="chat-contacts-backdrop" onClick={onClose}>
      <div
        className="chat-contacts chat-modal"
        role="dialog"
        aria-modal="true"
        aria-label={t('chat.group.membersTitle')}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="chat-contacts-head">
          <h3>{t('chat.group.membersTitle')}</h3>
          <button type="button" className="icon-btn" onClick={onClose} aria-label={t('common.close')}>
            <IconClose size={18} />
          </button>
        </div>

        {error && <div className="chat-error" role="alert">{error}</div>}

        {!detail ? (
          <div className="muted chat-modal-loading">{t('common.loading')}</div>
        ) : adding ? (
          <>
            <div className="chat-modal-label">
              {t('chat.group.addPeople')}
              {picked.size > 0 && (
                <span className="chat-modal-count">{t('chat.group.selectedCount', { n: picked.size })}</span>
              )}
            </div>
            <ContactPicker
              contacts={contacts}
              selected={picked}
              exclude={existingIds}
              onToggle={(id) => setPicked((prev) => {
                const next = new Set(prev);
                if (next.has(id)) next.delete(id); else next.add(id);
                return next;
              })}
            />
            <div className="chat-modal-actions">
              <button type="button" className="btn" onClick={() => { setAdding(false); setPicked(new Set()); }}>
                {t('common.cancel')}
              </button>
              <button type="button" className="btn btn-primary" disabled={busy || picked.size === 0}
                      onClick={confirmAdd}>
                {t('chat.group.addSelected')}
              </button>
            </div>
          </>
        ) : (
          <>
            {detail.isAdmin && (
              <form className="group-rename" onSubmit={onRename}>
                <input
                  className="input"
                  value={renaming}
                  maxLength={150}
                  aria-label={t('chat.group.nameLabel')}
                  onChange={(e) => setRenaming(e.target.value)}
                />
                <button type="submit" className="btn"
                        disabled={busy || !renaming.trim() || renaming.trim() === detail.name}>
                  {t('chat.group.rename')}
                </button>
              </form>
            )}

            <div className="chat-modal-label">
              {t('chat.group.memberCount', { n: detail.members.length })}
              {detail.isAdmin && (
                <button type="button" className="btn btn-sm btn-primary group-add-btn" onClick={startAdding}>
                  + {t('chat.group.addPeople')}
                </button>
              )}
            </div>

            <ul className="group-member-list">
              {detail.members.map((m) => {
                const isMe = m.userId === user?.id;
                return (
                  <li key={m.userId} className="group-member">
                    <Avatar name={nameOf(m)} size="sm" src={avatarUrl(m.userId, m.avatarUpdatedAt)} />
                    <span className="group-member-meta">
                      <strong>{nameOf(m)}{isMe ? ` ${t('common.you')}` : ''}</strong>
                      {m.role === 'ADMIN' && <span className="group-admin-badge">{t('chat.group.admin')}</span>}
                    </span>
                    {detail.isAdmin && !isMe && (
                      <span className="group-member-actions">
                        <button
                          type="button"
                          className="btn btn-sm btn-ghost"
                          disabled={busy}
                          onClick={() => run(() => chatGroupApi.setAdmin(detail.id, m.userId, m.role !== 'ADMIN'))}
                        >
                          {m.role === 'ADMIN' ? t('chat.group.demote') : t('chat.group.promote')}
                        </button>
                        <button
                          type="button"
                          className="btn btn-sm btn-ghost group-remove-btn"
                          disabled={busy}
                          onClick={() => onRemove(m)}
                        >
                          {t('chat.group.remove')}
                        </button>
                      </span>
                    )}
                  </li>
                );
              })}
            </ul>

            <div className="chat-modal-actions">
              <button type="button" className="btn group-leave-btn" disabled={busy} onClick={onLeave}>
                {t('chat.group.leave')}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
