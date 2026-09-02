import { useEffect, useMemo, useRef, useState } from 'react';
import type { DailyCount } from '../../../api/types';
import { useReducedMotion } from './hooks';

interface Props {
  data: DailyCount[];
  todayIso: string;
  /** Height of the bars area in px. Default 180. */
  height?: number;
  /** Base delay per bar (ms) for the staggered entrance. Default 22. */
  stagger?: number;
}

/**
 * Custom bar chart — no dependencies, matches the app's design language.
 * Bars grow in on mount with a staggered delay. Hovering / focusing a bar
 * dims the others so the hovered value reads clearly.
 */
export function TrendChart({ data, todayIso, height = 180, stagger = 22 }: Props) {
  const reduced = useReducedMotion();
  const [mounted, setMounted] = useState(false);
  const [activeIdx, setActiveIdx] = useState<number | null>(null);
  const rootRef = useRef<HTMLDivElement | null>(null);

  const max = useMemo(() => Math.max(1, ...data.map((d) => d.count)), [data]);

  // Flip to mounted on the next frame so the CSS transition can play.
  useEffect(() => {
    if (reduced) { setMounted(true); return; }
    const id = requestAnimationFrame(() => setMounted(true));
    return () => cancelAnimationFrame(id);
  }, [reduced, data.length]);

  if (data.length === 0) return <div className="udash-empty">لا توجد بيانات</div>;

  const first = data[0]?.date;
  const middle = data[Math.floor(data.length / 2)]?.date;
  const last = data[data.length - 1]?.date;

  // Trend line through the bar tops, drawn after the last bar lands. Coordinates
  // are LTR percentages; CSS mirrors the <svg> under html[dir='rtl'] so the line
  // follows the bars. The dot uses inset-inline-start, so it needs no mirroring.
  const points = data.map((d, i) => {
    const hp = d.count === 0 ? 2 : Math.max((d.count / max) * 100, 4);
    return { x: ((i + 0.5) / data.length) * 100, y: 100 - hp };
  });
  const linePath = points
    .map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(2)} ${p.y.toFixed(2)}`)
    .join(' ');
  const lineDelay = data.length * stagger + 420;
  const lastPoint = points[points.length - 1];

  return (
    <>
      <div
        ref={rootRef}
        className={`udash-trend ${mounted ? 'is-mounted' : ''} ${activeIdx != null ? 'has-active' : ''}`}
        role="img"
        aria-label="Daily submissions trend"
        style={{ height }}
      >
        {data.map((d, i) => {
          const heightPct = (d.count / max) * 100;
          const isToday = d.date === todayIso;
          const isZero = d.count === 0;
          const isActive = activeIdx === i;
          const cls = [
            'udash-trend-bar',
            isToday ? 'today' : '',
            isZero ? 'zero' : '',
            isActive ? 'is-active' : '',
          ].filter(Boolean).join(' ');
          return (
            <button
              type="button"
              key={d.date}
              className={cls}
              style={{
                ['--bar-i' as string]: i,
                ['--bar-h' as string]: isZero ? '4px' : `${Math.max(heightPct, 4)}%`,
                ['--bar-delay' as string]: `${i * stagger}ms`,
              }}
              onMouseEnter={() => setActiveIdx(i)}
              onMouseLeave={() => setActiveIdx((a) => (a === i ? null : a))}
              onFocus={() => setActiveIdx(i)}
              onBlur={() => setActiveIdx((a) => (a === i ? null : a))}
              aria-label={`${d.date}: ${d.count}`}
              title={`${d.date}: ${d.count}`}
            >
              <span className="udash-trend-bar-tip">{d.count} · {shortDate(d.date)}</span>
            </button>
          );
        })}
        {!reduced && data.length > 1 && (
          <div
            className="udash-trend-overlay"
            aria-hidden="true"
            style={{ ['--line-delay' as string]: `${lineDelay}ms` }}
          >
            <svg className="udash-trend-line" viewBox="0 0 100 100" preserveAspectRatio="none">
              <path d={linePath} pathLength={1} vectorEffect="non-scaling-stroke" />
            </svg>
            <span
              className="udash-trend-dot"
              style={{ insetInlineStart: `${lastPoint.x}%`, bottom: `${100 - lastPoint.y}%` }}
            />
          </div>
        )}
      </div>
      <div className="udash-trend-axis">
        <span>{shortDate(first)}</span>
        <span>{shortDate(middle)}</span>
        <span>{shortDate(last)}</span>
      </div>
    </>
  );
}

function shortDate(iso: string | undefined): string {
  if (!iso) return '';
  const [, m, d] = iso.split('-');
  return `${d}/${m}`;
}
