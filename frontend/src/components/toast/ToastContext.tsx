import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import { ToastContainer } from './ToastContainer';

export type ToastVariant = 'success' | 'error' | 'info' | 'warning';

export interface ToastItem {
  id: number;
  message: string;
  variant: ToastVariant;
  /** How long to stay visible in ms. Defaults to 4000 (5500 for errors). */
  duration?: number;
}

interface ToastContextValue {
  push: (variant: ToastVariant, message: string, duration?: number) => void;
  success: (message: string, duration?: number) => void;
  error:   (message: string, duration?: number) => void;
  info:    (message: string, duration?: number) => void;
  warning: (message: string, duration?: number) => void;
  dismiss: (id: number) => void;
}

const ToastContext = createContext<ToastContextValue | undefined>(undefined);

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const idRef = useRef(1);

  const dismiss = useCallback((id: number) => {
    setItems((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const push = useCallback((variant: ToastVariant, message: string, duration?: number) => {
    const id = idRef.current++;
    const defaultDur = variant === 'error' ? 5500 : 4000;
    const item: ToastItem = { id, variant, message, duration: duration ?? defaultDur };
    setItems((prev) => [...prev, item]);
    if (item.duration && item.duration > 0) {
      window.setTimeout(() => dismiss(id), item.duration);
    }
  }, [dismiss]);

  const api = useMemo<ToastContextValue>(() => ({
    push,
    success: (m, d) => push('success', m, d),
    error:   (m, d) => push('error',   m, d),
    info:    (m, d) => push('info',    m, d),
    warning: (m, d) => push('warning', m, d),
    dismiss,
  }), [push, dismiss]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      <ToastContainer items={items} onDismiss={dismiss} />
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used inside <ToastProvider>');
  return ctx;
}
