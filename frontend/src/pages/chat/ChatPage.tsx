import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { extractError } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { useT } from '../../i18n';
import { Avatar } from '../../components/Avatar';
import { avatarUrl } from '../../api/profile';
import { IconChat, IconClose, IconPaperclip, IconSearch, IconSend } from '../../components/Icons';
import {
  attachmentUrl, chatApi,
  type ChatContact,
  type ChatConversationItem,
  type ChatMessageItem,
  type ChatWsOut,
} from '../../api/chatMessaging';
import { useChatSocket } from '../../hooks/useChatSocket';
import './chat.css';

interface PendingBubble {
  clientMsgId: string;
  body: string;
  createdAt: string;
}

let seq = 0;
function newClientMsgId(): string {
  seq += 1;
  return `tmp-${Date.now()}-${seq}-${Math.random().toString(36).slice(2, 8)}`;
}

function fileSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${bytes} B`;
}

export default function ChatPage() {
  const { user } = useAuth();
  const { lang, t } = useT();
  const isAr = lang === 'ar';
  const [searchParams, setSearchParams] = useSearchParams();

  const [conversations, setConversations] = useState<ChatConversationItem[]>([]);
  const [activeId, setActiveId] = useState<number | null>(null);
  const [messages, setMessages] = useState<ChatMessageItem[]>([]);
  const [pending, setPending] = useState<PendingBubble[]>([]);
  const [contacts, setContacts] = useState<ChatContact[] | null>(null);
  const [contactsOpen, setContactsOpen] = useState(false);
  const [contactQuery, setContactQuery] = useState('');
  const [input, setInput] = useState('');
  const [pendingFiles, setPendingFiles] = useState<File[]>([]);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [typingName, setTypingName] = useState<string | null>(null);
  const [booting, setBooting] = useState(true);

  const activeIdRef = useRef<number | null>(null);
  const sendRef = useRef<(p: Record<string, unknown>) => boolean>(() => false);
  const typingSentAtRef = useRef(0);
  const typingHideRef = useRef<number | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  activeIdRef.current = activeId;
  const myId = user?.id ?? 0;
// __LOGIC__

  const conversationsRef = useRef<ChatConversationItem[]>([]);
  conversationsRef.current = conversations;

  const upsertMessage = useCallback((m: ChatMessageItem, clientMsgId?: string | null) => {
    if (clientMsgId) setPending((prev) => prev.filter((p) => p.clientMsgId !== clientMsgId));
    setMessages((prev) => {
      const idx = prev.findIndex((x) => x.id === m.id);
      if (idx === -1) return [...prev, m];
      const next = [...prev];
      next[idx] = m;
      return next;
    });
    const mine = m.senderId === myId;
    setConversations((prev) => {
      const idx = prev.findIndex((c) => c.id === m.conversationId);
      if (idx === -1) return prev;
      const cur = prev[idx];
      const preview = m.body || (m.attachments[0]
        ? (m.attachments[0].kind === 'IMAGE' ? '📷 ' : '📎 ') + m.attachments[0].filename
        : '');
      const bumped: ChatConversationItem = {
        ...cur,
        lastMessagePreview: preview,
        lastMessageAt: m.createdAt,
        unreadCount: mine
          ? cur.unreadCount
          : (activeIdRef.current === m.conversationId ? 0 : cur.unreadCount + 1),
      };
      const next = [...prev];
      next.splice(idx, 1);
      return [bumped, ...next];
    });
  }, [myId]);

  const loadConversations = useCallback(async () => {
    try {
      const data = await chatApi.conversations();
      setConversations(data.items);
    } catch (e) {
      setError(extractError(e, 'Chat failed to load'));
    }
  }, []);

  const markRead = useCallback((conversationId: number) => {
    const ok = sendRef.current({ type: 'READ', conversationId });
    if (!ok) chatApi.markRead(conversationId).catch(() => undefined);
    setConversations((prev) => prev.map((c) => (
      c.id === conversationId ? { ...c, unreadCount: 0 } : c)));
  }, []);

  const openConversation = useCallback(async (id: number) => {
    setActiveId(id);
    activeIdRef.current = id;
    setMessages([]);
    setPending([]);
    setError(null);
    setSearchParams({ c: String(id) }, { replace: true });
    try {
      const page = await chatApi.messages(id);
      setMessages(page.messages);
      markRead(id);
    } catch (e) {
      setError(extractError(e));
    }
  }, [markRead, setSearchParams]);

  const handleWs = useCallback((m: ChatWsOut) => {
    switch (m.type) {
      case 'MESSAGE': {
        if (!m.message) return;
        if (m.message.senderId !== myId && activeIdRef.current === m.message.conversationId) {
          markRead(m.message.conversationId);
        }
        upsertMessage(m.message, m.clientMsgId ?? undefined);
        break;
      }
      case 'READ': {
        if (m.conversationId === activeIdRef.current && m.readerId != null && m.readerId !== myId) {
          setMessages((prev) => prev.map((x) => (
            x.senderId === myId && !x.readAt ? { ...x, readAt: new Date().toISOString() } : x)));
        }
        break;
      }
      case 'TYPING': {
        if (m.conversationId !== activeIdRef.current || m.fromUserId === myId) return;
        const conv = conversationsRef.current.find((c) => c.id === m.conversationId);
        setTypingName(conv?.otherName ?? null);
        if (typingHideRef.current != null) window.clearTimeout(typingHideRef.current);
        typingHideRef.current = window.setTimeout(() => setTypingName(null), 2600);
        break;
      }
      case 'ERROR': {
        if (m.clientMsgId) {
          setPending((prev) => prev.filter((p) => p.clientMsgId !== m.clientMsgId));
        }
        setError(m.error || t('chat.sendFailed'));
        break;
      }
      default:
        break;
    }
  }, [markRead, myId, t, upsertMessage]);

  const socket = useChatSocket(handleWs);
  sendRef.current = socket.send;
// __BOOT__

  useEffect(() => {
    if (!user) return;
    loadConversations().finally(() => setBooting(false));
    const param = searchParams.get('c');
    if (param) {
      const id = Number(param);
      if (Number.isFinite(id) && id > 0) openConversation(id);
    }
    const onFocus = () => loadConversations();
    window.addEventListener('focus', onFocus);
    const poll = window.setInterval(loadConversations, 20000);
    return () => {
      window.removeEventListener('focus', onFocus);
      window.clearInterval(poll);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user?.id]);

  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages, pending, typingName]);

  async function openContacts() {
    setContactsOpen(true);
    try {
      const list = await chatApi.contacts();
      setContacts(list.items);
    } catch (e) {
      setError(extractError(e));
    }
  }

  async function startWith(contact: ChatContact) {
    setContactsOpen(false);
    try {
      const conv = await chatApi.start(contact.id);
      setConversations((prev) => (
        prev.some((c) => c.id === conv.id) ? prev : [conv, ...prev]));
      await openConversation(conv.id);
    } catch (e) {
      setError(extractError(e));
    }
  }

  function onFilesChosen(files: FileList | null) {
    if (!files || files.length === 0) return;
    const arr = Array.from(files).slice(0, 5);
    setPendingFiles((prev) => [...prev, ...arr].slice(0, 5));
    if (fileInputRef.current) fileInputRef.current.value = '';
  }

  function onInputChange(v: string) {
    setInput(v);
    const now = Date.now();
    if (activeId && now - typingSentAtRef.current > 2000) {
      typingSentAtRef.current = now;
      sendRef.current({ type: 'TYPING', conversationId: activeId });
    }
  }

  async function onSend() {
    if (!activeId || sending) return;
    const body = input.trim();
    if (!body && pendingFiles.length === 0) return;
    setError(null);
    if (pendingFiles.length > 0) {
      setSending(true);
      try {
        const item = await chatApi.sendFiles(activeId, pendingFiles, body);
        setInput('');
        setPendingFiles([]);
        upsertMessage(item);
        markRead(activeId);
      } catch (e) {
        setError(extractError(e));
      } finally {
        setSending(false);
      }
      return;
    }
    const clientMsgId = newClientMsgId();
    const ok = sendRef.current({ type: 'SEND', conversationId: activeId, body, clientMsgId });
    if (ok) {
      setPending((prev) => [...prev, { clientMsgId, body, createdAt: new Date().toISOString() }]);
      setInput('');
    } else {
      // WS down — REST fallback
      setSending(true);
      try {
        const item = await chatApi.sendText(activeId, body);
        setInput('');
        upsertMessage(item);
      } catch (e) {
        setError(extractError(e));
      } finally {
        setSending(false);
      }
    }
    markRead(activeId);
  }
// __RENDER__

  const active = conversations.find((c) => c.id === activeId) || null;

  const filteredContacts = (contacts ?? []).filter((c) => {
    const q = contactQuery.trim().toLowerCase();
    if (!q) return true;
    return [c.displayName, c.displayNameEn, c.displayNameAr, c.username, c.team]
      .some((s) => (s || '').toLowerCase().includes(q));
  });

  const roleLabel = (role: string) => role === 'SUPER_ADMIN'
    ? (isAr ? 'سوبر أدمن' : 'Super Admin')
    : role === 'ADMIN' ? (isAr ? 'قائد فريق' : 'Team Leader')
    : (isAr ? 'موظف إدخال' : 'Agent');

  const nameOf = (n: {
    displayName?: string | null; displayNameEn?: string | null; displayNameAr?: string | null;
    otherName?: string | null; otherNameEn?: string | null; otherNameAr?: string | null;
    username?: string;
  }) => isAr
    ? (n.displayNameAr || n.otherNameAr || n.displayName || n.otherName || n.username || '?')
    : (n.displayNameEn || n.otherNameEn || n.displayName || n.otherName || n.username || '?');

  return (
    <div className={`chat-page${isAr ? ' chat-rtl' : ''}`}>
      <aside className="chat-list">
        <div className="chat-list-head">
          <h2>{t('chat.title')}</h2>
          <button type="button" className="btn btn-primary btn-sm" onClick={openContacts}>
            + {t('chat.newChat')}
          </button>
        </div>
        <div className={`chat-conn${socket.connected ? ' on' : ''}`}>
          {socket.connected ? t('chat.connected') : t('chat.connecting')}
        </div>
        {booting ? (
          <div className="chat-empty muted">{t('common.loading')}</div>
        ) : conversations.length === 0 ? (
          <div className="chat-empty muted">{t('chat.noConversations')}</div>
        ) : (
          <ul className="chat-conv-items">
            {conversations.map((c) => (
              <li key={c.id}>
                <button
                  type="button"
                  className={`chat-conv${c.id === activeId ? ' active' : ''}`}
                  onClick={() => openConversation(c.id)}
                >
                  <Avatar
                    name={nameOf(c)}
                    size="md"
                    src={avatarUrl(c.otherUserId, c.otherAvatarUpdatedAt)}
                  />
                  <span className="chat-conv-meta">
                    <span className="chat-conv-name">
                      {nameOf(c)}
                      <span className="chat-conv-role">{roleLabel(c.otherRole)}</span>
                    </span>
                    <span className="chat-conv-preview">{c.lastMessagePreview || '—'}</span>
                  </span>
                  {c.unreadCount > 0 && (
                    <span className="chat-badge">{c.unreadCount > 99 ? '99+' : c.unreadCount}</span>
                  )}
                </button>
              </li>
            ))}
          </ul>
        )}
      </aside>

      <section className="chat-window">
        {!active ? (
          <div className="chat-placeholder">
            <IconChat size={44} />
            <p>{t('chat.pickConversation')}</p>
            <button type="button" className="btn btn-primary" onClick={openContacts}>
              {t('chat.newChat')}
            </button>
          </div>
        ) : (
          <>
            <header className="chat-window-head">
              <Avatar name={nameOf(active)} size="md"
                      src={avatarUrl(active.otherUserId, active.otherAvatarUpdatedAt)} />
              <div className="chat-window-title">
                <strong>{nameOf(active)}</strong>
                <span className="muted small">
                  {roleLabel(active.otherRole)}{active.otherTeam ? ` · ${active.otherTeam}` : ''}
                </span>
              </div>
            </header>

            <div className="chat-scroll" ref={scrollRef}>
              {messages.map((m) => {
                const mine = m.senderId === myId;
                return (
                  <div key={m.id} className={`chat-row${mine ? ' mine' : ''}`}>
                    <div className="chat-bubble">
                      {m.body && <div className="chat-text">{m.body}</div>}
                      {m.attachments.map((a) => a.kind === 'IMAGE' ? (
                        <a key={a.id} href={attachmentUrl(a.id)} target="_blank" rel="noreferrer"
                           className="chat-att-img">
                          <img src={attachmentUrl(a.id)} alt={a.filename} loading="lazy" />
                        </a>
                      ) : (
                        <a key={a.id} href={attachmentUrl(a.id)} className="chat-att-file" download>
                          <span className="chat-att-ico">{a.kind === 'PDF' ? '📄' : '📝'}</span>
                          <span className="chat-att-name">
                            {a.filename}
                            <small>{fileSize(a.sizeBytes)}</small>
                          </span>
                          <span className="chat-att-dl">⬇</span>
                        </a>
                      ))}
                      <span className="chat-time">
                        {new Date(m.createdAt).toLocaleTimeString(isAr ? 'ar-EG' : 'en-GB',
                          { hour: '2-digit', minute: '2-digit' })}
                        {mine && <span className="chat-read">{m.readAt ? '✓✓' : '✓'}</span>}
                      </span>
                    </div>
                  </div>
                );
              })}
              {pending.map((p) => (
                <div key={p.clientMsgId} className="chat-row mine">
                  <div className="chat-bubble is-pending">
                    <div className="chat-text">{p.body}</div>
                    <span className="chat-time">…</span>
                  </div>
                </div>
              ))}
              {typingName && <div className="chat-typing">{t('chat.typing', { name: typingName })}</div>}
            </div>

            {error && <div className="chat-error" role="alert">{error}</div>}
            {pendingFiles.length > 0 && (
              <div className="chat-files-row">
                {pendingFiles.map((f, i) => (
                  <span key={`${f.name}-${i}`} className="chat-file-chip">
                    {f.type.startsWith('image/') ? '🖼' : f.type === 'application/pdf' ? '📄' : '📝'}
                    {' '}{f.name}
                    <button type="button" aria-label={t('common.delete')}
                            onClick={() => setPendingFiles((prev) => prev.filter((_, j) => j !== i))}>
                      <IconClose size={12} />
                    </button>
                  </span>
                ))}
              </div>
            )}

            <form className="chat-composer" onSubmit={(e) => { e.preventDefault(); onSend(); }}>
              <input
                ref={fileInputRef}
                type="file"
                hidden
                multiple
                accept=".png,.jpg,.jpeg,.webp,.gif,.pdf,.doc,.docx,image/*,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                onChange={(e) => onFilesChosen(e.target.files)}
              />
              <button
                type="button"
                className="chat-icon-btn"
                aria-label={t('chat.attach')}
                title={t('chat.attach')}
                onClick={() => fileInputRef.current?.click()}
              >
                <IconPaperclip size={20} />
              </button>
              <textarea
                className="chat-input"
                rows={1}
                value={input}
                maxLength={4000}
                placeholder={t('chat.placeholder')}
                onChange={(e) => onInputChange(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    onSend();
                  }
                }}
              />
              <button
                type="submit"
                className="chat-send-btn"
                disabled={sending || (!input.trim() && pendingFiles.length === 0)}
                aria-label={t('chat.send')}
              >
                <IconSend size={18} />
              </button>
            </form>
          </>
        )}
      </section>

      {contactsOpen && (
        <div className="chat-contacts-backdrop" onClick={() => setContactsOpen(false)}>
          <div className="chat-contacts" onClick={(e) => e.stopPropagation()}>
            <div className="chat-contacts-head">
              <h3>{t('chat.pickPerson')}</h3>
              <button type="button" className="icon-btn" onClick={() => setContactsOpen(false)}>
                <IconClose size={18} />
              </button>
            </div>
            <div className="chat-contacts-search">
              <IconSearch size={16} />
              <input
                value={contactQuery}
                onChange={(e) => setContactQuery(e.target.value)}
                placeholder={t('chat.searchPeople')}
              />
            </div>
            <ul className="chat-contacts-list">
              {!contacts ? (
                <li className="muted">{t('common.loading')}</li>
              ) : filteredContacts.length === 0 ? (
                <li className="muted">{t('chat.noPeople')}</li>
              ) : filteredContacts.map((c) => (
                <li key={c.id}>
                  <button type="button" className="chat-contact" onClick={() => startWith(c)}>
                    <Avatar
                      name={nameOf(c)}
                      size="sm"
                      src={avatarUrl(c.id, c.avatarUpdatedAt)}
                    />
                    <span className="chat-contact-meta">
                      <strong>{nameOf(c)}</strong>
                      <small className="muted">{roleLabel(c.role)}{c.team ? ` · ${c.team}` : ''}</small>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}
    </div>
  );
}
