import { useCallback, useEffect, useRef, useState } from 'react';
import { chatSocketUrl, type ChatWsOut } from '../api/chatMessaging';
import { tokenStore } from '../api/client';

type Handler = (msg: ChatWsOut) => void;

/**
 * Live /ws/chat connection with automatic exponential-backoff reconnect.
 * Auth travels via ?token= (memory token when present); otherwise the dems_auth
 * cookie authenticates the handshake — browsers never send Authorization headers
 * on WebSocket upgrades.
 */
export interface ChatSocket {
  connected: boolean;
  send: (payload: Record<string, unknown>) => boolean;
}

export function useChatSocket(onMessage: Handler): ChatSocket {
  const [connected, setConnected] = useState(false);
  const handlerRef = useRef<Handler>(onMessage);
  const wsRef = useRef<WebSocket | null>(null);
  const retryRef = useRef(0);
  const timerRef = useRef<number | null>(null);
  const disposedRef = useRef(false);

  handlerRef.current = onMessage;

  const connect = useCallback(() => {
    if (disposedRef.current) return;
    const token = tokenStore.get();
    let ws: WebSocket;
    try {
      ws = new WebSocket(chatSocketUrl(token));
    } catch {
      scheduleRetry();
      return;
    }
    wsRef.current = ws;

    ws.onopen = () => {
      retryRef.current = 0;
      setConnected(true);
    };
    ws.onmessage = (ev) => {
      try {
        const parsed = JSON.parse(ev.data as string) as ChatWsOut;
        handlerRef.current(parsed);
      } catch { /* ignore malformed frame */ }
    };
    ws.onclose = () => {
      setConnected(false);
      if (wsRef.current === ws) wsRef.current = null;
      scheduleRetry();
    };
    ws.onerror = () => {
      try { ws.close(); } catch { /* ignore */ }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function scheduleRetry() {
    if (disposedRef.current) return;
    const delay = Math.min(1000 * Math.pow(2, retryRef.current), 15000);
    retryRef.current += 1;
    if (timerRef.current != null) window.clearTimeout(timerRef.current);
    timerRef.current = window.setTimeout(connect, delay);
  }

  useEffect(() => {
    disposedRef.current = false;
    connect();
    return () => {
      disposedRef.current = true;
      if (timerRef.current != null) window.clearTimeout(timerRef.current);
      const ws = wsRef.current;
      if (ws) {
        ws.onclose = null;
        try { ws.close(); } catch { /* ignore */ }
      }
    };
  }, [connect]);

  const send = useCallback((payload: Record<string, unknown>): boolean => {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(payload));
      return true;
    }
    return false;
  }, []);

  return { connected, send };
}
  