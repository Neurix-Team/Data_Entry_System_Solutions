import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { extractError } from '../../api/client';
import { useAuth } from '../../context/AuthContext';
import { useT } from '../../i18n';
import { Avatar } from '../../components/Avatar';
import { avatarUrl } from '../../api/profile';
import {
  IconChat, IconChevronDown, IconClose, IconMembers, IconPaperclip, IconPlus, IconSearch, IconSend,
} from '../../components/Icons';
import {
  attachmentUrl, chatApi, chatGroupApi, groupAttachmentUrl,
  type ChatContact,
  type ChatConversationItem,
  type ChatGroupDetail,
  type ChatGroupItem,
  type ChatMessageItem,
  type ChatWsOut,
} from '../../api/chatMessaging';
import { CreateGroupModal } from '../../components/chat/CreateGroupModal';
import { GroupMembersModal } from '../../components/chat/GroupMembersModal';
import { useChatSocketContext } from '../../context/ChatSocketContext';
import './chat.css';

interface PendingBubble {
  clientMsgId: string;
  body: string;
  createdAt: string;
}

/** What the window is showing: a 1:1 conversation or a group. Never both. */
type Target = { kind: 'dm'; id: number } | { kind: 'group'; id: number };

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

function previewOf(m: ChatMessageItem): string {
  if (m.body) return m.body;
  const first = m.attachments[0];
  if (!first) return '';
  return (first.kind === 'IMAGE' ? '📷 ' : '📎 ') + first.filename;
}

export default function ChatPage() {
  const { user } = useAuth();
  const { lang, t } = useT();
  const isAr = lang === 'ar';
  const [searchParams, setSearchParams] = useSearchParams();

  const [conversations, setConversations] = useState<ChatConversationItem[]>([]);
  const [groups, setGroups] = useState<ChatGroupItem[]>([]);
  const [target, setTarget] = useState<Target | null>(null);
  const [groupDetail, setGroupDetail] = useState<ChatGroupDetail | null>(null);
  const [messages, setMessages] = useState<ChatMessageItem[]>([]);
  const [pending, setPending] = useState<PendingBubble[]>([]);
  const [contacts, setContacts] = useState<ChatContact[] | null>(null);
  const [contactsOpen, setContactsOpen] = useState(false);
  const [contactQuery, setContactQuery] = useState('');
  const [createGroupOpen, setCreateGroupOpen] = useState(false);
  const [membersOpen, setMembersOpen] = useState(false);
  const [input, setInput] = useState('');
  const [pendingFiles, setPendingFiles] = useState<File[]>([]);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [typingName, setTypingName] = useState<string | null>(null);
  const [booting, setBooting] = useState(true);

  const targetRef = useRef<Target | null>(null);
  const sendRef = useRef<(p: Record<string, unknown>) => boolean>(() => false);
  const typingSentAtRef = useRef(0);
  const typingHideRef = useRef<number | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const conversationsRef = useRef<ChatConversationItem[]>([]);
  const groupDetailRef = useRef<ChatGroupDetail | null>(null);

  targetRef.current = target;
  conversationsRef.current = conversations;
  groupDetailRef.current = groupDetail;
  const myId = user?.id ?? 0;

  const isViewing = (kind: Target['kind'], id: number | null | undefined) =>
    id != null && targetRef.current?.kind === kind && targetRef.current.id === id;

  /** The id field the server expects on a frame for whatever the window currently shows. */
  const frameTarget = (tg: Target) => (tg.kind === 'dm' ? { conversationId: tg.id } : { groupId: tg.id });

  // ── 1:1 ────────────────────────────────────────────────────────────────────────

  const upsertMessage = useCallback((m: ChatMessageItem, clientMsgId?: string | null) => {
    if (clientMsgId) setPending((prev) => prev.filter((p) => p.clientMsgId !== clientMsgId));
    setMessages((prev) => {
      const idx = prev.findIndex((x) => x.id === m.id);
      if (idx === -1) return [...prev, m];
      const next = [...prev];
      next[idx] = m;
      return next;
    });
  }, []);

  const bumpConversation = useCallback((m: ChatMessageItem) => {
    const mine = m.senderId === myId;
    setConversations((prev) => {
      const idx = prev.findIndex((c) => c.id === m.conversationId);
      if (idx === -1) return prev;
      const cur = prev[idx];
      const bumped: ChatConversationItem = {
        ...cur,
        lastMessagePreview: previewOf(m),
        lastMessageAt: m.createdAt,
        unreadCount: mine
          ? cur.unreadCount
          : (isViewing('dm', m.conversationId) ? 0 : cur.unreadCount + 1),
      };
      const next = [...prev];
      next.splice(idx, 1);
      return [bumped, ...next];
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [myId]);

  const loadConversations = useCallback(async () => {
    try {
      const data = await chatApi.conversations();
      setConversations(data.items);
    } catch (e) {
      setError(extractError(e, 'Chat failed to load'));
    }
  }, []);

  // ── groups ─────────────────────────────────────────────────────────────────────

  const loadGroups = useCallback(async () => {
    try {
      setGroups(await chatGroupApi.list());
    } catch { /* groups are additive — a failure here must not blank the 1:1 list */ }
  }, []);

  const loadGroupDetail = useCallback(async (id: number) => {
    try {
      setGroupDetail(await chatGroupApi.detail(id));
    } catch { /* header falls back to the list's name/count */ }
  }, []);

  const bumpGroup = useCallback((m: ChatMessageItem) => {
    const mine = m.senderId === myId;
    setGroups((prev) => {
      const idx = prev.findIndex((g) => g.id === m.groupId);
      if (idx === -1) return prev;
      const cur = prev[idx];
      const bumped: ChatGroupItem = {
        ...cur,
        lastMessagePreview: m.kind === 'SYSTEM' ? m.body : previewOf(m),
        lastMessageAt: m.createdAt,
        unreadCount: (mine || m.kind === 'SYSTEM' || isViewing('group', m.groupId))
          ? cur.unreadCount
          : cur.unreadCount + 1,
      };
      const next = [...prev];
      next.splice(idx, 1);
      return [bumped, ...next];
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [myId]);

  // ── read + open ────────────────────────────────────────────────────────────────

  const markRead = useCallback((tg: Target) => {
    const ok = sendRef.current({ type: 'READ', ...frameTarget(tg) });
    if (tg.kind === 'dm') {
      if (!ok) chatApi.markRead(tg.id).catch(() => undefined);
      setConversations((prev) => prev.map((c) => (c.id === tg.id ? { ...c, unreadCount: 0 } : c)));
    } else {
      if (!ok) chatGroupApi.markRead(tg.id).catch(() => undefined);
      setGroups((prev) => prev.map((g) => (g.id === tg.id ? { ...g, unreadCount: 0 } : g)));
    }
  }, []);

  const openTarget = useCallback(async (tg: Target) => {
    setTarget(tg);
    targetRef.current = tg;
    setMessages([]);
    setPending([]);
    setError(null);
    setTypingName(null);
    setGroupDetail(null);
    setSearchParams(tg.kind === 'dm' ? { c: String(tg.id) } : { g: String(tg.id) }, { replace: true });
    try {
      if (tg.kind === 'dm') {
        const page = await chatApi.messages(tg.id);
        setMessages(page.messages);
      } else {
        const [page] = await Promise.all([chatGroupApi.messages(tg.id), loadGroupDetail(tg.id)]);
        setMessages(page.messages);
      }
      markRead(tg);
    } catch (e) {
      setError(extractError(e));
    }
  }, [loadGroupDetail, markRead, setSearchParams]);

  const closeTarget = useCallback(() => {
    setTarget(null);
    targetRef.current = null;
    setMessages([]);
    setGroupDetail(null);
    setSearchParams({}, { replace: true });
  }, [setSearchParams]);

  // ── live frames ────────────────────────────────────────────────────────────────

  const handleWs = useCallback((m: ChatWsOut) => {
    switch (m.type) {
      case 'MESSAGE': {
        if (!m.message) return;
        const msg = m.message;
        if (msg.groupId != null) {
          if (isViewing('group', msg.groupId)) {
            if (msg.senderId !== myId) markRead({ kind: 'group', id: msg.groupId });
            upsertMessage(msg, m.clientMsgId ?? undefined);
          }
          bumpGroup(msg);
          return;
        }
        if (msg.senderId !== myId && isViewing('dm', msg.conversationId)) {
          markRead({ kind: 'dm', id: msg.conversationId as number });
        }
        if (isViewing('dm', msg.conversationId)) upsertMessage(msg, m.clientMsgId ?? undefined);
        bumpConversation(msg);
        break;
      }
      case 'READ': {
        if (isViewing('dm', m.conversationId) && m.readerId != null && m.readerId !== myId) {
          setMessages((prev) => prev.map((x) => (
            x.senderId === myId && !x.readAt ? { ...x, readAt: new Date().toISOString() } : x)));
        }
        break;
      }
      case 'TYPING': {
        if (m.fromUserId === myId) return;
        let who: string | null = null;
        if (m.groupId != null) {
          if (!isViewing('group', m.groupId)) return;
          const member = groupDetailRef.current?.members.find((x) => x.userId === m.fromUserId);
          who = member ? (isAr ? (member.displayNameAr || member.displayName) : (member.displayNameEn || member.displayName))
            : t('chat.someoneTyping');
        } else {
          if (!isViewing('dm', m.conversationId)) return;
          who = conversationsRef.current.find((c) => c.id === m.conversationId)?.otherName ?? null;
        }
        setTypingName(who);
        if (typingHideRef.current != null) window.clearTimeout(typingHideRef.current);
        typingHideRef.current = window.setTimeout(() => setTypingName(null), 2600);
        break;
      }
      case 'GROUP_UPDATED': {
        loadGroups();
        if (isViewing('group', m.groupId)) loadGroupDetail(m.groupId as number);
        break;
      }
      case 'GROUP_REMOVED': {
        loadGroups();
        if (isViewing('group', m.groupId)) closeTarget();
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [markRead, myId, t, isAr, upsertMessage, bumpConversation, bumpGroup, loadGroups, loadGroupDetail, closeTarget]);

  // One connection for the whole app (see ChatSocketProvider) — this page just listens
  // to it rather than opening its own, so leaving /chat never drops anyone else's socket.
  const socket = useChatSocketContext();
  useEffect(() => socket.subscribe(handleWs), [socket, handleWs]);
  sendRef.current = socket.send;

  // ── boot + polling ─────────────────────────────────────────────────────────────

  useEffect(() => {
    if (!user) return;
    Promise.all([loadConversations(), loadGroups()]).finally(() => setBooting(false));
    const dm = Number(searchParams.get('c'));
    const grp = Number(searchParams.get('g'));
    if (Number.isFinite(grp) && grp > 0) openTarget({ kind: 'group', id: grp });
    else if (Number.isFinite(dm) && dm > 0) openTarget({ kind: 'dm', id: dm });
    const refresh = () => { loadConversations(); loadGroups(); };
    window.addEventListener('focus', refresh);
    const poll = window.setInterval(refresh, 20000);
    return () => {
      window.removeEventListener('focus', refresh);
      window.clearInterval(poll);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user?.id]);

  // A toast/notification can send us to a different chat while this page is already open.
  useEffect(() => {
    const dm = Number(searchParams.get('c'));
    const grp = Number(searchParams.get('g'));
    const wanted: Target | null = Number.isFinite(grp) && grp > 0
      ? { kind: 'group', id: grp }
      : Number.isFinite(dm) && dm > 0 ? { kind: 'dm', id: dm } : null;
    if (wanted && (targetRef.current?.kind !== wanted.kind || targetRef.current.id !== wanted.id)) {
      openTarget(wanted);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams]);

  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages, pending, typingName]);

  // ── actions ────────────────────────────────────────────────────────────────────

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
      await openTarget({ kind: 'dm', id: conv.id });
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
    if (target && now - typingSentAtRef.current > 2000) {
      typingSentAtRef.current = now;
      sendRef.current({ type: 'TYPING', ...frameTarget(target) });
    }
  }

  async function onSend() {
    if (!target || sending) return;
    const body = input.trim();
    if (!body && pendingFiles.length === 0) return;
    setError(null);
    const tg = target;
    if (pendingFiles.length > 0) {
      setSending(true);
      try {
        const item = tg.kind === 'dm'
          ? await chatApi.sendFiles(tg.id, pendingFiles, body)
          : await chatGroupApi.sendFiles(tg.id, pendingFiles, body);
        setInput('');
        setPendingFiles([]);
        upsertMessage(item);
        if (tg.kind === 'dm') bumpConversation(item); else bumpGroup(item);
        markRead(tg);
      } catch (e) {
        setError(extractError(e));
      } finally {
        setSending(false);
      }
      return;
    }
    const clientMsgId = newClientMsgId();
    const ok = sendRef.current({ type: 'SEND', ...frameTarget(tg), body, clientMsgId });
    if (ok) {
      setPending((prev) => [...prev, { clientMsgId, body, createdAt: new Date().toISOString() }]);
      setInput('');
    } else {
      // WS down — REST fallback
      setSending(true);
      try {
        const item = tg.kind === 'dm'
          ? await chatApi.sendText(tg.id, body)
          : await chatGroupApi.sendText(tg.id, body);
        setInput('');
        upsertMessage(item);
      } catch (e) {
        setError(extractError(e));
      } finally {
        setSending(false);
      }
    }
    markRead(tg);
  }

  // ── derived view state ─────────────────────────────────────────────────────────

  const activeDm = target?.kind === 'dm' ? conversations.find((c) => c.id === target.id) ?? null : null;
  const activeGroup = target?.kind === 'group' ? groups.find((g) => g.id === target.id) ?? null : null;
  const hasActive = target != null && (activeDm != null || activeGroup != null);

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

  const inGroup = target?.kind === 'group';
  const attachHref = (id: number) => (inGroup ? groupAttachmentUrl(id) : attachmentUrl(id));

  return (
    <div className={`chat-page${isAr ? ' chat-rtl' : ''}${hasActive ? ' has-active' : ''}`}>
      <aside className="chat-list">
        <div className="chat-list-head">
          <h2>{t('chat.title')}</h2>
          <div className="chat-list-actions">
            <button type="button" className="btn btn-sm" onClick={() => setCreateGroupOpen(true)}>
              <IconMembers size={14} /> {t('chat.newGroup')}
            </button>
            <button type="button" className="btn btn-primary btn-sm" onClick={openContacts}>
              <IconPlus size={14} /> {t('chat.newChat')}
            </button>
          </div>
        </div>
        <div className={`chat-conn${socket.connected ? ' on' : ''}`}>
          {socket.connected ? t('chat.connected') : t('chat.connecting')}
        </div>
        {booting ? (
          <div className="chat-empty muted">{t('common.loading')}</div>
        ) : conversations.length === 0 && groups.length === 0 ? (
          <div className="chat-empty muted">{t('chat.noConversations')}</div>
        ) : (
          <div className="chat-conv-scroll">
            {groups.length > 0 && (
              <>
                <div className="chat-section-title">{t('chat.groupsHeading')}</div>
                <ul className="chat-conv-items">
                  {groups.map((g) => (
                    <li key={`g${g.id}`}>
                      <button
                        type="button"
                        className={`chat-conv${target?.kind === 'group' && target.id === g.id ? ' active' : ''}`}
                        onClick={() => openTarget({ kind: 'group', id: g.id })}
                      >
                        <span className="chat-group-avatar" aria-hidden="true"><IconMembers size={18} /></span>
                        <span className="chat-conv-meta">
                          <span className="chat-conv-name">
                            {g.name}
                            <span className="chat-conv-role">{t('chat.group.members', { n: g.memberCount })}</span>
                          </span>
                          <span className="chat-conv-preview">{g.lastMessagePreview || '—'}</span>
                        </span>
                        {g.unreadCount > 0 && (
                          <span className="chat-badge">{g.unreadCount > 99 ? '99+' : g.unreadCount}</span>
                        )}
                      </button>
                    </li>
                  ))}
                </ul>
              </>
            )}
            {conversations.length > 0 && (
              <>
                {groups.length > 0 && <div className="chat-section-title">{t('chat.directHeading')}</div>}
                <ul className="chat-conv-items">
                  {conversations.map((c) => (
                    <li key={`c${c.id}`}>
                      <button
                        type="button"
                        className={`chat-conv${target?.kind === 'dm' && target.id === c.id ? ' active' : ''}`}
                        onClick={() => openTarget({ kind: 'dm', id: c.id })}
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
              </>
            )}
          </div>
        )}
      </aside>

      <section className="chat-window">
        {!hasActive ? (
          <div className="chat-placeholder">
            <IconChat size={44} />
            <p>{t('chat.pickConversation')}</p>
            <div className="chat-placeholder-actions">
              <button type="button" className="btn btn-primary" onClick={openContacts}>
                {t('chat.newChat')}
              </button>
              <button type="button" className="btn" onClick={() => setCreateGroupOpen(true)}>
                {t('chat.newGroup')}
              </button>
            </div>
          </div>
        ) : (
          <>
            <header className="chat-window-head">
              <button type="button" className="chat-back-btn" onClick={closeTarget}
                      aria-label={t('chat.back')} title={t('chat.back')}>
                <IconChevronDown size={20} />
              </button>
              {activeGroup ? (
                <>
                  <span className="chat-group-avatar" aria-hidden="true"><IconMembers size={18} /></span>
                  <div className="chat-window-title">
                    <strong>{groupDetail?.name ?? activeGroup.name}</strong>
                    <span className="muted small">
                      {t('chat.group.members', { n: groupDetail?.members.length ?? activeGroup.memberCount })}
                      {activeGroup.isAdmin ? ` · ${t('chat.group.admin')}` : ''}
                    </span>
                  </div>
                  <button type="button" className="btn btn-sm chat-head-action"
                          onClick={() => setMembersOpen(true)}>
                    <IconMembers size={14} /> <span>{t('chat.group.viewMembers')}</span>
                  </button>
                </>
              ) : activeDm ? (
                <>
                  <Avatar name={nameOf(activeDm)} size="md"
                          src={avatarUrl(activeDm.otherUserId, activeDm.otherAvatarUpdatedAt)} />
                  <div className="chat-window-title">
                    <strong>{nameOf(activeDm)}</strong>
                    <span className="muted small">
                      {roleLabel(activeDm.otherRole)}{activeDm.otherTeam ? ` · ${activeDm.otherTeam}` : ''}
                    </span>
                  </div>
                </>
              ) : null}
            </header>

            <div className="chat-scroll" ref={scrollRef}>
              {messages.map((m, i) => {
                if (m.kind === 'SYSTEM') {
                  return (
                    <div key={m.id ?? `sys-${i}`} className="chat-system-row">
                      <span className="chat-system-note">{m.body}</span>
                    </div>
                  );
                }
                const mine = m.senderId === myId;
                return (
                  <div key={m.id ?? `m-${i}`} className={`chat-row${mine ? ' mine' : ''}`}>
                    <div className="chat-bubble">
                      {inGroup && !mine && m.senderName && (
                        <div className="chat-sender">{m.senderName}</div>
                      )}
                      {m.body && <div className="chat-text">{m.body}</div>}
                      {m.attachments.map((a) => a.kind === 'IMAGE' ? (
                        <a key={a.id} href={attachHref(a.id)} target="_blank" rel="noreferrer"
                           className="chat-att-img">
                          <img src={attachHref(a.id)} alt={a.filename} loading="lazy" />
                        </a>
                      ) : (
                        <a key={a.id} href={attachHref(a.id)} className="chat-att-file" download>
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
                        {mine && !inGroup && <span className="chat-read">{m.readAt ? '✓✓' : '✓'}</span>}
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

      <CreateGroupModal
        open={createGroupOpen}
        onClose={() => setCreateGroupOpen(false)}
        onCreated={(g) => {
          setCreateGroupOpen(false);
          setGroups((prev) => (prev.some((x) => x.id === g.id) ? prev : [g, ...prev]));
          openTarget({ kind: 'group', id: g.id });
        }}
      />

      <GroupMembersModal
        groupId={membersOpen && target?.kind === 'group' ? target.id : null}
        onClose={() => setMembersOpen(false)}
        onChanged={() => {
          loadGroups();
          if (target?.kind === 'group') loadGroupDetail(target.id);
        }}
        onLeft={() => { setMembersOpen(false); closeTarget(); loadGroups(); }}
      />
    </div>
  );
}
