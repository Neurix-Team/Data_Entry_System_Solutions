import { useEffect } from 'react';
import type { ReactNode } from 'react';
import { animate, motion, useMotionValue, useReducedMotion, useTransform } from 'motion/react';
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

/** Counts a numeric value up from 0 (then between values) off the React render path —
 *  the MotionValue writes straight to the DOM node, so a 60fps count costs no re-renders.
 *  Ease matches --ease-expo; duration sits at the top of the app's motion scale on
 *  purpose: a number "arriving" is an entrance, and 420ms reads as weight, not lag. */
function CountUpValue({ value }: { value: number }) {
  const reduced = useReducedMotion();
  const mv = useMotionValue(0);
  const text = useTransform(mv, (v) => Math.round(v).toString());
  useEffect(() => {
    if (reduced) {
      mv.set(value);
      return;
    }
    const controls = animate(mv, value, { duration: 0.42, ease: [0.16, 1, 0.3, 1] });
    return () => controls.stop();
  }, [value, reduced, mv]);
  return <motion.span>{text}</motion.span>;
}

export function KpiCard({ label, value, sub, icon, accent = 'blue', trend }: Props) {
  return (
    <div className="udash-kpi" data-accent={accent}>
      <div className="udash-kpi-head">
        <span>{label}</span>
        {icon && <span className="udash-kpi-icon">{icon}</span>}
      </div>
      <div className="udash-kpi-value">
        {typeof value === 'number' ? <CountUpValue value={value} /> : value}
      </div>
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
