import { FormEvent, KeyboardEvent, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { extractError } from '../../api/client';
import { ChatAction, sendChatMessage } from '../../api/chat';
import { useT } from '../../i18n';

// UI copy is inlined here (bilingual) rather than hoisted into the i18n dicts —
// keeps the widget self-contained and easy to iterate on.

/**
 * Floating Neurix assistant. Click the pill to open a chat panel, ask about
 * pages or features, and get taken there via an action button in the reply.
 * Conversation persists in localStorage across page navigation.
 */

interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  actions?: ChatAction[];
  ts: number;
}

const STORAGE_KEY = 'neurix.chat.history';
const MAX_STORED = 40;

function loadHistory(): ChatMessage[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const arr = JSON.parse(raw) as ChatMessage[];
    if (!Array.isArray(arr)) return [];
    return arr.slice(-MAX_STORED);
  } catch {
    return [];
  }
}

function saveHistory(messages: ChatMessage[]) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(messages.slice(-MAX_STORED)));
  } catch { /* localStorage full — ignore */ }
}

function newId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

export function ChatWidget() {
  const { lang } = useT();
  const navigate = useNavigate();
  const location = useLocation();

  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>(loadHistory);
  const [input, setInput] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);

  const isAr = lang === 'ar';

  // Seed the very first greeting once
  useEffect(() => {
    if (messages.length === 0) {
      const greet: ChatMessage = {
        id: newId(),
        role: 'assistant',
        text: isAr
          ? 'أهلاً بك في Neurix 👋 اسألني عن أي صفحة أو ميزة، هأوديك عليها فوراً.'
          : "Hi! I'm the Neurix assistant 👋 Ask about any page or feature — I'll take you there.",
        ts: Date.now(),
      };
      setMessages([greet]);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Persist and auto-scroll on new messages
  useEffect(() => {
    saveHistory(messages);
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages, pending]);

  // Focus the input when the panel opens
  useEffect(() => {
    if (open) {
      setTimeout(() => inputRef.current?.focus(), 100);
    }
  }, [open]);

  async function send(raw: string) {
    const message = raw.trim();
    if (!message || pending) return;
    setError(null);
    setInput('');
    const userMsg: ChatMessage = { id: newId(), role: 'user', text: message, ts: Date.now() };
    setMessages((prev) => [...prev, userMsg]);
    setPending(true);
    try {
      const resp = await sendChatMessage(message, location.pathname, isAr ? 'ar' : 'en');
      const botMsg: ChatMessage = {
        id: newId(),
        role: 'assistant',
        text: resp.reply,
        actions: resp.actions,
        ts: Date.now(),
      };
      setMessages((prev) => [...prev, botMsg]);
    } catch (err) {
      setError(extractError(err, isAr ? 'حصل خطأ. حاول تاني.' : 'Something went wrong. Try again.'));
    } finally {
      setPending(false);
    }
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    send(input);
  }

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      send(input);
    }
  }

  function runAction(action: ChatAction) {
    if (action.type === 'navigate') {
      navigate(action.path);
      setOpen(false);
    }
  }

  function clearHistory() {
    setMessages([]);
    localStorage.removeItem(STORAGE_KEY);
    // Trigger the initial greeting to be re-seeded
    setTimeout(() => {
      const greet: ChatMessage = {
        id: newId(),
        role: 'assistant',
        text: isAr
          ? 'أهلاً بك في Neurix 👋 اسألني عن أي صفحة أو ميزة، هأوديك عليها فوراً.'
          : "Hi! I'm the Neurix assistant 👋 Ask about any page or feature — I'll take you there.",
        ts: Date.now(),
      };
      setMessages([greet]);
    }, 0);
  }

  return (
    <>
      {/* Floating trigger — always mounted; the panel slides in/out */}
      <button
        type="button"
        className={`chat-fab${open ? ' is-open' : ''}`}
        onClick={() => setOpen((v) => !v)}
        aria-label={open ? (isAr ? 'إغلاق المساعد' : 'Close assistant') : (isAr ? 'فتح مساعد Neurix' : 'Open Neurix assistant')}
        aria-expanded={open}
      >
        {open ? (
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" />
          </svg>
        ) : (
          <>
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M4 6.5A2.5 2.5 0 016.5 4h11A2.5 2.5 0 0120 6.5v8A2.5 2.5 0 0117.5 17H13l-3.5 3.5V17H6.5A2.5 2.5 0 014 14.5v-8z"
                    stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" fill="none" />
              <circle cx="9"  cy="10.5" r="1" fill="currentColor" />
              <circle cx="12" cy="10.5" r="1" fill="currentColor" />
              <circle cx="15" cy="10.5" r="1" fill="currentColor" />
            </svg>
            <span className="chat-fab-pulse" aria-hidden="true" />
          </>
        )}
      </button>

      {open && (
        <div className="chat-panel" role="dialog" aria-label={isAr ? 'مساعد Neurix' : 'Neurix Assistant'}>
          <header className="chat-header">
            <div className="chat-header-brand">
              <img
                src="/neurix-mark.png"
                alt=""
                width={28}
                height={28}
                onError={(e) => { (e.currentTarget as HTMLImageElement).style.display = 'none'; }}
                aria-hidden="true"
              />
              <div>
                <div className="chat-header-title">{isAr ? 'مساعد Neurix' : 'Neurix Assistant'}</div>
                <div className="chat-header-status">
                  <span className="chat-status-dot" aria-hidden="true" />
                  {isAr ? 'متاح الآن' : 'Online'}
                </div>
              </div>
            </div>
            <div className="chat-header-actions">
              <button
                type="button"
                className="chat-icon-btn"
                onClick={clearHistory}
                aria-label={isAr ? 'مسح المحادثة' : 'Clear conversation'}
                title={isAr ? 'مسح المحادثة' : 'Clear conversation'}
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/>
                </svg>
              </button>
              <button
                type="button"
                className="chat-icon-btn"
                onClick={() => setOpen(false)}
                aria-label={isAr ? 'إغلاق' : 'Close'}
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
                </svg>
              </button>
            </div>
          </header>

          <div className="chat-body" ref={scrollRef}>
            {messages.map((m) => (
              <div key={m.id} className={`chat-msg chat-msg-${m.role}`}>
                {m.role === 'assistant' && (
                  <div className="chat-avatar" aria-hidden="true">
                    <img
                      src="/neurix-mark.png"
                      alt=""
                      width={24}
                      height={24}
                      onError={(e) => { (e.currentTarget as HTMLImageElement).style.display = 'none'; }}
                    />
                  </div>
                )}
                <div className="chat-msg-content">
                  <div className="chat-bubble">{m.text}</div>
                  {m.actions && m.actions.length > 0 && (
                    <div className="chat-actions">
                      {m.actions.map((a, idx) => (
                        <button
                          key={idx}
                          type="button"
                          className="chat-action-btn"
                          onClick={() => runAction(a)}
                        >
                          {a.label}
                          <span aria-hidden="true">{isAr ? '←' : '→'}</span>
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            ))}
            {pending && (
              <div className="chat-msg chat-msg-assistant">
                <div className="chat-avatar" aria-hidden="true">
                  <img
                    src="/neurix-mark.png"
                    alt=""
                    width={24}
                    height={24}
                    onError={(e) => { (e.currentTarget as HTMLImageElement).style.display = 'none'; }}
                  />
                </div>
                <div className="chat-msg-content">
                  <div className="chat-bubble chat-typing" aria-label={isAr ? 'يكتب...' : 'Typing...'}>
                    <span /><span /><span />
                  </div>
                </div>
              </div>
            )}
            {error && (
              <div className="chat-error" role="alert">{error}</div>
            )}
          </div>

          <form className="chat-input-row" onSubmit={onSubmit}>
            <textarea
              ref={inputRef}
              className="chat-input"
              rows={1}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={onKeyDown}
              placeholder={isAr ? 'اكتب رسالتك…' : 'Type a message…'}
              disabled={pending}
              maxLength={2000}
            />
            <button
              type="submit"
              className="chat-send"
              disabled={pending || !input.trim()}
              aria-label={isAr ? 'إرسال' : 'Send'}
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d={isAr ? "M20 12l-14 -7l3 7l-3 7l14 -7z" : "M4 12l14 -7l-3 7l3 7l-14 -7z"}
                      fill="currentColor" />
              </svg>
            </button>
          </form>
        </div>
      )}
    </>
  );
}
