import type { ReactNode } from 'react';
import { IconTrendDown, IconTrendFlat, IconTrendUp } from '../../../components/Icons';

type Accent = 'blue' | 'green' | 'amber' | 'rose' | 'purple' | 'teal';
type TrendDir = 'up' | 'down' | 'flat';

interface Props {
  label: string;
  value: string | number;
  sub?: string;
  icon?: ReactNode;
  accent?: Accent;
  trend?: { dir: TrendDir; text: string };
}

function TrendIcon({ dir }: { dir: TrendDir }) {
  const Cmp = dir === 'up' ? IconTrendUp : dir === 'down' ? IconTrendDown : IconTrendFlat;
  return <Cmp size={14} className="udash-kpi-trend-icon" />;
}

export function KpiCard({ label, value, sub, icon, accent = 'blue', trend }: Props) {
  return (
    <div className="udash-kpi" data-accent={accent}>
      <div className="udash-kpi-head">
        <span>{label}</span>
        {icon && <span className="udash-kpi-icon">{icon}</span>}
      </div>
      <div className="udash-kpi-value">{value}</div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '0.5rem' }}>
        {sub && <span className="udash-kpi-sub">{sub}</span>}
        {trend && (
          <span className={`udash-kpi-trend ${trend.dir}`}>
            {/* Drawn icons rather than ↑/↓/→ glyphs: the arrows rendered at whatever
                weight and baseline the user's fallback font happened to supply, which
                never matched the 1.75px stroke used everywhere else in the app. */}
            <TrendIcon dir={trend.dir} />
            {trend.text}
          </span>
        )}
      </div>
    </div>
  );
}
