import type { UserBreakdownRow } from '../../../api/types';
import { useT } from '../../../i18n';

interface Props {
  items: UserBreakdownRow[];
}

/** Horizontal bar list — used for department & subcategory breakdowns. */
export function BreakdownList({ items }: Props) {
  const { t } = useT();
  if (items.length === 0) {
    return <div className="udash-empty">{t('user.dashboard.emptyBreakdown')}</div>;
  }
  const max = Math.max(1, ...items.map((i) => i.count));

  return (
    <div className="udash-breakdown">
      {items.map((item) => {
        const pct = (item.count / max) * 100;
        return (
          <div key={item.id} className="udash-breakdown-row">
            <div className="udash-breakdown-head">
              <span className="udash-breakdown-name">{item.name}</span>
              <span className="udash-breakdown-value">{item.count}</span>
            </div>
            <div className="udash-breakdown-track">
              <div className="udash-breakdown-fill" style={{ width: `${pct}%` }} />
            </div>
          </div>
        );
      })}
    </div>
  );
}
