import { useEffect, useRef } from 'react';

interface Props {
  open: boolean;
  title: string;
  onClose: () => void;
  children: React.ReactNode;
  footer?: React.ReactNode;
}

/**
 * Accessible modal:
 *  - role=dialog + aria-modal + aria-labelledby so screen readers announce it as a dialog.
 *  - Focus is captured on open and restored to the previously focused element on close.
 *  - Tab / Shift+Tab cycle inside the modal so keyboard users can't tab into background UI.
 *  - Escape closes; backdrop click closes.
 */
export function Modal({ open, title, onClose, children, footer }: Props) {
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const titleId = useRef(`modal-title-${Math.random().toString(36).slice(2, 9)}`);

  useEffect(() => {
    if (!open) return;
    const previouslyFocused = document.activeElement as HTMLElement | null;

    // Focus the first focusable inside the dialog (or the dialog itself if empty).
    const focusables = getFocusable(dialogRef.current);
    (focusables[0] ?? dialogRef.current)?.focus();

    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
        return;
      }
      if (e.key !== 'Tab' || !dialogRef.current) return;
      const items = getFocusable(dialogRef.current);
      if (items.length === 0) {
        e.preventDefault();
        dialogRef.current.focus();
        return;
      }
      const first = items[0];
      const last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };
    window.addEventListener('keydown', onKey);

    // Block body scroll while a modal is up so the page doesn't scroll under it.
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    return () => {
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = prevOverflow;
      previouslyFocused?.focus?.();
    };
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div className="modal-backdrop" onClick={onClose} role="presentation">
      <div
        ref={dialogRef}
        className="modal"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId.current}
        tabIndex={-1}
      >
        <div className="modal-header">
          <h3 id={titleId.current} style={{ margin: 0 }}>{title}</h3>
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={onClose}
            aria-label="Close"
          >✕</button>
        </div>
        <div>{children}</div>
        {footer && <div className="modal-footer">{footer}</div>}
      </div>
    </div>
  );
}

function getFocusable(root: HTMLElement | null): HTMLElement[] {
  if (!root) return [];
  const sel = [
    'a[href]',
    'button:not([disabled])',
    'textarea:not([disabled])',
    'input:not([disabled]):not([type="hidden"])',
    'select:not([disabled])',
    '[tabindex]:not([tabindex="-1"])',
  ].join(',');
  return Array.from(root.querySelectorAll<HTMLElement>(sel))
    .filter((el) => el.offsetParent !== null || el === document.activeElement);
}
