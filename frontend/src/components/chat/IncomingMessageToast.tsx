import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import type { ChatMessageItem, ChatWsOut } from '../../api/chatMessaging';
import { useAuth } from '../../context/AuthContext';
import { useChatSocketContext } from '../../context/ChatSocketContext';
import { useT } from '../../i18n';
import { playMessageChime } from '../../utils/chime';
import { Avatar } from '../Avatar';
import { IconChat, IconClose } from '../Icons';

const VISIBLE_MS = 6000;
const MAX_STACK = 3;

interface Toast {
  id: number;
  message: ChatMessageItem;
}

let seq = 1;

function previewOf(m: ChatMessageItem): string {
  if (m.body && m.body.trim()) return m.body.trim();
  const first = m.attachments[0];
  if (!first) return '';
  return first.kind === 'IMAGE' ? '📷 ' + first.filename : '📎 ' + first.filename;
}

/**
 * A message from someone else, delivered while you're anywhere in the app — the in-app
 * equivalent of the browser push that reaches you when you're not. Mounted once, high up
 * in the tree, so it fires no matter which page is open.
 *
 * <p>Suppressed for exactly one case: the conversation is already open and on screen (the
 * {@code c=} query param on /chat matches), because a bubble landing right in front of you
 * needs no announcement on top of it.</p>
 */
export function IncomingMessageToast() {
  const { user } = useAuth();
  const { subscribe } = useChatSocketContext();
  const { lang, t } = useT();
  const navigate = useNavigate();
  const location = useLocation();
  const [toasts, setToasts] = useState<Toast[]>([]);
  const locationRef = useRef(location);
  locationRef.current = location;

  const dismiss = useCallback((id: number) => {
    setToasts((prev) => prev.filter((x) => x.id !== id));
  }, []);

  useEffect(() => {
    if (!user) return;
    return subscribe((frame: ChatWsOut) => {
      if (frame.type !== 'MESSAGE' || !frame.message) return;
      const m = frame.message;
      if (m.senderId === user.id) return; // never announce my own message back to me

      const here = locationRef.current;
      const openConversationId = here.pathname === '/chat'
        ? Number(new URLSearchParams(here.search).get('c'))
        : null;
      if (openConversationId === m.conversationId) return; // already looking right at it

      playMessageChime();
      const id = seq++;
      setToasts((prev) => [...prev.slice(-(MAX_STACK - 1)), { id, message: m }]);
      window.setTimeout(() => dismiss(id), VISIBLE_MS);
    });
  }, [user, subscribe, dismiss]);

  if (toasts.length === 0) return null;

  return (
    <div className={`chat-toast-stack${lang === 'ar' ? ' rtl' : ''}`} role="region"
         aria-label={t('chat.newMessage')}>
      {toasts.map(({ id, message }) => (
        <button
          key={id}
          type="button"
          className="chat-toast"
          onClick={() => { navigate(`/chat?c=${message.conversationId}`); dismiss(id); }}
        >
          <span className="chat-toast-icon" aria-hidden="true"><IconChat size={14} /></span>
          <Avatar name={message.senderName} size="sm" />
          <span className="chat-toast-body">
            <span className="chat-toast-name">{message.senderName}</span>
            <span className="chat-toast-preview">{previewOf(message)}</span>
          </span>
          <span
            className="chat-toast-close"
            role="button"
            tabIndex={0}
            aria-label={t('common.close')}
            onClick={(e) => { e.stopPropagation(); dismiss(id); }}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.stopPropagation(); dismiss(id); } }}
          >
            <IconClose size={12} />
          </span>
        </button>
      ))}
    </div>
  );
}
