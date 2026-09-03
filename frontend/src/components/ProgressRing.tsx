import { useId, type ReactNode } from 'react';

export type ProgressRingState = 'idle' | 'uploading' | 'finalizing' | 'done' | 'failed';

interface Props {
  /** 0..1 */
  value: number;
  size?: number;
  stroke?: number;
  state?: ProgressRingState;
  /** Overrides the centre content (defaults to the rounded percentage). */
  label?: ReactNode;
  ariaLabel?: string;
  className?: string;
}

/**
 * Circular progress meter with the percentage in the middle. The arc is a single SVG
 * circle whose dash offset tracks {@code value}, so updates animate through CSS without
 * touching layout. While the server finalizes ("finalizing") the arc becomes a short
 * spinning segment — the client has nothing left to measure but the work isn't done.
 * On "done" the arc fills green and a check draws itself in the centre.
 */
export function ProgressRing({
  value, size = 48, stroke = 4, state = 'uploading', label, ariaLabel, className,
}: Props) {
  const rawId = useId();
  const gradId = `pring-grad-${rawId.replace(/[^a-zA-Z0-9_-]/g, '')}`;
  const v = Number.isFinite(value) ? Math.min(1, Math.max(0, value)) : 0;
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const shown = state === 'done' ? 1 : state === 'finalizing' ? 0.28 : v;
  const pct = Math.round(v * 100);
  const fontSize = Math.max(10, Math.round(size * 0.27));

  return (
    <div
      className={`pring is-${state}${className ? ` ${className}` : ''}`}
      style={{ width: size, height: size }}
      role="progressbar"
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={state === 'done' ? 100 : pct}
      aria-label={ariaLabel}
    >
      <svg viewBox={`0 0 ${size} ${size}`} width={size} height={size} aria-hidden="true" focusable="false">
        <defs>
          <linearGradient id={gradId} x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="var(--brand)" />
            <stop offset="100%" stopColor="var(--accent-cyan)" />
          </linearGradient>
        </defs>
        <circle className="pring-track" cx={size / 2} cy={size / 2} r={r} strokeWidth={stroke} />
        <circle
          className="pring-arc"
          cx={size / 2}
          cy={size / 2}
          r={r}
          strokeWidth={stroke}
          stroke={`url(#${gradId})`}
          strokeDasharray={c}
          strokeDashoffset={c * (1 - shown)}
          strokeLinecap="round"
          data-motion={state === 'finalizing' ? 'essential' : undefined}
        />
      </svg>
      <div className="pring-label" style={{ fontSize }}>
        {label ?? (
          state === 'done'
            ? <CheckMark size={Math.round(size * 0.42)} />
            : state === 'failed'
              ? '!'
              : `${pct}%`
        )}
      </div>
    </div>
  );
}

function CheckMark({ size }: { size: number }) {
  return (
    <svg className="upload-check" width={size} height={size} viewBox="0 0 14 14" fill="none" aria-hidden="true">
      <circle cx="7" cy="7" r="6.4" fill="var(--success)" />
      <path d="M4 7.3l2.1 2.1L10.2 5" stroke="var(--bg-surface)" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
