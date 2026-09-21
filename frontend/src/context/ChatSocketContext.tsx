import { createContext, useCallback, useContext, useMemo, useRef } from 'react';
import type { ChatWsOut } from '../api/chatMessaging';
import { useChatSocket } from '../hooks/useChatSocket';
import { useAuth } from './AuthContext';

type Listener = (msg: ChatWsOut) => void;

interface ChatSocketContextValue {
  connected: boolean;
  send: (payload: Record<string, unknown>) => boolean;
  /** Registers a listener for every inbound frame; call the returned function to stop. */
  subscribe: (fn: Listener) => () => void;
}

const ChatSocketContext = createContext<ChatSocketContextValue | undefined>(undefined);

const DISCONNECTED: ChatSocketContextValue = {
  connected: false,
  send: () => false,
  subscribe: () => () => undefined,
};

/**
 * One live /ws/chat connection for the whole signed-in app, not one per page. ChatPage
 * needs it while it's open, but a message can just as easily arrive while someone is on
 * the dashboard or an admin screen — the chime, the toast and the notification bell all
 * need that same real-time signal, and giving each of them their own socket would triple
 * the connections and the reconnect storms for no benefit.
 *
 * <p>Mounted once, above every route (both {@code Layout} and {@code SuperLayout} sit
 * under it), so it never depends on which shell happens to be rendering.</p>
 */
export function ChatSocketProvider({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  // Signed out: no token to authenticate a handshake with, so there is nothing to hold
  // open — a disconnected, no-op value keeps every consumer's code identical either way.
  return user ? (
    <ConnectedChatSocketProvider>{children}</ConnectedChatSocketProvider>
  ) : (
    <ChatSocketContext.Provider value={DISCONNECTED}>{children}</ChatSocketContext.Provider>
  );
}

function ConnectedChatSocketProvider({ children }: { children: React.ReactNode }) {
  const listenersRef = useRef<Set<Listener>>(new Set());

  const dispatch = useCallback((msg: ChatWsOut) => {
    listenersRef.current.forEach((fn) => {
      try { fn(msg); } catch { /* one broken listener must not silence the others */ }
    });
  }, []);

  const socket = useChatSocket(dispatch);

  const subscribe = useCallback((fn: Listener) => {
    listenersRef.current.add(fn);
    return () => { listenersRef.current.delete(fn); };
  }, []);

  const value = useMemo<ChatSocketContextValue>(
    () => ({ connected: socket.connected, send: socket.send, subscribe }),
    [socket.connected, socket.send, subscribe],
  );

  return <ChatSocketContext.Provider value={value}>{children}</ChatSocketContext.Provider>;
}

export function useChatSocketContext(): ChatSocketContextValue {
  const ctx = useContext(ChatSocketContext);
  if (!ctx) throw new Error('useChatSocketContext must be used inside <ChatSocketProvider>');
  return ctx;
}
