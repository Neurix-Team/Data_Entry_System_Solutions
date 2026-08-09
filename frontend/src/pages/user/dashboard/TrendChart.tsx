import { useMemo } from 'react';
import type { DailyCount } from '../../../api/types';

interface Props {
  data: DailyCount[];
  /** Show only these labels on the x-axis (indices). Defaults to first/mid/last. */
  todayIso: string;
}

/** Custom bar chart — no dependencies, matches the app's design language. */
export function TrendChart({ data, todayIso }: Props) {
  const max = useMemo(() => Math.max(1, ...data.map((d) => d.count)), [data]);

  if (data.length === 0) return <div className="udash-empty">لا توجد بيانات</div>;

  const first = data[0]?.date;
  const middle = data[Math.floor(data.length / 2)]?.date;
  const last = data[data.length - 1]?.date;

  return (
    <>
      <div className="udash-trend" role="img" aria-label="Daily submissions trend">
        {data.map((d) => {
          const heightPct = (d.count / max) * 100;
          const isToday = d.date === todayIso;
          const isZero = d.count === 0;
          const cls = `udash-trend-bar ${isToday ? 'today' : ''} ${isZero ? 'zero' : ''}`.trim();
          return (
            <div
              key={d.date}
              className={cls}
              style={{ height: isZero ? undefined : `${Math.max(heightPct, 4)}%` }}
              title={`${d.date}: ${d.count}`}
            >
              <span className="udash-trend-bar-tip">{d.count} · {shortDate(d.date)}</span>
            </div>
          );
        })}
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
