import { useMemo, useState } from 'react';
import { avatarUrl } from '../../api/profile';
import type { ChatContact } from '../../api/chatMessaging';
import { useT } from '../../i18n';
import { Avatar } from '../Avatar';
import { IconCheck, IconSearch } from '../Icons';

interface Props {
  contacts: ChatContact[] | null;
  selected: Set<number>;
  onToggle: (id: number) => void;
  /** People to leave out — e.g. those already in the group. */
  exclude?: Set<number>;
}

/**
 * A searchable, multi-select list of people. Shared by "create group" and "add members" so
 * both behave identically — same search, same look, same touch targets on a phone.
 */
export function ContactPicker({ contacts, selected, onToggle, exclude }: Props) {
  const { lang, t } = useT();
  const isAr = lang === 'ar';
  const [query, setQuery] = useState('');

  const nameOf = (c: ChatContact) => isAr
    ? (c.displayNameAr || c.displayName || c.username)
    : (c.displayNameEn || c.displayName || c.username);

  const roleLabel = (role: string) => role === 'SUPER_ADMIN'
    ? (isAr ? 'سوبر أدمن' : 'Super Admin')
    : role === 'ADMIN' ? (isAr ? 'قائد فريق' : 'Team Leader')
    : (isAr ? 'موظف إدخال' : 'Agent');

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (contacts ?? []).filter((c) => {
      if (exclude?.has(c.id)) return false;
      if (!q) return true;
      return [c.displayName, c.displayNameEn, c.displayNameAr, c.username, c.team]
        .some((s) => (s || '').toLowerCase().includes(q));
    });
  }, [contacts, query, exclude]);

  return (
    <div className="contact-picker">
      <div className="chat-contacts-search">
        <IconSearch size={16} />
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={t('chat.searchPeople')}
          aria-label={t('chat.searchPeople')}
        />
      </div>
      <ul className="contact-picker-list">
        {!contacts ? (
          <li className="muted contact-picker-empty">{t('common.loading')}</li>
        ) : visible.length === 0 ? (
          <li className="muted contact-picker-empty">{t('chat.noPeople')}</li>
        ) : visible.map((c) => {
          const on = selected.has(c.id);
          return (
            <li key={c.id}>
              <button
                type="button"
                className={`contact-picker-row${on ? ' is-selected' : ''}`}
                onClick={() => onToggle(c.id)}
                aria-pressed={on}
              >
                <Avatar name={nameOf(c)} size="sm" src={avatarUrl(c.id, c.avatarUpdatedAt)} />
                <span className="chat-contact-meta">
                  <strong>{nameOf(c)}</strong>
                  <small className="muted">{roleLabel(c.role)}{c.team ? ` · ${c.team}` : ''}</small>
                </span>
                <span className="contact-picker-check" aria-hidden="true">
                  {on && <IconCheck size={14} />}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
