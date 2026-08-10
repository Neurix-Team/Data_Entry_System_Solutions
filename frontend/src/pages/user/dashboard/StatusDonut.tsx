import type { StatusMap } from '../../../api/types';
import { useT } from '../../../i18n';

interface Props {
  data: StatusMap;
}

const STATUS_ORDER: Array<keyof StatusMap> = ['IN_PROGRESS', 'REVIEW', 'COMPLETED'];
const STATUS_COLORS: Record<string, string> = {
  IN_PROGRESS: '#0f5fd1', // status-progress (electric blue)
  REVIEW: '#22c3d9',      // status-review (cyan)
  COMPLETED: '#0e9f7c',   // success
};

/** SVG donut chart for the three ticket statuses. */
export function StatusDonut({ data }: Props) {
  const { t } = useT();
  const segments = STATUS_ORDER.map((s) => ({
    key: s as string,
    label: t(`status.${s}`),
    value: (data[s] ?? 0) as number,
    color: STATUS_COLORS[s as string],
  }));
  const total = segments.reduce((sum, s) => sum + s.value, 0);

  const size = 140;
  const stroke = 22;
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;

  let offset = 0;
  const arcs = segments.map((s) => {
    const fraction = total > 0 ? s.value / total : 0;
    const length = fraction * circumference;
    const arc = {
      color: s.color,
      strokeDasharray: `${length} ${circumference - length}`,
      strokeDashoffset: -offset,
    };
    offset += length;
    return arc;
  });

  return (
    <div className="udash-donut-wrap">
      <div className="udash-donut">
        <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
          {/* Track */}
          <circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            fill="none"
            stroke="var(--bg-sunken)"
            strokeWidth={stroke}
          />
          {total > 0 && arcs.map((a, i) => (
            <circle
              key={i}
              cx={size / 2}
              cy={size / 2}
              r={radius}
              fill="none"
              stroke={a.color}
              strokeWidth={stroke}
              strokeDasharray={a.strokeDasharray}
              strokeDashoffset={a.strokeDashoffset}
              transform={`rotate(-90 ${size / 2} ${size / 2})`}
              style={{ transition: 'stroke-dasharray 0.4s ease' }}
            />
          ))}
        </svg>
        <div className="udash-donut-center">
          <div className="udash-donut-value">{total}</div>
          <div className="udash-donut-label">{t('user.dashboard.donutTotal')}</div>
        </div>
      </div>
      <div className="udash-legend">
        {segments.map((s) => (
          <div key={s.key} className="udash-legend-item">
            <span className="udash-legend-dot" style={{ background: s.color }} />
            <span className="udash-legend-label">{s.label}</span>
            <span className="udash-legend-value">{s.value}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
