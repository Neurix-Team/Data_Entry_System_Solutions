import type { ReactNode } from 'react';

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
            {trend.dir === 'up' ? '↑' : trend.dir === 'down' ? '↓' : '→'} {trend.text}
          </span>
        )}
      </div>
    </div>
  );
}
