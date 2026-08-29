import { useEffect, useMemo, useState } from 'react';
import type { StatusMap } from '../../../api/types';
import { useT } from '../../../i18n';
import { useCountUp, useReducedMotion } from './hooks';

interface Props {
  data: StatusMap;
  /** Outer diameter in px. Default 140. */
  size?: number;
  /** Ring thickness in px. Default 22. */
  stroke?: number;
  /** Show the total in the center. Default true. */
  showTotal?: boolean;
}

const STATUS_ORDER: Array<keyof StatusMap> = ['IN_PROGRESS', 'REVIEW', 'COMPLETED'];
/**
 * Token references, not literals. SVG `stroke` and `background` both resolve `var()`,
 * so the chart re-colours with the theme instead of staying on the light-mode palette —
 * the dark theme lifts these hues for contrast against the navy surface (`--status-progress`
 * goes #0f5fd1 → #6ba3f2) and a hard-coded hex would ignore that.
 */
const STATUS_COLORS: Record<string, string> = {
  IN_PROGRESS: 'var(--status-progress)',
  REVIEW: 'var(--status-review)',
  COMPLETED: 'var(--success)',
};

/**
 * SVG donut chart. Arcs draw in on mount; the center total counts up.
 * Legend items are toggleable — click to hide/show a series, and the
 * remaining segments re-normalize.
 */
export function StatusDonut({ data, size = 140, stroke = 22, showTotal = true }: Props) {
  const { t } = useT();
  const reduced = useReducedMotion();
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const [hovered, setHovered] = useState<string | null>(null);
  const [primed, setPrimed] = useState(false);
  useEffect(() => {
    if (reduced) { setPrimed(true); return; }
    const id = requestAnimationFrame(() => setPrimed(true));
    return () => cancelAnimationFrame(id);
  }, [reduced]);

  const segments = useMemo(
    () => STATUS_ORDER.map((s) => ({
      key: s as string,
      label: t(`status.${s}`),
      value: (data[s] ?? 0) as number,
      color: STATUS_COLORS[s as string],
    })),
    [data, t],
  );

  const visible = segments.filter((s) => !hidden.has(s.key));
  const shownTotal = visible.reduce((sum, s) => sum + s.value, 0);
  const rawTotal = segments.reduce((sum, s) => sum + s.value, 0);
  const displayTotal = useCountUp(shownTotal);

  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;

  let offset = 0;
  const arcs = segments.map((s) => {
    const isHidden = hidden.has(s.key);
    const fraction = shownTotal > 0 && !isHidden ? s.value / shownTotal : 0;
    const length = fraction * circumference;
    const arc = {
      key: s.key,
      color: s.color,
      length,
      dashArray: `${length} ${circumference - length}`,
      dashOffset: -offset,
      isHidden,
    };
    offset += length;
    return arc;
  });

  const toggle = (key: string) => setHidden((prev) => {
    const next = new Set(prev);
    if (next.has(key)) next.delete(key); else next.add(key);
    // Guard: never hide all segments at once.
    if (next.size >= segments.length) next.delete(key);
    return next;
  });

  const isDimmed = (key: string) => hovered != null && hovered !== key;

  return (
    <div className="udash-donut-wrap">
      <div
        className={`udash-donut ${reduced ? 'is-reduced' : ''}`}
        style={{ width: size, height: size }}
      >
        <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true">
          <circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            fill="none"
            stroke="var(--bg-sunken)"
            strokeWidth={stroke}
          />
          {rawTotal > 0 && arcs.map((a) => (
            <circle
              key={a.key}
              className="udash-donut-arc"
              cx={size / 2}
              cy={size / 2}
              r={radius}
              fill="none"
              stroke={a.color}
              strokeWidth={hovered === a.key ? stroke + 3 : stroke}
              strokeLinecap="butt"
              strokeDasharray={a.isHidden || !primed ? `0 ${circumference}` : a.dashArray}
              strokeDashoffset={a.dashOffset}
              transform={`rotate(-90 ${size / 2} ${size / 2})`}
              opacity={a.isHidden ? 0 : isDimmed(a.key) ? 0.35 : 1}
              onMouseEnter={() => setHovered(a.key)}
              onMouseLeave={() => setHovered((h) => (h === a.key ? null : h))}
            />
          ))}
        </svg>
        {showTotal && (
          <div className="udash-donut-center">
            <div className="udash-donut-value">{displayTotal}</div>
            <div className="udash-donut-label">{t('user.dashboard.donutTotal')}</div>
          </div>
        )}
      </div>
      <div className="udash-legend" role="list">
        {segments.map((s) => {
          const isHidden = hidden.has(s.key);
          return (
            <button
              key={s.key}
              type="button"
              className={`udash-legend-item ${isHidden ? 'is-hidden' : ''} ${hovered === s.key ? 'is-hovered' : ''}`}
              role="listitem"
              aria-pressed={!isHidden}
              onClick={() => toggle(s.key)}
              onMouseEnter={() => setHovered(s.key)}
              onMouseLeave={() => setHovered((h) => (h === s.key ? null : h))}
            >
              <span className="udash-legend-dot" style={{ background: s.color }} />
              <span className="udash-legend-label">{s.label}</span>
              <span className="udash-legend-value">{s.value}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
