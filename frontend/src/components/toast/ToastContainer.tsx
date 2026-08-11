import { ConfettiBurst } from './ConfettiBurst';
import type { ToastItem } from './ToastContext';

interface Props {
  items: ToastItem[];
  onDismiss: (id: number) => void;
}

/**
 * Fixed-position stack of active toasts, top-center on desktop and full-width top on mobile.
 * Success toasts get a confetti burst behind them.
 */
export function ToastContainer({ items, onDismiss }: Props) {
  if (items.length === 0) return null;
  return (
    <div className="toast-container" role="region" aria-label="Notifications">
      {items.map((t) => (
        <div key={t.id} className={`toast toast-${t.variant}`} role={t.variant === 'error' ? 'alert' : 'status'}>
          {t.variant === 'success' && <ConfettiBurst />}
          <span className="toast-icon" aria-hidden="true">
            {t.variant === 'success' && (
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                <circle cx="12" cy="12" r="11" fill="currentColor" opacity="0.15"/>
                <path d="M7 12.5l3.2 3.2L17 8.5" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"/>
              </svg>
            )}
            {t.variant === 'error' && (
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                <circle cx="12" cy="12" r="11" fill="currentColor" opacity="0.15"/>
                <path d="M12 7v6M12 16.5v.5" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round"/>
              </svg>
            )}
            {t.variant === 'info' && (
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                <circle cx="12" cy="12" r="11" fill="currentColor" opacity="0.15"/>
                <path d="M12 11v6M12 7.5v.5" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round"/>
              </svg>
            )}
            {t.variant === 'warning' && (
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
                <path d="M12 3l10 18H2L12 3z" fill="currentColor" opacity="0.15" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round"/>
                <path d="M12 10v5M12 17.5v.5" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round"/>
              </svg>
            )}
          </span>
          <span className="toast-message">{t.message}</span>
          <button
            type="button"
            className="toast-close"
            aria-label="Dismiss"
            onClick={() => onDismiss(t.id)}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M6 6l12 12M18 6L6 18" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/>
            </svg>
          </button>
          <div className="toast-progress" style={{ animationDuration: `${t.duration ?? 4000}ms` }} />
        </div>
      ))}
    </div>
  );
}
