import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
  /** Rendered instead of `children` when a descendant throws. Receives `reset` to try again. */
  fallback?: (error: Error, reset: () => void) => ReactNode;
}

interface State {
  error: Error | null;
}

/**
 * Contains render-time errors so a single broken component does not blank the entire app.
 * Wrap risky subtrees (chat widget, extracted images, page routes) with this and give
 * users a way to retry without a full reload.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('ErrorBoundary caught:', error, info.componentStack);
  }

  private reset = () => this.setState({ error: null });

  render() {
    if (this.state.error) {
      if (this.props.fallback) {
        return this.props.fallback(this.state.error, this.reset);
      }
      return <DefaultFallback error={this.state.error} reset={this.reset} />;
    }
    return this.props.children;
  }
}

function DefaultFallback({ error, reset }: { error: Error; reset: () => void }) {
  const isAr = typeof document !== 'undefined' && document.documentElement.getAttribute('dir') === 'rtl';
  return (
    <div className="error-boundary" role="alert">
      <div className="error-boundary-card">
        <h2 className="error-boundary-title">
          {isAr ? 'حصل خطأ' : 'Something went wrong'}
        </h2>
        <p className="error-boundary-message">
          {isAr
            ? 'حصلت مشكلة غير متوقعة أثناء عرض هذا الجزء. جرب مرة تانية أو حدث الصفحة.'
            : 'An unexpected error occurred while rendering this section. Try again or reload the page.'}
        </p>
        <details className="error-boundary-details">
          <summary>{isAr ? 'التفاصيل' : 'Details'}</summary>
          <pre>{error.message}</pre>
        </details>
        <div className="error-boundary-actions">
          <button type="button" className="btn btn-primary" onClick={reset}>
            {isAr ? 'حاول مرة تانية' : 'Try again'}
          </button>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => window.location.reload()}
          >
            {isAr ? 'حدث الصفحة' : 'Reload page'}
          </button>
        </div>
      </div>
    </div>
  );
}
