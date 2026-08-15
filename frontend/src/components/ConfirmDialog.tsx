import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import { Modal } from './Modal';
import { useT } from '../i18n';

interface ConfirmOptions {
  title?: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  destructive?: boolean;
}

interface ConfirmContextValue {
  /** Returns a promise that resolves to true if the user confirmed. */
  confirm: (opts: ConfirmOptions) => Promise<boolean>;
}

const ConfirmContext = createContext<ConfirmContextValue | undefined>(undefined);

/**
 * Replaces `window.confirm` — an accessible, RTL-aware, styled dialog. Renders through
 * the shared Modal so it inherits focus trap + Escape + backdrop dismiss.
 */
export function ConfirmProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<(ConfirmOptions & { open: boolean }) | null>(null);
  const resolver = useRef<((value: boolean) => void) | null>(null);
  const { lang } = useT();
  const isAr = lang === 'ar';

  const confirm = useCallback((opts: ConfirmOptions): Promise<boolean> => {
    setState({ ...opts, open: true });
    return new Promise<boolean>((resolve) => {
      resolver.current = resolve;
    });
  }, []);

  const close = useCallback((result: boolean) => {
    resolver.current?.(result);
    resolver.current = null;
    setState((s) => (s ? { ...s, open: false } : s));
  }, []);

  const value = useMemo(() => ({ confirm }), [confirm]);

  const title = state?.title ?? (isAr ? 'تأكيد' : 'Confirm');
  const confirmLabel = state?.confirmLabel ?? (isAr ? 'تأكيد' : 'Confirm');
  const cancelLabel = state?.cancelLabel ?? (isAr ? 'إلغاء' : 'Cancel');

  return (
    <ConfirmContext.Provider value={value}>
      {children}
      <Modal
        open={!!state?.open}
        title={title}
        onClose={() => close(false)}
        footer={
          <>
            <button type="button" className="btn btn-secondary" onClick={() => close(false)}>
              {cancelLabel}
            </button>
            <button
              type="button"
              className={state?.destructive ? 'btn btn-danger' : 'btn btn-primary'}
              onClick={() => close(true)}
              autoFocus
            >
              {confirmLabel}
            </button>
          </>
        }
      >
        <p style={{ margin: '0.75rem 0 1rem' }}>{state?.message}</p>
      </Modal>
    </ConfirmContext.Provider>
  );
}

export function useConfirm(): ConfirmContextValue['confirm'] {
  const ctx = useContext(ConfirmContext);
  if (!ctx) throw new Error('useConfirm must be used inside <ConfirmProvider>');
  return ctx.confirm;
}
